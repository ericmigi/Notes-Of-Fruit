package com.example.applenotes.proto

import android.util.Log
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

private const val TAG = "AppleNotesAppender"

/**
 * Append plain text to the end of a Note's body proto, respecting Apple Notes'
 * "topotext" sequence CRDT.
 *
 * For each character appended:
 *  - note_text (Note.field 2) grows by N UTF-8 bytes.
 *  - A NEW run op is INSERTED before the sentinel (Note.field 3, repeated):
 *      { from = (myReplicaIdx, myCounter),    // CharIDs of the new run
 *        count = N,
 *        to   = (anchorReplicaIdx, anchorClock),
 *        seq  = maxSeqInDoc + 1 }
 *    The "to" anchors the new run to the last visible CharID in the document
 *    (= the last CharID of the penultimate op). For an empty doc, anchor to
 *    (0, 0) — the implicit document-start sentinel.
 *  - Replica registry (Note.field 4): if our UUID is already registered, bump
 *    our entry's first counter by N. If not, append a fresh entry at the end
 *    of the list (UUID + counter=0), THEN bump it by N. Our 1-based replica
 *    index is the position in the entry list.
 *  - attribute_run (Note.field 5): N new entries appended, each length=1 with
 *    the font UUID copied from any existing run.
 *
 * What the previous version got wrong: it bumped the FIRST replica's counter
 * (which was almost certainly a Mac/iPhone, not us) and built ops with
 * `replicaIdx = 1` hardcoded. That forged Mac's identity in the CRDT history;
 * Mac eventually noticed and refused to merge, leaving devices diverged.
 */
@OptIn(ExperimentalEncodingApi::class)
object NoteAppender {

    private const val FIELD_NOTESTOREPROTO_DOCUMENT = 2
    private const val FIELD_DOCUMENT_NOTE = 3

    private const val FIELD_NOTE_TEXT = 2
    private const val FIELD_NOTE_OPS = 3
    private const val FIELD_NOTE_REPLICAS = 4
    private const val FIELD_NOTE_ATTRIBUTE_RUN = 5

    private const val FIELD_OP_FROM = 1
    private const val FIELD_OP_COUNT = 2
    private const val FIELD_OP_TO = 3
    private const val FIELD_OP_SEQ = 5

    private const val FIELD_CHARID_REPLICA = 1
    private const val FIELD_CHARID_CLOCK = 2

    // Note.replicas is a wrapper message; each replica entry uses field number 1
    // in the wrapper. Inside an entry: field 1 = UUID (16 bytes), field 2
    // (repeated, length-delim) = counter blocks. Inside a counter block:
    // field 1 (varint) = counter value.
    private const val FIELD_REPLICAS_LIST_ENTRY = 1
    private const val FIELD_REPLICA_ENTRY_UUID = 1
    private const val FIELD_REPLICA_ENTRY_COUNTER_BLOCK = 2
    private const val FIELD_COUNTER_BLOCK_VALUE = 1

    private const val FIELD_ATTR_LENGTH = 1
    private const val FIELD_ATTR_FONT = 2
    private const val FIELD_FONT_UUID = 9
    private const val FIELD_ATTR_TIMESTAMP = 13

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
        noteStoreProtoBytes: ByteArray,
        ourReplicaUuid: ByteArray,
        text: String,
        nowEpochSec: Long,
    ): ByteArray {
        require(text.isNotEmpty()) { "Empty append" }
        require(ourReplicaUuid.size == 16) { "ourReplicaUuid must be 16 bytes" }

        val kind = NoteBodyEditor.probe(noteStoreProtoBytes)
        require(kind == NoteProtoKind.NOTE_STORE_PROTO) {
            "Cannot append to a $kind note (modern collaborative MergableData). Not yet supported."
        }

        // ----- Decode top-level: NoteStoreProto → Document → Note -----
        val top = ProtobufWire.decode(noteStoreProtoBytes).toMutableList()
        val docIdx = top.indexOfFirst {
            it.fieldNumber == FIELD_NOTESTOREPROTO_DOCUMENT && it.wireType == ProtobufWire.WIRE_LENGTH_DELIM
        }
        require(docIdx >= 0) { "Missing NoteStoreProto.Document" }
        val docFields = ProtobufWire.decode(top[docIdx].payload).toMutableList()
        val noteIdx = docFields.indexOfFirst {
            it.fieldNumber == FIELD_DOCUMENT_NOTE && it.wireType == ProtobufWire.WIRE_LENGTH_DELIM
        }
        require(noteIdx >= 0) { "Missing Document.Note" }
        val noteFields = ProtobufWire.decode(docFields[noteIdx].payload).toMutableList()

        val n = text.length // Kotlin String.length is # of UTF-16 code units (= NSString length)
        val newBytes = text.encodeToByteArray()

        // ----- Step 1: scan replica registry, find-or-register OUR identity -----
        val replicasIdx = noteFields.indexOfFirst {
            it.fieldNumber == FIELD_NOTE_REPLICAS && it.wireType == ProtobufWire.WIRE_LENGTH_DELIM
        }
        require(replicasIdx >= 0) { "Note has no replicas field (field 4)" }
        val replicaList = ProtobufWire.decode(noteFields[replicasIdx].payload).toMutableList()
        val scan = scanReplicas(replicaList, ourReplicaUuid)

        var ourReplicaIndex = scan.ourIndex1Based
        var ourCounter = scan.ourCounter
        val ourEntryListIdx: Int

        if (scan.ourIndex1Based < 0) {
            // Register at the end of the registry. Match Mac's entry shape EXACTLY:
            // two counter blocks, both initialized to 0. (Real Mac entries always
            // have two; emitting a single block makes Apple's renderer reject the
            // proto even when the bytes round-trip through CloudKit storage.)
            val counterBlockZero = ProtobufWire.encode(
                listOf(ProtobufWire.encodeVarintField(FIELD_COUNTER_BLOCK_VALUE, 0L)),
            )
            val newEntryFields = listOf(
                ProtobufWire.Field(FIELD_REPLICA_ENTRY_UUID, ProtobufWire.WIRE_LENGTH_DELIM, ourReplicaUuid),
                ProtobufWire.Field(FIELD_REPLICA_ENTRY_COUNTER_BLOCK, ProtobufWire.WIRE_LENGTH_DELIM, counterBlockZero),
                ProtobufWire.Field(FIELD_REPLICA_ENTRY_COUNTER_BLOCK, ProtobufWire.WIRE_LENGTH_DELIM, counterBlockZero),
            )
            val newEntry = ProtobufWire.Field(
                FIELD_REPLICAS_LIST_ENTRY,
                ProtobufWire.WIRE_LENGTH_DELIM,
                ProtobufWire.encode(newEntryFields),
            )
            ourEntryListIdx = replicaList.size
            replicaList.add(newEntry)
            ourReplicaIndex = scan.totalEntries + 1
            ourCounter = 0L
            Log.i(
                TAG,
                "REGISTERED new replica: idx=$ourReplicaIndex uuid=${hex(ourReplicaUuid)} " +
                    "(coexists with ${scan.totalEntries} existing replicas)",
            )
        } else {
            ourEntryListIdx = scan.ourListIdx
            Log.i(
                TAG,
                "MATCHED existing replica: idx=$ourReplicaIndex counter=$ourCounter " +
                    "uuid=${hex(ourReplicaUuid)} (this device has edited this note before)",
            )
        }

        // ----- Step 2: compute max seq + last visible CharID across the op log -----
        val opEntries = noteFields.withIndex()
            .filter { it.value.fieldNumber == FIELD_NOTE_OPS && it.value.wireType == ProtobufWire.WIRE_LENGTH_DELIM }
        require(opEntries.isNotEmpty()) { "Note has no ops at all (no sentinel)" }

        var maxSeq = 0L
        for (entry in opEntries) {
            val sub = ProtobufWire.decode(entry.value.payload)
            val seq = sub.firstOrNull {
                it.fieldNumber == FIELD_OP_SEQ && it.wireType == ProtobufWire.WIRE_VARINT
            }?.let { ProtobufWire.decodeVarint(it) } ?: 0L
            if (seq > maxSeq) maxSeq = seq
        }

        // Gate: we only know how to extend notes that have exactly one existing
        // replica with the canonical Mac shape (two counter blocks). Anything else
        // (no replicas, multiple replicas, single-counter-block entries) is
        // unfamiliar territory we'd corrupt.
        val nonUsEntries = scan.allEntries.filter { !it.uuid.contentEquals(ourReplicaUuid) }
        require(nonUsEntries.size == 1 && nonUsEntries[0].counters.size >= 2) {
            "Refusing write: expected exactly one non-us replica with two counter " +
                "blocks. Got ${nonUsEntries.size} non-us entries with counters=" +
                nonUsEntries.map { it.counters }
        }
        val existing = nonUsEntries[0]
        // Mac's anchor convention (observed empirically across multiple notes):
        //   to.clock = counter2_of_authoring_replica - 1
        // Every Mac op uses this same anchor cluster; counter2 advances as Mac
        // makes new edits. We mimic that: anchor our new run to the same CharID,
        // and set our own counter2 to anchorClock + 1 after the write so any
        // future op we author lands at the same end-of-doc marker.
        val anchorReplica = existing.index.toLong()
        val anchorClock = existing.counters[1] - 1
        require(anchorClock >= 0) {
            "Existing replica has counter2=${existing.counters[1]} which would yield " +
                "a negative anchor clock"
        }

        // Inspect every op so unknown/extra fields (e.g. f4 we've seen on real
        // Mac ops) are visible. Cheap; turns into one log line per op.
        for ((idx, op) in opEntries.withIndex()) {
            val label = when (idx) {
                0 -> "op[0]/doc-start"
                opEntries.size - 1 -> "op[${idx}]/sentinel"
                else -> "op[$idx]"
            }
            logOp(label, op.value)
        }
        Log.i(
            TAG,
            "topo: ourReplica=$ourReplicaIndex ourCounter=$ourCounter " +
                "anchor=($anchorReplica,$anchorClock) maxSeq=$maxSeq append.n=$n",
        )

        // ----- Step 3: build the new run op (NEW op, not a bump of an existing one) -----
        val fromCharId = listOf(
            ProtobufWire.encodeVarintField(FIELD_CHARID_REPLICA, ourReplicaIndex.toLong()),
            ProtobufWire.encodeVarintField(FIELD_CHARID_CLOCK, ourCounter),
        )
        val toCharId = listOf(
            ProtobufWire.encodeVarintField(FIELD_CHARID_REPLICA, anchorReplica),
            ProtobufWire.encodeVarintField(FIELD_CHARID_CLOCK, anchorClock),
        )
        val opFields = listOf(
            ProtobufWire.Field(FIELD_OP_FROM, ProtobufWire.WIRE_LENGTH_DELIM, ProtobufWire.encode(fromCharId)),
            ProtobufWire.encodeVarintField(FIELD_OP_COUNT, n.toLong()),
            ProtobufWire.Field(FIELD_OP_TO, ProtobufWire.WIRE_LENGTH_DELIM, ProtobufWire.encode(toCharId)),
            ProtobufWire.encodeVarintField(FIELD_OP_SEQ, maxSeq + 1),
        )
        val newOp = ProtobufWire.Field(
            FIELD_NOTE_OPS,
            ProtobufWire.WIRE_LENGTH_DELIM,
            ProtobufWire.encode(opFields),
        )

        // ----- Step 4: update OUR replica's counters -----
        // counter1 (chars authored by us) += n
        // counter2 (max to-clock used by us + 1) = anchorClock + 1
        // CRITICAL: do this BEFORE inserting the new op so the in-place write
        // to noteFields[replicasIdx] uses a valid index. Inserting the op
        // shifts every noteFields index >= sentinelIdx by +1, including
        // replicasIdx (which sits at Note.field 4, after Note.field 3 ops).
        val newCounter1 = ourCounter + n
        val newCounter2 = anchorClock + 1
        val ourEntry = replicaList[ourEntryListIdx]
        val es = ProtobufWire.decode(ourEntry.payload).toMutableList()
        val counterBlockListIndices = es.withIndex().filter {
            it.value.fieldNumber == FIELD_REPLICA_ENTRY_COUNTER_BLOCK &&
                it.value.wireType == ProtobufWire.WIRE_LENGTH_DELIM
        }.map { it.index }
        // We register with two counter blocks; existing entries also have two.
        check(counterBlockListIndices.size >= 2) {
            "Our replica entry should have at least 2 counter blocks; found " +
                counterBlockListIndices.size
        }
        es[counterBlockListIndices[0]] = es[counterBlockListIndices[0]].copy(
            payload = ProtobufWire.encode(
                listOf(ProtobufWire.encodeVarintField(FIELD_COUNTER_BLOCK_VALUE, newCounter1)),
            ),
        )
        es[counterBlockListIndices[1]] = es[counterBlockListIndices[1]].copy(
            payload = ProtobufWire.encode(
                listOf(ProtobufWire.encodeVarintField(FIELD_COUNTER_BLOCK_VALUE, newCounter2)),
            ),
        )
        replicaList[ourEntryListIdx] = ourEntry.copy(payload = ProtobufWire.encode(es))
        noteFields[replicasIdx] = noteFields[replicasIdx].copy(payload = ProtobufWire.encode(replicaList))
        Log.i(TAG, "ourCounters: c1=$newCounter1 c2=$newCounter2")

        // ----- Step 5: append to note_text (UTF-8 bytes) -----
        // Modifies noteFields[noteTextIdx] in place at field 2, which is BEFORE
        // both ops (field 3) and replicas (field 4) so its index never shifts.
        val noteTextIdx = noteFields.indexOfFirst {
            it.fieldNumber == FIELD_NOTE_TEXT && it.wireType == ProtobufWire.WIRE_LENGTH_DELIM
        }
        require(noteTextIdx >= 0) { "Missing Note.note_text" }
        val oldText = noteFields[noteTextIdx].payload
        noteFields[noteTextIdx] = noteFields[noteTextIdx].copy(payload = oldText + newBytes)

        // ----- Step 6: insert the new op before the sentinel -----
        // (Doing this LAST among the index-shifting mutations means later code
        // doesn't need to recompute replicasIdx / noteTextIdx.)
        val sentinelNoteFieldIdx = opEntries.last().index
        noteFields.add(sentinelNoteFieldIdx, newOp)

        // ----- Step 7: append n attribute_run entries (one per code unit) -----
        // Adds at the END of noteFields, doesn't shift any earlier index.
        val fontUuid = findFontUuid(noteFields) ?: ByteArray(16)
        repeat(n) { i ->
            noteFields.add(buildAttributeRun(length = 1, fontUuid = fontUuid, ts = nowEpochSec + i))
        }

        // ----- Step 8: re-encode bottom-up -----
        docFields[noteIdx] = docFields[noteIdx].copy(payload = ProtobufWire.encode(noteFields))
        top[docIdx] = top[docIdx].copy(payload = ProtobufWire.encode(docFields))
        return ProtobufWire.encode(top)
    }


    private fun logOp(label: String, op: ProtobufWire.Field) {
        val sub = ProtobufWire.decode(op.payload)
        val from = sub.firstOrNull {
            it.fieldNumber == FIELD_OP_FROM && it.wireType == ProtobufWire.WIRE_LENGTH_DELIM
        }?.let { ProtobufWire.decode(it.payload) }
        val fromRid = from?.firstOrNull {
            it.fieldNumber == FIELD_CHARID_REPLICA && it.wireType == ProtobufWire.WIRE_VARINT
        }?.let { ProtobufWire.decodeVarint(it) }
        val fromOff = from?.firstOrNull {
            it.fieldNumber == FIELD_CHARID_CLOCK && it.wireType == ProtobufWire.WIRE_VARINT
        }?.let { ProtobufWire.decodeVarint(it) }
        val count = sub.firstOrNull {
            it.fieldNumber == FIELD_OP_COUNT && it.wireType == ProtobufWire.WIRE_VARINT
        }?.let { ProtobufWire.decodeVarint(it) }
        val to = sub.firstOrNull {
            it.fieldNumber == FIELD_OP_TO && it.wireType == ProtobufWire.WIRE_LENGTH_DELIM
        }?.let { ProtobufWire.decode(it.payload) }
        val toRid = to?.firstOrNull {
            it.fieldNumber == FIELD_CHARID_REPLICA && it.wireType == ProtobufWire.WIRE_VARINT
        }?.let { ProtobufWire.decodeVarint(it) }
        val toOff = to?.firstOrNull {
            it.fieldNumber == FIELD_CHARID_CLOCK && it.wireType == ProtobufWire.WIRE_VARINT
        }?.let { ProtobufWire.decodeVarint(it) }
        val seq = sub.firstOrNull {
            it.fieldNumber == FIELD_OP_SEQ && it.wireType == ProtobufWire.WIRE_VARINT
        }?.let { ProtobufWire.decodeVarint(it) }
        val unknownFields = sub.filter {
            it.fieldNumber !in setOf(FIELD_OP_FROM, FIELD_OP_COUNT, FIELD_OP_TO, FIELD_OP_SEQ)
        }.map { "f${it.fieldNumber}/w${it.wireType}/${it.payload.size}b" }
        Log.i(
            TAG,
            "$label op: from=($fromRid,$fromOff) count=$count to=($toRid,$toOff) seq=$seq " +
                "extra=$unknownFields",
        )
    }

    private data class ReplicaSnapshot(
        /** 1-based replica index (entry position in the registry). */
        val index: Int,
        val uuid: ByteArray,
        /** All counter-block field-1 values, in order. Mac always emits two. */
        val counters: List<Long>,
    )

    private data class ReplicaScan(
        /** Position in `replicaList` (the wrapper's internal slot) of OUR entry, or -1. */
        val ourListIdx: Int,
        /** 1-based replica index (entry position) of OUR entry, or -1 if not found. */
        val ourIndex1Based: Int,
        /** Current counter[0] value for OUR entry, or 0 if not found. */
        val ourCounter: Long,
        /** Total number of replica entries in the registry. */
        val totalEntries: Int,
        /** All entries' parsed snapshots — used by callers needing the registry shape. */
        val allEntries: List<ReplicaSnapshot>,
    )

    /**
     * Scan `Note.replicas` once: log every entry's UUID and counter, detect
     * duplicate-UUID corruption (refuses if found), and locate OUR entry by
     * UUID. Returns a snapshot we can act on.
     */
    private fun scanReplicas(
        replicaList: List<ProtobufWire.Field>,
        ourUuid: ByteArray,
    ): ReplicaScan {
        var ourListIdx = -1
        var ourIndex = -1
        var ourCounter = 0L
        var entriesSeen = 0
        val sb = StringBuilder()
        val seenUuids = HashMap<String, Int>()
        val allEntries = mutableListOf<ReplicaSnapshot>()
        for ((listIdx, f) in replicaList.withIndex()) {
            if (f.fieldNumber != FIELD_REPLICAS_LIST_ENTRY ||
                f.wireType != ProtobufWire.WIRE_LENGTH_DELIM
            ) continue
            entriesSeen++
            val es = ProtobufWire.decode(f.payload)
            val uuidField = es.firstOrNull {
                it.fieldNumber == FIELD_REPLICA_ENTRY_UUID &&
                    it.wireType == ProtobufWire.WIRE_LENGTH_DELIM
            }
            val uuidBytes = uuidField?.payload ?: ByteArray(0)
            val uuidHex = if (uuidBytes.isEmpty()) "<no-uuid>" else hex(uuidBytes)
            // Collect ALL counter-block values (Mac emits two; ours starts as two zeroes).
            val counters = es.filter {
                it.fieldNumber == FIELD_REPLICA_ENTRY_COUNTER_BLOCK &&
                    it.wireType == ProtobufWire.WIRE_LENGTH_DELIM
            }.map { cb ->
                ProtobufWire.decode(cb.payload).firstOrNull { fld ->
                    fld.fieldNumber == FIELD_COUNTER_BLOCK_VALUE &&
                        fld.wireType == ProtobufWire.WIRE_VARINT
                }?.let { ProtobufWire.decodeVarint(it) } ?: 0L
            }
            val isUs = uuidBytes.contentEquals(ourUuid)
            sb.append("  #").append(entriesSeen).append(" uuid=").append(uuidHex)
                .append(" counters=").append(counters)
            if (isUs) sb.append("  <-- US")
            sb.append('\n')

            seenUuids[uuidHex]?.let { prev ->
                error(
                    "Replica registry corruption: UUID $uuidHex appears at entries " +
                        "$prev and $entriesSeen — refusing to write to avoid making it worse",
                )
            }
            seenUuids[uuidHex] = entriesSeen

            allEntries.add(ReplicaSnapshot(entriesSeen, uuidBytes, counters))

            if (isUs) {
                ourListIdx = listIdx
                ourIndex = entriesSeen
                ourCounter = counters.firstOrNull() ?: 0L
            }
        }
        Log.i(
            TAG,
            "Replica scan: ${entriesSeen} existing entries, ourUuid=${hex(ourUuid)}\n$sb" +
                "  decision=" + if (ourIndex < 0) "register-new" else "match-existing#$ourIndex",
        )
        return ReplicaScan(ourListIdx, ourIndex, ourCounter, entriesSeen, allEntries)
    }

    private fun findFontUuid(noteFields: List<ProtobufWire.Field>): ByteArray? {
        for (f in noteFields) {
            if (f.fieldNumber != FIELD_NOTE_ATTRIBUTE_RUN || f.wireType != ProtobufWire.WIRE_LENGTH_DELIM) continue
            val subs = ProtobufWire.decode(f.payload)
            val fontField = subs.firstOrNull {
                it.fieldNumber == FIELD_ATTR_FONT && it.wireType == ProtobufWire.WIRE_LENGTH_DELIM
            } ?: continue
            val fontSubs = ProtobufWire.decode(fontField.payload)
            val uuidField = fontSubs.firstOrNull {
                it.fieldNumber == FIELD_FONT_UUID && it.wireType == ProtobufWire.WIRE_LENGTH_DELIM
            }
            if (uuidField != null && uuidField.payload.size == 16) return uuidField.payload
        }
        return null
    }

    private fun buildAttributeRun(length: Int, fontUuid: ByteArray, ts: Long): ProtobufWire.Field {
        val fontInner = listOf(
            ProtobufWire.Field(FIELD_FONT_UUID, ProtobufWire.WIRE_LENGTH_DELIM, fontUuid),
        )
        val runFields = listOf(
            ProtobufWire.encodeVarintField(FIELD_ATTR_LENGTH, length.toLong()),
            ProtobufWire.Field(FIELD_ATTR_FONT, ProtobufWire.WIRE_LENGTH_DELIM, ProtobufWire.encode(fontInner)),
            ProtobufWire.encodeVarintField(FIELD_ATTR_TIMESTAMP, ts),
        )
        return ProtobufWire.Field(
            FIELD_NOTE_ATTRIBUTE_RUN,
            ProtobufWire.WIRE_LENGTH_DELIM,
            ProtobufWire.encode(runFields),
        )
    }

    private fun hex(b: ByteArray): String =
        b.joinToString("") { ((it.toInt() and 0xFF) + 0x100).toString(16).substring(1) }
}
