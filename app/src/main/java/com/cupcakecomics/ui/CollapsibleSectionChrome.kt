package com.cupcakecomics.ui

import android.content.Context
import android.content.SharedPreferences
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import com.nkanaev.comics.R

/**
 * Shared expand/collapse chrome for Library home sections: header title
 * with item count, chevron state, and persistence of the expanded flag.
 * The section decides what to show or hide in [onChanged].
 */
class CollapsibleSectionChrome(
    private val context: Context,
    private val prefs: SharedPreferences,
    private val prefKey: String,
    defaultExpanded: Boolean,
    headerRow: View,
    private val header: TextView,
    private val chevron: ImageView,
    private val titleRes: Int,
    private val onChanged: () -> Unit,
) {
    var expanded = prefs.getBoolean(prefKey, defaultExpanded)
        private set

    init {
        headerRow.setOnClickListener { toggle() }
    }

    private fun toggle() {
        expanded = !expanded
        prefs.edit().putBoolean(prefKey, expanded).apply()
        onChanged()
    }

    /** Repaints the header title (with count) and chevron for the current state. */
    fun apply(count: Int) {
        chevron.setImageResource(
            if (expanded) R.drawable.ic_expand_less_24 else R.drawable.ic_expand_more_24,
        )
        header.text = if (count > 0) {
            context.getString(titleRes) + " ($count)"
        } else {
            context.getString(titleRes)
        }
    }
}
