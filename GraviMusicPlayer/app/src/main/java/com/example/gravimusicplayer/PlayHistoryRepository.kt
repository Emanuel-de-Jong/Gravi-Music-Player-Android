package com.example.gravimusicplayer

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class PlayHistorySongStats(
    val uriString: String,
    val isrc: String,
    val playCount: Long,
)

data class PlayHistoryQueueStats(
    val sourceType: String,
    val sourceName: String,
    val playOrderMode: String,
    val queueCount: Long,
    val playCount: Long,
)

data class PlayHistoryStats(
    val songs: List<PlayHistorySongStats>,
    val queues: List<PlayHistoryQueueStats>,
)

data class PlayHistoryQueue(
    val type: QueueType,
    val name: String,
    val order: QueueOrder,
    val createdAtMs: Long,
)

class PlayHistoryRepository(context: Context) {
    private val databaseHelper = PlayHistoryDatabase(context.applicationContext)

    fun recordQualifiedPlay(
        item: AudioItem,
        queue: PlayHistoryQueue?,
        queueId: Long?,
        startedAtMs: Long,
        listenedDurationMs: Long,
    ): Long? {
        if (listenedDurationMs <= 0) return queueId

        val database = databaseHelper.writableDatabase
        database.beginTransaction()
        try {
            val songId = ensureSong(database, item)
            val savedQueueId = queueId ?: queue?.let { createQueue(database, it) }
            database.compileStatement(
                """
                INSERT INTO play_history_plays(song_id, queue_id, started_at_ms, listened_duration_ms)
                VALUES (?, ?, ?, ?)
                """.trimIndent()
            ).apply {
                bindLong(1, songId)
                if (savedQueueId == null) bindNull(2) else bindLong(2, savedQueueId)
                bindLong(3, startedAtMs)
                bindLong(4, listenedDurationMs)
            }.executeInsert()
            database.setTransactionSuccessful()
            return savedQueueId
        } finally {
            database.endTransaction()
        }
    }

    fun songStats(): List<PlayHistorySongStats> {
        return databaseHelper.readableDatabase.rawQuery(
            """
            SELECT play_history_songs.uri_string,
                   play_history_songs.isrc,
                   COUNT(play_history_plays.id) AS play_count
            FROM play_history_plays
            INNER JOIN play_history_songs
                ON play_history_songs.id = play_history_plays.song_id
            GROUP BY play_history_songs.id
            HAVING play_count > 0
            ORDER BY play_count DESC, play_history_songs.uri_string COLLATE NOCASE
            """.trimIndent(),
            null,
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        PlayHistorySongStats(
                            uriString = cursor.getString(0),
                            isrc = cursor.getString(1),
                            playCount = cursor.getLong(2),
                        )
                    )
                }
            }
        }
    }

    fun queueStats(): List<PlayHistoryQueueStats> {
        return databaseHelper.readableDatabase.rawQuery(
            """
            SELECT source_type,
                   source_name,
                   play_order_mode,
                   COUNT(DISTINCT play_history_queues.id) AS queue_count,
                   COUNT(play_history_plays.id) AS play_count
            FROM play_history_queues
            INNER JOIN play_history_plays
                ON play_history_plays.queue_id = play_history_queues.id
            GROUP BY source_type, source_name, play_order_mode
            HAVING play_count > 0
            ORDER BY play_count DESC, queue_count DESC, source_name COLLATE NOCASE
            """.trimIndent(),
            null,
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        PlayHistoryQueueStats(
                            sourceType = cursor.getString(0),
                            sourceName = cursor.getString(1),
                            playOrderMode = cursor.getString(2),
                            queueCount = cursor.getLong(3),
                            playCount = cursor.getLong(4),
                        )
                    )
                }
            }
        }
    }

    fun stats(): PlayHistoryStats {
        return PlayHistoryStats(songStats(), queueStats())
    }

    private fun createQueue(database: SQLiteDatabase, queue: PlayHistoryQueue): Long {
        return database.compileStatement(
            """
            INSERT INTO play_history_queues(source_type, source_name, play_order_mode, created_at_ms)
            VALUES (?, ?, ?, ?)
            """.trimIndent()
        ).apply {
            bindString(1, queue.type.name)
            bindString(2, queue.name)
            bindString(3, queue.order.name)
            bindLong(4, queue.createdAtMs)
        }.executeInsert()
    }

    private fun ensureSong(database: SQLiteDatabase, item: AudioItem): Long {
        database.execSQL(
            """
            INSERT OR IGNORE INTO play_history_songs(uri_string, isrc)
            VALUES (?, ?)
            """.trimIndent(),
            arrayOf(item.uriString, item.isrc.orEmpty()),
        )
        database.execSQL(
            """
            UPDATE play_history_songs
            SET isrc = ?
            WHERE uri_string = ?
            """.trimIndent(),
            arrayOf(item.isrc.orEmpty(), item.uriString),
        )
        return database.rawQuery(
            "SELECT id FROM play_history_songs WHERE uri_string = ?",
            arrayOf(item.uriString),
        ).use { cursor ->
            cursor.moveToFirst()
            cursor.getLong(0)
        }
    }

    private class PlayHistoryDatabase(context: Context) :
        SQLiteOpenHelper(context, "play_history.db", null, 1) {
        override fun onConfigure(database: SQLiteDatabase) {
            database.setForeignKeyConstraintsEnabled(true)
        }

        override fun onCreate(database: SQLiteDatabase) {
            database.execSQL("CREATE TABLE play_history_songs (id INTEGER PRIMARY KEY, uri_string TEXT NOT NULL UNIQUE, isrc TEXT NOT NULL DEFAULT '')")
            database.execSQL("CREATE TABLE play_history_queues (id INTEGER PRIMARY KEY, source_type TEXT NOT NULL, source_name TEXT NOT NULL, play_order_mode TEXT NOT NULL, created_at_ms INTEGER NOT NULL)")
            database.execSQL("CREATE TABLE play_history_plays (id INTEGER PRIMARY KEY, song_id INTEGER NOT NULL, queue_id INTEGER, started_at_ms INTEGER NOT NULL, listened_duration_ms INTEGER NOT NULL, FOREIGN KEY(song_id) REFERENCES play_history_songs(id) ON DELETE CASCADE, FOREIGN KEY(queue_id) REFERENCES play_history_queues(id) ON DELETE SET NULL)")
            database.execSQL("CREATE INDEX play_history_songs_isrc_index ON play_history_songs(isrc)")
            database.execSQL("CREATE INDEX play_history_queues_source_index ON play_history_queues(source_type, source_name, play_order_mode)")
            database.execSQL("CREATE INDEX play_history_plays_song_index ON play_history_plays(song_id, started_at_ms)")
            database.execSQL("CREATE INDEX play_history_plays_queue_index ON play_history_plays(queue_id, started_at_ms)")
        }

        override fun onUpgrade(database: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    }
}