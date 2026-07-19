package com.cupcakecomics.ui

import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.cupcakecomics.cover.FileCoverHandler
import com.cupcakecomics.cover.SmbNetworkCoverCache
import com.cupcakecomics.data.ConnectionRepository
import com.cupcakecomics.data.LibraryRepository
import com.cupcakecomics.data.MonitoredFolderEntity
import com.cupcakecomics.data.PullComicEntity
import com.cupcakecomics.pulllist.PullEstimateRepository
import com.cupcakecomics.pulllist.PullListRepository
import com.cupcakecomics.pulllist.PullListWorker
import com.cupcakecomics.pulllist.SeriesEstimate
import com.cupcakecomics.smb.SmbStageManager
import com.google.android.material.tabs.TabLayout
import com.nkanaev.comics.R
import com.nkanaev.comics.activity.MainActivity
import com.nkanaev.comics.activity.ReaderActivity
import com.nkanaev.comics.fragment.ReaderFragment
import com.nkanaev.comics.managers.Utils
import com.nkanaev.comics.view.CoverImageView
import com.squareup.picasso.Picasso
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.Serializable

class PullListFragment : Fragment() {
    private lateinit var repo: PullListRepository
    private lateinit var estimateRepo: PullEstimateRepository
    private lateinit var connections: ConnectionRepository
    private lateinit var libraryRepo: LibraryRepository
    private lateinit var refresh: SwipeRefreshLayout
    private lateinit var empty: TextView
    private lateinit var unreadList: RecyclerView
    private lateinit var estimatesList: RecyclerView
    private lateinit var tabs: TabLayout
    private lateinit var picasso: Picasso

    private var estimatesByFolder = emptyMap<Long, SeriesEstimate>()
    private var estimatesBySharePath = emptyList<Pair<MonitoredFolderEntity, SeriesEstimate>>()
    private var showingEstimates = false

    private val unreadAdapter = UnreadAdapter(
        onClick = { openComic(it) },
        onLongClick = { showActions(it); true },
        estimateFor = { item -> estimateForComic(item) },
    )
    private val estimatesAdapter = EstimatesAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setHasOptionsMenu(true)
        repo = PullListRepository(requireContext())
        estimateRepo = PullEstimateRepository(requireContext())
        connections = ConnectionRepository(requireContext())
        libraryRepo = LibraryRepository(requireContext())
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val view = inflater.inflate(R.layout.fragment_pull_list, container, false)
        refresh = view.findViewById(R.id.pull_list_refresh)
        empty = view.findViewById(R.id.pull_list_empty)
        tabs = view.findViewById(R.id.pull_list_tabs)
        unreadList = view.findViewById(R.id.pull_list_recycler)
        estimatesList = view.findViewById(R.id.pull_estimates_recycler)

        unreadList.layoutManager = LinearLayoutManager(requireContext())
        unreadList.adapter = unreadAdapter
        unreadList.setHasFixedSize(true)
        estimatesList.layoutManager = LinearLayoutManager(requireContext())
        estimatesList.adapter = estimatesAdapter

        picasso = (requireActivity() as MainActivity).picasso
        unreadAdapter.picasso = picasso
        unreadAdapter.libraryRepo = libraryRepo
        unreadAdapter.connections = connections
        unreadAdapter.scope = viewLifecycleOwner.lifecycleScope
        unreadAdapter.hideTitles = com.cupcakecomics.settings.CupcakeSettings(requireContext()).hideCoverTitles

        tabs.addTab(tabs.newTab().setText(R.string.pull_list_tab_unread))
        tabs.addTab(tabs.newTab().setText(R.string.pull_list_tab_estimates))
        tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                showingEstimates = tab.position == 1
                applyTabVisibility()
                if (showingEstimates) loadEstimates(force = false)
            }
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })

        refresh.setOnRefreshListener {
            if (showingEstimates) loadEstimates(force = true)
            else scanNow()
        }
        requireActivity().title = getString(R.string.menu_pull_list)
        (requireActivity() as MainActivity).setSubTitle(null)

        viewLifecycleOwner.lifecycleScope.launch {
            repo.pullList.collectLatest { items ->
                unreadAdapter.submit(items)
                if (!showingEstimates) {
                    empty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
                    if (items.isEmpty()) {
                        empty.setText(R.string.pull_list_empty)
                    }
                }
            }
        }
        applyTabVisibility()
        scanNow(silent = true)
        loadEstimates(force = false)
        return view
    }

    override fun onCreateOptionsMenu(menu: android.view.Menu, inflater: android.view.MenuInflater) {
        menu.add(0, MENU_FOLDERS, 0, R.string.pull_list_manage_folders)
            .setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_NEVER)
        super.onCreateOptionsMenu(menu, inflater)
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        if (item.itemId == MENU_FOLDERS) {
            (activity as? MainActivity)?.pushFragment(MonitoredFoldersFragment())
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun applyTabVisibility() {
        unreadList.visibility = if (showingEstimates) View.GONE else View.VISIBLE
        estimatesList.visibility = if (showingEstimates) View.VISIBLE else View.GONE
        if (showingEstimates) {
            empty.visibility = if (estimatesAdapter.itemCount == 0) View.VISIBLE else View.GONE
            if (estimatesAdapter.itemCount == 0) empty.setText(R.string.pull_estimates_empty)
        } else {
            empty.visibility = if (unreadAdapter.itemCount == 0) View.VISIBLE else View.GONE
        }
    }

    private fun loadEstimates(force: Boolean) {
        viewLifecycleOwner.lifecycleScope.launch {
            if (force) refresh.isRefreshing = true
            val folders = withContext(Dispatchers.IO) {
                repo.monitoredFolders.first()
            }
            val estimates = withContext(Dispatchers.IO) {
                estimateRepo.estimates(forceRefresh = force)
            }
            estimatesByFolder = estimates.associateBy { it.folderId }
            estimatesBySharePath = folders.mapNotNull { folder ->
                val est = estimatesByFolder[folder.id] ?: return@mapNotNull null
                folder to est
            }
            estimatesAdapter.submit(estimates)
            unreadAdapter.notifyDataSetChanged()
            if (force) refresh.isRefreshing = false
            if (showingEstimates) applyTabVisibility()
        }
    }

    private fun estimateForComic(item: PullComicEntity): SeriesEstimate? {
        val path = item.relativePath.trim().trim('/').replace('\\', '/')
        return estimatesBySharePath.firstOrNull { (folder, _) ->
            if (folder.shareId != item.shareId) return@firstOrNull false
            val root = folder.relativePath.trim().trim('/')
            root.isEmpty() || path == root || path.startsWith("$root/")
        }?.second
    }

    private fun scanNow(silent: Boolean = false) {
        if (!silent) refresh.isRefreshing = true
        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { repo.scanAll() }
            loadEstimates(force = true)
            refresh.isRefreshing = false
            if (!silent && isAdded) {
                val msg = if (result.folders == 0) {
                    getString(R.string.pull_list_no_folders)
                } else {
                    getString(
                        R.string.pull_list_scan_done,
                        result.newUnreadItems,
                        result.scannedComics,
                    )
                }
                Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
                if (result.newUnreadItems > 0 &&
                    com.cupcakecomics.settings.CupcakeSettings(requireContext()).pullListNotify
                ) {
                    PullListWorker.notifyNewIssues(requireContext(), result.newTitles)
                }
                if (result.downloadedTitles.isNotEmpty()) {
                    com.cupcakecomics.notifications.CupcakeNotifications.onDownloadsComplete(
                        requireContext(),
                        result.downloadedTitles,
                    )
                }
            }
        }
    }

    private fun openComic(item: PullComicEntity) {
        viewLifecycleOwner.lifecycleScope.launch {
            val share = connections.getSmbShare(item.shareId)
            if (share == null) {
                Toast.makeText(requireContext(), R.string.smb_share_missing, Toast.LENGTH_SHORT).show()
                return@launch
            }
            val useGpu = try {
                com.cupcakecomics.reader.settings.ReaderSettingsStore(requireContext())
                    .loadDefaults().useGpuRenderer
            } catch (_: Throwable) {
                true
            }
            val preferStream = com.cupcakecomics.settings.CupcakeSettings(requireContext()).preferSmbStreaming
            val isZip = com.cupcakecomics.reader.source.ZipRangePageSource.isZipName(item.title) ||
                com.cupcakecomics.reader.source.ZipRangePageSource.isZipName(item.relativePath)
            if (preferStream && useGpu && isZip) {
                val intent = Intent(requireActivity(), ReaderActivity::class.java)
                intent.putExtra(ReaderFragment.PARAM_MODE, ReaderFragment.Mode.MODE_BROWSER)
                intent.putExtra(ReaderFragment.PARAM_IDENTITY_KEY, item.identityKey)
                intent.putExtra(com.cupcakecomics.reader.CupcakeReaderFragment.PARAM_SMB_SHARE_ID, share.id)
                intent.putExtra(
                    com.cupcakecomics.reader.CupcakeReaderFragment.PARAM_SMB_RELATIVE_PATH,
                    item.relativePath,
                )
                intent.putExtra(ReaderFragment.PARAM_HANDLER, File(item.title) as Serializable)
                startActivity(intent)
                return@launch
            }
            val dialogView = layoutInflater.inflate(R.layout.dialog_download_progress, null)
            val titleView = dialogView.findViewById<TextView>(R.id.download_title)
            val bar = dialogView.findViewById<android.widget.ProgressBar>(R.id.download_progress)
            val bytesView = dialogView.findViewById<TextView>(R.id.download_bytes)
            titleView.text = item.title
            bar.isIndeterminate = true
            val progress = AlertDialog.Builder(requireContext())
                .setView(dialogView)
                .setCancelable(false)
                .create()
            progress.show()
            val result = withContext(Dispatchers.IO) {
                SmbStageManager(requireContext(), connections.credentialStore())
                    .stage(share, item.relativePath, keepOffline = false) { copied, total ->
                        if (!isAdded) return@stage
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
                    }
            }
            if (isAdded) progress.dismiss()
            result.onSuccess { file ->
                val intent = Intent(requireActivity(), ReaderActivity::class.java)
                intent.putExtra(ReaderFragment.PARAM_HANDLER, file as Serializable)
                intent.putExtra(ReaderFragment.PARAM_MODE, ReaderFragment.Mode.MODE_BROWSER)
                intent.putExtra(ReaderFragment.PARAM_IDENTITY_KEY, item.identityKey)
                startActivity(intent)
            }.onFailure { err ->
                Toast.makeText(
                    requireContext(),
                    getString(R.string.smb_stage_fail, err.message ?: "error"),
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    private fun showActions(item: PullComicEntity) {
        val labels = arrayOf(
            getString(R.string.action_mark_read),
            getString(R.string.action_mark_unread),
            getString(R.string.pull_list_ignore),
        )
        AlertDialog.Builder(requireContext())
            .setTitle(Utils.removeExtensionIfAny(item.title))
            .setItems(labels) { _, which ->
                viewLifecycleOwner.lifecycleScope.launch {
                    when (which) {
                        0 -> repo.markRead(item.identityKey)
                        1 -> repo.markUnread(item.identityKey)
                        2 -> repo.ignoreFromPull(item.identityKey)
                    }
                }
            }
            .show()
    }

    private class UnreadAdapter(
        private val onClick: (PullComicEntity) -> Unit,
        private val onLongClick: (PullComicEntity) -> Boolean,
        private val estimateFor: (PullComicEntity) -> SeriesEstimate?,
    ) : RecyclerView.Adapter<UnreadAdapter.VH>() {
        var picasso: Picasso? = null
        var libraryRepo: LibraryRepository? = null
        var connections: ConnectionRepository? = null
        var scope: kotlinx.coroutines.CoroutineScope? = null
        var hideTitles: Boolean = true
        private var items: List<PullComicEntity> = emptyList()

        fun submit(next: List<PullComicEntity>) {
            items = next
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_pull_list_row, parent, false)
            return VH(view)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items[position]
            val ctx = holder.itemView.context
            val hide = hideTitles
            holder.title.visibility = if (hide) View.GONE else View.VISIBLE
            holder.subtitle.visibility = if (hide) View.GONE else View.VISIBLE
            if (!hide) {
                holder.title.text = Utils.removeExtensionIfAny(item.title)
                val size = Formatter.formatShortFileSize(ctx, item.sizeBytes)
                val progress = if (item.pageCount > 0) {
                    ctx.getString(R.string.pull_list_progress, item.highestPage, item.pageCount)
                } else {
                    size
                }
                val missing = if (item.missing) " · " + ctx.getString(R.string.pull_list_missing) else ""
                holder.subtitle.text = progress + missing
            }
            bindEstimate(holder, estimateFor(item))
            holder.cover.setImageResource(R.drawable.ic_collections_image_24)
            holder.cover.tag = item.identityKey
            val repo = libraryRepo
            val connectionRepo = connections
            val p = picasso
            val s = scope
            if (repo != null && connectionRepo != null && p != null && s != null) {
                s.launch {
                    val offline = withContext(Dispatchers.IO) { repo.getOfflineBySource(item.identityKey) }
                    if (holder.cover.tag != item.identityKey) return@launch
                    if (offline != null) {
                        p.load(FileCoverHandler.uriFor(offline.localPath))
                            .placeholder(R.drawable.ic_collections_image_24)
                            .error(R.drawable.ic_collections_image_24)
                            .fit()
                            .centerCrop()
                            .into(holder.cover)
                        return@launch
                    }
                    val networkCover = withContext(Dispatchers.IO) {
                        val share = connectionRepo.getSmbShare(item.shareId) ?: return@withContext null
                        SmbNetworkCoverCache.ensureComicCover(
                            ctx.applicationContext,
                            share,
                            item.relativePath,
                            connectionRepo.credentialStore(),
                            repo,
                        )
                    }
                    if (holder.cover.tag == item.identityKey && networkCover != null) {
                        p.load(File(networkCover))
                            .placeholder(R.drawable.ic_collections_image_24)
                            .error(R.drawable.ic_collections_image_24)
                            .fit()
                            .centerCrop()
                            .into(holder.cover)
                    }
                }
            }
            holder.itemView.setOnClickListener { onClick(item) }
            holder.itemView.setOnLongClickListener { onLongClick(item) }
        }

        override fun getItemCount(): Int = items.size

        class VH(view: View) : RecyclerView.ViewHolder(view) {
            val cover: CoverImageView = view.findViewById(R.id.pull_cover)
            val title: TextView = view.findViewById(R.id.pull_title)
            val subtitle: TextView = view.findViewById(R.id.pull_subtitle)
            val eta: TextView = view.findViewById(R.id.pull_eta)
            val bar: ProgressBar = view.findViewById(R.id.pull_release_bar)
        }
    }

    private class EstimatesAdapter : RecyclerView.Adapter<EstimatesAdapter.VH>() {
        private var items: List<SeriesEstimate> = emptyList()

        fun submit(next: List<SeriesEstimate>) {
            items = next
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_pull_estimate_row, parent, false)
            return VH(view)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items[position]
            val ctx = holder.itemView.context
            holder.title.text = item.seriesName
            holder.status.text = when (item.status) {
                MonitoredFolderEntity.SERIES_ENDED -> ctx.getString(R.string.pull_status_ended)
                MonitoredFolderEntity.SERIES_ONGOING -> ctx.getString(R.string.pull_status_ongoing)
                else -> ctx.getString(R.string.pull_status_unknown)
            }
            holder.eta.text = formatEta(ctx, item)
            holder.source.text = item.sourceLabel
            tintBar(holder.bar, item)
        }

        override fun getItemCount(): Int = items.size

        class VH(view: View) : RecyclerView.ViewHolder(view) {
            val title: TextView = view.findViewById(R.id.estimate_title)
            val status: TextView = view.findViewById(R.id.estimate_status)
            val eta: TextView = view.findViewById(R.id.estimate_eta)
            val bar: ProgressBar = view.findViewById(R.id.estimate_bar)
            val source: TextView = view.findViewById(R.id.estimate_source)
        }
    }

    companion object {
        private const val MENU_FOLDERS = 1001

        private fun bindEstimate(holder: UnreadAdapter.VH, estimate: SeriesEstimate?) {
            if (estimate == null) {
                holder.eta.visibility = View.GONE
                holder.bar.visibility = View.GONE
                return
            }
            holder.eta.visibility = View.VISIBLE
            holder.bar.visibility = View.VISIBLE
            holder.eta.text = formatEta(holder.itemView.context, estimate)
            tintBar(holder.bar, estimate)
        }

        private fun tintBar(bar: ProgressBar, estimate: SeriesEstimate) {
            val progress = (estimate.releaseProgress() * 1000f).toInt().coerceIn(0, 1000)
            bar.max = 1000
            bar.progress = progress
            bar.progressTintList = ColorStateList.valueOf(estimate.accentColor)
        }

        fun formatEta(ctx: android.content.Context, estimate: SeriesEstimate): String {
            if (estimate.status == MonitoredFolderEntity.SERIES_ENDED) {
                return ctx.getString(R.string.pull_eta_ended)
            }
            val days = estimate.daysUntil()
            val gap = estimate.typicalGapDays
            return when {
                days == null -> ctx.getString(R.string.pull_eta_unknown)
                days < 0 && gap != null -> ctx.getString(R.string.pull_eta_overdue, gap)
                days <= 0 -> ctx.getString(R.string.pull_eta_today)
                days == 1 -> ctx.getString(R.string.pull_eta_tomorrow)
                else -> ctx.getString(R.string.pull_eta_days, days)
            }
        }
    }
}
