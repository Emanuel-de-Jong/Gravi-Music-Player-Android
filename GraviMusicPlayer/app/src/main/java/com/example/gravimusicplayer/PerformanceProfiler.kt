package com.example.gravimusicplayer

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject
import java.util.ArrayDeque
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import kotlin.math.roundToLong

class PerformanceProfiler private constructor(context: Context) {
    private val databaseHelper = ProfilerDatabase(context.applicationContext)
    private val pendingMeasurements = ArrayDeque<Measurement>()
    private val pendingMeasurementsLock = Any()
    private val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
    private var flushScheduled = false
    private var droppedMeasurements = 0L

    fun <Result> measure(label: String, block: () -> Result): Result {
        val startedAt = System.nanoTime()
        return try {
            block()
        } finally {
            record(label, elapsedMilliseconds(startedAt))
        }
    }

    fun record(label: String, durationMs: Long) {
        val flushDelayMs = synchronized(pendingMeasurementsLock) {
            if (pendingMeasurements.size == MAX_PENDING_MEASUREMENTS) {
                pendingMeasurements.removeFirst()
                droppedMeasurements++
            }
            pendingMeasurements.addLast(Measurement(label, durationMs, System.currentTimeMillis()))
            if (pendingMeasurements.size >= BATCH_SIZE) 0L else FLUSH_DELAY_MS
        }
        scheduleFlush(flushDelayMs)
    }

    fun exportReport(): String {
        return executor.submit<String> {
            flushOnWorker()
            buildReport()
        }.get()
    }

    fun clearData() {
        executor.submit {
            synchronized(pendingMeasurementsLock) {
                pendingMeasurements.clear()
                droppedMeasurements = 0
            }
            databaseHelper.writableDatabase.delete(MEASUREMENTS_TABLE_NAME, null, null)
        }.get()
    }

    fun shutdown() {
        executor.submit {
            flushOnWorker()
            databaseHelper.close()
        }.get()
        executor.shutdown()
    }

    private fun scheduleFlush(delayMs: Long) {
        val shouldSchedule = synchronized(pendingMeasurementsLock) {
            if (flushScheduled) {
                false
            } else {
                flushScheduled = true
                true
            }
        }
        if (shouldSchedule) {
            executor.schedule(::flushOnWorker, delayMs, TimeUnit.MILLISECONDS)
        }
    }

    private fun flushOnWorker() {
        val measurements = synchronized(pendingMeasurementsLock) {
            flushScheduled = false
            buildList {
                while (pendingMeasurements.isNotEmpty()) {
                    add(pendingMeasurements.removeFirst())
                }
            }
        }
        if (measurements.isEmpty()) return

        try {
            val database = databaseHelper.writableDatabase
            database.beginTransaction()
            try {
                measurements.forEach { measurement ->
                    database.execSQL(
                        "INSERT INTO measurements (label, durationMs, recordedAtMs) VALUES (?, ?, ?)",
                        arrayOf<Any>(
                            measurement.label,
                            measurement.durationMs,
                            measurement.recordedAtMs,
                        ),
                    )
                }
                trimMeasurements(database)
                database.setTransactionSuccessful()
            } finally {
                database.endTransaction()
            }
        } catch (_: Exception) {
            synchronized(pendingMeasurementsLock) {
                droppedMeasurements += measurements.size
            }
        }
    }

    private fun trimMeasurements(database: SQLiteDatabase) {
        val measurementCount = database.rawQuery(
            "SELECT COUNT(*) FROM measurements",
            null,
        ).use { cursor ->
            cursor.moveToFirst()
            cursor.getLong(0)
        }
        if (measurementCount > MAX_ROWS) {
            database.execSQL(
                "DELETE FROM measurements WHERE id < (SELECT id FROM measurements ORDER BY id DESC LIMIT 1 OFFSET ${MAX_ROWS - 1})"
            )
        }
    }

    private fun buildReport(): String {
        val database = databaseHelper.readableDatabase
        val summary = JSONArray()
        database.rawQuery(
            "SELECT label, COUNT(*), SUM(durationMs), AVG(durationMs), MIN(durationMs), MAX(durationMs), MAX(recordedAtMs) FROM measurements GROUP BY label ORDER BY label",
            null,
        ).use { cursor ->
            while (cursor.moveToNext()) {
                summary.put(
                    JSONObject()
                        .put("label", cursor.getString(0))
                        .put("count", cursor.getLong(1))
                        .put("totalDurationMs", cursor.getLong(2))
                        .put("averageDurationMs", cursor.getDouble(3))
                        .put("minDurationMs", cursor.getLong(4))
                        .put("maxDurationMs", cursor.getLong(5))
                        .put("latestRecordedAtMs", cursor.getLong(6))
                )
            }
        }
        val measurements = JSONArray()
        database.rawQuery(
            "SELECT label, durationMs, recordedAtMs FROM measurements ORDER BY recordedAtMs",
            null,
        ).use { cursor ->
            while (cursor.moveToNext()) {
                measurements.put(
                    JSONObject()
                        .put("label", cursor.getString(0))
                        .put("durationMs", cursor.getLong(1))
                        .put("recordedAtMs", cursor.getLong(2))
                )
            }
        }
        val droppedMeasurementCount = synchronized(pendingMeasurementsLock) { droppedMeasurements }
        return JSONObject()
            .put("formatVersion", 1)
            .put("generatedAtMs", System.currentTimeMillis())
            .put("droppedMeasurementCount", droppedMeasurementCount)
            .put("summary", summary)
            .put("measurements", measurements)
            .toString(2)
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
        private const val FLUSH_DELAY_MS = 5_000L
        private const val MAX_PENDING_MEASUREMENTS = 1_000
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