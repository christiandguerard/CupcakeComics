package com.cupcakecomics.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
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
import com.cupcakecomics.data.SmbShareEntity
import com.nkanaev.comics.R
import com.nkanaev.comics.activity.MainActivity
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class ConnectionsFragment : Fragment() {
    private lateinit var repo: ConnectionRepository
    private lateinit var empty: TextView
    private lateinit var kapEmpty: TextView

    private val smbAdapter = ShareAdapter(
        onOpen = { share ->
            (activity as MainActivity).pushFragment(SmbBrowseFragment.newInstance(share.id))
        },
        onDelete = { share -> confirmDeleteShare(share) },
    )

    private val kapAdapter = KapAdapter(
        onDelete = { profile -> confirmDeleteKap(profile) },
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repo = ConnectionRepository(requireContext())
        requireActivity().title = getString(R.string.menu_connections)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val view = inflater.inflate(R.layout.fragment_connections, container, false)
        empty = view.findViewById(R.id.connections_empty)
        kapEmpty = view.findViewById(R.id.connections_kap_empty)

        val smbList = view.findViewById<RecyclerView>(R.id.connections_smb_list)
        smbList.layoutManager = LinearLayoutManager(requireContext())
        smbList.adapter = smbAdapter

        val kapList = view.findViewById<RecyclerView>(R.id.connections_kap_list)
        kapList.layoutManager = LinearLayoutManager(requireContext())
        kapList.adapter = kapAdapter

        view.findViewById<Button>(R.id.connections_add).setOnClickListener {
            showAddChooser()
        }

        viewLifecycleOwner.lifecycleScope.launch {
            launch {
                repo.smbShares.collectLatest { shares ->
                    smbAdapter.submit(shares)
                    empty.visibility = if (shares.isEmpty()) View.VISIBLE else View.GONE
                }
            }
            repo.kapowarrProfiles.collectLatest { profiles ->
                kapAdapter.submit(profiles)
                kapEmpty.visibility = if (profiles.isEmpty()) View.VISIBLE else View.GONE
            }
        }
        return view
    }

    private fun showAddChooser() {
        val options = arrayOf(
            getString(R.string.connections_add_smb),
            getString(R.string.connections_add_kapowarr_option),
        )
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.connections_add_choose_title)
            .setItems(options) { _, which ->
                val frag = when (which) {
                    0 -> SmbEditFragment()
                    else -> KapowarrEditFragment()
                }
                (activity as MainActivity).pushFragment(frag)
            }
            .show()
    }

    private fun confirmDeleteShare(share: SmbShareEntity) {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.connections_delete_title)
            .setMessage(getString(R.string.connections_delete_message, share.displayName))
            .setPositiveButton(android.R.string.ok) { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    repo.deleteSmbShare(share.id)
                    Toast.makeText(requireContext(), R.string.connections_deleted, Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun confirmDeleteKap(profile: KapowarrProfileEntity) {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.connections_delete_kap_title)
            .setMessage(getString(R.string.connections_delete_kap_message, profile.displayName))
            .setPositiveButton(android.R.string.ok) { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    repo.deleteKapowarrProfile(profile.id)
                    Toast.makeText(requireContext(), R.string.connections_deleted, Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private class ShareAdapter(
        private val onOpen: (SmbShareEntity) -> Unit,
        private val onDelete: (SmbShareEntity) -> Unit,
    ) : RecyclerView.Adapter<ShareAdapter.VH>() {
        private var items: List<SmbShareEntity> = emptyList()

        fun submit(next: List<SmbShareEntity>) {
            items = next
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_simple_row, parent, false)
            return VH(view)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items[position]
            holder.title.text = item.displayName
            holder.subtitle.text = "\\\\${item.host}\\${item.shareName}"
            holder.icon.setImageResource(R.drawable.ic_folder_open_24)
            holder.itemView.setOnClickListener { onOpen(item) }
            holder.itemView.setOnLongClickListener {
                onDelete(item)
                true
            }
        }

        override fun getItemCount(): Int = items.size

        class VH(view: View) : RecyclerView.ViewHolder(view) {
            val icon: ImageView = view.findViewById(R.id.row_icon)
            val title: TextView = view.findViewById(R.id.row_title)
            val subtitle: TextView = view.findViewById(R.id.row_subtitle)
        }
    }

    private class KapAdapter(
        private val onDelete: (KapowarrProfileEntity) -> Unit,
    ) : RecyclerView.Adapter<KapAdapter.VH>() {
        private var items: List<KapowarrProfileEntity> = emptyList()

        fun submit(next: List<KapowarrProfileEntity>) {
            items = next
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_simple_row, parent, false)
            return VH(view)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items[position]
            holder.title.text = item.displayName
            holder.subtitle.text = item.baseUrl
            holder.icon.setImageResource(R.drawable.ic_search_white_24dp)
            holder.itemView.setOnLongClickListener {
                onDelete(item)
                true
            }
        }

        override fun getItemCount(): Int = items.size

        class VH(view: View) : RecyclerView.ViewHolder(view) {
            val icon: ImageView = view.findViewById(R.id.row_icon)
            val title: TextView = view.findViewById(R.id.row_title)
            val subtitle: TextView = view.findViewById(R.id.row_subtitle)
        }
    }
}
