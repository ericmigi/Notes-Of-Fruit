package com.example.applenotes.proto

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream

/** Format hint we read from the input bytes' magic, so we can re-encode in the same format. */
enum class CompressionFormat { GZIP, ZLIB, NONE }

/**
 * Apple Notes' `TextDataEncrypted` uses both gzip (`1f 8b`) and zlib (`78 xx`)
 * — usually older / larger notes are gzipped, smaller / newer notes are zlib
 * (deflate). We auto-detect by magic and round-trip in the same format we read.
 */
object Gzip {
    fun detect(bytes: ByteArray): CompressionFormat = when {
        bytes.size >= 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte() -> CompressionFormat.GZIP
        bytes.size >= 2 && bytes[0] == 0x78.toByte() &&
            (bytes[1] == 0x01.toByte() || bytes[1] == 0x5e.toByte() ||
                bytes[1] == 0x9c.toByte() || bytes[1] == 0xda.toByte()) -> CompressionFormat.ZLIB
        else -> CompressionFormat.NONE
    }

    fun decompress(bytes: ByteArray): ByteArray = when (detect(bytes)) {
        CompressionFormat.GZIP -> GZIPInputStream(ByteArrayInputStream(bytes)).use { it.readBytes() }
        CompressionFormat.ZLIB -> InflaterInputStream(ByteArrayInputStream(bytes)).use { it.readBytes() }
        CompressionFormat.NONE -> bytes // already raw
    }

    fun compress(bytes: ByteArray, format: CompressionFormat = CompressionFormat.GZIP): ByteArray = when (format) {
        CompressionFormat.GZIP -> {
            val out = ByteArrayOutputStream()
            GZIPOutputStream(out).use { it.write(bytes) }
            out.toByteArray()
        }
        CompressionFormat.ZLIB -> {
            // java.util.zip.DeflaterOutputStream produces zlib-wrapped output by default.
            val out = ByteArrayOutputStream()
            java.util.zip.DeflaterOutputStream(out).use { it.write(bytes) }
            out.toByteArray()
        }
        CompressionFormat.NONE -> bytes
    }
}
