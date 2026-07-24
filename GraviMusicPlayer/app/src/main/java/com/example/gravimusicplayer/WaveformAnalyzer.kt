package com.example.gravimusicplayer

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class WaveformAnalyzer(private val context: Context) {
    private val performanceProfiler = PerformanceProfiler.get(context)

    fun waveform(item: AudioItem, shouldCancel: () -> Boolean): List<Float> {
        return performanceProfiler.measure("WaveformAnalyzer.waveform") {
            val cache = loadCache()
            cache.entries.firstOrNull { it.matches(item) }?.let { return@measure it.values }
            val values = runCatching { computeWaveform(item, shouldCancel) }.getOrNull().orEmpty()
            if (values.isNotEmpty() && !shouldCancel()) {
                val updatedEntries =
                    (cache.entries.filterNot { it.uriString == item.uriString } + WaveformCacheEntry(
                        item.uriString,
                        item.lastModifiedMs,
                        item.sizeBytes,
                        ANALYSIS_VERSION,
                        System.currentTimeMillis(),
                        values,
                    )).sortedByDescending { it.updatedAtMs }
                saveCache(WaveformCacheFile(updatedEntries))
            }
            values
        }
    }

    fun clearCache() {
        cacheFile().delete()
    }

    private fun computeWaveform(item: AudioItem, shouldCancel: () -> Boolean): List<Float> {
        return emptyList()
    }

    private fun loadCache(): WaveformCacheFile {
        return runCatching {
            val json = JSONObject(cacheFile().readText())
            val entries = json.optJSONArray("entries") ?: JSONArray()
            WaveformCacheFile(
                (0 until entries.length()).mapNotNull { index ->
                    val entry = entries.optJSONObject(index) ?: return@mapNotNull null
                    WaveformCacheEntry(
                        entry.optString("uriString"),
                        entry.optLong("lastModifiedMs"),
                        entry.optLong("sizeBytes"),
                        entry.optInt("analysisVersion"),
                        entry.optLong("updatedAtMs"),
                        entry.optJSONArray("values").orEmptyFloatList(),
                    ).takeIf { it.uriString.isNotBlank() && it.values.isNotEmpty() }
                }
            )
        }.getOrDefault(WaveformCacheFile())
    }

    private fun saveCache(cache: WaveformCacheFile) {
        val entries = JSONArray()
        cache.entries.forEach { entry ->
            entries.put(
                JSONObject()
                    .put("uriString", entry.uriString)
                    .put("lastModifiedMs", entry.lastModifiedMs)
                    .put("sizeBytes", entry.sizeBytes)
                    .put("analysisVersion", entry.analysisVersion)
                    .put("updatedAtMs", entry.updatedAtMs)
                    .put("values", JSONArray(entry.values))
            )
        }
        cacheFile().writeText(JSONObject().put("entries", entries).toString())
    }

    private fun cacheFile(): File {
        return File(context.filesDir, "waveform_cache.json")
    }

    private fun JSONArray?.orEmptyFloatList(): List<Float> {
        if (this == null) return emptyList()
        return (0 until length()).map { index -> optDouble(index).toFloat().coerceIn(0f, 1f) }
    }

    private data class WaveformCacheFile(
        val entries: List<WaveformCacheEntry> = emptyList(),
    )

    private data class WaveformCacheEntry(
        val uriString: String,
        val lastModifiedMs: Long,
        val sizeBytes: Long,
        val analysisVersion: Int,
        val updatedAtMs: Long,
        val values: List<Float>,
    ) {
        fun matches(item: AudioItem): Boolean {
            return uriString == item.uriString &&
                    lastModifiedMs == item.lastModifiedMs &&
                    sizeBytes == item.sizeBytes &&
                    analysisVersion == ANALYSIS_VERSION
        }
    }

    companion object {
        private const val ANALYSIS_VERSION = 1
    }
}