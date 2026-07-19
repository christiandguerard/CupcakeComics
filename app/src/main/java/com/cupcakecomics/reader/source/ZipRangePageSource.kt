package com.cupcakecomics.reader.source

import android.graphics.BitmapFactory
import com.cupcakecomics.reader.model.PageDescriptor
import com.cupcakecomics.reader.model.TocEntry
import com.nkanaev.comics.managers.Utils
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream

/**
 * ZIP/CBZ reader that only fetches the central directory and per-page byte ranges.
 */
class ZipRangePageSource(
    private val source: SeekableByteSource,
    override val title: String,
    override val remoteStreaming: Boolean = false,
) : PageSource {
    data class ZipEntryInfo(
        val name: String,
        val compressionMethod: Int,
        val compressedSize: Long,
        val uncompressedSize: Long,
        val localHeaderOffset: Long,
        val crc32: Long,
        val generalPurposeFlag: Int,
    )

    private var entries: List<ZipEntryInfo> = emptyList()
    private var imageEntries: List<ZipEntryInfo> = emptyList()
    private val descriptorCache = java.util.concurrent.ConcurrentHashMap<Int, PageDescriptor>()
    private var opened = false

    override val type: String get() = "cbz-range"

    @Throws(Exception::class)
    override fun open() {
        if (opened) return
        entries = parseCentralDirectory(source)
        imageEntries = entries
            .filter { !it.name.contains("__MACOSX/") }
            .filter { Utils.isImage(it.name.substringAfterLast('/')) }
            .sortedBy { it.name.lowercase() }
        if (imageEntries.isEmpty()) throw IOException("No images in archive")
        opened = true
    }

    @Throws(Exception::class)
    override fun pageCount(): Int {
        open()
        return imageEntries.size
    }

    @Throws(Exception::class)
    override fun descriptor(index: Int): PageDescriptor {
        open()
        descriptorCache[index]?.let { return it }
        val entry = imageEntries[index]
        val folder = entry.name.substringBeforeLast('/', missingDelimiterValue = "")
        // Probe dimensions from a bounded prefix of the inflated bytes.
        val probe = readEntryBytes(entry, maxUncompressed = 512 * 1024)
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(probe, 0, probe.size, opts)
        val desc = PageDescriptor(
            index = index,
            name = entry.name.substringAfterLast('/'),
            mime = opts.outMimeType,
            width = opts.outWidth.coerceAtLeast(0),
            height = opts.outHeight.coerceAtLeast(0),
            sizeBytes = entry.uncompressedSize,
            folder = folder,
        )
        descriptorCache[index] = desc
        return desc
    }

    @Throws(Exception::class)
    override fun openPage(index: Int): InputStream {
        open()
        val entry = imageEntries[index]
        val bytes = readEntryBytes(entry, maxUncompressed = entry.uncompressedSize.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
        // CRC check when we have the full payload
        if (entry.uncompressedSize in 1 until Int.MAX_VALUE.toLong() && bytes.size.toLong() == entry.uncompressedSize) {
            val crc = CRC32()
            crc.update(bytes)
            if (crc.value != (entry.crc32 and 0xffffffffL) && entry.crc32 != 0L) {
                throw IOException("CRC mismatch for ${entry.name}")
            }
        }
        return ByteArrayInputStream(bytes)
    }

    override fun tableOfContents(): List<TocEntry> {
        val count = runCatching { pageCount() }.getOrDefault(0)
        val folders = LinkedHashMap<String, Int>()
        for (i in 0 until count) {
            val folder = imageEntries[i].name.substringBeforeLast('/', missingDelimiterValue = "")
            if (folder.isNotBlank()) folders.putIfAbsent(folder, i)
        }
        if (folders.size <= 1) return emptyList()
        return folders.map { (folder, page) ->
            TocEntry(title = folder.substringAfterLast('/').ifBlank { folder }, pageIndex = page)
        }
    }

    override fun close() {
        runCatching { source.close() }
        descriptorCache.clear()
        opened = false
    }

    @Throws(IOException::class)
    private fun readEntryBytes(entry: ZipEntryInfo, maxUncompressed: Int): ByteArray {
        // Local file header is 30 bytes + filename + extra
        val local = source.readFully(entry.localHeaderOffset, 30)
        val bb = ByteBuffer.wrap(local).order(ByteOrder.LITTLE_ENDIAN)
        val sig = bb.int
        if (sig != 0x04034b50) throw IOException("Bad local header signature")
        bb.short // version
        val flags = bb.short.toInt() and 0xffff
        val method = bb.short.toInt() and 0xffff
        bb.short; bb.short // time/date
        bb.int // crc
        var compSize = bb.int.toLong() and 0xffffffffL
        var uncompSize = bb.int.toLong() and 0xffffffffL
        val nameLen = bb.short.toInt() and 0xffff
        val extraLen = bb.short.toInt() and 0xffff
        // Skip name+extra
        val dataOffset = entry.localHeaderOffset + 30 + nameLen + extraLen

        // Prefer central directory sizes; local may be zero with data descriptor.
        if (compSize == 0L || (flags and 0x08) != 0) {
            compSize = entry.compressedSize
            uncompSize = entry.uncompressedSize
        }
        if (method != entry.compressionMethod && entry.compressionMethod != method) {
            // trust local method
        }
        if ((flags and 0x01) != 0) throw IOException("Encrypted ZIP entries are not supported")

        val compressed = source.readFully(dataOffset, compSize.toInt().coerceAtLeast(0))
        return when (method) {
            0 -> { // stored
                if (compressed.size > maxUncompressed) compressed.copyOf(maxUncompressed) else compressed
            }
            8 -> { // deflate
                inflate(compressed, maxUncompressed)
            }
            else -> throw IOException("Unsupported compression method $method")
        }
    }

    private fun inflate(compressed: ByteArray, maxUncompressed: Int): ByteArray {
        val inflater = Inflater(true) // ZIP uses raw deflate sometimes; try nowrap=false first below
        // Standard ZIP deflate is zlib-wrapped? Actually ZIP uses raw deflate (nowrap=true).
        inflater.setInput(compressed)
        val out = ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        try {
            while (!inflater.finished() && out.size() < maxUncompressed) {
                val n = inflater.inflate(buf)
                if (n == 0) {
                    if (inflater.needsInput()) break
                    if (inflater.needsDictionary()) throw IOException("Dictionary required")
                } else {
                    val remain = maxUncompressed - out.size()
                    out.write(buf, 0, minOf(n, remain))
                }
            }
        } catch (e: Exception) {
            // Retry with nowrap=false
            inflater.end()
            return InflaterInputStream(ByteArrayInputStream(compressed)).use { stream ->
                val o = ByteArrayOutputStream()
                val b = ByteArray(16 * 1024)
                var total = 0
                while (total < maxUncompressed) {
                    val n = stream.read(b)
                    if (n < 0) break
                    val w = minOf(n, maxUncompressed - total)
                    o.write(b, 0, w)
                    total += w
                }
                o.toByteArray()
            }
        } finally {
            inflater.end()
        }
        return out.toByteArray()
    }

    companion object {
        private const val EOCD_SIG = 0x06054b50
        private const val ZIP64_LOCATOR_SIG = 0x07064b50
        private const val ZIP64_EOCD_SIG = 0x06064b50
        private const val CEN_SIG = 0x02014b50

        @Throws(IOException::class)
        fun parseCentralDirectory(source: SeekableByteSource): List<ZipEntryInfo> {
            val len = source.length()
            if (len < 22) throw IOException("File too small to be a ZIP")
            val scan = minOf(len, 64 * 1024L + 22)
            val tail = source.readFully(len - scan, scan.toInt())
            var eocd = -1
            for (i in tail.size - 22 downTo 0) {
                if (u32(tail, i) == EOCD_SIG.toLong()) {
                    eocd = i
                    break
                }
            }
            if (eocd < 0) throw IOException("EOCD not found")

            val diskEntries = u16(tail, eocd + 8)
            val totalEntries = u16(tail, eocd + 10)
            var cdSize = u32(tail, eocd + 12)
            var cdOffset = u32(tail, eocd + 16)
            val commentLen = u16(tail, eocd + 20)

            // ZIP64
            if (cdOffset == 0xffffffffL || cdSize == 0xffffffffL || totalEntries == 0xffff) {
                // Look for ZIP64 end of central directory locator just before EOCD
                val locPos = eocd - 20
                if (locPos >= 0 && u32(tail, locPos) == ZIP64_LOCATOR_SIG.toLong()) {
                    val zip64EocdOffset = u64(tail, locPos + 8)
                    val z64 = source.readFully(zip64EocdOffset, 56)
                    if (u32(z64, 0) != ZIP64_EOCD_SIG.toLong()) {
                        throw IOException("Bad ZIP64 EOCD")
                    }
                    cdSize = u64(z64, 40)
                    cdOffset = u64(z64, 48)
                }
            }

            if (cdSize > Int.MAX_VALUE) throw IOException("Central directory too large")
            val cd = source.readFully(cdOffset, cdSize.toInt())
            val list = ArrayList<ZipEntryInfo>()
            var pos = 0
            while (pos + 46 <= cd.size) {
                if (u32(cd, pos).toInt() != CEN_SIG) break
                val flags = u16(cd, pos + 8)
                val method = u16(cd, pos + 10)
                val crc = u32(cd, pos + 16)
                var comp = u32(cd, pos + 20)
                var uncomp = u32(cd, pos + 24)
                val nameLen = u16(cd, pos + 28)
                val extraLen = u16(cd, pos + 30)
                val commentLen2 = u16(cd, pos + 32)
                var localOff = u32(cd, pos + 42)
                val nameBytes = cd.copyOfRange(pos + 46, pos + 46 + nameLen)
                val name = String(nameBytes, Charsets.UTF_8)
                // ZIP64 extra
                var extraPos = pos + 46 + nameLen
                val extraEnd = extraPos + extraLen
                if (comp == 0xffffffffL || uncomp == 0xffffffffL || localOff == 0xffffffffL) {
                    while (extraPos + 4 <= extraEnd) {
                        val id = u16(cd, extraPos)
                        val sz = u16(cd, extraPos + 2)
                        if (id == 0x0001 && extraPos + 4 + sz <= extraEnd) {
                            var p = extraPos + 4
                            if (uncomp == 0xffffffffL && p + 8 <= extraPos + 4 + sz) {
                                uncomp = u64(cd, p); p += 8
                            }
                            if (comp == 0xffffffffL && p + 8 <= extraPos + 4 + sz) {
                                comp = u64(cd, p); p += 8
                            }
                            if (localOff == 0xffffffffL && p + 8 <= extraPos + 4 + sz) {
                                localOff = u64(cd, p)
                            }
                        }
                        extraPos += 4 + sz
                    }
                }
                if (!name.endsWith("/")) {
                    list.add(
                        ZipEntryInfo(
                            name = name,
                            compressionMethod = method,
                            compressedSize = comp,
                            uncompressedSize = uncomp,
                            localHeaderOffset = localOff,
                            crc32 = crc,
                            generalPurposeFlag = flags,
                        ),
                    )
                }
                pos += 46 + nameLen + extraLen + commentLen2
            }
            if (list.isEmpty() && totalEntries > 0) {
                throw IOException("Failed to parse central directory")
            }
            // silence unused
            @Suppress("UNUSED_VARIABLE")
            val unused = diskEntries to commentLen
            return list
        }

        private fun u16(b: ByteArray, off: Int): Int {
            if (off + 2 > b.size) throw EOFException()
            return (b[off].toInt() and 0xff) or ((b[off + 1].toInt() and 0xff) shl 8)
        }

        private fun u32(b: ByteArray, off: Int): Long {
            if (off + 4 > b.size) throw EOFException()
            return (b[off].toLong() and 0xff) or
                ((b[off + 1].toLong() and 0xff) shl 8) or
                ((b[off + 2].toLong() and 0xff) shl 16) or
                ((b[off + 3].toLong() and 0xff) shl 24)
        }

        private fun u64(b: ByteArray, off: Int): Long {
            if (off + 8 > b.size) throw EOFException()
            return u32(b, off) or (u32(b, off + 4) shl 32)
        }

        fun isZipName(name: String): Boolean {
            val lower = name.lowercase()
            return lower.endsWith(".cbz") || lower.endsWith(".zip")
        }
    }
}
