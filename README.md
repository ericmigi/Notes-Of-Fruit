# Notes of Fruit

An Android client for iCloud Notes. Reverse-engineered from CloudKit's private DB
API + Apple's `topotext` CRDT proto. Reads and writes notes that round-trip with
Mac's Apple Notes and iCloud.com.

This is a research codebase, not a polished product. Treat the README as a field
report for the next person (or agent) who picks it up.

## Status

| Capability | Status |
|---|---|
| Sign in with Apple ID (cookie-based) | Works |
| List notes (recents, paginated) | Works |
| Folder navigation (sidebar, trash filter) | Works |
| Read note content (decode topotext proto) | Works for `NOTE_STORE_PROTO`; not the modern `MergableData` shape |
| Append text | Works (round-trips with Mac) |
| Mid-text splice (insert/replace/delete) | Works (matches iCloud.com's slot-promotion pattern) |
| Create new note (FAB → save) | Works **only with v4 UUIDs** — see "The UUID gotcha" below |
| Auto-save on lifecycle pause | Works |
| Share OUT (Android intent chooser) | Works (plaintext) |
| Delete | Works (`forceDelete` removes the record entirely) |
| Format display (headings, lists, checkboxes) | Decoded but not rendered yet (Phase D1 done; D2 pending) |
| Format input toolbar | Not built |
| Image attachments | Renders `￼` stub; no image display |
| Conflict UX | Auto-retry on CONFLICT/oplock; no concurrent-edit merge yet |

## The most important thing you'll learn here

**Apple's replica UUID must be RFC 4122 v4.** This is not documented anywhere.
We spent hours diffing protos before noticing.

When you create a note from a foreign client, Apple Notes on Mac runs notesync,
adds housekeeping fields, and **silently sets `Deleted=1`** if the sole replica's
UUID isn't v4 (byte 6 high nibble = 0x4, byte 8 high two bits = 0b10). The body
proto is left intact. The note appears in Recently Deleted within a few minutes.

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

### Paragraph styles (in `AttributeRun.f2.f3`)

| Code | Style |
|---|---|
| 0 | Body |
| 1 | Title |
| 2 | Heading |
| 3 | Subheading |
| 4 | Monospaced |
| 100 | Bulleted list |
| 101 | Checkbox (with `f4=1` if checked) |
| 102 | Numbered list |

`f9` inside `f2` is a 16-byte paragraph UUID (Mac uses these; iCloud.com doesn't,
yet both round-trip). Decode via
[`NoteBodyEditor.parseAttributeRuns`](app/src/main/java/com/example/applenotes/proto/NoteBodyEditor.kt).

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

## Disclaimer

This project is unaffiliated with Apple. It uses Apple's public CloudKit Web
Services API with the user's own credentials. It does not break encryption — it
reads notes the authenticated user already has access to. The proto formats
were inferred from observation of the user's own data round-tripping with their
own iCloud account; no Apple-internal documentation was consulted.

If you're going to publish anything based on this, be respectful. Don't redistribute
captured proto data from other users' accounts. Don't ship anything that would
encourage account credential sharing.
