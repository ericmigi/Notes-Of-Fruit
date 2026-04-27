package com.example.applenotes.proto

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/** Minimal gzip wrapper using java.util.zip directly. */
object Gzip {
    fun decompress(bytes: ByteArray): ByteArray {
        return GZIPInputStream(ByteArrayInputStream(bytes)).use { it.readBytes() }
    }

    fun compress(bytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(bytes) }
        return out.toByteArray()
    }
}
