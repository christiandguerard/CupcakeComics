package com.cupcakecomics.ui

import android.content.Context
import android.content.Intent
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.view.ActionMode
import com.cupcakecomics.data.LibraryRepository
import com.cupcakecomics.data.ReadMarkEntity
import com.cupcakecomics.data.ReadStatusRepository
import com.nkanaev.comics.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Shared multi-select ActionMode for cover-tile library sections. Owns the
 * selection set and the comic_selection menu actions (mark read/unread via
 * ReadStatusRepository, delete, export read history, select all); sections
 * provide data accessors and the delete side effect.
 */
class CoverTileSelectionController<T>(
    private val context: Context,
    private val scope: CoroutineScope,
    private val repo: LibraryRepository,
    private val readStatus: ReadStatusRepository,
    private val items: () -> List<T>,
    private val idOf: (T) -> Long,
    private val readMarkOf: (T) -> ReadMarkEntity,
    private val keyOf: (T) -> String,
    private val deleteToastRes: Int,
    private val onDelete: suspend (List<T>) -> Unit,
    private val onStateChanged: () -> Unit,
) {
    private var actionMode: ActionMode? = null
    val selected = linkedSetOf<Long>()
    var selecting = false
        private set

    fun startWith(item: T) {
        selecting = true
        selected.clear()
        selected.add(idOf(item))
        onStateChanged()
        val activity = context as? AppCompatActivity ?: return
        actionMode = activity.startSupportActionMode(callback)
        syncTitle()
    }

    fun toggle(item: T) {
        val id = idOf(item)
        if (!selected.remove(id)) selected.add(id)
        onStateChanged()
        syncTitle()
        if (selected.isEmpty()) actionMode?.finish()
    }

    fun finish() {
        actionMode?.finish()
    }

    /** Keep the ActionMode title in sync when the underlying list changes. */
    fun syncTitle() {
        actionMode?.title = context.getString(R.string.selection_count, selected.size)
    }

    private val callback = object : ActionMode.Callback {
        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
            mode.menuInflater.inflate(R.menu.comic_selection, menu)
            menu.findItem(R.id.action_download_offline)?.isVisible = false
            return true
        }

        override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean = false

        override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
            val picked = items().filter { idOf(it) in selected }
            when (item.itemId) {
                R.id.action_mark_read -> {
                    scope.launch {
                        readStatus.markRead(picked.map(readMarkOf))
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
                        readStatus.markUnread(picked.map(keyOf))
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
                        onDelete(picked)
                        Toast.makeText(
                            context,
                            context.getString(deleteToastRes, picked.size),
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
                    selected.addAll(items().map(idOf))
                    onStateChanged()
                    syncTitle()
                    return true
                }
            }
            return false
        }

        override fun onDestroyActionMode(mode: ActionMode) {
            selecting = false
            selected.clear()
            actionMode = null
            onStateChanged()
        }
    }
}
