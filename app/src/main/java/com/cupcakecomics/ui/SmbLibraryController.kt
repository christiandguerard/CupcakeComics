package com.cupcakecomics.ui

import android.content.Context
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.cupcakecomics.data.ConnectionRepository
import com.cupcakecomics.data.SmbShareEntity
import com.cupcakecomics.smb.SmbLibraryScanner
import com.nkanaev.comics.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Binds SMB share cards into the Library screen (display name + comic count + total size).
 */
class SmbLibraryController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val header: TextView,
    private val list: RecyclerView,
    private val onOpenShare: (SmbShareEntity) -> Unit,
    private val onSharesChanged: (hasShares: Boolean) -> Unit,
) {
    private val repo = ConnectionRepository(context)
    private val scanner = SmbLibraryScanner(repo.credentialStore())
    private val adapter = Adapter(onOpenShare)
    private var collectJob: Job? = null
    private var scanJob: Job? = null

    init {
        list.layoutManager = LinearLayoutManager(context)
        list.adapter = adapter
        list.isNestedScrollingEnabled = false
    }

    fun start() {
        collectJob?.cancel()
        collectJob = scope.launch {
            repo.restoreCachedStatsIfNeeded()
            repo.smbShares.collectLatest { shares ->
                adapter.submit(shares)
                val visible = shares.isNotEmpty()
                header.visibility = if (visible) View.VISIBLE else View.GONE
                list.visibility = if (visible) View.VISIBLE else View.GONE
                onSharesChanged(visible)
                refreshStaleStats(shares)
            }
        }
    }

    fun stop() {
        collectJob?.cancel()
        scanJob?.cancel()
    }

    fun refreshAllStats() {
        scope.launch {
            val shares = repo.getAllSmbShares()
            shares.forEach { scanAndStore(it) }
        }
    }

    private fun refreshStaleStats(shares: List<SmbShareEntity>) {
        val stale = shares.filter {
            it.comicCount < 0 || System.currentTimeMillis() - it.statsUpdatedAt > STALE_MS
        }
        if (stale.isEmpty()) return
        scanJob?.cancel()
        scanJob = scope.launch {
            stale.forEach { scanAndStore(it) }
        }
    }

    private suspend fun scanAndStore(share: SmbShareEntity) {
        val result = withContext(Dispatchers.IO) { scanner.scan(share) }
        result.onSuccess { stats ->
            repo.updateSmbStats(share.id, stats.comicCount, stats.totalBytes)
        }
    }

    private class Adapter(
        private val onOpen: (SmbShareEntity) -> Unit,
    ) : RecyclerView.Adapter<Adapter.VH>() {
        private var items: List<SmbShareEntity> = emptyList()

        fun submit(next: List<SmbShareEntity>) {
            items = next
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_smb_library_card, parent, false)
            return VH(view)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items[position]
            holder.name.text = item.displayName
            holder.stats.text = formatStats(holder.itemView.context, item)
            holder.itemView.setOnClickListener { onOpen(item) }
        }

        override fun getItemCount(): Int = items.size

        private fun formatStats(context: Context, item: SmbShareEntity): String {
            if (item.comicCount < 0) {
                return context.getString(R.string.library_smb_scanning)
            }
            val size = Formatter.formatShortFileSize(context, item.totalBytes.coerceAtLeast(0L))
            return context.getString(R.string.library_smb_stats, item.comicCount, size)
        }

        class VH(view: View) : RecyclerView.ViewHolder(view) {
            val name: TextView = view.findViewById(R.id.smb_lib_name)
            val stats: TextView = view.findViewById(R.id.smb_lib_stats)
        }
    }

    companion object {
        private const val STALE_MS = 30L * 60L * 1000L
    }
}
