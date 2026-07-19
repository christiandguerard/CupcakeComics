package com.cupcakecomics.reminders

import android.content.Context
import com.cupcakecomics.data.CupcakeDatabase
import com.cupcakecomics.data.ReminderEntity
import com.cupcakecomics.data.ReminderFrequency
import com.cupcakecomics.data.ReminderPageMode
import com.cupcakecomics.data.ReminderType
import com.cupcakecomics.settings.CupcakeSettings
import kotlinx.coroutines.flow.Flow

class ReminderRepository(context: Context) {
    private val app = context.applicationContext
    private val db = CupcakeDatabase.get(app)
    private val dao = db.reminderDao()
    private val pullDao = db.pullComicDao()
    private val settings = CupcakeSettings(app)

    fun observeAll(): Flow<List<ReminderEntity>> = dao.observeAll()

    suspend fun getAll(): List<ReminderEntity> = dao.getAll()

    suspend fun getById(id: Long): ReminderEntity? = dao.getById(id)

    suspend fun getDue(nowMillis: Long = System.currentTimeMillis()): List<ReminderEntity> =
        dao.getDue(nowMillis)

    suspend fun unreadPullListCount(): Int = pullDao.getPullList().size

    suspend fun save(entity: ReminderEntity): Long {
        val withSchedule = entity.copy(
            nextFireAt = if (entity.enabled) computeNextFor(entity) else 0L,
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
            nextFireAt = if (enabled) computeNextFor(existing) else 0L,
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
            nextFireAt = if (!disabled && entity.enabled) {
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

    suspend fun incrementPageADay(id: Long) {
        val existing = dao.getById(id) ?: return
        dao.update(existing.copy(pageADayIndex = existing.pageADayIndex + 1))
    }

    suspend fun updateTrackedPageForLocalPath(localPath: String, page: Int) {
        if (page <= 0) return
        dao.updateTrackedPageForLocalPath(localPath, page)
    }

    suspend fun updateTrackedPageForIdentity(identityKey: String, page: Int) {
        if (page <= 0 || identityKey.isBlank()) return
        dao.updateTrackedPageForIdentity(identityKey, page)
    }

    suspend fun resolveResumePage(entity: ReminderEntity): Int {
        return when (entity.bookSource) {
            com.cupcakecomics.data.ReminderBookSource.LIBRARY -> {
                if (entity.libraryComicId <= 0) 1
                else {
                    val comic = com.nkanaev.comics.model.Storage.getStorage(app)
                        .getComic(entity.libraryComicId)
                    (comic?.currentPage ?: 1).coerceAtLeast(1)
                }
            }
            com.cupcakecomics.data.ReminderBookSource.PULL -> {
                val key = entity.identityKey ?: return 1
                val pull = pullDao.getByKey(key)
                (pull?.highestPage?.takeIf { it > 0 } ?: 1)
            }
            com.cupcakecomics.data.ReminderBookSource.LOCAL ->
                entity.trackedPage.coerceAtLeast(1)
            null -> 1
        }
    }

    suspend fun resolvePageForFire(entity: ReminderEntity): Int {
        if (entity.type != ReminderType.BOOK) return 1
        return when (entity.pageMode) {
            ReminderPageMode.PAGE_A_DAY -> entity.pageADayIndex.coerceAtLeast(1)
            ReminderPageMode.RESUME -> resolveResumePage(entity)
        }
    }

    suspend fun isBookFinished(entity: ReminderEntity): Boolean {
        if (entity.type != ReminderType.BOOK || entity.pageMode != ReminderPageMode.PAGE_A_DAY) {
            return false
        }
        val pageCount = when (entity.bookSource) {
            com.cupcakecomics.data.ReminderBookSource.LIBRARY -> {
                if (entity.libraryComicId <= 0) return false
                com.nkanaev.comics.model.Storage.getStorage(app)
                    .getComic(entity.libraryComicId)?.totalPages ?: return false
            }
            com.cupcakecomics.data.ReminderBookSource.PULL -> {
                val key = entity.identityKey ?: return false
                pullDao.getByKey(key)?.pageCount?.takeIf { it > 0 } ?: return false
            }
            com.cupcakecomics.data.ReminderBookSource.LOCAL -> return false
            null -> return false
        }
        return entity.pageADayIndex > pageCount
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
            pageMode = ReminderPageMode.RESUME,
        )
    }
}
