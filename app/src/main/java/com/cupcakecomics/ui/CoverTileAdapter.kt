package com.cupcakecomics.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.nkanaev.comics.R
import com.nkanaev.comics.view.CoverImageView

/**
 * Shared cover-tile grid adapter over R.layout.item_offline_cover_tile.
 * Library sections supply data accessors and cover binding; the adapter
 * owns title visibility, the read badge, and the selection overlay.
 */
class CoverTileAdapter<T>(
    private val titleOf: (T) -> String,
    private val bindCover: (VH, T) -> Unit,
    private val idOf: (T) -> Long = { 0L },
    private val readKeyOf: (T) -> String? = { null },
    private val hideTitles: () -> Boolean = { false },
    private val onClick: (T) -> Unit,
    private val onLongClick: (T) -> Boolean,
) : RecyclerView.Adapter<CoverTileAdapter.VH>() {

    private var items: List<T> = emptyList()
    private var selected: Set<Long> = emptySet()
    private var selecting = false
    private var readKeys: Set<String> = emptySet()

    fun submit(
        next: List<T>,
        sel: Set<Long> = emptySet(),
        selecting: Boolean = false,
        readKeys: Set<String> = emptySet(),
    ) {
        items = next
        selected = sel.toSet()
        this.selecting = selecting
        this.readKeys = readKeys
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_offline_cover_tile, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        val displayTitle = if (hideTitles()) "" else titleOf(item)
        holder.title.text = displayTitle
        holder.title.visibility = if (displayTitle.isBlank()) View.GONE else View.VISIBLE
        val readKey = readKeyOf(item)
        holder.readCheck.visibility =
            if (readKey != null && readKey in readKeys) View.VISIBLE else View.GONE
        holder.selected.visibility =
            if (selecting && idOf(item) in selected) View.VISIBLE else View.GONE
        bindCover(holder, item)
        holder.itemView.setOnClickListener { onClick(item) }
        holder.itemView.setOnLongClickListener { onLongClick(item) }
    }

    override fun getItemCount(): Int = items.size

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val cover: CoverImageView = view.findViewById(R.id.offline_cover)
        val title: TextView = view.findViewById(R.id.offline_title)
        val readCheck: ImageView = view.findViewById(R.id.offline_read_check)
        val selected: View = view.findViewById(R.id.offline_selected)
    }
}
