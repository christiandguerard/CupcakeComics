package com.cupcakecomics.feedback

import android.os.Looper
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class FeedbackUploaderTest {

    @Test
    fun `manual config wins over baked value`() {
        assertEquals("manual", FeedbackUploader.resolveConfigValue("manual", "baked"))
        assertEquals("manual", FeedbackUploader.resolveConfigValue(" manual ", "baked"))
    }

    @Test
    fun `baked value seeds when config is blank`() {
        assertEquals("baked", FeedbackUploader.resolveConfigValue("", "baked"))
        assertEquals("baked", FeedbackUploader.resolveConfigValue("   ", "baked"))
    }

    @Test
    fun `blank when neither is set`() {
        assertEquals("", FeedbackUploader.resolveConfigValue("", ""))
    }

    @Test
    fun `uploadReport reports not-configured without touching the network`() {
        // Unit-test BuildConfig has no baked token, and prefs start empty,
        // so the uploader must short-circuit with a failure result.
        var outcome: Boolean? = null
        val result = FeedbackResult(
            stamp = "20260801_000000",
            markdown = "note",
            markdownFile = null,
            screenshotFile = null,
            downloadsRelativePath = "CupcakeFeedback/feedback_20260801_000000.md",
        )
        FeedbackUploader.uploadReport(RuntimeEnvironment.getApplication(), result, "title") {
            outcome = it
        }
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(false, outcome)
    }
}
