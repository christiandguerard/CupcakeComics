package com.cupcakecomics.ui

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.view.ActionMode
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.cupcakecomics.cover.FileCoverHandler
import com.cupcakecomics.data.LibraryRepository
import com.cupcakecomics.data.OfflineComicEntity
import com.cupcakecomics.data.ReadMarkEntity
import com.cupcakecomics.settings.CupcakeSettings
import com.cupcakecomics.smb.ComicFileNames
import com.nkanaev.comics.R
import com.nkanaev.comics.activity.MainActivity
import com.nkanaev.comics.activity.ReaderActivity
import com.nkanaev.comics.fragment.ReaderFragment
import com.nkanaev.comics.managers.Utils
import com.nkanaev.comics.view.CoverImageView
import com.squareup.picasso.Picasso
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.Serializable

/**
 * Collapsible tiled cover grid for offline downloads on Library home.
 */
class OfflineLibraryController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val headerRow: View,
    private val header: TextView,
    private val chevron: ImageView,
    private val list: RecyclerView,
    private val onHasOffline: (Boolean) -> Unit,
) {
    private val repo = LibraryRepository(context)
    private val settings = CupcakeSettings(context)
    private val prefs: SharedPreferences =
        context.getSharedPreferences("cupcake_library_ui", Context.MODE_PRIVATE)
    private val picasso: Picasso = (context as MainActivity).picasso
    private val adapter = Adapter(
        picasso = picasso,
        hideTitles = { settings.hideCoverTitles },
        onClick = { comic ->
            if (selectionMode) toggle(comic) else open(comic)
        },
        onLongClick = { comic ->
            if (!selectionMode) startSelection(comic)
            true
        },
    )
    private var collectJob: Job? = null
    private var actionMode: ActionMode? = null
    private var selectionMode = false
    private val selected = linkedSetOf<Long>()
    private var comics: List<OfflineComicEntity> = emptyList()
    private var readKeys: Set<String> = emptySet()
    private var expanded = prefs.getBoolean(PREF_EXPANDED, false)

    init {
        refreshSpan()
        list.adapter = adapter
        list.isNestedScrollingEnabled = false
        list.overScrollMode = View.OVER_SCROLL_NEVER
        headerRow.setOnClickListener { toggleExpanded() }
        applyExpanded()
    }

    fun start() {
        collectJob?.cancel()
        collectJob = scope.launch {
            launch {
                repo.readMarks.collectLatest { marks ->
                    readKeys = marks.map { it.identityKey }.toSet()
                    adapter.submit(comics, selected, selectionMode, readKeys)
                }
            }
            repo.offlineComics.collectLatest { items ->
                comics = items.sortedWith(compareBy { ComicFileNames.librarySortKey(it.title) })
                val visible = comics.isNotEmpty()
                headerRow.visibility = if (visible) View.VISIBLE else View.GONE
                applyExpanded()
                onHasOffline(visible)
                adapter.submit(comics, selected, selectionMode, readKeys)
                actionMode?.title = context.getString(R.string.selection_count, selected.size)
                // Warm any missing covers in the background
                launch(Dispatchers.IO) {
                    comics.forEach { FileCoverHandler.warmCache(it.localPath) }
                }
            }
        }
    }

    fun stop() {
        collectJob?.cancel()
        actionMode?.finish()
    }

    fun refreshTitlesVisibility() {
        adapter.notifyDataSetChanged()
    }

    fun refreshSpan() {
        val span = CoverGridHelper.spanCount(context, settings)
        list.layoutManager = GridLayoutManager(context, span)
    }

    private fun toggleExpanded() {
        expanded = !expanded
        prefs.edit().putBoolean(PREF_EXPANDED, expanded).apply()
        applyExpanded()
    }

    private fun applyExpanded() {
        val showList = comics.isNotEmpty() && expanded
        list.visibility = if (showList) View.VISIBLE else View.GONE
        chevron.setImageResource(
            if (expanded) R.drawable.ic_expand_less_24 else R.drawable.ic_expand_more_24,
        )
        val count = comics.size
        header.text = if (count > 0) {
            context.getString(R.string.library_offline_header) + " ($count)"
        } else {
            context.getString(R.string.library_offline_header)
        }
    }

    private fun open(comic: OfflineComicEntity) {
        val file = File(comic.localPath)
        if (!file.exists()) {
            Toast.makeText(context, R.string.offline_missing, Toast.LENGTH_SHORT).show()
            scope.launch { repo.deleteOffline(listOf(comic.id)) }
            return
        }
        val intent = Intent(context, ReaderActivity::class.java)
        intent.putExtra(ReaderFragment.PARAM_HANDLER, file as Serializable)
        intent.putExtra(ReaderFragment.PARAM_MODE, ReaderFragment.Mode.MODE_BROWSER)
        if (comic.sourceKey.isNotBlank()) {
            intent.putExtra(ReaderFragment.PARAM_IDENTITY_KEY, comic.sourceKey)
        }
        context.startActivity(intent)
    }

    private fun startSelection(comic: OfflineComicEntity) {
        selectionMode = true
        selected.clear()
        selected.add(comic.id)
        adapter.submit(comics, selected, true, readKeys)
        val activity = context as? AppCompatActivity ?: return
        actionMode = activity.startSupportActionMode(callback)
        actionMode?.title = context.getString(R.string.selection_count, selected.size)
    }

    private fun toggle(comic: OfflineComicEntity) {
        if (selected.contains(comic.id)) selected.remove(comic.id) else selected.add(comic.id)
        adapter.submit(comics, selected, selectionMode, readKeys)
        actionMode?.title = context.getString(R.string.selection_count, selected.size)
        if (selected.isEmpty()) actionMode?.finish()
    }

    private val callback = object : ActionMode.Callback {
        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
            mode.menuInflater.inflate(R.menu.comic_selection, menu)
            menu.findItem(R.id.action_download_offline)?.isVisible = false
            return true
        }

        override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean = false

        override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
            val picked = comics.filter { it.id in selected }
            when (item.itemId) {
                R.id.action_mark_read -> {
                    scope.launch {
                        repo.markRead(
                            picked.map {
                                ReadMarkEntity(
                                    identityKey = it.sourceKey,
                                    displayName = it.title,
                                    sourceType = "offline",
                                    sourceDetail = it.localPath,
                                    markedReadAt = System.currentTimeMillis(),
                                )
                            },
                        )
                        Toast.makeText(
                            context,
                            context.getString(R.string.marked_read_toast, picked.size),
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                    mode.finish()
                    return true
                }
                R.id.action_mark_unread -> {
                    scope.launch {
                        repo.unmarkRead(picked.map { it.sourceKey })
                        Toast.makeText(
                            context,
                            context.getString(R.string.marked_unread_toast, picked.size),
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                    mode.finish()
                    return true
                }
                R.id.action_delete_offline -> {
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            picked.forEach { Utils.deleteCoverCacheFile(it.localPath) }
                        }
                        repo.deleteOffline(picked.map { it.id })
                        Toast.makeText(
                            context,
                            context.getString(R.string.deleted_offline_toast, picked.size),
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                    mode.finish()
                    return true
                }
                R.id.action_export_read -> {
                    scope.launch {
                        val json = repo.exportReadMarksJson()
                        val share = Intent(Intent.ACTION_SEND).apply {
                            type = "application/json"
                            putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.export_read_share_title))
                            putExtra(Intent.EXTRA_TEXT, json)
                        }
                        context.startActivity(
                            Intent.createChooser(share, context.getString(R.string.export_read_share_title)),
                        )
                    }
                    return true
                }
                R.id.action_select_all -> {
                    selected.clear()
                    selected.addAll(comics.map { it.id })
                    adapter.submit(comics, selected, true, readKeys)
                    mode.title = context.getString(R.string.selection_count, selected.size)
                    return true
                }
            }
            return false
        }

        override fun onDestroyActionMode(mode: ActionMode) {
            selectionMode = false
            selected.clear()
            actionMode = null
            adapter.submit(comics, selected, false, readKeys)
        }
    }

    private class Adapter(
        private val picasso: Picasso,
        private val hideTitles: () -> Boolean,
        private val onClick: (OfflineComicEntity) -> Unit,
        private val onLongClick: (OfflineComicEntity) -> Boolean,
    ) : RecyclerView.Adapter<Adapter.VH>() {
        private var items: List<OfflineComicEntity> = emptyList()
        private var selected: Set<Long> = emptySet()
        private var selecting = false
        private var readKeys: Set<String> = emptySet()

        fun submit(
            next: List<OfflineComicEntity>,
            sel: Set<Long>,
            selecting: Boolean,
            readKeys: Set<String>,
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
            val hide = hideTitles()
            val isRead = item.sourceKey in readKeys
            val displayTitle = if (hide) "" else ComicFileNames.shortDisplayName(item.title)
            holder.title.text = displayTitle
            holder.title.visibility = if (displayTitle.isBlank()) View.GONE else View.VISIBLE
            holder.readCheck.visibility = if (isRead) View.VISIBLE else View.GONE
            picasso.load(FileCoverHandler.uriFor(item.localPath))
                .into(holder.cover)
            holder.selected.visibility = if (selecting && item.id in selected) View.VISIBLE else View.GONE
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

    companion object {
        private const val PREF_EXPANDED = "offline_section_expanded_v2"
    }
}
