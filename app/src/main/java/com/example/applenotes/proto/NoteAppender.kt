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

        // ===== Promote ourselves to replica slot 1 =====
        // iCloud.com's writes always land us at slot 1, demoting every other
        // replica by one. Mac's notesync uses the slot rotation as the cache-
        // invalidation signal — without it Mac merges our delta on top of stale
        // local content and duplicates the body. We rebuild VectorTimestamp's
        // Clock list (us at front) and rewrite every existing substring's
        // CharID.replicaID and timestamp.replicaID through the slot remap.
        // Verified by capturing iCloud.com's resulting proto on a typed note.

        // Build remap: oldSlot -> newSlot.
        val oldOurSlot = ourClock?.replicaID
        val replicaRemap = HashMap<Int, Int>()
        if (oldOurSlot != null) {
            for (c in clocks) {
                replicaRemap[c.replicaID] = when {
                    c.replicaID == oldOurSlot -> 1
                    c.replicaID < oldOurSlot -> c.replicaID + 1
                    else -> c.replicaID
                }
            }
        } else {
            for (c in clocks) replicaRemap[c.replicaID] = c.replicaID + 1
        }

        // Save each existing Clock entry's bytes by NEW slot so we can rebuild
        // vtFields in slot order.
        val savedByNewSlot = HashMap<Int, ProtobufWire.Field>()
        for (c in clocks) savedByNewSlot[replicaRemap[c.replicaID]!!] = vtFields[c.vtFieldIdx]

        // Strip every Clock entry from vtFields (preserve any non-Clock fields,
        // which Apple may add in future).
        vtFields.removeAll {
            it.fieldNumber == FIELD_VT_CLOCK && it.wireType == ProtobufWire.WIRE_LENGTH_DELIM
        }

        // Decide our new clocks. We always end up at slot 1.
        ourReplicaID = 1
        // First-allocation clock = max-doc-clock + 1 (matches iCloud.com).
        val freshClockStart = maxOf(maxDocCharIDClock + 1, 0L)
        if (ourClock != null) {
            require(ourClock.replicaClocks.size == 2) {
                "Our existing Clock has ${ourClock.replicaClocks.size} ReplicaClock entries; expected 2"
            }
            ourCharIDClock = maxOf(ourClock.replicaClocks[0].clock, freshClockStart)
            ourTimestampClock = ourClock.replicaClocks[1].clock
            isFirstEdit = false
        } else {
            ourCharIDClock = freshClockStart
            ourTimestampClock = 0L
            isFirstEdit = true
        }

        // Add Clock entries back in new-slot order. Slot 1 = ours; we replace
        // its bytes below with the bumped counter1/counter2 anyway, but use the
        // existing entry's bytes if present so subclock + unknown fields survive.
        ourClockListIdx = vtFields.size
        if (ourClock != null) {
            vtFields.add(savedByNewSlot[1]!!) // existing bytes; counters get rewritten below
        } else {
            vtFields.add(buildClockEntry(ourReplicaUuid, ourCharIDClock, maxDocTimestampClock))
        }
        // Then add slots 2..N in order. Skip slot 1 (just added).
        val totalSlots = clocks.size + (if (ourClock == null) 1 else 0)
        for (newSlot in 2..totalSlots) {
            savedByNewSlot[newSlot]?.let { vtFields.add(it) }
        }
        Log.i(
            TAG,
            "promoted ourselves to slot 1: oldSlot=$oldOurSlot remap=$replicaRemap " +
                "ourCharIDClock=$ourCharIDClock ourTimestampClock=$ourTimestampClock " +
                "(maxDocCharID=$maxDocCharIDClock maxDocTimestamp=$maxDocTimestampClock isFirstEdit=$isFirstEdit)",
        )

        // ----- Rewrite ALL existing substrings' replicaIDs via remap -----
        // Doc-start (charID=(0,0)) and sentinel (charID=(0,FFFF...)) have
        // replicaID=0 — leave them alone.
        for (subEntry in substrings) {
            val sentinel = subEntry.charIDReplicaID == SENTINEL_REPLICA_ID && subEntry.charIDClock == SENTINEL_CLOCK
            if (sentinel) continue
            if (subEntry.arrayIdx == 0) continue // doc-start
            val noteIdx = subEntry.noteFieldIdx
            val subFields = ProtobufWire.decode(stringFields[noteIdx].payload).toMutableList()
            var subChanged = false
            // CharID
            val charIdx = subFields.indexOfFirst {
                it.fieldNumber == FIELD_SUBSTRING_CHARID && it.wireType == ProtobufWire.WIRE_LENGTH_DELIM
            }
            if (charIdx >= 0) {
                val newRid = replicaRemap[subEntry.charIDReplicaID.toInt()]
                if (newRid != null && newRid != subEntry.charIDReplicaID.toInt()) {
                    val cf = ProtobufWire.decode(subFields[charIdx].payload).toMutableList()
                    val ridIdx = cf.indexOfFirst {
                        it.fieldNumber == FIELD_CHARID_REPLICA_ID && it.wireType == ProtobufWire.WIRE_VARINT
                    }
                    if (ridIdx >= 0) {
                        cf[ridIdx] = ProtobufWire.encodeVarintField(FIELD_CHARID_REPLICA_ID, newRid.toLong())
                    } else {
                        cf.add(0, ProtobufWire.encodeVarintField(FIELD_CHARID_REPLICA_ID, newRid.toLong()))
                    }
                    subFields[charIdx] = subFields[charIdx].copy(payload = ProtobufWire.encode(cf))
                    subChanged = true
                }
            }
            // Timestamp CharID
            val tsIdx = subFields.indexOfFirst {
                it.fieldNumber == FIELD_SUBSTRING_TIMESTAMP && it.wireType == ProtobufWire.WIRE_LENGTH_DELIM
            }
            if (tsIdx >= 0) {
                val newRid = replicaRemap[subEntry.timestampReplicaID.toInt()]
                if (newRid != null && newRid != subEntry.timestampReplicaID.toInt()) {
                    val tf = ProtobufWire.decode(subFields[tsIdx].payload).toMutableList()
                    val ridIdx = tf.indexOfFirst {
                        it.fieldNumber == FIELD_CHARID_REPLICA_ID && it.wireType == ProtobufWire.WIRE_VARINT
                    }
                    if (ridIdx >= 0) {
                        tf[ridIdx] = ProtobufWire.encodeVarintField(FIELD_CHARID_REPLICA_ID, newRid.toLong())
                    } else {
                        tf.add(0, ProtobufWire.encodeVarintField(FIELD_CHARID_REPLICA_ID, newRid.toLong()))
                    }
                    subFields[tsIdx] = subFields[tsIdx].copy(payload = ProtobufWire.encode(tf))
                    subChanged = true
                }
            }
            if (subChanged) {
                stringFields[noteIdx] = stringFields[noteIdx].copy(payload = ProtobufWire.encode(subFields))
            }
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
        // No Lamport-advance for other replicas. iCloud.com doesn't do it
        // (verified: Mac's counter1 stays at its own max+1 even after iCloud.com
        // appends past it). Each replica owns its own counter; touching a
        // foreign replica's counter1 confuses Mac's notesync.
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
    /**
     * Replace the note body with [newFullBody] entirely. Tombstones all existing
     * live substrings and writes a single new substring containing [newFullBody].
     * Use this for non-pure-append edits (mid-string changes, deletions, format
     * tweaks). It loses CRDT history but produces a body Mac will render exactly
     * as [newFullBody].
     */
    fun setBodyBase64(
        textDataEncryptedB64: String,
        ourReplicaUuid: ByteArray,
        newFullBody: String,
        nowEpochSec: Long,
    ): String {
        require(ourReplicaUuid.size == 16) { "ourReplicaUuid must be 16 bytes" }
        val compressed = Base64.decode(textDataEncryptedB64)
        val format = Gzip.detect(compressed)
        val proto = Gzip.decompress(compressed)
        Log.i(TAG, "setBodyBase64 IN: bodyLen=${newFullBody.length} protoLen=${proto.size} format=$format")
        Log.i(TAG, "setBodyBase64 IN  proto: ${NoteBodyEditor.summarize(proto)}")
        val newProto = setBodyBytes(proto, ourReplicaUuid, newFullBody, nowEpochSec)
        Log.i(TAG, "setBodyBase64 OUT proto: ${NoteBodyEditor.summarize(newProto)}")
        val newCompressed = Gzip.compress(newProto, format)
        return Base64.encode(newCompressed)
    }

    fun setBodyBytes(
        protoBytes: ByteArray,
        ourReplicaUuid: ByteArray,
        newFullBody: String,
        nowEpochSec: Long,
    ): ByteArray = setBodySpliceBytes(protoBytes, ourReplicaUuid, newFullBody, nowEpochSec)

    /**
     * Splice-based mid-edit. Mirrors iCloud.com's pattern verified by capturing
     * its outgoing proto on a typed-then-edited test note:
     *
     *  1. Promote ourselves to replica slot 1; demote everyone else by one;
     *     remap every existing substring's CharID.replicaID + timestamp.replicaID.
     *  2. Walk the substring tree to build (visiblePos -> originalSubIdx + offset)
     *     and the chain-order list.
     *  3. Compute a longest-common-prefix / longest-common-suffix diff against
     *     [newFullBody]. The middle (deleteFrom..deleteTo in visible space) is
     *     the splice region; [insertText] is what goes there instead.
     *  4. SPLIT the substring(s) that straddle the splice boundaries: the kept
     *     left part keeps the original (R, C0)+offset; the deleted part gets
     *     tombstoned at (R, C0+offset)+(deleteLenInThisSub); the kept right
     *     part is (R, C0+offset+deleteLenInThisSub)+(remainder).
     *  5. Tombstone any substrings that were entirely inside the splice region.
     *  6. Insert ONE new substring with our (replicaID=1, ourCharIDClock+) and
     *     length = insertText.length, content = insertText. Anchor it between
     *     the kept-left and the tombstoned-middle so tree walk sees:
     *        ...prefix... -> NEW -> ...tombstones... -> ...suffix...
     *  7. Rebuild the substring list in chain order and re-emit field-3 entries.
     *  8. Rebuild flat (String.string) as the concat of non-tombstoned chars in
     *     tree-walk order.
     *  9. Rebuild attribute_runs as ONE run covering the new visible length —
     *     iCloud.com keeps multiple runs but for now we collapse (formatting
     *     fidelity is a TODO; visible text correctness comes first).
     * 10. Bump our replica's counter1 by insertText.length.
     */
    fun setBodySpliceBytes(
        protoBytes: ByteArray,
        ourReplicaUuid: ByteArray,
        newFullBody: String,
        nowEpochSec: Long,
    ): ByteArray {
        require(ourReplicaUuid.size == 16)
        val kind = NoteBodyEditor.probe(protoBytes)
        require(kind == NoteProtoKind.NOTE_STORE_PROTO) { "Cannot splice a $kind body." }

        // Decode wrapper.
        val top = ProtobufWire.decode(protoBytes).toMutableList()
        val versionIdx = top.indexOfFirst {
            it.fieldNumber == FIELD_OUTER_VERSION && it.wireType == ProtobufWire.WIRE_LENGTH_DELIM
        }
        require(versionIdx >= 0)
        val versionFields = ProtobufWire.decode(top[versionIdx].payload).toMutableList()
        val dataIdx = versionFields.indexOfFirst {
            it.fieldNumber == FIELD_VERSION_DATA && it.wireType == ProtobufWire.WIRE_LENGTH_DELIM
        }
        require(dataIdx >= 0)
        val stringFields = ProtobufWire.decode(versionFields[dataIdx].payload).toMutableList()

        val origSubs = parseSubstrings(stringFields)
        require(origSubs.isNotEmpty())
        val sentinelOrig = origSubs.last()
        require(
            sentinelOrig.charIDReplicaID == SENTINEL_REPLICA_ID && sentinelOrig.charIDClock == SENTINEL_CLOCK,
        ) { "Last substring is not the sentinel" }

        val flatFieldIdx = stringFields.indexOfFirst {
            it.fieldNumber == FIELD_STRING_STRING && it.wireType == ProtobufWire.WIRE_LENGTH_DELIM
        }
        require(flatFieldIdx >= 0)
        val origFlat = stringFields[flatFieldIdx].payload.decodeToString()

        // Walk tree (via child[0]) collecting chain order + per-substring flat slice.
        // origSubs is in array (proto field) order; chainOrder follows children pointers.
        val origFlatOffsets = IntArray(origSubs.size)
        run {
            var off = 0
            for (s in origSubs) {
                origFlatOffsets[s.arrayIdx] = off
                if (!s.tombstone) off += s.length.toInt()
            }
        }
        val chainArrayIdxes = mutableListOf<Int>()
        run {
            var cur = 0
            val visited = BooleanArray(origSubs.size)
            while (cur in origSubs.indices && !visited[cur]) {
                visited[cur] = true
                chainArrayIdxes.add(cur)
                cur = origSubs[cur].children.firstOrNull() ?: break
            }
        }

        // Build the chain-ordered list as our working "substring list". Each
        // entry tracks its origin (so we can preserve replicaIDs/clocks/etc),
        // the chars that contribute to flat (empty for tombstones), and a
        // tombstone flag.
        data class S(
            val origArrayIdx: Int, // -1 = newly inserted
            val replica: Long,
            val clock: Long,
            val length: Long,
            val tsReplica: Long,
            val tsClock: Long,
            var tombstone: Boolean,
            val chars: String, // empty if tombstoned or zero-length
        )

        val chain = mutableListOf<S>()
        for (idx in chainArrayIdxes) {
            val s = origSubs[idx]
            val isSentinel = s.charIDReplicaID == SENTINEL_REPLICA_ID && s.charIDClock == SENTINEL_CLOCK
            val chars = if (s.tombstone || isSentinel || s.length == 0L) ""
            else origFlat.substring(origFlatOffsets[s.arrayIdx], origFlatOffsets[s.arrayIdx] + s.length.toInt())
            chain.add(
                S(
                    origArrayIdx = s.arrayIdx,
                    replica = s.charIDReplicaID,
                    clock = s.charIDClock,
                    length = s.length,
                    tsReplica = s.timestampReplicaID,
                    tsClock = s.timestampClock,
                    tombstone = s.tombstone,
                    chars = chars,
                ),
            )
        }

        // Compute currentVisible & visiblePos -> chain index + intra-substring offset.
        val visibleSb = StringBuilder()
        // Maps each visible position to (chainIndex, offsetWithinSubstring).
        val visibleToChain = mutableListOf<Pair<Int, Int>>()
        for ((ci, s) in chain.withIndex()) {
            if (s.tombstone || s.chars.isEmpty()) continue
            for (i in s.chars.indices) {
                visibleSb.append(s.chars[i])
                visibleToChain.add(Pair(ci, i))
            }
        }
        val currentVisible = visibleSb.toString()
        if (currentVisible == newFullBody) {
            Log.i(TAG, "setBodySplice: no-op (current visible matches target)")
            return protoBytes
        }
        Log.i(
            TAG,
            "setBodySplice: currentVisible.len=${currentVisible.length} newBody.len=${newFullBody.length}",
        )

        // Diff: longest common prefix + longest common suffix.
        val maxPrefix = minOf(currentVisible.length, newFullBody.length)
        var prefixLen = 0
        while (prefixLen < maxPrefix && currentVisible[prefixLen] == newFullBody[prefixLen]) prefixLen++
        var suffixLen = 0
        while (
            suffixLen < currentVisible.length - prefixLen &&
            suffixLen < newFullBody.length - prefixLen &&
            currentVisible[currentVisible.length - 1 - suffixLen] ==
                newFullBody[newFullBody.length - 1 - suffixLen]
        ) suffixLen++
        val deleteFrom = prefixLen
        val deleteTo = currentVisible.length - suffixLen
        val insertText = newFullBody.substring(prefixLen, newFullBody.length - suffixLen)
        val deleteLen = deleteTo - deleteFrom
        Log.i(
            TAG,
            "setBodySplice diff: prefix=$prefixLen suffix=$suffixLen " +
                "deleteFrom=$deleteFrom deleteTo=$deleteTo deleteLen=$deleteLen " +
                "insertLen=${insertText.length}",
        )

        // ===== Slot promotion: ourselves to slot 1 =====
        val timestampNoteFieldIdx = stringFields.indexOfFirst {
            it.fieldNumber == FIELD_STRING_TIMESTAMP && it.wireType == ProtobufWire.WIRE_LENGTH_DELIM
        }
        require(timestampNoteFieldIdx >= 0)
        val vtFields = ProtobufWire.decode(stringFields[timestampNoteFieldIdx].payload).toMutableList()
        val clocks = parseVectorTimestamp(vtFields)
        val ourClock = clocks.firstOrNull { it.uuid.contentEquals(ourReplicaUuid) }
        val maxDocCharIDClock = origSubs.maxOfOrNull {
            if (it.charIDReplicaID == SENTINEL_REPLICA_ID && it.charIDClock == SENTINEL_CLOCK) -1L
            else if (it.length == 0L) it.charIDClock - 1
            else it.charIDClock + it.length - 1
        } ?: -1L
        val maxDocTimestampClock = origSubs.maxOfOrNull {
            if (it.charIDReplicaID == SENTINEL_REPLICA_ID && it.charIDClock == SENTINEL_CLOCK) -1L
            else it.timestampClock
        } ?: 0L

        val oldOurSlot = ourClock?.replicaID
        val replicaRemap = HashMap<Int, Int>()
        if (oldOurSlot != null) {
            for (c in clocks) {
                replicaRemap[c.replicaID] = when {
                    c.replicaID == oldOurSlot -> 1
                    c.replicaID < oldOurSlot -> c.replicaID + 1
                    else -> c.replicaID
                }
            }
        } else {
            for (c in clocks) replicaRemap[c.replicaID] = c.replicaID + 1
        }
        val savedByNewSlot = HashMap<Int, ProtobufWire.Field>()
        for (c in clocks) savedByNewSlot[replicaRemap[c.replicaID]!!] = vtFields[c.vtFieldIdx]
        vtFields.removeAll {
            it.fieldNumber == FIELD_VT_CLOCK && it.wireType == ProtobufWire.WIRE_LENGTH_DELIM
        }
        val ourReplicaID = 1
        val ourCharIDClockStart = if (ourClock != null) {
            require(ourClock.replicaClocks.size == 2)
            maxOf(ourClock.replicaClocks[0].clock, maxDocCharIDClock + 1)
        } else {
            maxDocCharIDClock + 1
        }
        val ourTimestampClock = ourClock?.replicaClocks?.get(1)?.clock ?: 0L
        // Slot 1 entry — placeholder bytes; we rebuild it after we know the
        // post-edit counter1 (clock + insertText.length).
        val ourClockListIdx = vtFields.size
        if (ourClock != null) {
            vtFields.add(savedByNewSlot[1]!!)
        } else {
            vtFields.add(buildClockEntry(ourReplicaUuid, ourCharIDClockStart, maxDocTimestampClock))
        }
        val totalSlots = clocks.size + (if (ourClock == null) 1 else 0)
        for (newSlot in 2..totalSlots) {
            savedByNewSlot[newSlot]?.let { vtFields.add(it) }
        }

        // Apply remap to the chain entries' replica IDs.
        for ((i, s) in chain.withIndex()) {
            val newCharRep = replicaRemap[s.replica.toInt()]
            val newTsRep = replicaRemap[s.tsReplica.toInt()]
            if ((newCharRep != null && newCharRep.toLong() != s.replica) ||
                (newTsRep != null && newTsRep.toLong() != s.tsReplica)
            ) {
                chain[i] = s.copy(
                    replica = newCharRep?.toLong() ?: s.replica,
                    tsReplica = newTsRep?.toLong() ?: s.tsReplica,
                )
            }
        }

        // ===== Splice =====
        // We're going to perform a sequence of operations on `chain`:
        //   (a) Find chain indices that straddle deleteFrom and deleteTo.
        //   (b) SPLIT entries that straddle (so the boundary lines up exactly
        //       with chain entries).
        //   (c) Mark every entry covering [deleteFrom, deleteTo) as tombstoned.
        //   (d) Insert ONE new entry for insertText at the boundary.
        //
        // Implementation: walk visible positions to find boundary chain idxs.
        // (visibleToChain might not work well after splits; rebuild after each.)
        //
        // We split at the boundaries and then collect all live entries that
        // overlap [deleteFrom, deleteTo) and tombstone them in-place.

        // Helper: split chain entry at intra-substring offset `off`. Returns
        // the chainIndex of the new RIGHT half.
        fun splitChainEntryAt(chainIdx: Int, off: Int): Int {
            val s = chain[chainIdx]
            require(!s.tombstone) { "split called on tombstone (chainIdx=$chainIdx)" }
            require(off in 0..s.length.toInt()) { "split offset out of range" }
            if (off == 0 || off == s.length.toInt()) return chainIdx // no actual split needed
            val leftLen = off
            val rightLen = s.length.toInt() - off
            val left = s.copy(
                length = leftLen.toLong(),
                chars = s.chars.substring(0, leftLen),
            )
            val right = s.copy(
                clock = s.clock + leftLen,
                tsClock = s.tsClock, // keep same style timestamp; both halves originated together
                length = rightLen.toLong(),
                chars = s.chars.substring(leftLen),
            )
            chain[chainIdx] = left
            chain.add(chainIdx + 1, right)
            return chainIdx + 1
        }

        // Find the chain index whose live chars cover visible position `pos`.
        // Returns Pair(chainIdx, offsetWithin). If pos == currentVisible.length,
        // returns Pair(chain.size, 0) meaning "after the last entry" (insert at end).
        fun findChainAtVisible(pos: Int): Pair<Int, Int> {
            var seen = 0
            for ((ci, s) in chain.withIndex()) {
                if (s.tombstone || s.chars.isEmpty()) continue
                if (pos < seen + s.chars.length) return Pair(ci, pos - seen)
                seen += s.chars.length
            }
            return Pair(chain.size, 0)
        }

        // Split at deleteFrom (start of edit region).
        val (fromChainIdx, fromOff) = findChainAtVisible(deleteFrom)
        val insertAfterChainIdx: Int =
            if (fromChainIdx >= chain.size) {
                // Pure append at end: insert before sentinel.
                val sentIdx = chain.indexOfFirst {
                    it.replica == SENTINEL_REPLICA_ID && it.clock == SENTINEL_CLOCK
                }
                if (sentIdx >= 0) sentIdx - 1 else chain.size - 1
            } else if (fromOff == 0) {
                fromChainIdx - 1 // insert AFTER the previous entry (so we appear before fromChainIdx in chain)
            } else {
                splitChainEntryAt(fromChainIdx, fromOff)
                // After split, the left half stays at fromChainIdx, the right half is at fromChainIdx+1.
                fromChainIdx
            }

        // After the optional split, recompute (fromChainIdx, fromOff) for the
        // RIGHT half (which will be tombstoned start).
        val (rightStartChainIdx, _) = findChainAtVisible(deleteFrom)

        // Split at deleteTo (end of edit region).
        if (deleteLen > 0) {
            val (toChainIdx, toOff) = findChainAtVisible(deleteTo)
            if (toChainIdx < chain.size && toOff > 0) {
                splitChainEntryAt(toChainIdx, toOff)
                // Now the chunk to tombstone ends at original toChainIdx; the kept right is at toChainIdx+1.
            }
        }

        // Tombstone every chain entry from rightStartChainIdx up to (but not
        // including) the first entry that begins at visiblePos == deleteTo.
        // CRITICAL: each NEW tombstone gets a FRESH timestamp signed by us
        // (replicaID=1, fresh ts clock). Mac's notesync compares the substring's
        // timestamp to its local view; if the tombstone's ts is the SAME as
        // what Mac's local copy has (i.e., the original Mac ts), Mac decides
        // "this isn't a new event" and ignores the tombstone. Verified by
        // capturing iCloud.com's mid-edit traffic: every new tombstone it
        // emits has ts=(1, freshClock), where 1 is iCloud.com's slot.
        var freshTsClock = maxDocTimestampClock + 1
        if (deleteLen > 0) {
            // Re-find rightStartChainIdx after possible second split (indices may have shifted).
            val rsi = findChainAtVisible(deleteFrom).first
            // Find chainIdx at deleteTo (after split, this is the kept right half).
            val keepRightChainIdx = findChainAtVisible(deleteTo).first
            for (ci in rsi until keepRightChainIdx) {
                val e = chain[ci]
                if (!e.tombstone &&
                    !(e.replica == SENTINEL_REPLICA_ID && e.clock == SENTINEL_CLOCK) &&
                    e.length > 0L
                ) {
                    chain[ci] = e.copy(
                        tombstone = true,
                        chars = "",
                        tsReplica = ourReplicaID.toLong(),
                        tsClock = freshTsClock,
                    )
                    freshTsClock++
                }
            }
        }

        // Insert a NEW entry for insertText, anchored AFTER `insertAfterChainIdx`.
        // Use our (replicaID=1, ourCharIDClockStart) for the new substring.
        if (insertText.isNotEmpty()) {
            val newEntry = S(
                origArrayIdx = -1,
                replica = ourReplicaID.toLong(),
                clock = ourCharIDClockStart,
                length = insertText.length.toLong(),
                tsReplica = ourReplicaID.toLong(),
                tsClock = ourTimestampClock,
                tombstone = false,
                chars = insertText,
            )
            // Insert at index = insertAfterChainIdx + 1.
            chain.add(insertAfterChainIdx + 1, newEntry)
        }

        // ===== Rebuild the proto =====
        // Rebuild stringFields:
        //  - Keep field 2 (String.string) — replace payload below
        //  - Keep field 4 (timestamp / VectorTimestamp) — already updated vtFields
        //  - Keep attribute_runs (field 5) — replace below
        //  - Replace all field 3 (substrings) with our new chain-ordered list

        // Build the new flat from chain entries (chars, in order).
        val newFlatSb = StringBuilder()
        for (s in chain) {
            if (!s.tombstone && s.chars.isNotEmpty()) newFlatSb.append(s.chars)
        }
        val newFlat = newFlatSb.toString()

        // Build new substring fields. Children: each entry's child[0] = chainIdx+1
        // (i.e., the next chain entry's array index, which matches its position
        // in the new field-3 list). The sentinel keeps children=[].
        val newSubstringFields = ArrayList<ProtobufWire.Field>(chain.size)
        for ((ci, s) in chain.withIndex()) {
            val isSentinel = s.replica == SENTINEL_REPLICA_ID && s.clock == SENTINEL_CLOCK
            val children = if (isSentinel) emptyList<Int>() else listOf(ci + 1)
            newSubstringFields.add(
                buildSubstring(
                    charIDReplicaID = s.replica,
                    charIDClock = s.clock,
                    length = s.length,
                    timestampReplicaID = s.tsReplica,
                    timestampClock = s.tsClock,
                    tombstone = s.tombstone,
                    children = children,
                ),
            )
        }

        // ===== Preserve attribute_runs across the splice =====
        //
        // Naive: emit ONE body-styled AR covering all chars (loses headings,
        // bullets, checkboxes, every paragraph's f9 UUID).
        // Correct: keep the original AR for each char that survives the
        // splice (prefix + suffix), emit a default-body AR for the inserted
        // middle. Run-length-encode consecutive same-source chars into ARs.
        //
        // For each char in `currentVisible` we know its origin AR (cumulative
        // walk over original AR lengths). For each char in the new body, we
        // know whether it came from the prefix, the insert, or the suffix
        // (computed earlier). Map new chars → origin AR, then emit ARs.
        val origARFields = stringFields.filter {
            it.fieldNumber == FIELD_STRING_ATTRIBUTE_RUN && it.wireType == ProtobufWire.WIRE_LENGTH_DELIM
        }
        val origARLengths = origARFields.map { ar ->
            val arf = ProtobufWire.decode(ar.payload)
            val len = arf.firstOrNull {
                it.fieldNumber == FIELD_ATTR_LENGTH && it.wireType == ProtobufWire.WIRE_VARINT
            }?.let { ProtobufWire.decodeVarint(it).toInt() } ?: 0
            len
        }
        // origCharAR[i] = index into origARFields for the original visible
        // char at position i, or -1 if past the AR coverage (Apple's AR
        // lengths usually total visible.length but we tolerate mismatch).
        val origCharAR = IntArray(currentVisible.length) { -1 }
        run {
            var p = 0
            for ((arIdx, len) in origARLengths.withIndex()) {
                val end = (p + len).coerceAtMost(currentVisible.length)
                for (i in p until end) origCharAR[i] = arIdx
                p = end
                if (p >= currentVisible.length) break
            }
        }
        // newCharAR[i] = origARFields index for char i of the new body, or -1
        // for "this char is newly inserted, give it default body style".
        val newCharAR = IntArray(newFullBody.length) { -1 }
        for (i in 0 until prefixLen.coerceAtMost(currentVisible.length)) {
            newCharAR[i] = origCharAR[i]
        }
        // Suffix chars: come from the END of currentVisible.
        val newSuffixStart = newFullBody.length - suffixLen
        for (i in 0 until suffixLen) {
            val origIdx = currentVisible.length - suffixLen + i
            if (origIdx in origCharAR.indices) {
                newCharAR[newSuffixStart + i] = origCharAR[origIdx]
            }
        }
        // The middle chars (if any insertion) keep the default -1.

        // Run-length encode consecutive same-source chars into ARs. For -1
        // (newly inserted), emit a default body AR using the template font of
        // the last existing AR (matches the look of typing into a body region).
        val templateFont = lastAttributeRunFont(stringFields)
        val newARs = ArrayList<ProtobufWire.Field>()
        var runStart = 0
        while (runStart < newFullBody.length) {
            val src = newCharAR[runStart]
            var runEnd = runStart + 1
            while (runEnd < newFullBody.length && newCharAR[runEnd] == src) runEnd++
            val len = runEnd - runStart
            if (src >= 0) {
                // Copy the original AR's bytes, replace its length field.
                val origPayload = origARFields[src].payload
                val arFields = ProtobufWire.decode(origPayload).toMutableList()
                val li = arFields.indexOfFirst {
                    it.fieldNumber == FIELD_ATTR_LENGTH && it.wireType == ProtobufWire.WIRE_VARINT
                }
                if (li >= 0) {
                    arFields[li] = ProtobufWire.encodeVarintField(FIELD_ATTR_LENGTH, len.toLong())
                } else {
                    arFields.add(0, ProtobufWire.encodeVarintField(FIELD_ATTR_LENGTH, len.toLong()))
                }
                newARs.add(
                    ProtobufWire.Field(
                        FIELD_STRING_ATTRIBUTE_RUN,
                        ProtobufWire.WIRE_LENGTH_DELIM,
                        ProtobufWire.encode(arFields),
                    ),
                )
            } else {
                newARs.add(buildAttributeRun(length = len, fontFields = templateFont))
            }
            runStart = runEnd
        }
        Log.i(TAG, "splice attribute_runs: orig=${origARFields.size} new=${newARs.size} " +
                "(prefix=$prefixLen insert=${insertText.length} suffix=$suffixLen)")

        val rebuiltStringFields = ArrayList<ProtobufWire.Field>()
        rebuiltStringFields.add(
            ProtobufWire.Field(
                FIELD_STRING_STRING,
                ProtobufWire.WIRE_LENGTH_DELIM,
                newFlat.encodeToByteArray(),
            ),
        )
        rebuiltStringFields.addAll(newSubstringFields)
        rebuiltStringFields.add(
            ProtobufWire.Field(
                FIELD_STRING_TIMESTAMP,
                ProtobufWire.WIRE_LENGTH_DELIM,
                ProtobufWire.encode(vtFields),
            ),
        )
        rebuiltStringFields.addAll(newARs)
        // Preserve any other fields we don't touch (e.g., attachments at field 6).
        for (f in stringFields) {
            when (f.fieldNumber) {
                FIELD_STRING_STRING, FIELD_STRING_SUBSTRING,
                FIELD_STRING_TIMESTAMP, FIELD_STRING_ATTRIBUTE_RUN -> Unit
                else -> rebuiltStringFields.add(f)
            }
        }

        // Bump our replica's counter1 to ourCharIDClockStart + insertText.length
        // (the next-to-allocate clock for us). counter2 stays at maxDocTimestamp.
        val newCharIDClock = ourCharIDClockStart + insertText.length
        vtFields[ourClockListIdx] = if (ourClock != null) {
            updateExistingClockInPlace(vtFields[ourClockListIdx], newCharIDClock, maxDocTimestampClock)
        } else {
            buildClockEntry(ourReplicaUuid, newCharIDClock, maxDocTimestampClock)
        }
        // Replace the timestamp field in rebuiltStringFields with the bumped one.
        val tsIdx = rebuiltStringFields.indexOfFirst {
            it.fieldNumber == FIELD_STRING_TIMESTAMP && it.wireType == ProtobufWire.WIRE_LENGTH_DELIM
        }
        rebuiltStringFields[tsIdx] = ProtobufWire.Field(
            FIELD_STRING_TIMESTAMP,
            ProtobufWire.WIRE_LENGTH_DELIM,
            ProtobufWire.encode(vtFields),
        )

        versionFields[dataIdx] = versionFields[dataIdx].copy(
            payload = ProtobufWire.encode(rebuiltStringFields),
        )
        top[versionIdx] = top[versionIdx].copy(payload = ProtobufWire.encode(versionFields))
        return ProtobufWire.encode(top)
    }

    fun rewriteWithTombstoneBytes(
        protoBytes: ByteArray,
        ourReplicaUuid: ByteArray,
        appendedText: String,
        nowEpochSec: Long,
        overrideFullBody: String? = null,
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
        val newBody = overrideFullBody ?: (originalText + appendedText)

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

        // ===== Slot promotion (mirrors iCloud.com's pattern) =====
        // Promote ourselves to replica slot 1, demote everyone else by one. Mac's
        // notesync uses the rotation as the cache-invalidation signal — without it
        // the local cache merges our delta on top of stale state and duplicates
        // the body.
        val oldOurSlot = ourClock?.replicaID
        val replicaRemap = HashMap<Int, Int>()
        if (oldOurSlot != null) {
            for (c in clocks) {
                replicaRemap[c.replicaID] = when {
                    c.replicaID == oldOurSlot -> 1
                    c.replicaID < oldOurSlot -> c.replicaID + 1
                    else -> c.replicaID
                }
            }
        } else {
            for (c in clocks) replicaRemap[c.replicaID] = c.replicaID + 1
        }
        val savedByNewSlot = HashMap<Int, ProtobufWire.Field>()
        for (c in clocks) savedByNewSlot[replicaRemap[c.replicaID]!!] = vtFields[c.vtFieldIdx]
        vtFields.removeAll {
            it.fieldNumber == FIELD_VT_CLOCK && it.wireType == ProtobufWire.WIRE_LENGTH_DELIM
        }
        val ourReplicaID: Int = 1
        val ourClockListIdx: Int = vtFields.size
        val isFirstEdit: Boolean
        val ourCharIDClockStart: Long
        if (ourClock != null) {
            isFirstEdit = false
            require(ourClock.replicaClocks.size == 2) { "Clock entry needs 2 ReplicaClocks" }
            ourCharIDClockStart = maxOf(ourClock.replicaClocks[0].clock, maxDocCharIDClock + 1)
            vtFields.add(savedByNewSlot[1]!!)
        } else {
            isFirstEdit = true
            ourCharIDClockStart = maxDocCharIDClock + 1
            vtFields.add(buildClockEntry(ourReplicaUuid, ourCharIDClockStart, maxDocTimestampClock))
            Log.i(TAG, "REWRITE: registered replica + promoted to slot 1")
        }
        val totalSlots = clocks.size + (if (ourClock == null) 1 else 0)
        for (newSlot in 2..totalSlots) {
            savedByNewSlot[newSlot]?.let { vtFields.add(it) }
        }
        Log.i(
            TAG,
            "REWRITE promoted self to slot 1: oldSlot=$oldOurSlot remap=$replicaRemap"
        )

        // Rewrite all existing substrings' replicaIDs via remap.
        for (subEntry in substrings) {
            val isSentinel = subEntry.charIDReplicaID == SENTINEL_REPLICA_ID && subEntry.charIDClock == SENTINEL_CLOCK
            if (isSentinel) continue
            if (subEntry.arrayIdx == 0) continue
            val noteIdx = subEntry.noteFieldIdx
            val subFields = ProtobufWire.decode(stringFields[noteIdx].payload).toMutableList()
            var subChanged = false
            val charIdx = subFields.indexOfFirst {
                it.fieldNumber == FIELD_SUBSTRING_CHARID && it.wireType == ProtobufWire.WIRE_LENGTH_DELIM
            }
            if (charIdx >= 0) {
                val newRid = replicaRemap[subEntry.charIDReplicaID.toInt()]
                if (newRid != null && newRid != subEntry.charIDReplicaID.toInt()) {
                    val cf = ProtobufWire.decode(subFields[charIdx].payload).toMutableList()
                    val ridIdx = cf.indexOfFirst {
                        it.fieldNumber == FIELD_CHARID_REPLICA_ID && it.wireType == ProtobufWire.WIRE_VARINT
                    }
                    if (ridIdx >= 0) cf[ridIdx] = ProtobufWire.encodeVarintField(FIELD_CHARID_REPLICA_ID, newRid.toLong())
                    else cf.add(0, ProtobufWire.encodeVarintField(FIELD_CHARID_REPLICA_ID, newRid.toLong()))
                    subFields[charIdx] = subFields[charIdx].copy(payload = ProtobufWire.encode(cf))
                    subChanged = true
                }
            }
            val tsIdx = subFields.indexOfFirst {
                it.fieldNumber == FIELD_SUBSTRING_TIMESTAMP && it.wireType == ProtobufWire.WIRE_LENGTH_DELIM
            }
            if (tsIdx >= 0) {
                val newRid = replicaRemap[subEntry.timestampReplicaID.toInt()]
                if (newRid != null && newRid != subEntry.timestampReplicaID.toInt()) {
                    val tf = ProtobufWire.decode(subFields[tsIdx].payload).toMutableList()
                    val ridIdx = tf.indexOfFirst {
                        it.fieldNumber == FIELD_CHARID_REPLICA_ID && it.wireType == ProtobufWire.WIRE_VARINT
                    }
                    if (ridIdx >= 0) tf[ridIdx] = ProtobufWire.encodeVarintField(FIELD_CHARID_REPLICA_ID, newRid.toLong())
                    else tf.add(0, ProtobufWire.encodeVarintField(FIELD_CHARID_REPLICA_ID, newRid.toLong()))
                    subFields[tsIdx] = subFields[tsIdx].copy(payload = ProtobufWire.encode(tf))
                    subChanged = true
                }
            }
            if (subChanged) {
                stringFields[noteIdx] = stringFields[noteIdx].copy(payload = ProtobufWire.encode(subFields))
            }
        }

        val newSubstringLen = newBody.length.toLong()
        Log.i(
            TAG,
            "REWRITE: ourReplica=$ourReplicaID startClock=$ourCharIDClockStart " +
                "newBodyLen=$newSubstringLen (was ${originalText.length}, +${appendedText.length})",
        )

        // Tombstone every existing live substring AND increment its children by 1
        // (we're about to insert OUR new substring at array index 1, which shifts
        // every other substring's array index by +1). Skip doc-start (index 0) —
        // its children value [1] stays at [1], naturally pointing at our new
        // substring after the shift. This matches Mac's own set-body pattern
        // (verified by capturing its post-edit proto).
        for (sub in substrings) {
            val isDocStart = sub.arrayIdx == 0
            val isSentinel = sub.arrayIdx == sentinelArrayIdx
            val noteFieldIdx = sub.noteFieldIdx
            val subFields = ProtobufWire.decode(stringFields[noteFieldIdx].payload).toMutableList()
            var changed = false
            if (!isDocStart) {
                // Increment every child[] entry — they all reference substrings
                // that shifted (any index >= 1 was bumped by +1).
                rewriteSubstringChildren(subFields) { children ->
                    children.map { it + 1 }
                }
                changed = true
            }
            if (!isDocStart && !isSentinel && !sub.tombstone) {
                // Mark this substring as tombstoned (its content is no longer live).
                subFields.removeAll {
                    it.fieldNumber == FIELD_SUBSTRING_TOMBSTONE && it.wireType == ProtobufWire.WIRE_VARINT
                }
                subFields.add(ProtobufWire.encodeVarintField(FIELD_SUBSTRING_TOMBSTONE, 1L))
                changed = true
            }
            if (changed) {
                stringFields[noteFieldIdx] = stringFields[noteFieldIdx].copy(payload = ProtobufWire.encode(subFields))
            }
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
        // It will be inserted at array index 1 (just after doc-start). All other
        // substrings shift to index+1. Our child[0] points at the substring that
        // was at array index 1 before insertion (now at index 2 after shift).
        // Mac's pattern: NEW(1) -> OLD-tombstoned(2) -> ... -> sentinel(last).
        val ourSubstring = buildSubstring(
            charIDReplicaID = ourReplicaID.toLong(),
            charIDClock = ourCharIDClockStart,
            length = newSubstringLen,
            timestampReplicaID = ourReplicaID.toLong(),
            timestampClock = 0L,
            tombstone = false,
            children = listOf(2),
        )
        // Find the proto-field index of doc-start (array idx 0) so we insert
        // OUR substring as the next field after it.
        val docStartNoteFieldIdx = substrings.first { it.arrayIdx == 0 }.noteFieldIdx
        stringFields.add(docStartNoteFieldIdx + 1, ourSubstring)

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
