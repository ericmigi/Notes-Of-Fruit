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

    /**
     * Strategy controlling how we structure the write. Different patterns to
     * empirically figure out what Mac's CRDT replay accepts.
     */
    enum class Strategy {
        /** Add a new Substring after the tail, like iCloud.com appears to do. */
        APPEND,

        /**
         * Tombstone the existing live substring(s), add ONE new substring
         * containing the FULL post-edit body. Mirrors Mac's own set-body
         * pattern (see ref-152233 trace).
         */
        REWRITE_TOMBSTONE,
    }

    /** Process-wide strategy; override before tests via [setStrategy]. */
    @Volatile
    private var strategy: Strategy = Strategy.APPEND

    fun setStrategy(s: Strategy) { strategy = s }

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
        val format = Gzip.detect(compressed)
        val proto = Gzip.decompress(compressed)
        Log.i(TAG, "appendBase64 IN: textLen=${text.length} protoLen=${proto.size} format=$format strategy=$strategy")
        Log.i(TAG, "appendBase64 IN  proto: ${NoteBodyEditor.summarize(proto)}")
        val newProto = when (strategy) {
            Strategy.APPEND -> appendBytes(proto, ourReplicaUuid, text, nowEpochSec)
            Strategy.REWRITE_TOMBSTONE -> rewriteWithTombstoneBytes(proto, ourReplicaUuid, text, nowEpochSec)
        }
        Log.i(TAG, "appendBase64 OUT proto: ${NoteBodyEditor.summarize(newProto)}")
        // CRITICAL: round-trip in the SAME compression format we read. iCloud.com
        // uses zlib for short notes, gzip for older / longer ones. Mac probably
        // doesn't care which (it auto-detects too), but matching the input shape
        // means our writes look indistinguishable from Apple's own writes.
        val newCompressed = Gzip.compress(newProto, format)
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

        // Find the sentinel by its CharID (0, 0xFFFFFFFF). Apple's invariant
        // (verified by reading back what iCloud.com produces): the sentinel is
        // ALWAYS the LAST substring in the array. We rely on this — and we
        // preserve it on writes by inserting at the sentinel's slot, which
        // pushes the sentinel to the next array index.
        val sentinelSubstring = substrings.last()
        require(
            sentinelSubstring.charIDReplicaID == SENTINEL_REPLICA_ID &&
                sentinelSubstring.charIDClock == SENTINEL_CLOCK,
        ) {
            "Last substring is not the sentinel (charID=(0, 0xFFFFFFFF)) — got " +
                "(${sentinelSubstring.charIDReplicaID}, ${sentinelSubstring.charIDClock})"
        }
        val sentinelArrayIdx = sentinelSubstring.arrayIdx
        val sentinelNoteFieldIdx = sentinelSubstring.noteFieldIdx

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

        // Compute Lamport state across the whole doc. charID.clock behaves as
        // a global Lamport: a new edit allocates at max-existing-clock. Style
        // timestamps appear to be a separate Lamport view tracked per replica
        // (the second ReplicaClock); first edit by a new replica inherits the
        // doc's max-timestamp-clock without bumping it. Verified by reading
        // back what iCloud.com produces when it edits a fresh note.
        val maxDocCharIDClock = substrings.maxOfOrNull {
            // span [from, from+length-1]; sentinel + doc-start have length 0
            // and a sentinel clock of 0xFFFFFFFF that we deliberately exclude
            // (it would bury every real allocation under 4 billion).
            if (it.charIDReplicaID == SENTINEL_REPLICA_ID && it.charIDClock == SENTINEL_CLOCK) {
                -1L
            } else if (it.length == 0L) {
                it.charIDClock - 1 // doc-start (charID=(0,0)) gives -1, ignored by maxOf
            } else {
                it.charIDClock + it.length - 1
            }
        } ?: -1L
        val maxDocTimestampClock = substrings.maxOfOrNull {
            if (it.charIDReplicaID == SENTINEL_REPLICA_ID && it.charIDClock == SENTINEL_CLOCK) -1L
            else it.timestampClock
        } ?: 0L

        if (ourClock != null) {
            require(ourClock.replicaClocks.size == 2) {
                "Our existing Clock has ${ourClock.replicaClocks.size} ReplicaClock " +
                    "entries; expected 2"
            }
            ourReplicaID = ourClock.replicaID
            // Subsequent edits: pick up where we left off, but never below the
            // doc's current Lamport (other replicas may have edited since).
            ourCharIDClock = maxOf(ourClock.replicaClocks[0].clock, maxDocCharIDClock)
            ourTimestampClock = ourClock.replicaClocks[1].clock
            ourClockListIdx = ourClock.vtFieldIdx
            isFirstEdit = false
            Log.i(
                TAG,
                "MATCHED our replica: rid=$ourReplicaID resumeCharIDClock=$ourCharIDClock " +
                    "ourTimestampClock=$ourTimestampClock " +
                    "(maxDocCharID=$maxDocCharIDClock maxDocTimestamp=$maxDocTimestampClock)",
            )
        } else {
            ourReplicaID = clocks.size + 1
            // First edit by this replica. Initial charID clock = the doc's
            // current max charID clock — same Lamport position as the existing
            // last char. Tie broken by our (higher) replicaID. iCloud.com does
            // exactly this when it registers a new web-session replica.
            ourCharIDClock = maxOf(maxDocCharIDClock, 0L)
            ourTimestampClock = 0L
            ourClockListIdx = vtFields.size
            isFirstEdit = true
            // Append a fresh Clock entry. counter1 starts at the Lamport position
            // we just computed; counter2 inherits the doc's max-timestamp-clock
            // (no bump). Both are bumped below as part of the edit.
            vtFields.add(buildClockEntry(ourReplicaUuid, ourCharIDClock, maxDocTimestampClock))
            Log.i(
                TAG,
                "REGISTERED our replica: rid=$ourReplicaID uuid=${hex(ourReplicaUuid)} " +
                    "initCharIDClock=$ourCharIDClock initTimestampClock=$maxDocTimestampClock " +
                    "(coexists with ${clocks.size} existing replicas, " +
                    "maxDocCharID=$maxDocCharIDClock maxDocTimestamp=$maxDocTimestampClock)",
            )
        }

        // ----- Build our new Substring -----
        // Insertion strategy: take the SENTINEL'S array slot. The sentinel
        // itself gets pushed to (oldSentinelArrayIdx + 1). This is exactly
        // what iCloud.com does — verified by reading back its writes.
        //
        // Why this matters: every existing substring's child[] entry that
        // currently points to `sentinelArrayIdx` now naturally points to US
        // (since we took that index), without us having to rewrite anyone's
        // child[]. AND the sentinel stays as the LAST substring in the array,
        // which is Apple's invariant — Mac probably uses the array tail as
        // an implicit doc-end marker, and inserting AFTER the sentinel (our
        // previous bug) broke that.
        val n = text.length // UTF-16 code units (= NSString length)
        val ourArrayIdx = sentinelArrayIdx // we take the sentinel's slot
        val newSentinelArrayIdx = sentinelArrayIdx + 1 // sentinel pushed back

        val ourSubstring = buildSubstring(
            charIDReplicaID = ourReplicaID.toLong(),
            charIDClock = ourCharIDClock,
            length = n.toLong(),
            timestampReplicaID = ourReplicaID.toLong(),
            timestampClock = ourTimestampClock,
            tombstone = false,
            children = listOf(newSentinelArrayIdx),
        )
        Log.i(
            TAG,
            "topo: ourReplica=$ourReplicaID ourCharIDClock=$ourCharIDClock " +
                "ourTimestampClock=$ourTimestampClock n=$n " +
                "ourArrayIdx=$ourArrayIdx (taking old sentinel slot) " +
                "newSentinelArrayIdx=$newSentinelArrayIdx",
        )

        // ----- Update OUR Clock's ReplicaClock entries -----
        // counter1: bumps by length of run we just allocated. The first char of
        // our run is at clock = ourCharIDClock; the last is at clock+n-1; next-
        // to-allocate is clock+n.
        // counter2: tracks the doc-wide max timestamp clock. Our op's own
        // timestamp.clock (in our private replica space) is independent.
        val newCharIDClock = ourCharIDClock + n
        val newTimestampClock = maxDocTimestampClock
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
        // Lamport advance for OTHER replicas. When our edit pushes the doc-wide
        // CharID Lamport clock past where any existing replica's counter1 was
        // sitting, those replicas should see their counter1 bumped (= "I've now
        // observed up to this clock"). Mac may use a vector-clock validity
        // check that requires ALL replicas' counter1 >= doc-Lamport.
        for (clockEntry in clocks) {
            if (clockEntry.uuid.contentEquals(ourReplicaUuid)) continue
            if (clockEntry.replicaClocks.size < 2) continue
            val theirCharIDClock = clockEntry.replicaClocks[0].clock
            val theirTimestampClock = clockEntry.replicaClocks[1].clock
            if (theirCharIDClock < newCharIDClock) {
                Log.i(
                    TAG,
                    "advancing replica#${clockEntry.replicaID} counter1 " +
                        "$theirCharIDClock -> $newCharIDClock (Lamport)",
                )
                vtFields[clockEntry.vtFieldIdx] = updateExistingClockInPlace(
                    vtFields[clockEntry.vtFieldIdx],
                    newCharIDClock,
                    theirTimestampClock,
                )
            }
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

        // ----- Insert our new Substring AT the sentinel's slot -----
        // This pushes the sentinel one position back in stringFields AND in
        // the substring array. Existing children references to the old
        // sentinelArrayIdx now naturally point at us (we're now at that
        // index). Our own child[] = [oldSentinelArrayIdx + 1] = the new
        // sentinel position. No parent rewrite needed.
        //
        // Doing this AFTER the in-place mutations above keeps the indices
        // we held (timestampNoteFieldIdx, stringFieldIdx) valid for those
        // mutations. We don't reference them after this point.
        stringFields.add(sentinelNoteFieldIdx, ourSubstring)

        // ----- Extend the LAST existing AttributeRun by n -----
        // Apple's invariant (verified by reading back what iCloud.com produces):
        // attribute_run count tends to stay STABLE on simple appends — the last
        // run's `length` is bumped instead of a new run being added. Adding a
        // new run is technically legal but introduces a style boundary at our
        // insertion point that other clients' merge code may treat as
        // "different style starts here", potentially blocking sync.
        val lastAttrRunNoteFieldIdx = stringFields.indexOfLast {
            it.fieldNumber == FIELD_STRING_ATTRIBUTE_RUN && it.wireType == ProtobufWire.WIRE_LENGTH_DELIM
        }
        if (lastAttrRunNoteFieldIdx >= 0) {
            val runFields = ProtobufWire.decode(stringFields[lastAttrRunNoteFieldIdx].payload).toMutableList()
            val lengthIdx = runFields.indexOfFirst {
                it.fieldNumber == FIELD_ATTR_LENGTH && it.wireType == ProtobufWire.WIRE_VARINT
            }
            if (lengthIdx >= 0) {
                val oldLength = ProtobufWire.decodeVarint(runFields[lengthIdx])
                runFields[lengthIdx] = ProtobufWire.encodeVarintField(FIELD_ATTR_LENGTH, oldLength + n)
                Log.i(TAG, "extended last attribute_run length: $oldLength -> ${oldLength + n}")
            } else {
                runFields.add(0, ProtobufWire.encodeVarintField(FIELD_ATTR_LENGTH, n.toLong()))
                Log.i(TAG, "added attribute_run length=$n (no prior length field)")
            }
            stringFields[lastAttrRunNoteFieldIdx] =
                stringFields[lastAttrRunNoteFieldIdx].copy(payload = ProtobufWire.encode(runFields))
        } else {
            // Empty doc: emit a fresh attribute_run.
            val templateFont = lastAttributeRunFont(stringFields)
            stringFields.add(buildAttributeRun(length = n, fontFields = templateFont))
            Log.i(TAG, "no existing attribute_run; added new length=$n")
        }

        // ----- Re-encode bottom-up -----
        versionFields[dataIdx] = versionFields[dataIdx].copy(payload = ProtobufWire.encode(stringFields))
        top[versionIdx] = top[versionIdx].copy(payload = ProtobufWire.encode(versionFields))
        return ProtobufWire.encode(top)
    }

    // -------- Substring parsing --------

    /**
     * REWRITE_TOMBSTONE strategy: tombstone every existing live substring,
     * add ONE new Substring containing the FULL post-edit body. Mirrors what
     * Mac itself produces when AppleScript `set body` runs (see ref-152233
     * trace: substring[1]=(1,17)+18 NEW + substring[2]=(1,0)+17 tombstoned).
     *
     * Loses CRDT history but matches Mac's own write pattern, so Mac's merge
     * logic should accept it identically to a Mac self-edit.
     */
    fun rewriteWithTombstoneBytes(
        protoBytes: ByteArray,
        ourReplicaUuid: ByteArray,
        appendedText: String,
        nowEpochSec: Long,
    ): ByteArray {
        val kind = NoteBodyEditor.probe(protoBytes)
        require(kind == NoteProtoKind.NOTE_STORE_PROTO) {
            "Cannot rewrite a $kind body."
        }

        val top = ProtobufWire.decode(protoBytes).toMutableList()
        val versionFieldIndices = top.withIndex()
            .filter { it.value.fieldNumber == FIELD_OUTER_VERSION && it.value.wireType == ProtobufWire.WIRE_LENGTH_DELIM }
            .map { it.index }
        require(versionFieldIndices.size == 1) { "Outer doc has ${versionFieldIndices.size} Versions; expected 1" }
        val versionIdx = versionFieldIndices[0]
        val versionFields = ProtobufWire.decode(top[versionIdx].payload).toMutableList()
        val dataIdx = versionFields.indexOfFirst {
            it.fieldNumber == FIELD_VERSION_DATA && it.wireType == ProtobufWire.WIRE_LENGTH_DELIM
        }
        require(dataIdx >= 0) { "Missing Version.data" }
        val stringFields = ProtobufWire.decode(versionFields[dataIdx].payload).toMutableList()

        val substrings = parseSubstrings(stringFields)
        require(substrings.isNotEmpty()) { "no substrings" }

        // Sentinel must be the last entry.
        val sentinel = substrings.last()
        require(
            sentinel.charIDReplicaID == SENTINEL_REPLICA_ID && sentinel.charIDClock == SENTINEL_CLOCK,
        ) { "Last substring is not the sentinel (got ${sentinel.charIDReplicaID},${sentinel.charIDClock})" }
        val sentinelArrayIdx = sentinel.arrayIdx
        val sentinelNoteFieldIdx = sentinel.noteFieldIdx

        // Read current note_text.
        val stringFieldIdx = stringFields.indexOfFirst {
            it.fieldNumber == FIELD_STRING_STRING && it.wireType == ProtobufWire.WIRE_LENGTH_DELIM
        }
        require(stringFieldIdx >= 0) { "no String.string" }
        val originalText = stringFields[stringFieldIdx].payload.decodeToString()
        val newBody = originalText + appendedText

        // Compute Lamport state.
        val maxDocCharIDClock = substrings.maxOfOrNull {
            if (it.charIDReplicaID == SENTINEL_REPLICA_ID && it.charIDClock == SENTINEL_CLOCK) -1L
            else if (it.length == 0L) it.charIDClock - 1
            else it.charIDClock + it.length - 1
        } ?: -1L
        val maxDocTimestampClock = substrings.maxOfOrNull {
            if (it.charIDReplicaID == SENTINEL_REPLICA_ID && it.charIDClock == SENTINEL_CLOCK) -1L
            else it.timestampClock
        } ?: 0L

        // Find or register OUR replica (same logic as APPEND).
        val timestampNoteFieldIdx = stringFields.indexOfFirst {
            it.fieldNumber == FIELD_STRING_TIMESTAMP && it.wireType == ProtobufWire.WIRE_LENGTH_DELIM
        }
        require(timestampNoteFieldIdx >= 0) { "no VectorTimestamp" }
        val vtFields = ProtobufWire.decode(stringFields[timestampNoteFieldIdx].payload).toMutableList()
        val clocks = parseVectorTimestamp(vtFields)
        val ourClock = clocks.firstOrNull { it.uuid.contentEquals(ourReplicaUuid) }
        val ourReplicaID: Int
        val ourClockListIdx: Int
        val isFirstEdit: Boolean
        val ourCharIDClockStart: Long
        if (ourClock != null) {
            ourReplicaID = ourClock.replicaID
            ourClockListIdx = ourClock.vtFieldIdx
            isFirstEdit = false
            require(ourClock.replicaClocks.size == 2) { "Clock entry needs 2 ReplicaClocks" }
            ourCharIDClockStart = maxOf(ourClock.replicaClocks[0].clock, maxDocCharIDClock + 1)
        } else {
            ourReplicaID = clocks.size + 1
            ourClockListIdx = vtFields.size
            isFirstEdit = true
            // Mac's set-body uses charID.clock = max-doc-clock + 1 (see ref-152233).
            ourCharIDClockStart = maxDocCharIDClock + 1
            vtFields.add(buildClockEntry(ourReplicaUuid, ourCharIDClockStart, maxDocTimestampClock))
            Log.i(TAG, "REWRITE: registered replica idx=$ourReplicaID")
        }

        val newSubstringLen = newBody.length.toLong()
        Log.i(
            TAG,
            "REWRITE: ourReplica=$ourReplicaID startClock=$ourCharIDClockStart " +
                "newBodyLen=$newSubstringLen (was ${originalText.length}, +${appendedText.length})",
        )

        // Tombstone every existing live substring (skip doc-start at index 0 and sentinel at last).
        for (sub in substrings) {
            if (sub.arrayIdx == 0) continue // doc-start
            if (sub.arrayIdx == sentinelArrayIdx) continue // sentinel
            if (sub.tombstone) continue // already tombstoned
            val noteFieldIdx = sub.noteFieldIdx
            val subFields = ProtobufWire.decode(stringFields[noteFieldIdx].payload).toMutableList()
            // Remove any existing tombstone fields, add tombstone=true.
            subFields.removeAll {
                it.fieldNumber == FIELD_SUBSTRING_TOMBSTONE && it.wireType == ProtobufWire.WIRE_VARINT
            }
            subFields.add(ProtobufWire.encodeVarintField(FIELD_SUBSTRING_TOMBSTONE, 1L))
            stringFields[noteFieldIdx] = stringFields[noteFieldIdx].copy(payload = ProtobufWire.encode(subFields))
        }

        // Bump our replica's counter1 to clock + len, counter2 = max-doc-timestamp.
        val newCharIDClock = ourCharIDClockStart + newSubstringLen
        val newTimestampClock = maxDocTimestampClock
        vtFields[ourClockListIdx] = if (isFirstEdit) {
            buildClockEntry(ourReplicaUuid, newCharIDClock, newTimestampClock)
        } else {
            updateExistingClockInPlace(vtFields[ourClockListIdx], newCharIDClock, newTimestampClock)
        }
        stringFields[timestampNoteFieldIdx] =
            stringFields[timestampNoteFieldIdx].copy(payload = ProtobufWire.encode(vtFields))

        // Replace note_text with the FULL new body bytes.
        stringFields[stringFieldIdx] = stringFields[stringFieldIdx].copy(payload = newBody.encodeToByteArray())

        // Build new substring with full body length.
        val ourSubstring = buildSubstring(
            charIDReplicaID = ourReplicaID.toLong(),
            charIDClock = ourCharIDClockStart,
            length = newSubstringLen,
            timestampReplicaID = ourReplicaID.toLong(),
            timestampClock = 0L,
            tombstone = false,
            children = listOf(sentinelArrayIdx + 1),
        )
        // Insert at sentinel's slot, pushing sentinel back.
        stringFields.add(sentinelNoteFieldIdx, ourSubstring)

        // Rewrite the attribute_runs: drop existing, add ONE covering full body.
        val attrIdxs = stringFields.withIndex()
            .filter { it.value.fieldNumber == FIELD_STRING_ATTRIBUTE_RUN && it.value.wireType == ProtobufWire.WIRE_LENGTH_DELIM }
            .map { it.index }
            .reversed()
        val templateFont = lastAttributeRunFont(stringFields)
        for (idx in attrIdxs) stringFields.removeAt(idx)
        stringFields.add(buildAttributeRun(length = newBody.length, fontFields = templateFont))

        versionFields[dataIdx] = versionFields[dataIdx].copy(payload = ProtobufWire.encode(stringFields))
        top[versionIdx] = top[versionIdx].copy(payload = ProtobufWire.encode(versionFields))
        return ProtobufWire.encode(top)
    }

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
