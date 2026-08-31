package com.example.gravimusicplayer

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class ListeningSegment(
    val startPositionMs: Long,
    val durationMs: Long,
)

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

class PlayHistoryRepository(context: Context) {
    private val databaseHelper = PlayHistoryDatabase(context.applicationContext)

    fun createQueue(
        queue: List<AudioItem>,
        queueType: QueueType,
        queueName: String,
        queueOrder: QueueOrder,
    ): Long {
        val nowMs = System.currentTimeMillis()
        val database = databaseHelper.writableDatabase
        database.beginTransaction()
        try {
            val queueId = database.compileStatement(
                """
                INSERT INTO play_history_queues(
                    source_type, source_name, play_order_mode, created_at_ms
                ) VALUES (?, ?, ?, ?)
                """.trimIndent()
            ).apply {
                bindString(1, queueType.name)
                bindString(2, queueName)
                bindString(3, queueOrder.name)
                bindLong(4, nowMs)
            }.executeInsert()
            queue.forEachIndexed { position, item ->
                val songId = ensureSong(database, item, nowMs)
                database.execSQL(
                    """
                    INSERT INTO play_history_queue_items(queue_id, song_id, position)
                    VALUES (?, ?, ?)
                    """.trimIndent(),
                    arrayOf(queueId, songId, position),
                )
            }
            database.setTransactionSuccessful()
            return queueId
        } finally {
            database.endTransaction()
        }
    }

    fun recordQualifiedPlay(
        item: AudioItem,
        queueId: Long?,
        startedAtMs: Long,
        segments: List<ListeningSegment>,
    ) {
        val listenedDurationMs = segments.sumOf { it.durationMs }
        if (listenedDurationMs <= 0) return

        val nowMs = System.currentTimeMillis()
        val database = databaseHelper.writableDatabase
        database.beginTransaction()
        try {
            val songId = ensureSong(database, item, nowMs)
            val playId = database.compileStatement(
                """
                INSERT INTO play_history_plays(
                    song_id, queue_id, started_at_ms, listened_duration_ms, created_at_ms
                ) VALUES (?, ?, ?, ?, ?)
                """.trimIndent()
            ).apply {
                bindLong(1, songId)
                if (queueId == null) bindNull(2) else bindLong(2, queueId)
                bindLong(3, startedAtMs)
                bindLong(4, listenedDurationMs)
                bindLong(5, nowMs)
            }.executeInsert()
            segments.forEach { segment ->
                database.execSQL(
                    """
                    INSERT INTO play_history_listening_segments(
                        play_id, start_position_ms, duration_ms
                    ) VALUES (?, ?, ?)
                    """.trimIndent(),
                    arrayOf(playId, segment.startPositionMs, segment.durationMs),
                )
            }
            if (queueId != null) {
                database.execSQL(
                    """
                    UPDATE play_history_queues
                    SET activated_at_ms = COALESCE(activated_at_ms, ?)
                    WHERE id = ?
                    """.trimIndent(),
                    arrayOf(nowMs, queueId),
                )
            }
            database.setTransactionSuccessful()
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
            WHERE play_history_queues.activated_at_ms IS NOT NULL
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

    private fun ensureSong(database: SQLiteDatabase, item: AudioItem, nowMs: Long): Long {
        database.execSQL(
            """
            INSERT OR IGNORE INTO play_history_songs(uri_string, isrc, created_at_ms, updated_at_ms)
            VALUES (?, ?, ?, ?)
            """.trimIndent(),
            arrayOf(item.uriString, item.isrc.orEmpty(), nowMs, nowMs),
        )
        database.execSQL(
            """
            UPDATE play_history_songs
            SET isrc = ?, updated_at_ms = ?
            WHERE uri_string = ?
            """.trimIndent(),
            arrayOf(item.isrc.orEmpty(), nowMs, item.uriString),
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
            database.execSQL("CREATE TABLE play_history_songs (id INTEGER PRIMARY KEY, uri_string TEXT NOT NULL UNIQUE, isrc TEXT NOT NULL DEFAULT '', created_at_ms INTEGER NOT NULL, updated_at_ms INTEGER NOT NULL)")
            database.execSQL("CREATE TABLE play_history_queues (id INTEGER PRIMARY KEY, source_type TEXT NOT NULL, source_name TEXT NOT NULL, play_order_mode TEXT NOT NULL, created_at_ms INTEGER NOT NULL, activated_at_ms INTEGER)")
            database.execSQL("CREATE TABLE play_history_queue_items (queue_id INTEGER NOT NULL, song_id INTEGER NOT NULL, position INTEGER NOT NULL, FOREIGN KEY(queue_id) REFERENCES play_history_queues(id) ON DELETE CASCADE, FOREIGN KEY(song_id) REFERENCES play_history_songs(id) ON DELETE CASCADE, PRIMARY KEY(queue_id, position))")
            database.execSQL("CREATE TABLE play_history_plays (id INTEGER PRIMARY KEY, song_id INTEGER NOT NULL, queue_id INTEGER, started_at_ms INTEGER NOT NULL, listened_duration_ms INTEGER NOT NULL, created_at_ms INTEGER NOT NULL, FOREIGN KEY(song_id) REFERENCES play_history_songs(id) ON DELETE CASCADE, FOREIGN KEY(queue_id) REFERENCES play_history_queues(id) ON DELETE SET NULL)")
            database.execSQL("CREATE TABLE play_history_listening_segments (id INTEGER PRIMARY KEY, play_id INTEGER NOT NULL, start_position_ms INTEGER NOT NULL, duration_ms INTEGER NOT NULL, FOREIGN KEY(play_id) REFERENCES play_history_plays(id) ON DELETE CASCADE)")
            database.execSQL("CREATE INDEX play_history_songs_isrc_index ON play_history_songs(isrc)")
            database.execSQL("CREATE INDEX play_history_queues_source_index ON play_history_queues(source_type, source_name, play_order_mode)")
            database.execSQL("CREATE INDEX play_history_queues_activated_index ON play_history_queues(activated_at_ms)")
            database.execSQL("CREATE INDEX play_history_queue_items_song_index ON play_history_queue_items(song_id)")
            database.execSQL("CREATE INDEX play_history_plays_song_index ON play_history_plays(song_id, started_at_ms)")
            database.execSQL("CREATE INDEX play_history_plays_queue_index ON play_history_plays(queue_id, started_at_ms)")
            database.execSQL("CREATE INDEX play_history_listening_segments_play_index ON play_history_listening_segments(play_id)")
        }

        override fun onUpgrade(database: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    }
}