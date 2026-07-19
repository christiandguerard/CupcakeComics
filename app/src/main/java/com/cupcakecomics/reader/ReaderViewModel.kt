package com.cupcakecomics.reader

import android.app.Application
import android.content.res.Configuration
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.cupcakecomics.pulllist.PullListRepository
import com.cupcakecomics.reminders.ReminderRepository
import com.cupcakecomics.reader.layout.PageLayoutEngine
import com.cupcakecomics.reader.model.PageDescriptor
import com.cupcakecomics.reader.model.ReaderPreferences
import com.cupcakecomics.reader.model.ReaderSession
import com.cupcakecomics.reader.model.ReadingFlow
import com.cupcakecomics.reader.model.TocEntry
import com.cupcakecomics.reader.settings.ReaderSettingsStore
import com.cupcakecomics.reader.source.PageSource
import com.nkanaev.comics.model.Comic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ReaderViewModel(application: Application) : AndroidViewModel(application) {
    private val settingsStore = ReaderSettingsStore(application)
    private val pullRepo = PullListRepository(application)
    private val reminderRepo = ReminderRepository(application)

    private var pageSource: PageSource? = null
    private var comic: Comic? = null
    private var identityKey: String? = null
    private var localFilePath: String? = null
    private var warmJob: Job? = null
    private var progressJob: Job? = null
    private var screenPortrait: Boolean =
        application.resources.configuration.orientation != Configuration.ORIENTATION_LANDSCAPE

    private val _session = MutableStateFlow(ReaderSession())
    val session: StateFlow<ReaderSession> = _session.asStateFlow()

    @Volatile private var stageCancelledFlag = false
    private val _stageProgress = MutableStateFlow<Pair<Long, Long>?>(null)
    val stageProgress: StateFlow<Pair<Long, Long>?> = _stageProgress.asStateFlow()

    fun cancelStaging() { stageCancelledFlag = true }
    fun resetStageCancel() { stageCancelledFlag = false }
    fun isStageCancelled(): Boolean = stageCancelledFlag
    fun setStageProgress(copied: Long, total: Long) {
        _stageProgress.value = copied to total
    }
    fun clearStageProgress() { _stageProgress.value = null }

    fun open(
        source: PageSource,
        identityKey: String? = null,
        comic: Comic? = null,
        initialPage: Int = 0,
        localPath: String? = null,
    ) {
        resetStageCancel()
        clearStageProgress()
        closeSource()
        this.pageSource = source
        this.identityKey = identityKey
        this.comic = comic
        this.localFilePath = localPath ?: comic?.file?.absolutePath
        val prefs = settingsStore.loadForBook(identityKey)
        val bookmarks = settingsStore.getBookmarks(identityKey)
        _session.value = ReaderSession(
            identityKey = identityKey,
            title = source.title,
            sourceType = source.type,
            preferences = prefs,
            bookmarks = bookmarks,
            loading = true,
            remoteStreaming = source.remoteStreaming,
            chromeVisible = false,
        )

        viewModelScope.launch {
            try {
                val resumeKey = identityKey?.takeIf { it.isNotBlank() }
                    ?: localFilePath?.takeIf { it.isNotBlank() }?.let { "file:$it" }
                val savedPage = settingsStore.getLastPage(resumeKey)
                data class Opened(
                    val count: Int,
                    val descriptors: List<PageDescriptor>,
                    val toc: List<TocEntry>,
                    val pullPage: Int,
                )
                val opened = withContext(Dispatchers.IO) {
                    source.open()
                    val n = source.pageCount()
                    // Probe first batch of headers without full decode payload where possible.
                    val probe = minOf(n, 24)
                    source.warmDescriptors(0, probe)
                    val descs = ArrayList<PageDescriptor>(n)
                    for (i in 0 until n) {
                        descs.add(
                            if (i < probe) source.descriptor(i)
                            else PageDescriptor(index = i),
                        )
                    }
                    val pull = if (!identityKey.isNullOrBlank()) {
                        pullRepo.getPull(identityKey)?.highestPage ?: 0
                    } else {
                        0
                    }
                    Opened(n, descs, source.tableOfContents(), pull)
                }
                val count = opened.count
                val descriptors = opened.descriptors
                val toc = opened.toc
                val pullPage = opened.pullPage
                val layout = prefs.layout.forScreen(screenPortrait)
                val slots = PageLayoutEngine.buildSlots(
                    descriptors = descriptors,
                    layout = layout,
                    flow = prefs.readingFlow,
                    splitSpreads = prefs.splitSpreads,
                    screenPortrait = screenPortrait,
                )
                val startPage = when {
                    initialPage > 0 -> initialPage - 1
                    comic != null && comic.currentPage > 1 -> (comic.currentPage - 1).coerceAtLeast(0)
                    else -> maxOf(savedPage, pullPage).let { if (it > 1) it - 1 else 0 }
                }
                val slotIdx = PageLayoutEngine.slotIndexForPage(slots, startPage)
                _session.update {
                    it.copy(
                        pageCount = count,
                        descriptors = descriptors,
                        slots = slots,
                        toc = toc,
                        currentSlotIndex = slotIdx,
                        loading = false,
                        error = null,
                    )
                }
                warmAround(slotIdx)
                reportProgress()
            } catch (t: Throwable) {
                _session.update {
                    it.copy(loading = false, error = t.message ?: t.javaClass.simpleName)
                }
            }
        }
    }

    fun setScreenPortrait(portrait: Boolean) {
        if (screenPortrait == portrait) return
        screenPortrait = portrait
        rebuildSlots(keepPage = true)
    }

    fun updatePreferences(transform: (ReaderPreferences) -> ReaderPreferences) {
        val next = transform(_session.value.preferences)
        settingsStore.saveForBook(identityKey, next)
        _session.update { it.copy(preferences = next) }
        rebuildSlots(keepPage = true)
    }

    fun setReadingFlow(flow: ReadingFlow) {
        updatePreferences { it.copy(readingFlow = flow) }
    }

    fun goToSlot(index: Int, fromUser: Boolean = true) {
        val slots = _session.value.slots
        if (slots.isEmpty()) return
        val clamped = index.coerceIn(0, slots.lastIndex)
        if (clamped == _session.value.currentSlotIndex) return
        _session.update { it.copy(currentSlotIndex = clamped) }
        warmAround(clamped)
        if (fromUser) scheduleProgress()
    }

    fun goToPage(pageIndex: Int) {
        val slot = PageLayoutEngine.slotIndexForPage(_session.value.slots, pageIndex)
        goToSlot(slot)
    }

    fun advance(forward: Boolean) {
        // Slots are always chronological. Reading flow controls spread assembly
        // and gesture/tap mapping, not the underlying page-number direction.
        goToSlot(_session.value.currentSlotIndex + if (forward) 1 else -1)
    }

    fun toggleChrome() {
        _session.update { it.copy(chromeVisible = !it.chromeVisible) }
    }

    fun setChromeVisible(visible: Boolean) {
        _session.update { it.copy(chromeVisible = visible) }
    }

    fun togglePageInfo() {
        _session.update { it.copy(pageInfoVisible = !it.pageInfoVisible) }
    }

    fun toggleBookmarkOnCurrent() {
        val page = _session.value.currentPageIndex
        val next = settingsStore.toggleBookmark(identityKey, page)
        _session.update { it.copy(bookmarks = next) }
    }

    fun rotateCurrentPage(deltaDegrees: Int = 90) {
        val page = _session.value.currentPageIndex
        val descs = _session.value.descriptors.toMutableList()
        if (page !in descs.indices) return
        val cur = descs[page]
        descs[page] = cur.copy(rotationDegrees = (cur.rotationDegrees + deltaDegrees) % 360)
        _session.update { it.copy(descriptors = descs) }
        rebuildSlots(keepPage = true)
    }

    fun pageSourceOrNull(): PageSource? = pageSource

    fun descriptor(index: Int): PageDescriptor? = _session.value.descriptors.getOrNull(index)

    fun ensureDescriptor(index: Int) {
        val source = pageSource ?: return
        if (index !in _session.value.descriptors.indices) return
        val existing = _session.value.descriptors[index]
        if (existing.width > 0 && existing.height > 0) return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val d = source.descriptor(index)
                _session.update { s ->
                    val list = s.descriptors.toMutableList()
                    if (index in list.indices) list[index] = d
                    s.copy(descriptors = list)
                }
            }
        }
    }

    private fun rebuildSlots(keepPage: Boolean) {
        val s = _session.value
        if (s.descriptors.isEmpty()) return
        val page = if (keepPage) s.currentPageIndex else 0
        val layout = s.preferences.layout.forScreen(screenPortrait)
        val slots = PageLayoutEngine.buildSlots(
            descriptors = s.descriptors,
            layout = layout,
            flow = s.preferences.readingFlow,
            splitSpreads = s.preferences.splitSpreads,
            screenPortrait = screenPortrait,
        )
        val slotIdx = PageLayoutEngine.slotIndexForPage(slots, page)
        _session.update { it.copy(slots = slots, currentSlotIndex = slotIdx) }
        warmAround(slotIdx)
    }

    private fun warmAround(slotIndex: Int) {
        warmJob?.cancel()
        val source = pageSource ?: return
        val slots = _session.value.slots
        if (slots.isEmpty()) return
        val pages = LinkedHashSet<Int>()
        for (i in (slotIndex - 2)..(slotIndex + 3)) {
            slots.getOrNull(i)?.pageIndices?.let { pages.addAll(it) }
        }
        warmJob = viewModelScope.launch(Dispatchers.IO) {
            // Let the visible page decode first. Parser-backed sources serialize
            // metadata and pixel access, so eager metadata probing can stall a swipe.
            delay(250)
            val updates = HashMap<Int, PageDescriptor>()
            for (p in pages) {
                val existing = _session.value.descriptors.getOrNull(p)
                if (existing != null && existing.width > 0 && existing.height > 0) continue
                runCatching {
                    updates[p] = source.descriptor(p)
                }
            }
            if (updates.isEmpty()) return@launch
            _session.update { s ->
                val list = s.descriptors.toMutableList()
                for ((index, desc) in updates) {
                    if (index in list.indices) list[index] = desc
                }
                s.copy(descriptors = list)
            }
        }
    }

    fun reportProgress() {
        val s = _session.value
        val slot = s.currentSlot ?: return
        val highest = PageLayoutEngine.highestPageInSlot(slot) + 1
        val key = identityKey
        viewModelScope.launch(Dispatchers.IO) {
            comic?.let { c ->
                runCatching { c.setCurrentPage(highest) }
            }
            if (!key.isNullOrBlank() && s.pageCount > 0) {
                runCatching {
                    settingsStore.setLastPage(key, highest)
                    pullRepo.updateReadingProgressSync(key, highest, s.pageCount)
                }
            } else if (!localFilePath.isNullOrBlank() && s.pageCount > 0) {
                // Offline opens without an identity still resume via local path key.
                runCatching {
                    settingsStore.setLastPage("file:$localFilePath", highest)
                }
            }
            localFilePath?.let { path ->
                runCatching {
                    reminderRepo.updateTrackedPageForLocalPath(path, highest)
                }
            }
            if (!key.isNullOrBlank()) {
                runCatching {
                    reminderRepo.updateTrackedPageForIdentity(key, highest)
                }
            }
        }
    }

    private fun scheduleProgress() {
        progressJob?.cancel()
        progressJob = viewModelScope.launch {
            delay(500)
            reportProgress()
        }
    }

    private fun closeSource() {
        warmJob?.cancel()
        progressJob?.cancel()
        runCatching { pageSource?.close() }
        pageSource = null
    }

    override fun onCleared() {
        reportProgress()
        closeSource()
        super.onCleared()
    }
}
