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
    fun analyze(uri: Uri, shouldCancel: () -> Boolean): SilenceBoundaries {
        return runCatching { decodeLevels(uri, shouldCancel)?.let(::boundariesFromLevels) }
            .getOrNull() ?: SilenceBoundaries()
    }

    private fun decodeLevels(uri: Uri, shouldCancel: () -> Boolean): List<Double>? {
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        return try {
            extractor.setDataSource(context, uri, null)
            val trackIndex = findAudioTrackIndex(extractor) ?: return null
            val inputFormat = extractor.getTrackFormat(trackIndex)
            val mimeType = inputFormat.getString(MediaFormat.KEY_MIME) ?: return null
            extractor.selectTrack(trackIndex)
            decoder = MediaCodec.createDecoderByType(mimeType)
            decoder.configure(inputFormat, null, null, 0)
            decoder.start()
            decodeLevels(extractor, decoder, shouldCancel)
        } finally {
            decoder?.runCatching { stop() }
            decoder?.release()
            extractor.release()
        }
    }

    private fun findAudioTrackIndex(extractor: MediaExtractor): Int? {
        for (index in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(index)
            val mimeType = format.getString(MediaFormat.KEY_MIME)
            if (mimeType?.startsWith("audio/") == true) return index
        }
        return null
    }

    private fun decodeLevels(
        extractor: MediaExtractor,
        decoder: MediaCodec,
        shouldCancel: () -> Boolean,
    ): List<Double>? {
        val levels = mutableListOf<Double>()
        val bufferInfo = MediaCodec.BufferInfo()
        var inputEnded = false
        var outputEnded = false
        var outputFormat = decoder.outputFormat
        var sampleRate = outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channelCount = outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        var encoding = outputFormat.getIntegerOrDefault(MediaFormat.KEY_PCM_ENCODING, 2)
        var windowSquareSum = 0.0
        var windowSampleCount = 0
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
                    sampleRate = outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    channelCount = outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    encoding = outputFormat.getIntegerOrDefault(MediaFormat.KEY_PCM_ENCODING, 2)
                    samplesPerWindow = samplesPerWindow(sampleRate)
                }

                else -> if (outputIndex >= 0) {
                    val outputBuffer = decoder.getOutputBuffer(outputIndex) ?: return null
                    val result = readPcmLevels(
                        outputBuffer,
                        bufferInfo,
                        channelCount,
                        encoding,
                        samplesPerWindow,
                        windowSquareSum,
                        windowSampleCount,
                        levels,
                    ) ?: return null
                    windowSquareSum = result.squareSum
                    windowSampleCount = result.sampleCount
                    outputEnded = bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    decoder.releaseOutputBuffer(outputIndex, false)
                }
            }
        }

        if (shouldCancel()) return null
        if (windowSampleCount > 0) {
            levels += sqrt(windowSquareSum / windowSampleCount)
        }
        return levels
    }

    private fun readPcmLevels(
        outputBuffer: ByteBuffer,
        bufferInfo: MediaCodec.BufferInfo,
        channelCount: Int,
        encoding: Int,
        samplesPerWindow: Int,
        initialSquareSum: Double,
        initialSampleCount: Int,
        levels: MutableList<Double>,
    ): WindowState? {
        var squareSum = initialSquareSum
        var sampleCount = initialSampleCount
        val buffer = outputBuffer.order(ByteOrder.LITTLE_ENDIAN)
        buffer.position(bufferInfo.offset)
        buffer.limit(bufferInfo.offset + bufferInfo.size)
        val safeChannelCount = channelCount.coerceAtLeast(1)

        when (encoding) {
            2 -> {
                while (buffer.remaining() >= safeChannelCount * 2) {
                    var frameValue = 0.0
                    repeat(safeChannelCount) {
                        frameValue += buffer.short / 32768.0
                    }
                    val value = frameValue / safeChannelCount
                    squareSum += value * value
                    sampleCount++
                    if (sampleCount >= samplesPerWindow) {
                        levels += sqrt(squareSum / sampleCount)
                        squareSum = 0.0
                        sampleCount = 0
                    }
                }
            }

            4 -> {
                while (buffer.remaining() >= safeChannelCount * 4) {
                    var frameValue = 0.0
                    repeat(safeChannelCount) {
                        frameValue += buffer.float.toDouble().coerceIn(-1.0, 1.0)
                    }
                    val value = frameValue / safeChannelCount
                    squareSum += value * value
                    sampleCount++
                    if (sampleCount >= samplesPerWindow) {
                        levels += sqrt(squareSum / sampleCount)
                        squareSum = 0.0
                        sampleCount = 0
                    }
                }
            }

            else -> return null
        }

        return WindowState(squareSum, sampleCount)
    }

    private fun boundariesFromLevels(levels: List<Double>): SilenceBoundaries {
        if (levels.isEmpty()) return SilenceBoundaries()

        val leadingWindows = silentWindowCount(levels)
        val trailingWindows = silentWindowCount(levels.asReversed())
        val retainedWindows = RETAINED_SILENCE_MS / WINDOW_DURATION_MS
        val startPositionMs = if (leadingWindows > retainedWindows) {
            (leadingWindows - retainedWindows) * WINDOW_DURATION_MS
        } else {
            0
        }
        val endPositionMs = if (trailingWindows > retainedWindows) {
            (levels.size - trailingWindows + retainedWindows) * WINDOW_DURATION_MS
        } else {
            null
        }
        return SilenceBoundaries(startPositionMs, endPositionMs)
    }

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

    private fun MediaFormat.getIntegerOrDefault(key: String, defaultValue: Int): Int {
        return if (containsKey(key)) getInteger(key) else defaultValue
    }

    private data class WindowState(
        val squareSum: Double,
        val sampleCount: Int,
    )

    companion object {
        private const val TIMEOUT_US = 10_000L
        private const val WINDOW_DURATION_MS = 100
        private const val RETAINED_SILENCE_MS = 1500
        private const val SILENCE_THRESHOLD = 0.003
    }
}