package com.cupcakecomics.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
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
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatDelegate
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import android.view.inputmethod.EditorInfo
import com.cupcakecomics.cover.SmbNetworkCoverCache
import com.cupcakecomics.data.ConnectionRepository
import com.cupcakecomics.data.LibraryRepository
import com.cupcakecomics.pulllist.PullListWorker
import com.cupcakecomics.reader.model.PagesLayout
import com.cupcakecomics.reader.model.ReadingFlow
import com.cupcakecomics.reader.model.TransitionAxis
import com.cupcakecomics.reader.settings.ReaderSettingsStore
import com.cupcakecomics.settings.CoverSize
import com.cupcakecomics.settings.CupcakeSettings
import com.cupcakecomics.settings.LibrarySection
import com.cupcakecomics.smb.SmbBrowser
import com.cupcakecomics.smb.SmbStageManager
import com.nkanaev.comics.Constants
import com.nkanaev.comics.MainApplication
import androidx.appcompat.app.AlertDialog
import com.nkanaev.comics.R
import com.nkanaev.comics.activity.MainActivity
import com.nkanaev.comics.fragment.AboutFragment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

class SettingsFragment : Fragment() {
    private lateinit var cupcake: CupcakeSettings
    private lateinit var readerStore: ReaderSettingsStore
    private var cacheJob: Job? = null
    private val cacheCancel = AtomicBoolean(false)
    private var bindingSpinners = false

    private val notifyPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        refreshNotifyStatus()
        if (!granted) {
            Toast.makeText(requireContext(), R.string.settings_notify_denied_toast, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cupcake = CupcakeSettings(requireContext())
        readerStore = ReaderSettingsStore(requireContext())
        requireActivity().title = getString(R.string.menu_settings)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val view = inflater.inflate(R.layout.fragment_settings, container, false)
        bindNotifications(view)
        bindReminders(view)
        bindPullList(view)
        bindLibrary(view)
        bindReading(view)
        bindAppearance(view)
        bindStorage(view)
        bindMore(view)
        return view
    }

    override fun onResume() {
        super.onResume()
        view?.let { refreshNotifyStatus(it) }
    }

    override fun onDestroyView() {
        cacheCancel.set(true)
        cacheJob?.cancel()
        super.onDestroyView()
    }

    private fun bindNotifications(view: View) {
        val notify = view.findViewById<CheckBox>(R.id.settings_pull_notify)
        val digest = view.findViewById<CheckBox>(R.id.settings_notify_digest)
        val downloads = view.findViewById<CheckBox>(R.id.settings_notify_downloads)
        val offlineBg = view.findViewById<CheckBox>(R.id.settings_offline_download_background)
        val quiet = view.findViewById<CheckBox>(R.id.settings_quiet_hours)
        val quietRow = view.findViewById<View>(R.id.settings_quiet_hours_row)
        val quietStart = view.findViewById<Spinner>(R.id.settings_quiet_start)
        val quietEnd = view.findViewById<Spinner>(R.id.settings_quiet_end)

        notify.isChecked = cupcake.pullListNotify
        digest.isChecked = cupcake.notifyDigestMode
        downloads.isChecked = cupcake.notifyDownloads
        offlineBg.isChecked = cupcake.offlineDownloadBackground
        quiet.isChecked = cupcake.quietHoursEnabled
        quietRow.visibility = if (cupcake.quietHoursEnabled) View.VISIBLE else View.GONE

        bindingSpinners = true
        setupSimpleSpinner(quietStart, R.array.settings_hour_labels)
        setupSimpleSpinner(quietEnd, R.array.settings_hour_labels)
        quietStart.setSelection(cupcake.quietHoursStartHour)
        quietEnd.setSelection(cupcake.quietHoursEndHour)
        bindingSpinners = false

        notify.setOnCheckedChangeListener { _, checked ->
            cupcake.pullListNotify = checked
            if (checked) {
                com.cupcakecomics.notifications.CupcakeNotifications.ensureChannels(requireContext())
                maybeRequestNotifyPermission()
            }
            refreshNotifyStatus(view)
        }
        digest.setOnCheckedChangeListener { _, checked -> cupcake.notifyDigestMode = checked }
        downloads.setOnCheckedChangeListener { _, checked -> cupcake.notifyDownloads = checked }
        offlineBg.setOnCheckedChangeListener { _, checked -> cupcake.offlineDownloadBackground = checked }
        quiet.setOnCheckedChangeListener { _, checked ->
            cupcake.quietHoursEnabled = checked
            quietRow.visibility = if (checked) View.VISIBLE else View.GONE
            if (!checked) {
                com.cupcakecomics.notifications.CupcakeNotifications.flushPendingPullList(requireContext())
            }
        }
        val quietListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, v: View?, position: Int, id: Long) {
                if (bindingSpinners) return
                cupcake.quietHoursStartHour = quietStart.selectedItemPosition
                cupcake.quietHoursEndHour = quietEnd.selectedItemPosition
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        quietStart.onItemSelectedListener = quietListener
        quietEnd.onItemSelectedListener = quietListener

        view.findViewById<Button>(R.id.settings_notify_grant).setOnClickListener {
            maybeRequestNotifyPermission()
        }
        view.findViewById<Button>(R.id.settings_notify_system).setOnClickListener {
            openSystemNotificationSettings()
        }
        refreshNotifyStatus(view)
    }

    private fun bindReminders(view: View) {
        view.findViewById<Button>(R.id.settings_open_reminders).setOnClickListener {
            (requireActivity() as MainActivity).pushFragment(RemindersFragment())
        }
    }

    private fun bindPullList(view: View) {
        val scanSpinner = view.findViewById<Spinner>(R.id.settings_pull_scan_interval)
        val leaveSpinner = view.findViewById<Spinner>(R.id.settings_pull_leave_threshold)
        val scanWifi = view.findViewById<CheckBox>(R.id.settings_pull_scan_wifi)
        val autoDl = view.findViewById<CheckBox>(R.id.settings_pull_auto_download)
        val wifiOnly = view.findViewById<CheckBox>(R.id.settings_pull_wifi_only)

        bindingSpinners = true
        setupSimpleSpinner(scanSpinner, R.array.settings_scan_interval_labels)
        val scanIdx = CupcakeSettings.SCAN_INTERVAL_CHOICES
            .indexOf(cupcake.pullListScanMinutes)
            .takeIf { it >= 0 } ?: 1
        scanSpinner.setSelection(scanIdx)

        setupSimpleSpinner(leaveSpinner, R.array.settings_leave_threshold_labels)
        val leaveIdx = CupcakeSettings.LEAVE_THRESHOLD_CHOICES
            .indexOfFirst { kotlin.math.abs(it - cupcake.pullListLeaveThreshold) < 0.001f }
            .takeIf { it >= 0 } ?: 1
        leaveSpinner.setSelection(leaveIdx)
        bindingSpinners = false

        scanSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, v: View?, position: Int, id: Long) {
                if (bindingSpinners) return
                val minutes = CupcakeSettings.SCAN_INTERVAL_CHOICES.getOrElse(position) { 30 }
                if (minutes != cupcake.pullListScanMinutes) {
                    cupcake.pullListScanMinutes = minutes
                    PullListWorker.schedule(requireContext())
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        leaveSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, v: View?, position: Int, id: Long) {
                if (bindingSpinners) return
                cupcake.pullListLeaveThreshold =
                    CupcakeSettings.LEAVE_THRESHOLD_CHOICES.getOrElse(position) { 0.90f }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        scanWifi.isChecked = cupcake.pullListScanWifiOnly
        autoDl.isChecked = cupcake.pullListAutoDownload
        wifiOnly.isChecked = cupcake.pullListWifiOnly
        wifiOnly.isEnabled = cupcake.pullListAutoDownload

        scanWifi.setOnCheckedChangeListener { _, checked ->
            cupcake.pullListScanWifiOnly = checked
            PullListWorker.schedule(requireContext())
        }
        autoDl.setOnCheckedChangeListener { _, checked ->
            cupcake.pullListAutoDownload = checked
            wifiOnly.isEnabled = checked
            PullListWorker.schedule(requireContext())
        }
        wifiOnly.setOnCheckedChangeListener { _, checked ->
            cupcake.pullListWifiOnly = checked
        }

        val cvKey = view.findViewById<EditText>(R.id.settings_comicvine_key)
        cvKey.setText(cupcake.comicVineApiKey)
        cvKey.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE || actionId == EditorInfo.IME_ACTION_NEXT) {
                cupcake.comicVineApiKey = cvKey.text?.toString().orEmpty()
                true
            } else {
                false
            }
        }
        cvKey.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) cupcake.comicVineApiKey = cvKey.text?.toString().orEmpty()
        }
    }

    private fun bindLibrary(view: View) {
        val hideTitles = view.findViewById<CheckBox>(R.id.settings_hide_cover_titles)
        val shortNames = view.findViewById<CheckBox>(R.id.settings_short_network_filenames)
        val streaming = view.findViewById<CheckBox>(R.id.settings_smb_streaming)
        val coverSize = view.findViewById<Spinner>(R.id.settings_cover_size)
        val sectionOrderList = view.findViewById<LinearLayout>(R.id.settings_section_order_list)
        val cacheBtn = view.findViewById<Button>(R.id.settings_cache_network_covers)
        val cacheStatus = view.findViewById<TextView>(R.id.settings_cache_network_status)

        hideTitles.isChecked = cupcake.hideCoverTitles
        shortNames.isChecked = cupcake.shortenNetworkFilenames
        streaming.isChecked = cupcake.preferSmbStreaming

        hideTitles.setOnCheckedChangeListener { _, c -> cupcake.hideCoverTitles = c }
        shortNames.setOnCheckedChangeListener { _, c -> cupcake.shortenNetworkFilenames = c }
        streaming.setOnCheckedChangeListener { _, c -> cupcake.preferSmbStreaming = c }

        bindingSpinners = true
        setupSimpleSpinner(coverSize, R.array.settings_cover_size_labels)
        coverSize.setSelection(cupcake.coverSize.ordinal.coerceIn(0, 2))
        bindingSpinners = false
        coverSize.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, v: View?, position: Int, id: Long) {
                if (bindingSpinners) return
                cupcake.coverSize = CoverSize.entries[position]
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        bindSectionOrder(sectionOrderList)

        cacheBtn.setOnClickListener {
            if (cacheJob?.isActive == true) {
                cacheCancel.set(true)
                cacheJob?.cancel()
                cacheBtn.text = getString(R.string.settings_cache_network_covers)
                cacheStatus.text = getString(R.string.settings_cache_network_hint)
                return@setOnClickListener
            }
            startNetworkCoverCache(cacheBtn, cacheStatus)
        }
    }

    private fun bindSectionOrder(container: LinearLayout) {
        fun refresh() {
            container.removeAllViews()
            val order = cupcake.librarySectionOrder.toMutableList()
            order.forEachIndexed { index, section ->
                val row = layoutInflater.inflate(R.layout.item_section_order_row, container, false)
                row.findViewById<TextView>(R.id.section_order_label).text = sectionLabel(section)
                row.findViewById<Button>(R.id.section_order_up).apply {
                    isEnabled = index > 0
                    setOnClickListener {
                        if (index <= 0) return@setOnClickListener
                        val next = order.toMutableList()
                        val tmp = next[index - 1]
                        next[index - 1] = next[index]
                        next[index] = tmp
                        cupcake.librarySectionOrder = next
                        refresh()
                    }
                }
                row.findViewById<Button>(R.id.section_order_down).apply {
                    isEnabled = index < order.lastIndex
                    setOnClickListener {
                        if (index >= order.lastIndex) return@setOnClickListener
                        val next = order.toMutableList()
                        val tmp = next[index + 1]
                        next[index + 1] = next[index]
                        next[index] = tmp
                        cupcake.librarySectionOrder = next
                        refresh()
                    }
                }
                container.addView(row)
            }
        }
        refresh()
    }

    private fun sectionLabel(section: LibrarySection): String = when (section) {
        LibrarySection.PULL -> getString(R.string.library_order_pull)
        LibrarySection.LOCAL -> getString(R.string.library_order_local)
        LibrarySection.OFFLINE -> getString(R.string.library_order_offline)
        LibrarySection.SMB -> getString(R.string.library_order_smb)
        LibrarySection.MEDIA -> getString(R.string.library_order_media)
    }

    private fun bindReading(view: View) {
        val flow = view.findViewById<Spinner>(R.id.settings_reading_flow)
        val axis = view.findViewById<Spinner>(R.id.settings_transition_axis)
        val layoutP = view.findViewById<Spinner>(R.id.settings_layout_portrait)
        val layoutL = view.findViewById<Spinner>(R.id.settings_layout_landscape)
        val gpu = view.findViewById<CheckBox>(R.id.settings_gpu_reader)
        val crop = view.findViewById<CheckBox>(R.id.settings_crop_borders)
        val split = view.findViewById<CheckBox>(R.id.settings_split_spreads)

        val prefs = readerStore.loadDefaults()
        bindingSpinners = true
        setupSimpleSpinner(flow, R.array.settings_reading_flow_labels)
        setupSimpleSpinner(axis, R.array.settings_transition_axis_labels)
        setupSimpleSpinner(layoutP, R.array.settings_pages_layout_labels)
        setupSimpleSpinner(layoutL, R.array.settings_pages_layout_labels)

        flow.setSelection(prefs.readingFlow.ordinal.coerceIn(0, 2))
        axis.setSelection(prefs.transitions.axis.ordinal.coerceIn(0, 2))
        layoutP.setSelection(prefs.layout.portrait.ordinal.coerceIn(0, 4))
        layoutL.setSelection(prefs.layout.landscape.ordinal.coerceIn(0, 4))
        gpu.isChecked = prefs.useGpuRenderer
        crop.isChecked = prefs.cropBorders
        split.isChecked = prefs.splitSpreads
        bindingSpinners = false

        fun persistReading() {
            if (bindingSpinners) return
            val current = readerStore.loadDefaults()
            val next = current.copy(
                readingFlow = ReadingFlow.entries[flow.selectedItemPosition.coerceIn(0, ReadingFlow.entries.lastIndex)],
                transitions = current.transitions.copy(
                    axis = TransitionAxis.entries[axis.selectedItemPosition.coerceIn(0, TransitionAxis.entries.lastIndex)],
                ),
                layout = current.layout.copy(
                    portrait = PagesLayout.entries[layoutP.selectedItemPosition.coerceIn(0, PagesLayout.entries.lastIndex)],
                    landscape = PagesLayout.entries[layoutL.selectedItemPosition.coerceIn(0, PagesLayout.entries.lastIndex)],
                ),
                useGpuRenderer = gpu.isChecked,
                cropBorders = crop.isChecked,
                splitSpreads = split.isChecked,
            )
            readerStore.saveDefaults(next)
        }

        val spinnerListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, v: View?, position: Int, id: Long) = persistReading()
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        flow.onItemSelectedListener = spinnerListener
        axis.onItemSelectedListener = spinnerListener
        layoutP.onItemSelectedListener = spinnerListener
        layoutL.onItemSelectedListener = spinnerListener
        gpu.setOnCheckedChangeListener { _, _ -> persistReading() }
        crop.setOnCheckedChangeListener { _, _ -> persistReading() }
        split.setOnCheckedChangeListener { _, _ -> persistReading() }
    }

    private fun bindAppearance(view: View) {
        val theme = view.findViewById<Spinner>(R.id.settings_theme)
        setupSimpleSpinner(theme, R.array.settings_theme_labels)
        val legacy = MainApplication.getPreferences()
        val mode = legacy.getInt(Constants.SETTINGS_THEME, AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        bindingSpinners = true
        theme.setSelection(
            when (mode) {
                AppCompatDelegate.MODE_NIGHT_NO -> 1
                AppCompatDelegate.MODE_NIGHT_YES -> 2
                else -> 0
            },
        )
        bindingSpinners = false
        theme.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, v: View?, position: Int, id: Long) {
                if (bindingSpinners) return
                val next = when (position) {
                    1 -> AppCompatDelegate.MODE_NIGHT_NO
                    2 -> AppCompatDelegate.MODE_NIGHT_YES
                    else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                }
                legacy.edit().putInt(Constants.SETTINGS_THEME, next).apply()
                AppCompatDelegate.setDefaultNightMode(next)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun bindStorage(view: View) {
        view.findViewById<Button>(R.id.settings_clear_network_covers).setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                withContext(Dispatchers.IO) {
                    File(requireContext().cacheDir, "covers-network").deleteRecursively()
                }
                Toast.makeText(requireContext(), R.string.settings_storage_cleared, Toast.LENGTH_SHORT).show()
            }
        }
        view.findViewById<Button>(R.id.settings_clear_smb_stage).setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                withContext(Dispatchers.IO) {
                    SmbStageManager(requireContext(), ConnectionRepository(requireContext()).credentialStore())
                        .stageDir()
                        .deleteRecursively()
                }
                Toast.makeText(requireContext(), R.string.settings_storage_cleared, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun bindMore(view: View) {
        view.findViewById<Button>(R.id.settings_open_connections).setOnClickListener {
            (requireActivity() as MainActivity).pushFragment(ConnectionsFragment())
        }
        view.findViewById<Button>(R.id.settings_open_about).setOnClickListener {
            (requireActivity() as MainActivity).pushFragment(AboutFragment())
        }

        val feedback = view.findViewById<CheckBox>(R.id.settings_debug_feedback)
        feedback.isChecked = cupcake.debugFeedbackEnabled
        feedback.setOnCheckedChangeListener { _, checked ->
            cupcake.debugFeedbackEnabled = checked
            com.cupcakecomics.feedback.FeedbackOverlay.refresh(requireContext())
            Toast.makeText(
                requireContext(),
                if (checked) R.string.feedback_enabled_toast else R.string.feedback_disabled_toast,
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    private fun setupSimpleSpinner(spinner: Spinner, labelsRes: Int) {
        spinner.adapter = ArrayAdapter.createFromResource(
            requireContext(),
            labelsRes,
            android.R.layout.simple_spinner_dropdown_item,
        )
    }

    private fun refreshNotifyStatus(root: View? = view) {
        val status = root?.findViewById<TextView>(R.id.settings_notify_status) ?: return
        val grant = root.findViewById<Button>(R.id.settings_notify_grant) ?: return
        val allowed = PullListWorker.areNotificationsAllowed(requireContext())
        status.setText(
            if (allowed) R.string.settings_notify_status_allowed
            else R.string.settings_notify_status_blocked,
        )
        grant.visibility = if (allowed) View.GONE else View.VISIBLE
    }

    private fun maybeRequestNotifyPermission() {
        if (Build.VERSION.SDK_INT < 33) {
            refreshNotifyStatus()
            return
        }
        if (PullListWorker.areNotificationsAllowed(requireContext())) {
            refreshNotifyStatus()
            return
        }
        notifyPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun openSystemNotificationSettings() {
        val ctx = requireContext()
        val intent = Intent().apply {
            when {
                Build.VERSION.SDK_INT >= 26 -> {
                    action = Settings.ACTION_APP_NOTIFICATION_SETTINGS
                    putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
                }
                else -> {
                    action = Settings.ACTION_APPLICATION_DETAILS_SETTINGS
                    data = Uri.fromParts("package", ctx.packageName, null)
                }
            }
        }
        startActivity(intent)
    }

    private fun startNetworkCoverCache(button: Button, status: TextView) {
        cacheCancel.set(false)
        button.text = getString(R.string.settings_cache_network_cancel)
        status.text = getString(R.string.settings_cache_network_running, 0, 0, "…")

        cacheJob = viewLifecycleOwner.lifecycleScope.launch {
            try {
                val repo = ConnectionRepository(requireContext())
                val shares = withContext(Dispatchers.IO) { repo.getAllSmbShares() }
                if (shares.isEmpty()) {
                    status.text = getString(R.string.settings_cache_network_none)
                    return@launch
                }
                val browser = SmbBrowser(repo.credentialStore())
                val libraryRepo = LibraryRepository(requireContext())
                val appCtx = requireContext().applicationContext
                val result = withContext(Dispatchers.IO) {
                    SmbNetworkCoverCache.precacheAll(
                        context = appCtx,
                        shares = shares,
                        browser = browser,
                        credentials = repo.credentialStore(),
                        libraryRepo = libraryRepo,
                        onProgress = { p ->
                            launch(Dispatchers.Main.immediate) {
                                if (!isAdded) return@launch
                                status.text = getString(
                                    R.string.settings_cache_network_running,
                                    p.done,
                                    p.total.coerceAtLeast(p.done),
                                    p.message,
                                )
                            }
                        },
                        isCancelled = { cacheCancel.get() || !isActive },
                    )
                }
                if (!isAdded) return@launch
                status.text = getString(
                    R.string.settings_cache_network_done,
                    result.cached,
                    result.alreadyHad,
                    result.failed,
                )
            } finally {
                if (isAdded) {
                    button.text = getString(R.string.settings_cache_network_covers)
                }
            }
        }
    }
}
