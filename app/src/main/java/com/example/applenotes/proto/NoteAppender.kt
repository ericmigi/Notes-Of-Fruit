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
            // Our UUID is NOT in the registry — register at the end. The new
            // entry's 1-based position becomes our replica index, and existing
            // replicas keep their indexes (since we append).
            val counterBlock = listOf(ProtobufWire.encodeVarintField(FIELD_COUNTER_BLOCK_VALUE, 0L))
            val newEntryFields = listOf(
                ProtobufWire.Field(
                    FIELD_REPLICA_ENTRY_UUID,
                    ProtobufWire.WIRE_LENGTH_DELIM,
                    ourReplicaUuid,
                ),
                ProtobufWire.Field(
                    FIELD_REPLICA_ENTRY_COUNTER_BLOCK,
                    ProtobufWire.WIRE_LENGTH_DELIM,
                    ProtobufWire.encode(counterBlock),
                ),
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

        // The sentinel is the last op. Anchor the new run to the LAST CharID of
        // the penultimate op (the most recently authored character). If the only
        // op is the sentinel, anchor to (0, 0) which is the document-start.
        var anchorReplica = 0L
        var anchorClock = 0L
        if (opEntries.size >= 2) {
            val penultimate = opEntries[opEntries.size - 2].value
            val sub = ProtobufWire.decode(penultimate.payload)
            val from = sub.firstOrNull {
                it.fieldNumber == FIELD_OP_FROM && it.wireType == ProtobufWire.WIRE_LENGTH_DELIM
            }?.let { ProtobufWire.decode(it.payload) }
            val rid = from?.firstOrNull {
                it.fieldNumber == FIELD_CHARID_REPLICA && it.wireType == ProtobufWire.WIRE_VARINT
            }?.let { ProtobufWire.decodeVarint(it) } ?: 0L
            val off = from?.firstOrNull {
                it.fieldNumber == FIELD_CHARID_CLOCK && it.wireType == ProtobufWire.WIRE_VARINT
            }?.let { ProtobufWire.decodeVarint(it) } ?: 0L
            val count = sub.firstOrNull {
                it.fieldNumber == FIELD_OP_COUNT && it.wireType == ProtobufWire.WIRE_VARINT
            }?.let { ProtobufWire.decodeVarint(it) } ?: 0L
            anchorReplica = rid
            anchorClock = if (count > 0) off + count - 1 else off
        }
        // Multi-replica safety check: anchoring to the penultimate op's last CharID
        // is only sound when the op-log order is also the visible (RGA) order — that
        // holds for single-replica notes and notes only ever edited by us. Once two
        // replicas have non-zero counters, log positions can diverge from visible
        // positions and "penultimate's end" can be wrong. Refuse rather than corrupt.
        val replicasWithEdits = countReplicasWithEdits(replicaList, ourReplicaUuid)
        require(replicasWithEdits <= 1) {
            "Note has $replicasWithEdits other replicas with edits — multi-replica " +
                "append needs proper RGA traversal which isn't implemented yet. " +
                "Refusing to write to avoid CRDT corruption."
        }
        // Inspect the sentinel before trusting it as "always last": log its shape so
        // anomalies are visible. (We don't fail closed yet — we don't have a verified
        // canonical sentinel encoding to compare against.)
        logOp("sentinel(last)", opEntries.last().value)
        if (opEntries.size >= 2) {
            logOp("penultimate", opEntries[opEntries.size - 2].value)
        }
        Log.i(
            TAG,
            "topo: ourReplica=$ourReplicaIndex ourCounter=$ourCounter " +
                "anchor=($anchorReplica,$anchorClock) maxSeq=$maxSeq append.n=$n " +
                "replicasWithEdits=$replicasWithEdits",
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

        // ----- Step 4: bump OUR replica's counter by n -----
        // CRITICAL: do this BEFORE inserting the new op, so we can address the
        // replicas field by its still-valid `replicasIdx`. Inserting the new op
        // shifts every noteFields index >= sentinelIdx by +1; the replicas field
        // (Note.field 4) sits AFTER the ops (Note.field 3) in canonical proto
        // ordering, so its index would shift. Mutate it first, op-insert last.
        val ourEntry = replicaList[ourEntryListIdx]
        val es = ProtobufWire.decode(ourEntry.payload).toMutableList()
        val cbIdx = es.indexOfFirst {
            it.fieldNumber == FIELD_REPLICA_ENTRY_COUNTER_BLOCK && it.wireType == ProtobufWire.WIRE_LENGTH_DELIM
        }
        val newCounter = ourCounter + n
        if (cbIdx >= 0) {
            val cs = ProtobufWire.decode(es[cbIdx].payload).toMutableList()
            val cvIdx = cs.indexOfFirst {
                it.fieldNumber == FIELD_COUNTER_BLOCK_VALUE && it.wireType == ProtobufWire.WIRE_VARINT
            }
            if (cvIdx >= 0) {
                cs[cvIdx] = ProtobufWire.encodeVarintField(FIELD_COUNTER_BLOCK_VALUE, newCounter)
            } else {
                cs.add(0, ProtobufWire.encodeVarintField(FIELD_COUNTER_BLOCK_VALUE, newCounter))
            }
            es[cbIdx] = es[cbIdx].copy(payload = ProtobufWire.encode(cs))
        } else {
            val cb = listOf(ProtobufWire.encodeVarintField(FIELD_COUNTER_BLOCK_VALUE, newCounter))
            es.add(
                ProtobufWire.Field(
                    FIELD_REPLICA_ENTRY_COUNTER_BLOCK,
                    ProtobufWire.WIRE_LENGTH_DELIM,
                    ProtobufWire.encode(cb),
                ),
            )
        }
        replicaList[ourEntryListIdx] = ourEntry.copy(payload = ProtobufWire.encode(es))
        noteFields[replicasIdx] = noteFields[replicasIdx].copy(payload = ProtobufWire.encode(replicaList))

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

    /**
     * Number of replicas other than us that have a non-zero counter — i.e. that
     * have actually authored characters. Used to gate the multi-replica safety
     * check: 0 means a fresh note, 1 means another device created it (and we're
     * about to be the second replica), >1 means concurrent multi-device editing
     * which the current append-anchor heuristic can't handle.
     */
    private fun countReplicasWithEdits(
        replicaList: List<ProtobufWire.Field>,
        ourUuid: ByteArray,
    ): Int {
        var count = 0
        for (f in replicaList) {
            if (f.fieldNumber != FIELD_REPLICAS_LIST_ENTRY ||
                f.wireType != ProtobufWire.WIRE_LENGTH_DELIM
            ) continue
            val es = ProtobufWire.decode(f.payload)
            val uuidField = es.firstOrNull {
                it.fieldNumber == FIELD_REPLICA_ENTRY_UUID &&
                    it.wireType == ProtobufWire.WIRE_LENGTH_DELIM
            }
            if (uuidField?.payload?.contentEquals(ourUuid) == true) continue
            val counter = es.firstOrNull {
                it.fieldNumber == FIELD_REPLICA_ENTRY_COUNTER_BLOCK &&
                    it.wireType == ProtobufWire.WIRE_LENGTH_DELIM
            }?.let {
                ProtobufWire.decode(it.payload).firstOrNull { fld ->
                    fld.fieldNumber == FIELD_COUNTER_BLOCK_VALUE &&
                        fld.wireType == ProtobufWire.WIRE_VARINT
                }?.let { ProtobufWire.decodeVarint(it) }
            } ?: 0L
            if (counter > 0) count++
        }
        return count
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

    private data class ReplicaScan(
        /** Position in `replicaList` (the wrapper's internal slot) of OUR entry, or -1. */
        val ourListIdx: Int,
        /** 1-based replica index (entry position) of OUR entry, or -1 if not found. */
        val ourIndex1Based: Int,
        /** Current counter[0] value for OUR entry, or 0 if not found. */
        val ourCounter: Long,
        /** Total number of replica entries in the registry. */
        val totalEntries: Int,
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
            val uuidBytes = uuidField?.payload
            val uuidHex = uuidBytes?.let { hex(it) } ?: "<no-uuid>"
            val cb = es.firstOrNull {
                it.fieldNumber == FIELD_REPLICA_ENTRY_COUNTER_BLOCK &&
                    it.wireType == ProtobufWire.WIRE_LENGTH_DELIM
            }
            val counter = cb?.let {
                ProtobufWire.decode(it.payload).firstOrNull { fld ->
                    fld.fieldNumber == FIELD_COUNTER_BLOCK_VALUE &&
                        fld.wireType == ProtobufWire.WIRE_VARINT
                }?.let { ProtobufWire.decodeVarint(it) }
            } ?: 0L
            val isUs = uuidBytes?.contentEquals(ourUuid) == true
            sb.append("  #").append(entriesSeen).append(" uuid=").append(uuidHex)
                .append(" counter=").append(counter)
            if (isUs) sb.append("  <-- US")
            sb.append('\n')

            seenUuids[uuidHex]?.let { prev ->
                error(
                    "Replica registry corruption: UUID $uuidHex appears at entries " +
                        "$prev and $entriesSeen — refusing to write to avoid making it worse",
                )
            }
            seenUuids[uuidHex] = entriesSeen

            if (isUs) {
                ourListIdx = listIdx
                ourIndex = entriesSeen
                ourCounter = counter
            }
        }
        Log.i(
            TAG,
            "Replica scan: ${entriesSeen} existing entries, ourUuid=${hex(ourUuid)}\n$sb" +
                "  decision=" + if (ourIndex < 0) "register-new" else "match-existing#$ourIndex",
        )
        return ReplicaScan(ourListIdx, ourIndex, ourCounter, entriesSeen)
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
