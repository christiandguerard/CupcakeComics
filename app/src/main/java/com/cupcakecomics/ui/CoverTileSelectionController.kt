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
 * provide data accessors and the delete side effect. Sections that support
 * in-place file renames pass [renameSupport]; the action appears only when
 * exactly one item is selected.
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
    private val renameSupport: RenameSupport<T>? = null,
) {
    /** Rename wiring for a section: current file name and the rename side effect. */
    class RenameSupport<T>(
        val currentNameOf: (T) -> String,
        val onRename: suspend (T, String) -> String,
    )
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
        syncRenameVisibility()
    }

    private fun syncRenameVisibility() {
        val item = actionMode?.menu?.findItem(R.id.action_rename) ?: return
        item.isVisible = renameSupport != null && selected.size == 1
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
                R.id.action_rename -> {
                    val support = renameSupport
                    val target = picked.singleOrNull()
                    if (support != null && target != null) {
                        showRenameDialog(mode, support, target)
                    }
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

    private fun showRenameDialog(mode: ActionMode, support: RenameSupport<T>, target: T) {
        val currentName = support.currentNameOf(target)
        val density = context.resources.displayMetrics.density
        val input = android.widget.EditText(context).apply {
            setText(currentName.substringBeforeLast('.'))
            setSelectAllOnFocus(true)
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE
            maxLines = 1
            setSingleLine(true)
            setHint(R.string.rename_name_hint)
        }
        // A bare EditText as the dialog view renders edge-to-edge; the wrapper
        // restores the standard dialog content margins.
        val container = android.widget.FrameLayout(context).apply {
            val horizontal = (24 * density).toInt()
            setPadding(horizontal, (8 * density).toInt(), horizontal, 0)
            addView(
                input,
                android.widget.FrameLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
        val dialog = androidx.appcompat.app.AlertDialog.Builder(context)
            .setTitle(R.string.rename_dialog_title)
            .setView(container)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                renameFromDialog(mode, support, target, input.text.toString())
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        // Show the keyboard with the name pre-selected so renaming starts typing.
        dialog.window?.setSoftInputMode(
            android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE,
        )
        dialog.show()
    }

    private fun renameFromDialog(
        mode: ActionMode,
        support: RenameSupport<T>,
        target: T,
        requested: String,
    ) {
        scope.launch {
            runCatching {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    support.onRename(target, requested)
                }
            }.onSuccess { newName ->
                Toast.makeText(
                    context,
                    context.getString(R.string.rename_success_toast, newName),
                    Toast.LENGTH_SHORT,
                ).show()
            }.onFailure { error ->
                Toast.makeText(
                    context,
                    context.getString(
                        R.string.rename_failed_toast,
                        error.message ?: error.javaClass.simpleName,
                    ),
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
        mode.finish()
    }
}
