package com.cupcakecomics.reader

import android.content.Context
import android.content.Intent
import com.nkanaev.comics.activity.ReaderActivity
import java.io.File

/**
 * Single entry point for opening a comic in the reader. Callers describe
 * what to open; the launcher owns the ReaderActivity extra contract so no
 * UI code touches fragment arguments directly.
 *
 * For pending-intent builders (reminders, notifications) use the *Intent
 * variants and add any extras of your own before handing the intent over.
 */
object ReaderLauncher {

    @JvmStatic
    fun openFile(context: Context, file: File, identityKey: String? = null, initialPage: Int = 0) {
        context.startActivity(fileIntent(context, file, identityKey, initialPage))
    }

    @JvmStatic
    fun fileIntent(context: Context, file: File, identityKey: String? = null, initialPage: Int = 0): Intent =
        base(context, identityKey, initialPage).apply {
            putExtra(CupcakeReaderFragment.PARAM_MODE, CupcakeReaderFragment.MODE_FILE)
            putExtra(CupcakeReaderFragment.PARAM_HANDLER, file)
        }

    @JvmStatic
    fun openLibraryComic(context: Context, comicId: Int, identityKey: String? = null, initialPage: Int = 0) {
        context.startActivity(libraryComicIntent(context, comicId, identityKey, initialPage))
    }

    @JvmStatic
    fun libraryComicIntent(
        context: Context,
        comicId: Int,
        identityKey: String? = null,
        initialPage: Int = 0,
    ): Intent =
        base(context, identityKey, initialPage).apply {
            putExtra(CupcakeReaderFragment.PARAM_MODE, CupcakeReaderFragment.MODE_LIBRARY)
            putExtra(CupcakeReaderFragment.PARAM_HANDLER, comicId)
        }

    /**
     * Stream an SMB comic in the reader (range requests for CBZ; staged
     * progress otherwise). The display name rides in the handler slot only
     * as a fallback title — the SMB extras take precedence when opening.
     */
    @JvmStatic
    fun openSmb(
        context: Context,
        shareId: Long,
        relativePath: String,
        displayName: String,
        identityKey: String? = null,
        initialPage: Int = 0,
    ) {
        context.startActivity(smbIntent(context, shareId, relativePath, displayName, identityKey, initialPage))
    }

    @JvmStatic
    fun smbIntent(
        context: Context,
        shareId: Long,
        relativePath: String,
        displayName: String,
        identityKey: String? = null,
        initialPage: Int = 0,
    ): Intent =
        base(context, identityKey, initialPage).apply {
            putExtra(CupcakeReaderFragment.PARAM_MODE, CupcakeReaderFragment.MODE_FILE)
            putExtra(CupcakeReaderFragment.PARAM_HANDLER, File(displayName))
            putExtra(CupcakeReaderFragment.PARAM_SMB_SHARE_ID, shareId)
            putExtra(CupcakeReaderFragment.PARAM_SMB_RELATIVE_PATH, relativePath)
        }

    /** Open an external ACTION_VIEW intent (content:// URIs, other apps). */
    @JvmStatic
    fun openViewIntent(context: Context, viewIntent: Intent, identityKey: String? = null, initialPage: Int = 0) {
        context.startActivity(viewIntentIntent(context, viewIntent, identityKey, initialPage))
    }

    @JvmStatic
    fun viewIntentIntent(
        context: Context,
        viewIntent: Intent,
        identityKey: String? = null,
        initialPage: Int = 0,
    ): Intent =
        base(context, identityKey, initialPage).apply {
            putExtra(CupcakeReaderFragment.PARAM_MODE, CupcakeReaderFragment.MODE_INTENT)
            putExtra(CupcakeReaderFragment.PARAM_HANDLER, viewIntent)
        }

    private fun base(context: Context, identityKey: String?, initialPage: Int): Intent =
        Intent(context, ReaderActivity::class.java).apply {
            identityKey?.let { putExtra(CupcakeReaderFragment.PARAM_IDENTITY_KEY, it) }
            if (initialPage > 0) putExtra(CupcakeReaderFragment.PARAM_PAGE, initialPage)
        }
}
