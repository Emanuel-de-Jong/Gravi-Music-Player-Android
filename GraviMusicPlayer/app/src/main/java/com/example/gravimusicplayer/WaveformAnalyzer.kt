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

class WaveformAnalyzer(private val context: Context) {
    fun waveform(item: AudioItem, shouldCancel: () -> Boolean): List<Float> {
        val cache = loadCache()
        cache.entries.firstOrNull { it.matches(item) }?.let {
            return it.values
        }

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
        return values
    }

    fun clearCache() {
        cacheFile().delete()
    }

    private fun computeWaveform(item: AudioItem, shouldCancel: () -> Boolean): List<Float> {
        val peaks = FloatArray(BUCKET_COUNT)
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        return try {
            extractor.setDataSource(context, item.uri, null)
            val trackIndex = findAudioTrackIndex(extractor) ?: return emptyList()
            val inputFormat = extractor.getTrackFormat(trackIndex)
            val mimeType = inputFormat.getString(MediaFormat.KEY_MIME) ?: return emptyList()
            val durationUs = if (inputFormat.containsKey(MediaFormat.KEY_DURATION)) {
                inputFormat.getLong(MediaFormat.KEY_DURATION)
            } else {
                item.durationMs?.times(1000) ?: 0L
            }
            if (durationUs <= 0L) return emptyList()

            extractor.selectTrack(trackIndex)
            decoder = MediaCodec.createDecoderByType(mimeType)
            decoder.configure(inputFormat, null, null, 0)
            decoder.start()
            decodeWaveform(extractor, decoder, shouldCancel, peaks, durationUs)
        } finally {
            decoder?.runCatching { stop() }
            decoder?.release()
            extractor.release()
        }
    }

    private fun decodeWaveform(
        extractor: MediaExtractor,
        decoder: MediaCodec,
        shouldCancel: () -> Boolean,
        peaks: FloatArray,
        durationUs: Long,
    ): List<Float> {
        val bufferInfo = MediaCodec.BufferInfo()
        var inputEnded = false
        var outputEnded = false
        var outputFormat = decoder.outputFormat
        var channelCount = outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        var encoding = outputFormat.getIntegerOrDefault(MediaFormat.KEY_PCM_ENCODING, 2)

        while (!outputEnded && !shouldCancel()) {
            if (!inputEnded) {
                val inputIndex = decoder.dequeueInputBuffer(TIMEOUT_US)
                if (inputIndex >= 0) {
                    val inputBuffer = decoder.getInputBuffer(inputIndex) ?: return emptyList()
                    val sampleSize = extractor.readSampleData(inputBuffer, 0)
                    if (sampleSize < 0) {
                        decoder.queueInputBuffer(
                            inputIndex,
                            0,
                            0,
                            0,
                            MediaCodec.BUFFER_FLAG_END_OF_STREAM
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
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    outputFormat = decoder.outputFormat
                    channelCount = outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    encoding = outputFormat.getIntegerOrDefault(MediaFormat.KEY_PCM_ENCODING, 2)
                }

                else -> if (outputIndex >= 0) {
                    val outputBuffer = decoder.getOutputBuffer(outputIndex) ?: return emptyList()
                    try {
                        if (!readWaveformBuffer(
                                outputBuffer,
                                bufferInfo,
                                channelCount,
                                encoding,
                                peaks,
                                durationUs,
                                shouldCancel,
                            )
                        ) {
                            return emptyList()
                        }
                        outputEnded = bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    } finally {
                        decoder.releaseOutputBuffer(outputIndex, false)
                    }
                }
            }
        }

        if (shouldCancel()) return emptyList()
        return normalizePeaks(peaks)
    }

    private fun readWaveformBuffer(
        outputBuffer: ByteBuffer,
        bufferInfo: MediaCodec.BufferInfo,
        channelCount: Int,
        encoding: Int,
        peaks: FloatArray,
        durationUs: Long,
        shouldCancel: () -> Boolean,
    ): Boolean {
        val buffer = outputBuffer.order(ByteOrder.LITTLE_ENDIAN)
        buffer.position(bufferInfo.offset)
        buffer.limit(bufferInfo.offset + bufferInfo.size)
        val safeChannelCount = channelCount.coerceAtLeast(1)
        val bucketIndex = (bufferInfo.presentationTimeUs * BUCKET_COUNT / durationUs).toInt()
            .coerceIn(0, BUCKET_COUNT - 1)
        var frameIndex = 0

        when (encoding) {
            2 -> {
                while (buffer.remaining() >= safeChannelCount * 2) {
                    if (frameIndex++ % CANCEL_CHECK_FRAME_INTERVAL == 0 && shouldCancel()) return false

                    var framePeak = 0f
                    repeat(safeChannelCount) {
                        framePeak = maxOf(framePeak, kotlin.math.abs(buffer.short / 32768f))
                    }
                    peaks[bucketIndex] = maxOf(peaks[bucketIndex], framePeak)
                }
            }

            4 -> {
                while (buffer.remaining() >= safeChannelCount * 4) {
                    if (frameIndex++ % CANCEL_CHECK_FRAME_INTERVAL == 0 && shouldCancel()) return false

                    var framePeak = 0f
                    repeat(safeChannelCount) {
                        framePeak =
                            maxOf(framePeak, kotlin.math.abs(buffer.float.coerceIn(-1f, 1f)))
                    }
                    peaks[bucketIndex] = maxOf(peaks[bucketIndex], framePeak)
                }
            }

            else -> return false
        }
        return true
    }

    private fun normalizePeaks(peaks: FloatArray): List<Float> {
        val minPeak = peaks.minOrNull() ?: return emptyList()
        val maxPeak = peaks.maxOrNull() ?: return emptyList()
        val range = maxPeak - minPeak
        if (range <= 0f) return peaks.map { 0f }

        return peaks.map { ((it - minPeak) / range).coerceIn(0f, 1f) }
    }

    private fun findAudioTrackIndex(extractor: MediaExtractor): Int? {
        for (index in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(index)
            val mimeType = format.getString(MediaFormat.KEY_MIME)
            if (mimeType?.startsWith("audio/") == true) return index
        }
        return null
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

    private fun MediaFormat.getIntegerOrDefault(key: String, defaultValue: Int): Int {
        return if (containsKey(key)) getInteger(key) else defaultValue
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
        private const val TIMEOUT_US = 10_000L
        private const val BUCKET_COUNT = 100
        private const val CANCEL_CHECK_FRAME_INTERVAL = 4096
        private const val ANALYSIS_VERSION = 5
        private const val MAX_CACHE_ENTRIES = 500
    }
}