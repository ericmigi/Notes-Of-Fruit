package com.example.applenotes.proto

import android.util.Log
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

private const val TAG = "AppleNotesAppender"

/**
 * Append plain text to the end of a Note's body proto, respecting Apple Notes'
 * actual `topotext.String` schema (extracted from the iCloud Notes web bundle).
 *
 * The proto we're operating on is `topotext.String`:
 *
 *     message String {
 *         optional string string = 2;
 *         repeated Substring substring = 3;
 *         optional VectorTimestamp timestamp = 4;
 *         repeated AttributeRun attributeRun = 5;
 *         repeated Attachment attachment = 6;
 *     }
 *
 *     message Substring {
 *         optional CharID charID = 1;        // the run's first char
 *         optional uint32 length = 2;        // run length, UTF-16 code units
 *         optional CharID timestamp = 3;     // STYLE timestamp (NOT a tree anchor)
 *         optional bool tombstone = 4;       // true => deleted
 *         repeated uint32 child = 5;         // INDICES into String.substring
 *     }
 *
 * The RGA tree is encoded entirely via `Substring.child` — those are indices
 * into the `substring` repeated field, defining a tree of runs. Visible text =
 * walk the tree (depth-first), output non-tombstoned chars in order.
 *
 * `VectorTimestamp` (field 4) tracks per-replica clocks:
 *
 *     message VectorTimestamp {
 *         message Clock {
 *             optional bytes replicaUUID = 1;
 *             repeated ReplicaClock replicaClock = 2;
 *         }
 *         repeated Clock clock = 1;
 *     }
 *     message ReplicaClock { uint32 clock = 1; uint32 subclock = 2; }
 *
 * Each replica gets ONE Clock entry. By Apple's convention there are TWO
 * ReplicaClock entries: the first holds the replica's next CharID clock to
 * allocate, the second holds the next style-timestamp clock. (Empirically
 * verified across multiple notes — entries' first ReplicaClock value matches
 * `max(charID.clock) + 1` for that replica, second matches `max(timestamp.clock) + 1`.)
 *
 * The replica's INDEX (used as `CharID.replicaID`) is its 1-based position in
 * the `Clock` list. Our previous code wrote `replicaID = 1` claiming to be Mac.
 * That was the bug.
 *
 * What an end-append looks like in this schema:
 *
 *  - There is always a doc-start substring at array index 0 with charID=(0,0)
 *    and a sentinel substring with charID=(0, 4294967295) somewhere in the array.
 *  - Every existing substring forms a chain via `child[0]` pointers ending at
 *    the sentinel. Exactly one substring's `child` list contains the sentinel
 *    index — call that the "tail" substring.
 *  - To append, insert ourselves between the tail and the sentinel:
 *       - Add a new Substring at array end with charID=(myReplica, 0..n-1),
 *         a fresh style timestamp, tombstone=false, child=[sentinelIdx].
 *       - Rewrite the tail's child list to swap sentinelIdx for our new index.
 *  - Update VectorTimestamp: find or register our Clock entry; bump its
 *    ReplicaClock[0].clock by n (chars consumed) and ReplicaClock[1].clock by 1
 *    (style timestamp consumed).
 *  - Append n bytes to String.string and add an AttributeRun of length n.
 */
@OptIn(ExperimentalEncodingApi::class)
object NoteAppender {

    // Outer wrapper is versioned_document.Document → Version → bytes(=topotext.String).
    // In wire form (matching what we see): outer.field 2 = Version,
    // Version.field 3 = bytes data (the topotext.String).
    private const val FIELD_OUTER_VERSION = 2
    private const val FIELD_VERSION_DATA = 3

    // topotext.String fields
    private const val FIELD_STRING_STRING = 2
    private const val FIELD_STRING_SUBSTRING = 3
    private const val FIELD_STRING_TIMESTAMP = 4
    private const val FIELD_STRING_ATTRIBUTE_RUN = 5

    // Substring fields
    private const val FIELD_SUBSTRING_CHARID = 1
    private const val FIELD_SUBSTRING_LENGTH = 2
    private const val FIELD_SUBSTRING_TIMESTAMP = 3
    private const val FIELD_SUBSTRING_TOMBSTONE = 4
    private const val FIELD_SUBSTRING_CHILD = 5

    // CharID fields
    private const val FIELD_CHARID_REPLICA_ID = 1
    private const val FIELD_CHARID_CLOCK = 2

    // VectorTimestamp fields
    private const val FIELD_VT_CLOCK = 1

    // VectorTimestamp.Clock fields
    private const val FIELD_CLOCK_REPLICA_UUID = 1
    private const val FIELD_CLOCK_REPLICA_CLOCK = 2

    // ReplicaClock fields
    private const val FIELD_RC_CLOCK = 1
    private const val FIELD_RC_SUBCLOCK = 2

    // AttributeRun fields (a tiny subset; see /tmp/apple-notes-real.proto for full schema)
    private const val FIELD_ATTR_LENGTH = 1
    private const val FIELD_ATTR_FONT = 3
    private const val FIELD_FONT_NAME = 1
    private const val FIELD_FONT_POINT_SIZE = 2

    private const val SENTINEL_REPLICA_ID = 0L
    private const val SENTINEL_CLOCK = 0xFFFFFFFFL

    fun appendBase64(
        textDataEncryptedB64: String,
        ourReplicaUuid: ByteArray,
        text: String,
        nowEpochSec: Long,
    ): String {
        require(text.isNotEmpty()) { "Empty append" }
        require(ourReplicaUuid.size == 16) {
            "ourReplicaUuid must be 16 bytes, got ${ourReplicaUuid.size}"
        }
        val compressed = Base64.decode(textDataEncryptedB64)
        val proto = Gzip.decompress(compressed)
        Log.i(TAG, "appendBase64 IN: textLen=${text.length} protoLen=${proto.size}")
        Log.i(TAG, "appendBase64 IN  proto: ${NoteBodyEditor.summarize(proto)}")
        val newProto = appendBytes(proto, ourReplicaUuid, text, nowEpochSec)
        Log.i(TAG, "appendBase64 OUT proto: ${NoteBodyEditor.summarize(newProto)}")
        val newCompressed = Gzip.compress(newProto)
        val newB64 = Base64.encode(newCompressed)
        Log.i(TAG, "appendBase64 OUT: protoLen=${newProto.size} b64Len=${newB64.length}")
        return newB64
    }

    fun appendBytes(
        protoBytes: ByteArray,
        ourReplicaUuid: ByteArray,
        text: String,
        nowEpochSec: Long,
    ): ByteArray {
        require(text.isNotEmpty()) { "Empty append" }
        require(ourReplicaUuid.size == 16) { "ourReplicaUuid must be 16 bytes" }

        // ----- Decode the wrapper (versioned_document.Document → Version) -----
        // The proto says `repeated Version version = 2`. We refuse to operate on
        // a doc with multiple Version entries — picking the wrong one would update
        // a stale snapshot while the live data lives elsewhere. Real notes we've
        // observed only ever ship one.
        val top = ProtobufWire.decode(protoBytes).toMutableList()
        val versionFieldIndices = top.withIndex()
            .filter { it.value.fieldNumber == FIELD_OUTER_VERSION && it.value.wireType == ProtobufWire.WIRE_LENGTH_DELIM }
            .map { it.index }
        require(versionFieldIndices.size == 1) {
            "Outer doc has ${versionFieldIndices.size} Version entries; expected exactly 1"
        }
        val versionIdx = versionFieldIndices[0]
        val versionFields = ProtobufWire.decode(top[versionIdx].payload).toMutableList()
        val dataIdx = versionFields.indexOfFirst {
            it.fieldNumber == FIELD_VERSION_DATA && it.wireType == ProtobufWire.WIRE_LENGTH_DELIM
        }
        require(dataIdx >= 0) { "Missing Version.data field" }
        val stringFields = ProtobufWire.decode(versionFields[dataIdx].payload).toMutableList()

        // Refuse anything that looks like the modern collaborative MergableData shape.
        // (`probe()` checks for the absence of String.string-as-text inside Note.note_text;
        // for end-append on legacy topotext.String we want it to be NOTE_STORE_PROTO.)
        val kind = NoteBodyEditor.probe(protoBytes)
        require(kind == NoteProtoKind.NOTE_STORE_PROTO) {
            "Cannot append to a $kind body. Modern collaborative notes use a different proto."
        }

        // ----- Parse all substrings -----
        val substrings = parseSubstrings(stringFields)
        require(substrings.isNotEmpty()) { "topotext.String has no substrings at all" }

        // Find the sentinel by its CharID (0, 0xFFFFFFFF).
        val sentinelArrayIdx = substrings.indexOfFirst {
            it.charIDReplicaID == SENTINEL_REPLICA_ID && it.charIDClock == SENTINEL_CLOCK
        }
        require(sentinelArrayIdx >= 0) {
            "No sentinel substring (charID=(0, 0xFFFFFFFF)) found"
        }

        // Find the unique tail: the substring whose child[] contains sentinelArrayIdx.
        val tails = substrings.filter { sentinelArrayIdx in it.children }
        require(tails.size == 1) {
            "Expected exactly 1 tail substring pointing to the sentinel; found ${tails.size}. " +
                "Concurrent edits or unfamiliar tree shape — refusing."
        }
        val tail = tails[0]

        // ----- Find or register OUR replica in VectorTimestamp -----
        val timestampNoteFieldIdx = stringFields.indexOfFirst {
            it.fieldNumber == FIELD_STRING_TIMESTAMP && it.wireType == ProtobufWire.WIRE_LENGTH_DELIM
        }
        require(timestampNoteFieldIdx >= 0) { "topotext.String has no .timestamp (VectorTimestamp)" }
        val vtFields = ProtobufWire.decode(stringFields[timestampNoteFieldIdx].payload).toMutableList()

        val clocks = parseVectorTimestamp(vtFields)

        // Refuse on duplicate UUIDs — replicaID is positional, so duplicates make
        // CharID.replicaID ambiguous.
        val seen = HashMap<String, Int>()
        for (c in clocks) {
            val key = hex(c.uuid)
            seen[key]?.let { prev ->
                error(
                    "VectorTimestamp corruption: UUID $key appears at replicaID $prev and ${c.replicaID}",
                )
            }
            seen[key] = c.replicaID
        }

        val matches = clocks.filter { it.uuid.contentEquals(ourReplicaUuid) }
        require(matches.size <= 1) {
            "Multiple Clock entries match our UUID — refusing"
        }
        val ourClock = matches.firstOrNull()

        val ourReplicaID: Int
        val ourCharIDClock: Long
        val ourTimestampClock: Long
        val ourClockListIdx: Int
        val isFirstEdit: Boolean

        if (ourClock != null) {
            // Existing replicas always have exactly two ReplicaClock entries
            // (Apple's convention: index 0 = next charID clock, index 1 = next
            // style timestamp). Anything else is a shape we don't understand —
            // refuse rather than silently default to zero (would re-allocate
            // CharIDs we'd already issued).
            require(ourClock.replicaClocks.size == 2) {
                "Our existing Clock has ${ourClock.replicaClocks.size} ReplicaClock " +
                    "entries; expected 2"
            }
            ourReplicaID = ourClock.replicaID
            ourCharIDClock = ourClock.replicaClocks[0].clock
            ourTimestampClock = ourClock.replicaClocks[1].clock
            ourClockListIdx = ourClock.vtFieldIdx
            isFirstEdit = false
            Log.i(
                TAG,
                "MATCHED our replica: rid=$ourReplicaID nextCharIDClock=$ourCharIDClock " +
                    "nextTimestampClock=$ourTimestampClock",
            )
        } else {
            ourReplicaID = clocks.size + 1
            ourCharIDClock = 0L
            ourTimestampClock = 0L
            ourClockListIdx = vtFields.size
            isFirstEdit = true
            // Append a fresh Clock entry: { uuid, replicaClock=[{clock=0},{clock=0}] }
            vtFields.add(buildClockEntry(ourReplicaUuid, 0L, 0L))
            Log.i(
                TAG,
                "REGISTERED our replica: rid=$ourReplicaID uuid=${hex(ourReplicaUuid)} " +
                    "(coexists with ${clocks.size} existing replicas)",
            )
        }

        // ----- Build our new Substring -----
        val n = text.length // UTF-16 code units (= NSString length)
        val ourArrayIdx = substrings.size // index in substring[] AFTER we append

        val ourSubstring = buildSubstring(
            charIDReplicaID = ourReplicaID.toLong(),
            charIDClock = ourCharIDClock,
            length = n.toLong(),
            timestampReplicaID = ourReplicaID.toLong(),
            timestampClock = ourTimestampClock,
            tombstone = false,
            children = listOf(sentinelArrayIdx),
        )
        Log.i(
            TAG,
            "topo: ourReplica=$ourReplicaID ourCharIDClock=$ourCharIDClock " +
                "ourTimestampClock=$ourTimestampClock n=$n  tail.arrayIdx=${tail.arrayIdx} " +
                "tail.children=${tail.children} sentinel.arrayIdx=$sentinelArrayIdx " +
                "ourArrayIdx=$ourArrayIdx",
        )

        // ----- Rewrite the tail's child list: replace sentinelIdx with ourArrayIdx -----
        val tailNoteFieldIdx = tail.noteFieldIdx
        val tailFields = ProtobufWire.decode(stringFields[tailNoteFieldIdx].payload).toMutableList()
        rewriteSubstringChildren(tailFields) { children ->
            children.map { if (it == sentinelArrayIdx) ourArrayIdx else it }
        }
        stringFields[tailNoteFieldIdx] =
            stringFields[tailNoteFieldIdx].copy(payload = ProtobufWire.encode(tailFields))

        // ----- Update OUR Clock's ReplicaClock entries (clock by n, timestamp by 1) -----
        val newCharIDClock = ourCharIDClock + n
        val newTimestampClock = ourTimestampClock + 1
        vtFields[ourClockListIdx] = if (isFirstEdit) {
            // Just registered — the entry we built already has clocks=[0,0].
            // Replace it with the bumped values. No prior subclock/unknown fields
            // to preserve since we just constructed it.
            buildClockEntry(ourReplicaUuid, newCharIDClock, newTimestampClock)
        } else {
            // In-place update: change only the `clock` varint of each ReplicaClock,
            // preserving subclock + any unknown fields Apple may add later.
            updateExistingClockInPlace(
                vtFields[ourClockListIdx],
                newCharIDClock,
                newTimestampClock,
            )
        }
        stringFields[timestampNoteFieldIdx] =
            stringFields[timestampNoteFieldIdx].copy(payload = ProtobufWire.encode(vtFields))

        // ----- Append our text to String.string -----
        val stringFieldIdx = stringFields.indexOfFirst {
            it.fieldNumber == FIELD_STRING_STRING && it.wireType == ProtobufWire.WIRE_LENGTH_DELIM
        }
        require(stringFieldIdx >= 0) { "topotext.String has no .string field" }
        stringFields[stringFieldIdx] = stringFields[stringFieldIdx].copy(
            payload = stringFields[stringFieldIdx].payload + text.encodeToByteArray(),
        )

        // ----- Insert our new Substring after the last existing substring -----
        // Doing this AFTER all in-place mutations on stringFields above so the
        // indices we held (timestampNoteFieldIdx, stringFieldIdx, tailNoteFieldIdx)
        // remained valid for those mutations. We don't reference them after this point.
        val lastSubstringNoteFieldIdx = stringFields.withIndex()
            .filter { it.value.fieldNumber == FIELD_STRING_SUBSTRING && it.value.wireType == ProtobufWire.WIRE_LENGTH_DELIM }
            .last().index
        stringFields.add(lastSubstringNoteFieldIdx + 1, ourSubstring)

        // ----- Append a new AttributeRun of length n -----
        // Inherit the LAST existing AttributeRun's font if present (so our chars
        // render in a similar style); otherwise emit a minimal run.
        val templateFont = lastAttributeRunFont(stringFields)
        stringFields.add(buildAttributeRun(length = n, fontFields = templateFont))

        // ----- Re-encode bottom-up -----
        versionFields[dataIdx] = versionFields[dataIdx].copy(payload = ProtobufWire.encode(stringFields))
        top[versionIdx] = top[versionIdx].copy(payload = ProtobufWire.encode(versionFields))
        return ProtobufWire.encode(top)
    }

    // -------- Substring parsing --------

    private data class ParsedSubstring(
        val noteFieldIdx: Int,   // index in stringFields list
        val arrayIdx: Int,        // index in substring repeated field (0-based)
        val charIDReplicaID: Long,
        val charIDClock: Long,
        val length: Long,
        val timestampReplicaID: Long,
        val timestampClock: Long,
        val tombstone: Boolean,
        val children: List<Int>,
    )

    private fun parseSubstrings(stringFields: List<ProtobufWire.Field>): List<ParsedSubstring> {
        val out = mutableListOf<ParsedSubstring>()
        var arrayIdx = 0
        for ((listIdx, f) in stringFields.withIndex()) {
            if (f.fieldNumber != FIELD_STRING_SUBSTRING ||
                f.wireType != ProtobufWire.WIRE_LENGTH_DELIM
            ) continue
            val sub = ProtobufWire.decode(f.payload)
            val charID = sub.firstOrNull {
                it.fieldNumber == FIELD_SUBSTRING_CHARID && it.wireType == ProtobufWire.WIRE_LENGTH_DELIM
            }?.let { ProtobufWire.decode(it.payload) }
            val charIDReplica = charID?.firstOrNull {
                it.fieldNumber == FIELD_CHARID_REPLICA_ID && it.wireType == ProtobufWire.WIRE_VARINT
            }?.let { ProtobufWire.decodeVarint(it) } ?: 0L
            val charIDClock = charID?.firstOrNull {
                it.fieldNumber == FIELD_CHARID_CLOCK && it.wireType == ProtobufWire.WIRE_VARINT
            }?.let { ProtobufWire.decodeVarint(it) } ?: 0L
            val length = sub.firstOrNull {
                it.fieldNumber == FIELD_SUBSTRING_LENGTH && it.wireType == ProtobufWire.WIRE_VARINT
            }?.let { ProtobufWire.decodeVarint(it) } ?: 0L
            val timestamp = sub.firstOrNull {
                it.fieldNumber == FIELD_SUBSTRING_TIMESTAMP && it.wireType == ProtobufWire.WIRE_LENGTH_DELIM
            }?.let { ProtobufWire.decode(it.payload) }
            val tsReplica = timestamp?.firstOrNull {
                it.fieldNumber == FIELD_CHARID_REPLICA_ID && it.wireType == ProtobufWire.WIRE_VARINT
            }?.let { ProtobufWire.decodeVarint(it) } ?: 0L
            val tsClock = timestamp?.firstOrNull {
                it.fieldNumber == FIELD_CHARID_CLOCK && it.wireType == ProtobufWire.WIRE_VARINT
            }?.let { ProtobufWire.decodeVarint(it) } ?: 0L
            val tombstone = sub.firstOrNull {
                it.fieldNumber == FIELD_SUBSTRING_TOMBSTONE && it.wireType == ProtobufWire.WIRE_VARINT
            }?.let { ProtobufWire.decodeVarint(it) != 0L } ?: false
            // child is `repeated uint32` — non-packed (proto2), so multiple field-5 varints.
            val children = sub.filter {
                it.fieldNumber == FIELD_SUBSTRING_CHILD && it.wireType == ProtobufWire.WIRE_VARINT
            }.map { ProtobufWire.decodeVarint(it).toInt() }
            out.add(
                ParsedSubstring(
                    noteFieldIdx = listIdx,
                    arrayIdx = arrayIdx,
                    charIDReplicaID = charIDReplica,
                    charIDClock = charIDClock,
                    length = length,
                    timestampReplicaID = tsReplica,
                    timestampClock = tsClock,
                    tombstone = tombstone,
                    children = children,
                ),
            )
            arrayIdx++
        }
        return out
    }

    private fun rewriteSubstringChildren(
        substringFields: MutableList<ProtobufWire.Field>,
        rewrite: (List<Int>) -> List<Int>,
    ) {
        val oldChildren = substringFields.filter {
            it.fieldNumber == FIELD_SUBSTRING_CHILD && it.wireType == ProtobufWire.WIRE_VARINT
        }.map { ProtobufWire.decodeVarint(it).toInt() }
        val newChildren = rewrite(oldChildren)
        // Remove all existing field-5 entries.
        substringFields.removeAll {
            it.fieldNumber == FIELD_SUBSTRING_CHILD && it.wireType == ProtobufWire.WIRE_VARINT
        }
        // Append the new ones at the end (proto2 repeated allows any position;
        // canonical encoding generally clusters them at the field's logical place).
        for (c in newChildren) {
            substringFields.add(ProtobufWire.encodeVarintField(FIELD_SUBSTRING_CHILD, c.toLong()))
        }
    }

    // -------- VectorTimestamp parsing --------

    private data class ParsedClock(
        val vtFieldIdx: Int,    // index in vtFields list
        val replicaID: Int,      // 1-based position among Clock entries
        val uuid: ByteArray,
        val replicaClocks: List<ParsedReplicaClock>,
    )

    private data class ParsedReplicaClock(val clock: Long, val subclock: Long)

    private fun parseVectorTimestamp(vtFields: List<ProtobufWire.Field>): List<ParsedClock> {
        val out = mutableListOf<ParsedClock>()
        var rid = 0
        for ((listIdx, f) in vtFields.withIndex()) {
            if (f.fieldNumber != FIELD_VT_CLOCK ||
                f.wireType != ProtobufWire.WIRE_LENGTH_DELIM
            ) continue
            rid++
            val clk = ProtobufWire.decode(f.payload)
            val uuid = clk.firstOrNull {
                it.fieldNumber == FIELD_CLOCK_REPLICA_UUID && it.wireType == ProtobufWire.WIRE_LENGTH_DELIM
            }?.payload ?: ByteArray(0)
            val replicaClocks = clk.filter {
                it.fieldNumber == FIELD_CLOCK_REPLICA_CLOCK && it.wireType == ProtobufWire.WIRE_LENGTH_DELIM
            }.map { rcField ->
                val rc = ProtobufWire.decode(rcField.payload)
                val clock = rc.firstOrNull {
                    it.fieldNumber == FIELD_RC_CLOCK && it.wireType == ProtobufWire.WIRE_VARINT
                }?.let { ProtobufWire.decodeVarint(it) } ?: 0L
                val subclock = rc.firstOrNull {
                    it.fieldNumber == FIELD_RC_SUBCLOCK && it.wireType == ProtobufWire.WIRE_VARINT
                }?.let { ProtobufWire.decodeVarint(it) } ?: 0L
                ParsedReplicaClock(clock, subclock)
            }
            out.add(ParsedClock(listIdx, rid, uuid, replicaClocks))
        }
        return out
    }

    // -------- Builders --------

    /**
     * Update only the `clock` varint of each ReplicaClock inside an existing
     * Clock entry. Preserves the UUID, subclock fields, any other ReplicaClock
     * fields we don't recognize, and any unknown fields Apple may add. Caller
     * must guarantee there are exactly 2 ReplicaClock entries.
     */
    private fun updateExistingClockInPlace(
        existingClockField: ProtobufWire.Field,
        newCharIDClock: Long,
        newTimestampClock: Long,
    ): ProtobufWire.Field {
        val clockFields = ProtobufWire.decode(existingClockField.payload).toMutableList()
        val rcIndices = clockFields.withIndex()
            .filter {
                it.value.fieldNumber == FIELD_CLOCK_REPLICA_CLOCK &&
                    it.value.wireType == ProtobufWire.WIRE_LENGTH_DELIM
            }
            .map { it.index }
        check(rcIndices.size == 2) {
            "Expected exactly 2 ReplicaClock entries, found ${rcIndices.size}"
        }
        clockFields[rcIndices[0]] = updateReplicaClockInPlace(clockFields[rcIndices[0]], newCharIDClock)
        clockFields[rcIndices[1]] = updateReplicaClockInPlace(clockFields[rcIndices[1]], newTimestampClock)
        return existingClockField.copy(payload = ProtobufWire.encode(clockFields))
    }

    private fun updateReplicaClockInPlace(
        rcField: ProtobufWire.Field,
        newClock: Long,
    ): ProtobufWire.Field {
        val rcFields = ProtobufWire.decode(rcField.payload).toMutableList()
        val clockIdx = rcFields.indexOfFirst {
            it.fieldNumber == FIELD_RC_CLOCK && it.wireType == ProtobufWire.WIRE_VARINT
        }
        if (clockIdx >= 0) {
            rcFields[clockIdx] = ProtobufWire.encodeVarintField(FIELD_RC_CLOCK, newClock)
        } else {
            rcFields.add(0, ProtobufWire.encodeVarintField(FIELD_RC_CLOCK, newClock))
        }
        return rcField.copy(payload = ProtobufWire.encode(rcFields))
    }

    private fun buildClockEntry(uuid: ByteArray, charIDClock: Long, timestampClock: Long): ProtobufWire.Field {
        // Always emit two ReplicaClocks to match Mac's shape.
        val rc0 = ProtobufWire.encode(
            listOf(ProtobufWire.encodeVarintField(FIELD_RC_CLOCK, charIDClock)),
        )
        val rc1 = ProtobufWire.encode(
            listOf(ProtobufWire.encodeVarintField(FIELD_RC_CLOCK, timestampClock)),
        )
        val clockFields = listOf(
            ProtobufWire.Field(FIELD_CLOCK_REPLICA_UUID, ProtobufWire.WIRE_LENGTH_DELIM, uuid),
            ProtobufWire.Field(FIELD_CLOCK_REPLICA_CLOCK, ProtobufWire.WIRE_LENGTH_DELIM, rc0),
            ProtobufWire.Field(FIELD_CLOCK_REPLICA_CLOCK, ProtobufWire.WIRE_LENGTH_DELIM, rc1),
        )
        return ProtobufWire.Field(
            FIELD_VT_CLOCK,
            ProtobufWire.WIRE_LENGTH_DELIM,
            ProtobufWire.encode(clockFields),
        )
    }

    private fun buildSubstring(
        charIDReplicaID: Long,
        charIDClock: Long,
        length: Long,
        timestampReplicaID: Long,
        timestampClock: Long,
        tombstone: Boolean,
        children: List<Int>,
    ): ProtobufWire.Field {
        val charID = ProtobufWire.encode(
            listOf(
                ProtobufWire.encodeVarintField(FIELD_CHARID_REPLICA_ID, charIDReplicaID),
                ProtobufWire.encodeVarintField(FIELD_CHARID_CLOCK, charIDClock),
            ),
        )
        val timestamp = ProtobufWire.encode(
            listOf(
                ProtobufWire.encodeVarintField(FIELD_CHARID_REPLICA_ID, timestampReplicaID),
                ProtobufWire.encodeVarintField(FIELD_CHARID_CLOCK, timestampClock),
            ),
        )
        val fields = mutableListOf<ProtobufWire.Field>()
        fields.add(ProtobufWire.Field(FIELD_SUBSTRING_CHARID, ProtobufWire.WIRE_LENGTH_DELIM, charID))
        fields.add(ProtobufWire.encodeVarintField(FIELD_SUBSTRING_LENGTH, length))
        fields.add(ProtobufWire.Field(FIELD_SUBSTRING_TIMESTAMP, ProtobufWire.WIRE_LENGTH_DELIM, timestamp))
        if (tombstone) {
            fields.add(ProtobufWire.encodeVarintField(FIELD_SUBSTRING_TOMBSTONE, 1L))
        }
        for (child in children) {
            fields.add(ProtobufWire.encodeVarintField(FIELD_SUBSTRING_CHILD, child.toLong()))
        }
        return ProtobufWire.Field(
            FIELD_STRING_SUBSTRING,
            ProtobufWire.WIRE_LENGTH_DELIM,
            ProtobufWire.encode(fields),
        )
    }

    private fun lastAttributeRunFont(stringFields: List<ProtobufWire.Field>): List<ProtobufWire.Field> {
        // Find the last attribute_run that has a font field, return its font's inner fields.
        val attrRuns = stringFields.filter {
            it.fieldNumber == FIELD_STRING_ATTRIBUTE_RUN && it.wireType == ProtobufWire.WIRE_LENGTH_DELIM
        }
        for (run in attrRuns.reversed()) {
            val sub = ProtobufWire.decode(run.payload)
            val font = sub.firstOrNull {
                it.fieldNumber == FIELD_ATTR_FONT && it.wireType == ProtobufWire.WIRE_LENGTH_DELIM
            } ?: continue
            return ProtobufWire.decode(font.payload)
        }
        return emptyList()
    }

    private fun buildAttributeRun(length: Int, fontFields: List<ProtobufWire.Field>): ProtobufWire.Field {
        val fields = mutableListOf<ProtobufWire.Field>(
            ProtobufWire.encodeVarintField(FIELD_ATTR_LENGTH, length.toLong()),
        )
        if (fontFields.isNotEmpty()) {
            fields.add(
                ProtobufWire.Field(
                    FIELD_ATTR_FONT,
                    ProtobufWire.WIRE_LENGTH_DELIM,
                    ProtobufWire.encode(fontFields),
                ),
            )
        }
        return ProtobufWire.Field(
            FIELD_STRING_ATTRIBUTE_RUN,
            ProtobufWire.WIRE_LENGTH_DELIM,
            ProtobufWire.encode(fields),
        )
    }

    private fun hex(b: ByteArray): String =
        b.joinToString("") { ((it.toInt() and 0xFF) + 0x100).toString(16).substring(1) }
}
