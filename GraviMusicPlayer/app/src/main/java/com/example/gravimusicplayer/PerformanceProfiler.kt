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
import kotlin.math.ceil
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
        val summaryRaw = JSONArray()
        database.rawQuery(
            "SELECT label, COUNT(*), SUM(durationMs), AVG(durationMs), MIN(durationMs), MAX(durationMs), MAX(recordedAtMs) FROM measurements GROUP BY label ORDER BY AVG(durationMs)",
            null,
        ).use { cursor ->
            while (cursor.moveToNext()) {
                summaryRaw.put(
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
        val durationsByLabel = mutableMapOf<String, MutableList<Long>>()
        database.rawQuery(
            "SELECT label, durationMs, recordedAtMs FROM measurements ORDER BY recordedAtMs",
            null,
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val label = cursor.getString(0)
                val durationMs = cursor.getLong(1)
                durationsByLabel.getOrPut(label) { mutableListOf() }.add(durationMs)
                measurements.put(
                    JSONObject()
                        .put("label", label)
                        .put("durationMs", durationMs)
                        .put("recordedAtMs", cursor.getLong(2))
                )
            }
        }
        val summaryNoOutliers = buildSummaryNoOutliers(durationsByLabel)
        val droppedMeasurementCount = synchronized(pendingMeasurementsLock) { droppedMeasurements }
        return JSONObject()
            .put("formatVersion", 2)
            .put("generatedAtMs", System.currentTimeMillis())
            .put("droppedMeasurementCount", droppedMeasurementCount)
            .put("summaryNoOutliers", summaryNoOutliers)
            .put("summaryRaw", summaryRaw)
            .put("measurements", measurements)
            .toString(2)
    }

    private fun buildSummaryNoOutliers(durationsByLabel: Map<String, List<Long>>): JSONArray {
        val entries = durationsByLabel.map { (label, durationsMs) ->
            val filteredDurationsMs = filterOutliers(durationsMs)
            SummaryEntry(
                label,
                filteredDurationsMs.size.toLong(),
                filteredDurationsMs.sum(),
                filteredDurationsMs.average(),
                filteredDurationsMs.minOrNull() ?: 0L,
                filteredDurationsMs.maxOrNull() ?: 0L,
                durationsMs.size - filteredDurationsMs.size,
                (durationsMs.size - filteredDurationsMs.size).toDouble() / durationsMs.size * 100,
            )
        }.sortedBy { it.averageDurationMs }

        val summary = JSONArray()
        entries.forEach { entry ->
            summary.put(
                JSONObject()
                    .put("label", entry.label)
                    .put("count", entry.count)
                    .put("totalDurationMs", entry.totalDurationMs)
                    .put("averageDurationMs", entry.averageDurationMs)
                    .put("minDurationMs", entry.minDurationMs)
                    .put("maxDurationMs", entry.maxDurationMs)
                    .put("filteredMeasurementCount", entry.filteredMeasurementCount)
                    .put("filteredMeasurementPercent", entry.filteredMeasurementPercent)
            )
        }
        return summary
    }

    private fun filterOutliers(durationsMs: List<Long>): List<Long> {
        if (durationsMs.size >= OUTLIER_PERCENTILE_MIN_MEASUREMENTS) {
            return trimPercentiles(durationsMs, OUTLIER_TRIM_RATIO)
        }

        return trimSmallSampleOutliers(durationsMs)
    }

    private fun trimPercentiles(durationsMs: List<Long>, trimRatio: Double): List<Long> {
        val sortedDurationsMs = durationsMs.sorted()
        val trimCount = maxOf(1, ceil(sortedDurationsMs.size * trimRatio).toInt())
        val trimmedDurationsMs = sortedDurationsMs.drop(trimCount).dropLast(trimCount)
        return trimmedDurationsMs.ifEmpty { sortedDurationsMs }
    }

    private fun trimSmallSampleOutliers(durationsMs: List<Long>): List<Long> {
        if (durationsMs.size < 4) return durationsMs

        val sortedDurationsMs = durationsMs.sorted()
        val gaps = sortedDurationsMs.zipWithNext { firstDurationMs, secondDurationMs ->
            secondDurationMs - firstDurationMs
        }
        val positiveGaps = gaps.filter { it > 0 }
        if (positiveGaps.size < 2) return sortedDurationsMs

        val typicalGap = median(positiveGaps)
        if (typicalGap <= 0.0) return sortedDurationsMs

        val unusualGapIndexes = gaps.mapIndexedNotNull { index, gap ->
            if (gap >= typicalGap * 10) index else null
        }
        if (unusualGapIndexes.isEmpty()) return sortedDurationsMs

        var bestFilteredDurationsMs = sortedDurationsMs
        var bestScore = 0.0

        unusualGapIndexes.forEach { gapIndex ->
            val lowClusterEnd = gapIndex + 1
            val highClusterStart = gapIndex + 1
            val lowCluster = sortedDurationsMs.take(lowClusterEnd)
            val highCluster = sortedDurationsMs.drop(highClusterStart)

            if (shouldRemoveEdgeCluster(lowCluster, sortedDurationsMs)) {
                val filteredDurationsMs = sortedDurationsMs.drop(lowClusterEnd)
                val score = gaps[gapIndex].toDouble() / typicalGap - lowCluster.size
                if (score > bestScore) {
                    bestFilteredDurationsMs = filteredDurationsMs
                    bestScore = score
                }
            }

            if (shouldRemoveEdgeCluster(highCluster, sortedDurationsMs)) {
                val filteredDurationsMs = sortedDurationsMs.take(highClusterStart)
                val score = gaps[gapIndex].toDouble() / typicalGap - highCluster.size
                if (score > bestScore) {
                    bestFilteredDurationsMs = filteredDurationsMs
                    bestScore = score
                }
            }
        }

        return bestFilteredDurationsMs.ifEmpty { sortedDurationsMs }
    }

    private fun shouldRemoveEdgeCluster(cluster: List<Long>, values: List<Long>): Boolean {
        if (cluster.isEmpty()) return false

        if (cluster.size >= values.size / 2.0) return false

        return cluster.size <= 2
    }

    private fun median(values: List<Long>): Double {
        val sortedValues = values.sorted()
        val middleIndex = sortedValues.size / 2
        return if (sortedValues.size % 2 == 0) {
            (sortedValues[middleIndex - 1] + sortedValues[middleIndex]) / 2.0
        } else {
            sortedValues[middleIndex].toDouble()
        }
    }

    private fun elapsedMilliseconds(startedAt: Long): Long {
        return ((System.nanoTime() - startedAt) / 1_000_000.0).roundToLong().coerceAtLeast(0)
    }

    private data class SummaryEntry(
        val label: String,
        val count: Long,
        val totalDurationMs: Long,
        val averageDurationMs: Double,
        val minDurationMs: Long,
        val maxDurationMs: Long,
        val filteredMeasurementCount: Int,
        val filteredMeasurementPercent: Double,
    )

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
        private const val OUTLIER_PERCENTILE_MIN_MEASUREMENTS = 25
        private const val OUTLIER_TRIM_RATIO = 0.01

        @Volatile
        private var instance: PerformanceProfiler? = null

        fun get(context: Context): PerformanceProfiler {
            return instance ?: synchronized(this) {
                instance ?: PerformanceProfiler(context).also { instance = it }
            }
        }
    }
}