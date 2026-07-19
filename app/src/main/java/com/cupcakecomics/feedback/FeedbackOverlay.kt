package com.cupcakecomics.feedback

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.AppCompatEditText
import com.cupcakecomics.settings.CupcakeSettings
import com.nkanaev.comics.R
import java.lang.ref.WeakReference

/**
 * Shows a floating feedback button on every Activity while the debug setting is on.
 */
object FeedbackOverlay {
    private const val TAG = "cupcake_feedback_fab"
    private var callbacks: Application.ActivityLifecycleCallbacks? = null
    private var activeActivity: WeakReference<Activity>? = null

    @JvmStatic
    fun install(app: Application) {
        if (callbacks != null) return
        val cb = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                activeActivity = WeakReference(activity)
                sync(activity)
            }

            override fun onActivityPaused(activity: Activity) {
                // keep fab until destroy so rotation is smoother
            }

            override fun onActivityDestroyed(activity: Activity) {
                removeFab(activity)
                if (activeActivity?.get() === activity) activeActivity = null
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
        }
        callbacks = cb
        app.registerActivityLifecycleCallbacks(cb)
    }

    /** Call when the debug toggle changes so the current screen updates immediately. */
    @JvmStatic
    fun refresh(context: android.content.Context) {
        val activity = activeActivity?.get()
            ?: (context as? Activity)
            ?: return
        sync(activity)
    }

    private fun sync(activity: Activity) {
        val enabled = try {
            CupcakeSettings(activity).debugFeedbackEnabled
        } catch (_: Throwable) {
            false
        }
        if (enabled) attachFab(activity) else removeFab(activity)
    }

    private fun attachFab(activity: Activity) {
        val decor = activity.window?.decorView as? ViewGroup ?: return
        if (decor.findViewWithTag<View>(TAG) != null) return

        val button = ImageButton(activity).apply {
            tag = TAG
            setImageResource(android.R.drawable.ic_menu_edit)
            contentDescription = activity.getString(R.string.feedback_fab_cd)
            setBackgroundResource(R.drawable.feedback_fab_bg)
            elevation = 12f * resources.displayMetrics.density
            setOnClickListener { showFeedbackDialog(activity, this) }
        }

        val size = (52 * activity.resources.displayMetrics.density).toInt()
        val margin = (16 * activity.resources.displayMetrics.density).toInt()
        val bottom = (72 * activity.resources.displayMetrics.density).toInt()
        val lp = FrameLayout.LayoutParams(size, size).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            setMargins(margin, margin, margin, bottom)
        }
        decor.addView(button, lp)
    }

    private fun removeFab(activity: Activity) {
        val decor = activity.window?.decorView as? ViewGroup ?: return
        val existing = decor.findViewWithTag<View>(TAG) ?: return
        decor.removeView(existing)
    }

    private fun showFeedbackDialog(activity: Activity, fab: View) {
        val pad = (20 * activity.resources.displayMetrics.density).toInt()
        val input = AppCompatEditText(activity).apply {
            hint = activity.getString(R.string.feedback_note_hint)
            minLines = 3
            maxLines = 8
            setPadding(pad, pad, pad, pad)
        }

        AlertDialog.Builder(activity)
            .setTitle(R.string.feedback_dialog_title)
            .setMessage(R.string.feedback_dialog_message)
            .setView(input)
            .setPositiveButton(R.string.feedback_submit) { _, _ ->
                val note = input.text?.toString().orEmpty()
                try {
                    val result = FeedbackCapture.capture(activity, note, hideViews = listOf(fab))
                    Toast.makeText(
                        activity,
                        activity.getString(
                            R.string.feedback_saved_toast,
                            result.downloadsRelativePath,
                        ),
                        Toast.LENGTH_LONG,
                    ).show()
                } catch (t: Throwable) {
                    Toast.makeText(
                        activity,
                        activity.getString(R.string.feedback_failed_toast, t.message ?: ""),
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
