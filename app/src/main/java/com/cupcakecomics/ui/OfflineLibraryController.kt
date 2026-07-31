package com.cupcakecomics.ui

import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
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
import com.cupcakecomics.data.ReadStatusRepository
import com.cupcakecomics.reader.ReaderLauncher
import com.cupcakecomics.settings.CupcakeSettings
import com.cupcakecomics.smb.ComicFileNames
import com.nkanaev.comics.R
import com.nkanaev.comics.activity.MainActivity
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
    private val stack: FrameLayout,
    private val onHasOffline: (Boolean) -> Unit,
) {
    private val repo = LibraryRepository(context)
    private val readStatus = ReadStatusRepository(context)
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
    private var pickerDialog: Dialog? = null

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
                    updateStack()
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
        pickerDialog?.dismiss()
        pickerDialog = null
    }

    fun refreshTitlesVisibility() {
        adapter.notifyDataSetChanged()
    }

    fun refreshSpan() {
        val span = CoverGridHelper.spanCount(context, settings)
        list.layoutManager = GridLayoutManager(context, span)
        updateStack()
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
        updateStack()
    }

    /**
     * Collapsed look for larger libraries: a deck of covers layered so each
     * underlying cover keeps ~20% visible as a tab above the next one.
     * Tapping the deck opens a darkened grid overlay to pick a comic.
     */
    private fun updateStack() {
        val show = comics.size > STACK_THRESHOLD && !expanded
        stack.visibility = if (show) View.VISIBLE else View.GONE
        if (!show) return

        stack.removeAllViews()
        stack.setOnClickListener { showPickerOverlay() }
        stack.contentDescription = context.getString(R.string.library_offline_stack_cd, comics.size)

        val metrics = context.resources.displayMetrics
        val density = metrics.density
        val span = CoverGridHelper.spanCount(context, settings).coerceAtLeast(1)
        val containerPad = (32 * density).toInt()
        val tilePad = (6 * density).toInt()
        val tileWidth = (metrics.widthPixels - containerPad) / span
        val coverHeight = ((tileWidth - 2 * tilePad) / CoverImageView.FACTOR).toInt()
        val tileHeight = coverHeight + 2 * tilePad
        val peek = (tileHeight * STACK_PEEK_FRACTION).toInt()

        val layers = comics.take(MAX_STACK_LAYERS)
        val last = layers.lastIndex
        for (i in last downTo 0) {
            val comic = layers[i]
            val tile = LayoutInflater.from(context)
                .inflate(R.layout.item_offline_cover_tile, stack, false)
            val cover = tile.findViewById<CoverImageView>(R.id.offline_cover)
            val title = tile.findViewById<TextView>(R.id.offline_title)
            val readCheck = tile.findViewById<ImageView>(R.id.offline_read_check)
            val displayTitle = if (settings.hideCoverTitles) "" else ComicFileNames.shortDisplayName(comic.title)
            title.text = displayTitle
            title.visibility = if (displayTitle.isBlank()) View.GONE else View.VISIBLE
            readCheck.visibility = if (comic.sourceKey in readKeys) View.VISIBLE else View.GONE
            picasso.load(FileCoverHandler.uriFor(comic.localPath)).into(cover)
            val lp = FrameLayout.LayoutParams(tileWidth, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            lp.topMargin = (last - i) * peek
            // Front cover (i == 0) stacks highest; back layers step down.
            val z = (last - i + 1).toFloat()
            if (i == 0 && comics.size > layers.size) {
                // Wrap the front cover so the "+N more" badge tracks its corner.
                val wrapper = FrameLayout(context)
                wrapper.addView(
                    tile,
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ),
                )
                val badge = TextView(context).apply {
                    text = context.getString(R.string.offline_stack_more, comics.size - layers.size)
                    setTextColor(Color.WHITE)
                    textSize = 12f
                    val h = (10 * density).toInt()
                    val v = (4 * density).toInt()
                    setPadding(h, v, h, v)
                    setBackgroundColor(0xCC000000.toInt())
                }
                val badgeLp = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
                badgeLp.gravity = Gravity.TOP or Gravity.END
                val inset = (10 * density).toInt()
                badgeLp.marginEnd = inset
                badgeLp.topMargin = inset
                wrapper.addView(badge, badgeLp)
                wrapper.translationZ = z
                stack.addView(wrapper, lp)
            } else {
                tile.translationZ = z
                stack.addView(tile, lp)
            }
        }
    }

    /** Darkened full-screen grid of every downloaded comic; tap one to read it. */
    private fun showPickerOverlay() {
        if (comics.isEmpty()) return
        val density = context.resources.displayMetrics.density
        val dialog = Dialog(context)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val title = TextView(context).apply {
            text = context.getString(R.string.library_offline_header) + " (${comics.size})"
            setTextColor(Color.WHITE)
            textSize = 18f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            val pad = (20 * density).toInt()
            setPadding(pad, pad, pad, (12 * density).toInt())
        }
        val grid = RecyclerView(context).apply {
            layoutManager = GridLayoutManager(context, CoverGridHelper.spanCount(context, settings))
            val pad = (10 * density).toInt()
            setPadding(pad, 0, pad, pad)
            clipToPadding = false
            adapter = Adapter(
                picasso = picasso,
                hideTitles = { settings.hideCoverTitles },
                onClick = { comic ->
                    dismissPickerOverlay()
                    open(comic)
                },
                onLongClick = { true },
            ).also {
                it.submit(comics, emptySet(), false, readKeys)
            }
        }
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(
                title,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
            addView(
                grid,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f),
            )
        }
        val root = FrameLayout(context).apply {
            // Taps outside the grid bubble up here and dismiss.
            setOnClickListener { dismissPickerOverlay() }
            addView(
                content,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ),
            )
        }
        dialog.setContentView(root)
        dialog.window?.apply {
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            attributes = attributes.apply { dimAmount = 0.75f }
        }
        pickerDialog = dialog
        dialog.setOnDismissListener { pickerDialog = null }
        dialog.show()
    }

    private fun dismissPickerOverlay() {
        pickerDialog?.dismiss()
        pickerDialog = null
    }

    private fun open(comic: OfflineComicEntity) {
        val file = File(comic.localPath)
        if (!file.exists()) {
            Toast.makeText(context, R.string.offline_missing, Toast.LENGTH_SHORT).show()
            scope.launch { repo.deleteOffline(listOf(comic.id)) }
            return
        }
        ReaderLauncher.openFile(
            context,
            file,
            identityKey = comic.sourceKey.takeIf { it.isNotBlank() },
        )
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
                        readStatus.markRead(
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
                        readStatus.markUnread(picked.map { it.sourceKey })
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

        /** Stack instead of a plain grid once the section holds more than this. */
        private const val STACK_THRESHOLD = 4

        /** Front cover plus this many minus one tabbed covers behind it. */
        private const val MAX_STACK_LAYERS = 4

        /** Fraction of each tabbed cover left visible above the next layer. */
        private const val STACK_PEEK_FRACTION = 0.20f
    }
}
