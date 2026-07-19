package com.cupcakecomics.ui

import android.os.Bundle
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.cupcakecomics.data.ConnectionRepository
import com.cupcakecomics.data.KapowarrProfileEntity
import com.cupcakecomics.kapowarr.KapowarrClient
import com.cupcakecomics.kapowarr.KapowarrError
import com.cupcakecomics.kapowarr.KapowarrException
import com.cupcakecomics.kapowarr.KapowarrQueueItem
import com.cupcakecomics.kapowarr.KapowarrRootFolder
import com.cupcakecomics.kapowarr.KapowarrSearchResult
import com.cupcakecomics.pulllist.PullListRepository
import com.cupcakecomics.smb.SmbBrowser
import com.nkanaev.comics.R
import com.nkanaev.comics.activity.MainActivity
import com.squareup.picasso.Picasso
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class RequestFragment : Fragment() {
    private lateinit var repo: ConnectionRepository
    private lateinit var client: KapowarrClient
    private lateinit var status: TextView
    private lateinit var queueHeader: TextView
    private lateinit var queueEmpty: TextView
    private lateinit var queueList: RecyclerView
    private lateinit var picasso: Picasso
    private var profile: KapowarrProfileEntity? = null
    private var rootFolders: List<KapowarrRootFolder> = emptyList()
    private var queuePollJob: Job? = null

    private val adapter = ResultAdapter { item -> onPick(item) }
    private val queueAdapter = QueueAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repo = ConnectionRepository(requireContext())
        client = KapowarrClient(repo.credentialStore())
        requireActivity().title = getString(R.string.menu_request)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val view = inflater.inflate(R.layout.fragment_request, container, false)
        status = view.findViewById(R.id.request_status)
        queueHeader = view.findViewById(R.id.request_queue_header)
        queueEmpty = view.findViewById(R.id.request_queue_empty)
        queueList = view.findViewById(R.id.request_queue)
        picasso = (requireActivity() as MainActivity).picasso
        adapter.picasso = picasso

        val query = view.findViewById<EditText>(R.id.request_query)
        val list = view.findViewById<RecyclerView>(R.id.request_results)
        list.layoutManager = LinearLayoutManager(requireContext())
        list.adapter = adapter

        queueList.layoutManager = LinearLayoutManager(requireContext())
        queueList.adapter = queueAdapter
        (requireActivity() as MainActivity).setSubTitle(null)

        viewLifecycleOwner.lifecycleScope.launch {
            val profiles = repo.getAllKapowarrProfiles()
            if (profiles.isEmpty()) {
                status.text = getString(R.string.request_no_profile)
                AlertDialog.Builder(requireContext())
                    .setTitle(R.string.menu_request)
                    .setMessage(R.string.request_setup_prompt)
                    .setPositiveButton(R.string.connections_add_kapowarr) { _, _ ->
                        (activity as MainActivity).pushFragment(KapowarrEditFragment())
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
                return@launch
            }
            profile = profiles.first()
            status.text = getString(R.string.request_using_profile, profile!!.displayName)
            val roots = withContext(Dispatchers.IO) { client.rootFolders(profile!!) }
            roots.onSuccess {
                rootFolders = it
            }.onFailure {
                status.text = friendly(it)
            }
            startQueuePolling()
        }

        view.findViewById<Button>(R.id.request_search).setOnClickListener {
            runSearch(query)
        }
        query.setOnEditorActionListener { v, actionId, event ->
            val submit = actionId == EditorInfo.IME_ACTION_SEARCH ||
                actionId == EditorInfo.IME_ACTION_DONE ||
                (event?.keyCode == android.view.KeyEvent.KEYCODE_ENTER &&
                    event.action == android.view.KeyEvent.ACTION_UP)
            if (!submit) return@setOnEditorActionListener false
            runSearch(query)
            v.clearFocus()
            val imm = requireContext().getSystemService(InputMethodManager::class.java)
            imm?.hideSoftInputFromWindow(v.windowToken, 0)
            true
        }
        return view
    }

    private fun runSearch(query: EditText) {
        val q = query.text?.toString().orEmpty().trim()
        if (q.isEmpty()) return
        val p = profile
        if (p == null) {
            status.text = getString(R.string.request_no_profile)
            return
        }
        status.text = getString(R.string.request_searching)
        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { client.search(p, q) }
            result.onSuccess { items ->
                adapter.submit(items)
                status.text = getString(R.string.request_results_count, items.size)
            }.onFailure {
                adapter.submit(emptyList())
                status.text = friendly(it)
            }
        }
    }

    override fun onDestroyView() {
        queuePollJob?.cancel()
        queuePollJob = null
        super.onDestroyView()
    }

    private fun startQueuePolling() {
        queuePollJob?.cancel()
        queueHeader.visibility = View.VISIBLE
        queuePollJob = viewLifecycleOwner.lifecycleScope.launch {
            while (isActive) {
                refreshQueue()
                delay(3_000)
            }
        }
    }

    private suspend fun refreshQueue() {
        val p = profile ?: return
        val result = withContext(Dispatchers.IO) { client.downloadQueue(p) }
        result.onSuccess { items ->
            queueAdapter.submit(items)
            if (items.isEmpty()) {
                queueHeader.text = getString(R.string.request_queue_header)
                queueEmpty.visibility = View.VISIBLE
                queueList.visibility = View.GONE
            } else {
                queueHeader.text = getString(R.string.request_queue_count, items.size)
                queueEmpty.visibility = View.GONE
                queueList.visibility = View.VISIBLE
            }
        }.onFailure {
            // Keep last known queue; don't spam status while searching.
            if (queueAdapter.itemCount == 0) {
                queueEmpty.visibility = View.VISIBLE
                queueEmpty.text = friendly(it)
                queueList.visibility = View.GONE
            }
        }
    }

    private fun onPick(item: KapowarrSearchResult) {
        val p = profile ?: return
        if (item.alreadyAdded) {
            Toast.makeText(requireContext(), R.string.kapowarr_err_already, Toast.LENGTH_SHORT).show()
            return
        }
        if (rootFolders.isEmpty()) {
            status.text = getString(R.string.kapowarr_err_no_root)
            return
        }
        if (rootFolders.size == 1) {
            addNow(p, item, rootFolders[0])
            return
        }
        val labels = rootFolders.map { it.folder.ifBlank { "Root #${it.id}" } }.toTypedArray()
        AlertDialog.Builder(requireContext())
            .setTitle(item.title)
            .setItems(labels) { _, which ->
                addNow(p, item, rootFolders[which])
            }
            .show()
    }

    private fun addNow(
        p: KapowarrProfileEntity,
        item: KapowarrSearchResult,
        root: KapowarrRootFolder,
    ) {
        viewLifecycleOwner.lifecycleScope.launch {
            status.text = getString(R.string.request_adding)
            val result = withContext(Dispatchers.IO) {
                client.addVolume(p, item.comicvineId, root.id, autoSearch = true)
                    .map { added ->
                        val folder = added.folderPath ?: added.volumeId?.let { id ->
                            client.volumeFolder(p, id).getOrNull()
                        }
                        added.copy(folderPath = folder)
                    }
            }
            result.onSuccess { added ->
                Toast.makeText(requireContext(), R.string.request_added, Toast.LENGTH_LONG).show()
                status.text = getString(R.string.request_added)
                refreshQueue()
                promptForPullList(item, root, added.folderPath)
            }.onFailure {
                status.text = friendly(it)
            }
        }
    }

    private fun promptForPullList(
        item: KapowarrSearchResult,
        root: KapowarrRootFolder,
        actualFolder: String?,
    ) {
        AlertDialog.Builder(requireContext())
            .setTitle(item.title)
            .setMessage(R.string.request_add_to_pull_message)
            .setPositiveButton(R.string.request_add_to_pull_yes) { _, _ ->
                choosePullListShare(item, root, actualFolder)
            }
            .setNegativeButton(R.string.request_add_to_pull_no, null)
            .show()
    }

    private fun choosePullListShare(
        item: KapowarrSearchResult,
        root: KapowarrRootFolder,
        actualFolder: String?,
    ) {
        viewLifecycleOwner.lifecycleScope.launch {
            val shares = repo.getAllSmbShares()
            if (shares.isEmpty()) {
                Toast.makeText(requireContext(), R.string.request_pull_no_share, Toast.LENGTH_LONG).show()
                return@launch
            }
            val enroll: (com.cupcakecomics.data.SmbShareEntity) -> Unit = { share ->
                viewLifecycleOwner.lifecycleScope.launch {
                    val suffix = seriesFolderSuffix(root.folder, actualFolder, item.title)
                    val relative = SmbBrowser.normalizePath(
                        listOf(share.startPath, suffix)
                            .filter { it.isNotBlank() }
                            .joinToString("/"),
                    )
                    withContext(Dispatchers.IO) {
                        PullListRepository(requireContext()).enrollFolder(
                            share.id,
                            relative,
                            item.title,
                            comicvineId = item.comicvineId,
                        )
                    }
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.request_pull_added, item.title),
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
            if (shares.size == 1) {
                enroll(shares.first())
            } else {
                AlertDialog.Builder(requireContext())
                    .setTitle(R.string.request_pull_choose_library)
                    .setItems(shares.map { it.displayName }.toTypedArray()) { _, which ->
                        enroll(shares[which])
                    }
                    .show()
            }
        }
    }

    private fun seriesFolderSuffix(rootFolder: String, actualFolder: String?, fallback: String): String {
        val root = rootFolder.replace('\\', '/').trimEnd('/')
        val actual = actualFolder?.replace('\\', '/')?.trimEnd('/').orEmpty()
        if (actual.isBlank()) return fallback
        if (root.isNotBlank() && actual.startsWith(root, ignoreCase = true)) {
            return actual.substring(root.length).trim('/')
        }
        return actual.substringAfterLast('/').ifBlank { fallback }
    }

    private fun friendly(err: Throwable): String {
        val code = (err as? KapowarrException)?.code
        return when (code) {
            KapowarrError.AUTH_INVALID -> getString(R.string.kapowarr_err_auth)
            KapowarrError.SERVER_UNREACHABLE -> getString(R.string.kapowarr_err_unreachable)
            KapowarrError.CLEARTEXT_BLOCKED -> getString(R.string.kapowarr_err_cleartext)
            KapowarrError.NO_ROOT_FOLDER -> getString(R.string.kapowarr_err_no_root)
            KapowarrError.ALREADY_ADDED -> getString(R.string.kapowarr_err_already)
            else -> err.message ?: getString(R.string.kapowarr_err_unknown)
        }
    }

    private class ResultAdapter(
        private val onClick: (KapowarrSearchResult) -> Unit,
    ) : RecyclerView.Adapter<ResultAdapter.VH>() {
        var picasso: Picasso? = null
        private var items: List<KapowarrSearchResult> = emptyList()

        fun submit(next: List<KapowarrSearchResult>) {
            items = next
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_request_result, parent, false)
            return VH(view)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items[position]
            val year = item.year?.toString() ?: "?"
            holder.title.text = item.title
            holder.subtitle.text = buildString {
                append(year)
                item.volumeNumber?.let { append(" · Vol ").append(it) }
                if (item.alreadyAdded) {
                    append(" · ")
                    append(holder.itemView.context.getString(R.string.request_already_added))
                }
            }
            holder.cover.setImageResource(R.drawable.ic_article_24)
            val url = item.coverUrl
            val p = picasso
            if (!url.isNullOrBlank() && p != null) {
                p.load(url)
                    .placeholder(R.drawable.ic_article_24)
                    .error(R.drawable.ic_article_24)
                    .fit()
                    .centerCrop()
                    .into(holder.cover)
            }
            holder.itemView.setOnClickListener { onClick(item) }
            holder.itemView.alpha = if (item.alreadyAdded) 0.5f else 1f
        }

        override fun getItemCount(): Int = items.size

        class VH(view: View) : RecyclerView.ViewHolder(view) {
            val cover: ImageView = view.findViewById(R.id.request_cover)
            val title: TextView = view.findViewById(R.id.request_title)
            val subtitle: TextView = view.findViewById(R.id.request_subtitle)
        }
    }

    private class QueueAdapter : RecyclerView.Adapter<QueueAdapter.VH>() {
        private var items: List<KapowarrQueueItem> = emptyList()

        fun submit(next: List<KapowarrQueueItem>) {
            items = next
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_kapowarr_queue, parent, false)
            return VH(view)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items[position]
            val ctx = holder.itemView.context
            holder.title.text = item.title
            val statusLabel = item.status.replaceFirstChar {
                if (it.isLowerCase()) it.titlecase() else it.toString()
            }
            val progressLabel = when {
                item.sizeBytes < 0 -> ctx.getString(R.string.request_queue_unknown_size)
                else -> ctx.getString(R.string.request_queue_progress_pct, item.progress.toInt().coerceIn(0, 100))
            }
            val source = item.sourceName?.replaceFirstChar {
                if (it.isLowerCase()) it.titlecase() else it.toString()
            }
            holder.subtitle.text = buildString {
                append(statusLabel)
                append(" · ")
                append(progressLabel)
                if (!source.isNullOrBlank()) {
                    append(" · ")
                    append(source)
                }
            }
        }

        override fun getItemCount(): Int = items.size

        class VH(view: View) : RecyclerView.ViewHolder(view) {
            val title: TextView = view.findViewById(R.id.queue_title)
            val subtitle: TextView = view.findViewById(R.id.queue_subtitle)
        }
    }
}
