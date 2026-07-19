package com.cupcakecomics.reader.gl

import android.content.Context
import android.graphics.Bitmap
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.opengl.Matrix
import android.util.AttributeSet
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import com.cupcakecomics.reader.model.ColorCorrection
import com.cupcakecomics.reader.model.FitMode
import com.cupcakecomics.reader.model.PageTransition
import com.cupcakecomics.reader.model.ReadingFlow
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * OpenGL ES page surface. Uses a GLES2-safe bilinear sampler by default;
 * optional Lanczos uses hardcoded weights (no dynamic uniform-array indexing).
 */
class ReaderGlSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : GLSurfaceView(context, attrs) {

    interface Callback {
        fun onCenterTap()
        fun onEdgeTap(forward: Boolean)
        fun onSwipePage(forward: Boolean)
        fun onLongPressCenter()
    }

    var callback: Callback? = null

    private val renderer = PageGlRenderer()
    private val scaleDetector = ScaleGestureDetector(context, ScaleListener())
    private val gestureDetector = GestureDetector(context, GestureListener())

    private var downX = 0f
    private var downY = 0f
    private var tracking = false
    private var userScaled = false

    init {
        setEGLContextClientVersion(2)
        setRenderer(renderer)
        renderMode = RENDERMODE_WHEN_DIRTY
        preserveEGLContextOnPause = true
    }

    fun setPageBitmap(bitmap: Bitmap?, matteColor: Int) {
        if (bitmap != null && bitmap.isRecycled) {
            Log.w(TAG, "Ignoring recycled bitmap")
            return
        }
        // Must set + requestRender on the GL thread so the first frame after
        // upload is not lost (requestRender before queueEvent can race).
        queueEvent {
            renderer.setBitmap(bitmap, matteColor)
            requestRender()
        }
    }

    fun setFitMode(mode: FitMode) {
        queueEvent {
            renderer.fitMode = mode
            requestRender()
        }
    }

    fun setReadingFlow(flow: ReadingFlow) {
        queueEvent { renderer.readingFlow = flow }
    }

    fun setUseLanczos(enabled: Boolean) {
        queueEvent {
            renderer.useLanczos = enabled
            requestRender()
        }
    }

    fun setColorCorrection(color: ColorCorrection) {
        queueEvent {
            renderer.colorCorrection = color
            requestRender()
        }
    }

    fun setTransitionProgress(progress: Float, horizontal: Boolean, type: PageTransition) {
        queueEvent {
            renderer.transitionProgress = progress.coerceIn(-1f, 1f)
            renderer.transitionHorizontal = horizontal
            renderer.transitionType = type
            requestRender()
        }
    }

    fun resetTransform() {
        queueEvent {
            renderer.resetTransform()
            requestRender()
        }
    }

    fun isAtHome(): Boolean = renderer.isAtHome()

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                tracking = true
                userScaled = false
            }
            MotionEvent.ACTION_MOVE -> {
                if (!scaleDetector.isInProgress && tracking && renderer.scale > renderer.minScale * 1.02f) {
                    val dx = event.x - downX
                    val dy = event.y - downY
                    downX = event.x
                    downY = event.y
                    queueEvent {
                        renderer.pan(dx, dy)
                        requestRender()
                    }
                } else if (!scaleDetector.isInProgress && tracking && renderer.isAtHome()) {
                    val dx = event.x - downX
                    val dy = event.y - downY
                    val axisH = abs(dx) >= abs(dy)
                    val progress = if (axisH) dx / width else dy / height
                    setTransitionProgress(progress, axisH, PageTransition.TRANSLATE)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (tracking && !scaleDetector.isInProgress) {
                    val dx = event.x - downX
                    val dy = event.y - downY
                    setTransitionProgress(0f, true, PageTransition.TRANSLATE)
                    if (renderer.isAtHome() && !userScaled) {
                        val threshold = min(width, height) * 0.22f
                        if (abs(dx) >= threshold || abs(dy) >= threshold) {
                            val horizontal = abs(dx) >= abs(dy)
                            val forward = if (horizontal) dx < 0 else dy < 0
                            callback?.onSwipePage(forward)
                        }
                    } else if (!renderer.isAtHome()) {
                        queueEvent {
                            renderer.snapHomeIfNeeded()
                            requestRender()
                        }
                    }
                }
                tracking = false
            }
        }
        return true
    }

    private inner class ScaleListener : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            userScaled = true
            queueEvent {
                renderer.zoom(detector.scaleFactor, detector.focusX, detector.focusY, width, height)
                requestRender()
            }
            return true
        }
    }

    private inner class GestureListener : GestureDetector.SimpleOnGestureListener() {
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            val w = width.toFloat()
            when {
                e.x < w * 0.3f -> callback?.onEdgeTap(forward = false)
                e.x > w * 0.7f -> callback?.onEdgeTap(forward = true)
                else -> callback?.onCenterTap()
            }
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            queueEvent {
                renderer.toggleDoubleTapZoom(e.x, e.y, width, height)
                requestRender()
            }
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            val w = width.toFloat()
            val h = height.toFloat()
            if (e.x in w * 0.2f..w * 0.8f && e.y in h * 0.2f..h * 0.8f) {
                callback?.onLongPressCenter()
            }
        }
    }

    companion object {
        private const val TAG = "ReaderGlSurface"
    }
}

class PageGlRenderer : GLSurfaceView.Renderer {
    @Volatile var fitMode: FitMode = FitMode.FIT_SCREEN
    @Volatile var readingFlow: ReadingFlow = ReadingFlow.LEFT_TO_RIGHT
    @Volatile var useLanczos: Boolean = false
    @Volatile var colorCorrection: ColorCorrection = ColorCorrection()
    @Volatile var transitionProgress: Float = 0f
    @Volatile var transitionHorizontal: Boolean = true
    @Volatile var transitionType: PageTransition = PageTransition.TRANSLATE
    @Volatile var scale: Float = 1f
    @Volatile var minScale: Float = 1f

    private var program = 0
    private var texId = 0
    private var bitmapW = 0
    private var bitmapH = 0
    private var viewW = 1
    private var viewH = 1
    private var pendingBitmap: Bitmap? = null
    /** Strong ref so LRU eviction cannot recycle the displayed page mid-frame. */
    private var retainedBitmap: Bitmap? = null
    private var matteR = 0f
    private var matteG = 0f
    private var matteB = 0f
    private var textureUploaded = false

    private var transX = 0f
    private var transY = 0f
    private var homeScale = 1f
    private var homeTransX = 0f
    private var homeTransY = 0f

    private val mvp = FloatArray(16)

    private lateinit var vertexBuffer: FloatBuffer
    private lateinit var texBuffer: FloatBuffer

    private var aPos = 0
    private var aTex = 0
    private var uMvp = 0
    private var uTex = 0
    private var uUseLanczos = 0
    private var uTexel = 0

    fun setBitmap(bitmap: Bitmap?, matteColor: Int) {
        if (bitmap != null && bitmap.isRecycled) return
        retainedBitmap = bitmap
        pendingBitmap = bitmap
        textureUploaded = false
        matteR = ((matteColor shr 16) and 0xff) / 255f
        matteG = ((matteColor shr 8) and 0xff) / 255f
        matteB = (matteColor and 0xff) / 255f
    }

    fun isAtHome(): Boolean =
        abs(scale - homeScale) < 0.02f && abs(transX - homeTransX) < 2f && abs(transY - homeTransY) < 2f

    fun resetTransform() {
        computeHome()
        scale = homeScale
        transX = homeTransX
        transY = homeTransY
    }

    fun snapHomeIfNeeded() {
        if (scale < homeScale * 1.05f) {
            resetTransform()
        }
    }

    fun pan(dx: Float, dy: Float) {
        transX += dx
        transY -= dy
        clampPan()
    }

    fun zoom(factor: Float, focusX: Float, focusY: Float, vw: Int, vh: Int) {
        val old = scale
        scale = (scale * factor).coerceIn(minScale, homeScale * 8f)
        val cx = focusX - vw / 2f
        transX = focusX - vw / 2f - (cx - transX) * (scale / old)
        clampPan()
    }

    fun toggleDoubleTapZoom(x: Float, y: Float, vw: Int, vh: Int) {
        if (abs(scale - homeScale) < 0.05f) {
            scale = homeScale * 1.5f
            transX = (vw / 2f - x)
            transY = (y - vh / 2f)
            clampPan()
        } else {
            resetTransform()
        }
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glDisable(GLES20.GL_BLEND)
        program = buildProgram(VERTEX, FRAGMENT)
        if (program == 0) {
            Log.e(TAG, "Falling back to simple shader")
            program = buildProgram(VERTEX, FRAGMENT_SIMPLE)
        }
        aPos = GLES20.glGetAttribLocation(program, "aPosition")
        aTex = GLES20.glGetAttribLocation(program, "aTexCoord")
        uMvp = GLES20.glGetUniformLocation(program, "uMVP")
        uTex = GLES20.glGetUniformLocation(program, "uTexture")
        uUseLanczos = GLES20.glGetUniformLocation(program, "uUseLanczos")
        uTexel = GLES20.glGetUniformLocation(program, "uTexelSize")

        val verts = floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
        vertexBuffer = ByteBuffer.allocateDirect(verts.size * 4).order(ByteOrder.nativeOrder())
            .asFloatBuffer().put(verts)
        vertexBuffer.position(0)
        // Android Bitmap + GLUtils: use top-left UV at top vertices.
        val tex = floatArrayOf(0f, 1f, 1f, 1f, 0f, 0f, 1f, 0f)
        texBuffer = ByteBuffer.allocateDirect(tex.size * 4).order(ByteOrder.nativeOrder())
            .asFloatBuffer().put(tex)
        texBuffer.position(0)

        val texIds = IntArray(1)
        GLES20.glGenTextures(1, texIds, 0)
        texId = texIds[0]
        textureUploaded = false
        // Re-upload after context loss / surface recreate.
        retainedBitmap?.takeUnless { it.isRecycled }?.let { pendingBitmap = it }
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        viewW = max(1, width)
        viewH = max(1, height)
        GLES20.glViewport(0, 0, viewW, viewH)
        computeHome()
        resetTransform()
    }

    override fun onDrawFrame(gl: GL10?) {
        pendingBitmap?.let { bmp ->
            if (!bmp.isRecycled) {
                uploadTexture(bmp)
                bitmapW = bmp.width
                bitmapH = bmp.height
                textureUploaded = true
                computeHome()
                resetTransform()
            }
            pendingBitmap = null
        }

        GLES20.glClearColor(matteR, matteG, matteB, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        if (!textureUploaded || bitmapW <= 0 || bitmapH <= 0 || program == 0 || texId == 0) return

        GLES20.glUseProgram(program)
        Matrix.setIdentityM(mvp, 0)

        val contentAspect = bitmapW.toFloat() / bitmapH.toFloat()
        val viewAspect = viewW.toFloat() / viewH.toFloat()
        var sx = 1f
        var sy = 1f
        when (fitMode) {
            FitMode.FIT_SCREEN -> {
                if (contentAspect > viewAspect) {
                    sy = viewAspect / contentAspect
                } else {
                    sx = contentAspect / viewAspect
                }
            }
            FitMode.FIT_WIDTH -> {
                sx = 1f
                sy = viewAspect / contentAspect
            }
            FitMode.FIT_HEIGHT -> {
                sy = 1f
                sx = contentAspect / viewAspect
            }
            FitMode.FULL_SIZE -> {
                val scalePx = min(viewW.toFloat() / bitmapW, viewH.toFloat() / bitmapH)
                sx = (bitmapW * scalePx) / viewW
                sy = (bitmapH * scalePx) / viewH
            }
        }

        val user = if (homeScale == 0f) 1f else scale / homeScale
        Matrix.scaleM(mvp, 0, sx * user, sy * user, 1f)
        Matrix.translateM(mvp, 0, (transX / viewW) * 2f, (transY / viewH) * 2f, 0f)

        if (abs(transitionProgress) > 0.001f) {
            val p = transitionProgress
            when (transitionType) {
                PageTransition.TRANSLATE, PageTransition.SCALE, PageTransition.PAGE -> {
                    if (transitionHorizontal) Matrix.translateM(mvp, 0, p * 2f, 0f, 0f)
                    else Matrix.translateM(mvp, 0, 0f, -p * 2f, 0f)
                }
                PageTransition.IN_PLACE -> {
                    val reveal = 1f - abs(p)
                    if (transitionHorizontal) Matrix.scaleM(mvp, 0, reveal, 1f, 1f)
                    else Matrix.scaleM(mvp, 0, 1f, reveal, 1f)
                }
            }
        }

        GLES20.glUniformMatrix4fv(uMvp, 1, false, mvp, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId)
        GLES20.glUniform1i(uTex, 0)
        if (uUseLanczos >= 0) {
            GLES20.glUniform1f(uUseLanczos, if (useLanczos) 1f else 0f)
        }
        if (uTexel >= 0) {
            GLES20.glUniform2f(uTexel, 1f / bitmapW, 1f / bitmapH)
        }

        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer)
        GLES20.glEnableVertexAttribArray(aTex)
        GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 0, texBuffer)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(aPos)
        GLES20.glDisableVertexAttribArray(aTex)
    }

    private fun uploadTexture(bitmap: Bitmap) {
        if (texId == 0) return
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        // Ensure software pixels are available for upload.
        val upload = if (bitmap.config == Bitmap.Config.HARDWARE) {
            bitmap.copy(Bitmap.Config.ARGB_8888, false) ?: bitmap
        } else {
            bitmap
        }
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, upload, 0)
        val err = GLES20.glGetError()
        if (err != GLES20.GL_NO_ERROR) {
            Log.e(TAG, "texImage2D error: 0x${Integer.toHexString(err)} ${upload.width}x${upload.height}")
        }
        if (upload !== bitmap && !upload.isRecycled) {
            upload.recycle()
        }
    }

    private fun computeHome() {
        homeScale = 1f
        minScale = 0.75f
        homeTransX = 0f
        homeTransY = 0f
        scale = homeScale
        transX = homeTransX
        transY = homeTransY
    }

    private fun clampPan() {
        val maxPan = max(viewW, viewH) * 0.5f * (scale / homeScale)
        transX = transX.coerceIn(-maxPan, maxPan)
        transY = transY.coerceIn(-maxPan, maxPan)
    }

    private fun buildProgram(vs: String, fs: String): Int {
        val v = compile(GLES20.GL_VERTEX_SHADER, vs) ?: return 0
        val f = compile(GLES20.GL_FRAGMENT_SHADER, fs) ?: return 0
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, v)
        GLES20.glAttachShader(p, f)
        GLES20.glLinkProgram(p)
        val link = IntArray(1)
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, link, 0)
        if (link[0] == 0) {
            Log.e(TAG, "Program link failed: ${GLES20.glGetProgramInfoLog(p)}")
            GLES20.glDeleteProgram(p)
            return 0
        }
        return p
    }

    private fun compile(type: Int, src: String): Int? {
        val s = GLES20.glCreateShader(type)
        GLES20.glShaderSource(s, src)
        GLES20.glCompileShader(s)
        val status = IntArray(1)
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            Log.e(TAG, "Shader compile failed: ${GLES20.glGetShaderInfoLog(s)}")
            GLES20.glDeleteShader(s)
            return null
        }
        return s
    }

    companion object {
        private const val TAG = "PageGlRenderer"

        private const val VERTEX = """
            attribute vec2 aPosition;
            attribute vec2 aTexCoord;
            uniform mat4 uMVP;
            varying vec2 vTex;
            void main() {
              vTex = aTexCoord;
              gl_Position = uMVP * vec4(aPosition, 0.0, 1.0);
            }
        """

        // GLES2-safe: no dynamic uniform array indexing. Hardcoded 3-lobe weights.
        private const val FRAGMENT = """
            precision mediump float;
            varying vec2 vTex;
            uniform sampler2D uTexture;
            uniform float uUseLanczos;
            uniform vec2 uTexelSize;

            void main() {
              if (uUseLanczos < 0.5) {
                gl_FragColor = texture2D(uTexture, vTex);
                return;
              }
              // Approximate Lanczos-3 with fixed taps (center + 4 neighbors)
              float w0 = 0.417;
              float w1 = 0.296;
              float w2 = -0.0045;
              vec4 c = texture2D(uTexture, vTex) * w0;
              c += texture2D(uTexture, vTex + vec2(uTexelSize.x, 0.0)) * w1;
              c += texture2D(uTexture, vTex - vec2(uTexelSize.x, 0.0)) * w1;
              c += texture2D(uTexture, vTex + vec2(0.0, uTexelSize.y)) * w1;
              c += texture2D(uTexture, vTex - vec2(0.0, uTexelSize.y)) * w1;
              c += texture2D(uTexture, vTex + vec2(2.0 * uTexelSize.x, 0.0)) * w2;
              c += texture2D(uTexture, vTex - vec2(2.0 * uTexelSize.x, 0.0)) * w2;
              c += texture2D(uTexture, vTex + vec2(0.0, 2.0 * uTexelSize.y)) * w2;
              c += texture2D(uTexture, vTex - vec2(0.0, 2.0 * uTexelSize.y)) * w2;
              gl_FragColor = c;
            }
        """

        private const val FRAGMENT_SIMPLE = """
            precision mediump float;
            varying vec2 vTex;
            uniform sampler2D uTexture;
            void main() {
              gl_FragColor = texture2D(uTexture, vTex);
            }
        """
    }
}
