package com.cupcakecomics.reader.ui

import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.cupcakecomics.reader.ReaderViewModel
import com.cupcakecomics.reader.gl.PageDecodeCache
import com.cupcakecomics.reader.model.FitMode
import com.cupcakecomics.reader.model.OrientationContext
import com.cupcakecomics.reader.model.ReaderSession

/**
 * One page (slot) per ViewPager2 item. Prefers cached bitmaps to avoid blank frames while scrolling.
 */
class PagedSlotAdapter(
    private val viewModel: ReaderViewModel,
    private val decodeCache: PageDecodeCache,
    private val isScreenPortrait: () -> Boolean,
    private val onCenterTap: () -> Unit,
    private val onEdgeTap: (forward: Boolean) -> Unit,
) : RecyclerView.Adapter<PagedSlotAdapter.VH>() {

    private var session: ReaderSession = ReaderSession()
    private var maxEdge: Int = 2048

    fun submit(s: ReaderSession, displayMaxEdge: Int) {
        val structural = session.slots.size != s.slots.size ||
            session.preferences.colorCorrection != s.preferences.colorCorrection ||
            session.preferences.fit != s.preferences.fit
        session = s
        maxEdge = displayMaxEdge
        if (structural) notifyDataSetChanged()
    }

    override fun getItemCount(): Int = session.slots.size

    override fun getItemId(position: Int): Long =
        session.slots.getOrNull(position)?.let {
            (it.primaryPage.toLong() shl 16) or ((it.secondaryPage ?: -1).toLong() and 0xffff)
        } ?: position.toLong()

    init {
        setHasStableIds(true)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val iv = ZoomablePageView(parent.context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            setBackgroundColor(0xFFFFFFFF.toInt())
            setLayerType(View.LAYER_TYPE_HARDWARE, null)
            isClickable = true
        }
        return VH(iv)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val slot = session.slots.getOrNull(position) ?: return
        val source = viewModel.pageSourceOrNull() ?: return
        val fit = session.preferences.fit.modeFor(
            OrientationContext(
                isScreenPortrait(),
                slot.aspectRatio < 1f,
            ),
        )
        holder.image.configure(fit, onCenterTap, onEdgeTap)
        holder.bindGeneration++
        val gen = holder.bindGeneration
        val key = decodeCache.cacheKey(slot, session.preferences.colorCorrection, maxEdge)
        val cached = decodeCache.get(key)
        if (cached != null) {
            holder.image.setImageBitmap(cached)
            holder.boundKey = key
            decodeCache.pin(key)
        }
        if (cached != null) return
        decodeCache.request(
            source = source,
            slot = slot,
            descriptors = session.descriptors,
            color = session.preferences.colorCorrection,
            maxEdge = maxEdge,
            pinOnReady = true,
        ) { decoded ->
            if (gen != holder.bindGeneration) return@request
            if (decoded.bitmap.isRecycled) return@request
            if (holder.bindingAdapterPosition != position) return@request
            holder.image.setImageBitmap(decoded.bitmap)
            holder.image.setBackgroundColor(decoded.matteColor)
            holder.boundKey = decoded.key
        }
    }

    override fun onViewRecycled(holder: VH) {
        holder.boundKey?.let { decodeCache.unpin(it) }
        holder.boundKey = null
        holder.image.resetZoom(animated = false)
        holder.bindGeneration++
        super.onViewRecycled(holder)
    }

    class VH(val image: ZoomablePageView) : RecyclerView.ViewHolder(image) {
        var bindGeneration: Int = 0
        var boundKey: String? = null
    }
}
