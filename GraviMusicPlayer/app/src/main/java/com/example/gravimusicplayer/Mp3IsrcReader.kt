package com.example.gravimusicplayer

import android.content.Context
import android.net.Uri
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

object Mp3IsrcReader {
    fun readIsrc(context: Context, uri: Uri): String? {
        val performanceProfiler = PerformanceProfiler.get(context)
        return performanceProfiler.measure("Mp3IsrcReader.readIsrc") {
            val tagBytes = context.contentResolver.openInputStream(uri)?.use { inputStream ->
                val headerBytes = inputStream.readExactBytes(10) ?: return@use null
                if (!hasId3Header(headerBytes)) return@use null

                val tagSize = readSynchsafeInteger(headerBytes, 6)
                val bodyBytes = inputStream.readExactBytes(tagSize) ?: return@use null
                headerBytes + bodyBytes
            } ?: return@measure null

            readIsrcFrame(tagBytes)
        }
    }

    private fun hasId3Header(bytes: ByteArray): Boolean {
        return bytes.size >= 10 &&
                bytes[0].toInt().toChar() == 'I' &&
                bytes[1].toInt().toChar() == 'D' &&
                bytes[2].toInt().toChar() == '3'
    }

    private fun readIsrcFrame(tagBytes: ByteArray): String? {
        if (tagBytes.size < 10) return null

        val version = tagBytes[3].toInt()
        var offset = 10
        while (offset + 10 <= tagBytes.size) {
            val frameId = String(tagBytes, offset, 4, StandardCharsets.ISO_8859_1)
            if (frameId.any { it.code == 0 }) return null

            val frameSize = if (version == 4) {
                readSynchsafeInteger(tagBytes, offset + 4)
            } else {
                readInteger(tagBytes, offset + 4)
            }
            if (frameSize <= 0 || offset + 10 + frameSize > tagBytes.size) return null

            val frameOffset = offset + 10
            val isrc = when (frameId) {
                "TSRC" -> parseTextFrame(tagBytes, frameOffset, frameSize)
                "TXXX" -> parseUserTextFrame(tagBytes, frameOffset, frameSize)
                else -> null
            }?.normalizedIsrc()
            if (isrc != null) return isrc

            offset += 10 + frameSize
        }
        return null
    }

    private fun parseTextFrame(tagBytes: ByteArray, frameOffset: Int, frameSize: Int): String? {
        if (frameSize <= 1) return null

        val textEncoding = tagBytes[frameOffset].toInt()
        val charset = charsetForEncoding(textEncoding)
        return String(tagBytes, frameOffset + 1, frameSize - 1, charset)
            .trim('\u0000', ' ', '\n', '\r', '\t')
    }

    private fun parseUserTextFrame(tagBytes: ByteArray, frameOffset: Int, frameSize: Int): String? {
        if (frameSize <= 1) return null

        val textEncoding = tagBytes[frameOffset].toInt()
        val charset = charsetForEncoding(textEncoding)
        val descriptorStart = frameOffset + 1
        val frameEnd = frameOffset + frameSize
        val descriptorEnd = findTextTerminator(tagBytes, descriptorStart, frameEnd, textEncoding)
        if (descriptorEnd < 0) return null

        val descriptor = String(tagBytes, descriptorStart, descriptorEnd - descriptorStart, charset)
            .trim('\u0000', ' ', '\n', '\r', '\t')
        if (!descriptor.equals("ISRC", ignoreCase = true)) return null

        val valueStart = descriptorEnd + terminatorSize(textEncoding)
        if (valueStart >= frameEnd) return null
        return String(tagBytes, valueStart, frameEnd - valueStart, charset)
            .trim('\u0000', ' ', '\n', '\r', '\t')
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
}