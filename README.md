# Notes of Fruit

An Android client for iCloud Notes. Reverse-engineered from CloudKit's private DB
API + Apple's `topotext` CRDT proto. Reads and writes notes that round-trip with
Mac's Apple Notes and iCloud.com.

This is a research codebase, not a polished product. Treat the README as a field
report for the next person (or agent) who picks it up.

## Install

Grab a signed APK from the [Releases page](../../releases) and side-load it onto an
Android 8.0+ device. Each release's notes link back to the disclaimer below;
read it first.

If you'd rather build from source, see [Build / run](#build--run) further down.

## ⚠️ Use at your own risk

This is **alpha-quality software**. It might:

- corrupt, duplicate, or delete notes in your iCloud account
- drop formatting in subtle ways when round-tripping through Mac
- stop working at any time if Apple changes their API
- fail to handle modern collaborative notes (read-only at best)

It is **unaffiliated with Apple** — it talks to Apple's CloudKit Web Services
API as a third-party client using your own credentials. The proto formats are
inferred by observation, not from any Apple documentation. There is no
warranty of any kind. If you have notes you can't afford to lose, back them up
first or just don't use this app.

The first launch will block the UI behind a dialog you have to acknowledge so
you can't say you weren't warned.

## Contributing

PRs and improvements are very welcome! Bug reports are OK, but I'd much rather
have a bug report _with an associated PR fixing the issue_ 😉 — this is a
weekend project I'm not on call for. The reverse-engineering surface area is
huge (substring CRDT, MergeableData CRDT, table object graph, image fetch
pipeline, paragraph + inline attribute_runs) and there's a long tail of edge
cases waiting to be discovered. If you investigate one, please write down what
you find in the README's relevant section so the next person doesn't have to
rediscover it.

## Status

| Capability | Status |
|---|---|
| Sign in with Apple ID (cookie-based) | Works |
| List notes (recents, paginated) | Works |
| Folder navigation (sidebar, trash filter) | Works |
| Read note content (decode topotext proto) | Works for `NOTE_STORE_PROTO`; not the modern `MergableData` shape |
| Append text | Works (round-trips with Mac) |
| Mid-text splice (insert/replace/delete) | Works (matches iCloud.com's slot-promotion pattern) |
| Create new note (FAB → save) | Code path works (deferred-create + v4 UUID); end-to-end Mac round-trip not yet user-confirmed |
| Auto-save on lifecycle pause | Works (lifecycle `ON_PAUSE` only — see [AppleNotesApp.kt:1052](app/src/main/java/com/example/applenotes/ui/AppleNotesApp.kt)) |
| Share OUT (Android intent chooser) | Works (plaintext only) |
| Delete | Uses CloudKit `forceDelete`; Mac surfaces as "Recently Deleted" (server-managed, not a hard wipe) |
| Format display (paragraph styles + inline) | Works — Title / Heading / Subheading / Body / Monospaced; Bulleted / Dashed / Numbered / Checkbox; bold / italic / underline / strikethrough; clickable links |
| Table attachments | Decoded from `MergeableDataEncrypted` (CRDT graph), rendered as a real grid |
| Image attachments | Fetched as CKAsset bytes from the Media reference, rendered inline |
| Other attachments (sketches, maps, etc.) | Rendered as a labeled placeholder card |
| Format input toolbar | Works — paragraph style picker, list toggles (bullet/dash/numbered/checkbox), inline B/I/U/S, link |
| Tap-to-toggle checkbox | Works in formatted view (auto-saves) |
| Conflict UX | Auto-retry on CONFLICT/oplock; no concurrent-edit merge yet |

## The most important thing we think we learned (not yet end-to-end confirmed)

**Strong hypothesis: Apple's replica UUID must be RFC 4122 v4.** This is the
last byte-level difference between an Android-created note that got trashed
(`ab-fresh-162944`) and an iCloud.com-created note that survived (`ic-create-test-001`).
Both had identical proto structure, same fields, same compression. Only the
UUID's version nibble differed.

When we created a note from Android with our previous (raw-random) UUID, Mac's
notesync added housekeeping fields, **left the body proto bytes intact**, and
**silently set `Deleted=1`** within a few minutes — the note appeared in
Recently Deleted on Mac. iCloud.com-created notes (which use v4 UUIDs) survive
the same flow.

**Caveat**: we shipped the v4 UUID fix in commit `3ee1a60` but haven't yet
confirmed end-to-end that a v4-UUID-created note survives Mac quit/reopen. If
you pick this up and v4 turns out not to be the only thing Mac validates, the
next things to investigate are (a) the substring tree shape (iCloud.com always
emits multiple substrings with tombstone separators around line breaks; we emit
one substring), (b) attribute_runs count (iCloud.com splits at trailing `\n`;
we don't), and (c) the per-replica `counter2` field (iCloud.com uses a
non-trivial value; we use 1).

`SecureRandom().nextBytes(16)` produces raw bytes — only ~1/16 chance the version
nibble is 4. Use `java.util.UUID.randomUUID()`, or set the bits explicitly:

```kotlin
val bytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
bytes[6] = ((bytes[6].toInt() and 0x0F) or 0x40).toByte()  // version = 4
bytes[8] = ((bytes[8].toInt() and 0x3F) or 0x80).toByte()  // variant = 10
```

See [DeviceIdentity.kt](app/src/main/java/com/example/applenotes/auth/DeviceIdentity.kt)
and commit `3ee1a60`.

## How Apple Notes works (the parts that matter)

### CloudKit layer

- Endpoint: `https://p<N>-ckdatabasews.icloud.com.cn` for Chinese accounts, `.com`
  otherwise. Path: `/database/1/com.apple.notes/production/private/...`.
- Auth: cookie-based (`X-APPLE-WEBAUTH-TOKEN`). We harvest cookies via Android's
  `WebView` flow in [`auth/`](app/src/main/java/com/example/applenotes/auth/).
- Zone: `Notes`. Record types: `Note`, `Folder`, `SearchIndexes`.
- Required fields on `Note` create: `TextDataEncrypted`, `TitleEncrypted`,
  `SnippetEncrypted`, `Folder` (CKReference), `CreationDate`, `ModificationDate`.
- **Date trap**: omit `ModificationDate` and CloudKit defaults it to .NET
  `DateTime.MinValue` (`-62135769600000` ms — year 1). Mac silently skips records
  with that sentinel. Always send `System.currentTimeMillis()`.
- `recordChangeTag` is CloudKit's optimistic-concurrency token. Mac's notesync
  bumps it asynchronously after merging — expect `CONFLICT`/`oplock` errors on
  edits that follow a create. We auto-retry once with a fresh tag.

### Listing notes and folders

- `recordType=Note` query works.
- `recordType=Folder` query is **rejected** by the server: "Type is not marked
  indexable: Folder". Use `/records/lookup` with recordNames pulled from notes'
  `Folder` references instead.
- `SearchIndex` "recents" index exists and works. "folders" returns "No index of
  this name exists".
- Special folder recordNames: `DefaultFolder-CloudKit` ("Notes"),
  `TrashFolder-CloudKit` ("Recently Deleted").
- Notes in trash live under `Folder = TrashFolder-CloudKit`. The `Deleted` field
  is added by Mac housekeeping; don't filter by it. Filter by Folder ref.

### The body proto: `topotext.String`

`TextDataEncrypted` is gzipped (or zlibbed) protobuf. The shape:

```
versioned_document.Document {
  Version version = 2 {
    int32 minimumSupportedVersion = 2;
    bytes data = 3;     // encoded topotext.String
  }
}

topotext.String {
  string string = 2;             // the visible text, concatenation of live substrings
  repeated Substring substrings = 3;
  VectorTimestamp timestamp = 4; // per-replica clocks
  repeated AttributeRun attributeRuns = 5;
}

Substring {
  CharID charID = 1;       // (replicaID, clock) — globally unique
  uint32 length = 2;
  CharID timestamp = 3;    // for tombstone fresh-ts ordering
  bool tombstone = 4;
  repeated int32 child = 5; // forward links into substrings[]
}

CharID {
  uint32 replicaID = 1;    // 1-based index into VectorTimestamp.clock
  uint32 clock = 2;        // Lamport
}
```

Invariants (verified by sampling iCloud.com-created and Mac-created notes):

- The substring array always starts with **doc-start** (`charID=(0,0)`,
  `length=0`) and ends with the **sentinel** (`charID=(0,0xFFFFFFFF)`,
  `length=0`). Don't reorder.
- Children are forward links into the array. The walk produces the visible-order
  chain: doc-start → ... → sentinel.
- New inserts go between doc-start and sentinel; tombstones flip `tombstone=true`
  but stay in the tree.

### CRDT semantics in practice

- Each character ever inserted has a globally-unique `(replicaID, clock)` ID.
  `replicaID=1` is whoever wrote *this* version of the proto; the same UUID may
  be at a different slot in someone else's proto.
- **Slot rotation** matters: when iCloud.com edits a note, it always remaps
  itself to slot 1, demoting everyone else. Mac's notesync uses the rotation as
  a "refresh local cache" signal. We mirror this in
  [`NoteAppender.setBodySpliceBytes`](app/src/main/java/com/example/applenotes/proto/NoteAppender.kt).
- `ReplicaIDToNotesVersionDataEncrypted` is the per-replica version vector. Mac
  uses changes to this field as the cache-invalidation signal. Pass it through
  unchanged on modify (don't strip or rewrite). Without this, Mac duplicates the
  body on multi-substring notes after Android edits.

### Compression

Both gzip (`1f 8b`) and zlib (`78 9c`) are accepted. iCloud.com sends zlib for
short notes; Mac sends gzip with OS=0x13. We send gzip with OS=0xff (Java
default). All three round-trip cleanly. Compression format is **not** the cause
of Mac trashing — we ruled this out by sampling.

### Special characters

- `U+2028` (LINE SEPARATOR) and `U+2029` (PARAGRAPH SEPARATOR) appear in Mac
  notes for soft line breaks inside lists/checkboxes. Don't strip them.
- `￼` (U+FFFC OBJECT REPLACEMENT) is Apple's placeholder for inline attachments.
  We pass through but don't render the attachment.

### Paragraph styles (`AttributeRun.f2.f1`)

| Code | Style |
|---|---|
| 0 | Title (also: implicit for the first line) |
| 1 | Heading |
| 2 | Subheading |
| 3 | Body (also: f1 absent → Body) |
| 4 | Monospaced |
| 100 | Bulleted list |
| 101 | Dashed list |
| 102 | Numbered list |
| 103 | Checkbox list |

(The earlier draft of this README listed 0=Body / 1=Title / 101=Checkbox. Wrong:
verified by sampling a baseline note that contained one of every style.)

Checkbox checked-state lives at `AttributeRun.f2.f5` — a 20-byte sub-message
containing `{f1: 16-byte UUID, f2: 0|1 (done flag)}`, NOT in `AttributeRun.f2.f4`.

### Inline formatting (siblings of `AttributeRun.f2`)

| Field | Meaning |
|---|---|
| `f5` (varint) | font weight enum: 1=Bold, 2=Italic, 3=BoldItalic |
| `f6` (varint) | underlined (bool) |
| `f7` (varint) | strikethrough (bool) |
| `f9` (bytes) | link URL (UTF-8 string) |
| `f12` (msg) | attachment_info `{f1: UUID-string, f2: UTI-string}` (table/image/etc.) |

Decode via [`NoteBodyEditor.parseAttributeRuns`](app/src/main/java/com/example/applenotes/proto/NoteBodyEditor.kt).
Re-encode via [`NoteBodyEditor.encodeAttributeRunField`](app/src/main/java/com/example/applenotes/proto/NoteBodyEditor.kt).

### Table attachments

Tables are stored as separate `Attachment` CKRecords (UTI
`com.apple.notes.table`). Their content lives in `MergeableDataEncrypted` — a
zlib-deflate'd protobuf encoding Apple's MergeableData CRDT graph. The graph
nodes form a typed object soup:

- **KeyItems** (field 4): string field names — `self`, `crRows`, `crColumns`,
  `cellColumns`, `crTableColumnDirection`, `identity`, `UUIDIndex`.
- **TypeItems** (field 5): string type names — `com.apple.CRDT.NSString`,
  `com.apple.CRDT.NSUUID`, `com.apple.notes.ICTable`, etc. Indexed.
- **UUIDItems** (field 6): 16-byte UUIDs. Indexed.
- **GraphObjects** (field 3): repeated, indexed. Each is one of:
  - `f1` List, `f6` Dictionary, `f10` String (with f2 = current text),
    `f13` CustomMap (typed map, like ICTable), `f16` OrderedSet.

The root ICTable's CustomMap has entries `crRows` (OrderedSet of row UUIDs),
`crColumns` (OrderedSet of column UUIDs), and `cellColumns` (Dict of
col_uuid → Dict of row_uuid → string_obj_id). Walk those to extract a 2D
grid; resolve each string_obj_id to its NSString.currentText.

See [`MergeableDataDecoder.kt`](app/src/main/java/com/example/applenotes/proto/MergeableDataDecoder.kt).
The decoder uses cellColumns iteration order as the column order and
first-appearance order across columns as the row order — crRows/crColumns
OrderedSets carry the canonical visual order via a separate UUIDIndex layer
that we don't fully reconstruct yet, but the iteration order matches the
visual order for tables created left-to-right top-to-bottom.

### Image attachments

Image Attachment records carry a `Media` field that's a CKReference to a
separate "Media" record. Look up the media record; its `Asset` (or
`MediaEncrypted`) field is a CKAsset whose `value.downloadURL` is the binary's
signed download URL. GET it with the session's cookies.

## What we tried that failed

These were dead ends; documenting so you don't repeat them.

- **Markdown rendering as a stopgap.** Tempting because it's easy, but Apple's
  notes don't have markdown syntax in their body bytes — they have
  `attributeRuns` metadata. Rendering markdown on the Android side would mean
  literal `**bold**` showing up everywhere on Mac. Use attribute_runs, even with
  partial coverage.
- **Empty `createNote(title="", body="")` then `modifyNoteBody`.** This races
  Mac's notesync housekeeping and produces records that get trashed within
  minutes. Even with the v4 UUID fix this is risky. Defer createNote until the
  first save with content (we do; see commit `55704ae`).
- **Querying for folders by `recordType=Folder`.** Server rejects with "Type is
  not marked indexable: Folder". Use `/records/lookup` with recordNames pulled
  from notes' Folder refs.
- **Trusting CloudKit's `Deleted` field as the trash discriminator.** Mac
  housekeeping adds it, but it's also present on alive notes. Filter by Folder
  ref pointing at `TrashFolder-CloudKit` instead.
- **`fetchRecents(1)`.** The server returns 0 results for tiny limits even when
  notes exist. Use ≥ 50.
- **Driving the FAB via `adb shell input tap` coordinates.** Brittle — emulator
  scaling means coords drift between sessions. Use `uiautomator dump` if you
  must, or just exercise the same code paths via `DebugReceiver` broadcasts.
- **Recreating the device's replica UUID per session.** Apple has a per-note
  replica cap and your registry will explode. Persist once, reuse forever.
- **Skipping `ReplicaIDToNotesVersionDataEncrypted` on modify.** Mac duplicates
  the body locally after multi-substring edits. Always pass it through.

## What works (and how to verify)

| What | Test |
|---|---|
| Read existing Mac notes | Open any note in the app — body should match Mac |
| Append edit round-trips | Add text on Android → save → check Mac after relaunch |
| Mid-text splice round-trips | Edit existing text on Android → save → verify on Mac |
| Create new note with v4 UUID | Tap FAB → type → back → verify note appears (and stays) on Mac |
| Auto-save | Type, swipe back without explicit save → reopen → text persists |
| Share OUT | Detail screen → share button → Android chooser appears |
| Folder filter | Drawer → select folder → list filters; trash hidden by default |

The `DebugReceiver` exposes the same code paths via `adb` broadcasts:

```bash
# List all notes
adb shell am broadcast -p com.example.applenotes -a com.example.applenotes.LIST

# Look up a specific note by title
adb shell am broadcast -p com.example.applenotes -a com.example.applenotes.LOOKUP_BY_TITLE --es title 'foo'

# Append to an existing note
adb shell am broadcast -p com.example.applenotes -a com.example.applenotes.APPEND_BY_TITLE \
  --es title 'foo' --es text 'extra line'

# Create a new note
adb shell am broadcast -p com.example.applenotes -a com.example.applenotes.CREATE \
  --es title 'newnote' --es body 'hello'
```

Watch with `adb logcat -s AppleNotesClient:* AppleNotesDebug:*`.

## Code tour

```
app/src/main/java/com/example/applenotes/
├── auth/                      WebView cookie harvest, session refresh, DeviceIdentity (v4 UUID!)
├── client/                    AppleNotesClient: HTTP layer, fetchRecents/lookupNote/createNote/modifyNoteBody
├── proto/
│   ├── ProtobufWire.kt       Hand-rolled protobuf wire format (no schema dep)
│   ├── Gzip.kt               gzip + zlib detect/compress/decompress
│   ├── NoteBodyEditor.kt     Decode topotext.String → visible text + attribute runs
│   ├── NoteAppender.kt       Append / splice / setBody — produces new proto bytes
│   └── NoteCreator.kt        Build proto for a fresh note
├── debug/DebugReceiver.kt    Broadcast-driven CLI for testing without UI
└── ui/AppleNotesApp.kt       Compose UI: list, detail, FAB draft state, drawer, save flow
```

Read order if you're new:

1. `NoteBodyEditor.kt` — get a feel for what a substring tree looks like.
2. `AppleNotesClient.kt` — see the wire shape of every CloudKit op.
3. `NoteAppender.setBodySpliceBytes` — the slot-promotion pattern in detail.
4. `AppleNotesApp.kt` — `ScreenState.Detail` and the deferred-create FAB flow.

## Build / run

```bash
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.example.applenotes/.MainActivity
```

You'll need an Android emulator or device with Google Play Services. Sign in
with an Apple ID that has Notes enabled in iCloud. The first launch goes through
the WebView auth flow.

## Advice for the next person/agent

**Use iCloud.com web for A/B comparisons.** When something doesn't work, the
fastest way to figure out what Apple expects is to make iCloud.com produce the
same operation on a parallel test note, then diff the bytes. We caught the v4
UUID issue this way after exhausting other theories.

**Sample multiple healthy notes before drawing conclusions.** Bisha and Money
maker (Mac-created, alive) gave us a baseline. ic-create-test-001
(iCloud.com-created, alive) gave us another. Newtest1-4 and Made-in-android
(Android-created, trashed) showed the failure mode. The trashed ones had
attributes the alive ones never had — but only the UUID was a *consistent*
discriminator across all of them.

**Don't trust a single failed test.** Mac trashes records asynchronously, on
notesync, on quit/reopen — sometimes minutes after creation. A note that "looks
fine right after save" may still get trashed an hour later when Mac next runs
notesync. Wait, then re-check.

**The CRDT is real.** char-IDs are stable across remote edits. Insertions slot
in by parent char-ID, not position. Tombstones are commutative. If you're
writing new code that touches the proto, encode operations as `(parentCharID,
chars)` or `(charIDs to tombstone)`, not as positional diffs. This makes the
v2 conflict-resolution work (re-splice on stale base) trivial — see the
"What this means for our code" discussion in commit history.

**Match iCloud.com's payload shape, not just the field names.** When in doubt,
look at what iCloud.com sends. We removed `PaperStyleType` / `AttachmentViewType`
/ `Deleted` from `createNote` because iCloud.com doesn't send them — server
defaults handle it. Sending zeros made the new record look "edited" and racy.

**Pre-flight checks before edits.** Always re-`lookupNote` to get a fresh
`recordChangeTag` before submitting a modify, OR rely on the auto-retry on
CONFLICT (we do the latter). The post-create race is real and consistent.

**Write logs that show wire shape.** `summarizeBase64` in `NoteBodyEditor` dumps
ops/replicas/attr_runs in one line. That's how we caught most issues. When you
add a new field, add a corresponding summary line.

**Don't speculate about validation rules in production.** Apple's clients run
validation we can't see. The only authoritative way to know if a write is
accepted is to watch what Mac does to it over time. Build the iteration loop
short — `adb broadcast` → read response → `lookupNote` again — and you'll find
issues fast.

**Read `PLAN.md` before adding new features.** It records decisions that are
not obvious from the code (markdown vs attribute_runs, lifecycle-only auto-save,
empty-body new-note flow). Don't relitigate without reason.

## Known limitations

- **Concurrent-edit conflicts**: the auto-retry resubmits the original body
  bytes, which last-writer-wins on a true concurrent edit. Safe for the
  post-create housekeeping race we hit in practice. The proper v2 fix is to
  decompose edits into char-ID-keyed ops and replay against a fresh base on
  conflict; the design is sketched in commit `0462bcb`'s discussion thread but
  not yet implemented.
- **Modern `MergableData` proto is not supported.** Newer Apple Notes (iOS 17+
  collaborative notes?) use a different shape. We refuse to operate on those —
  see `NoteBodyEditor.probe`. Read-only support would be a starting point.
- **Image attachments display as `￼`.** Decoding the `Attachment` CKRecord type
  is not implemented.
- **No formatting toolbar yet.** We decode paragraph styles but the editor only
  shows plain text. UI work pending (Phase D2 in PLAN.md).
- **No subscription / push.** Polls when the user opens the app. Background sync,
  CloudKit subscriptions, and a send queue are all unimplemented.

## A note on "ENCRYPTED" field names

CloudKit serializes `TitleEncrypted`, `SnippetEncrypted`, and `TextDataEncrypted`
as `ENCRYPTED_STRING` / `ENCRYPTED_BYTES` types — but the actual byte values
sent over the wire are just **base64-encoded UTF-8** (for Title/Snippet) and
**gzipped protobuf** (for TextData). There is no client-side encryption layer
involved. The names appear to be a CloudKit historical artifact; iCloud's E2EE
("Advanced Data Protection") is a separate keystore mechanism we're not
exercising.

If you `kotlin.io.encoding.Base64.decode(titleField.value).decodeToString()`
you get the cleartext title. That's the entire "decryption" step.

## Disclaimer

This project is unaffiliated with Apple. It uses Apple's public CloudKit Web
Services API with the user's own credentials. It reads and writes notes the
authenticated user already has access to. The proto formats were inferred from
observation of round-tripping the user's own data through their own iCloud
account; no Apple-internal documentation or reverse-engineered binaries were
consulted.

If you're going to publish anything based on this, be respectful. Don't redistribute
captured proto data from other users' accounts. Don't ship anything that would
encourage account credential sharing.
