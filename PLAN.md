# Notes of Fruit — CEO-Review Plan

## 1. Premise check

We have an Android client for iCloud Notes that does the technically hardest
thing well (CRDT-correct write path that survives Mac's notesync). What's
missing is **product**. Right now it feels like a debug harness with a Material
3 skin. To be the thing you'd actually use, three categories of work matter:

| Category | What it is | What it earns |
|---|---|---|
| **Trust** | Auto-save, no data loss, list-state preservation | You can rely on it for real notes |
| **Reach** | New-note creation, share in/out, formatting fidelity | It does what Apple Notes does, where Apple Notes won't go (Android) |
| **Feel** | Native gestures, instant list, proper typography, formatted text rendering | Disappears into the OS like Apple Notes does on iOS |

If we only get **Trust + Reach**, you have a usable utility. **Feel** is what
makes it the daily-driver.

## 2. The 10-star vision (anchor point — not the build target)

Pretend Apple wrote this themselves and shipped it as the official Apple Notes
for Android. What's in v1?

1. Identical visual language to iOS Notes (yellow accent, paper feel, serif
   body, real headings).
2. Auto-save, always. No save button.
3. Pinch-to-zoom typography. Swipe-from-left to delete in list.
4. Folder navigation (sidebar-style on tablet, drill-down on phone).
5. Search-as-you-type with snippet highlighting.
6. Share OUT (any note → other apps) and Share IN (other apps → new note).
7. Format toolbar with iOS-Notes-style buttons: Title, Heading, Subheading,
   Body, Monospace, Bullet list, Numbered list, Checklist.
8. Render checkboxes as actual tappable checkboxes. Render headings.
9. Image attachments displayed inline (read-only OK for v1).
10. "Edited 2m ago on Mac" multi-device awareness.
11. Biometric lock (per-app and per-note).
12. Widget for pinning notes to home.
13. Offline cache + send-queue: read your library without a connection,
    edit and queue saves.
14. Conflict UX: when iCloud sees both Android and Mac edits in the same
    window, prompt or auto-merge with visible diff.

## 3. What's missing today (gap audit, ranked by user impact)

| # | Gap | Severity | User-visible impact |
|---|---|---|---|
| 1 | No "new note" button | 🔴 critical | App is read-only-ish unless user's already got the note from another device |
| 2 | No auto-save | 🔴 critical | Type a paragraph, get a phone call, lose it |
| 3 | Format display (headings, lists, checkboxes) | 🟠 high | Notes from Mac look like flat plaintext blobs |
| 4 | Format input (toolbar) | 🟠 high | Anything you write from Android looks plain on Mac |
| 5 | Share Sheet OUT | 🟡 medium | Can't easily forward a note's text |
| 6 | Share Sheet IN | 🟡 medium | Can't capture from another app into a note |
| 7 | No-refresh on back ✅ done | n/a | Already shipped |
| 8 | Old branding ("Apple Notes") ✅ done | n/a | Renamed to "Notes of Fruit" in strings.xml |
| 9 | Folder navigation | 🟢 low | Workaround: titles are searchable |
| 10 | Search-as-you-type ✅ already there | n/a | Works; could improve highlight |
| 11 | Image attachments display | 🟢 low | Body shows a `￼` stub; not catastrophic |
| 12 | Pin/lock/biometric | 🟢 low | Power-user feature |
| 13 | Widget | 🟢 low | Power-user feature |
| 14 | Offline mode | 🟢 low | Most edits are online; conflict-free CRDT means we can ship without it for v1 |

## 4. CEO decisions (self-answered)

### Q1: Markdown or Apple's native attribute_runs?

**Decision: native attribute_runs.** Markdown looks like a shortcut, but
because Mac/iCloud renders attribute_runs (not markdown), any markdown we
write would show literal `**` and `#` on every other device. The whole point
of Notes of Fruit is bidirectional fidelity with iCloud. Bite the bullet on
attribute_runs.

We'll accept partial coverage — decode/render the **paragraph styles** Apple
uses most (title, heading, subheading, body, monospace, bullet, numbered,
checkbox) and ignore the rare ones (color, highlight, custom fonts) for v1.
Round-trip perfect on the common 90% beats markdown 100% of the time.

### Q2: Auto-save — debounce or lifecycle-only?

**Decision: lifecycle-only for v1.** A debounced "save 2s after last
keystroke" save is more network traffic and forces us to handle racing saves.
For v1, save on:
- back press (before leaving the screen)
- app pause / stop (lifecycle ON_PAUSE)
- explicit save tap (already there, becomes optional)

Add a tiny "Saved Xs ago" pill in the top app bar so the user knows.

### Q3: New-note flow — empty body, or a default template?

**Decision: empty body with auto-focus on the body editor.** No "Untitled"
placeholder in the title bar — it stays blank until they type a first line,
which becomes the title (matches Apple Notes behavior).

### Q4: Share OUT — plaintext only, or keep formatting?

**Decision: plaintext only for v1.** Simplest to implement, maximum
compatibility with target apps (Messages, email, etc). v2 could add "share
as Markdown" or "share as PDF" if there's demand.

### Q5: Share IN — implement now or defer?

**Decision: defer to v2.** It's `<intent-filter android:name="android.intent.action.SEND"`
plus a "create note from incoming text" flow plus Android Share-Sheet target
registration. Not hard, but the new-note FAB already gives a fast path. Don't
need Share IN yet.

### Q6: New-note FAB or top-bar button?

**Decision: FAB.** That's the Material 3 idiom for "primary create action".
The icon (Add) is already imported; FloatingActionButton is already imported.
Two-line wire-up.

### Q7: Markdown vs attribute_runs — what about iOS-typed notes that use **bold**?

**Decision: ignore for v1.** Apple users don't actually type `**bold**` —
they use the format toolbar. Existing Apple Notes don't have markdown syntax
in their body bytes; they have attribute_runs. Verified by sampling Jan 26
todos, Money maker, Oct 2025 plan, Core Tenets, etc.: all plain text bodies
with attribute_run paragraph-style metadata.

### Q8: Package rename (`com.example.applenotes` → `com.example.notesoffruit`)?

**Decision: defer.** Cost: rewrite every Kotlin import, change the
DebugReceiver action names (which our test scripts rely on), and force
existing emulator installs to re-bootstrap (they'd see it as a new app and
lose stored device UUID). Benefit: cosmetic. Wait until we have Play Store
distribution; then rename once.

### Q9: Markdown-style markdown rendering as a stopgap until attribute_runs are decoded?

**Decision: no.** Conflicts with Q1. Doing both means user sees `**bold**`
literal on Mac side AND headings rendered in our app — inconsistent
mental model.

## 5. Build sequence (medium scope, ~1 focused build session)

| Phase | Deliverable | Estimated effort | Risk |
|---|---|---|---|
| **A. Trust quick wins** | Auto-save (lifecycle), saved-pill in top bar | S | Low |
| **B. Reach quick wins** | New-note FAB; Share OUT toolbar button | S | Low |
| **C. Format display** | Decode paragraph style codes; render headings/lists/checkboxes in detail view | M | Medium — need to dump and decode `f3/w0=N` style codes from a few formatted notes |
| **D. Format editor** | Toolbar with: Title, Heading, Body, Bullet, Number, Checkbox; writes proper attribute_runs | L | High — need to splice attribute_runs alongside the body splice |
| **E. Polish** | Saved pill animation; "Edited 2m ago" pill on list; empty-state copy | S | Low |

**Stop after C if D's risk feels too high in one session.** Even just display
of formatted notes is a huge UX upgrade — you'd be able to read your existing
formatted notes from Android for the first time.

## 6. Out of scope (named explicitly)

- Markdown syntax of any kind (per Q1)
- Folder navigation
- Image attachment display (will continue showing `￼` stub)
- Pin/lock/biometric
- Widget
- Offline cache + send queue
- Multi-device awareness ("edited on Mac 2m ago")
- Package rename
- Share Sheet IN

## 7. Risks / unknowns to flag

- **Format editor (Phase D) is the only risk-bearing piece.** Apple's
  attribute_run format isn't fully decoded; we know `f1=length, f3/w0=paragraphStyleCode, f9=UUID, f13=timestamp` but the inner structure of `f2` may include style fields we haven't mapped. Mitigation: dump a heading-formatted note and a checkbox note from Mac, diff against a body-only note, name the unknown bytes, then write code only for the codes we recognize. Anything unrecognized: pass through unchanged.
- **Auto-save races with manual save**: solved by gating the lifecycle
  auto-save behind `if (isModified && !saving)`.
- **Share OUT permission**: just an `Intent.ACTION_SEND` chooser — no
  permission needed.
- **New-note CREATE flow** has been seen to occasionally produce a note Mac
  shows with empty body. We saw this earlier in the session. Need to verify
  it works cleanly end-to-end, may need to revisit `NoteCreator.kt`.

## 8. Definition of done (per phase)

**A. Auto-save**: type 5 chars, swipe back, reopen note → 5 chars persisted.
Type 5 chars, push app to background, kill app, reopen → 5 chars persisted.

**B. New-note + Share**: FAB → empty note opens, type a title, swipe back,
note appears in list with that title and syncs to Mac. Share button on
detail → Android chooser appears with the body's plaintext.

**C. Format display**: Open Jan 26 todos and any heading-formatted note from
Mac → headings render in larger weight, bullet lists render with bullets,
checkboxes render as tappable boxes (read-only OK).

**D. Format editor**: New empty note, type a line, tap Heading button on
toolbar, that line becomes a heading. Save. View on Mac → Mac shows the
line as a heading.

## 9. Open questions for the user

None. CEO mode = self-answer everything. If a decision turns out wrong,
revisit between phases.
