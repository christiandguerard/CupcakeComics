package com.cupcakecomics.reader

import android.content.ContentValues
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import kotlin.math.abs
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.cupcakecomics.reader.gl.PageDecodeCache
import com.cupcakecomics.reader.gl.ReaderGlSurfaceView
import com.cupcakecomics.reader.model.FitMode
import com.cupcakecomics.reader.model.PageTransition
import com.cupcakecomics.reader.model.PagesLayout
import com.cupcakecomics.reader.model.ReadingFlow
import com.cupcakecomics.reader.model.ReaderSession
import com.cupcakecomics.reader.model.TransitionAxis
import com.cupcakecomics.reader.source.PageSource
import com.cupcakecomics.reader.source.ParserPageSource
import com.cupcakecomics.reader.ui.PageTransitionTransformer
import com.cupcakecomics.reader.ui.PagedSlotAdapter
import com.cupcakecomics.reader.ui.ZoomablePageView
import com.nkanaev.comics.R
import com.nkanaev.comics.fragment.ReaderFragment
import com.nkanaev.comics.model.Comic
import com.nkanaev.comics.model.Storage
import com.nkanaev.comics.parsers.ParserFactory
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Distraction-free reader: ViewPager2 for paged layouts with real transitions,
 * RecyclerView for continuous assembly, software decode (stable under fling).
 */
class CupcakeReaderFragment : Fragment() {
    private val viewModel: ReaderViewModel by viewModels()
    private lateinit var decodeCache: PageDecodeCache

    private var glSurface: ReaderGlSurfaceView? = null
    private var pagePager: ViewPager2? = null
    private var continuousList: RecyclerView? = null
    private var chromeTop: View? = null
    private var chromeBottom: View? = null
    private var loading: View? = null
    private var errorView: TextView? = null
    private var titleView: TextView? = null
    private var pageText: TextView? = null
    private var seekBar: SeekBar? = null

    private var pagedAdapter: PagedSlotAdapter? = null
    private val pageTransformer = PageTransitionTransformer()
    private var pagerCallback: ViewPager2.OnPageChangeCallback? = null
    private var dualAxisTouchHelper: RecyclerView.SimpleOnItemTouchListener? = null
    private var syncingPager = false
    private var lastSlot = -1
    private var seeking = false
    private var continuousScrollListener: RecyclerView.OnScrollListener? = null
    private var lastChromeVisible: Boolean? = null
    private var lastPagerOrientation: Int = -1
    private var lastTransition: PageTransition? = null
    private var allowPerpendicularAdvance = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        decodeCache = PageDecodeCache(requireContext().applicationContext, lifecycleScope)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_cupcake_reader, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        hideHostChrome()

        glSurface = view.findViewById(R.id.reader_gl_surface)
        pagePager = view.findViewById(R.id.reader_page_pager)
        continuousList = view.findViewById(R.id.reader_continuous_list)
        chromeTop = view.findViewById(R.id.reader_chrome_top)
        chromeBottom = view.findViewById(R.id.reader_chrome_bottom)
        loading = view.findViewById(R.id.reader_loading)
        errorView = view.findViewById(R.id.reader_error)
        titleView = view.findViewById(R.id.reader_title)
        pageText = view.findViewById(R.id.reader_page_text)
        seekBar = view.findViewById(R.id.reader_seek)

        glSurface?.visibility = View.GONE
        setupPager()

        view.findViewById<ImageButton>(R.id.reader_btn_close).setOnClickListener {
            requireActivity().finish()
        }
        view.findViewById<ImageButton>(R.id.reader_btn_share).setOnClickListener { showShareMenu() }
        view.findViewById<ImageButton>(R.id.reader_btn_options).setOnClickListener { showOptionsMenu() }

        seekBar?.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    seeking = true
                    viewModel.goToSlot(progress)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) { seeking = false }
        })

        openFromArgs()
        collectSession()
    }

    private fun setupPager() {
        val pager = pagePager ?: return
        val adapter = PagedSlotAdapter(
            viewModel = viewModel,
            decodeCache = decodeCache,
            isScreenPortrait = { isScreenPortrait() },
            onCenterTap = { viewModel.toggleChrome() },
            onEdgeTap = { forward ->
                if (viewModel.session.value.chromeVisible) {
                    viewModel.setChromeVisible(false)
                } else {
                    viewModel.advance(forward)
                }
            },
        )
        pagedAdapter = adapter
        pager.adapter = adapter
        pager.offscreenPageLimit = 2
        // TRANSLATE is ViewPager2's native motion — avoid transformer overhead.
        pager.setPageTransformer(null)
        val callback = object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                if (syncingPager) return
                if (position != viewModel.session.value.currentSlotIndex) {
                    viewModel.goToSlot(position)
                }
                pinAround(position)
            }

            override fun onPageScrollStateChanged(state: Int) {
                allowPerpendicularAdvance = state == ViewPager2.SCROLL_STATE_IDLE
            }
        }
        pagerCallback = callback
        pager.registerOnPageChangeCallback(callback)
        installDualAxisGesture(pager)
    }

    private fun installDualAxisGesture(pager: ViewPager2) {
        pager.post {
            val rv = pager.getChildAt(0) as? RecyclerView ?: return@post
            dualAxisTouchHelper?.let { rv.removeOnItemTouchListener(it) }
            var downX = 0f
            var downY = 0f
            var captured = false
            var pageView: View? = null
            var incomingView: View? = null
            var incomingTarget = -1
            var primaryHorizontal = true
            val touchSlop = android.view.ViewConfiguration.get(rv.context).scaledTouchSlop
            val settleInterpolator =
                android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1f)

            fun isForward(delta: Float): Boolean =
                // Vertical comic scrolling follows content direction: an upward
                // finger swipe scrolls down and advances to the next page.
                if (primaryHorizontal) delta < 0f else delta > 0f

            fun resetIncoming() {
                incomingView?.animate()?.cancel()
                incomingView?.translationX = 0f
                incomingView?.translationY = 0f
                incomingView?.alpha = 1f
                incomingView = null
                incomingTarget = -1
            }

            fun positionPages(delta: Float) {
                val page = pageView ?: return
                val size = if (primaryHorizontal) page.height else page.width
                val forward = isForward(delta)
                val target = (pager.currentItem + if (forward) 1 else -1)
                    .coerceIn(0, (pagedAdapter?.itemCount ?: 1) - 1)

                if (target == pager.currentItem) {
                    resetIncoming()
                    if (primaryHorizontal) page.translationY = delta * 0.28f
                    else page.translationX = delta * 0.28f
                    return
                }

                if (incomingTarget != target) {
                    resetIncoming()
                    incomingTarget = target
                    incomingView = rv.layoutManager?.findViewByPosition(target)
                }

                val incoming = incomingView
                if (primaryHorizontal) {
                    page.translationY = delta
                    if (incoming != null) {
                        // Adjacent ViewPager items are laid out horizontally. Cancel
                        // that offset, then place the next page directly above/below.
                        incoming.translationX = (page.left - incoming.left).toFloat()
                        incoming.translationY =
                            (page.top - incoming.top).toFloat() +
                            delta - (if (delta > 0f) size else -size)
                    }
                } else {
                    page.translationX = delta
                    if (incoming != null) {
                        incoming.translationY = (page.top - incoming.top).toFloat()
                        incoming.translationX =
                            (page.left - incoming.left).toFloat() +
                            delta - (if (delta > 0f) size else -size)
                    }
                }
            }

            fun finishPerpendicularSwipe(delta: Float) {
                val page = pageView ?: return
                val size = if (primaryHorizontal) page.height else page.width
                val forward = isForward(delta)
                val target = (pager.currentItem + if (forward) 1 else -1)
                    .coerceIn(0, (pagedAdapter?.itemCount ?: 1) - 1)
                val shouldAdvance =
                    abs(delta) > size * 0.10f && target != pager.currentItem

                if (!shouldAdvance || incomingView == null) {
                    val incoming = incomingView
                    val returnDuration = 210L
                    page.animate()
                        .translationX(0f)
                        .translationY(0f)
                        .alpha(1f)
                        .setDuration(returnDuration)
                        .setInterpolator(settleInterpolator)
                        .withEndAction {
                            resetIncoming()
                            allowPerpendicularAdvance = true
                        }
                        .start()
                    incoming?.animate()
                        ?.translationX(0f)
                        ?.translationY(0f)
                        ?.alpha(1f)
                        ?.setDuration(returnDuration)
                        ?.setInterpolator(settleInterpolator)
                        ?.start()
                    return
                }

                val destination = if (delta > 0f) size.toFloat() else -size.toFloat()
                val progress = (abs(delta) / size).coerceIn(0f, 1f)
                // Match ViewPager's measured settle rather than snapping in 160 ms.
                val settleDuration = ((1f - progress) * 320f)
                    .toLong()
                    .coerceIn(160L, 320L)
                val incoming = incomingView
                val animator = page.animate()
                    .alpha(1f)
                    .setDuration(settleDuration)
                    .setInterpolator(settleInterpolator)
                if (primaryHorizontal) animator.translationY(destination)
                else animator.translationX(destination)
                incoming?.animate()
                    ?.translationX(if (primaryHorizontal) incoming.translationX else 0f)
                    ?.translationY(if (primaryHorizontal) 0f else incoming.translationY)
                    ?.alpha(1f)
                    ?.setDuration(settleDuration)
                    ?.setInterpolator(settleInterpolator)
                    ?.start()
                animator.withEndAction {
                    page.translationX = 0f
                    page.translationY = 0f
                    page.alpha = 1f
                    syncingPager = true
                    pager.setCurrentItem(target, false)
                    syncingPager = false
                    viewModel.goToSlot(target)
                    resetIncoming()
                    allowPerpendicularAdvance = true
                }.start()
            }

            val helper = object : RecyclerView.SimpleOnItemTouchListener() {
                override fun onInterceptTouchEvent(recyclerView: RecyclerView, e: MotionEvent): Boolean {
                    val session = viewModel.session.value
                    val continuous = session.preferences.layout.forScreen(isScreenPortrait()) in
                        setOf(PagesLayout.CONTINUOUS_VERTICAL, PagesLayout.CONTINUOUS_HORIZONTAL)
                    if (continuous) return false
                    when (e.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            downX = e.x
                            downY = e.y
                            captured = false
                            resetIncoming()
                            primaryHorizontal = pager.orientation == ViewPager2.ORIENTATION_HORIZONTAL
                            pageView = recyclerView.layoutManager
                                ?.findViewByPosition(pager.currentItem)
                        }
                        MotionEvent.ACTION_MOVE -> {
                            if (e.pointerCount > 1) {
                                // Pinch always wins over page navigation.
                                captured = false
                                pageView?.animate()?.cancel()
                                pageView?.translationX = 0f
                                pageView?.translationY = 0f
                                resetIncoming()
                                allowPerpendicularAdvance = true
                                recyclerView.requestDisallowInterceptTouchEvent(true)
                                return false
                            }
                            val dx = e.x - downX
                            val dy = e.y - downY
                            if (session.preferences.transitions.axis != TransitionAxis.BOTH) return false
                            if (!allowPerpendicularAdvance) return false
                            val zoomPage = pageView as? ZoomablePageView
                            if (zoomPage?.hasPanOrZoom == true && !zoomPage.parentHandoff) {
                                // The page owns the first gesture while zoomed. It
                                // explicitly releases us only on a new outward drag
                                // that began at a clamped border.
                                return false
                            }
                            val perpendicular = if (primaryHorizontal) {
                                // Deliberately generous: diagonals should feel like a
                                // confident guess, not an axis-alignment test.
                                abs(dy) >= abs(dx) * 0.70f &&
                                    abs(dy) > touchSlop * 1.25f
                            } else {
                                abs(dx) >= abs(dy) * 0.70f &&
                                    abs(dx) > touchSlop * 1.25f
                            }
                            if (perpendicular) {
                                captured = true
                                allowPerpendicularAdvance = false
                                recyclerView.stopScroll()
                                positionPages(if (primaryHorizontal) dy else dx)
                                return true
                            }
                        }
                        MotionEvent.ACTION_UP -> Unit
                        MotionEvent.ACTION_CANCEL -> {
                            captured = false
                            pageView?.animate()
                                ?.translationX(0f)
                                ?.translationY(0f)
                                ?.alpha(1f)
                                ?.setDuration(100)
                                ?.start()
                            resetIncoming()
                            allowPerpendicularAdvance = true
                        }
                    }
                    return false
                }

                override fun onTouchEvent(recyclerView: RecyclerView, e: MotionEvent) {
                    if (e.pointerCount > 1) {
                        captured = false
                        pageView?.animate()?.cancel()
                        pageView?.translationX = 0f
                        pageView?.translationY = 0f
                        resetIncoming()
                        allowPerpendicularAdvance = true
                        recyclerView.requestDisallowInterceptTouchEvent(true)
                        return
                    }
                    if (!captured) return
                    val delta = if (primaryHorizontal) e.y - downY else e.x - downX
                    when (e.actionMasked) {
                        MotionEvent.ACTION_MOVE -> {
                            positionPages(delta)
                        }
                        MotionEvent.ACTION_UP -> {
                            captured = false
                            finishPerpendicularSwipe(delta)
                        }
                        MotionEvent.ACTION_CANCEL -> {
                            captured = false
                            finishPerpendicularSwipe(0f)
                            allowPerpendicularAdvance = true
                        }
                    }
                }
            }
            dualAxisTouchHelper = helper
            rv.addOnItemTouchListener(helper)
        }
    }

    private fun setLayoutForScreen(layout: PagesLayout) {
        viewModel.updatePreferences { p ->
            if (isScreenPortrait()) p.copy(layout = p.layout.copy(portrait = layout))
            else p.copy(layout = p.layout.copy(landscape = layout))
        }
        lastSlot = -1
    }

    private fun pinAround(index: Int) {
        val session = viewModel.session.value
        val color = session.preferences.colorCorrection
        val maxEdge = displayMaxEdge()
        val keys = (-1..2).mapNotNull { delta ->
            val slot = session.slots.getOrNull(index + delta) ?: return@mapNotNull null
            decodeCache.cacheKey(slot, color, maxEdge)
        }
        decodeCache.pinKeys(keys)
    }

    private fun displayMaxEdge(): Int {
        val dm = resources.displayMetrics
        return maxOf(dm.widthPixels, dm.heightPixels)
    }

    private fun hideHostChrome() {
        // Hide Bubble2 overlay chrome when using Cupcake reader layout inside ReaderActivity.
        activity?.findViewById<View>(R.id.menu_frame_reader)?.visibility = View.GONE
        (activity as? AppCompatActivity)?.supportActionBar?.hide()
        val window = activity?.window ?: return
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, requireView()).let { c ->
            c.hide(WindowInsetsCompat.Type.systemBars())
            c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun openFromArgs() {
        val args = arguments ?: return
        val identity = args.getString(ReaderFragment.PARAM_IDENTITY_KEY)
            ?: activity?.intent?.getStringExtra(ReaderFragment.PARAM_IDENTITY_KEY)
        val mode = args.getSerializable(ReaderFragment.PARAM_MODE) as? ReaderFragment.Mode
            ?: ReaderFragment.Mode.MODE_BROWSER
        val initialPage = args.getInt(ReaderFragment.PARAM_PAGE, 0)
            .takeIf { it > 0 }
            ?: activity?.intent?.getIntExtra(ReaderFragment.PARAM_PAGE, 0)?.takeIf { it > 0 }
            ?: 0

        lifecycleScope.launch {
            try {
                val shareId = args.getLong(PARAM_SMB_SHARE_ID, -1L)
                    .takeIf { it > 0 }
                    ?: activity?.intent?.getLongExtra(PARAM_SMB_SHARE_ID, -1L)?.takeIf { it > 0 }
                    ?: -1L
                val rel = args.getString(PARAM_SMB_RELATIVE_PATH)
                    ?: activity?.intent?.getStringExtra(PARAM_SMB_RELATIVE_PATH)
                if (shareId > 0 && !rel.isNullOrBlank()) {
                    val opened = withContext(kotlinx.coroutines.Dispatchers.IO) {
                        val share = com.cupcakecomics.data.ConnectionRepository(requireContext())
                            .getSmbShare(shareId)
                            ?: throw IllegalArgumentException("Share not found")
                        com.cupcakecomics.reader.source.PageSourceFactory.openSmb(
                            requireContext(), share, rel, keepOffline = false,
                        )
                    }
                    viewModel.open(opened.source, identity, null, initialPage)
                    return@launch
                }

                val source: PageSource
                var comic: Comic? = null
                when (mode) {
                    ReaderFragment.Mode.MODE_LIBRARY -> {
                        val id = args.getInt(ReaderFragment.PARAM_HANDLER)
                        comic = Storage.getStorage(requireContext()).getComic(id)
                        source = ParserPageSource.fromFile(comic.file)
                    }
                    ReaderFragment.Mode.MODE_BROWSER -> {
                        val file = args.getSerializable(ReaderFragment.PARAM_HANDLER) as File
                        source = ParserPageSource.fromFile(file)
                        viewModel.open(source, identity, comic, initialPage, file.absolutePath)
                        return@launch
                    }
                    ReaderFragment.Mode.MODE_INTENT -> {
                        val intent = args.getParcelable(ReaderFragment.PARAM_HANDLER) as? Intent
                            ?: activity?.intent
                        val parser = ParserFactory.create(intent)
                            ?: throw IllegalArgumentException("No parser for intent")
                        val title = intent?.data?.lastPathSegment.orEmpty()
                        source = ParserPageSource.fromParser(parser, title)
                        val localPath = intent?.data?.toString()
                        viewModel.open(source, identity, comic, initialPage, localPath)
                        return@launch
                    }
                }
                viewModel.open(source, identity, comic, initialPage)
            } catch (t: Throwable) {
                loading?.visibility = View.GONE
                errorView?.visibility = View.VISIBLE
                errorView?.text = t.message ?: t.javaClass.simpleName
            }
        }
    }

    private fun collectSession() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.session.collect { session ->
                    bindSession(session)
                }
            }
        }
    }

    private fun bindSession(session: ReaderSession) {
        loading?.visibility = if (session.loading) View.VISIBLE else View.GONE
        if (session.error != null) {
            errorView?.visibility = View.VISIBLE
            errorView?.text = session.error
        } else {
            errorView?.visibility = View.GONE
        }

        // Keep the close button reachable while opening/downloading a comic.
        val showChrome = session.chromeVisible || session.loading || session.error != null
        if (lastChromeVisible != showChrome) {
            lastChromeVisible = showChrome
            chromeTop?.visibility = if (showChrome) View.VISIBLE else View.GONE
            applyImmersive(!showChrome)
        }
        chromeBottom?.visibility = if (session.chromeVisible) View.VISIBLE else View.GONE

        if (showChrome) {
            titleView?.text = session.title.ifBlank {
                getString(R.string.smb_staging)
            }
        }
        if (session.chromeVisible) {
            val slotCount = session.slots.size.coerceAtLeast(1)
            seekBar?.max = (slotCount - 1).coerceAtLeast(0)
            if (!seeking) seekBar?.progress = session.currentSlotIndex
            pageText?.text = "${session.currentPageIndex + 1} / ${session.pageCount}"
        }

        val continuous = session.preferences.layout.forScreen(isScreenPortrait()) in
            setOf(PagesLayout.CONTINUOUS_VERTICAL, PagesLayout.CONTINUOUS_HORIZONTAL)

        val axis = session.preferences.transitions.axis
        val flow = session.preferences.readingFlow
        val transition = when {
            continuous -> PageTransition.TRANSLATE
            axis == TransitionAxis.VERTICAL ->
                session.preferences.transitions.verticalFor(isScreenPortrait())
            else -> session.preferences.transitions.horizontalFor(isScreenPortrait())
        }
        if (lastTransition != transition) {
            lastTransition = transition
            pageTransformer.type = transition
            pagePager?.setPageTransformer(
                if (transition == PageTransition.TRANSLATE) null else pageTransformer,
            )
        }

        val rtl = flow == ReadingFlow.RIGHT_TO_LEFT
        pagePager?.layoutDirection = if (rtl) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
        val pagerOrientation = when {
            continuous -> ViewPager2.ORIENTATION_HORIZONTAL
            axis == TransitionAxis.VERTICAL -> ViewPager2.ORIENTATION_VERTICAL
            else -> ViewPager2.ORIENTATION_HORIZONTAL
        }
        if (lastPagerOrientation != pagerOrientation) {
            lastPagerOrientation = pagerOrientation
            pagePager?.orientation = pagerOrientation
            pagePager?.let { installDualAxisGesture(it) }
        }

        if (continuous) {
            pagePager?.visibility = View.GONE
            continuousList?.visibility = View.VISIBLE
            ensureContinuousAdapter(session)
        } else {
            continuousList?.visibility = View.GONE
            pagePager?.visibility = View.VISIBLE
            pagedAdapter?.submit(session, displayMaxEdge())
            val target = session.currentSlotIndex
            if (pagePager?.currentItem != target) {
                syncingPager = true
                // Never animate programmatic jumps — animation while also decoding feels like stutter.
                pagePager?.setCurrentItem(target, false)
                syncingPager = false
            }
            if (target != lastSlot && !session.loading) {
                lastSlot = target
                pinAround(target)
            }
        }

        if (session.preferences.lockRotation) {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LOCKED
        } else {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    private fun prefetchNeighbors(session: ReaderSession) {
        val source = viewModel.pageSourceOrNull() ?: return
        val maxEdge = displayMaxEdge()
        for (delta in listOf(-2, -1, 1, 2, 3)) {
            val neighbor = session.slots.getOrNull(session.currentSlotIndex + delta) ?: continue
            decodeCache.request(
                source, neighbor, session.descriptors,
                session.preferences.colorCorrection, maxEdge,
                pinOnReady = true,
            ) { /* warm cache */ }
        }
    }

    private fun ensureContinuousAdapter(session: ReaderSession) {
        val list = continuousList ?: return
        val vertical = session.preferences.layout.forScreen(isScreenPortrait()) == PagesLayout.CONTINUOUS_VERTICAL
        val rtl = session.preferences.readingFlow == ReadingFlow.RIGHT_TO_LEFT
        val desiredOrientation = if (vertical) RecyclerView.VERTICAL else RecyclerView.HORIZONTAL
        val lm = list.layoutManager as? LinearLayoutManager
        if (lm == null || lm.orientation != desiredOrientation || lm.reverseLayout != (!vertical && rtl)) {
            list.layoutManager = LinearLayoutManager(
                requireContext(),
                desiredOrientation,
                !vertical && rtl,
            )
        }
        val adapter = list.adapter as? ContinuousPageAdapter
        if (adapter == null) {
            list.adapter = ContinuousPageAdapter(viewModel, decodeCache) {
                viewModel.toggleChrome()
            }
            continuousScrollListener?.let { list.removeOnScrollListener(it) }
            val listener = object : RecyclerView.OnScrollListener() {
                override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                    if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                        syncContinuousSlot(recyclerView)
                    }
                }
            }
            continuousScrollListener = listener
            list.addOnScrollListener(listener)
        }
        (list.adapter as ContinuousPageAdapter).submit(session, vertical)
        if (session.currentSlotIndex != lastSlot) {
            list.scrollToPosition(session.currentSlotIndex)
            lastSlot = session.currentSlotIndex
            pinAround(session.currentSlotIndex)
        }
    }

    private fun syncContinuousSlot(recyclerView: RecyclerView) {
        val lm = recyclerView.layoutManager as? LinearLayoutManager ?: return
        val first = lm.findFirstVisibleItemPosition()
        if (first >= 0) viewModel.goToSlot(first, fromUser = false)
        pinAround(first.coerceAtLeast(0))
    }

    private fun applyImmersive(immersive: Boolean) {
        val window = activity?.window ?: return
        val controller = WindowInsetsControllerCompat(window, requireView())
        if (immersive) {
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun isScreenPortrait(): Boolean =
        resources.configuration.orientation != Configuration.ORIENTATION_LANDSCAPE

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        viewModel.setScreenPortrait(newConfig.orientation != Configuration.ORIENTATION_LANDSCAPE)
        lastSlot = -1
    }

    override fun onPause() {
        viewModel.reportProgress()
        glSurface?.onPause()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        glSurface?.onResume()
        hideHostChrome()
    }

    override fun onDestroyView() {
        pagerCallback?.let { pagePager?.unregisterOnPageChangeCallback(it) }
        pagerCallback = null
        continuousScrollListener?.let { continuousList?.removeOnScrollListener(it) }
        continuousScrollListener = null
        continuousList?.adapter = null
        pagePager?.adapter = null
        pagedAdapter = null
        decodeCache.clear()
        glSurface = null
        pagePager = null
        continuousList = null
        super.onDestroyView()
    }

    // --- Settings sheets ---

    private fun showFlowPicker() {
        val labels = arrayOf(
            getString(R.string.reader_flow_ltr),
            getString(R.string.reader_flow_rtl),
            getString(R.string.reader_flow_ttb),
        )
        val values = arrayOf(ReadingFlow.LEFT_TO_RIGHT, ReadingFlow.RIGHT_TO_LEFT, ReadingFlow.TOP_TO_BOTTOM)
        val cur = values.indexOf(viewModel.session.value.preferences.readingFlow).coerceAtLeast(0)
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.reader_reading_flow)
            .setSingleChoiceItems(labels, cur) { d, which ->
                viewModel.setReadingFlow(values[which])
                d.dismiss()
            }
            .show()
    }

    private fun showFitPicker() {
        val labels = arrayOf(
            getString(R.string.reader_fit_screen),
            getString(R.string.reader_fit_full),
            getString(R.string.reader_fit_width),
            getString(R.string.reader_fit_height),
        )
        val values = arrayOf(FitMode.FIT_SCREEN, FitMode.FULL_SIZE, FitMode.FIT_WIDTH, FitMode.FIT_HEIGHT)
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.reader_fit_options)
            .setItems(labels) { _, which ->
                val mode = values[which]
                viewModel.updatePreferences { p ->
                    val screenP = isScreenPortrait()
                    val pageP = viewModel.session.value.currentSlot?.aspectRatio?.let { it < 1f } ?: true
                    val fit = p.fit
                    p.copy(
                        fit = when {
                            screenP && pageP -> fit.copy(portraitScreenPortraitPage = mode)
                            screenP && !pageP -> fit.copy(portraitScreenLandscapePage = mode)
                            !screenP && pageP -> fit.copy(landscapeScreenPortraitPage = mode)
                            else -> fit.copy(landscapeScreenLandscapePage = mode)
                        },
                    )
                }
                lastSlot = -1
            }
            .show()
    }

    private fun showLayoutPicker() {
        val labels = arrayOf(
            getString(R.string.reader_layout_single),
            getString(R.string.reader_layout_double),
            getString(R.string.reader_layout_double_cover),
            getString(R.string.reader_layout_cont_v),
            getString(R.string.reader_layout_cont_h),
        )
        val values = arrayOf(
            PagesLayout.SINGLE,
            PagesLayout.DOUBLE,
            PagesLayout.DOUBLE_WITH_COVER,
            PagesLayout.CONTINUOUS_VERTICAL,
            PagesLayout.CONTINUOUS_HORIZONTAL,
        )
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.reader_pages_layout)
            .setItems(labels) { _, which ->
                val layout = values[which]
                viewModel.updatePreferences { p ->
                    if (isScreenPortrait()) p.copy(layout = p.layout.copy(portrait = layout))
                    else p.copy(layout = p.layout.copy(landscape = layout))
                }
                lastSlot = -1
            }
            .show()
    }

    private fun showTransitionPicker() {
        val labels = arrayOf(
            getString(R.string.reader_trans_translate),
            getString(R.string.reader_trans_page),
            getString(R.string.reader_trans_inplace),
            getString(R.string.reader_trans_scale),
            getString(R.string.reader_trans_axis_h),
            getString(R.string.reader_trans_axis_v),
            getString(R.string.reader_trans_axis_both),
        )
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.reader_page_transitions)
            .setItems(labels) { _, which ->
                viewModel.updatePreferences { p ->
                    when (which) {
                        0 -> p.copy(
                            transitions = p.transitions.copy(
                                horizontal = PageTransition.TRANSLATE,
                                vertical = PageTransition.TRANSLATE,
                                landscapeHorizontal = PageTransition.TRANSLATE,
                                landscapeVertical = PageTransition.TRANSLATE,
                            ),
                        )
                        1 -> p.copy(
                            transitions = p.transitions.copy(
                                horizontal = PageTransition.PAGE,
                                vertical = PageTransition.PAGE,
                            ),
                        )
                        2 -> p.copy(
                            transitions = p.transitions.copy(
                                horizontal = PageTransition.IN_PLACE,
                                vertical = PageTransition.IN_PLACE,
                            ),
                        )
                        3 -> p.copy(
                            transitions = p.transitions.copy(
                                horizontal = PageTransition.SCALE,
                                landscapeHorizontal = PageTransition.SCALE,
                            ),
                        )
                        4 -> p.copy(transitions = p.transitions.copy(axis = TransitionAxis.HORIZONTAL))
                        5 -> p.copy(transitions = p.transitions.copy(axis = TransitionAxis.VERTICAL))
                        else -> p.copy(transitions = p.transitions.copy(axis = TransitionAxis.BOTH))
                    }
                }
            }
            .show()
    }

    private fun showColorSheet() {
        val ctx = requireContext()
        val pad = (16 * resources.displayMetrics.density).toInt()
        val layout = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        fun slider(label: String, min: Int, max: Int, cur: Int, onChange: (Int) -> Unit): SeekBar {
            layout.addView(TextView(ctx).apply { text = label; setTextColor(0xFFFFFFFF.toInt()) })
            val sb = SeekBar(ctx).apply {
                this.max = max - min
                progress = (cur - min).coerceIn(0, max - min)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                        if (fromUser) onChange(progress + min)
                    }
                    override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                    override fun onStopTrackingTouch(seekBar: SeekBar?) {
                        lastSlot = -1
                        pinAround(viewModel.session.value.currentSlotIndex)
                        prefetchNeighbors(viewModel.session.value)
                    }
                })
            }
            layout.addView(sb)
            return sb
        }
        val c = viewModel.session.value.preferences.colorCorrection
        slider(getString(R.string.reader_wb), 0, 100, (c.whiteBalance * 100).toInt()) { v ->
            viewModel.updatePreferences { p ->
                p.copy(colorCorrection = p.colorCorrection.copy(whiteBalance = v / 100f))
            }
        }
        slider(getString(R.string.reader_vibrance), -100, 100, (c.vibrance * 100).toInt()) { v ->
            viewModel.updatePreferences { p ->
                p.copy(colorCorrection = p.colorCorrection.copy(vibrance = v / 100f))
            }
        }
        slider(getString(R.string.reader_gamma), 20, 300, (c.gammaR * 100).toInt()) { v ->
            val g = v / 100f
            viewModel.updatePreferences { p ->
                p.copy(colorCorrection = p.colorCorrection.copy(gammaR = g, gammaG = g, gammaB = g, linkGamma = true))
            }
        }
        AlertDialog.Builder(ctx)
            .setTitle(R.string.reader_color_corrections)
            .setView(layout)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun showOptionsMenu() {
        val items = arrayOf(
            getString(R.string.reader_pages_layout),
            getString(R.string.reader_fit_options),
            getString(R.string.reader_reading_flow),
            getString(R.string.reader_page_transitions),
            getString(R.string.reader_color_corrections),
            getString(R.string.reader_bookmark),
            getString(R.string.reader_toc),
            getString(R.string.action_view_export),
            getString(R.string.reader_crop_borders),
            getString(R.string.reader_lock_rotation),
            getString(R.string.reader_split_spreads),
            getString(R.string.action_view_rotate),
        )
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.reader_options)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> showLayoutPicker()
                    1 -> showFitPicker()
                    2 -> showFlowPicker()
                    3 -> showTransitionPicker()
                    4 -> showColorSheet()
                    5 -> viewModel.toggleBookmarkOnCurrent()
                    6 -> showToc()
                    7 -> exportCurrentPage()
                    8 -> viewModel.updatePreferences { it.copy(cropBorders = !it.cropBorders) }
                    9 -> viewModel.updatePreferences { it.copy(lockRotation = !it.lockRotation) }
                    10 -> viewModel.updatePreferences { it.copy(splitSpreads = !it.splitSpreads) }
                    11 -> {
                        viewModel.rotateCurrentPage(90)
                        lastSlot = -1
                    }
                }
            }
            .show()
    }

    private fun showToc() {
        val toc = viewModel.session.value.toc
        if (toc.isEmpty()) {
            Toast.makeText(requireContext(), "No table of contents", Toast.LENGTH_SHORT).show()
            return
        }
        val labels = toc.map { it.title }.toTypedArray()
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.reader_toc)
            .setItems(labels) { _, which ->
                viewModel.goToPage(toc[which].pageIndex)
                lastSlot = -1
            }
            .show()
    }

    private fun showShareMenu() {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.reader_share)
            .setItems(
                arrayOf(
                    getString(R.string.reader_share_page),
                    getString(R.string.reader_share_crop),
                ),
            ) { _, which ->
                when (which) {
                    0 -> shareCurrentPage(crop = false)
                    1 -> shareCurrentPage(crop = true)
                }
            }
            .show()
    }

    private fun shareCurrentPage(crop: Boolean) {
        val source = viewModel.pageSourceOrNull() ?: return
        val page = viewModel.session.value.currentPageIndex
        lifecycleScope.launch {
            try {
                val bitmap = withContext(kotlinx.coroutines.Dispatchers.IO) {
                    val bytes = source.openPage(page).use { it.readBytes() }
                    android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        ?: throw IllegalStateException(getString(R.string.reader_share_failed))
                }
                if (crop) {
                    showCropAndShare(bitmap)
                } else {
                    launchShare(bitmap, page)
                }
            } catch (t: Throwable) {
                Toast.makeText(
                    requireContext(),
                    t.message ?: getString(R.string.reader_share_failed),
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    private fun showCropAndShare(bitmap: Bitmap) {
        val cropView = com.cupcakecomics.reader.ui.PageCropView(requireContext())
        cropView.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            (resources.displayMetrics.heightPixels * 0.7f).toInt(),
        )
        cropView.setBitmap(bitmap)
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.reader_crop_title)
            .setView(cropView)
            .setPositiveButton(R.string.reader_crop_share) { _, _ ->
                val cropped = cropView.croppedBitmap()
                if (cropped != null) {
                    launchShare(cropped, viewModel.session.value.currentPageIndex)
                } else {
                    Toast.makeText(requireContext(), R.string.reader_share_failed, Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun launchShare(bitmap: Bitmap, page: Int) {
        lifecycleScope.launch {
            try {
                val uri = withContext(kotlinx.coroutines.Dispatchers.IO) {
                    writeShareBitmap(bitmap, page)
                }
                if (uri == null) {
                    Toast.makeText(requireContext(), R.string.reader_share_failed, Toast.LENGTH_SHORT).show()
                    return@launch
                }
                val share = Intent(Intent.ACTION_SEND).apply {
                    type = "image/jpeg"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(
                        Intent.EXTRA_SUBJECT,
                        "${viewModel.session.value.title} · p.${page + 1}",
                    )
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(share, getString(R.string.reader_share_chooser)))
            } catch (t: Throwable) {
                Toast.makeText(
                    requireContext(),
                    t.message ?: getString(R.string.reader_share_failed),
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    private fun writeShareBitmap(bitmap: Bitmap, page: Int): Uri? {
        val name = "${viewModel.session.value.title}.page${page + 1}.jpg"
            .replace(Regex("""[\\\\/:*?"<>|]"""), "_")
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/CupcakeComics")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val resolver = requireContext().contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        resolver.openOutputStream(uri)?.use { out ->
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)) {
                resolver.delete(uri, null, null)
                return null
            }
        } ?: run {
            resolver.delete(uri, null, null)
            return null
        }
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        }
        return uri
    }

    private fun exportCurrentPage() {
        val source = viewModel.pageSourceOrNull() ?: return
        val page = viewModel.session.value.currentPageIndex
        lifecycleScope.launch {
            try {
                val bytes = withContext(kotlinx.coroutines.Dispatchers.IO) {
                    source.openPage(page).use { it.readBytes() }
                }
                val name = "${viewModel.session.value.title}.page${page + 1}.jpg"
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, name)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/CupcakeComics")
                }
                val uri = requireContext().contentResolver.insert(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    values,
                )
                if (uri != null) {
                    requireContext().contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                    AlertDialog.Builder(requireContext())
                        .setMessage(R.string.reader_export_ok)
                        .setPositiveButton(R.string.reader_export_open) { _, _ ->
                            startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "image/jpeg").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                        }
                        .setNegativeButton(android.R.string.ok, null)
                        .show()
                } else {
                    Toast.makeText(requireContext(), "Export failed", Toast.LENGTH_SHORT).show()
                }
            } catch (t: Throwable) {
                Toast.makeText(requireContext(), t.message, Toast.LENGTH_LONG).show()
            }
        }
    }

    companion object {
        const val PARAM_SMB_SHARE_ID = "PARAM_SMB_SHARE_ID"
        const val PARAM_SMB_RELATIVE_PATH = "PARAM_SMB_RELATIVE_PATH"
        const val PARAM_USE_GPU_READER = "PARAM_USE_GPU_READER"

        @JvmStatic
        fun createLibrary(comicId: Int, identityKey: String? = null): CupcakeReaderFragment {
            return CupcakeReaderFragment().apply {
                arguments = Bundle().apply {
                    putSerializable(ReaderFragment.PARAM_MODE, ReaderFragment.Mode.MODE_LIBRARY)
                    putInt(ReaderFragment.PARAM_HANDLER, comicId)
                    identityKey?.let { putString(ReaderFragment.PARAM_IDENTITY_KEY, it) }
                }
            }
        }

        @JvmStatic
        fun createFile(file: File, identityKey: String? = null): CupcakeReaderFragment {
            return CupcakeReaderFragment().apply {
                arguments = Bundle().apply {
                    putSerializable(ReaderFragment.PARAM_MODE, ReaderFragment.Mode.MODE_BROWSER)
                    putSerializable(ReaderFragment.PARAM_HANDLER, file)
                    identityKey?.let { putString(ReaderFragment.PARAM_IDENTITY_KEY, it) }
                }
            }
        }

        @JvmStatic
        fun createIntent(intent: Intent): CupcakeReaderFragment {
            return CupcakeReaderFragment().apply {
                arguments = Bundle().apply {
                    putSerializable(ReaderFragment.PARAM_MODE, ReaderFragment.Mode.MODE_INTENT)
                    putParcelable(ReaderFragment.PARAM_HANDLER, intent)
                }
            }
        }
    }
}

private class ContinuousPageAdapter(
    private val viewModel: ReaderViewModel,
    private val decodeCache: PageDecodeCache,
    private val onTap: () -> Unit,
) : RecyclerView.Adapter<ContinuousPageAdapter.VH>() {
    private var session: ReaderSession = ReaderSession()
    private var vertical: Boolean = true

    fun submit(s: ReaderSession, verticalMode: Boolean) {
        val changed = session.slots.size != s.slots.size ||
            session.preferences.colorCorrection != s.preferences.colorCorrection ||
            vertical != verticalMode
        session = s
        vertical = verticalMode
        if (changed) notifyDataSetChanged()
    }

    override fun getItemCount(): Int = session.slots.size

    override fun getItemViewType(position: Int): Int = if (vertical) 1 else 2

    override fun getItemId(position: Int): Long =
        session.slots.getOrNull(position)?.primaryPage?.toLong() ?: position.toLong()

    init {
        setHasStableIds(true)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val gutter = (8 * parent.resources.displayMetrics.density).toInt()
        val iv = android.widget.ImageView(parent.context).apply {
            layoutParams = if (vertical) {
                RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).also { it.bottomMargin = gutter }
            } else {
                RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ).also { it.marginEnd = gutter }
            }
            adjustViewBounds = true
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(0xFFFFFFFF.toInt())
        }
        return VH(iv)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val slot = session.slots.getOrNull(position) ?: return
        val source = viewModel.pageSourceOrNull() ?: return
        val metrics = holder.itemView.resources.displayMetrics
        val maxEdge = if (vertical) metrics.widthPixels else metrics.heightPixels
        holder.bindGeneration++
        val gen = holder.bindGeneration
        holder.itemView.setOnClickListener { onTap() }
        val key = decodeCache.cacheKey(slot, session.preferences.colorCorrection, maxEdge)
        val cached = decodeCache.get(key)
        if (cached != null) {
            holder.image.setImageBitmap(cached)
            holder.boundKey = key
            decodeCache.pin(key)
            return
        }
        decodeCache.request(
            source = source,
            slot = slot,
            descriptors = session.descriptors,
            color = session.preferences.colorCorrection,
            maxEdge = maxEdge,
            pinOnReady = true,
        ) { decoded ->
            if (gen != holder.bindGeneration) return@request
            if (decoded.bitmap.isRecycled) return@request
            if (holder.bindingAdapterPosition != position) return@request
            holder.image.setImageBitmap(decoded.bitmap)
            holder.image.setBackgroundColor(decoded.matteColor)
            holder.boundKey = decoded.key
        }
    }

    override fun onViewRecycled(holder: VH) {
        holder.boundKey?.let { decodeCache.unpin(it) }
        holder.boundKey = null
        holder.bindGeneration++
        super.onViewRecycled(holder)
    }

    class VH(val image: android.widget.ImageView) : RecyclerView.ViewHolder(image) {
        var bindGeneration: Int = 0
        var boundKey: String? = null
    }
}

