package com.cupcakecomics.ui

import android.content.Context
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.cupcakecomics.cover.FileCoverHandler
import com.cupcakecomics.data.LibraryRepository
import com.cupcakecomics.data.LocalFileEntity
import com.cupcakecomics.data.ReadMarkEntity
import com.cupcakecomics.data.ReadStatusRepository
import com.cupcakecomics.reader.ReaderLauncher
import com.cupcakecomics.settings.CupcakeSettings
import com.cupcakecomics.smb.ComicFileNames
import com.nkanaev.comics.R
import com.nkanaev.comics.activity.MainActivity
import com.nkanaev.comics.managers.Utils
import com.squareup.picasso.Picasso
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Collapsible tiled cover grid for user-imported local files on Library home.
 */
class LocalFilesLibraryController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val sectionRoot: View,
    private val headerRow: View,
    private val header: TextView,
    private val chevron: ImageView,
    private val list: RecyclerView,
    private val addButton: View,
    private val onAddClick: () -> Unit,
    private val onHasLocal: (Boolean) -> Unit,
) {
    private val repo = LibraryRepository(context)
    private val settings = CupcakeSettings(context)
    private val picasso: Picasso = (context as MainActivity).picasso
    private var collectJob: Job? = null
    private var comics: List<LocalFileEntity> = emptyList()
    private var readKeys: Set<String> = emptySet()

    private val adapter = CoverTileAdapter<LocalFileEntity>(
        titleOf = { ComicFileNames.shortDisplayName(it.title) },
        bindCover = { holder, item ->
            picasso.load(FileCoverHandler.uriFor(item.localPath)).into(holder.cover)
        },
        idOf = { it.id },
        readKeyOf = { it.sourceKey },
        hideTitles = { settings.hideCoverTitles },
        onClick = { comic ->
            if (selection.selecting) selection.toggle(comic) else open(comic)
        },
        onLongClick = { comic ->
            if (!selection.selecting) selection.startWith(comic)
            true
        },
    )

    private val selection = CoverTileSelectionController(
        context = context,
        scope = scope,
        repo = repo,
        readStatus = ReadStatusRepository(context),
        items = { comics },
        idOf = { it.id },
        readMarkOf = {
            ReadMarkEntity(
                identityKey = it.sourceKey,
                displayName = it.title,
                sourceType = "local",
                sourceDetail = it.localPath,
                markedReadAt = System.currentTimeMillis(),
            )
        },
        keyOf = { it.sourceKey },
        deleteToastRes = R.string.deleted_local_toast,
        onDelete = { picked ->
            withContext(Dispatchers.IO) {
                picked.forEach { Utils.deleteCoverCacheFile(it.localPath) }
            }
            repo.deleteLocalFiles(picked.map { it.id })
        },
        onStateChanged = { submitList() },
    )

    private val chrome = CollapsibleSectionChrome(
        context = context,
        prefs = context.getSharedPreferences("cupcake_library_ui", Context.MODE_PRIVATE),
        prefKey = PREF_EXPANDED,
        defaultExpanded = true,
        headerRow = headerRow,
        header = header,
        chevron = chevron,
        titleRes = R.string.library_local_header,
        onChanged = { applyExpanded() },
    )

    init {
        refreshSpan()
        list.adapter = adapter
        list.isNestedScrollingEnabled = false
        list.overScrollMode = View.OVER_SCROLL_NEVER
        addButton.setOnClickListener { onAddClick() }
        // Always show the Local files section so users can add books.
        sectionRoot.visibility = View.VISIBLE
        applyExpanded()
    }

    fun start() {
        collectJob?.cancel()
        collectJob = scope.launch {
            launch {
                repo.readMarks.collectLatest { marks ->
                    readKeys = marks.map { it.identityKey }.toSet()
                    submitList()
                }
            }
            repo.localFiles.collectLatest { items ->
                comics = items
                onHasLocal(items.isNotEmpty())
                applyExpanded()
                submitList()
                selection.syncTitle()
                launch(Dispatchers.IO) {
                    items.forEach { FileCoverHandler.warmCache(it.localPath) }
                }
            }
        }
    }

    fun stop() {
        collectJob?.cancel()
        selection.finish()
    }

    fun refreshTitlesVisibility() {
        adapter.notifyDataSetChanged()
    }

    fun refreshSpan() {
        val span = CoverGridHelper.spanCount(context, settings)
        list.layoutManager = GridLayoutManager(context, span)
    }

    fun importUri(uri: android.net.Uri) {
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    repo.importLocalFromUri(uri)
                }
                Toast.makeText(context, R.string.library_local_added, Toast.LENGTH_SHORT).show()
            } catch (t: Throwable) {
                Toast.makeText(
                    context,
                    context.getString(R.string.library_local_add_failed, t.message ?: ""),
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    private fun applyExpanded() {
        list.visibility =
            if (comics.isNotEmpty() && chrome.expanded) View.VISIBLE else View.GONE
        chrome.apply(comics.size)
    }

    private fun submitList() {
        adapter.submit(comics, selection.selected, selection.selecting, readKeys)
    }

    private fun open(comic: LocalFileEntity) {
        val file = File(comic.localPath)
        if (!file.exists()) {
            Toast.makeText(context, R.string.local_file_missing, Toast.LENGTH_SHORT).show()
            scope.launch { repo.deleteLocalFiles(listOf(comic.id)) }
            return
        }
        ReaderLauncher.openFile(context, file, identityKey = comic.sourceKey)
    }

    companion object {
        private const val PREF_EXPANDED = "local_section_expanded"
    }
}
