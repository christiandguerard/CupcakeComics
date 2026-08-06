package com.cupcakecomics.feedback

import android.os.Looper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import java.io.File
import java.nio.file.Files

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

    // ── Backfill helpers ────────────────────────────────────────────

    @Test
    fun `stampFromFileName only accepts report markdown names`() {
        assertEquals("20260801_153000", FeedbackUploader.stampFromFileName("feedback_20260801_153000.md"))
        assertNull(FeedbackUploader.stampFromFileName("feedback_20260801_153000.png"))
        assertNull(FeedbackUploader.stampFromFileName("LATEST.md"))
        assertNull(FeedbackUploader.stampFromFileName("LATEST (3).md"))
        assertNull(FeedbackUploader.stampFromFileName("feedback_submitted.json"))
        assertNull(FeedbackUploader.stampFromFileName("feedback_2026080_153000.md"))
        assertNull(FeedbackUploader.stampFromFileName("feedback_20260801_153000.md.bak"))
    }

    @Test
    fun `extractStamps finds the capture stamp in generated markdown`() {
        val md = """
            # Cupcake Comics feedback — 20260717_192055

            ## Context

            - **Time:** 2026-07-17 19:20:55 -0400

            ## Notes

            Small lag when entering the storage browser

            ## Screenshot

            ![screenshot](feedback_20260717_192055.png)

            _File: `feedback_20260717_192055.png`_
        """.trimIndent()
        assertEquals(listOf("20260717_192055"), FeedbackUploader.extractStamps(md))
    }

    @Test
    fun `extractStamps matches the fallback issue title and ignores human dates`() {
        assertEquals(listOf("20260717_192055"), FeedbackUploader.extractStamps("Feedback: 20260717_192055"))
        assertEquals(emptyList<String>(), FeedbackUploader.extractStamps("No stamp here. Time: 2026-07-17 19:20:55 -0400"))
    }

    @Test
    fun `findPendingReports pairs screenshots and sorts chronologically`() {
        val dir = Files.createTempDirectory("feedback").toFile()
        try {
            File(dir, "feedback_20260802_101500.md").writeText("newer")
            File(dir, "feedback_20260801_101500.md").writeText("older")
            File(dir, "feedback_20260801_101500.png").writeText("shot")
            File(dir, "LATEST.md").writeText("latest")
            File(dir, "LATEST (2).md").writeText("latest dupe")
            File(dir, "feedback_submitted.json").writeText("{}")

            val reports = FeedbackUploader.findPendingReports(dir)

            assertEquals(listOf("20260801_101500", "20260802_101500"), reports.map { it.stamp })
            assertEquals(File(dir, "feedback_20260801_101500.png"), reports[0].pngFile)
            assertNull(reports[1].pngFile)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `planBackfill skips tracked, adopts remote, uploads the rest`() {
        val dir = Files.createTempDirectory("feedback").toFile()
        try {
            fun report(stamp: String): FeedbackUploader.PendingReport {
                val md = File(dir, "feedback_$stamp.md").apply { writeText("# report") }
                return FeedbackUploader.PendingReport(stamp, md, null)
            }
            val tracked = report("20260801_000001")
            val onGithub = report("20260801_000002")
            val missed = report("20260801_000003")
            val remote = mapOf(
                "20260801_000002" to FeedbackUploader.RemoteIssue(7, "closed", "Lag", "https://x/7"),
            )

            val plan = FeedbackUploader.planBackfill(
                local = listOf(tracked, onGithub, missed),
                sidecarStamps = setOf("20260801_000001"),
                remote = remote,
            )

            assertEquals(listOf("20260801_000003"), plan.toUpload.map { it.stamp })
            assertEquals(listOf("20260801_000002"), plan.toAdopt.map { it.first.stamp })
            assertEquals(7, plan.toAdopt.single().second.number)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `backfillMissedReports reports zero without touching the network when not configured`() {
        var outcome: FeedbackUploader.BackfillResult? = null
        FeedbackUploader.backfillMissedReports(RuntimeEnvironment.getApplication()) { outcome = it }
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(FeedbackUploader.BackfillResult(0, 0, 0), outcome)
    }
}
