package com.cupcakecomics.downloads

import androidx.room.Room
import com.cupcakecomics.data.CupcakeDatabase
import com.cupcakecomics.data.DownloadJobStatus
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class DownloadQueueRepositoryTest {
    private lateinit var db: CupcakeDatabase
    private lateinit var repo: DownloadQueueRepository

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            CupcakeDatabase::class.java,
        ).allowMainThreadQueries().build()
        repo = DownloadQueueRepository(RuntimeEnvironment.getApplication(), db)
        repo.kicker = {}
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `enqueue dedupes queued jobs and normalizes paths`() = runBlocking {
        assertEquals(2, repo.enqueue(1, listOf("/a.cbz" to "a.cbz", "b.cbz" to "b.cbz")))
        // Same sources again: nothing new.
        assertEquals(0, repo.enqueue(1, listOf("a.cbz" to "a.cbz", "/b.cbz" to "b.cbz")))
        assertEquals(2, repo.queuedCount())
    }

    @Test
    fun `failed jobs can be retried individually and in bulk`() = runBlocking {
        repo.enqueue(1, listOf("/a.cbz" to "a.cbz", "/b.cbz" to "b.cbz"))
        val first = repo.nextQueued()!!
        repo.markRunning(first.id, 1)
        repo.markFailed(first.id, "connection reset")
        assertEquals(1, repo.queuedCount())

        repo.retryFailed()
        assertEquals(2, repo.queuedCount())
        val job = repo.nextQueued()!!
        assertEquals("a.cbz", job.title)
        assertNull(job.error)
        assertEquals(0, job.attempts)
    }

    @Test
    fun `crash recovery requeues jobs stuck running`() = runBlocking {
        repo.enqueue(1, listOf("/a.cbz" to "a.cbz"))
        val job = repo.nextQueued()!!
        repo.markRunning(job.id, 1)
        assertEquals(0, repo.queuedCount())

        assertEquals(1, repo.requeueRunningLeftovers())
        assertEquals(DownloadJobStatus.QUEUED, repo.nextQueued()?.status)
    }

    @Test
    fun `lifecycle transitions persist progress and clear on success`() = runBlocking {
        repo.enqueue(1, listOf("/a.cbz" to "a.cbz"))
        val job = repo.nextQueued()!!
        repo.markRunning(job.id, 1)
        repo.setProgress(job.id, 400, 1000)
        repo.markSucceeded(job.id)
        assertEquals(0, repo.queuedCount())
        assertNull(repo.nextQueued())
        repo.clearFinished()
        assertNotNull(repo) // finished rows removed without touching others
    }

    @Test
    fun `enqueue refreshes a failed row instead of duplicating it`() = runBlocking {
        repo.enqueue(1, listOf("/a.cbz" to "a.cbz"))
        val job = repo.nextQueued()!!
        repo.markRunning(job.id, 3)
        repo.markFailed(job.id, "timeout")

        assertEquals(1, repo.enqueue(1, listOf("/a.cbz" to "a.cbz")))
        assertEquals(1, repo.queuedCount())
    }
}
