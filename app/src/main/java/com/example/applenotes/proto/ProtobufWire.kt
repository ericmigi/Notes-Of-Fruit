package com.example.applenotes.proto

/**
 * Minimal protobuf wire-format reader/writer used by NoteBodyEditor.
 *
 * Only what we need: varints, length-delimited fields, fixed32, fixed64.
 * Fields are decoded as a list of (fieldNumber, wireType, rawBytes) tuples
 * preserving source order so unknown fields round-trip cleanly.
 *
 * This is NOT a general-purpose protobuf library. It is just enough to
 * surgically modify NoteStoreProto.document.note.{note_text, attribute_run[*].length}
 * while passing through all other fields untouched.
 */
internal object ProtobufWire {
    const val WIRE_VARINT = 0
    const val WIRE_FIXED64 = 1
    const val WIRE_LENGTH_DELIM = 2
    const val WIRE_FIXED32 = 5

    data class Field(
        val fieldNumber: Int,
        val wireType: Int,
        /**
         * Raw payload bytes. For VARINT this holds the encoded varint bytes
         * (so re-emitting is byte-identical); for LENGTH_DELIM it holds just the
         * payload (without the length prefix); for fixed32/fixed64 it's the 4/8 bytes.
         */
        val payload: ByteArray,
    )

    fun decode(bytes: ByteArray): MutableList<Field> {
        val out = mutableListOf<Field>()
        var i = 0
        while (i < bytes.size) {
            val tagStart = i
            val (tag, after) = readVarint(bytes, i)
            i = after
            val fieldNumber = (tag shr 3).toInt()
            val wireType = (tag and 0x7).toInt()
            when (wireType) {
                WIRE_VARINT -> {
                    val varintStart = i
                    val (_, end) = readVarint(bytes, i)
                    out.add(Field(fieldNumber, wireType, bytes.copyOfRange(varintStart, end)))
                    i = end
                }
                WIRE_LENGTH_DELIM -> {
                    val (len, lenEnd) = readVarint(bytes, i)
                    val payloadStart = lenEnd
                    val payloadEnd = payloadStart + len.toInt()
                    if (payloadEnd > bytes.size) {
                        error("Protobuf: length-delim field $fieldNumber overflows input ($payloadEnd > ${bytes.size}) at offset $tagStart")
                    }
                    out.add(Field(fieldNumber, wireType, bytes.copyOfRange(payloadStart, payloadEnd)))
                    i = payloadEnd
                }
                WIRE_FIXED32 -> {
                    out.add(Field(fieldNumber, wireType, bytes.copyOfRange(i, i + 4)))
                    i += 4
                }
                WIRE_FIXED64 -> {
                    out.add(Field(fieldNumber, wireType, bytes.copyOfRange(i, i + 8)))
                    i += 8
                }
                else -> error("Protobuf: unsupported wireType=$wireType for field $fieldNumber at offset $tagStart")
            }
        }
        return out
    }

    fun encode(fields: List<Field>): ByteArray {
        val buf = ByteList()
        for (f in fields) {
            val tag = (f.fieldNumber.toLong() shl 3) or f.wireType.toLong()
            writeVarint(buf, tag)
            when (f.wireType) {
                WIRE_VARINT -> buf.append(f.payload)
                WIRE_LENGTH_DELIM -> {
                    writeVarint(buf, f.payload.size.toLong())
                    buf.append(f.payload)
                }
                WIRE_FIXED32, WIRE_FIXED64 -> buf.append(f.payload)
                else -> error("Protobuf: cannot encode wireType=${f.wireType}")
            }
        }
        return buf.toByteArray()
    }

    /** Decode a varint, return (value, indexAfter). */
    fun readVarint(bytes: ByteArray, start: Int): Pair<Long, Int> {
        var result = 0L
        var shift = 0
        var i = start
        while (i < bytes.size) {
            val b = bytes[i].toInt() and 0xFF
            result = result or ((b and 0x7F).toLong() shl shift)
            i++
            if (b and 0x80 == 0) return result to i
            shift += 7
            if (shift >= 64) error("Protobuf: varint too long at offset $start")
        }
        error("Protobuf: truncated varint at offset $start")
    }

    fun writeVarint(buf: ByteList, value: Long) {
        var v = value
        // protobuf varints are ULEB128; for our cases (positive lengths/tags) just emit unsigned.
        while (true) {
            val byte = (v and 0x7F).toInt()
            v = v ushr 7
            if (v == 0L) {
                buf.appendByte(byte.toByte())
                return
            }
            buf.appendByte((byte or 0x80).toByte())
        }
    }

    fun encodeVarint(value: Long): ByteArray {
        val buf = ByteList()
        writeVarint(buf, value)
        return buf.toByteArray()
    }

    /**
     * Decode a length-delim field's payload as a UTF-8 string.
     * Convenience helper for known string fields.
     */
    fun decodeString(field: Field): String {
        require(field.wireType == WIRE_LENGTH_DELIM) {
            "decodeString: field ${field.fieldNumber} is wireType=${field.wireType}, expected LENGTH_DELIM"
        }
        return field.payload.decodeToString()
    }

    fun encodeString(fieldNumber: Int, s: String): Field {
        return Field(fieldNumber, WIRE_LENGTH_DELIM, s.encodeToByteArray())
    }

    /** Decode a varint field's payload to a Long. */
    fun decodeVarint(field: Field): Long {
        require(field.wireType == WIRE_VARINT) {
            "decodeVarint: field ${field.fieldNumber} is wireType=${field.wireType}, expected VARINT"
        }
        val (v, _) = readVarint(field.payload, 0)
        return v
    }

    fun encodeVarintField(fieldNumber: Int, value: Long): Field {
        return Field(fieldNumber, WIRE_VARINT, encodeVarint(value))
    }
}

/** Append-only byte list — avoids platform-specific stream APIs. */
internal class ByteList(initial: Int = 64) {
    private var arr = ByteArray(initial)
    private var size = 0
    fun appendByte(b: Byte) {
        ensure(1)
        arr[size++] = b
    }
    fun append(bytes: ByteArray) {
        ensure(bytes.size)
        bytes.copyInto(arr, size)
        size += bytes.size
    }
    fun toByteArray(): ByteArray = arr.copyOfRange(0, size)
    private fun ensure(extra: Int) {
        if (size + extra > arr.size) {
            var n = arr.size * 2
            while (n < size + extra) n *= 2
            arr = arr.copyOf(n)
        }
    }
}
