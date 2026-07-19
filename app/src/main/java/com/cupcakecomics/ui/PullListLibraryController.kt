package com.cupcakecomics.ui

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.cupcakecomics.cover.FileCoverHandler
import com.cupcakecomics.cover.SmbNetworkCoverCache
import com.cupcakecomics.data.ConnectionRepository
import com.cupcakecomics.data.LibraryRepository
import com.cupcakecomics.data.PullComicEntity
import com.cupcakecomics.pulllist.PullListRepository
import com.cupcakecomics.settings.CupcakeSettings
import com.cupcakecomics.smb.SmbStageManager
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.Serializable

/** Cover-grid Pull List section embedded on Library home. */
class PullListLibraryController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val headerRow: View,
    private val header: TextView,
    private val settingsButton: View,
    private val chevron: ImageView,
    private val list: RecyclerView,
    private val empty: TextView,
    private val onHasItems: (Boolean) -> Unit,
) {
    private val pullRepo = PullListRepository(context)
    private val connections = ConnectionRepository(context)
    private val libraryRepo = LibraryRepository(context)
    private val settings = CupcakeSettings(context)
    private val prefs: SharedPreferences =
        context.getSharedPreferences("cupcake_library_ui", Context.MODE_PRIVATE)
    private val picasso: Picasso = (context as MainActivity).picasso
    private val adapter = Adapter(
        picasso = picasso,
        libraryRepo = libraryRepo,
        connections = connections,
        scope = scope,
        hideTitles = { settings.hideCoverTitles },
        onClick = { openComic(it) },
        onLongClick = { showActions(it); true },
    )
    private var collectJob: Job? = null
    private var comics: List<PullComicEntity> = emptyList()
    private var expanded = prefs.getBoolean(PREF_EXPANDED, true)
    private var etaJob: Job? = null

    init {
        refreshSpan()
        list.adapter = adapter
        list.isNestedScrollingEnabled = false
        list.overScrollMode = View.OVER_SCROLL_NEVER
        headerRow.setOnClickListener { toggleExpanded() }
        settingsButton.setOnClickListener { showPullMenu(it) }
        empty.setOnClickListener { openPullList() }
        applyExpanded()
    }

    fun start() {
        collectJob?.cancel()
        collectJob = scope.launch {
            launch {
                pullRepo.pullList.collectLatest { items ->
                    comics = items
                    val has = items.isNotEmpty()
                    headerRow.visibility = View.VISIBLE
                    onHasItems(has)
                    adapter.submit(items)
                    applyExpanded()
                    if (!has) refreshEta()
                }
            }
            launch(Dispatchers.IO) { pullRepo.scanAll() }
        }
    }

    fun stop() {
        collectJob?.cancel()
        etaJob?.cancel()
    }

    fun refreshSpan() {
        val span = CoverGridHelper.spanCount(context, settings)
        list.layoutManager = GridLayoutManager(context, span)
    }

    fun refreshTitlesVisibility() {
        adapter.notifyDataSetChanged()
    }

    private fun toggleExpanded() {
        expanded = !expanded
        prefs.edit().putBoolean(PREF_EXPANDED, expanded).apply()
        applyExpanded()
    }

    private fun applyExpanded() {
        val has = comics.isNotEmpty()
        header.text = if (has) {
            context.getString(R.string.menu_pull_list) + " (${comics.size})"
        } else {
            context.getString(R.string.menu_pull_list)
        }
        chevron.setImageResource(
            if (expanded) R.drawable.ic_expand_less_24 else R.drawable.ic_expand_more_24,
        )
        list.visibility = if (has && expanded) View.VISIBLE else View.GONE
        empty.visibility = if (!has && expanded) View.VISIBLE else View.GONE
        if (!has && expanded && empty.text.isNullOrBlank()) {
            empty.setText(R.string.library_pull_empty_short)
        }
    }

    private fun refreshEta() {
        etaJob?.cancel()
        etaJob = scope.launch {
            empty.setText(R.string.library_pull_empty_short)
            val folders = withContext(Dispatchers.IO) {
                pullRepo.monitoredFolders.first()
            }
            if (folders.isEmpty()) {
                empty.setText(R.string.library_pull_empty_short)
                return@launch
            }
            val summary = withContext(Dispatchers.IO) {
                com.cupcakecomics.pulllist.PullCadenceEstimator.summarize(context, folders)
            }
            if (comics.isNotEmpty()) return@launch
            val body = buildString {
                append(context.getString(R.string.pull_list_eta_header))
                if (summary.lines.isEmpty()) {
                    append("\n")
                    append(context.getString(R.string.pull_list_eta_none))
                } else {
                    for (line in summary.lines.take(4)) {
                        append("\n")
                        append(line)
                    }
                }
            }
            empty.text = body
        }
    }

    private fun showPullMenu(anchor: View) {
        val popup = android.widget.PopupMenu(context, anchor)
        popup.menu.add(0, 1, 0, R.string.pull_list_open)
        popup.menu.add(0, 2, 1, R.string.pull_list_manage_folders)
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> openPullList()
                2 -> openMonitoredFolders()
            }
            true
        }
        popup.show()
    }

    private fun openPullList() {
        (context as? MainActivity)?.pushFragment(PullListFragment())
    }

    private fun openMonitoredFolders() {
        (context as? MainActivity)?.pushFragment(MonitoredFoldersFragment())
    }

    private fun openComic(item: PullComicEntity) {
        scope.launch {
            val share = connections.getSmbShare(item.shareId)
            if (share == null) {
                Toast.makeText(context, R.string.smb_share_missing, Toast.LENGTH_SHORT).show()
                return@launch
            }
            val dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_download_progress, null)
            val titleView = dialogView.findViewById<TextView>(R.id.download_title)
            val bar = dialogView.findViewById<android.widget.ProgressBar>(R.id.download_progress)
            val bytesView = dialogView.findViewById<TextView>(R.id.download_bytes)
            titleView.text = Utils.removeExtensionIfAny(item.title)
            bar.isIndeterminate = true
            val dialog = AlertDialog.Builder(context)
                .setView(dialogView)
                .setCancelable(false)
                .create()
            dialog.show()
            val stage = SmbStageManager(context, connections.credentialStore())
            val result = withContext(Dispatchers.IO) {
                stage.stage(share, item.relativePath, keepOffline = false) { done, total ->
                    bar.isIndeterminate = false
                    bar.max = 1000
                    if (total > 0) {
                        bar.progress = ((done * 1000L) / total).toInt().coerceIn(0, 1000)
                    }
                    bytesView.text = context.getString(
                        R.string.smb_download_bytes,
                        Formatter.formatShortFileSize(context, done),
                        Formatter.formatShortFileSize(context, total),
                    )
                }
            }
            dialog.dismiss()
            result.onSuccess { file ->
                val intent = Intent(context, ReaderActivity::class.java)
                intent.putExtra(ReaderFragment.PARAM_HANDLER, file as Serializable)
                intent.putExtra(ReaderFragment.PARAM_MODE, ReaderFragment.Mode.MODE_BROWSER)
                intent.putExtra(ReaderFragment.PARAM_IDENTITY_KEY, item.identityKey)
                context.startActivity(intent)
            }.onFailure {
                Toast.makeText(
                    context,
                    context.getString(R.string.smb_stage_fail, it.message ?: ""),
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    private fun showActions(item: PullComicEntity) {
        val labels = arrayOf(
            context.getString(R.string.action_mark_read),
            context.getString(R.string.action_mark_unread),
            context.getString(R.string.pull_list_ignore),
        )
        AlertDialog.Builder(context)
            .setTitle(Utils.removeExtensionIfAny(item.title))
            .setItems(labels) { _, which ->
                scope.launch {
                    when (which) {
                        0 -> pullRepo.markRead(item.identityKey)
                        1 -> pullRepo.markUnread(item.identityKey)
                        2 -> pullRepo.ignoreFromPull(item.identityKey)
                    }
                }
            }
            .show()
    }

    private class Adapter(
        private val picasso: Picasso,
        private val libraryRepo: LibraryRepository,
        private val connections: ConnectionRepository,
        private val scope: CoroutineScope,
        private val hideTitles: () -> Boolean,
        private val onClick: (PullComicEntity) -> Unit,
        private val onLongClick: (PullComicEntity) -> Boolean,
    ) : RecyclerView.Adapter<Adapter.VH>() {
        private var items: List<PullComicEntity> = emptyList()

        fun submit(next: List<PullComicEntity>) {
            items = next
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
            holder.title.visibility = if (hide) View.GONE else View.VISIBLE
            if (!hide) {
                holder.title.text = Utils.removeExtensionIfAny(item.title)
            }
            holder.cover.setImageResource(R.drawable.ic_collections_image_24)
            holder.cover.tag = item.identityKey
            holder.selected.visibility = View.GONE
            scope.launch {
                val offline = withContext(Dispatchers.IO) {
                    libraryRepo.getOfflineBySource(item.identityKey)
                }
                if (holder.cover.tag != item.identityKey) return@launch
                if (offline != null) {
                    picasso.load(FileCoverHandler.uriFor(offline.localPath))
                        .placeholder(R.drawable.ic_collections_image_24)
                        .error(R.drawable.ic_collections_image_24)
                        .fit()
                        .centerCrop()
                        .into(holder.cover)
                    return@launch
                }
                val networkCover = withContext(Dispatchers.IO) {
                    val share = connections.getSmbShare(item.shareId) ?: return@withContext null
                    SmbNetworkCoverCache.ensureComicCover(
                        holder.itemView.context.applicationContext,
                        share,
                        item.relativePath,
                        connections.credentialStore(),
                        libraryRepo,
                    )
                }
                if (holder.cover.tag == item.identityKey && networkCover != null) {
                    picasso.load(File(networkCover))
                        .placeholder(R.drawable.ic_collections_image_24)
                        .error(R.drawable.ic_collections_image_24)
                        .fit()
                        .centerCrop()
                        .into(holder.cover)
                }
            }
            holder.itemView.setOnClickListener { onClick(item) }
            holder.itemView.setOnLongClickListener { onLongClick(item) }
        }

        override fun getItemCount(): Int = items.size

        class VH(view: View) : RecyclerView.ViewHolder(view) {
            val cover: CoverImageView = view.findViewById(R.id.offline_cover)
            val title: TextView = view.findViewById(R.id.offline_title)
            val selected: View = view.findViewById(R.id.offline_selected)
        }
    }

    companion object {
        private const val PREF_EXPANDED = "pull_section_expanded"
    }
}
