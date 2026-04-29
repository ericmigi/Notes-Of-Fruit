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
     * Walk the topotext.String substring tree and return the VISIBLE text — i.e.
     * what Mac/iCloud renders. This differs from [readText] (which returns
     * `String.string`, the bag-of-bytes in insertion order, INCLUDING bytes that
     * sit under tombstoned substrings).
     *
     * Apple's invariants we lean on:
     *  - Substring at array index 0 is the doc-start (charID=(0,0), length=0).
     *  - The sentinel (charID=(0,0xFFFFFFFF)) is the LAST substring.
     *  - Each substring's `child[0]` (when present) is the next-in-document
     *    substring index — chained from doc-start to sentinel.
     *  - `String.string` holds chars in array-INSERTION order: the k-th
     *    non-zero-length substring's chars start at the cumulative sum of all
     *    earlier non-zero-length substrings' `length`s.
     *
     * Returns null if the proto isn't shaped like NoteStoreProto/topotext.
     */
    fun readVisibleText(noteStoreProtoBytes: ByteArray): String? = runCatching {
        val top = ProtobufWire.decode(noteStoreProtoBytes)
        val docField = top.firstOrNull {
            it.fieldNumber == FIELD_NOTESTOREPROTO_DOCUMENT && it.wireType == ProtobufWire.WIRE_LENGTH_DELIM
        } ?: return@runCatching null
        val docFields = ProtobufWire.decode(docField.payload)
        val noteField = docFields.firstOrNull {
            it.fieldNumber == FIELD_DOCUMENT_NOTE && it.wireType == ProtobufWire.WIRE_LENGTH_DELIM
        } ?: return@runCatching null
        val noteFields = ProtobufWire.decode(noteField.payload)

        val flatBytes = noteFields.firstOrNull {
            it.fieldNumber == FIELD_NOTE_TEXT && it.wireType == ProtobufWire.WIRE_LENGTH_DELIM
        }?.payload ?: return@runCatching null
        val flat = flatBytes.decodeToString()

        // Parse substrings (field 3) of the Note message: charID, length, tombstone, children.
        // Field numbers are the topotext.String / Substring schema (see NoteAppender).
        val FIELD_SUBSTRING = 3
        val FIELD_SUB_CHARID = 1
        val FIELD_SUB_LENGTH = 2
        val FIELD_SUB_TOMBSTONE = 4
        val FIELD_SUB_CHILD = 5
        val FIELD_CHARID_REPLICA = 1
        val FIELD_CHARID_CLOCK = 2
        val SENTINEL_CLOCK = 0xFFFFFFFFL

        data class S(
            val arrayIdx: Int,
            val replica: Long,
            val clock: Long,
            val length: Int,
            val tombstone: Boolean,
            val children: List<Int>,
        )

        val subs = mutableListOf<S>()
        var arrayIdx = 0
        for (f in noteFields) {
            if (f.fieldNumber != FIELD_SUBSTRING || f.wireType != ProtobufWire.WIRE_LENGTH_DELIM) continue
            val sub = ProtobufWire.decode(f.payload)
            val charID = sub.firstOrNull {
                it.fieldNumber == FIELD_SUB_CHARID && it.wireType == ProtobufWire.WIRE_LENGTH_DELIM
            }?.let { ProtobufWire.decode(it.payload) }
            val replica = charID?.firstOrNull {
                it.fieldNumber == FIELD_CHARID_REPLICA && it.wireType == ProtobufWire.WIRE_VARINT
            }?.let { ProtobufWire.decodeVarint(it) } ?: 0L
            val clock = charID?.firstOrNull {
                it.fieldNumber == FIELD_CHARID_CLOCK && it.wireType == ProtobufWire.WIRE_VARINT
            }?.let { ProtobufWire.decodeVarint(it) } ?: 0L
            val length = sub.firstOrNull {
                it.fieldNumber == FIELD_SUB_LENGTH && it.wireType == ProtobufWire.WIRE_VARINT
            }?.let { ProtobufWire.decodeVarint(it).toInt() } ?: 0
            val tombstone = sub.firstOrNull {
                it.fieldNumber == FIELD_SUB_TOMBSTONE && it.wireType == ProtobufWire.WIRE_VARINT
            }?.let { ProtobufWire.decodeVarint(it) != 0L } ?: false
            val children = sub.filter {
                it.fieldNumber == FIELD_SUB_CHILD && it.wireType == ProtobufWire.WIRE_VARINT
            }.map { ProtobufWire.decodeVarint(it).toInt() }
            subs.add(S(arrayIdx, replica, clock, length, tombstone, children))
            arrayIdx++
        }
        if (subs.isEmpty()) return@runCatching null

        // Compute each substring's offset into `flat` (array-insertion order).
        // Apple stores `String.string` as the LIVE chars only — tombstoned
        // substrings retain their `length` field (it describes the CharID range
        // they consumed) but contribute zero bytes to `String.string`. So when
        // accumulating offsets we skip tombstoned substrings. Doc-start and
        // sentinel have length 0 already.
        val offsets = IntArray(subs.size)
        var off = 0
        for (s in subs) {
            offsets[s.arrayIdx] = off
            if (!s.tombstone) off += s.length
        }

        // Walk via child[0] from doc-start (idx 0) to the sentinel.
        // Output non-tombstoned, non-sentinel substrings' chars from `flat`.
        val out = StringBuilder()
        var cur = 0
        val visited = BooleanArray(subs.size)
        var safety = subs.size + 1
        while (cur in subs.indices && !visited[cur] && safety-- > 0) {
            visited[cur] = true
            val s = subs[cur]
            val isSentinel = s.replica == 0L && s.clock == SENTINEL_CLOCK
            if (!isSentinel && !s.tombstone && s.length > 0) {
                val start = offsets[s.arrayIdx]
                val end = (start + s.length).coerceAtMost(flat.length)
                if (start in 0..flat.length) out.append(flat, start, end)
            }
            cur = s.children.firstOrNull() ?: break
        }
        // Apple Notes stores Shift-Enter / soft line breaks as U+2028 (LINE
        // SEPARATOR) and paragraph separators as U+2029 — same byte sequence
        // Mac and iCloud render as a line break. Android's default fonts lack
        // glyphs for these so they render as `□` placeholder boxes. Translate
        // them to `\n` so the UI shows clean line breaks; this also makes
        // pure-append diffing work normally for users who add lines below.
        out.toString()
            .replace(' ', '\n')
            .replace(' ', '\n')
    }.getOrNull()

    fun readVisibleTextFromBase64(textDataEncryptedB64: String): String? {
        val compressed = Base64.decode(textDataEncryptedB64)
        return readVisibleText(Gzip.decompress(compressed))
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
        // TextDataEncrypted ships in either gzip or zlib (deflate); Gzip.decompress
        // auto-detects.
        summarize(Gzip.decompress(Base64.decode(textDataEncryptedB64)))
    }.getOrElse { "summarize_b64_failed: ${it.message}" }

    /**
     * Recursive structural dump of a protobuf payload that ISN'T gzipped (e.g.
     * `ReplicaIDToNotesVersionDataEncrypted` — name is misleading; it's plain
     * protobuf in CloudKit, despite the "Encrypted" suffix).
     */
    fun dumpRawBase64(b64: String): String = runCatching {
        val bytes = Base64.decode(b64)
        val sb = StringBuilder()
        sb.append("bytes=").append(bytes.size).append('\n')
        dumpFields(sb, ProtobufWire.decode(bytes), depth = 1)
        sb.toString()
    }.getOrElse { "dump_raw_failed: ${it.message}" }

    private fun dumpFields(sb: StringBuilder, fields: List<ProtobufWire.Field>, depth: Int) {
        val indent = "  ".repeat(depth)
        for (f in fields) {
            sb.append(indent).append("f").append(f.fieldNumber)
                .append("/w").append(f.wireType).append("/sz").append(f.payload.size)
            when (f.wireType) {
                ProtobufWire.WIRE_VARINT -> sb.append("  v=").append(ProtobufWire.decodeVarint(f))
                ProtobufWire.WIRE_LENGTH_DELIM -> {
                    val nested = runCatching { ProtobufWire.decode(f.payload) }.getOrNull()
                    if (nested != null && nested.isNotEmpty() && depth < 8) {
                        sb.append('\n')
                        dumpFields(sb, nested, depth + 1)
                        continue
                    } else if (f.payload.size <= 32) {
                        sb.append("  hex=").append(
                            f.payload.joinToString("") {
                                ((it.toInt() and 0xFF) + 0x100).toString(16).substring(1)
                            },
                        )
                    } else {
                        sb.append("  bytes")
                    }
                }
            }
            sb.append('\n')
        }
    }

    /**
     * Full structural dump of `Note.replicas` — every replica entry, every
     * inner field (numbered), every counter block. Used as ground-truth to
     * design our own registration. Multi-line.
     */
    /** Multi-line dump of every AttributeRun's inner field structure. */
    fun dumpAttributeRunsBase64(textDataEncryptedB64: String): String = runCatching {
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
        val sb = StringBuilder()
        var idx = 0
        for (f in noteFields) {
            if (f.fieldNumber != FIELD_NOTE_ATTRIBUTE_RUN || f.wireType != ProtobufWire.WIRE_LENGTH_DELIM) continue
            sb.append("AR[").append(idx++).append("] sz=").append(f.payload.size).append('\n')
            for (sub in ProtobufWire.decode(f.payload)) {
                sb.append("  f").append(sub.fieldNumber).append("/w").append(sub.wireType)
                    .append("/sz").append(sub.payload.size)
                when (sub.wireType) {
                    ProtobufWire.WIRE_VARINT -> sb.append(" v=").append(ProtobufWire.decodeVarint(sub))
                    ProtobufWire.WIRE_LENGTH_DELIM -> {
                        val nested = runCatching { ProtobufWire.decode(sub.payload) }.getOrNull()
                        if (nested != null && nested.isNotEmpty()) {
                            sb.append(" {")
                            for ((j, nf) in nested.withIndex()) {
                                if (j > 0) sb.append(", ")
                                sb.append("f").append(nf.fieldNumber).append("/w").append(nf.wireType)
                                if (nf.wireType == ProtobufWire.WIRE_VARINT) {
                                    sb.append("=").append(ProtobufWire.decodeVarint(nf))
                                } else {
                                    sb.append("/sz").append(nf.payload.size)
                                }
                            }
                            sb.append("}")
                        } else if (sub.payload.size <= 32) {
                            sb.append(" hex=").append(sub.payload.joinToString("") {
                                ((it.toInt() and 0xFF) + 0x100).toString(16).substring(1)
                            })
                        }
                    }
                }
                sb.append('\n')
            }
        }
        sb.toString()
    }.getOrElse { "dump_attrs_failed: ${it.message}" }

    /**
     * One line per substring with its array idx, charID, length, tombstone, and
     * children. Lets us spot CRDT corruption like: more than one substring's
     * `child[0]` pointing at the sentinel (a "fork" => Mac visible-text walk
     * visits the doc twice).
     */
    fun dumpSubstringTreeBase64(textDataEncryptedB64: String): String = runCatching {
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
        val FIELD_SUBSTRING = 3
        val FIELD_SUB_CHARID = 1
        val FIELD_SUB_LENGTH = 2
        val FIELD_SUB_TIMESTAMP = 3
        val FIELD_SUB_TOMBSTONE = 4
        val FIELD_SUB_CHILD = 5
        val FIELD_CHARID_REPLICA = 1
        val FIELD_CHARID_CLOCK = 2
        val sb = StringBuilder()
        var idx = 0
        val parentsOfSentinel = mutableListOf<Int>()
        var sentinelIdx = -1
        for (f in noteFields) {
            if (f.fieldNumber != FIELD_SUBSTRING || f.wireType != ProtobufWire.WIRE_LENGTH_DELIM) continue
            val sub = ProtobufWire.decode(f.payload)
            val charID = sub.firstOrNull {
                it.fieldNumber == FIELD_SUB_CHARID && it.wireType == ProtobufWire.WIRE_LENGTH_DELIM
            }?.let { ProtobufWire.decode(it.payload) }
            val replica = charID?.firstOrNull {
                it.fieldNumber == FIELD_CHARID_REPLICA && it.wireType == ProtobufWire.WIRE_VARINT
            }?.let { ProtobufWire.decodeVarint(it) } ?: 0L
            val clock = charID?.firstOrNull {
                it.fieldNumber == FIELD_CHARID_CLOCK && it.wireType == ProtobufWire.WIRE_VARINT
            }?.let { ProtobufWire.decodeVarint(it) } ?: 0L
            val length = sub.firstOrNull {
                it.fieldNumber == FIELD_SUB_LENGTH && it.wireType == ProtobufWire.WIRE_VARINT
            }?.let { ProtobufWire.decodeVarint(it) } ?: 0L
            val tomb = sub.firstOrNull {
                it.fieldNumber == FIELD_SUB_TOMBSTONE && it.wireType == ProtobufWire.WIRE_VARINT
            }?.let { ProtobufWire.decodeVarint(it) != 0L } ?: false
            val children = sub.filter {
                it.fieldNumber == FIELD_SUB_CHILD && it.wireType == ProtobufWire.WIRE_VARINT
            }.map { ProtobufWire.decodeVarint(it).toInt() }
            // Style timestamp (CharID nested struct).
            val ts = sub.firstOrNull {
                it.fieldNumber == FIELD_SUB_TIMESTAMP && it.wireType == ProtobufWire.WIRE_LENGTH_DELIM
            }?.let { ProtobufWire.decode(it.payload) }
            val tsRep = ts?.firstOrNull {
                it.fieldNumber == FIELD_CHARID_REPLICA && it.wireType == ProtobufWire.WIRE_VARINT
            }?.let { ProtobufWire.decodeVarint(it) } ?: 0L
            val tsClk = ts?.firstOrNull {
                it.fieldNumber == FIELD_CHARID_CLOCK && it.wireType == ProtobufWire.WIRE_VARINT
            }?.let { ProtobufWire.decodeVarint(it) } ?: 0L
            if (replica == 0L && clock == 0xFFFFFFFFL) sentinelIdx = idx
            sb.append("[$idx] charID=($replica,$clock) len=$length tomb=$tomb ts=($tsRep,$tsClk) children=$children\n")
            idx++
        }
        if (sentinelIdx >= 0) {
            // Find every substring that points at the sentinel (its "parent" in the tree).
            idx = 0
            for (f in noteFields) {
                if (f.fieldNumber != FIELD_SUBSTRING || f.wireType != ProtobufWire.WIRE_LENGTH_DELIM) continue
                val sub = ProtobufWire.decode(f.payload)
                val children = sub.filter {
                    it.fieldNumber == FIELD_SUB_CHILD && it.wireType == ProtobufWire.WIRE_VARINT
                }.map { ProtobufWire.decodeVarint(it).toInt() }
                if (children.contains(sentinelIdx)) parentsOfSentinel.add(idx)
                idx++
            }
            sb.append("=> sentinel at idx $sentinelIdx; parents=$parentsOfSentinel")
            if (parentsOfSentinel.size > 1) sb.append(" *** FORK: ${parentsOfSentinel.size} chains lead to sentinel ***")
        }
        sb.toString()
    }.getOrElse { "dump failed: ${it.message}" }

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

    // ---------- Attribute-run paragraph style decoding ----------
    //
    // Apple Notes encodes per-character formatting in `attribute_run` entries
    // (field 5 of the topotext.String / Note message). Each entry has:
    //
    //   message AttributeRun {
    //     int32 length            = 1;  // chars covered (UTF-16 code units)
    //     ParagraphStyle style    = 2;  // nested style msg
    //     int64 timestamp         = 13; // creation timestamp
    //   }
    //   message ParagraphStyle {
    //     int32 styleType         = 1;  // OUR `paragraphStyle` enum
    //     int32 indent            = 2;  // (mostly absent)
    //     int32 fontVariant       = 3;  // (1 in observed notes)
    //     int32 checked           = 4;  // checkbox state (only for CHECKBOX style)
    //     bytes paragraphUuid     = 9;  // 16-byte UUID identifying the paragraph
    //   }
    //
    // ParagraphStyle.f1 (style_type) values, verified by sampling a baseline
    // note that contains every style iCloud Notes' formatting toolbar can
    // produce. The iCloud client omits f1 entirely for the default Body style;
    // an absent field decodes as 0 (proto3 default), which we'd then have to
    // disambiguate from explicit 0 (Title). We disambiguate via "field
    // present?", not via the decoded value — see parseAttributeRuns below.
    //
    //   0   = TITLE         (first line / explicitly applied)
    //   1   = HEADING
    //   2   = SUBHEADING
    //   3   = BODY          (also: f1 absent => Body)
    //   4   = MONOSPACED
    //   100 = BULLETED_LIST (•)
    //   101 = DASHED_LIST   (–)
    //   102 = NUMBERED_LIST (1.)
    //   103 = CHECKBOX_LIST (☐/☑ — checked state in style.f5.f2)

    enum class ParagraphStyle(val code: Int) {
        BODY(3),
        TITLE(0),
        HEADING(1),
        SUBHEADING(2),
        MONOSPACED(4),
        BULLETED_LIST(100),
        DASHED_LIST(101),
        NUMBERED_LIST(102),
        CHECKBOX_LIST(103);
        companion object {
            fun fromCode(c: Int): ParagraphStyle = values().firstOrNull { it.code == c } ?: BODY
        }
    }

    /**
     * One contiguous attribute-run span. `length` is the number of visible
     * chars this span covers (in tree-walk order, matching the visible text
     * returned by [readVisibleText]). The spans are returned in order; their
     * lengths sum to the visible text length (modulo Apple bugs).
     *
     * Inline fields below come from the AttributeRun message itself (siblings
     * of `paragraph_style`), not from inside ParagraphStyle:
     *  - bold/italic     => f5 (font_weight enum: 1=Bold, 2=Italic, 3=both)
     *  - underline       => f6
     *  - strikethrough   => f7
     *  - linkUrl         => f9 (UTF-8 URL string)
     *  - isAttachment    => f12 present (table, image, etc. — the visible
     *                       char is U+FFFC; we don't decode the attachment yet)
     */
    data class AttrSpan(
        val length: Int,
        val style: ParagraphStyle,
        val checked: Boolean = false,
        val bold: Boolean = false,
        val italic: Boolean = false,
        val underline: Boolean = false,
        val strikethrough: Boolean = false,
        val linkUrl: String? = null,
        val isAttachment: Boolean = false,
        /** Attachment record UUID — null unless [isAttachment]. */
        val attachmentRecordName: String? = null,
        /** UTI string e.g. "com.apple.notes.table", "public.png". */
        val attachmentType: String? = null,
    )

    fun parseAttributeRuns(noteStoreProtoBytes: ByteArray): List<AttrSpan> = runCatching {
        val top = ProtobufWire.decode(noteStoreProtoBytes)
        val docField = top.firstOrNull {
            it.fieldNumber == FIELD_NOTESTOREPROTO_DOCUMENT && it.wireType == ProtobufWire.WIRE_LENGTH_DELIM
        } ?: return@runCatching emptyList<AttrSpan>()
        val docFields = ProtobufWire.decode(docField.payload)
        val noteField = docFields.firstOrNull {
            it.fieldNumber == FIELD_DOCUMENT_NOTE && it.wireType == ProtobufWire.WIRE_LENGTH_DELIM
        } ?: return@runCatching emptyList<AttrSpan>()
        val noteFields = ProtobufWire.decode(noteField.payload)
        val FIELD_AR = 5
        val FIELD_AR_LENGTH = 1
        val FIELD_AR_STYLE = 2
        val FIELD_AR_FONTWEIGHT = 5
        val FIELD_AR_UNDERLINE = 6
        val FIELD_AR_STRIKE = 7
        val FIELD_AR_LINK = 9
        val FIELD_AR_ATTACHMENT = 12
        val FIELD_PS_STYLETYPE = 1
        val FIELD_PS_CHECKLIST = 5
        val FIELD_CL_CHECKED = 2
        val out = ArrayList<AttrSpan>()
        for (f in noteFields) {
            if (f.fieldNumber != FIELD_AR || f.wireType != ProtobufWire.WIRE_LENGTH_DELIM) continue
            val arFields = ProtobufWire.decode(f.payload)
            val length = arFields.firstOrNull {
                it.fieldNumber == FIELD_AR_LENGTH && it.wireType == ProtobufWire.WIRE_VARINT
            }?.let { ProtobufWire.decodeVarint(it).toInt() } ?: 0
            val styleField = arFields.firstOrNull {
                it.fieldNumber == FIELD_AR_STYLE && it.wireType == ProtobufWire.WIRE_LENGTH_DELIM
            }
            var style: ParagraphStyle = ParagraphStyle.BODY
            var checked = false
            if (styleField != null) {
                val ps = ProtobufWire.decode(styleField.payload)
                val styleTypeField = ps.firstOrNull {
                    it.fieldNumber == FIELD_PS_STYLETYPE && it.wireType == ProtobufWire.WIRE_VARINT
                }
                style = if (styleTypeField != null) {
                    ParagraphStyle.fromCode(ProtobufWire.decodeVarint(styleTypeField).toInt())
                } else {
                    // f1 absent (iCloud's encoding for default Body).
                    ParagraphStyle.BODY
                }
                val checklist = ps.firstOrNull {
                    it.fieldNumber == FIELD_PS_CHECKLIST && it.wireType == ProtobufWire.WIRE_LENGTH_DELIM
                }
                if (checklist != null) {
                    val cl = ProtobufWire.decode(checklist.payload)
                    checked = (cl.firstOrNull {
                        it.fieldNumber == FIELD_CL_CHECKED && it.wireType == ProtobufWire.WIRE_VARINT
                    }?.let { ProtobufWire.decodeVarint(it) } ?: 0L) != 0L
                }
            }
            val fontWeight = arFields.firstOrNull {
                it.fieldNumber == FIELD_AR_FONTWEIGHT && it.wireType == ProtobufWire.WIRE_VARINT
            }?.let { ProtobufWire.decodeVarint(it).toInt() } ?: 0
            val underline = (arFields.firstOrNull {
                it.fieldNumber == FIELD_AR_UNDERLINE && it.wireType == ProtobufWire.WIRE_VARINT
            }?.let { ProtobufWire.decodeVarint(it) } ?: 0L) != 0L
            val strike = (arFields.firstOrNull {
                it.fieldNumber == FIELD_AR_STRIKE && it.wireType == ProtobufWire.WIRE_VARINT
            }?.let { ProtobufWire.decodeVarint(it) } ?: 0L) != 0L
            val linkUrl = arFields.firstOrNull {
                it.fieldNumber == FIELD_AR_LINK && it.wireType == ProtobufWire.WIRE_LENGTH_DELIM
            }?.let { runCatching { it.payload.decodeToString() }.getOrNull() }
            val attachmentField = arFields.firstOrNull {
                it.fieldNumber == FIELD_AR_ATTACHMENT && it.wireType == ProtobufWire.WIRE_LENGTH_DELIM
            }
            var attachmentRecord: String? = null
            var attachmentType: String? = null
            if (attachmentField != null) {
                // f12 = AttachmentInfo { f1: UUID-string, f2: UTI-string }
                val info = ProtobufWire.decode(attachmentField.payload)
                attachmentRecord = info.firstOrNull {
                    it.fieldNumber == 1 && it.wireType == ProtobufWire.WIRE_LENGTH_DELIM
                }?.let { runCatching { it.payload.decodeToString() }.getOrNull() }
                attachmentType = info.firstOrNull {
                    it.fieldNumber == 2 && it.wireType == ProtobufWire.WIRE_LENGTH_DELIM
                }?.let { runCatching { it.payload.decodeToString() }.getOrNull() }
            }
            out.add(
                AttrSpan(
                    length = length,
                    style = style,
                    checked = checked,
                    bold = (fontWeight and 1) != 0,
                    italic = (fontWeight and 2) != 0,
                    underline = underline,
                    strikethrough = strike,
                    linkUrl = linkUrl,
                    isAttachment = attachmentField != null,
                    attachmentRecordName = attachmentRecord,
                    attachmentType = attachmentType,
                ),
            )
        }
        out
    }.getOrElse { emptyList() }

    fun parseAttributeRunsFromBase64(textDataEncryptedB64: String): List<AttrSpan> {
        val compressed = Base64.decode(textDataEncryptedB64)
        return parseAttributeRuns(Gzip.decompress(compressed))
    }

    // ----- Encoding AttrSpan back into proto attribute_run fields -----

    private const val FIELD_AR_LENGTH = 1
    private const val FIELD_AR_STYLE = 2
    private const val FIELD_AR_FONTWEIGHT = 5
    private const val FIELD_AR_UNDERLINE = 6
    private const val FIELD_AR_STRIKE = 7
    private const val FIELD_AR_LINK = 9
    private const val FIELD_AR_ATTACHMENT = 12
    private const val FIELD_PS_STYLETYPE = 1
    private const val FIELD_PS_FONTVARIANT = 2  // Apple emits 4 in observed notes
    private const val FIELD_PS_CHECKLIST = 5

    /**
     * Re-encode an AttrSpan back into a proto AttributeRun message.
     *
     * @param paragraphUuid 16-byte UUID for checkbox style (uniquely identifies
     *  the checkbox row across replicas). Caller is responsible for
     *  consistency: the same paragraph should keep its UUID across edits;
     *  generating a fresh one on every keystroke would explode Apple's
     *  per-note replica cap.
     */
    internal fun encodeAttributeRunField(
        span: AttrSpan,
        checkboxUuid: ByteArray? = null,
    ): ProtobufWire.Field {
        val arFields = mutableListOf<ProtobufWire.Field>()
        arFields.add(ProtobufWire.encodeVarintField(FIELD_AR_LENGTH, span.length.toLong()))

        // Build paragraph_style sub-message. Apple omits f1 for default Body
        // (style.code == 3) — match that to keep diffs minimal.
        val needsStyleField = span.style != ParagraphStyle.BODY ||
            span.style == ParagraphStyle.CHECKBOX_LIST
        if (needsStyleField || span.linkUrl != null || span.bold || span.italic ||
            span.underline || span.strikethrough || span.isAttachment) {
            val psFields = mutableListOf<ProtobufWire.Field>()
            if (span.style != ParagraphStyle.BODY) {
                psFields.add(ProtobufWire.encodeVarintField(FIELD_PS_STYLETYPE, span.style.code.toLong()))
            }
            psFields.add(ProtobufWire.encodeVarintField(FIELD_PS_FONTVARIANT, 4L))
            if (span.style == ParagraphStyle.CHECKBOX_LIST) {
                val uuid = checkboxUuid ?: ByteArray(16).also { java.util.UUID.randomUUID().let { u ->
                    val msb = u.mostSignificantBits
                    val lsb = u.leastSignificantBits
                    for (i in 0 until 8) it[i] = ((msb shr (56 - i * 8)) and 0xFF).toByte()
                    for (i in 0 until 8) it[8 + i] = ((lsb shr (56 - i * 8)) and 0xFF).toByte()
                }}
                val checklistFields = listOf(
                    ProtobufWire.Field(1, ProtobufWire.WIRE_LENGTH_DELIM, uuid),
                    ProtobufWire.encodeVarintField(2, if (span.checked) 1L else 0L),
                )
                psFields.add(ProtobufWire.Field(
                    FIELD_PS_CHECKLIST, ProtobufWire.WIRE_LENGTH_DELIM,
                    ProtobufWire.encode(checklistFields),
                ))
            }
            arFields.add(ProtobufWire.Field(
                FIELD_AR_STYLE, ProtobufWire.WIRE_LENGTH_DELIM,
                ProtobufWire.encode(psFields),
            ))
        }

        // Inline formatting siblings of paragraph_style.
        val fontWeight = (if (span.bold) 1 else 0) or (if (span.italic) 2 else 0)
        if (fontWeight != 0) {
            arFields.add(ProtobufWire.encodeVarintField(FIELD_AR_FONTWEIGHT, fontWeight.toLong()))
        }
        if (span.underline) arFields.add(ProtobufWire.encodeVarintField(FIELD_AR_UNDERLINE, 1L))
        if (span.strikethrough) arFields.add(ProtobufWire.encodeVarintField(FIELD_AR_STRIKE, 1L))
        if (span.linkUrl != null) {
            arFields.add(ProtobufWire.encodeString(FIELD_AR_LINK, span.linkUrl))
        }
        if (span.isAttachment && span.attachmentRecordName != null) {
            val info = mutableListOf<ProtobufWire.Field>()
            info.add(ProtobufWire.encodeString(1, span.attachmentRecordName))
            if (span.attachmentType != null) info.add(ProtobufWire.encodeString(2, span.attachmentType))
            arFields.add(ProtobufWire.Field(
                FIELD_AR_ATTACHMENT, ProtobufWire.WIRE_LENGTH_DELIM,
                ProtobufWire.encode(info),
            ))
        }

        return ProtobufWire.Field(
            FIELD_NOTE_ATTRIBUTE_RUN, ProtobufWire.WIRE_LENGTH_DELIM,
            ProtobufWire.encode(arFields),
        )
    }

    /**
     * Coalesce identical-formatting consecutive [AttrSpan]s into single runs.
     * Apple iCloud always emits the most compact form, so a series of bold
     * chars becomes one run. Equality compares everything except length.
     */
    fun coalesceSpans(spans: List<AttrSpan>): List<AttrSpan> {
        if (spans.size <= 1) return spans
        val out = ArrayList<AttrSpan>()
        for (s in spans) {
            val prev = out.lastOrNull()
            if (prev != null && spansShareFormat(prev, s)) {
                out[out.size - 1] = prev.copy(length = prev.length + s.length)
            } else out.add(s)
        }
        return out
    }

    private fun spansShareFormat(a: AttrSpan, b: AttrSpan): Boolean {
        // Two attachments must never coalesce — each owns its own f12.
        if (a.isAttachment || b.isAttachment) return false
        return a.style == b.style && a.checked == b.checked && a.bold == b.bold &&
            a.italic == b.italic && a.underline == b.underline &&
            a.strikethrough == b.strikethrough && a.linkUrl == b.linkUrl
    }

    // ----- High-level paragraph-aware mutations the toolbar invokes. -----
    //
    // The editor tracks a flat List<AttrSpan> aligned with the visible text.
    // Each helper returns a new list with the requested change applied. Lengths
    // remain in sync with the underlying text — these never insert/remove
    // characters; they only retag existing ones.
    //
    // Paragraph-style helpers operate on whole paragraphs (split by '\n'); the
    // selection's range is mapped to the set of paragraphs it touches.
    // Inline-style helpers operate on the exact char range of the selection,
    // splitting spans at the boundaries.

    /** Per-character expansion — convenient for range-based mutations. */
    fun expandSpansToChars(text: String, spans: List<AttrSpan>): Array<AttrSpan> {
        val out = arrayOfNulls<AttrSpan>(text.length)
        var p = 0
        for (s in spans) {
            val end = (p + s.length).coerceAtMost(text.length)
            for (i in p until end) out[i] = s.copy(length = 1)
            p = end
            if (p >= text.length) break
        }
        // Pad with default body for any tail not covered.
        for (i in 0 until text.length) {
            if (out[i] == null) out[i] = AttrSpan(length = 1, style = ParagraphStyle.BODY)
        }
        @Suppress("UNCHECKED_CAST")
        return out as Array<AttrSpan>
    }

    /** Run-length encode a per-char span array back into coalesced spans. */
    fun compactCharsToSpans(perChar: Array<AttrSpan>): List<AttrSpan> {
        if (perChar.isEmpty()) return emptyList()
        val out = ArrayList<AttrSpan>()
        var i = 0
        while (i < perChar.size) {
            val cur = perChar[i]
            var j = i + 1
            while (j < perChar.size && spansShareFormat(perChar[j], cur)) j++
            out.add(cur.copy(length = j - i))
            i = j
        }
        return out
    }

    /** Compute paragraph ranges (char index → IntRange) for a full body. */
    fun paragraphRanges(text: String): List<IntRange> {
        if (text.isEmpty()) return listOf(0..-1)
        val out = ArrayList<IntRange>()
        var start = 0
        for ((i, c) in text.withIndex()) {
            if (c == '\n') {
                out.add(start..i)
                start = i + 1
            }
        }
        if (start <= text.length - 1) out.add(start..(text.length - 1))
        else if (text.endsWith('\n')) out.add(text.length until text.length)
        return out
    }

    /** Paragraph index containing a given char position (or the final paragraph). */
    fun paragraphIndexAt(text: String, charPos: Int): Int {
        val ranges = paragraphRanges(text)
        for ((i, r) in ranges.withIndex()) {
            if (charPos in r) return i
        }
        return (ranges.size - 1).coerceAtLeast(0)
    }

    /**
     * Set every char in selection [range] (paragraph-aligned) to [newStyle].
     * If the selection spans multiple paragraphs, every paragraph in the range
     * gets the new style. range.first/last are CHAR indices into [text].
     */
    fun setParagraphStyle(
        text: String,
        spans: List<AttrSpan>,
        rangeStart: Int,
        rangeEnd: Int,
        newStyle: ParagraphStyle,
    ): List<AttrSpan> {
        if (text.isEmpty()) return spans
        val perChar = expandSpansToChars(text, spans)
        val ranges = paragraphRanges(text)
        for (r in ranges) {
            // Touch this paragraph if it overlaps the selection.
            val overlaps = !(rangeEnd <= r.first || rangeStart > r.last)
            if (!overlaps) continue
            for (i in r) {
                if (i in perChar.indices) perChar[i] = perChar[i].copy(
                    style = newStyle,
                    // Toggle off any list-specific state when leaving a list style.
                    checked = if (newStyle == ParagraphStyle.CHECKBOX_LIST) perChar[i].checked else false,
                )
            }
        }
        return compactCharsToSpans(perChar)
    }

    enum class InlineKind { BOLD, ITALIC, UNDERLINE, STRIKETHROUGH }

    /** Toggle an inline style across [rangeStart, rangeEnd). */
    fun toggleInline(
        text: String,
        spans: List<AttrSpan>,
        rangeStart: Int,
        rangeEnd: Int,
        kind: InlineKind,
    ): List<AttrSpan> {
        if (text.isEmpty() || rangeEnd <= rangeStart) return spans
        val perChar = expandSpansToChars(text, spans)
        // If every char in the range already has the style on, toggle it off;
        // otherwise turn it on for the whole range.
        val allOn = (rangeStart until rangeEnd).all { i ->
            val s = perChar.getOrNull(i) ?: return@all true
            when (kind) {
                InlineKind.BOLD -> s.bold
                InlineKind.ITALIC -> s.italic
                InlineKind.UNDERLINE -> s.underline
                InlineKind.STRIKETHROUGH -> s.strikethrough
            }
        }
        val newValue = !allOn
        for (i in rangeStart until rangeEnd.coerceAtMost(perChar.size)) {
            perChar[i] = when (kind) {
                InlineKind.BOLD -> perChar[i].copy(bold = newValue)
                InlineKind.ITALIC -> perChar[i].copy(italic = newValue)
                InlineKind.UNDERLINE -> perChar[i].copy(underline = newValue)
                InlineKind.STRIKETHROUGH -> perChar[i].copy(strikethrough = newValue)
            }
        }
        return compactCharsToSpans(perChar)
    }

    /** Toggle the checked state of the checkbox paragraph at [charPos]. */
    fun toggleCheckbox(
        text: String,
        spans: List<AttrSpan>,
        charPos: Int,
    ): List<AttrSpan> {
        val perChar = expandSpansToChars(text, spans)
        val ranges = paragraphRanges(text)
        val pIdx = paragraphIndexAt(text, charPos)
        val r = ranges.getOrNull(pIdx) ?: return spans
        val first = r.first.coerceIn(0, perChar.size - 1)
        if (perChar[first].style != ParagraphStyle.CHECKBOX_LIST) return spans
        val newChecked = !perChar[first].checked
        for (i in r) if (i in perChar.indices) perChar[i] = perChar[i].copy(checked = newChecked)
        return compactCharsToSpans(perChar)
    }

    /** Apply a hyperlink URL to [rangeStart, rangeEnd). null clears any link. */
    fun setLink(
        text: String,
        spans: List<AttrSpan>,
        rangeStart: Int,
        rangeEnd: Int,
        url: String?,
    ): List<AttrSpan> {
        if (text.isEmpty() || rangeEnd <= rangeStart) return spans
        val perChar = expandSpansToChars(text, spans)
        for (i in rangeStart until rangeEnd.coerceAtMost(perChar.size)) {
            perChar[i] = perChar[i].copy(linkUrl = url)
        }
        return compactCharsToSpans(perChar)
    }
}
