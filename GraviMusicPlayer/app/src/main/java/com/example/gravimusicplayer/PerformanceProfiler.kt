package com.example.gravimusicplayer

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.roundToLong

class PerformanceProfiler private constructor(context: Context) {
    private val databaseHelper = ProfilerDatabase(context.applicationContext)
    private val pendingMeasurements = ConcurrentLinkedQueue<Measurement>()
    private val executor = Executors.newSingleThreadScheduledExecutor()

    init {
        executor.scheduleWithFixedDelay(
            ::flush,
            FLUSH_INTERVAL_SECONDS,
            FLUSH_INTERVAL_SECONDS,
            TimeUnit.SECONDS
        )
    }

    fun <Result> measure(label: String, block: () -> Result): Result {
        val startedAt = System.nanoTime()
        return try {
            block()
        } finally {
            record(label, elapsedMilliseconds(startedAt))
        }
    }

    fun record(label: String, durationMs: Long) {
        pendingMeasurements += Measurement(label, durationMs, System.currentTimeMillis())
        if (pendingMeasurements.size >= BATCH_SIZE) executor.execute(::flush)
    }

    fun exportReport(): String {
        flush()
        val database = databaseHelper.readableDatabase
        val report = buildString {
            appendLine("Gravi Music Player performance data")
            appendLine("Generated at: ${System.currentTimeMillis()}")
            appendLine()
            appendLine("Summary")
            appendLine("label,count,totalMs,averageMs,minMs,maxMs,latestRecordedAtMs")
            database.rawQuery(
                "SELECT label, COUNT(*), SUM(durationMs), AVG(durationMs), MIN(durationMs), MAX(durationMs), MAX(recordedAtMs) FROM measurements GROUP BY label ORDER BY label",
                null,
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    appendLine(
                        listOf(
                            cursor.getString(0).replace(',', '_'),
                            cursor.getLong(1),
                            cursor.getLong(2),
                            "%.2f".format(cursor.getDouble(3)),
                            cursor.getLong(4),
                            cursor.getLong(5),
                            cursor.getLong(6),
                        ).joinToString(",")
                    )
                }
            }
            appendLine()
            appendLine("Raw measurements")
            appendLine("label,durationMs,recordedAtMs")
            database.rawQuery(
                "SELECT label, durationMs, recordedAtMs FROM measurements ORDER BY recordedAtMs",
                null
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    appendLine(
                        "${
                            cursor.getString(0).replace(',', '_')
                        },${cursor.getLong(1)},${cursor.getLong(2)}"
                    )
                }
            }
        }
        return report
    }

    fun clearData() {
        flush()
        databaseHelper.writableDatabase.delete(MEASUREMENTS_TABLE_NAME, null, null)
    }

    fun shutdown() {
        flush()
        executor.shutdown()
        databaseHelper.close()
    }

    private fun flush() {
        if (pendingMeasurements.isEmpty()) return
        val database = databaseHelper.writableDatabase
        database.beginTransaction()
        try {
            while (true) {
                val measurement = pendingMeasurements.poll() ?: break
                database.execSQL(
                    "INSERT INTO measurements (label, durationMs, recordedAtMs) VALUES (?, ?, ?)",
                    arrayOf(measurement.label, measurement.durationMs, measurement.recordedAtMs),
                )
            }
            database.execSQL("DELETE FROM measurements WHERE id NOT IN (SELECT id FROM measurements ORDER BY id DESC LIMIT $MAX_ROWS)")
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
    }

    private fun elapsedMilliseconds(startedAt: Long): Long {
        return ((System.nanoTime() - startedAt) / 1_000_000.0).roundToLong().coerceAtLeast(0)
    }

    private data class Measurement(val label: String, val durationMs: Long, val recordedAtMs: Long)

    private class ProfilerDatabase(context: Context) :
        SQLiteOpenHelper(context, "performance_profiler.db", null, 1) {
        override fun onCreate(database: SQLiteDatabase) {
            database.execSQL("CREATE TABLE measurements (id INTEGER PRIMARY KEY AUTOINCREMENT, label TEXT NOT NULL, durationMs INTEGER NOT NULL, recordedAtMs INTEGER NOT NULL)")
            database.execSQL("CREATE INDEX measurements_label_index ON measurements(label)")
        }

        override fun onUpgrade(database: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    }

    companion object {
        private const val MEASUREMENTS_TABLE_NAME = "measurements"
        private const val BATCH_SIZE = 20
        private const val FLUSH_INTERVAL_SECONDS = 5L
        private const val MAX_ROWS = 20_000

        @Volatile
        private var instance: PerformanceProfiler? = null

        fun get(context: Context): PerformanceProfiler {
            return instance ?: synchronized(this) {
                instance ?: PerformanceProfiler(context).also { instance = it }
            }
        }
    }
}