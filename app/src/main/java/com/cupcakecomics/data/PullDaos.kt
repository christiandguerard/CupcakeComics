package com.cupcakecomics.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface MonitoredFolderDao {
    @Query("SELECT * FROM monitored_folders ORDER BY enrolledAt DESC")
    fun observeAll(): Flow<List<MonitoredFolderEntity>>

    @Query("SELECT * FROM monitored_folders ORDER BY enrolledAt DESC")
    suspend fun getAll(): List<MonitoredFolderEntity>

    @Query("SELECT * FROM monitored_folders WHERE id = :id")
    suspend fun getById(id: Long): MonitoredFolderEntity?

    @Query(
        """
        SELECT * FROM monitored_folders
        WHERE shareId = :shareId AND relativePath = :relativePath
        LIMIT 1
        """,
    )
    suspend fun find(shareId: Long, relativePath: String): MonitoredFolderEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: MonitoredFolderEntity): Long

    @Update
    suspend fun update(entity: MonitoredFolderEntity)

    @Query("DELETE FROM monitored_folders WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("UPDATE monitored_folders SET baselined = 1 WHERE id = :id")
    suspend fun markBaselined(id: Long)
}

@Dao
interface PullComicDao {
    @Query(
        """
        SELECT * FROM pull_comics
        WHERE inPullList = 1
        ORDER BY firstSeenAt DESC
        """,
    )
    fun observePullList(): Flow<List<PullComicEntity>>

    @Query("SELECT * FROM pull_comics WHERE inPullList = 1 ORDER BY firstSeenAt DESC")
    suspend fun getPullList(): List<PullComicEntity>

    @Query("SELECT * FROM pull_comics WHERE identityKey = :key LIMIT 1")
    suspend fun getByKey(key: String): PullComicEntity?

    @Query(
        "SELECT identityKey FROM pull_comics " +
            "WHERE shareId = :shareId AND (" +
            "relativePath = :folderPath " +
            "OR relativePath LIKE :childPrefix ESCAPE '\\' " +
            "OR (:isRoot != 0 AND relativePath != ''))",
    )
    suspend fun keysUnderFolder(
        shareId: Long,
        folderPath: String,
        childPrefix: String,
        isRoot: Int,
    ): List<String>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(entity: PullComicEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: PullComicEntity)

    @Update
    suspend fun update(entity: PullComicEntity)

    @Query(
        """
        UPDATE pull_comics
        SET inPullList = :inPullList, markedReadManually = :markedRead
        WHERE identityKey = :key
        """,
    )
    suspend fun setPullMembership(key: String, inPullList: Boolean, markedRead: Boolean)

    @Query(
        """
        UPDATE pull_comics
        SET highestPage = MAX(highestPage, :highestPage),
            pageCount = MAX(pageCount, :pageCount),
            inPullList = CASE
                WHEN MAX(pageCount, :pageCount) > 0
                     AND (1.0 * MAX(highestPage, :highestPage) / MAX(pageCount, :pageCount)) >= :leaveThreshold
                THEN 0
                ELSE inPullList
            END,
            markedReadManually = CASE
                WHEN MAX(pageCount, :pageCount) > 0
                     AND (1.0 * MAX(highestPage, :highestPage) / MAX(pageCount, :pageCount)) >= :leaveThreshold
                THEN 1
                ELSE markedReadManually
            END
        WHERE identityKey = :key
        """,
    )
    suspend fun updateProgress(
        key: String,
        highestPage: Int,
        pageCount: Int,
        leaveThreshold: Float,
    )

    @Query("UPDATE pull_comics SET missing = :missing WHERE identityKey IN (:keys)")
    suspend fun setMissing(keys: List<String>, missing: Boolean)

    @Query(
        "DELETE FROM pull_comics WHERE shareId = :shareId AND (" +
            "relativePath = :folderPath " +
            "OR relativePath LIKE :childPrefix ESCAPE '\\' " +
            "OR (:isRoot != 0))",
    )
    suspend fun deleteUnderFolder(
        shareId: Long,
        folderPath: String,
        childPrefix: String,
        isRoot: Int,
    )
}
