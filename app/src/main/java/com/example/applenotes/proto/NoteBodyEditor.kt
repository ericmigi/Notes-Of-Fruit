package com.example.applenotes.proto

import android.util.Log
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

private const val TAG = "AppleNotesEditor"

/**
 * Top-level shape of Apple Notes' `TextDataEncrypted` bytes.
 *  - NOTE_STORE_PROTO (legacy): Document.Note.note_text + AttributeRun[]
 *  - MERGABLE_DATA   (modern, for collaborative-editable notes): Document.MergeableDataObjectData
 * Both have the same outer envelope so we peek at the inner shape. Editing a
 * MERGABLE_DATA note as if it were NOTE_STORE_PROTO writes garbage into a
 * structured ObjectID/key-item array and corrupts the note.
 */
enum class NoteProtoKind { NOTE_STORE_PROTO, MERGABLE_DATA, UNKNOWN }

/**
 * Round-trips Apple Notes' `TextDataEncrypted` payload to insert text at the
 * top or bottom of the note while keeping all other proto fields (formatting,
 * attachments, etc.) intact.
 *
 * Layout we care about:
 *   NoteStoreProto {
 *     Document document = 2;       // length-delim
 *   }
 *   Document {
 *     int32  version  = 2;          // varint
 *     Note   note     = 3;          // length-delim
 *   }
 *   Note {
 *     string note_text     = 2;     // length-delim (UTF-8)
 *     repeated AttributeRun attribute_run = 5;
 *   }
 *   AttributeRun {
 *     int32 length = 1;             // varint — char count this run covers
 *     // ... lots of optional fields, all preserved untouched
 *   }
 *
 * To insert N characters at the top: prepend the chars to `note_text` and
 * grow the FIRST attribute_run's `length` by N (so the inserted text inherits
 * the formatting of the first character). Mirror for the bottom: append to
 * `note_text`, grow the LAST attribute_run's `length`.
 */
@OptIn(ExperimentalEncodingApi::class)
object NoteBodyEditor {
    private const val FIELD_NOTESTOREPROTO_DOCUMENT = 2
    private const val FIELD_DOCUMENT_NOTE = 3
    private const val FIELD_NOTE_TEXT = 2
    private const val FIELD_NOTE_ATTRIBUTE_RUN = 5
    private const val FIELD_ATTRIBUTERUN_LENGTH = 1

    /** Decode → mutate → re-encode the note's TextDataEncrypted (base64). */
    fun mutateBase64(textDataEncryptedB64: String, mutator: (String) -> Mutation): String {
        val compressed = Base64.decode(textDataEncryptedB64)
        val proto = Gzip.decompress(compressed)
        Log.i(TAG, "mutateBase64 IN: b64.len=${textDataEncryptedB64.length} compressed.len=${compressed.size} proto.len=${proto.size}")
        val newProto = mutate(proto, mutator)
        val newCompressed = Gzip.compress(newProto)
        val newB64 = Base64.encode(newCompressed)
        Log.i(TAG, "mutateBase64 OUT: proto.len=${newProto.size} compressed.len=${newCompressed.size} b64.len=${newB64.length}")
        return newB64
    }

    /**
     * Build a fresh, minimal NoteStoreProto carrying the given plain text
     * with a single attribute_run covering the full UTF-16 length. Loses
     * formatting but produces something Apple Notes can definitely parse.
     *
     * Layout:
     *   NoteStoreProto { Document { version=2, Note { note_text, attribute_run{length} } } }
     */
    fun buildPlainTextBase64(plainText: String): String {
        val noteFields = mutableListOf<ProtobufWire.Field>().apply {
            add(ProtobufWire.encodeString(FIELD_NOTE_TEXT, plainText))
            // single attribute_run with just length=utf16(plainText)
            val len = utf16Length(plainText).toLong()
            val runFields = listOf(
                ProtobufWire.encodeVarintField(FIELD_ATTRIBUTERUN_LENGTH, len),
            )
            add(
                ProtobufWire.Field(
                    fieldNumber = FIELD_NOTE_ATTRIBUTE_RUN,
                    wireType = ProtobufWire.WIRE_LENGTH_DELIM,
                    payload = ProtobufWire.encode(runFields),
                ),
            )
        }
        val docFields = listOf(
            ProtobufWire.encodeVarintField(2, 2L), // Document.version = 2
            ProtobufWire.Field(
                fieldNumber = FIELD_DOCUMENT_NOTE,
                wireType = ProtobufWire.WIRE_LENGTH_DELIM,
                payload = ProtobufWire.encode(noteFields),
            ),
        )
        val topFields = listOf(
            ProtobufWire.Field(
                fieldNumber = FIELD_NOTESTOREPROTO_DOCUMENT,
                wireType = ProtobufWire.WIRE_LENGTH_DELIM,
                payload = ProtobufWire.encode(docFields),
            ),
        )
        val protoBytes = ProtobufWire.encode(topFields)
        val gzipped = Gzip.compress(protoBytes)
        val b64 = Base64.encode(gzipped)
        Log.i(TAG, "buildPlainTextBase64: text.len=${plainText.length} utf16.len=${utf16Length(plainText)} proto.len=${protoBytes.size} b64.len=${b64.length}")
        return b64
    }

    /**
     * Pretty-print the proto tree of TextDataEncrypted (no mutation).
     * Useful for figuring out what's actually inside a note's body.
     */
    fun describeBase64(textDataEncryptedB64: String): String = buildString {
        try {
            val compressed = Base64.decode(textDataEncryptedB64)
            val proto = Gzip.decompress(compressed)
            append("b64.len=").append(textDataEncryptedB64.length)
                .append(" compressed.len=").append(compressed.size)
                .append(" proto.len=").append(proto.size).append('\n')
            describeFields(this, ProtobufWire.decode(proto), depth = 0)
        } catch (t: Throwable) {
            append("decode failed: ").append(t.message)
        }
    }

    private fun describeFields(out: StringBuilder, fields: List<ProtobufWire.Field>, depth: Int) {
        val indent = "  ".repeat(depth)
        for (f in fields) {
            out.append(indent).append("field ").append(f.fieldNumber)
                .append(" wireType=").append(f.wireType)
                .append(" len=").append(f.payload.size)
            when (f.wireType) {
                ProtobufWire.WIRE_VARINT -> {
                    val v = ProtobufWire.decodeVarint(f)
                    out.append(" varint=").append(v)
                }
                ProtobufWire.WIRE_LENGTH_DELIM -> {
                    // Try to recursively decode as nested message; if not, show as utf-8 / hex.
                    val nested = runCatching { ProtobufWire.decode(f.payload) }.getOrNull()
                    if (nested != null && nested.isNotEmpty() && depth < 6) {
                        out.append(" nested {\n")
                        describeFields(out, nested, depth + 1)
                        out.append(indent).append('}')
                    } else {
                        val s = runCatching { f.payload.decodeToString() }.getOrNull()
                        if (s != null && s.all { it.code in 9..126 || it.code >= 160 }) {
                            out.append(" str=").append(s.take(80).replace("\n", "\\n"))
                        } else {
                            out.append(" hex=").append(f.payload.take(24).joinToString("") {
                                ((it.toInt() and 0xFF) + 0x100).toString(16).substring(1)
                            })
                        }
                    }
                }
            }
            out.append('\n')
        }
    }

    /** Detect whether bytes are legacy NoteStoreProto (mutable) or modern MergableDataProto (not). */
    fun probe(noteStoreProtoBytes: ByteArray): NoteProtoKind {
        return runCatching {
            val top = ProtobufWire.decode(noteStoreProtoBytes)
            val docField = top.firstOrNull {
                it.fieldNumber == FIELD_NOTESTOREPROTO_DOCUMENT && it.wireType == ProtobufWire.WIRE_LENGTH_DELIM
            } ?: return@runCatching NoteProtoKind.UNKNOWN
            val docFields = ProtobufWire.decode(docField.payload)
            val noteField = docFields.firstOrNull {
                it.fieldNumber == FIELD_DOCUMENT_NOTE && it.wireType == ProtobufWire.WIRE_LENGTH_DELIM
            } ?: return@runCatching NoteProtoKind.UNKNOWN
            val noteFields = ProtobufWire.decode(noteField.payload)
            // Legacy Note has note_text (field 2, UTF-8 string). MergeableDataObjectData
            // does not have field 2 — it starts at field 3 (mergeable_data_object_entry).
            val noteTextField = noteFields.firstOrNull {
                it.fieldNumber == FIELD_NOTE_TEXT && it.wireType == ProtobufWire.WIRE_LENGTH_DELIM
            } ?: return@runCatching NoteProtoKind.MERGABLE_DATA
            if (looksLikeUtf8Text(noteTextField.payload)) NoteProtoKind.NOTE_STORE_PROTO
            else NoteProtoKind.MERGABLE_DATA
        }.getOrDefault(NoteProtoKind.UNKNOWN)
    }

    fun probeBase64(textDataEncryptedB64: String): NoteProtoKind = runCatching {
        probe(Gzip.decompress(Base64.decode(textDataEncryptedB64)))
    }.getOrDefault(NoteProtoKind.UNKNOWN)

    /** Decompressed-bytes form, exposed for unit testing. */
    fun mutate(noteStoreProtoBytes: ByteArray, mutator: (String) -> Mutation): ByteArray {
        val kind = probe(noteStoreProtoBytes)
        if (kind != NoteProtoKind.NOTE_STORE_PROTO) {
            error(
                "Refusing to mutate note body: detected $kind, not NOTE_STORE_PROTO. " +
                    "Modern Apple Notes are stored as MergableDataProto and editing them as if " +
                    "they were the legacy schema corrupts the note. Use buildPlainTextBase64() " +
                    "if you intentionally want to overwrite the body with plain text."
            )
        }
        val top = ProtobufWire.decode(noteStoreProtoBytes)
        rewriteNoteStoreProto(top, mutator)
        return ProtobufWire.encode(top)
    }

    /** UTF-8 + ≥80% printable / common-control bytes => looks like text, not a nested protobuf. */
    private fun looksLikeUtf8Text(bytes: ByteArray): Boolean {
        if (bytes.isEmpty()) return true
        var i = 0
        var printable = 0
        while (i < bytes.size) {
            val b = bytes[i].toInt() and 0xFF
            val len = when {
                b and 0x80 == 0 -> 1
                b and 0xE0 == 0xC0 -> 2
                b and 0xF0 == 0xE0 -> 3
                b and 0xF8 == 0xF0 -> 4
                else -> return false
            }
            if (i + len > bytes.size) return false
            for (k in 1 until len) {
                if ((bytes[i + k].toInt() and 0xC0) != 0x80) return false
            }
            if (len == 1) {
                if (b == 0x09 || b == 0x0A || b == 0x0D || (b in 0x20..0x7E)) printable++
            } else {
                printable++
            }
            i += len
        }
        return printable * 5 >= bytes.size * 4
    }

    /** Just read note_text out (no mutation). */
    fun readText(noteStoreProtoBytes: ByteArray): String? {
        val top = ProtobufWire.decode(noteStoreProtoBytes)
        val docField = top.firstOrNull { it.fieldNumber == FIELD_NOTESTOREPROTO_DOCUMENT } ?: return null
        val docFields = ProtobufWire.decode(docField.payload)
        val noteField = docFields.firstOrNull { it.fieldNumber == FIELD_DOCUMENT_NOTE } ?: return null
        val noteFields = ProtobufWire.decode(noteField.payload)
        val textField = noteFields.firstOrNull {
            it.fieldNumber == FIELD_NOTE_TEXT && it.wireType == ProtobufWire.WIRE_LENGTH_DELIM
        } ?: return null
        return ProtobufWire.decodeString(textField)
    }

    fun readTextFromBase64(textDataEncryptedB64: String): String? {
        val compressed = Base64.decode(textDataEncryptedB64)
        return readText(Gzip.decompress(compressed))
    }

    /**
     * One-line structural summary of the proto bytes for diagnostic logging.
     * Goal: see at a glance whether ops/replicas/attribute_runs look sane and
     * whether the editing replica index we forge matches the replica registry.
     */
    fun summarize(noteStoreProtoBytes: ByteArray): String = runCatching {
        val sb = StringBuilder()
        val kind = probe(noteStoreProtoBytes)
        sb.append("kind=").append(kind)
        sb.append(" bytes=").append(noteStoreProtoBytes.size)
        val top = ProtobufWire.decode(noteStoreProtoBytes)
        val docField = top.firstOrNull {
            it.fieldNumber == FIELD_NOTESTOREPROTO_DOCUMENT && it.wireType == ProtobufWire.WIRE_LENGTH_DELIM
        } ?: return@runCatching sb.append(" (no document)").toString()
        val docFields = ProtobufWire.decode(docField.payload)
        val noteField = docFields.firstOrNull {
            it.fieldNumber == FIELD_DOCUMENT_NOTE && it.wireType == ProtobufWire.WIRE_LENGTH_DELIM
        } ?: return@runCatching sb.append(" (no note)").toString()
        val noteFields = ProtobufWire.decode(noteField.payload)

        val textField = noteFields.firstOrNull {
            it.fieldNumber == FIELD_NOTE_TEXT && it.wireType == ProtobufWire.WIRE_LENGTH_DELIM
        }
        sb.append(" text.bytes=").append(textField?.payload?.size ?: 0)

        val opFields = noteFields.filter {
            it.fieldNumber == 3 && it.wireType == ProtobufWire.WIRE_LENGTH_DELIM
        }
        sb.append(" ops=").append(opFields.size).append('[')
        for ((i, op) in opFields.withIndex()) {
            if (i > 0) sb.append(',')
            val sub = ProtobufWire.decode(op.payload)
            val from = sub.firstOrNull {
                it.fieldNumber == 1 && it.wireType == ProtobufWire.WIRE_LENGTH_DELIM
            }?.let { ProtobufWire.decode(it.payload) }
            val fromRid = from?.firstOrNull {
                it.fieldNumber == 1 && it.wireType == ProtobufWire.WIRE_VARINT
            }?.let { ProtobufWire.decodeVarint(it) }
            val fromOff = from?.firstOrNull {
                it.fieldNumber == 2 && it.wireType == ProtobufWire.WIRE_VARINT
            }?.let { ProtobufWire.decodeVarint(it) }
            val len = sub.firstOrNull {
                it.fieldNumber == 2 && it.wireType == ProtobufWire.WIRE_VARINT
            }?.let { ProtobufWire.decodeVarint(it) }
            val to = sub.firstOrNull {
                it.fieldNumber == 3 && it.wireType == ProtobufWire.WIRE_LENGTH_DELIM
            }?.let { ProtobufWire.decode(it.payload) }
            val toRid = to?.firstOrNull {
                it.fieldNumber == 1 && it.wireType == ProtobufWire.WIRE_VARINT
            }?.let { ProtobufWire.decodeVarint(it) }
            val toOff = to?.firstOrNull {
                it.fieldNumber == 2 && it.wireType == ProtobufWire.WIRE_VARINT
            }?.let { ProtobufWire.decodeVarint(it) }
            val seq = sub.firstOrNull {
                it.fieldNumber == 5 && it.wireType == ProtobufWire.WIRE_VARINT
            }?.let { ProtobufWire.decodeVarint(it) }
            sb.append("(").append(fromRid).append(",").append(fromOff).append(")")
                .append("+").append(len)
                .append("->(").append(toRid).append(",").append(toOff).append(")")
                .append("/s=").append(seq)
        }
        sb.append(']')

        val replicaField = noteFields.firstOrNull {
            it.fieldNumber == 4 && it.wireType == ProtobufWire.WIRE_LENGTH_DELIM
        }
        if (replicaField != null) {
            val replicas = ProtobufWire.decode(replicaField.payload)
                .filter { it.fieldNumber == 1 && it.wireType == ProtobufWire.WIRE_LENGTH_DELIM }
            sb.append(" replicas=").append(replicas.size).append('[')
            for ((i, ent) in replicas.withIndex()) {
                if (i > 0) sb.append(',')
                val es = ProtobufWire.decode(ent.payload)
                val uuidPrefix = es.firstOrNull {
                    it.fieldNumber == 1 && it.wireType == ProtobufWire.WIRE_LENGTH_DELIM
                }?.payload?.take(4)?.joinToString("") {
                    ((it.toInt() and 0xFF) + 0x100).toString(16).substring(1)
                } ?: "????"
                val counters = es.filter {
                    it.fieldNumber == 2 && it.wireType == ProtobufWire.WIRE_LENGTH_DELIM
                }.mapNotNull { cb ->
                    val cs = ProtobufWire.decode(cb.payload)
                    cs.firstOrNull {
                        it.fieldNumber == 1 && it.wireType == ProtobufWire.WIRE_VARINT
                    }?.let { ProtobufWire.decodeVarint(it) }
                }
                sb.append('#').append(i + 1).append('(').append(uuidPrefix).append(")=")
                    .append(counters.joinToString("/"))
            }
            sb.append(']')
        } else {
            sb.append(" replicas=null")
        }

        val attrRunCount = noteFields.count {
            it.fieldNumber == FIELD_NOTE_ATTRIBUTE_RUN && it.wireType == ProtobufWire.WIRE_LENGTH_DELIM
        }
        sb.append(" attr_runs=").append(attrRunCount)
        sb.toString()
    }.getOrElse { "summarize_failed: ${it.message}" }

    fun summarizeBase64(textDataEncryptedB64: String): String = runCatching {
        summarize(Gzip.decompress(Base64.decode(textDataEncryptedB64)))
    }.getOrElse { "summarize_b64_failed: ${it.message}" }

    /**
     * Full structural dump of `Note.replicas` — every replica entry, every
     * inner field (numbered), every counter block. Used as ground-truth to
     * design our own registration. Multi-line.
     */
    fun dumpReplicasBase64(textDataEncryptedB64: String): String = runCatching {
        val proto = Gzip.decompress(Base64.decode(textDataEncryptedB64))
        val top = ProtobufWire.decode(proto)
        val docField = top.firstOrNull {
            it.fieldNumber == FIELD_NOTESTOREPROTO_DOCUMENT && it.wireType == ProtobufWire.WIRE_LENGTH_DELIM
        } ?: return@runCatching "no document"
        val docFields = ProtobufWire.decode(docField.payload)
        val noteField = docFields.firstOrNull {
            it.fieldNumber == FIELD_DOCUMENT_NOTE && it.wireType == ProtobufWire.WIRE_LENGTH_DELIM
        } ?: return@runCatching "no note"
        val noteFields = ProtobufWire.decode(noteField.payload)
        val replicaField = noteFields.firstOrNull {
            it.fieldNumber == 4 && it.wireType == ProtobufWire.WIRE_LENGTH_DELIM
        } ?: return@runCatching "no replicas field"

        val sb = StringBuilder()
        sb.append("replicas wrapper payload size=").append(replicaField.payload.size).append('\n')
        val wrapperFields = ProtobufWire.decode(replicaField.payload)
        var entryNum = 0
        for ((i, f) in wrapperFields.withIndex()) {
            sb.append("  wrapper[").append(i).append("] field=").append(f.fieldNumber)
                .append("/w=").append(f.wireType).append("/sz=").append(f.payload.size)
            if (f.wireType != ProtobufWire.WIRE_LENGTH_DELIM) {
                sb.append('\n')
                continue
            }
            entryNum++
            sb.append("  (replica entry #").append(entryNum).append(")\n")
            val es = ProtobufWire.decode(f.payload)
            for ((j, sf) in es.withIndex()) {
                sb.append("    [").append(j).append("] field=").append(sf.fieldNumber)
                    .append("/w=").append(sf.wireType).append("/sz=").append(sf.payload.size)
                when (sf.wireType) {
                    ProtobufWire.WIRE_VARINT -> {
                        sb.append("  varint=").append(ProtobufWire.decodeVarint(sf))
                    }
                    ProtobufWire.WIRE_LENGTH_DELIM -> {
                        if (sf.payload.size == 16) {
                            sb.append("  uuid=").append(hexBytes(sf.payload))
                        }
                        val nested = runCatching { ProtobufWire.decode(sf.payload) }.getOrNull()
                        if (nested != null && nested.isNotEmpty() && nested.all { it.payload.size <= sf.payload.size }) {
                            sb.append("  nested=[")
                            for ((k, nf) in nested.withIndex()) {
                                if (k > 0) sb.append(", ")
                                sb.append("f").append(nf.fieldNumber).append("/w").append(nf.wireType)
                                when (nf.wireType) {
                                    ProtobufWire.WIRE_VARINT -> sb.append("=").append(ProtobufWire.decodeVarint(nf))
                                    ProtobufWire.WIRE_LENGTH_DELIM -> {
                                        if (nf.payload.size == 16) {
                                            sb.append("=uuid:").append(hexBytes(nf.payload))
                                        } else {
                                            sb.append("/sz").append(nf.payload.size)
                                            val nn = runCatching { ProtobufWire.decode(nf.payload) }.getOrNull()
                                            if (nn != null && nn.isNotEmpty()) {
                                                sb.append("=[")
                                                for ((m, nfn) in nn.withIndex()) {
                                                    if (m > 0) sb.append(',')
                                                    sb.append("f").append(nfn.fieldNumber).append("/w").append(nfn.wireType)
                                                    if (nfn.wireType == ProtobufWire.WIRE_VARINT) {
                                                        sb.append("=").append(ProtobufWire.decodeVarint(nfn))
                                                    } else {
                                                        sb.append("/sz").append(nfn.payload.size)
                                                    }
                                                }
                                                sb.append("]")
                                            }
                                        }
                                    }
                                }
                            }
                            sb.append("]")
                        }
                    }
                }
                sb.append('\n')
            }
        }
        sb.toString()
    }.getOrElse { "dump_replicas_failed: ${it.message}" }

    private fun hexBytes(b: ByteArray): String =
        b.joinToString("") { ((it.toInt() and 0xFF) + 0x100).toString(16).substring(1) }

    /** What to insert and where. */
    data class Mutation(val prepend: String = "", val append: String = "")

    // ---- internal: targeted rewrite of nested message bytes ----

    private fun rewriteNoteStoreProto(
        fields: MutableList<ProtobufWire.Field>,
        mutator: (String) -> Mutation,
    ) {
        val docIdx = fields.indexOfFirst { it.fieldNumber == FIELD_NOTESTOREPROTO_DOCUMENT }
        if (docIdx < 0) return
        val docFields = ProtobufWire.decode(fields[docIdx].payload).toMutableList()
        rewriteDocument(docFields, mutator)
        fields[docIdx] = fields[docIdx].copy(payload = ProtobufWire.encode(docFields))
    }

    private fun rewriteDocument(
        fields: MutableList<ProtobufWire.Field>,
        mutator: (String) -> Mutation,
    ) {
        val noteIdx = fields.indexOfFirst { it.fieldNumber == FIELD_DOCUMENT_NOTE }
        if (noteIdx < 0) return
        val noteFields = ProtobufWire.decode(fields[noteIdx].payload).toMutableList()
        rewriteNote(noteFields, mutator)
        fields[noteIdx] = fields[noteIdx].copy(payload = ProtobufWire.encode(noteFields))
    }

    private fun rewriteNote(
        fields: MutableList<ProtobufWire.Field>,
        mutator: (String) -> Mutation,
    ) {
        val textIdx = fields.indexOfFirst {
            it.fieldNumber == FIELD_NOTE_TEXT && it.wireType == ProtobufWire.WIRE_LENGTH_DELIM
        }
        val original = if (textIdx >= 0) ProtobufWire.decodeString(fields[textIdx]) else ""
        val mutation = mutator(original)
        Log.i(TAG, "rewriteNote: orig.len=${original.length} (utf16=${utf16Length(original)})  " +
                "prepend=${mutation.prepend.length}  append=${mutation.append.length}  " +
                "noteFields=${fields.map { "${it.fieldNumber}(w${it.wireType},${it.payload.size}b)" }}")
        if (mutation.prepend.isEmpty() && mutation.append.isEmpty()) return

        val newText = mutation.prepend + original + mutation.append
        if (textIdx >= 0) {
            fields[textIdx] = ProtobufWire.encodeString(FIELD_NOTE_TEXT, newText)
        } else {
            fields.add(ProtobufWire.encodeString(FIELD_NOTE_TEXT, newText))
        }
        Log.i(TAG, "rewriteNote: newText.len=${newText.length} (utf16=${utf16Length(newText)})")

        // attribute_run lengths are in UTF-16 code-units (matches NSString length on Apple).
        val prependCodeUnits = utf16Length(mutation.prepend)
        val appendCodeUnits = utf16Length(mutation.append)

        if (prependCodeUnits > 0) {
            growFirstAttributeRunLength(fields, prependCodeUnits)
        }
        if (appendCodeUnits > 0) {
            growLastAttributeRunLength(fields, appendCodeUnits)
        }
    }

    private fun growFirstAttributeRunLength(
        noteFields: MutableList<ProtobufWire.Field>,
        delta: Int,
    ) {
        val idx = noteFields.indexOfFirst {
            it.fieldNumber == FIELD_NOTE_ATTRIBUTE_RUN && it.wireType == ProtobufWire.WIRE_LENGTH_DELIM
        }
        if (idx < 0) return
        noteFields[idx] = noteFields[idx].copy(payload = bumpAttributeRunLength(noteFields[idx].payload, delta))
    }

    private fun growLastAttributeRunLength(
        noteFields: MutableList<ProtobufWire.Field>,
        delta: Int,
    ) {
        val idx = noteFields.indexOfLast {
            it.fieldNumber == FIELD_NOTE_ATTRIBUTE_RUN && it.wireType == ProtobufWire.WIRE_LENGTH_DELIM
        }
        if (idx < 0) return
        noteFields[idx] = noteFields[idx].copy(payload = bumpAttributeRunLength(noteFields[idx].payload, delta))
    }

    private fun bumpAttributeRunLength(runBytes: ByteArray, delta: Int): ByteArray {
        val runFields = ProtobufWire.decode(runBytes).toMutableList()
        val lenIdx = runFields.indexOfFirst {
            it.fieldNumber == FIELD_ATTRIBUTERUN_LENGTH && it.wireType == ProtobufWire.WIRE_VARINT
        }
        if (lenIdx >= 0) {
            val cur = ProtobufWire.decodeVarint(runFields[lenIdx])
            runFields[lenIdx] = ProtobufWire.encodeVarintField(FIELD_ATTRIBUTERUN_LENGTH, cur + delta)
        } else {
            // No existing length field — add one matching the delta.
            runFields.add(0, ProtobufWire.encodeVarintField(FIELD_ATTRIBUTERUN_LENGTH, delta.toLong()))
        }
        return ProtobufWire.encode(runFields)
    }

    /** Apple counts attribute_run.length in UTF-16 code units (NSString length). */
    private fun utf16Length(s: String): Int {
        var n = 0
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAtCompat(i)
            n += if (cp > 0xFFFF) 2 else 1
            i += if (cp > 0xFFFF) 2 else 1
        }
        return n
    }

    private fun String.codePointAtCompat(i: Int): Int {
        val high = this[i]
        if (high.isHighSurrogate() && i + 1 < length) {
            val low = this[i + 1]
            if (low.isLowSurrogate()) {
                return 0x10000 + ((high.code - 0xD800) shl 10) + (low.code - 0xDC00)
            }
        }
        return high.code
    }
}
