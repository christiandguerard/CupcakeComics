package com.cupcakecomics.feedback

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import com.nkanaev.comics.BuildConfig
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class FeedbackResult(
    val stamp: String,
    val markdown: String,
    val markdownFile: File?,
    val screenshotFile: File?,
    val downloadsRelativePath: String,
)

/**
 * Captures a screenshot + UI context and writes a reviewable markdown report.
 * Files land in app storage and Downloads/CupcakeFeedback/ for easy adb pull into the project.
 */
object FeedbackCapture {
    const val DOWNLOADS_FOLDER = "CupcakeFeedback"
    const val PROJECT_PULL_HINT = "feedback/"

    fun capture(activity: Activity, note: String, hideViews: List<View> = emptyList()): FeedbackResult {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val previous = hideViews.map { it to it.visibility }
        hideViews.forEach { it.visibility = View.INVISIBLE }

        val bitmap = try {
            captureScreenshot(activity)
        } finally {
            previous.forEach { (view, vis) -> view.visibility = vis }
        }

        val contextBlock = buildContext(activity, note)
        val shotName = "feedback_$stamp.png"
        val mdName = "feedback_$stamp.md"

        val dir = feedbackDir(activity)
        val shotFile = File(dir, shotName)
        val mdFile = File(dir, mdName)

        if (bitmap != null) {
            FileOutputStream(shotFile).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
        }

        val markdown = buildMarkdown(
            stamp = stamp,
            note = note,
            contextBlock = contextBlock,
            screenshotName = if (bitmap != null) shotName else null,
        )
        mdFile.writeText(markdown, Charsets.UTF_8)

        // Public Downloads copy for adb pull → project feedback/
        if (bitmap != null) {
            writeToDownloads(activity, shotName, "image/png") { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
        }
        writeToDownloads(activity, mdName, "text/markdown") { out ->
            out.write(markdown.toByteArray(Charsets.UTF_8))
        }

        // Also keep a rolling LATEST.md for quick pull
        File(dir, "LATEST.md").writeText(markdown, Charsets.UTF_8)
        writeToDownloads(activity, "LATEST.md", "text/markdown") { out ->
            out.write(markdown.toByteArray(Charsets.UTF_8))
        }

        copyToClipboard(activity, markdown)

        return FeedbackResult(
            stamp = stamp,
            markdown = markdown,
            markdownFile = mdFile,
            screenshotFile = if (bitmap != null) shotFile else null,
            downloadsRelativePath = "Download/$DOWNLOADS_FOLDER/",
        )
    }

    fun feedbackDir(context: Context): File {
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        return File(base, "feedback").also { it.mkdirs() }
    }

    private fun captureScreenshot(activity: Activity): Bitmap? {
        val root = activity.window?.decorView?.rootView ?: return null
        if (root.width <= 0 || root.height <= 0) return null
        return try {
            val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            root.draw(canvas)
            bitmap
        } catch (_: Throwable) {
            null
        }
    }

    private fun buildContext(activity: Activity, note: String): String {
        val lines = mutableListOf<String>()
        lines += "- **Time:** ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(Date())}"
        lines += "- **App:** ${BuildConfig.APPLICATION_ID} ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
        lines += "- **Activity:** ${activity.javaClass.name}"
        lines += "- **Title:** ${activity.title ?: "(none)"}"

        if (activity is AppCompatActivity) {
            val subtitle = activity.supportActionBar?.subtitle
            if (!subtitle.isNullOrBlank()) lines += "- **Subtitle:** $subtitle"
        }

        if (activity is FragmentActivity) {
            val fm = activity.supportFragmentManager
            val visible = fm.fragments.filter { it.isVisible && it.isAdded }
            if (visible.isNotEmpty()) {
                lines += "- **Visible fragments:**"
                visible.forEach { frag ->
                    lines += "  - ${describeFragment(frag)}"
                }
            }
            val back = fm.backStackEntryCount
            if (back > 0) {
                lines += "- **Back stack ($back):**"
                for (i in 0 until back) {
                    val e = fm.getBackStackEntryAt(i)
                    lines += "  - ${e.name ?: e.id}"
                }
            }
        }

        val intent = activity.intent
        if (intent != null) {
            lines += "- **Intent action:** ${intent.action ?: "(none)"}"
            val extras = intent.extras
            if (extras != null && !extras.isEmpty) {
                lines += "- **Intent extras:**"
                for (key in extras.keySet()) {
                    val value = runCatching { extras.get(key) }.getOrNull()
                    lines += "  - `$key` = ${summarizeValue(value)}"
                }
            }
        }

        val focus = activity.currentFocus
        if (focus != null) {
            lines += "- **Focused view:** ${describeView(focus)}"
        }

        val selected = findSelectedViews(activity.window?.decorView)
        if (selected.isNotEmpty()) {
            lines += "- **Selected / checked views:**"
            selected.take(20).forEach { lines += "  - ${describeView(it)}" }
        }

        if (note.isNotBlank()) {
            lines += "- **User note:** (see below)"
        }
        return lines.joinToString("\n")
    }

    private fun describeFragment(frag: Fragment): String {
        val tag = frag.tag?.let { " tag=$it" } ?: ""
        val args = frag.arguments
        val argSummary = if (args != null && !args.isEmpty) {
            val keys = args.keySet().joinToString(", ")
            " args=[$keys]"
        } else {
            ""
        }
        return "${frag.javaClass.simpleName}$tag$argSummary"
    }

    private fun describeView(view: View): String {
        val id = try {
            if (view.id != View.NO_ID) view.resources.getResourceEntryName(view.id) else "no-id"
        } catch (_: Exception) {
            "0x${Integer.toHexString(view.id)}"
        }
        val text = (view as? TextView)?.text?.toString()?.take(80)
        val desc = view.contentDescription?.toString()?.take(80)
        val bits = mutableListOf(view.javaClass.simpleName, "id=$id")
        if (!text.isNullOrBlank()) bits += "text=\"$text\""
        if (!desc.isNullOrBlank()) bits += "desc=\"$desc\""
        if (view.isSelected) bits += "selected"
        if (view is android.widget.Checkable && view.isChecked) bits += "checked"
        return bits.joinToString(" · ")
    }

    private fun findSelectedViews(root: View?): List<View> {
        if (root == null) return emptyList()
        val out = mutableListOf<View>()
        fun walk(v: View) {
            if (v.isSelected || (v is android.widget.Checkable && v.isChecked && v !is android.widget.CheckBox)) {
                // Include selected tiles; skip ordinary settings checkboxes noise somewhat —
                // still include CheckBox if selected for context
                out.add(v)
            }
            if (v is android.widget.Checkable && v.isChecked) {
                if (v !in out) out.add(v)
            }
            if (v is ViewGroup) {
                for (i in 0 until v.childCount) walk(v.getChildAt(i))
            }
        }
        walk(root)
        return out
    }

    private fun summarizeValue(value: Any?): String {
        if (value == null) return "null"
        val s = value.toString()
        return if (s.length > 120) s.take(117) + "…" else s
    }

    private fun buildMarkdown(
        stamp: String,
        note: String,
        contextBlock: String,
        screenshotName: String?,
    ): String {
        val sb = StringBuilder()
        sb.appendLine("# Cupcake Comics feedback — $stamp")
        sb.appendLine()
        sb.appendLine("> Paste this file (and the PNG if present) into Cursor when reporting a bug or asking for a change.")
        sb.appendLine()
        sb.appendLine("## Context")
        sb.appendLine()
        sb.appendLine(contextBlock)
        sb.appendLine()
        if (note.isNotBlank()) {
            sb.appendLine("## Notes")
            sb.appendLine()
            sb.appendLine(note.trim())
            sb.appendLine()
        }
        if (screenshotName != null) {
            sb.appendLine("## Screenshot")
            sb.appendLine()
            sb.appendLine("![screenshot]($screenshotName)")
            sb.appendLine()
            sb.appendLine("_File: `$screenshotName`_")
            sb.appendLine()
        }
        sb.appendLine("## Pull into project")
        sb.appendLine()
        sb.appendLine("```bat")
        sb.appendLine("adb pull /sdcard/Download/$DOWNLOADS_FOLDER/ .\\feedback\\")
        sb.appendLine("```")
        sb.appendLine()
        return sb.toString()
    }

    private fun copyToClipboard(context: Context, text: String) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        cm.setPrimaryClip(ClipData.newPlainText("Cupcake feedback", text))
    }

    private fun writeToDownloads(
        context: Context,
        displayName: String,
        mime: String,
        writer: (java.io.OutputStream) -> Unit,
    ) {
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, displayName)
                    put(MediaStore.Downloads.MIME_TYPE, mime)
                    put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$DOWNLOADS_FOLDER")
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val resolver = context.contentResolver
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return
                resolver.openOutputStream(uri)?.use(writer)
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            } else {
                @Suppress("DEPRECATION")
                val dir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    DOWNLOADS_FOLDER,
                ).also { it.mkdirs() }
                FileOutputStream(File(dir, displayName)).use(writer)
            }
        } catch (_: Throwable) {
            // Best-effort public copy
        }
    }
}
