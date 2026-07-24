package com.example.gravimusicplayer

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

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
                    )).sortedByDescending { it.updatedAtMs }.take(MAX_CACHE_ENTRIES)
                saveCache(WaveformCacheFile(updatedEntries))
            }
            values
        }
    }

    fun clearCache() {
        cacheFile().delete()
    }

    private fun computeWaveform(item: AudioItem, shouldCancel: () -> Boolean): List<Float> {
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        return try {
            extractor.setDataSource(context, item.uri, null)
            val trackIndex = findAudioTrackIndex(extractor) ?: return emptyList()
            val inputFormat = extractor.getTrackFormat(trackIndex)
            val mimeType = inputFormat.getString(MediaFormat.KEY_MIME) ?: return emptyList()
            val durationUs = inputFormat.getLongOrDefault(
                MediaFormat.KEY_DURATION,
                item.durationMs?.times(1_000) ?: 0L,
            )
            if (durationUs <= 0L) return emptyList()

            extractor.selectTrack(trackIndex)
            decoder = MediaCodec.createDecoderByType(mimeType)
            decoder.configure(inputFormat, null, null, 0)
            decoder.start()

            val peaks = FloatArray(WAVEFORM_BIN_COUNT)
            for (index in peaks.indices) {
                if (shouldCancel()) return emptyList()
                val targetUs = durationUs * (index * 2L + 1L) / (WAVEFORM_BIN_COUNT * 2L)
                peaks[index] =
                    samplePeak(extractor, decoder, targetUs, shouldCancel) ?: return emptyList()
            }
            normalizePeaks(peaks)
        } finally {
            decoder?.runCatching { stop() }
            decoder?.release()
            extractor.release()
        }
    }

    private fun samplePeak(
        extractor: MediaExtractor,
        decoder: MediaCodec,
        targetUs: Long,
        shouldCancel: () -> Boolean,
    ): Float? {
        extractor.seekTo(targetUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        decoder.flush()

        val bufferInfo = MediaCodec.BufferInfo()
        var inputEnded = false
        var sampleStarted = false
        var peak = 0f
        val sampleEndUs = targetUs + SAMPLE_DURATION_US

        while (!shouldCancel()) {
            if (!inputEnded) {
                val inputIndex = decoder.dequeueInputBuffer(TIMEOUT_US)
                if (inputIndex >= 0) {
                    val inputBuffer = decoder.getInputBuffer(inputIndex) ?: return null
                    val sampleSize = extractor.readSampleData(inputBuffer, 0)
                    if (sampleSize < 0) {
                        decoder.queueInputBuffer(
                            inputIndex,
                            0,
                            0,
                            0,
                            MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                        )
                        inputEnded = true
                    } else {
                        decoder.queueInputBuffer(inputIndex, 0, sampleSize, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }

            when (val outputIndex = decoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)) {
                MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Unit
                else -> if (outputIndex >= 0) {
                    val outputBuffer = decoder.getOutputBuffer(outputIndex) ?: return null
                    try {
                        if (bufferInfo.presentationTimeUs >= targetUs) {
                            sampleStarted = true
                            peak = maxOf(
                                peak,
                                readPeak(
                                    outputBuffer,
                                    bufferInfo,
                                    decoder.outputFormat,
                                    shouldCancel
                                )
                            )
                        }
                        if ((sampleStarted && bufferInfo.presentationTimeUs >= sampleEndUs) ||
                            bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        ) {
                            return peak
                        }
                    } finally {
                        decoder.releaseOutputBuffer(outputIndex, false)
                    }
                }
            }
        }
        return null
    }

    private fun readPeak(
        outputBuffer: ByteBuffer,
        bufferInfo: MediaCodec.BufferInfo,
        outputFormat: MediaFormat,
        shouldCancel: () -> Boolean,
    ): Float {
        val channelCount = outputFormat.getIntegerOrDefault(MediaFormat.KEY_CHANNEL_COUNT, 1)
            .coerceAtLeast(1)
        val encoding = outputFormat.getIntegerOrDefault(MediaFormat.KEY_PCM_ENCODING, PCM_16BIT)
        val bytesPerSample = when (encoding) {
            PCM_16BIT -> 2
            PCM_FLOAT -> 4
            else -> return 0f
        }
        val buffer = outputBuffer.order(ByteOrder.LITTLE_ENDIAN)
        buffer.position(bufferInfo.offset)
        buffer.limit(bufferInfo.offset + bufferInfo.size)
        var peak = 0f
        var frameCount = 0
        while (buffer.remaining() >= channelCount * bytesPerSample) {
            if (frameCount++ % CANCEL_CHECK_FRAME_INTERVAL == 0 && shouldCancel()) return peak
            repeat(channelCount) {
                val amplitude = when (encoding) {
                    PCM_16BIT -> buffer.short / 32768f
                    PCM_FLOAT -> buffer.float.coerceIn(-1f, 1f)
                    else -> 0f
                }
                peak = maxOf(peak, kotlin.math.abs(amplitude))
            }
        }
        return peak
    }

    private fun normalizePeaks(peaks: FloatArray): List<Float> {
        val referencePeak = peaks.sortedArray()[(peaks.size * NORMALIZATION_PERCENTILE / 100)
            .coerceIn(0, peaks.lastIndex)]
        if (referencePeak <= 0f) return peaks.map { 0f }
        return peaks.map { peak -> sqrt((peak / referencePeak).coerceIn(0f, 1f)) }
    }

    private fun findAudioTrackIndex(extractor: MediaExtractor): Int? {
        for (index in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(index)
            if (format.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) return index
        }
        return null
    }

    private fun MediaFormat.getLongOrDefault(key: String, defaultValue: Long): Long {
        return if (containsKey(key)) getLong(key) else defaultValue
    }

    private fun MediaFormat.getIntegerOrDefault(key: String, defaultValue: Int): Int {
        return if (containsKey(key)) getInteger(key) else defaultValue
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
        private const val ANALYSIS_VERSION = 2
        private const val MAX_CACHE_ENTRIES = 250
        private const val WAVEFORM_BIN_COUNT = 96
        private const val SAMPLE_DURATION_US = 150_000L
        private const val TIMEOUT_US = 10_000L
        private const val PCM_16BIT = 2
        private const val PCM_FLOAT = 4
        private const val NORMALIZATION_PERCENTILE = 90
        private const val CANCEL_CHECK_FRAME_INTERVAL = 4_096
    }
}