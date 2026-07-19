package com.cupcakecomics.notifications

import android.Manifest
import android.app.Activity
import android.os.Build
import androidx.appcompat.app.AlertDialog
import androidx.core.app.ActivityCompat
import com.cupcakecomics.data.CupcakeDatabase
import com.cupcakecomics.settings.CupcakeSettings
import com.nkanaev.comics.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * One-time prompt after the user has monitored folders and notifications are desired
 * but system permission is not granted yet.
 */
object NotifyPermissionPrompt {
    private const val REQUEST_CODE = 4401

    @JvmStatic
    fun maybeShow(activity: Activity) {
        if (activity.isFinishing) return
        val settings = CupcakeSettings(activity)
        if (!settings.pullListNotify) return
        if (settings.notifyPermissionPromptShown) return
        if (CupcakeNotifications.areNotificationsAllowed(activity)) {
            settings.notifyPermissionPromptShown = true
            return
        }

        CoroutineScope(Dispatchers.Main.immediate).launch {
            val folderCount = withContext(Dispatchers.IO) {
                CupcakeDatabase.get(activity).monitoredFolderDao().getAll().size
            }
            if (folderCount <= 0 || activity.isFinishing) return@launch
            if (settings.notifyPermissionPromptShown) return@launch

            settings.notifyPermissionPromptShown = true
            AlertDialog.Builder(activity)
                .setTitle(R.string.notify_prompt_title)
                .setMessage(R.string.notify_prompt_message)
                .setPositiveButton(R.string.notify_prompt_allow) { _, _ ->
                    requestPermission(activity)
                }
                .setNegativeButton(R.string.notify_prompt_not_now, null)
                .setNeutralButton(R.string.settings_notify_system) { _, _ ->
                    // Open system settings via same path as Settings screen.
                    val intent = android.content.Intent().apply {
                        if (Build.VERSION.SDK_INT >= 26) {
                            action = android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS
                            putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, activity.packageName)
                        } else {
                            action = android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS
                            data = android.net.Uri.fromParts("package", activity.packageName, null)
                        }
                    }
                    activity.startActivity(intent)
                }
                .show()
        }
    }

    @JvmStatic
    fun requestPermission(activity: Activity) {
        if (Build.VERSION.SDK_INT < 33) return
        if (CupcakeNotifications.areNotificationsAllowed(activity)) return
        ActivityCompat.requestPermissions(
            activity,
            arrayOf(Manifest.permission.POST_NOTIFICATIONS),
            REQUEST_CODE,
        )
    }
}
