package com.cupcakecomics.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Single write path for explicit read/unread actions. Keeps the two
 * read-status stores in sync:
 * - read_marks: exportable read history + green check badges
 * - pull_comics: Pull List membership (unread comics under monitored folders)
 *
 * Keys that have no pull_comics row (e.g. local files) are ignored on the
 * pull side, so callers can route every section through here.
 */
class ReadStatusRepository(context: Context) {
    private val db = CupcakeDatabase.get(context)

    suspend fun markRead(entries: List<ReadMarkEntity>) = withContext(Dispatchers.IO) {
        db.readMarkDao().upsertAll(entries)
        entries.forEach {
            db.pullComicDao().setPullMembership(it.identityKey, inPullList = false, markedRead = true)
        }
    }

    suspend fun markUnread(keys: List<String>) = withContext(Dispatchers.IO) {
        db.readMarkDao().deleteKeys(keys)
        keys.forEach { key ->
            db.pullComicDao().getByKey(key)?.let { row ->
                db.pullComicDao().upsert(
                    row.copy(inPullList = true, markedReadManually = false, missing = false),
                )
            }
        }
    }

    suspend fun markPullComicRead(item: PullComicEntity) = markRead(
        listOf(
            ReadMarkEntity(
                identityKey = item.identityKey,
                displayName = item.title,
                sourceType = "smb",
                sourceDetail = item.relativePath,
                markedReadAt = System.currentTimeMillis(),
            ),
        ),
    )

    suspend fun markPullComicUnread(item: PullComicEntity) = markUnread(listOf(item.identityKey))
}
