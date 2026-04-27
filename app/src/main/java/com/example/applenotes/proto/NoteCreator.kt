package com.example.applenotes.proto

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Builds the `TextDataEncrypted` payload (gzipped versioned_document.Document
 * wrapping a topotext.String) for a brand-new note.
 *
 * The structure mirrors what Mac produces for a freshly created note:
 *   doc-start substring + one Mac-authored content substring + sentinel,
 *   one VectorTimestamp Clock with our UUID, one AttributeRun covering the
 *   full content length.
 */
@OptIn(ExperimentalEncodingApi::class)
object NoteCreator {

    private const val FIELD_OUTER_VERSION = 2
    private const val FIELD_VERSION_DATA = 3

    private const val FIELD_STRING_STRING = 2
    private const val FIELD_STRING_SUBSTRING = 3
    private const val FIELD_STRING_TIMESTAMP = 4
    private const val FIELD_STRING_ATTRIBUTE_RUN = 5

    private const val FIELD_SUBSTRING_CHARID = 1
    private const val FIELD_SUBSTRING_LENGTH = 2
    private const val FIELD_SUBSTRING_TIMESTAMP = 3
    private const val FIELD_SUBSTRING_CHILD = 5

    private const val FIELD_CHARID_REPLICA_ID = 1
    private const val FIELD_CHARID_CLOCK = 2

    private const val FIELD_VT_CLOCK = 1
    private const val FIELD_CLOCK_REPLICA_UUID = 1
    private const val FIELD_CLOCK_REPLICA_CLOCK = 2
    private const val FIELD_RC_CLOCK = 1

    private const val FIELD_ATTR_LENGTH = 1

    /** Returns the base64-encoded, gzipped TextDataEncrypted bytes. */
    fun buildEmptyNoteB64(ourReplicaUuid: ByteArray, noteText: String): String {
        require(ourReplicaUuid.size == 16)
        val proto = buildEmptyNoteBytes(ourReplicaUuid, noteText)
        val gz = Gzip.compress(proto, CompressionFormat.GZIP)
        return Base64.encode(gz)
    }

    fun buildEmptyNoteBytes(ourReplicaUuid: ByteArray, noteText: String): ByteArray {
        val n = noteText.length // UTF-16 code units

        // --- topotext.String fields ---
        val stringFields = mutableListOf<ProtobufWire.Field>()

        // field 2: string (the visible text)
        stringFields.add(ProtobufWire.Field(
            FIELD_STRING_STRING, ProtobufWire.WIRE_LENGTH_DELIM,
            noteText.encodeToByteArray(),
        ))

        // field 3: substrings — doc-start, content, sentinel
        // doc-start: charID=(0,0), length=0, child=[1]
        stringFields.add(buildSubstring(0L, 0L, 0L, 0L, 0L, listOf(1)))
        // content: charID=(1,0), length=n, timestamp=(1,0), child=[2]
        if (n > 0) {
            stringFields.add(buildSubstring(1L, 0L, n.toLong(), 1L, 0L, listOf(2)))
        }
        // sentinel: charID=(0, MAX), length=0
        stringFields.add(buildSubstring(0L, 0xFFFFFFFFL, 0L, 0L, 0xFFFFFFFFL, emptyList()))
        // (If n==0, we still need a doc-start linked to sentinel via child=[1].
        // Skip the content substring; doc-start.child=[1] now points at sentinel.)

        // field 4: VectorTimestamp with one Clock entry (us)
        val nextCharIDClock = if (n > 0) n.toLong() else 0L
        val nextTimestampClock = if (n > 0) 1L else 0L
        val clockFields = listOf(
            ProtobufWire.Field(FIELD_CLOCK_REPLICA_UUID, ProtobufWire.WIRE_LENGTH_DELIM, ourReplicaUuid),
            ProtobufWire.Field(
                FIELD_CLOCK_REPLICA_CLOCK, ProtobufWire.WIRE_LENGTH_DELIM,
                ProtobufWire.encode(listOf(ProtobufWire.encodeVarintField(FIELD_RC_CLOCK, nextCharIDClock))),
            ),
            ProtobufWire.Field(
                FIELD_CLOCK_REPLICA_CLOCK, ProtobufWire.WIRE_LENGTH_DELIM,
                ProtobufWire.encode(listOf(ProtobufWire.encodeVarintField(FIELD_RC_CLOCK, nextTimestampClock))),
            ),
        )
        val vtFields = listOf(
            ProtobufWire.Field(FIELD_VT_CLOCK, ProtobufWire.WIRE_LENGTH_DELIM, ProtobufWire.encode(clockFields)),
        )
        stringFields.add(ProtobufWire.Field(
            FIELD_STRING_TIMESTAMP, ProtobufWire.WIRE_LENGTH_DELIM,
            ProtobufWire.encode(vtFields),
        ))

        // field 5: AttributeRun covering full text (if any)
        if (n > 0) {
            stringFields.add(ProtobufWire.Field(
                FIELD_STRING_ATTRIBUTE_RUN, ProtobufWire.WIRE_LENGTH_DELIM,
                ProtobufWire.encode(listOf(ProtobufWire.encodeVarintField(FIELD_ATTR_LENGTH, n.toLong()))),
            ))
        }

        val topotextStringBytes = ProtobufWire.encode(stringFields)

        // --- versioned_document.Version (data + serializationVersion) ---
        val versionFields = listOf(
            ProtobufWire.encodeVarintField(2, 0L), // minimumSupportedVersion = 0
            ProtobufWire.Field(FIELD_VERSION_DATA, ProtobufWire.WIRE_LENGTH_DELIM, topotextStringBytes),
        )
        val versionBytes = ProtobufWire.encode(versionFields)

        // --- versioned_document.Document ---
        val topFields = listOf(
            ProtobufWire.Field(FIELD_OUTER_VERSION, ProtobufWire.WIRE_LENGTH_DELIM, versionBytes),
        )
        return ProtobufWire.encode(topFields)
    }

    private fun buildSubstring(
        replicaID: Long, clock: Long,
        length: Long,
        timestampReplicaID: Long, timestampClock: Long,
        children: List<Int>,
    ): ProtobufWire.Field {
        val charID = ProtobufWire.encode(listOf(
            ProtobufWire.encodeVarintField(FIELD_CHARID_REPLICA_ID, replicaID),
            ProtobufWire.encodeVarintField(FIELD_CHARID_CLOCK, clock),
        ))
        val timestamp = ProtobufWire.encode(listOf(
            ProtobufWire.encodeVarintField(FIELD_CHARID_REPLICA_ID, timestampReplicaID),
            ProtobufWire.encodeVarintField(FIELD_CHARID_CLOCK, timestampClock),
        ))
        val fields = mutableListOf<ProtobufWire.Field>()
        fields.add(ProtobufWire.Field(FIELD_SUBSTRING_CHARID, ProtobufWire.WIRE_LENGTH_DELIM, charID))
        fields.add(ProtobufWire.encodeVarintField(FIELD_SUBSTRING_LENGTH, length))
        fields.add(ProtobufWire.Field(FIELD_SUBSTRING_TIMESTAMP, ProtobufWire.WIRE_LENGTH_DELIM, timestamp))
        for (c in children) {
            fields.add(ProtobufWire.encodeVarintField(FIELD_SUBSTRING_CHILD, c.toLong()))
        }
        return ProtobufWire.Field(
            FIELD_STRING_SUBSTRING, ProtobufWire.WIRE_LENGTH_DELIM,
            ProtobufWire.encode(fields),
        )
    }
}
