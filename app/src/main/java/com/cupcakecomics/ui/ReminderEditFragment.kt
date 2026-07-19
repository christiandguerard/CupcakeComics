package com.cupcakecomics.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.cupcakecomics.data.CalendarCompat
import com.cupcakecomics.data.ReminderBookSource
import com.cupcakecomics.data.ReminderEntity
import com.cupcakecomics.data.ReminderFrequency
import com.cupcakecomics.data.ReminderPageMode
import com.cupcakecomics.data.ReminderType
import com.cupcakecomics.reminders.ReminderRepository
import com.nkanaev.comics.R
import com.nkanaev.comics.activity.MainActivity
import kotlinx.coroutines.launch

class ReminderEditFragment : Fragment() {
    private lateinit var repo: ReminderRepository
    private var bindingSpinners = false
    private var editing: ReminderEntity? = null
    private var pickedBook: BookPickResult? = null

    private lateinit var enabledBox: CheckBox
    private lateinit var frequencySpinner: Spinner
    private lateinit var hourSpinner: Spinner
    private lateinit var weeklyRow: View
    private lateinit var monthlyRow: View
    private lateinit var dayOfWeekSpinner: Spinner
    private lateinit var dayOfMonthSpinner: Spinner
    private lateinit var bookSection: View
    private lateinit var pageModeSpinner: Spinner
    private lateinit var bookLabel: TextView
    private lateinit var deleteButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repo = ReminderRepository(requireContext())
        val type = ReminderType.valueOf(requireArguments().getString(ARG_TYPE) ?: ReminderType.BOOK.name)
        val id = requireArguments().getLong(ARG_ID, 0L)
        requireActivity().title = getString(
            when (type) {
                ReminderType.PULL_LIST -> R.string.reminders_edit_pull
                ReminderType.BOOK -> R.string.reminders_edit_book
            },
        )
        parentFragmentManager.setFragmentResultListener(BookPickerFragment.REQUEST_KEY, this) { _, bundle ->
            @Suppress("DEPRECATION")
            (bundle.getSerializable(BookPickerFragment.BUNDLE_KEY) as? BookPickResult)?.let { picked ->
                pickedBook = picked
                refreshBookLabel()
            }
        }
        if (id > 0L) {
            lifecycleScope.launch {
                editing = repo.getById(id)
                view?.let { bindEntity(it) }
            }
        } else {
            editing = when (type) {
                ReminderType.PULL_LIST -> ReminderRepository.defaultPullListReminder()
                ReminderType.BOOK -> ReminderRepository.defaultBookReminder()
            }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val view = inflater.inflate(R.layout.fragment_reminder_edit, container, false)
        enabledBox = view.findViewById(R.id.reminder_edit_enabled)
        frequencySpinner = view.findViewById(R.id.reminder_edit_frequency)
        hourSpinner = view.findViewById(R.id.reminder_edit_hour)
        weeklyRow = view.findViewById(R.id.reminder_edit_weekly_row)
        monthlyRow = view.findViewById(R.id.reminder_edit_monthly_row)
        dayOfWeekSpinner = view.findViewById(R.id.reminder_edit_day_of_week)
        dayOfMonthSpinner = view.findViewById(R.id.reminder_edit_day_of_month)
        bookSection = view.findViewById(R.id.reminder_edit_book_section)
        pageModeSpinner = view.findViewById(R.id.reminder_edit_page_mode)
        bookLabel = view.findViewById(R.id.reminder_edit_book_label)
        deleteButton = view.findViewById(R.id.reminder_edit_delete)

        setupSpinner(frequencySpinner, R.array.reminder_frequency_labels)
        setupSpinner(hourSpinner, R.array.settings_hour_labels)
        setupSpinner(dayOfWeekSpinner, R.array.reminder_weekday_labels)
        setupSpinner(pageModeSpinner, R.array.reminder_page_mode_labels)

        val monthDays = (1..28).map { it.toString() }
        dayOfMonthSpinner.adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_dropdown_item,
            monthDays,
        )

        frequencySpinner.onItemSelectedListener = simpleListener { updateFrequencyRows() }
        view.findViewById<Button>(R.id.reminder_edit_pick_book).setOnClickListener {
            (activity as MainActivity).pushFragment(BookPickerFragment())
        }
        view.findViewById<Button>(R.id.reminder_edit_save).setOnClickListener { save() }
        deleteButton.setOnClickListener { confirmDelete() }

        bindEntity(view)
        return view
    }

    private fun bindEntity(view: View) {
        val entity = editing ?: return
        bindingSpinners = true
        enabledBox.isChecked = entity.enabled
        frequencySpinner.setSelection(entity.frequency.ordinal)
        hourSpinner.setSelection(entity.hourOfDay.coerceIn(0, 23))
        dayOfWeekSpinner.setSelection((entity.dayOfWeek - 1).coerceIn(0, 6))
        dayOfMonthSpinner.setSelection((entity.dayOfMonth - 1).coerceIn(0, 27))
        pageModeSpinner.setSelection(if (entity.pageMode == ReminderPageMode.RESUME) 0 else 1)
        bindingSpinners = false

        bookSection.visibility = if (entity.type == ReminderType.BOOK) View.VISIBLE else View.GONE
        deleteButton.visibility = if (entity.id > 0L) View.VISIBLE else View.GONE
        if (entity.type == ReminderType.BOOK && entity.title.isNotBlank()) {
            pickedBook = entity.toBookPick()
        }
        updateFrequencyRows()
        refreshBookLabel()
    }

    private fun updateFrequencyRows() {
        if (bindingSpinners) return
        when (frequencySpinner.selectedItemPosition) {
            ReminderFrequency.WEEKLY.ordinal -> {
                weeklyRow.visibility = View.VISIBLE
                monthlyRow.visibility = View.GONE
            }
            ReminderFrequency.MONTHLY.ordinal -> {
                weeklyRow.visibility = View.GONE
                monthlyRow.visibility = View.VISIBLE
            }
            else -> {
                weeklyRow.visibility = View.GONE
                monthlyRow.visibility = View.GONE
            }
        }
    }

    private fun refreshBookLabel() {
        val pick = pickedBook
        bookLabel.text = pick?.displayTitle ?: getString(R.string.reminders_no_book)
    }

    private fun save() {
        val base = editing ?: return
        if (base.type == ReminderType.BOOK && pickedBook == null) {
            Toast.makeText(requireContext(), R.string.reminders_book_required, Toast.LENGTH_SHORT).show()
            return
        }
        val pick = pickedBook
        val pageMode = if (pageModeSpinner.selectedItemPosition == 0) {
            ReminderPageMode.RESUME
        } else {
            ReminderPageMode.PAGE_A_DAY
        }
        val entity = base.copy(
            enabled = enabledBox.isChecked,
            frequency = ReminderFrequency.entries[frequencySpinner.selectedItemPosition],
            hourOfDay = hourSpinner.selectedItemPosition,
            dayOfWeek = dayOfWeekSpinner.selectedItemPosition + CalendarCompat.SUNDAY,
            dayOfMonth = dayOfMonthSpinner.selectedItemPosition + 1,
            pageMode = pageMode,
            title = pick?.displayTitle ?: base.title,
            bookSource = pick?.source ?: base.bookSource,
            identityKey = pick?.identityKey,
            libraryComicId = pick?.libraryComicId ?: 0,
            localPath = pick?.localPath,
            smbShareId = pick?.smbShareId ?: 0L,
            smbRelativePath = pick?.smbRelativePath,
        )
        viewLifecycleOwner.lifecycleScope.launch {
            repo.save(entity)
            Toast.makeText(requireContext(), R.string.reminders_saved, Toast.LENGTH_SHORT).show()
            parentFragmentManager.popBackStack()
        }
    }

    private fun confirmDelete() {
        val id = editing?.id ?: return
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.reminders_delete)
            .setMessage(R.string.reminders_delete_confirm)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    repo.delete(id)
                    Toast.makeText(requireContext(), R.string.reminders_deleted, Toast.LENGTH_SHORT).show()
                    parentFragmentManager.popBackStack()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun setupSpinner(spinner: Spinner, labelsRes: Int) {
        spinner.adapter = ArrayAdapter.createFromResource(
            requireContext(),
            labelsRes,
            android.R.layout.simple_spinner_dropdown_item,
        )
    }

    private fun simpleListener(block: () -> Unit) = object : AdapterView.OnItemSelectedListener {
        override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
            if (bindingSpinners) return
            block()
        }
        override fun onNothingSelected(parent: AdapterView<*>?) {}
    }

    private fun ReminderEntity.toBookPick(): BookPickResult = BookPickResult(
        source = bookSource ?: ReminderBookSource.LIBRARY,
        displayTitle = title,
        identityKey = identityKey,
        libraryComicId = libraryComicId,
        localPath = localPath,
        smbShareId = smbShareId,
        smbRelativePath = smbRelativePath,
    )

    companion object {
        private const val ARG_TYPE = "type"
        private const val ARG_ID = "id"

        fun newInstance(type: ReminderType, id: Long): ReminderEditFragment =
            ReminderEditFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_TYPE, type.name)
                    putLong(ARG_ID, id)
                }
            }
    }
}
