package com.cupcakecomics.reminders

import android.content.Context
import com.cupcakecomics.data.CupcakeDatabase
import com.cupcakecomics.data.ReminderEntity
import com.cupcakecomics.data.ReminderFrequency
import com.cupcakecomics.data.ReminderType
import com.cupcakecomics.reader.settings.ReaderSettingsStore
import com.cupcakecomics.settings.CupcakeSettings
import kotlinx.coroutines.flow.Flow

class ReminderRepository(context: Context) {
    private val app = context.applicationContext
    private val db = CupcakeDatabase.get(app)
    private val dao = db.reminderDao()
    private val pullDao = db.pullComicDao()
    private val settings = CupcakeSettings(app)
    private val readerSettings = ReaderSettingsStore(app)
    private val goalTracker = GoalProgressTracker(app)

    fun observeAll(): Flow<List<ReminderEntity>> = dao.observeAll()

    suspend fun getAll(): List<ReminderEntity> = dao.getAll()

    suspend fun getById(id: Long): ReminderEntity? = dao.getById(id)

    suspend fun getDue(nowMillis: Long = System.currentTimeMillis()): List<ReminderEntity> =
        dao.getDue(nowMillis)

    suspend fun unreadPullListCount(): Int = pullDao.getPullList().size

    suspend fun save(entity: ReminderEntity): Long {
        // Cache the page count at save time when the source knows it cheaply, so
        // finish detection and "pages left in book" work before the next read.
        val withPages = if (entity.type == ReminderType.BOOK && entity.totalPages <= 0) {
            entity.copy(totalPages = resolveTotalPages(entity))
        } else {
            entity
        }
        val withSchedule = withPages.copy(
            nextFireAt = if (withPages.enabled && withPages.effectiveNotify()) {
                computeNextFor(withPages)
            } else {
                0L
            },
        )
        val rowId = dao.upsert(withSchedule)
        ReminderScheduler.schedule(app)
        return if (withSchedule.id != 0L) withSchedule.id else rowId
    }

    suspend fun delete(id: Long) {
        dao.deleteById(id)
        ReminderScheduler.schedule(app)
    }

    suspend fun setEnabled(id: Long, enabled: Boolean) {
        val existing = dao.getById(id) ?: return
        val updated = existing.copy(
            enabled = enabled,
            nextFireAt = if (enabled && existing.effectiveNotify()) computeNextFor(existing) else 0L,
        )
        dao.update(updated)
        ReminderScheduler.schedule(app)
    }

    suspend fun refreshSchedule(id: Long) {
        val existing = dao.getById(id) ?: return
        if (!existing.enabled) return
        dao.update(existing.copy(nextFireAt = computeNextFor(existing)))
        ReminderScheduler.schedule(app)
    }

    suspend fun afterFired(entity: ReminderEntity, disabled: Boolean = false) {
        val now = System.currentTimeMillis()
        val updated = entity.copy(
            lastFiredAt = now,
            enabled = !disabled && entity.enabled,
            nextFireAt = if (!disabled && entity.enabled && entity.effectiveNotify()) {
                ReminderSchedule.computeNextFire(
                    afterMillis = now,
                    frequency = entity.frequency,
                    hourOfDay = entity.hourOfDay,
                    dayOfWeek = entity.dayOfWeek,
                    dayOfMonth = entity.dayOfMonth,
                    settings = settings,
                )
            } else {
                0L
            },
        )
        dao.update(updated)
    }

    suspend fun updateTrackedPageForLocalPath(localPath: String, page: Int) {
        if (page <= 0) return
        dao.updateTrackedPageForLocalPath(localPath, page)
    }

    suspend fun updateTrackedPageForIdentity(identityKey: String, page: Int) {
        if (page <= 0 || identityKey.isBlank()) return
        dao.updateTrackedPageForIdentity(identityKey, page)
    }

    suspend fun updateTotalPagesForLocalPath(localPath: String, totalPages: Int) {
        if (totalPages <= 0 || localPath.isBlank()) return
        dao.updateTotalPagesForLocalPath(localPath, totalPages)
    }

    suspend fun updateTotalPagesForIdentity(identityKey: String, totalPages: Int) {
        if (totalPages <= 0 || identityKey.isBlank()) return
        dao.updateTotalPagesForIdentity(identityKey, totalPages)
    }

    suspend fun pagesLeftInWindow(
        entity: ReminderEntity,
        nowMillis: Long = System.currentTimeMillis(),
    ): Int = goalTracker.pagesLeftInWindow(entity, nowMillis)

    suspend fun pagesReadInWindow(
        entity: ReminderEntity,
        nowMillis: Long = System.currentTimeMillis(),
    ): Int = goalTracker.pagesReadInWindow(entity, nowMillis)

    /**
     * Unified resume point: the furthest page any progress store has seen. The
     * reader's own open path maxes the same sources, so a notification tap never
     * lands behind where the user actually left off.
     */
    suspend fun resolveResumePage(entity: ReminderEntity): Int {
        val candidates = mutableListOf(entity.trackedPage)
        entity.identityKey?.takeIf { it.isNotBlank() }?.let {
            candidates += readerSettings.getLastPage(it)
        }
        entity.localPath?.takeIf { it.isNotBlank() }?.let {
            candidates += readerSettings.getLastPage("file:$it")
        }
        when (entity.bookSource) {
            com.cupcakecomics.data.ReminderBookSource.LIBRARY -> {
                if (entity.libraryComicId > 0) {
                    val comic = com.nkanaev.comics.model.Storage.getStorage(app)
                        .getComic(entity.libraryComicId)
                    candidates += comic?.currentPage ?: 0
                }
            }
            com.cupcakecomics.data.ReminderBookSource.PULL -> {
                entity.identityKey?.let { key ->
                    candidates += pullDao.getByKey(key)?.highestPage ?: 0
                }
            }
            else -> Unit
        }
        return (candidates.filter { it > 0 }.maxOrNull() ?: 1)
    }

    /** Fresh resume page for a notification tap, resolved at open time. */
    suspend fun resolveResumePageById(reminderId: Long): Int? =
        dao.getById(reminderId)?.let { resolveResumePage(it) }

    /** Page count for finish detection / "pages left", refreshing the cache when known. */
    suspend fun resolveTotalPages(entity: ReminderEntity): Int {
        if (entity.totalPages > 0) return entity.totalPages
        val count = when (entity.bookSource) {
            com.cupcakecomics.data.ReminderBookSource.LIBRARY -> {
                if (entity.libraryComicId <= 0) 0 else {
                    com.nkanaev.comics.model.Storage.getStorage(app)
                        .getComic(entity.libraryComicId)?.totalPages ?: 0
                }
            }
            com.cupcakecomics.data.ReminderBookSource.PULL ->
                entity.identityKey?.let { pullDao.getByKey(it)?.pageCount ?: 0 } ?: 0
            else -> 0
        }
        if (count > 0 && entity.id > 0) {
            dao.update(entity.copy(totalPages = count))
        }
        return count
    }

    /** Finished = resume page reached the final page. [resumePage] avoids a double lookup. */
    suspend fun isBookFinished(entity: ReminderEntity, resumePage: Int = resolveResumePage(entity)): Boolean {
        if (entity.type != ReminderType.BOOK) return false
        val total = resolveTotalPages(entity)
        return total > 0 && resumePage >= total
    }

    fun computeNextFor(entity: ReminderEntity): Long {
        val after = maxOf(entity.lastFiredAt, System.currentTimeMillis() - 60_000L)
        return ReminderSchedule.computeNextFire(
            afterMillis = after,
            frequency = entity.frequency,
            hourOfDay = entity.hourOfDay,
            dayOfWeek = entity.dayOfWeek,
            dayOfMonth = entity.dayOfMonth,
            settings = settings,
        )
    }

    companion object {
        fun defaultPullListReminder(): ReminderEntity = ReminderEntity(
            type = ReminderType.PULL_LIST,
            frequency = ReminderFrequency.WEEKLY,
            hourOfDay = 20,
        )

        fun defaultBookReminder(): ReminderEntity = ReminderEntity(
            type = ReminderType.BOOK,
            frequency = ReminderFrequency.DAILY,
            hourOfDay = 20,
            bookSource = com.cupcakecomics.data.ReminderBookSource.LIBRARY,
        )
    }
}
