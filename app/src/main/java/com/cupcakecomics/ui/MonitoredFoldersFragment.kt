package com.cupcakecomics.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.cupcakecomics.data.MonitoredFolderEntity
import com.cupcakecomics.pulllist.PullListRepository
import com.nkanaev.comics.R
import com.nkanaev.comics.activity.MainActivity
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** Lists enrolled Pull List folders and lets the user stop monitoring them. */
class MonitoredFoldersFragment : Fragment() {
    private lateinit var repo: PullListRepository
    private lateinit var empty: TextView
    private val adapter = Adapter { folder -> confirmUnenroll(folder) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repo = PullListRepository(requireContext())
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val view = inflater.inflate(R.layout.fragment_monitored_folders, container, false)
        empty = view.findViewById(R.id.monitored_folders_empty)
        val list = view.findViewById<RecyclerView>(R.id.monitored_folders_list)
        list.layoutManager = LinearLayoutManager(requireContext())
        list.adapter = adapter
        requireActivity().title = getString(R.string.monitored_folders_title)
        (requireActivity() as MainActivity).setSubTitle(null)

        viewLifecycleOwner.lifecycleScope.launch {
            repo.monitoredFolders.collectLatest { folders ->
                adapter.submit(folders)
                empty.visibility = if (folders.isEmpty()) View.VISIBLE else View.GONE
            }
        }
        return view
    }

    private fun confirmUnenroll(folder: MonitoredFolderEntity) {
        AlertDialog.Builder(requireContext())
            .setTitle(folder.displayName)
            .setMessage(R.string.monitored_folders_unenroll_confirm)
            .setPositiveButton(R.string.monitored_folders_unenroll) { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    repo.unenrollFolder(folder.id)
                    Toast.makeText(requireContext(), R.string.monitored_folders_unenrolled, Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private class Adapter(
        private val onUnenroll: (MonitoredFolderEntity) -> Unit,
    ) : RecyclerView.Adapter<Adapter.VH>() {
        private var items: List<MonitoredFolderEntity> = emptyList()

        fun submit(next: List<MonitoredFolderEntity>) {
            items = next
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_monitored_folder, parent, false)
            return VH(view)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items[position]
            val path = item.relativePath.ifBlank { "/" }
            holder.title.text = item.displayName
            holder.subtitle.text = holder.itemView.context.getString(R.string.monitored_folder_path_recursive, path)
            holder.action.setOnClickListener { onUnenroll(item) }
        }

        override fun getItemCount(): Int = items.size

        class VH(view: View) : RecyclerView.ViewHolder(view) {
            val title: TextView = view.findViewById(R.id.monitored_folder_title)
            val subtitle: TextView = view.findViewById(R.id.monitored_folder_subtitle)
            val action: TextView = view.findViewById(R.id.monitored_folder_unenroll)
        }
    }
}
