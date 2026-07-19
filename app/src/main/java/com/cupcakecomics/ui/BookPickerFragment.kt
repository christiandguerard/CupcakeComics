package com.cupcakecomics.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.fragment.app.setFragmentResult
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.cupcakecomics.data.CupcakeDatabase
import com.cupcakecomics.data.LibraryRepository
import com.cupcakecomics.data.ReminderBookSource
import com.cupcakecomics.smb.ComicFileNames
import com.google.android.material.tabs.TabLayout
import com.nkanaev.comics.R
import com.nkanaev.comics.managers.Utils
import com.nkanaev.comics.model.Storage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.Serializable

data class BookPickResult(
    val source: ReminderBookSource,
    val displayTitle: String,
    val identityKey: String?,
    val libraryComicId: Int,
    val localPath: String?,
    val smbShareId: Long,
    val smbRelativePath: String?,
) : Serializable

class BookPickerFragment : Fragment() {
    private enum class Tab { LIBRARY, PULL, FILES }

    private var currentTab = Tab.LIBRARY
    private lateinit var empty: TextView
    private lateinit var chooseFile: Button
    private val adapter = RowAdapter { onPick(it) }

    private val openDocument = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@registerForActivityResult
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val repo = LibraryRepository(requireContext())
                val id = withContext(Dispatchers.IO) { repo.importLocalFromUri(uri) }
                val entity = withContext(Dispatchers.IO) { repo.getLocalFile(id) }
                val title = entity?.title
                    ?: uri.lastPathSegment?.substringAfterLast('/')
                    ?: uri.toString()
                finishPick(
                    BookPickResult(
                        source = ReminderBookSource.LOCAL,
                        displayTitle = Utils.removeExtensionIfAny(title),
                        identityKey = entity?.sourceKey ?: "local:$uri",
                        libraryComicId = 0,
                        localPath = entity?.localPath ?: uri.toString(),
                        smbShareId = 0L,
                        smbRelativePath = null,
                    ),
                )
            } catch (t: Throwable) {
                val name = uri.lastPathSegment?.substringAfterLast('/') ?: uri.toString()
                finishPick(
                    BookPickResult(
                        source = ReminderBookSource.LOCAL,
                        displayTitle = Utils.removeExtensionIfAny(name),
                        identityKey = "local:$uri",
                        libraryComicId = 0,
                        localPath = uri.toString(),
                        smbShareId = 0L,
                        smbRelativePath = null,
                    ),
                )
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requireActivity().title = getString(R.string.book_picker_title)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val view = inflater.inflate(R.layout.fragment_book_picker, container, false)
        empty = view.findViewById(R.id.book_picker_empty)
        chooseFile = view.findViewById(R.id.book_picker_choose_file)
        val list = view.findViewById<RecyclerView>(R.id.book_picker_list)
        list.layoutManager = LinearLayoutManager(requireContext())
        list.adapter = adapter

        val tabs = view.findViewById<TabLayout>(R.id.book_picker_tabs)
        tabs.addTab(tabs.newTab().setText(R.string.book_picker_tab_library))
        tabs.addTab(tabs.newTab().setText(R.string.book_picker_tab_pull))
        tabs.addTab(tabs.newTab().setText(R.string.book_picker_tab_files))
        tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                currentTab = Tab.entries[tab.position]
                reload()
            }
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })

        chooseFile.setOnClickListener {
            openDocument.launch(arrayOf("application/zip", "application/x-cbz", "application/x-cbr", "application/pdf", "*/*"))
        }
        reload()
        return view
    }

    private fun reload() {
        when (currentTab) {
            Tab.LIBRARY -> loadLibrary()
            Tab.PULL -> loadPullList()
            Tab.FILES -> showFilesTab()
        }
    }

    private fun loadLibrary() {
        listVisible(true)
        viewLifecycleOwner.lifecycleScope.launch {
            val rows = withContext(Dispatchers.IO) {
                Storage.getStorage(requireContext()).listComics().map { comic ->
                    val title = Utils.removeExtensionIfAny(comic.file.name)
                    PickerRow(
                        title = title,
                        subtitle = comic.file.parent ?: "",
                        pick = BookPickResult(
                            source = ReminderBookSource.LIBRARY,
                            displayTitle = title,
                            identityKey = null,
                            libraryComicId = comic.id,
                            localPath = comic.file.absolutePath,
                            smbShareId = 0L,
                            smbRelativePath = null,
                        ),
                    )
                }
            }
            adapter.submit(rows)
            showEmpty(rows.isEmpty(), R.string.book_picker_empty_library)
        }
    }

    private fun loadPullList() {
        listVisible(true)
        viewLifecycleOwner.lifecycleScope.launch {
            val rows = withContext(Dispatchers.IO) {
                CupcakeDatabase.get(requireContext()).pullComicDao().getPullList().map { item ->
                    PickerRow(
                        title = ComicFileNames.shortDisplayName(item.title),
                        subtitle = item.relativePath,
                        pick = BookPickResult(
                            source = ReminderBookSource.PULL,
                            displayTitle = Utils.removeExtensionIfAny(item.title),
                            identityKey = item.identityKey,
                            libraryComicId = 0,
                            localPath = null,
                            smbShareId = item.shareId,
                            smbRelativePath = item.relativePath,
                        ),
                    )
                }
            }
            adapter.submit(rows)
            showEmpty(rows.isEmpty(), R.string.book_picker_empty_pull)
        }
    }

    private fun showFilesTab() {
        adapter.submit(emptyList())
        listVisible(false)
        empty.visibility = View.GONE
        chooseFile.visibility = View.VISIBLE
    }

    private fun listVisible(showList: Boolean) {
        view?.findViewById<RecyclerView>(R.id.book_picker_list)?.visibility =
            if (showList) View.VISIBLE else View.GONE
        chooseFile.visibility = View.GONE
    }

    private fun showEmpty(isEmpty: Boolean, messageRes: Int) {
        empty.visibility = if (isEmpty) View.VISIBLE else View.GONE
        empty.setText(messageRes)
        chooseFile.visibility = View.GONE
    }

    private fun onPick(pick: BookPickResult) {
        finishPick(pick)
    }

    private fun finishPick(pick: BookPickResult) {
        setFragmentResult(REQUEST_KEY, bundleOf(BUNDLE_KEY to pick))
        parentFragmentManager.popBackStack()
    }

    private data class PickerRow(val title: String, val subtitle: String, val pick: BookPickResult)

    private class RowAdapter(
        private val onClick: (BookPickResult) -> Unit,
    ) : RecyclerView.Adapter<RowAdapter.VH>() {
        private var items: List<PickerRow> = emptyList()

        fun submit(next: List<PickerRow>) {
            items = next
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_simple_row, parent, false)
            return VH(view)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items[position]
            holder.title.text = item.title
            holder.subtitle.text = item.subtitle
            holder.itemView.setOnClickListener { onClick(item.pick) }
        }

        override fun getItemCount(): Int = items.size

        class VH(view: View) : RecyclerView.ViewHolder(view) {
            val title: TextView = view.findViewById(R.id.row_title)
            val subtitle: TextView = view.findViewById(R.id.row_subtitle)
        }
    }

    companion object {
        const val REQUEST_KEY = "book_pick"
        const val BUNDLE_KEY = "book_pick_result"
    }
}
