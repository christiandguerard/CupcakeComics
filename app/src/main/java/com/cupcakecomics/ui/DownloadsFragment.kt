package com.cupcakecomics.ui

import android.os.Bundle
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.cupcakecomics.data.DownloadJobEntity
import com.cupcakecomics.data.DownloadJobStatus
import com.cupcakecomics.downloads.DownloadQueueRepository
import com.cupcakecomics.smb.ComicFileNames
import com.nkanaev.comics.R
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Monitor for the offline download queue: live status and progress per job,
 * retry for failed jobs (individually or all at once), and clearing finished rows.
 */
class DownloadsFragment : Fragment() {
    private lateinit var repo: DownloadQueueRepository
    private lateinit var empty: TextView
    private lateinit var retryFailed: Button
    private lateinit var clearFinished: Button
    private val adapter = Adapter(onRetry = { job ->
        viewLifecycleOwner.lifecycleScope.launch { repo.retry(job.id) }
    })

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repo = DownloadQueueRepository(requireContext())
        requireActivity().title = getString(R.string.downloads_title)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val view = inflater.inflate(R.layout.fragment_downloads, container, false)
        empty = view.findViewById(R.id.downloads_empty)
        retryFailed = view.findViewById(R.id.downloads_retry_failed)
        clearFinished = view.findViewById(R.id.downloads_clear_finished)
        val list = view.findViewById<RecyclerView>(R.id.downloads_list)
        list.layoutManager = LinearLayoutManager(requireContext())
        list.adapter = adapter

        retryFailed.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                val n = repo.retryFailed()
                Toast.makeText(
                    requireContext(),
                    getString(R.string.downloads_retried_toast, n),
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
        clearFinished.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch { repo.clearFinished() }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            repo.observeAll().collectLatest { jobs ->
                adapter.submit(jobs)
                empty.visibility = if (jobs.isEmpty()) View.VISIBLE else View.GONE
                retryFailed.isEnabled = jobs.any { it.status == DownloadJobStatus.FAILED }
                clearFinished.isEnabled = jobs.any { it.status == DownloadJobStatus.SUCCEEDED }
            }
        }
        return view
    }

    private class Adapter(
        private val onRetry: (DownloadJobEntity) -> Unit,
    ) : RecyclerView.Adapter<Adapter.VH>() {
        private var items: List<DownloadJobEntity> = emptyList()

        fun submit(next: List<DownloadJobEntity>) {
            items = next
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_download_job, parent, false)
            return VH(view)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items[position]
            val ctx = holder.itemView.context
            holder.title.text = ComicFileNames.shortDisplayName(item.title)

            val bytes = if (item.bytesTotal > 0) {
                " — ${Formatter.formatShortFileSize(ctx, item.bytesDone)} / " +
                    Formatter.formatShortFileSize(ctx, item.bytesTotal)
            } else {
                ""
            }
            holder.status.text = when (item.status) {
                DownloadJobStatus.QUEUED -> ctx.getString(R.string.downloads_status_queued)
                DownloadJobStatus.RUNNING -> if (item.bytesTotal > 0) {
                    val pct = ((item.bytesDone * 100) / item.bytesTotal).toInt().coerceIn(0, 100)
                    ctx.getString(R.string.downloads_status_running_pct, pct) + bytes
                } else {
                    ctx.getString(R.string.downloads_status_running)
                }
                DownloadJobStatus.FAILED -> ctx.getString(R.string.downloads_status_failed, item.attempts)
                DownloadJobStatus.SUCCEEDED -> ctx.getString(R.string.downloads_status_done) + bytes
            }

            val showProgress = item.status == DownloadJobStatus.RUNNING && item.bytesTotal > 0
            holder.progress.visibility = if (showProgress) View.VISIBLE else View.GONE
            if (showProgress) {
                holder.progress.max = 100
                holder.progress.progress =
                    ((item.bytesDone * 100) / item.bytesTotal).toInt().coerceIn(0, 100)
            }

            val showError = item.status == DownloadJobStatus.FAILED && !item.error.isNullOrBlank()
            holder.error.visibility = if (showError) View.VISIBLE else View.GONE
            if (showError) holder.error.text = item.error

            holder.retry.visibility =
                if (item.status == DownloadJobStatus.FAILED) View.VISIBLE else View.GONE
            holder.retry.setOnClickListener { onRetry(item) }
        }

        override fun getItemCount(): Int = items.size

        class VH(view: View) : RecyclerView.ViewHolder(view) {
            val title: TextView = view.findViewById(R.id.download_row_title)
            val status: TextView = view.findViewById(R.id.download_row_status)
            val progress: ProgressBar = view.findViewById(R.id.download_row_progress)
            val error: TextView = view.findViewById(R.id.download_row_error)
            val retry: Button = view.findViewById(R.id.download_row_retry)
        }
    }
}
