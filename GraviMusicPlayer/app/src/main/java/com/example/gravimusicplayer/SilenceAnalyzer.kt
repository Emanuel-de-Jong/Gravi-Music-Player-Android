package com.example.gravimusicplayer

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

data class SilenceBoundaries(
    val startPositionMs: Int = 0,
    val endPositionMs: Int? = null,
)

class SilenceAnalyzer(private val context: Context) {
    private val performanceProfiler = PerformanceProfiler.get(context)

    fun analyze(uri: Uri, shouldCancel: () -> Boolean): SilenceBoundaries {
        return performanceProfiler.measure("SilenceAnalyzer.analyze") {
            runCatching { analyzeBoundaries(uri, shouldCancel) }.getOrDefault(SilenceBoundaries())
        }
    }

    private fun analyzeBoundaries(uri: Uri, shouldCancel: () -> Boolean): SilenceBoundaries {
        val leadingScan = decodeLevels(uri, 0L, true, shouldCancel) ?: return SilenceBoundaries()
        if (shouldCancel()) return SilenceBoundaries()

        val leadingWindows = silentWindowCount(leadingScan.levels)
        val trailingWindows = trailingSilentWindowCount(
            uri,
            leadingScan.durationUs,
            shouldCancel,
        ) ?: return SilenceBoundaries()
        if (shouldCancel()) return SilenceBoundaries()

        return SilenceBoundaries(
            trimStartPositionMs(leadingWindows),
            trimEndPositionMs(leadingScan.durationUs, trailingWindows),
        )
    }

    private fun trailingSilentWindowCount(
        uri: Uri,
        durationUs: Long,
        shouldCancel: () -> Boolean,
    ): Int? {
        var searchDurationUs = INITIAL_TRAILING_SEARCH_US
        while (true) {
            val requestedStartUs = (durationUs - searchDurationUs).coerceAtLeast(0L)
            val scan = decodeLevels(uri, requestedStartUs, false, shouldCancel) ?: return null
            if (shouldCancel()) return null

            val trailingWindows = silentWindowCount(scan.levels.asReversed())
            if (trailingWindows < scan.levels.size || requestedStartUs == 0L) {
                return trailingWindows
            }
            searchDurationUs = (searchDurationUs * 2).coerceAtMost(durationUs)
        }
    }

    private fun decodeLevels(
        uri: Uri,
        startUs: Long,
        stopAfterLeadingAudio: Boolean,
        shouldCancel: () -> Boolean,
    ): LevelScan? {
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        return try {
            extractor.setDataSource(context, uri, null)
            val trackIndex = findAudioTrackIndex(extractor) ?: return null
            val inputFormat = extractor.getTrackFormat(trackIndex)
            val mimeType = inputFormat.getString(MediaFormat.KEY_MIME) ?: return null
            val durationUs = inputFormat.getLongOrDefault(MediaFormat.KEY_DURATION, 0L)
            if (durationUs <= 0L) return null

            extractor.selectTrack(trackIndex)
            if (startUs > 0L) extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            decoder = MediaCodec.createDecoderByType(mimeType)
            decoder.configure(inputFormat, null, null, 0)
            decoder.start()
            decodeLevels(extractor, decoder, durationUs, stopAfterLeadingAudio, shouldCancel)
        } finally {
            decoder?.runCatching { stop() }
            decoder?.release()
            extractor.release()
        }
    }

    private fun decodeLevels(
        extractor: MediaExtractor,
        decoder: MediaCodec,
        durationUs: Long,
        stopAfterLeadingAudio: Boolean,
        shouldCancel: () -> Boolean,
    ): LevelScan? {
        val levels = mutableListOf<Double>()
        val bufferInfo = MediaCodec.BufferInfo()
        var inputEnded = false
        var outputEnded = false
        var outputFormat = decoder.outputFormat
        var sampleRate = outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channelCount = outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        var encoding = outputFormat.getIntegerOrDefault(MediaFormat.KEY_PCM_ENCODING, 2)
        var windowState = WindowState()
        var samplesPerWindow = samplesPerWindow(sampleRate)

        while (!outputEnded && !shouldCancel()) {
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
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    outputFormat = decoder.outputFormat
                    sampleRate = outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    channelCount = outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    encoding = outputFormat.getIntegerOrDefault(MediaFormat.KEY_PCM_ENCODING, 2)
                    samplesPerWindow = samplesPerWindow(sampleRate)
                }

                else -> if (outputIndex >= 0) {
                    val outputBuffer = decoder.getOutputBuffer(outputIndex) ?: return null
                    try {
                        val result = readPcmLevels(
                            outputBuffer,
                            bufferInfo,
                            channelCount,
                            encoding,
                            samplesPerWindow,
                            windowState,
                            levels,
                            stopAfterLeadingAudio,
                            shouldCancel,
                        ) ?: return null
                        windowState = result
                        outputEnded = windowState.foundLeadingAudio ||
                                bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    } finally {
                        decoder.releaseOutputBuffer(outputIndex, false)
                    }
                }
            }
        }

        if (shouldCancel()) return null
        if (windowState.sampleCount > 0) {
            levels += sqrt(windowState.squareSum / windowState.sampleCount)
        }
        return LevelScan(levels, durationUs)
    }

    private fun readPcmLevels(
        outputBuffer: ByteBuffer,
        bufferInfo: MediaCodec.BufferInfo,
        channelCount: Int,
        encoding: Int,
        samplesPerWindow: Int,
        initialWindowState: WindowState,
        levels: MutableList<Double>,
        stopAfterLeadingAudio: Boolean,
        shouldCancel: () -> Boolean,
    ): WindowState? {
        var squareSum = initialWindowState.squareSum
        var sampleCount = initialWindowState.sampleCount
        var frameCount = 0
        var foundLeadingAudio = false
        val buffer = outputBuffer.order(ByteOrder.LITTLE_ENDIAN)
        buffer.position(bufferInfo.offset)
        buffer.limit(bufferInfo.offset + bufferInfo.size)
        val safeChannelCount = channelCount.coerceAtLeast(1)

        while (buffer.remaining() >= safeChannelCount * bytesPerSample(encoding) && !foundLeadingAudio) {
            if (frameCount++ % CANCEL_CHECK_FRAME_INTERVAL == 0 && shouldCancel()) return null
            val frameValue = readFrameValue(buffer, safeChannelCount, encoding) ?: return null
            squareSum += frameValue * frameValue
            sampleCount++
            if (sampleCount >= samplesPerWindow) {
                val level = sqrt(squareSum / sampleCount)
                levels += level
                foundLeadingAudio = stopAfterLeadingAudio && level > SILENCE_THRESHOLD
                squareSum = 0.0
                sampleCount = 0
            }
        }

        return WindowState(squareSum, sampleCount, foundLeadingAudio)
    }

    private fun readFrameValue(buffer: ByteBuffer, channelCount: Int, encoding: Int): Double? {
        var frameValue = 0.0
        when (encoding) {
            PCM_16BIT -> repeat(channelCount) { frameValue += buffer.short / 32768.0 }
            PCM_FLOAT -> repeat(channelCount) {
                frameValue += buffer.float.toDouble().coerceIn(-1.0, 1.0)
            }

            else -> return null
        }
        return frameValue / channelCount
    }

    private fun bytesPerSample(encoding: Int): Int {
        return when (encoding) {
            PCM_16BIT -> 2
            PCM_FLOAT -> 4
            else -> Int.MAX_VALUE
        }
    }

    private fun trimStartPositionMs(leadingWindows: Int): Int {
        return ((leadingWindows - retainedWindowCount()).coerceAtLeast(0) * WINDOW_DURATION_MS)
    }

    private fun trimEndPositionMs(durationUs: Long, trailingWindows: Int): Int? {
        if (trailingWindows <= retainedWindowCount()) return null
        val durationMs = (durationUs / 1_000).toInt()
        return (durationMs - (trailingWindows - retainedWindowCount()) * WINDOW_DURATION_MS)
            .coerceAtLeast(0)
    }

    private fun retainedWindowCount(): Int = RETAINED_SILENCE_MS / WINDOW_DURATION_MS

    private fun silentWindowCount(levels: List<Double>): Int {
        var count = 0
        for (level in levels) {
            if (level > SILENCE_THRESHOLD) break
            count++
        }
        return count
    }

    private fun samplesPerWindow(sampleRate: Int): Int {
        return (sampleRate * WINDOW_DURATION_MS / 1000).coerceAtLeast(1)
    }

    private fun findAudioTrackIndex(extractor: MediaExtractor): Int? {
        for (index in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(index)
            if (format.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) return index
        }
        return null
    }

    private fun MediaFormat.getIntegerOrDefault(key: String, defaultValue: Int): Int {
        return if (containsKey(key)) getInteger(key) else defaultValue
    }

    private fun MediaFormat.getLongOrDefault(key: String, defaultValue: Long): Long {
        return if (containsKey(key)) getLong(key) else defaultValue
    }

    private data class LevelScan(val levels: List<Double>, val durationUs: Long)

    private data class WindowState(
        val squareSum: Double = 0.0,
        val sampleCount: Int = 0,
        val foundLeadingAudio: Boolean = false,
    )

    companion object {
        private const val TIMEOUT_US = 10_000L
        private const val PCM_16BIT = 2
        private const val PCM_FLOAT = 4
        private const val WINDOW_DURATION_MS = 100
        private const val RETAINED_SILENCE_MS = 1500
        private const val SILENCE_THRESHOLD = 0.003
        private const val INITIAL_TRAILING_SEARCH_US = 30_000_000L
        private const val CANCEL_CHECK_FRAME_INTERVAL = 4096
    }
}