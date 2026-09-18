package com.cupcakecomics.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SwitchCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.cupcakecomics.data.ReminderEntity
import com.cupcakecomics.data.ReminderType
import com.cupcakecomics.reminders.ReminderFormat
import com.cupcakecomics.reminders.ReminderRepository
import com.nkanaev.comics.R
import com.nkanaev.comics.activity.MainActivity
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class RemindersFragment : Fragment() {
    private lateinit var repo: ReminderRepository
    private lateinit var empty: TextView
    private val adapter = Adapter(
        onClick = { openEdit(it) },
        onLongClick = { confirmDelete(it) },
        onToggle = { item, enabled ->
            viewLifecycleOwner.lifecycleScope.launch {
                repo.setEnabled(item.id, enabled)
            }
        },
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repo = ReminderRepository(requireContext())
        requireActivity().title = getString(R.string.reminders_title)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val view = inflater.inflate(R.layout.fragment_reminders, container, false)
        empty = view.findViewById(R.id.reminders_empty)
        val list = view.findViewById<RecyclerView>(R.id.reminders_list)
        list.layoutManager = LinearLayoutManager(requireContext())
        list.adapter = adapter

        view.findViewById<Button>(R.id.reminders_add_pull).setOnClickListener {
            (activity as MainActivity).pushFragment(
                ReminderEditFragment.newInstance(ReminderType.PULL_LIST, 0L),
            )
        }
        view.findViewById<Button>(R.id.reminders_add_book).setOnClickListener {
            (activity as MainActivity).pushFragment(
                ReminderEditFragment.newInstance(ReminderType.BOOK, 0L),
            )
        }

        viewLifecycleOwner.lifecycleScope.launch {
            repo.observeAll().collectLatest { items ->
                adapter.submit(items)
                empty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
            }
        }
        return view
    }

    private fun openEdit(item: ReminderEntity) {
        (activity as MainActivity).pushFragment(ReminderEditFragment.newInstance(item.type, item.id))
    }

    private fun confirmDelete(item: ReminderEntity) {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.reminders_delete)
            .setMessage(R.string.reminders_delete_confirm)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    repo.delete(item.id)
                    Toast.makeText(requireContext(), R.string.reminders_deleted, Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private class Adapter(
        private val onClick: (ReminderEntity) -> Unit,
        private val onLongClick: (ReminderEntity) -> Unit,
        private val onToggle: (ReminderEntity, Boolean) -> Unit,
    ) : RecyclerView.Adapter<Adapter.VH>() {
        private var items: List<ReminderEntity> = emptyList()

        fun submit(next: List<ReminderEntity>) {
            items = next
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_reminder_row, parent, false)
            return VH(view)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items[position]
            val ctx = holder.itemView.context
            holder.title.text = ReminderFormat.title(ctx, item)
            holder.schedule.text = ReminderFormat.scheduleSummary(ctx, item)
            val mode = ReminderFormat.modeBadge(ctx, item)
            if (mode != null) {
                holder.mode.visibility = View.VISIBLE
                holder.mode.text = mode
            } else {
                holder.mode.visibility = View.GONE
            }
            holder.enabled.setOnCheckedChangeListener(null)
            holder.enabled.isChecked = item.enabled
            holder.enabled.setOnCheckedChangeListener { _, checked ->
                onToggle(item, checked)
            }
            holder.itemView.setOnClickListener { onClick(item) }
            holder.itemView.setOnLongClickListener {
                onLongClick(item)
                true
            }
        }

        override fun getItemCount(): Int = items.size

        class VH(view: View) : RecyclerView.ViewHolder(view) {
            val title: TextView = view.findViewById(R.id.reminder_row_title)
            val schedule: TextView = view.findViewById(R.id.reminder_row_schedule)
            val mode: TextView = view.findViewById(R.id.reminder_row_mode)
            val enabled: SwitchCompat = view.findViewById(R.id.reminder_row_enabled)
        }
    }
}
