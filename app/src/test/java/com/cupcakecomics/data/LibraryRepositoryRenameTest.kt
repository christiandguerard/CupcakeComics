package com.cupcakecomics.data

import com.cupcakecomics.reader.settings.ReaderSettingsStore
import com.nkanaev.comics.managers.Utils
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

/**
 * Rename moves the file in place and migrates every piece of path-keyed state:
 * cover cache, last-page progress, read marks, and book reminders.
 */
@RunWith(RobolectricTestRunner::class)
class LibraryRepositoryRenameTest {
    private lateinit var db: CupcakeDatabase
    private lateinit var repo: LibraryRepository
    private lateinit var settingsStore: ReaderSettingsStore

    @Before
    fun setUp() {
        val app = RuntimeEnvironment.getApplication()
        // The repository is hard-wired to the singleton; reset it so every test
        // starts with an empty database.
        CupcakeDatabase.resetForTesting()
        db = CupcakeDatabase.get(app)
        repo = LibraryRepository(app)
        settingsStore = ReaderSettingsStore(app)
    }

    @After
    fun tearDown() {
        CupcakeDatabase.resetForTesting()
    }

    @Test
    fun `rename local file moves file and path-keyed state`() = runBlocking {
        val file = createBookFile("local", "Old Name.cbz")
        val id = db.localFileDao().insert(
            LocalFileEntity(
                title = "Old Name.cbz",
                localPath = file.absolutePath,
                sourceKey = "local:content://pick/1",
                contentUri = "content://pick/1",
                sizeBytes = file.length(),
            ),
        )
        // Simulate cached cover, reading progress, a read mark, and a reminder.
        Utils.getCoverCacheFileForPath(file.absolutePath).writeBytes(byteArrayOf(1, 2, 3))
        settingsStore.setLastPage("file:${file.absolutePath}", 7)
        db.readMarkDao().upsert(
            ReadMarkEntity(
                identityKey = "local:content://pick/1",
                displayName = "Old Name.cbz",
                sourceType = "local",
                sourceDetail = file.absolutePath,
                markedReadAt = 1L,
            ),
        )
        db.reminderDao().upsert(
            ReminderEntity(
                type = ReminderType.BOOK,
                bookSource = ReminderBookSource.LOCAL,
                title = "Old Name.cbz",
                identityKey = "local:content://pick/1",
                localPath = file.absolutePath,
            ),
        )

        val newName = repo.renameLocalFile(id, "New Name")

        assertEquals("New Name.cbz", newName)
        assertFalse(file.exists())
        val renamed = File(file.parentFile, "New Name.cbz")
        assertTrue(renamed.isFile)

        val entity = db.localFileDao().getById(id)!!
        assertEquals("New Name.cbz", entity.title)
        assertEquals(renamed.absolutePath, entity.localPath)

        // Cover cache followed the path-keyed rename.
        assertFalse(Utils.getCoverCacheFileForPath(file.absolutePath).exists())
        assertTrue(Utils.getCoverCacheFileForPath(renamed.absolutePath).isFile)
        // Last-page progress migrated to the new path key.
        assertEquals(7, settingsStore.getLastPage("file:${renamed.absolutePath}"))
        assertEquals(0, settingsStore.getLastPage("file:${file.absolutePath}"))
        // Read mark display updated in place (identity key is stable).
        val mark = db.readMarkDao().getAll().single()
        assertEquals("New Name.cbz", mark.displayName)
        assertEquals(renamed.absolutePath, mark.sourceDetail)
        // Reminder points at the renamed file with the new title.
        val reminder = db.reminderDao().getAll().single()
        assertEquals(renamed.absolutePath, reminder.localPath)
        assertEquals("New Name.cbz", reminder.title)
    }

    @Test
    fun `rename keeps the original extension even when another is typed`() = runBlocking {
        val file = createBookFile("local", "Book.cbz")
        val id = db.localFileDao().insert(
            LocalFileEntity(
                title = "Book.cbz",
                localPath = file.absolutePath,
                sourceKey = "local:content://pick/2",
            ),
        )
        val newName = repo.renameLocalFile(id, "Better Book.pdf")
        assertEquals("Better Book.cbz", newName)
        assertTrue(File(file.parentFile, "Better Book.cbz").isFile)
    }

    @Test
    fun `rename rejects collisions and blank names`() = runBlocking {
        val first = createBookFile("local", "First.cbz")
        val second = createBookFile("local", "Second.cbz")
        val id = db.localFileDao().insert(
            LocalFileEntity(
                title = "First.cbz",
                localPath = first.absolutePath,
                sourceKey = "local:content://pick/3",
            ),
        )
        assertThrows(IllegalStateException::class.java) {
            runBlocking { repo.renameLocalFile(id, "Second") }
        }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { repo.renameLocalFile(id, "   ") }
        }
        // Nothing moved.
        assertTrue(first.isFile)
        assertTrue(second.isFile)
        assertEquals("First.cbz", db.localFileDao().getById(id)!!.title)
    }

    @Test
    fun `rename offline comic updates row and reminder title`() = runBlocking {
        val file = createBookFile("offline", "Saga 001.cbz")
        val id = db.offlineComicDao().insert(
            OfflineComicEntity(
                title = "Saga 001.cbz",
                localPath = file.absolutePath,
                sourceKey = "smb:1:/Saga/Saga 001.cbz",
                sizeBytes = file.length(),
            ),
        )
        db.reminderDao().upsert(
            ReminderEntity(
                type = ReminderType.BOOK,
                bookSource = ReminderBookSource.PULL,
                title = "Saga 001.cbz",
                identityKey = "smb:1:/Saga/Saga 001.cbz",
            ),
        )

        val newName = repo.renameOfflineComic(id, "Saga - #1")

        assertEquals("Saga - #1.cbz", newName)
        val entity = db.offlineComicDao().getById(id)!!
        assertEquals("Saga - #1.cbz", entity.title)
        assertTrue(File(entity.localPath).isFile)
        // SMB identity key is untouched; the reminder title follows the rename.
        assertEquals("smb:1:/Saga/Saga 001.cbz", entity.sourceKey)
        assertEquals("Saga - #1.cbz", db.reminderDao().getAll().single().title)
    }

    private fun createBookFile(kind: String, name: String): File {
        val dir = File(RuntimeEnvironment.getApplication().filesDir, "rename-test/$kind")
            .also { it.mkdirs() }
        return File(dir, name).apply { writeBytes(byteArrayOf(9, 9, 9)) }
    }
}
