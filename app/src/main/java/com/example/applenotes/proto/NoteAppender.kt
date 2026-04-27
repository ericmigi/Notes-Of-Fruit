package com.example.applenotes.proto

import android.util.Log
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

private const val TAG = "AppleNotesAppender"

/**
 * Append plain text to the end of a Note's body proto using the in-session
 * extension pattern observed in iCloud Notes.
 *
 * For each character appended:
 *  - note_text (Note.field 2) grows by N UTF-8 bytes
 *  - CRDT op log (Note.field 3): the LAST non-sentinel op's `f2` (count varint)
 *    increments by N. No new op created.
 *  - Replica registry (Note.field 4): editing replica's first counter += N
 *  - attribute_run (Note.field 5): N new entries appended, each
 *      { field 1 = 1, field 2 = { field 9 = <font UUID copied from existing> },
 *        field 13 = <current Unix epoch seconds> }
 *
 * `ReplicaIDToNotesVersionDataEncrypted` (the sibling CloudKit field) does NOT
 * need to be touched — it stays unchanged on body edits, per RE.
 */
@OptIn(ExperimentalEncodingApi::class)
object NoteAppender {

    private const val FIELD_NOTESTOREPROTO_DOCUMENT = 2
    private const val FIELD_DOCUMENT_NOTE = 3
    private const val FIELD_NOTE_TEXT = 2
    private const val FIELD_NOTE_OPS = 3
    private const val FIELD_NOTE_REPLICAS = 4
    private const val FIELD_NOTE_ATTRIBUTE_RUN = 5

    private const val FIELD_OP_F2_COUNT = 2
    private const val FIELD_OP_F1_FROM_OFFSET_INT = 4294967295L  // sentinel marker

    private const val FIELD_REPLICA_ENTRY = 1
    private const val FIELD_REPLICA_COUNTER_BLOCK = 2
    private const val FIELD_REPLICA_COUNTER_VALUE = 1

    private const val FIELD_ATTR_LENGTH = 1
    private const val FIELD_ATTR_FONT = 2
    private const val FIELD_FONT_UUID = 9
    private const val FIELD_ATTR_TIMESTAMP = 13

    /** Decode → mutate → re-encode. Throws if the proto shape doesn't match expectations. */
    fun appendBase64(textDataEncryptedB64: String, text: String, nowEpochSec: Long): String {
        require(text.isNotEmpty()) { "Empty append" }
        val compressed = Base64.decode(textDataEncryptedB64)
        val proto = Gzip.decompress(compressed)
        Log.i(TAG, "appendBase64 IN: text='${text.take(40)}' textLen=${text.length} protoLen=${proto.size}")
        val newProto = appendBytes(proto, text, nowEpochSec)
        val newCompressed = Gzip.compress(newProto)
        val newB64 = Base64.encode(newCompressed)
        Log.i(TAG, "appendBase64 OUT: protoLen=${newProto.size} b64Len=${newB64.length}")
        return newB64
    }

    /** Operates on the decompressed proto bytes. Exposed for testing. */
    fun appendBytes(noteStoreProtoBytes: ByteArray, text: String, nowEpochSec: Long): ByteArray {
        val top = ProtobufWire.decode(noteStoreProtoBytes)
        val docFieldIdx = top.indexOfFirst {
            it.fieldNumber == FIELD_NOTESTOREPROTO_DOCUMENT && it.wireType == ProtobufWire.WIRE_LENGTH_DELIM
        }
        require(docFieldIdx >= 0) { "Missing top-level Document field" }
        val docFields = ProtobufWire.decode(top[docFieldIdx].payload).toMutableList()

        val noteIdx = docFields.indexOfFirst {
            it.fieldNumber == FIELD_DOCUMENT_NOTE && it.wireType == ProtobufWire.WIRE_LENGTH_DELIM
        }
        require(noteIdx >= 0) { "Missing Document.Note field" }
        val noteFields = ProtobufWire.decode(docFields[noteIdx].payload).toMutableList()

        val n = text.length
        val newBytes = text.encodeToByteArray()

        // 1. Append to note_text (Note.field 2, string)
        val noteTextIdx = noteFields.indexOfFirst {
            it.fieldNumber == FIELD_NOTE_TEXT && it.wireType == ProtobufWire.WIRE_LENGTH_DELIM
        }
        require(noteTextIdx >= 0) { "Missing Note.note_text field" }
        val oldNoteText = noteFields[noteTextIdx].payload
        val newNoteText = oldNoteText + newBytes
        noteFields[noteTextIdx] = noteFields[noteTextIdx].copy(payload = newNoteText)
        Log.i(TAG, "step 1: note_text ${oldNoteText.size} -> ${newNoteText.size} bytes")

        // 2. Bump f2 of LAST non-sentinel CRDT op (Note.field 3)
        val opIndices = noteFields.withIndex()
            .filter { it.value.fieldNumber == FIELD_NOTE_OPS && it.value.wireType == ProtobufWire.WIRE_LENGTH_DELIM }
            .map { it.index }
        require(opIndices.size >= 2) { "Need at least 2 ops (one + sentinel), have ${opIndices.size}" }
        // Sentinel is the last; we want the one before.
        val targetOpIdx = opIndices[opIndices.size - 2]
        val opSubfields = ProtobufWire.decode(noteFields[targetOpIdx].payload).toMutableList()
        val f2Idx = opSubfields.indexOfFirst {
            it.fieldNumber == FIELD_OP_F2_COUNT && it.wireType == ProtobufWire.WIRE_VARINT
        }
        require(f2Idx >= 0) { "Op missing field 2 (count)" }
        val oldF2 = ProtobufWire.decodeVarint(opSubfields[f2Idx])
        val newF2 = oldF2 + n
        opSubfields[f2Idx] = ProtobufWire.encodeVarintField(FIELD_OP_F2_COUNT, newF2)
        noteFields[targetOpIdx] = noteFields[targetOpIdx].copy(payload = ProtobufWire.encode(opSubfields))
        Log.i(TAG, "step 2: last op f2 $oldF2 -> $newF2")

        // 3. Bump first replica's first counter in Note.field 4
        val replicasIdx = noteFields.indexOfFirst {
            it.fieldNumber == FIELD_NOTE_REPLICAS && it.wireType == ProtobufWire.WIRE_LENGTH_DELIM
        }
        require(replicasIdx >= 0) { "Missing Note.replicas field" }
        val replicaFields = ProtobufWire.decode(noteFields[replicasIdx].payload).toMutableList()
        val firstReplicaEntryIdx = replicaFields.indexOfFirst {
            it.fieldNumber == FIELD_REPLICA_ENTRY && it.wireType == ProtobufWire.WIRE_LENGTH_DELIM
        }
        require(firstReplicaEntryIdx >= 0) { "No replicas registered" }
        val replicaEntrySubfields = ProtobufWire.decode(replicaFields[firstReplicaEntryIdx].payload).toMutableList()
        val firstCounterBlockIdx = replicaEntrySubfields.indexOfFirst {
            it.fieldNumber == FIELD_REPLICA_COUNTER_BLOCK && it.wireType == ProtobufWire.WIRE_LENGTH_DELIM
        }
        require(firstCounterBlockIdx >= 0) { "Replica entry has no counter block" }
        val counterFields = ProtobufWire.decode(replicaEntrySubfields[firstCounterBlockIdx].payload).toMutableList()
        val counterValueIdx = counterFields.indexOfFirst {
            it.fieldNumber == FIELD_REPLICA_COUNTER_VALUE && it.wireType == ProtobufWire.WIRE_VARINT
        }
        require(counterValueIdx >= 0) { "Counter block has no value field" }
        val oldCounter = ProtobufWire.decodeVarint(counterFields[counterValueIdx])
        val newCounter = oldCounter + n
        counterFields[counterValueIdx] = ProtobufWire.encodeVarintField(FIELD_REPLICA_COUNTER_VALUE, newCounter)
        replicaEntrySubfields[firstCounterBlockIdx] =
            replicaEntrySubfields[firstCounterBlockIdx].copy(payload = ProtobufWire.encode(counterFields))
        replicaFields[firstReplicaEntryIdx] =
            replicaFields[firstReplicaEntryIdx].copy(payload = ProtobufWire.encode(replicaEntrySubfields))
        noteFields[replicasIdx] = noteFields[replicasIdx].copy(payload = ProtobufWire.encode(replicaFields))
        Log.i(TAG, "step 3: replica1 counter[0] $oldCounter -> $newCounter")

        // 4. Get font UUID from any existing attribute_run; fallback to zeros if none.
        val existingAttrRunIndices = noteFields.withIndex()
            .filter { it.value.fieldNumber == FIELD_NOTE_ATTRIBUTE_RUN && it.value.wireType == ProtobufWire.WIRE_LENGTH_DELIM }
            .map { it.index }
        val fontUuid: ByteArray = run {
            for (idx in existingAttrRunIndices) {
                val subs = ProtobufWire.decode(noteFields[idx].payload)
                val fontField = subs.firstOrNull {
                    it.fieldNumber == FIELD_ATTR_FONT && it.wireType == ProtobufWire.WIRE_LENGTH_DELIM
                } ?: continue
                val fontSubs = ProtobufWire.decode(fontField.payload)
                val uuidField = fontSubs.firstOrNull {
                    it.fieldNumber == FIELD_FONT_UUID && it.wireType == ProtobufWire.WIRE_LENGTH_DELIM
                }
                if (uuidField != null && uuidField.payload.size == 16) {
                    return@run uuidField.payload
                }
            }
            ByteArray(16)
        }
        Log.i(TAG, "step 4: font UUID hex=${fontUuid.joinToString("") { ((it.toInt() and 0xFF) + 0x100).toString(16).substring(1) }}")

        // 5. Append N new attribute_run entries.
        repeat(n) { i ->
            val newRun = buildAttributeRun(length = 1, fontUuid = fontUuid, ts = nowEpochSec + i)
            noteFields.add(newRun)
        }
        Log.i(TAG, "step 5: appended $n attribute_run entries")

        // 6. Re-encode bottom-up.
        docFields[noteIdx] = docFields[noteIdx].copy(payload = ProtobufWire.encode(noteFields))
        top[docFieldIdx] = top[docFieldIdx].copy(payload = ProtobufWire.encode(docFields))
        return ProtobufWire.encode(top)
    }

    /**
     * Rewrite the body to `newText` while preserving the existing replicas + sentinel
     * structure. Used when the user makes a non-append edit (mid-string, delete, etc.)
     * where simple `f2++` won't work.
     *
     * Strategy: keep field 4 (replicas) intact, drop all field 3 ops EXCEPT the sentinel,
     * and prepend ONE new run op that "owns" the entire new text. That makes our replica
     * the sole author of the document from iCloud Notes' perspective. Other devices
     * that try to merge will see a clean linear text — they may diverge but at minimum
     * it renders cleanly.
     */
    fun rewriteToTextBase64(textDataEncryptedB64: String, newText: String, nowEpochSec: Long): String {
        val compressed = Base64.decode(textDataEncryptedB64)
        val proto = Gzip.decompress(compressed)
        Log.i(TAG, "rewriteToTextBase64 IN: newLen=${newText.length} protoLen=${proto.size}")
        val newProto = rewriteToTextBytes(proto, newText, nowEpochSec)
        val newCompressed = Gzip.compress(newProto)
        val newB64 = Base64.encode(newCompressed)
        Log.i(TAG, "rewriteToTextBase64 OUT: protoLen=${newProto.size} b64Len=${newB64.length}")
        return newB64
    }

    fun rewriteToTextBytes(noteStoreProtoBytes: ByteArray, newText: String, nowEpochSec: Long): ByteArray {
        val top = ProtobufWire.decode(noteStoreProtoBytes)
        val docFieldIdx = top.indexOfFirst {
            it.fieldNumber == FIELD_NOTESTOREPROTO_DOCUMENT && it.wireType == ProtobufWire.WIRE_LENGTH_DELIM
        }
        require(docFieldIdx >= 0) { "Missing top-level Document field" }
        val docFields = ProtobufWire.decode(top[docFieldIdx].payload).toMutableList()
        val noteIdx = docFields.indexOfFirst {
            it.fieldNumber == FIELD_DOCUMENT_NOTE && it.wireType == ProtobufWire.WIRE_LENGTH_DELIM
        }
        require(noteIdx >= 0) { "Missing Document.Note field" }
        val noteFields = ProtobufWire.decode(docFields[noteIdx].payload).toMutableList()

        val n = newText.length
        val newBytes = newText.encodeToByteArray()

        // 1. Replace note_text wholesale.
        val noteTextIdx = noteFields.indexOfFirst {
            it.fieldNumber == FIELD_NOTE_TEXT && it.wireType == ProtobufWire.WIRE_LENGTH_DELIM
        }
        require(noteTextIdx >= 0) { "Missing Note.note_text field" }
        noteFields[noteTextIdx] = noteFields[noteTextIdx].copy(payload = newBytes)
        Log.i(TAG, "rewrite step 1: note_text -> ${newBytes.size} bytes")

        // 2. Drop all field 3 (CRDT op log) entries. Keep one new run op + the sentinel.
        // Capture the sentinel before we drop, so we can put it back at the same relative position.
        val opEntries = noteFields.withIndex()
            .filter { it.value.fieldNumber == FIELD_NOTE_OPS && it.value.wireType == ProtobufWire.WIRE_LENGTH_DELIM }
        val sentinelOpField = opEntries.lastOrNull()?.value
            ?: error("No CRDT ops found at all — note proto is unexpected")
        // Verify it really is the sentinel.
        require(ProtobufWire.encode(listOf(sentinelOpField)).any { true }) { "sentinel detection failed" }
        val firstOpInsertionIdx = opEntries.first().index
        // Remove ALL existing op entries (we'll re-insert).
        for (entry in opEntries.reversed()) {
            noteFields.removeAt(entry.index)
        }
        // Build a new run op that owns all `n` chars: from=(replica1, oldCounter), f2=n, to=(0,0), seq=1
        // We'll figure out replica1's counter[0] by reading field 4 below; for now use placeholder
        // and patch after step 3.
        Log.i(TAG, "rewrite step 2: dropped ${opEntries.size} ops")

        // 3. Read replica 1 counter[0] (so the new op references valid offsets), then bump it by n.
        val replicasIdx = noteFields.indexOfFirst {
            it.fieldNumber == FIELD_NOTE_REPLICAS && it.wireType == ProtobufWire.WIRE_LENGTH_DELIM
        }
        require(replicasIdx >= 0) { "Missing Note.replicas field" }
        val replicaFields = ProtobufWire.decode(noteFields[replicasIdx].payload).toMutableList()
        val firstReplicaEntryIdx = replicaFields.indexOfFirst {
            it.fieldNumber == FIELD_REPLICA_ENTRY && it.wireType == ProtobufWire.WIRE_LENGTH_DELIM
        }
        require(firstReplicaEntryIdx >= 0) { "No replicas registered" }
        val replicaEntrySubfields = ProtobufWire.decode(replicaFields[firstReplicaEntryIdx].payload).toMutableList()
        val firstCounterBlockIdx = replicaEntrySubfields.indexOfFirst {
            it.fieldNumber == FIELD_REPLICA_COUNTER_BLOCK && it.wireType == ProtobufWire.WIRE_LENGTH_DELIM
        }
        require(firstCounterBlockIdx >= 0) { "Replica entry has no counter block" }
        val counterFields = ProtobufWire.decode(replicaEntrySubfields[firstCounterBlockIdx].payload).toMutableList()
        val counterValueIdx = counterFields.indexOfFirst {
            it.fieldNumber == FIELD_REPLICA_COUNTER_VALUE && it.wireType == ProtobufWire.WIRE_VARINT
        }
        require(counterValueIdx >= 0) { "Counter block has no value field" }
        val oldCounter = ProtobufWire.decodeVarint(counterFields[counterValueIdx])
        val newCounter = oldCounter + n
        counterFields[counterValueIdx] = ProtobufWire.encodeVarintField(FIELD_REPLICA_COUNTER_VALUE, newCounter)
        replicaEntrySubfields[firstCounterBlockIdx] =
            replicaEntrySubfields[firstCounterBlockIdx].copy(payload = ProtobufWire.encode(counterFields))
        replicaFields[firstReplicaEntryIdx] =
            replicaFields[firstReplicaEntryIdx].copy(payload = ProtobufWire.encode(replicaEntrySubfields))
        noteFields[replicasIdx] = noteFields[replicasIdx].copy(payload = ProtobufWire.encode(replicaFields))
        Log.i(TAG, "rewrite step 3: replica1 counter[0] $oldCounter -> $newCounter")

        // 4. Insert the new run op BEFORE the sentinel, plus the sentinel itself.
        // The replica index in ops is 1-indexed (first replica = 1).
        val newRunOp = buildRunOp(replicaIdx = 1, fromOffset = oldCounter, length = n.toLong(), seq = 1)
        // Insert at the position where the original first op was (which is now empty).
        // After the removal, that idx still points to a sensible insertion point.
        noteFields.add(firstOpInsertionIdx, newRunOp)
        noteFields.add(firstOpInsertionIdx + 1, sentinelOpField)
        Log.i(TAG, "rewrite step 4: inserted 1 new run op + sentinel")

        // 5. Drop ALL existing attribute_run entries, append N new ones (single run = single style).
        val existingAttrRunIndices = noteFields.withIndex()
            .filter { it.value.fieldNumber == FIELD_NOTE_ATTRIBUTE_RUN && it.value.wireType == ProtobufWire.WIRE_LENGTH_DELIM }
            .map { it.index }
        // Capture font UUID before removing.
        val fontUuid: ByteArray = run {
            for (idx in existingAttrRunIndices) {
                val subs = ProtobufWire.decode(noteFields[idx].payload)
                val fontField = subs.firstOrNull {
                    it.fieldNumber == FIELD_ATTR_FONT && it.wireType == ProtobufWire.WIRE_LENGTH_DELIM
                } ?: continue
                val fontSubs = ProtobufWire.decode(fontField.payload)
                val uuidField = fontSubs.firstOrNull {
                    it.fieldNumber == FIELD_FONT_UUID && it.wireType == ProtobufWire.WIRE_LENGTH_DELIM
                }
                if (uuidField != null && uuidField.payload.size == 16) {
                    return@run uuidField.payload
                }
            }
            ByteArray(16)
        }
        for (idx in existingAttrRunIndices.reversed()) {
            noteFields.removeAt(idx)
        }
        // Append N new entries (one per char).
        repeat(n) { i ->
            noteFields.add(buildAttributeRun(length = 1, fontUuid = fontUuid, ts = nowEpochSec + i))
        }
        Log.i(TAG, "rewrite step 5: dropped ${existingAttrRunIndices.size} attribute_run, appended $n")

        // 6. Re-encode bottom-up.
        docFields[noteIdx] = docFields[noteIdx].copy(payload = ProtobufWire.encode(noteFields))
        top[docFieldIdx] = top[docFieldIdx].copy(payload = ProtobufWire.encode(docFields))
        return ProtobufWire.encode(top)
    }

    /**
     * Build a CRDT op shaped like the ones we observed:
     *   { field 1 = {field 1 = replicaIdx, field 2 = fromOffset},
     *     field 2 = length,
     *     field 3 = {field 1 = replicaIdx, field 2 = 0},
     *     field 5 = seq }
     */
    private fun buildRunOp(replicaIdx: Int, fromOffset: Long, length: Long, seq: Long): ProtobufWire.Field {
        val from = listOf(
            ProtobufWire.encodeVarintField(1, replicaIdx.toLong()),
            ProtobufWire.encodeVarintField(2, fromOffset),
        )
        val to = listOf(
            ProtobufWire.encodeVarintField(1, replicaIdx.toLong()),
            ProtobufWire.encodeVarintField(2, 0L),
        )
        val opFields = listOf(
            ProtobufWire.Field(1, ProtobufWire.WIRE_LENGTH_DELIM, ProtobufWire.encode(from)),
            ProtobufWire.encodeVarintField(2, length),
            ProtobufWire.Field(3, ProtobufWire.WIRE_LENGTH_DELIM, ProtobufWire.encode(to)),
            ProtobufWire.encodeVarintField(5, seq),
        )
        return ProtobufWire.Field(
            FIELD_NOTE_OPS,
            ProtobufWire.WIRE_LENGTH_DELIM,
            ProtobufWire.encode(opFields),
        )
    }

    private fun buildAttributeRun(length: Int, fontUuid: ByteArray, ts: Long): ProtobufWire.Field {
        // Inner: { field 9 = <16-byte uuid> }
        val fontInner = listOf(
            ProtobufWire.Field(FIELD_FONT_UUID, ProtobufWire.WIRE_LENGTH_DELIM, fontUuid),
        )
        // attribute_run: { field 1 = length, field 2 = font, field 13 = ts }
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
}
