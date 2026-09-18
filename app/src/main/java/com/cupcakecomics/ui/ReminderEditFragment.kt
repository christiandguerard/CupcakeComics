package com.cupcakecomics.ui

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
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
import com.cupcakecomics.data.ReminderShiftDirection
import com.cupcakecomics.data.ReminderType
import com.cupcakecomics.notifications.CupcakeNotifications
import com.cupcakecomics.reminders.GoalWindow
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
    private lateinit var intervalRow: View
    private lateinit var intervalDaysInput: EditText
    private lateinit var blockedContainer: ViewGroup
    private lateinit var blockedShiftSpinner: Spinner
    private val blockedBoxes = mutableListOf<CheckBox>()
    private lateinit var dayOfWeekSpinner: Spinner
    private lateinit var dayOfMonthSpinner: Spinner
    private lateinit var bookSection: View
    private lateinit var goalCadenceSpinner: Spinner
    private lateinit var notifyBox: CheckBox
    private lateinit var goalInput: EditText
    private lateinit var goalSummary: TextView
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
                updateGoalSummary()
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
        intervalRow = view.findViewById(R.id.reminder_edit_interval_row)
        intervalDaysInput = view.findViewById(R.id.reminder_edit_interval_days)
        blockedContainer = view.findViewById(R.id.reminder_edit_blocked_container)
        blockedShiftSpinner = view.findViewById(R.id.reminder_edit_blocked_shift)
        dayOfWeekSpinner = view.findViewById(R.id.reminder_edit_day_of_week)
        dayOfMonthSpinner = view.findViewById(R.id.reminder_edit_day_of_month)
        bookSection = view.findViewById(R.id.reminder_edit_book_section)
        goalCadenceSpinner = view.findViewById(R.id.reminder_edit_goal_cadence)
        notifyBox = view.findViewById(R.id.reminder_edit_notify)
        goalInput = view.findViewById(R.id.reminder_edit_goal)
        goalSummary = view.findViewById(R.id.reminder_edit_summary)
        bookLabel = view.findViewById(R.id.reminder_edit_book_label)
        deleteButton = view.findViewById(R.id.reminder_edit_delete)

        setupSpinner(frequencySpinner, R.array.reminder_frequency_labels)
        setupSpinner(hourSpinner, R.array.settings_hour_labels)
        setupSpinner(dayOfWeekSpinner, R.array.reminder_weekday_labels)
        setupSpinner(goalCadenceSpinner, R.array.reminder_goal_cadence_labels)
        setupSpinner(blockedShiftSpinner, R.array.reminder_shift_labels)

        // One single-letter toggle per weekday (Sunday first, matching Calendar).
        val weekdayLabels = resources.getStringArray(R.array.reminder_weekday_labels)
        blockedBoxes.clear()
        weekdayLabels.forEachIndexed { index, label ->
            val box = CheckBox(requireContext()).apply {
                text = label.take(1)
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f,
                )
            }
            blockedBoxes.add(box)
            blockedContainer.addView(box)
        }

        val monthDays = (1..28).map { it.toString() }
        dayOfMonthSpinner.adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_dropdown_item,
            monthDays,
        )

        frequencySpinner.onItemSelectedListener = simpleListener { updateFrequencyRows() }
        goalCadenceSpinner.onItemSelectedListener = simpleListener { updateGoalSummary() }
        goalInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (!bindingSpinners) updateGoalSummary()
            }
        })
        view.findViewById<Button>(R.id.reminder_edit_pick_book).setOnClickListener {
            (activity as MainActivity).pushFragment(BookPickerFragment())
        }
        view.findViewById<Button>(R.id.reminder_edit_test).setOnClickListener { sendTestNotification() }
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
        goalCadenceSpinner.setSelection(entity.goalCadence.ordinal.coerceAtMost(2))
        notifyBox.isChecked = entity.notifyEnabled
        goalInput.setText(entity.goalPages.coerceAtLeast(0).toString())
        intervalDaysInput.setText(entity.intervalDays.coerceAtLeast(2).toString())
        blockedBoxes.forEachIndexed { index, box ->
            box.isChecked = entity.blockedWeekdays and (1 shl index) != 0
        }
        blockedShiftSpinner.setSelection(entity.blockedShift.ordinal)
        bindingSpinners = false

        bookSection.visibility = if (entity.type == ReminderType.BOOK) View.VISIBLE else View.GONE
        deleteButton.visibility = if (entity.id > 0L) View.VISIBLE else View.GONE
        if (entity.type == ReminderType.BOOK && entity.title.isNotBlank()) {
            pickedBook = entity.toBookPick()
        }
        updateFrequencyRows()
        refreshBookLabel()
        updateGoalSummary()
    }

    private fun updateFrequencyRows() {
        if (bindingSpinners) return
        weeklyRow.visibility = View.GONE
        monthlyRow.visibility = View.GONE
        intervalRow.visibility = View.GONE
        when (frequencySpinner.selectedItemPosition) {
            ReminderFrequency.WEEKLY.ordinal -> weeklyRow.visibility = View.VISIBLE
            ReminderFrequency.MONTHLY.ordinal -> monthlyRow.visibility = View.VISIBLE
            ReminderFrequency.INTERVAL.ordinal -> intervalRow.visibility = View.VISIBLE
        }
    }

    private fun currentGoalPages(): Int =
        goalInput.text.toString().trim().toIntOrNull()?.coerceIn(0, 999) ?: 0

    private fun currentGoalCadence(): ReminderFrequency =
        ReminderFrequency.entries.getOrElse(goalCadenceSpinner.selectedItemPosition) {
            ReminderFrequency.DAILY
        }

    /** Live "pages left this window" line so the goal's effect is visible before saving. */
    private fun updateGoalSummary() {
        if (!::goalSummary.isInitialized) return
        val goal = currentGoalPages()
        val cadence = currentGoalCadence()
        if (goal <= 0) {
            goalSummary.setText(R.string.reminders_summary_none)
            return
        }
        val per = getString(GoalWindow.perLabelRes(cadence))
        // Preview against the currently picked book — after a re-pick, the saved
        // row still points at the old title and would report its history instead.
        val pick = pickedBook
        val saved = editing?.takeIf { it.id > 0L && it.type == ReminderType.BOOK }
        val preview = when {
            pick != null -> (saved ?: ReminderRepository.defaultBookReminder()).copy(
                bookSource = pick.source,
                identityKey = pick.identityKey,
                localPath = pick.localPath,
                goalPages = goal,
                goalCadence = cadence,
            )
            saved != null -> saved.copy(goalPages = goal, goalCadence = cadence)
            else -> null
        }
        if (preview == null) {
            goalSummary.text = getString(R.string.reminders_summary_goal_new, goal, per)
            return
        }
        viewLifecycleOwner.lifecycleScope.launch {
            val left = repo.pagesLeftInWindow(preview)
            if (view == null) return@launch
            goalSummary.text = getString(
                R.string.reminders_summary_goal_left,
                goal,
                per,
                left,
                getString(GoalWindow.windowLabelRes(cadence)),
            )
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
        val frequency = ReminderFrequency.entries[frequencySpinner.selectedItemPosition]
        val intervalDays = intervalDaysInput.text.toString().trim().toIntOrNull()?.coerceIn(2, 999) ?: 2
        val blockedMask = blockedBoxes.foldIndexed(0) { index, mask, box ->
            if (box.isChecked) mask or (1 shl index) else mask
        }
        if (frequency == ReminderFrequency.INTERVAL && blockedMask == 0x7F) {
            Toast.makeText(requireContext(), R.string.reminders_blocked_all_error, Toast.LENGTH_SHORT).show()
            return
        }
        val pick = pickedBook
        val entity = base.copy(
            enabled = enabledBox.isChecked,
            frequency = frequency,
            hourOfDay = hourSpinner.selectedItemPosition,
            dayOfWeek = dayOfWeekSpinner.selectedItemPosition + CalendarCompat.SUNDAY,
            dayOfMonth = dayOfMonthSpinner.selectedItemPosition + 1,
            intervalDays = if (frequency == ReminderFrequency.INTERVAL) intervalDays else 0,
            blockedWeekdays = if (frequency == ReminderFrequency.INTERVAL) blockedMask else 0,
            blockedShift = ReminderShiftDirection.entries[
                blockedShiftSpinner.selectedItemPosition.coerceIn(0, 1),
            ],
            goalPages = currentGoalPages(),
            goalCadence = currentGoalCadence(),
            notifyEnabled = notifyBox.isChecked,
            title = pick?.displayTitle ?: base.title,
            bookSource = pick?.source ?: base.bookSource,
            identityKey = pick?.identityKey,
            libraryComicId = pick?.libraryComicId ?: 0,
            localPath = pick?.localPath,
            smbShareId = pick?.smbShareId ?: 0L,
            smbRelativePath = pick?.smbRelativePath,
            totalPages = pick?.totalPages ?: base.totalPages,
        )
        viewLifecycleOwner.lifecycleScope.launch {
            repo.save(entity)
            Toast.makeText(requireContext(), R.string.reminders_saved, Toast.LENGTH_SHORT).show()
            parentFragmentManager.popBackStack()
        }
    }

    /**
     * Posts a preview of exactly what this reminder will send: Pull List reminders
     * preview the unread-count nudge; book reminders use the current form state
     * (picked book, goal, cadence) so unsaved edits are reflected too.
     */
    private fun sendTestNotification() {
        val context = requireContext()
        if (!CupcakeNotifications.areNotificationsAllowed(context)) {
            Toast.makeText(context, R.string.reminders_test_blocked_toast, Toast.LENGTH_LONG).show()
            return
        }
        val base = editing ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            if (base.type == ReminderType.PULL_LIST) {
                val count = repo.unreadPullListCount().coerceAtLeast(1)
                CupcakeNotifications.notifyPullListReminder(context, count)
            } else {
                val entity = currentBookEntity()
                if (entity == null) {
                    Toast.makeText(context, R.string.reminders_book_required, Toast.LENGTH_SHORT).show()
                    return@launch
                }
                val page = repo.resolveResumePage(entity)
                val goalRead = if (entity.hasGoal()) repo.pagesReadInWindow(entity) else null
                CupcakeNotifications.notifyBookReminder(
                    context, entity, page, goalRead, preview = true,
                )
            }
            Toast.makeText(context, R.string.reminders_test_sent_toast, Toast.LENGTH_SHORT).show()
        }
    }

    /** The book reminder as currently configured in the form, saved or not. */
    private fun currentBookEntity(): ReminderEntity? {
        val base = editing ?: return null
        if (base.type != ReminderType.BOOK) return null
        val pick = pickedBook ?: return null
        return base.copy(
            goalPages = currentGoalPages(),
            goalCadence = currentGoalCadence(),
            title = pick.displayTitle,
            bookSource = pick.source,
            identityKey = pick.identityKey,
            libraryComicId = pick.libraryComicId,
            localPath = pick.localPath,
            smbShareId = pick.smbShareId,
            smbRelativePath = pick.smbRelativePath,
            totalPages = pick.totalPages.takeIf { it > 0 } ?: base.totalPages,
        )
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
        totalPages = totalPages,
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
