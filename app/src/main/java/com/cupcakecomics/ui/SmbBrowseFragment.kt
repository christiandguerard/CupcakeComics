package com.cupcakecomics.ui

import android.content.Intent
import android.os.Bundle
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.view.ActionMode
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.cupcakecomics.cover.SmbNetworkCoverCache
import com.cupcakecomics.data.ConnectionRepository
import com.cupcakecomics.data.CredentialStore
import com.cupcakecomics.data.LibraryRepository
import com.cupcakecomics.data.ReadMarkEntity
import com.cupcakecomics.data.SmbShareEntity
import com.cupcakecomics.smb.ComicFileNames
import com.cupcakecomics.smb.SmbBrowser
import com.cupcakecomics.smb.SmbListEntry
import com.cupcakecomics.smb.SmbStageManager
import com.nkanaev.comics.R
import com.nkanaev.comics.activity.MainActivity
import com.nkanaev.comics.activity.ReaderActivity
import com.nkanaev.comics.fragment.ReaderFragment
import com.nkanaev.comics.managers.Utils
import com.squareup.picasso.Picasso
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.Serializable
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

class SmbBrowseFragment : Fragment() {
    private var shareId: Long = -1
    private var share: SmbShareEntity? = null
    private var currentPath: String = ""
    private lateinit var repo: ConnectionRepository
    private lateinit var libraryRepo: LibraryRepository
    private lateinit var browser: SmbBrowser
    private lateinit var pathView: TextView
    private lateinit var statusView: TextView
    private lateinit var refresh: SwipeRefreshLayout
    private var actionMode: ActionMode? = null
    private var selectionMode = false
    /** When selecting, true = folders (monitor), false = comics (read/download). */
    private var selectingFolders = false
    private val selectedPaths = linkedSetOf<String>()
    private var entries: List<SmbListEntry> = emptyList()
    private var readKeys: Set<String> = emptySet()
    private var stageJob: kotlinx.coroutines.Job? = null
    private val stageCancelled = AtomicBoolean(false)

    private val adapter = EntryAdapter(
        onClick = { entry -> onEntryClick(entry) },
        onLongClick = { entry -> onEntryLongClick(entry) },
    )
    private lateinit var picasso: Picasso

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        shareId = requireArguments().getLong(ARG_SHARE_ID)
        currentPath = savedInstanceState?.getString(STATE_PATH).orEmpty()
        repo = ConnectionRepository(requireContext())
        libraryRepo = LibraryRepository(requireContext())
        browser = SmbBrowser(repo.credentialStore())
        requireActivity().onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (selectionMode) {
                        actionMode?.finish()
                        return
                    }
                    val base = SmbBrowser.normalizePath(share?.startPath.orEmpty())
                    val cur = SmbBrowser.normalizePath(currentPath.ifBlank { base })
                    if (cur.isNotEmpty() && cur != base) {
                        currentPath = SmbBrowser.parentPath(cur)
                        if (base.isNotEmpty() && !currentPath.startsWith(base) && currentPath.length < base.length) {
                            currentPath = base
                        }
                        load()
                    } else {
                        isEnabled = false
                        requireActivity().onBackPressedDispatcher.onBackPressed()
                    }
                }
            },
        )
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val view = inflater.inflate(R.layout.fragment_smb_browse, container, false)
        pathView = view.findViewById(R.id.smb_browse_path)
        statusView = view.findViewById(R.id.smb_browse_status)
        refresh = view.findViewById(R.id.smb_browse_refresh)
        val list = view.findViewById<RecyclerView>(R.id.smb_browse_list)
        list.layoutManager = LinearLayoutManager(requireContext())
        list.adapter = adapter
        adapter.libraryRepo = libraryRepo
        adapter.scope = viewLifecycleOwner.lifecycleScope
        adapter.browser = browser
        adapter.credentials = repo.credentialStore()
        adapter.shortenFilenames =
            com.cupcakecomics.settings.CupcakeSettings(requireContext()).shortenNetworkFilenames
        picasso = (requireActivity() as MainActivity).picasso
        adapter.picasso = picasso
        refresh.setOnRefreshListener { load() }
        viewLifecycleOwner.lifecycleScope.launch {
            launch {
                libraryRepo.readMarks.collectLatest { marks ->
                    readKeys = marks.map { it.identityKey }.toSet()
                    adapter.submit(entries, selectedPaths, selectionMode, readKeys, shareId)
                }
            }
            share = repo.getSmbShare(shareId)
            if (share == null) {
                statusView.text = getString(R.string.smb_share_missing)
                return@launch
            }
            adapter.share = share
            requireActivity().title = share!!.displayName
            if (currentPath.isEmpty()) {
                currentPath = share!!.startPath
            }
            load()
        }
        return view
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_PATH, currentPath)
    }

    private fun load() {
        val s = share ?: return
        refresh.isRefreshing = true
        statusView.text = ""
        pathView.text = "\\\\${s.host}\\${s.shareName}\\${SmbBrowser.normalizePath(currentPath)}"
        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { browser.list(s, currentPath) }
            refresh.isRefreshing = false
            result.onSuccess { list ->
                entries = list
                adapter.submit(list, selectedPaths, selectionMode, readKeys, shareId)
                statusView.text = getString(R.string.smb_browse_count, list.size)
            }.onFailure { err ->
                entries = emptyList()
                adapter.submit(emptyList(), selectedPaths, selectionMode, readKeys, shareId)
                statusView.text = getString(R.string.smb_browse_fail, err.message ?: "error")
            }
        }
    }

    private fun onEntryClick(entry: SmbListEntry) {
        if (selectionMode) {
            if (selectingFolders) {
                if (entry.isDirectory) toggleSelection(entry)
            } else if (!entry.isDirectory && ComicFileNames.isComicArchive(entry.name)) {
                toggleSelection(entry)
            }
            return
        }
        if (entry.isDirectory) {
            currentPath = entry.relativePath
            load()
            return
        }
        if (!ComicFileNames.isComicArchive(entry.name)) {
            Toast.makeText(requireContext(), R.string.smb_not_comic, Toast.LENGTH_SHORT).show()
            return
        }
        downloadAndOpen(entry)
    }

    private fun onEntryLongClick(entry: SmbListEntry): Boolean {
        if (entry.isDirectory) {
            if (selectionMode && !selectingFolders) return true
            if (!selectionMode) startSelection(entry, folders = true)
            else toggleSelection(entry)
            return true
        }
        if (!ComicFileNames.isComicArchive(entry.name)) return false
        if (selectionMode && selectingFolders) return true
        if (!selectionMode) startSelection(entry, folders = false)
        return true
    }

    private fun startSelection(entry: SmbListEntry, folders: Boolean) {
        selectionMode = true
        selectingFolders = folders
        selectedPaths.clear()
        selectedPaths.add(entry.relativePath)
        adapter.submit(entries, selectedPaths, true, readKeys, shareId)
        actionMode = (requireActivity() as AppCompatActivity).startSupportActionMode(
            if (folders) folderSelectionCallback else selectionCallback,
        )
        actionMode?.title = getString(R.string.selection_count, selectedPaths.size)
    }

    private fun toggleSelection(entry: SmbListEntry) {
        if (selectedPaths.contains(entry.relativePath)) {
            selectedPaths.remove(entry.relativePath)
        } else {
            selectedPaths.add(entry.relativePath)
        }
        adapter.submit(entries, selectedPaths, selectionMode, readKeys, shareId)
        actionMode?.title = getString(R.string.selection_count, selectedPaths.size)
        if (selectedPaths.isEmpty()) actionMode?.finish()
    }

    private fun selectedComics(): List<SmbListEntry> =
        entries.filter { it.relativePath in selectedPaths && !it.isDirectory }

    private fun selectedFolders(): List<SmbListEntry> =
        entries.filter { it.relativePath in selectedPaths && it.isDirectory }

    private fun identityKey(relativePath: String): String =
        "smb:$shareId:${SmbBrowser.normalizePath(relativePath)}"

    private val folderSelectionCallback = object : ActionMode.Callback {
        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
            mode.menuInflater.inflate(R.menu.folder_selection, menu)
            return true
        }

        override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean = false

        override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
            when (item.itemId) {
                R.id.action_monitor_folders -> {
                    val folders = selectedFolders()
                    mode.finish()
                    viewLifecycleOwner.lifecycleScope.launch {
                        val pullRepo = com.cupcakecomics.pulllist.PullListRepository(requireContext())
                        for (folder in folders) {
                            pullRepo.enrollFolder(shareId, folder.relativePath, folder.name)
                        }
                        val msg = if (folders.size == 1) {
                            getString(R.string.pull_list_monitored)
                        } else {
                            getString(R.string.pull_list_monitored_many, folders.size)
                        }
                        Toast.makeText(requireContext(), msg, Toast.LENGTH_LONG).show()
                        (activity as? MainActivity)?.let {
                            com.cupcakecomics.notifications.NotifyPermissionPrompt.maybeShow(it)
                        }
                    }
                    return true
                }
                R.id.action_select_all_folders -> {
                    selectedPaths.clear()
                    entries.filter { it.isDirectory }.forEach { selectedPaths.add(it.relativePath) }
                    adapter.submit(entries, selectedPaths, true, readKeys, shareId)
                    mode.title = getString(R.string.selection_count, selectedPaths.size)
                    return true
                }
            }
            return false
        }

        override fun onDestroyActionMode(mode: ActionMode) {
            selectionMode = false
            selectingFolders = false
            selectedPaths.clear()
            actionMode = null
            adapter.submit(entries, selectedPaths, false, readKeys, shareId)
        }
    }

    private val selectionCallback = object : ActionMode.Callback {
        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
            mode.menuInflater.inflate(R.menu.comic_selection, menu)
            return true
        }

        override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean = false

        override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
            val picked = selectedComics()
            when (item.itemId) {
                R.id.action_mark_read -> {
                    viewLifecycleOwner.lifecycleScope.launch {
                        libraryRepo.markRead(
                            picked.map {
                                ReadMarkEntity(
                                    identityKey = identityKey(it.relativePath),
                                    displayName = it.name,
                                    sourceType = "smb",
                                    sourceDetail = "smb:$shareId:${it.relativePath}",
                                    markedReadAt = System.currentTimeMillis(),
                                )
                            },
                        )
                        Toast.makeText(
                            requireContext(),
                            getString(R.string.marked_read_toast, picked.size),
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                    mode.finish()
                    return true
                }
                R.id.action_mark_unread -> {
                    viewLifecycleOwner.lifecycleScope.launch {
                        libraryRepo.unmarkRead(picked.map { identityKey(it.relativePath) })
                        Toast.makeText(
                            requireContext(),
                            getString(R.string.marked_unread_toast, picked.size),
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                    mode.finish()
                    return true
                }
                R.id.action_download_offline -> {
                    mode.finish()
                    downloadBatch(picked)
                    return true
                }
                R.id.action_delete_offline -> {
                    viewLifecycleOwner.lifecycleScope.launch {
                        val keys = picked.map { identityKey(it.relativePath) }
                        val offline = keys.mapNotNull { libraryRepo.getOfflineBySource(it) }
                        libraryRepo.deleteOffline(offline.map { it.id })
                        Toast.makeText(
                            requireContext(),
                            getString(R.string.deleted_offline_toast, offline.size),
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                    mode.finish()
                    return true
                }
                R.id.action_export_read -> {
                    viewLifecycleOwner.lifecycleScope.launch {
                        val json = libraryRepo.exportReadMarksJson()
                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                            type = "application/json"
                            putExtra(Intent.EXTRA_SUBJECT, getString(R.string.export_read_share_title))
                            putExtra(Intent.EXTRA_TEXT, json)
                        }
                        startActivity(Intent.createChooser(shareIntent, getString(R.string.export_read_share_title)))
                    }
                    return true
                }
                R.id.action_select_all -> {
                    selectedPaths.clear()
                    entries.filter { !it.isDirectory && ComicFileNames.isComicArchive(it.name) }
                        .forEach { selectedPaths.add(it.relativePath) }
                    adapter.submit(entries, selectedPaths, true, readKeys, shareId)
                    mode.title = getString(R.string.selection_count, selectedPaths.size)
                    return true
                }
            }
            return false
        }

        override fun onDestroyActionMode(mode: ActionMode) {
            selectionMode = false
            selectingFolders = false
            selectedPaths.clear()
            actionMode = null
            adapter.submit(entries, selectedPaths, false, readKeys, shareId)
        }
    }

    private fun downloadBatch(comics: List<SmbListEntry>) {
        val s = share ?: return
        if (comics.isEmpty()) return
        val settings = com.cupcakecomics.settings.CupcakeSettings(requireContext())
        if (settings.offlineDownloadBackground) {
            enqueueBackgroundDownloads(s.id, comics.map { it.relativePath })
            return
        }
        var index = 0
        var movedToBackground = false
        fun remainingPaths(): List<String> =
            comics.drop(index - 1).map { it.relativePath }.filter { it.isNotBlank() }

        fun next() {
            if (movedToBackground) return
            if (index >= comics.size) {
                if (isAdded) {
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.downloaded_offline_toast, comics.size),
                        Toast.LENGTH_SHORT,
                    ).show()
                }
                return
            }
            val entry = comics[index++]
            stageWithProgress(
                s = s,
                entry = entry,
                openAfter = false,
                keepOffline = true,
                allowBackground = true,
                onBackground = {
                    movedToBackground = true
                    enqueueBackgroundDownloads(s.id, remainingPaths())
                },
                onDone = { next() },
            )
        }
        next()
    }

    private fun enqueueBackgroundDownloads(shareId: Long, paths: List<String>) {
        if (paths.isEmpty()) return
        com.cupcakecomics.downloads.OfflineDownloadWorker.enqueue(requireContext(), shareId, paths)
        val v = view ?: return
        com.google.android.material.snackbar.Snackbar.make(
            v,
            getString(R.string.offline_download_queued, paths.size),
            com.google.android.material.snackbar.Snackbar.LENGTH_LONG,
        ).setAction(R.string.offline_section_view) {
            (activity as? MainActivity)?.let { main ->
                val fragment = com.nkanaev.comics.fragment.LibraryFragment()
                main.pushFragment(fragment)
                main.window.decorView.post { fragment.scrollToSection("offline") }
            }
        }.show()
        (activity as? MainActivity)?.let {
            com.cupcakecomics.notifications.NotifyPermissionPrompt.maybeShow(it)
        }
    }

    private fun downloadAndOpen(entry: SmbListEntry) {
        val s = share ?: return
        val useGpu = try {
            com.cupcakecomics.reader.settings.ReaderSettingsStore(requireContext())
                .loadDefaults().useGpuRenderer
        } catch (_: Throwable) {
            true
        }
        val preferStream = com.cupcakecomics.settings.CupcakeSettings(requireContext()).preferSmbStreaming
        // CBZ/ZIP streaming only works with the GPU reader (SMB range source).
        if (preferStream && useGpu &&
            com.cupcakecomics.reader.source.ZipRangePageSource.isZipName(entry.name)
        ) {
            val intent = Intent(requireActivity(), ReaderActivity::class.java)
            intent.putExtra(ReaderFragment.PARAM_MODE, ReaderFragment.Mode.MODE_BROWSER)
            intent.putExtra(ReaderFragment.PARAM_IDENTITY_KEY, identityKey(entry.relativePath))
            intent.putExtra(com.cupcakecomics.reader.CupcakeReaderFragment.PARAM_SMB_SHARE_ID, s.id)
            intent.putExtra(com.cupcakecomics.reader.CupcakeReaderFragment.PARAM_SMB_RELATIVE_PATH, entry.relativePath)
            // Dummy handler for legacy extras contract (GPU path remaps via PARAM_SMB_*).
            intent.putExtra(ReaderFragment.PARAM_HANDLER, File(entry.name) as Serializable)
            startActivity(intent)
            return
        }
        // Stage into capped smb-stage cache for open-to-read.
        stageWithProgress(s, entry, openAfter = true, keepOffline = false, onDone = null)
    }

    private fun stageWithProgress(
        s: SmbShareEntity,
        entry: SmbListEntry,
        openAfter: Boolean,
        keepOffline: Boolean,
        onDone: (() -> Unit)?,
        allowBackground: Boolean = false,
        onBackground: (() -> Unit)? = null,
    ) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_download_progress, null)
        val titleView = dialogView.findViewById<TextView>(R.id.download_title)
        val bar = dialogView.findViewById<ProgressBar>(R.id.download_progress)
        val bytesView = dialogView.findViewById<TextView>(R.id.download_bytes)
        titleView.text = entry.name
        bar.isIndeterminate = true
        bytesView.text = getString(R.string.smb_download_bytes, "…", "…")
        stageCancelled.set(false)
        lateinit var progress: AlertDialog
        val builder = AlertDialog.Builder(requireContext())
            .setView(dialogView)
            .setCancelable(true)
            .setNegativeButton(R.string.smb_download_cancel) { _, _ ->
                stageCancelled.set(true)
                stageJob?.cancel()
            }
            .setOnCancelListener {
                stageCancelled.set(true)
                stageJob?.cancel()
            }
        if (allowBackground && onBackground != null) {
            builder.setNeutralButton(R.string.smb_download_run_background) { _, _ ->
                stageCancelled.set(true)
                stageJob?.cancel()
                onBackground()
            }
        }
        progress = builder.create()
        progress.show()
        stageJob = viewLifecycleOwner.lifecycleScope.launch {
            val stage = SmbStageManager(requireContext(), repo.credentialStore())
            val result = try {
                withContext(Dispatchers.IO) {
                    stage.stage(
                        share = s,
                        relativePath = entry.relativePath,
                        keepOffline = keepOffline,
                        isCancelled = { stageCancelled.get() },
                        onProgress = { copied, total ->
                            if (!isAdded || stageCancelled.get()) return@stage
                            bar.isIndeterminate = total <= 0
                            if (total > 0) {
                                bar.max = 1000
                                bar.progress = ((copied * 1000L) / total).toInt().coerceIn(0, 1000)
                            }
                            bytesView.text = getString(
                                R.string.smb_download_bytes,
                                Formatter.formatShortFileSize(requireContext(), copied),
                                if (total > 0) Formatter.formatShortFileSize(requireContext(), total) else "?",
                            )
                        },
                    )
                }
            } catch (_: kotlinx.coroutines.CancellationException) {
                Result.failure(kotlinx.coroutines.CancellationException("Cancelled"))
            }
            if (isAdded) progress.dismiss()
            val cancelled = stageCancelled.get() ||
                result.exceptionOrNull() is kotlinx.coroutines.CancellationException ||
                result.exceptionOrNull() is java.util.concurrent.CancellationException
            if (cancelled) {
                return@launch
            }
            result.onSuccess { file ->
                if (openAfter && isAdded) openLocalComic(file, identityKey(entry.relativePath))
            }.onFailure { err ->
                if (isAdded) {
                    statusView.text = getString(R.string.smb_stage_fail, err.message ?: "error")
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.smb_stage_fail, err.message ?: "error"),
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
            onDone?.invoke()
        }
    }

    private fun openLocalComic(file: File, identityKey: String? = null) {
        val intent = Intent(requireActivity(), ReaderActivity::class.java)
        intent.putExtra(ReaderFragment.PARAM_HANDLER, file as Serializable)
        intent.putExtra(ReaderFragment.PARAM_MODE, ReaderFragment.Mode.MODE_BROWSER)
        if (!identityKey.isNullOrBlank()) {
            intent.putExtra(ReaderFragment.PARAM_IDENTITY_KEY, identityKey)
        }
        startActivity(intent)
    }

    private class EntryAdapter(
        private val onClick: (SmbListEntry) -> Unit,
        private val onLongClick: (SmbListEntry) -> Boolean,
    ) : RecyclerView.Adapter<EntryAdapter.VH>() {
        var picasso: Picasso? = null
        var libraryRepo: LibraryRepository? = null
        var scope: kotlinx.coroutines.CoroutineScope? = null
        var browser: SmbBrowser? = null
        var credentials: CredentialStore? = null
        var share: SmbShareEntity? = null
        var shortenFilenames: Boolean = true
        private var items: List<SmbListEntry> = emptyList()
        private var selected: Set<String> = emptySet()
        private var selecting = false
        private var readKeys: Set<String> = emptySet()
        private var shareId: Long = -1

        fun submit(
            next: List<SmbListEntry>,
            sel: Set<String>,
            selecting: Boolean,
            readKeys: Set<String>,
            shareId: Long,
        ) {
            items = next
            selected = sel.toSet()
            this.selecting = selecting
            this.readKeys = readKeys
            this.shareId = shareId
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_smb_browse_row, parent, false)
            return VH(view)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items[position]
            val ctx = holder.itemView.context
            holder.title.text = if (shortenFilenames && !item.isDirectory) {
                ComicFileNames.shortDisplayName(item.name)
            } else {
                item.name
            }
            val key = "smb:$shareId:${SmbBrowser.normalizePath(item.relativePath)}"
            val isRead = !item.isDirectory && key in readKeys
            val date = if (item.lastModified > 0L) {
                " · " + NETWORK_DATE_FORMAT.format(
                    Instant.ofEpochMilli(item.lastModified).atZone(ZoneId.systemDefault()),
                )
            } else {
                ""
            }
            holder.subtitle.text = if (item.isDirectory) {
                ctx.getString(R.string.smb_entry_folder) + date
            } else {
                Formatter.formatShortFileSize(ctx, item.size) + date
            }
            holder.readCheck.visibility = if (isRead) View.VISIBLE else View.GONE
            holder.cover.setImageResource(
                if (item.isDirectory) R.drawable.ic_folder_open_24 else R.drawable.ic_collections_image_24,
            )
            holder.cover.tag = key
            val repo = libraryRepo
            val p = picasso
            val s = scope
            val smb = share
            val br = browser
            val creds = credentials
            if (repo != null && p != null && s != null && smb != null && br != null && creds != null) {
                s.launch {
                    val path = withContext(Dispatchers.IO) {
                        SmbNetworkCoverCache.resolve(ctx, smb, item, br, creds, repo)
                    }
                    if (holder.cover.tag == key && path != null) {
                        p.load(File(path))
                            .fit()
                            .centerCrop()
                            .into(holder.cover)
                    }
                }
            }
            val isSelected = selecting && item.relativePath in selected
            holder.itemView.setBackgroundColor(
                if (isSelected) 0x33007ACC else 0x00000000,
            )
            holder.itemView.setOnClickListener { onClick(item) }
            holder.itemView.setOnLongClickListener { onLongClick(item) }
        }

        override fun getItemCount(): Int = items.size

        class VH(view: View) : RecyclerView.ViewHolder(view) {
            val cover: ImageView = view.findViewById(R.id.smb_entry_cover)
            val title: TextView = view.findViewById(R.id.smb_entry_title)
            val subtitle: TextView = view.findViewById(R.id.smb_entry_subtitle)
            val readCheck: ImageView = view.findViewById(R.id.smb_entry_read_check)
        }
    }

    companion object {
        private const val ARG_SHARE_ID = "shareId"
        private const val STATE_PATH = "path"
        private val NETWORK_DATE_FORMAT =
            DateTimeFormatter.ofPattern("MM/dd/yyyy", Locale.US)

        @JvmStatic
        fun newInstance(shareId: Long): SmbBrowseFragment {
            return SmbBrowseFragment().apply {
                arguments = Bundle().apply { putLong(ARG_SHARE_ID, shareId) }
            }
        }
    }
}
