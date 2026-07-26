package com.example.gravimusicplayer

import android.content.Context
import android.net.Uri
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

data class ReplayGainMetadata(
    val trackGainDb: Float?,
    val trackPeak: Float?,
)

object Mp3ReplayGainReader {
    fun readReplayGain(context: Context, uri: Uri): ReplayGainMetadata? {
        val performanceProfiler = PerformanceProfiler.get(context)
        return performanceProfiler.measure("Mp3ReplayGainReader.readReplayGain") {
            val tagBytes = context.contentResolver.openInputStream(uri)?.use { inputStream ->
                val headerBytes = inputStream.readExactBytes(10) ?: return@use null
                if (!hasId3Header(headerBytes)) return@use null

                val tagSize = readSynchsafeInteger(headerBytes, 6)
                val bodyBytes = inputStream.readExactBytes(tagSize) ?: return@use null
                headerBytes + bodyBytes
            } ?: return@measure null

            readReplayGainFrames(tagBytes)
        }
    }

    private fun hasId3Header(bytes: ByteArray): Boolean {
        return bytes.size >= 10 &&
                bytes[0].toInt().toChar() == 'I' &&
                bytes[1].toInt().toChar() == 'D' &&
                bytes[2].toInt().toChar() == '3'
    }

    private fun readReplayGainFrames(tagBytes: ByteArray): ReplayGainMetadata? {
        if (tagBytes.size < 10) return null

        val version = tagBytes[3].toInt()
        var offset = 10
        var trackGainDb: Float? = null
        var trackPeak: Float? = null
        while (offset + 10 <= tagBytes.size) {
            val frameId = String(tagBytes, offset, 4, StandardCharsets.ISO_8859_1)
            if (frameId.any { it.code == 0 }) return null

            val frameSize = if (version == 4) {
                readSynchsafeInteger(tagBytes, offset + 4)
            } else {
                readInteger(tagBytes, offset + 4)
            }
            if (frameSize <= 0 || offset + 10 + frameSize > tagBytes.size) return null

            if (frameId == "TXXX") {
                val userText = parseUserTextFrame(tagBytes, offset + 10, frameSize)
                when (userText?.description?.uppercase()) {
                    TRACK_GAIN_HEADER -> trackGainDb = parseNumber(userText.value)
                    TRACK_PEAK_HEADER -> trackPeak = parseNumber(userText.value)
                }
            }
            offset += 10 + frameSize
        }
        if (trackGainDb == null && trackPeak == null) return null

        return ReplayGainMetadata(trackGainDb, trackPeak)
    }

    private fun parseUserTextFrame(
        tagBytes: ByteArray,
        frameOffset: Int,
        frameSize: Int,
    ): UserTextFrame? {
        if (frameSize <= 1) return null

        val textEncoding = tagBytes[frameOffset].toInt()
        val charset = charsetForEncoding(textEncoding)
        val textStart = frameOffset + 1
        val textEnd = frameOffset + frameSize
        val descriptionEnd = findTextTerminator(tagBytes, textStart, textEnd, textEncoding)
        if (descriptionEnd < 0) return null

        val valueStart = descriptionEnd + terminatorSize(textEncoding)
        if (valueStart > textEnd) return null

        val description = String(tagBytes, textStart, descriptionEnd - textStart, charset)
            .trim('\u0000', ' ', '\n', '\r', '\t')
        val value = String(tagBytes, valueStart, textEnd - valueStart, charset)
            .trim('\u0000', ' ', '\n', '\r', '\t')
        return UserTextFrame(description, value)
    }

    private fun parseNumber(value: String): Float? {
        return numberRegex.find(value)?.value?.toFloatOrNull()
    }

    private fun findTextTerminator(
        bytes: ByteArray,
        start: Int,
        end: Int,
        textEncoding: Int,
    ): Int {
        val step = terminatorSize(textEncoding)
        var offset = start
        while (offset + step <= end) {
            if (step == 1 && bytes[offset].toInt() == 0) return offset
            if (step == 2 && bytes[offset].toInt() == 0 && bytes[offset + 1].toInt() == 0) return offset
            offset += step
        }
        return -1
    }

    private fun charsetForEncoding(textEncoding: Int): Charset {
        return when (textEncoding) {
            1 -> StandardCharsets.UTF_16
            2 -> StandardCharsets.UTF_16BE
            3 -> StandardCharsets.UTF_8
            else -> StandardCharsets.ISO_8859_1
        }
    }

    private fun terminatorSize(textEncoding: Int): Int {
        return if (textEncoding == 1 || textEncoding == 2) 2 else 1
    }

    private fun readSynchsafeInteger(bytes: ByteArray, offset: Int): Int {
        return (bytes[offset].toInt() and 0x7F shl 21) or
                (bytes[offset + 1].toInt() and 0x7F shl 14) or
                (bytes[offset + 2].toInt() and 0x7F shl 7) or
                (bytes[offset + 3].toInt() and 0x7F)
    }

    private fun readInteger(bytes: ByteArray, offset: Int): Int {
        return (bytes[offset].toInt() and 0xFF shl 24) or
                (bytes[offset + 1].toInt() and 0xFF shl 16) or
                (bytes[offset + 2].toInt() and 0xFF shl 8) or
                (bytes[offset + 3].toInt() and 0xFF)
    }

    private fun java.io.InputStream.readExactBytes(size: Int): ByteArray? {
        val bytes = ByteArray(size)
        var offset = 0
        while (offset < size) {
            val readCount = read(bytes, offset, size - offset)
            if (readCount < 0) return null
            offset += readCount
        }
        return bytes
    }

    private data class UserTextFrame(
        val description: String,
        val value: String,
    )

    private const val TRACK_GAIN_HEADER = "REPLAYGAIN_TRACK_GAIN"
    private const val TRACK_PEAK_HEADER = "REPLAYGAIN_TRACK_PEAK"
    private val numberRegex = Regex("[-+]?\\d+(?:\\.\\d+)?")
}