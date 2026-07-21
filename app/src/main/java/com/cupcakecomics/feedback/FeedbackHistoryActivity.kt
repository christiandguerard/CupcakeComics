package com.cupcakecomics.feedback

import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.cupcakecomics.settings.CupcakeSettings
import com.nkanaev.comics.R

/**
 * Shows all submitted feedback reports and their status (pending / submitted / addressed).
 *
 * Opens feedback reports from the device, shows GitHub issue links,
 * and lets the user sync status from GitHub.
 */
class FeedbackHistoryActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.AppThemeDayNight)
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_feedback_history)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.feedback_history_title)

        findViewById<Button>(R.id.feedback_history_sync).setOnClickListener {
            Toast.makeText(this, R.string.feedback_history_sync, Toast.LENGTH_SHORT).show()
            refreshList()
        }

        refreshList()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun refreshList() {
        FeedbackUploader.syncStatus(this)
        val subs = FeedbackUploader.getSubmissions(this)
        val container = findViewById<LinearLayout>(R.id.feedback_history_list)
        container.removeAllViews()

        if (subs.isEmpty()) {
            val empty = TextView(this).apply {
                text = getString(R.string.feedback_history_empty)
                setPadding(32, 32, 32, 32)
                textSize = 16f
            }
            container.addView(empty)
            return
        }

        subs.forEach { sub ->
            val statusText = when (sub.state) {
                "closed" -> getString(R.string.feedback_history_addressed)
                "open" -> getString(R.string.feedback_history_submitted)
                else -> getString(R.string.feedback_history_pending)
            }

            val row = layoutInflater.inflate(R.layout.item_feedback_history_row, container, false)
            row.findViewById<TextView>(R.id.feedback_history_row_title).text = sub.title
            row.findViewById<TextView>(R.id.feedback_history_row_status).text = statusText
            row.findViewById<TextView>(R.id.feedback_history_row_issue).text = "#${sub.issueNumber}"

            row.setOnClickListener {
                if (sub.url.isNotBlank()) {
                    try {
                        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(sub.url))
                        startActivity(intent)
                    } catch (_: Exception) {
                        // Fall back to showing issue number
                    }
                }
            }

            container.addView(row)
        }
    }
}
