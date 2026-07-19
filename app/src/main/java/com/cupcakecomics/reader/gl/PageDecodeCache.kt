package com.cupcakecomics.reader.gl

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Rect
import android.util.LruCache
import com.cupcakecomics.reader.model.ColorCorrection
import com.cupcakecomics.reader.model.PageDescriptor
import com.cupcakecomics.reader.model.PageSlot
import com.cupcakecomics.reader.source.PageSource
import com.nkanaev.comics.managers.Utils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max

/**
 * Decodes pages into bitmaps sized for the display, with optional color correction.
 *
 * Fast-scroll safety:
 * - Multiple keys can stay pinned (visible + neighbors) so LRU never recycles them.
 * - Evicted bitmaps are not recycled; GC reclaims them (avoids "recycled bitmap" crashes).
 * - Concurrent decode is capped so flings cannot OOM the process.
 * - Inflight requests fan out to every waiter for the same key.
 */
class PageDecodeCache(
    private val context: Context,
    private val scope: CoroutineScope,
) {
    private val maxBytes: Int = (Runtime.getRuntime().maxMemory() / 8).toInt().coerceIn(
        24 * 1024 * 1024,
        96 * 1024 * 1024,
    )

    private val pinnedKeys = ConcurrentHashMap.newKeySet<String>()
    private val decodeSemaphore = Semaphore(permits = 3)

    private val cache = object : LruCache<String, Bitmap>(maxBytes) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
        override fun entryRemoved(evicted: Boolean, key: String, old: Bitmap, new: Bitmap?) {
            // Do not recycle: ImageViews / ViewHolders may still reference the bitmap briefly.
            // Letting the GC reclaim avoids Canvas recycled-bitmap crashes under fling.
        }
    }

    private val inflight = ConcurrentHashMap<String, Job>()
    private val waiters = ConcurrentHashMap<String, MutableList<(DecodedPage) -> Unit>>()
    private val mutex = Mutex()

    data class DecodedPage(
        val bitmap: Bitmap,
        val matteColor: Int,
        val key: String,
    )

    fun cacheKey(slot: PageSlot, color: ColorCorrection, maxEdge: Int): String {
        val pages = slot.pageIndices.joinToString("-")
        val half = slot.splitHalf?.toString() ?: "f"
        return "s${slot.slotIndex}:$pages:$half:$maxEdge:${color.hashCode()}"
    }

    /** Replace the pinned set (typically visible page + nearby slots). */
    fun pinKeys(keys: Collection<String>) {
        pinnedKeys.clear()
        pinnedKeys.addAll(keys)
    }

    fun pin(key: String) {
        pinnedKeys.add(key)
    }

    fun unpin(key: String) {
        pinnedKeys.remove(key)
    }

    fun get(key: String): Bitmap? {
        val b = cache.get(key)
        return if (b != null && !b.isRecycled) b else null
    }

    fun request(
        source: PageSource,
        slot: PageSlot,
        descriptors: List<PageDescriptor>,
        color: ColorCorrection,
        maxEdge: Int,
        pinOnReady: Boolean = true,
        onReady: (DecodedPage) -> Unit,
    ) {
        val key = cacheKey(slot, color, maxEdge)
        get(key)?.let { bmp ->
            if (pinOnReady) pin(key)
            onReady(DecodedPage(bmp, com.cupcakecomics.reader.PageBorderColor.fromBitmap(bmp), key))
            return
        }

        waiters.compute(key) { _, list ->
            val next = list ?: mutableListOf()
            synchronized(next) { next.add(onReady) }
            next
        }

        if (inflight.containsKey(key)) return

        val job = scope.launch(Dispatchers.IO) {
            try {
                decodeSemaphore.withPermit {
                    val cached = get(key)
                    if (cached != null) {
                        dispatchReady(key, DecodedPage(
                            cached,
                            com.cupcakecomics.reader.PageBorderColor.fromBitmap(cached),
                            key,
                        ), pinOnReady)
                        return@withPermit
                    }
                    val bmp = decodeSlot(source, slot, descriptors, color, maxEdge)
                    if (bmp == null) {
                        android.util.Log.e("PageDecodeCache", "decode returned null for slot ${slot.slotIndex}")
                        clearWaiters(key)
                        return@withPermit
                    }
                    mutex.withLock { cache.put(key, bmp) }
                    val matte = com.cupcakecomics.reader.PageBorderColor.fromBitmap(bmp)
                    dispatchReady(key, DecodedPage(bmp, matte, key), pinOnReady)
                }
            } catch (t: Throwable) {
                android.util.Log.e("PageDecodeCache", "decode failed slot=${slot.slotIndex}", t)
                clearWaiters(key)
            } finally {
                inflight.remove(key)
            }
        }
        inflight[key] = job
    }

    private suspend fun dispatchReady(key: String, page: DecodedPage, pinOnReady: Boolean) {
        val listeners = waiters.remove(key).orEmpty()
        withContext(Dispatchers.Main) {
            if (pinOnReady) pin(key)
            if (page.bitmap.isRecycled) return@withContext
            listeners.forEach { listener ->
                runCatching { listener(page) }
            }
        }
    }

    private fun clearWaiters(key: String) {
        waiters.remove(key)
    }

    fun cancelAll() {
        inflight.values.forEach { it.cancel() }
        inflight.clear()
        waiters.clear()
    }

    fun trim() {
        cache.trimToSize(maxBytes / 2)
    }

    fun clear() {
        cancelAll()
        pinnedKeys.clear()
        cache.evictAll()
    }

    private fun decodeSlot(
        source: PageSource,
        slot: PageSlot,
        descriptors: List<PageDescriptor>,
        color: ColorCorrection,
        maxEdge: Int,
    ): Bitmap? {
        return when (slot.kind) {
            com.cupcakecomics.reader.model.SlotKind.DOUBLE -> {
                val left = decodePage(source, slot.primaryPage, descriptors, color, maxEdge)
                    ?: return null
                val rightIdx = slot.secondaryPage ?: return left
                val right = decodePage(source, rightIdx, descriptors, color, maxEdge)
                    ?: return left
                combineSideBySide(left, right)
            }
            com.cupcakecomics.reader.model.SlotKind.SPLIT_HALF -> {
                val full = decodePage(source, slot.primaryPage, descriptors, color, maxEdge * 2)
                    ?: return null
                cropHalf(full, slot.splitHalf ?: 0)
            }
            else -> decodePage(source, slot.primaryPage, descriptors, color, maxEdge)
        }
    }

    private fun decodePage(
        source: PageSource,
        index: Int,
        descriptors: List<PageDescriptor>,
        color: ColorCorrection,
        maxEdge: Int,
    ): Bitmap? {
        val desc = descriptors.getOrNull(index)
        val rotation = desc?.rotationDegrees ?: 0
        source.openPage(index).use { input ->
            val bounded = input.readBytes()
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bounded, 0, bounded.size, bounds)
            val opts = BitmapFactory.Options().apply {
                inSampleSize = calculateSampleSize(bounds.outWidth, bounds.outHeight, maxEdge)
                // RGB_565 is half the memory and much smoother to scroll when color correction is off.
                inPreferredConfig = if (color.isIdentity) Bitmap.Config.RGB_565 else Bitmap.Config.ARGB_8888
            }
            var bmp = BitmapFactory.decodeByteArray(bounded, 0, bounded.size, opts) ?: return null
            if (rotation != 0) {
                val m = android.graphics.Matrix().apply { postRotate(rotation.toFloat()) }
                val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
                if (rotated != bmp) {
                    bmp = rotated
                }
            }
            val longest = max(bmp.width, bmp.height)
            if (longest > maxEdge && maxEdge > 0) {
                val scale = maxEdge.toFloat() / longest
                val w = (bmp.width * scale).toInt().coerceAtLeast(1)
                val h = (bmp.height * scale).toInt().coerceAtLeast(1)
                val scaled = Bitmap.createScaledBitmap(bmp, w, h, true)
                if (scaled != bmp) {
                    bmp = scaled
                }
            }
            if (!color.isIdentity) {
                applyColorCorrection(bmp, color)
            }
            if (desc != null && (desc.cropLeft + desc.cropTop + desc.cropRight + desc.cropBottom) > 0.001f) {
                val l = (bmp.width * desc.cropLeft).toInt()
                val t = (bmp.height * desc.cropTop).toInt()
                val r = (bmp.width * (1f - desc.cropRight)).toInt()
                val b = (bmp.height * (1f - desc.cropBottom)).toInt()
                if (r > l && b > t) {
                    val cropped = Bitmap.createBitmap(bmp, l, t, r - l, b - t)
                    if (cropped != bmp) {
                        bmp = cropped
                    }
                }
            }
            return bmp
        }
    }

    private fun applyColorCorrection(bmp: Bitmap, color: ColorCorrection) {
        val w = bmp.width
        val h = bmp.height
        val pixels = IntArray(w * h)
        bmp.getPixels(pixels, 0, w, 0, 0, w, h)
        val gr = color.gammaR
        val gg = if (color.linkGamma) color.gammaR else color.gammaG
        val gb = if (color.linkGamma) color.gammaR else color.gammaB
        NativeImageBridge.colorCorrect(
            pixels, w, h,
            color.whiteBalance, color.whiteBalanceHardness, color.vibrance,
            gr, gg, gb,
        )
        bmp.setPixels(pixels, 0, w, 0, 0, w, h)
    }

    private fun combineSideBySide(left: Bitmap, right: Bitmap): Bitmap {
        val h = max(left.height, right.height)
        val scaleL = h.toFloat() / left.height
        val scaleR = h.toFloat() / right.height
        val lw = (left.width * scaleL).toInt()
        val rw = (right.width * scaleR).toInt()
        val out = Bitmap.createBitmap(lw + rw, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawBitmap(left, null, Rect(0, 0, lw, h), null)
        canvas.drawBitmap(right, null, Rect(lw, 0, lw + rw, h), null)
        return out
    }

    private fun cropHalf(full: Bitmap, half: Int): Bitmap {
        val mid = full.width / 2
        return if (half == 0) {
            Bitmap.createBitmap(full, 0, 0, mid.coerceAtLeast(1), full.height)
        } else {
            Bitmap.createBitmap(full, mid, 0, (full.width - mid).coerceAtLeast(1), full.height)
        }
    }

    private fun calculateSampleSize(w: Int, h: Int, maxEdge: Int): Int {
        if (w <= 0 || h <= 0 || maxEdge <= 0) return 1
        var sample = 1
        val longest = max(w, h)
        while (longest / sample > maxEdge * 2) {
            sample *= 2
        }
        val maxMem = Utils.bitmapMaxMemorySize()
        while ((w / sample).toLong() * (h / sample) * 4L > maxMem && sample < 64) {
            sample *= 2
        }
        return sample
    }
}
