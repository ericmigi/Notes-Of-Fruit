package com.example.applenotes.ui

import android.annotation.SuppressLint
import android.util.Log
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.automirrored.filled.Note
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.FormatBold
import androidx.compose.material.icons.filled.FormatItalic
import androidx.compose.material.icons.filled.FormatStrikethrough
import androidx.compose.material.icons.filled.FormatUnderlined
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.HorizontalRule
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.filled.FormatListNumbered
import androidx.compose.material3.Surface
import androidx.compose.ui.draw.clip
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.foundation.layout.size
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import android.content.Intent
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.applenotes.auth.AppleNotesSession
import com.example.applenotes.auth.CookieReader
import com.example.applenotes.auth.DeviceIdentity
import com.example.applenotes.auth.ICloudSession
import com.example.applenotes.auth.USER_AGENT
import com.example.applenotes.client.AppleNotesClient
import com.example.applenotes.client.FolderSummary
import com.example.applenotes.client.NoteRecord
import com.example.applenotes.client.NoteSummary
import com.example.applenotes.proto.NoteAppender
import com.example.applenotes.proto.NoteBodyEditor
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.launch

private const val TAG = "AppleNotesUi"
private const val ICLOUD_NOTES_URL = "https://www.icloud.com/notes"

private sealed interface ScreenState {
    data object Splash : ScreenState
    data object Login : ScreenState
    data class Loading(val msg: String) : ScreenState
    data class Error(val msg: String) : ScreenState
    data class NotesList(
        val session: ICloudSession,
        val notes: List<NoteSummary>,
        val folders: List<FolderSummary>,
        /**
         * Currently-selected folder recordName. Null = "All notes" pseudo-folder
         * (every note across user-visible folders). Use [TRASH_RECORD_NAME] to
         * show Recently Deleted explicitly.
         */
        val selectedFolder: String? = "DefaultFolder-CloudKit",
    ) : ScreenState
    /**
     * Detail keeps the cached [notesCache] from the list view so back-nav
     * restores the list instantly without a `fetchRecents` round trip.
     * `notesCache` is updated in place when we save (replacing the just-saved
     * note's summary) so the user sees the new snippet on return.
     */
    data class Detail(
        val session: ICloudSession,
        val record: NoteRecord,
        val notesCache: List<NoteSummary>,
        val folders: List<FolderSummary>,
        val selectedFolder: String?,
        /**
         * Set when this Detail represents a brand-new note that has NOT yet
         * been written to iCloud. The first save calls `createNote` with the
         * user's typed content (title + body in one shot); subsequent saves
         * use `modifyNoteBody`. We match iCloud.com's pattern: it never does
         * empty-create-then-modify — it only creates the record once there's
         * content to write. Empty-create + modify races Mac's notesync
         * housekeeping and produces records Mac later trashes.
         *
         * When non-null, [record] is a stub (recordChangeTag = null,
         * recordName = "", empty fields).
         */
        val pendingFolderRef: kotlinx.serialization.json.JsonElement? = null,
    ) : ScreenState
}

private const val TRASH_RECORD_NAME = "TrashFolder-CloudKit"
private const val DEFAULT_FOLDER_RECORD_NAME = "DefaultFolder-CloudKit"

@Composable
fun AppleNotesApp() {
    val context = LocalContext.current
    val cookieReader = remember { CookieReader() }
    val httpClient = remember {
        HttpClient(OkHttp) {
            expectSuccess = false
        }
    }

    var state by remember { mutableStateOf<ScreenState>(ScreenState.Splash) }
    var saving by remember { mutableStateOf(false) }
    var ourReplicaUuid by remember { mutableStateOf<ByteArray?>(null) }
    var lastSavedHint by remember { mutableStateOf<String?>(null) }
    var lastSavedAtMs by remember { mutableStateOf<Long?>(null) }
    val scope = rememberCoroutineScope()

    // First-launch alpha disclaimer. Block all app behaviour behind this — we
    // don't even start the auth bootstrap until the user has acknowledged.
    var alphaAck by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(Unit) {
        alphaAck = com.example.applenotes.AlphaWarning.isAcknowledged(context)
    }
    if (alphaAck == false) {
        AlphaWarningDialog(onAcknowledge = {
            scope.launch {
                com.example.applenotes.AlphaWarning.acknowledge(context)
                alphaAck = true
            }
        })
        return
    }
    if (alphaAck == null) {
        // Still loading the ack flag — render nothing; the splash theme is
        // still showing underneath.
        return
    }

    LaunchedEffect(Unit) {
        // Load (or generate on first run) our stable per-device replica UUID.
        // Same UUID for every note we ever edit on this install; getting a
        // fresh one every session would burn through Apple's per-note replica cap.
        val uuid = DeviceIdentity.getOrCreate(context)
        ourReplicaUuid = uuid
        Log.i(TAG, "device replica uuid=${DeviceIdentity.hexEncode(uuid)}")
    }

    suspend fun bootstrap(): ScreenState = try {
        val session = AppleNotesSession(httpClient, cookieReader).bootstrap().getOrThrow()
        val client = AppleNotesClient(httpClient, session)
        val notes = client.fetchRecents(limit = 1000)
            .filter { !it.deleted }
            .sortedByDescending { it.modificationTimestampMs ?: 0L }
        val folderIds = notes.mapNotNull { it.folderRecordName }.toSet()
        val folders = runCatching { client.lookupFolders(folderIds) }.getOrElse {
            Log.w(TAG, "lookupFolders failed; falling back to ID-only list", it)
            folderIds.map { rn ->
                FolderSummary(
                    recordName = rn,
                    displayName = null,
                    isTrash = rn == TRASH_RECORD_NAME,
                    isDefault = rn == DEFAULT_FOLDER_RECORD_NAME,
                )
            }
        }
        ScreenState.NotesList(session, notes, folders)
    } catch (e: Throwable) {
        Log.e(TAG, "bootstrap failed", e)
        ScreenState.Error(e.message ?: e.toString())
    }

    suspend fun refreshList(session: ICloudSession): List<NoteSummary> =
        AppleNotesClient(httpClient, session).fetchRecents(limit = 1000)
            .filter { !it.deleted }
            .sortedByDescending { it.modificationTimestampMs ?: 0L }

    LaunchedEffect(Unit) {
        // On launch, check for existing session cookies and try to restore.
        val cookies = cookieReader.cookieMap()
        state = if ("X-APPLE-WEBAUTH-TOKEN" in cookies) {
            ScreenState.Loading("Restoring session…").also {
                scope.launch { state = bootstrap() }
            }
        } else {
            ScreenState.Login
        }
    }

    when (val s = state) {
        ScreenState.Splash -> CenteredSpinner("Starting up…")
        ScreenState.Login -> LoginScreen(
            onCookiesArrived = {
                scope.launch {
                    state = ScreenState.Loading("Signing in…")
                    state = bootstrap()
                }
            },
        )
        is ScreenState.Loading -> CenteredSpinner(s.msg)
        is ScreenState.Error -> ErrorScreen(
            message = s.msg,
            onRetry = {
                scope.launch {
                    state = ScreenState.Loading("Retrying…")
                    state = bootstrap()
                }
            },
            onSignOut = {
                cookieReader.clearAuthCookies()
                state = ScreenState.Login
            },
        )
        is ScreenState.NotesList -> NotesListScreen(
            allNotes = s.notes,
            folders = s.folders,
            selectedFolder = s.selectedFolder,
            onSelectFolder = { folderRn ->
                state = s.copy(selectedFolder = folderRn)
            },
            onSelect = { note ->
                scope.launch {
                    state = ScreenState.Loading("Loading note…")
                    state = try {
                        val rec = AppleNotesClient(httpClient, s.session).lookupNote(note.recordName)
                        ScreenState.Detail(s.session, rec, s.notes, s.folders, s.selectedFolder)
                    } catch (e: Throwable) {
                        ScreenState.Error(e.message ?: e.toString())
                    }
                }
            },
            onRefresh = {
                scope.launch {
                    state = ScreenState.Loading("Refreshing…")
                    state = try {
                        val client = AppleNotesClient(httpClient, s.session)
                        val notes = client.fetchRecents(limit = 1000)
                            .filter { !it.deleted }
                            .sortedByDescending { it.modificationTimestampMs ?: 0L }
                        val folderIds = notes.mapNotNull { it.folderRecordName }.toSet()
                        val folders = runCatching { client.lookupFolders(folderIds) }.getOrElse { s.folders }
                        ScreenState.NotesList(s.session, notes, folders, s.selectedFolder)
                    } catch (e: Throwable) {
                        ScreenState.Error(e.message ?: e.toString())
                    }
                }
            },
            onCreateNote = {
                scope.launch {
                    val uuid = ourReplicaUuid
                    if (uuid == null) {
                        state = ScreenState.Error("Device replica UUID not loaded yet — try again in a moment.")
                        return@launch
                    }
                    state = ScreenState.Loading("Creating note…")
                    state = try {
                        val client = AppleNotesClient(httpClient, s.session)
                        // Grab a Folder CKReference from any existing note in
                        // the user-visible set. We need this BEFORE the user
                        // types anything because createNote requires a folder
                        // ref. Prefer the currently-selected folder so the
                        // new note lands where the user is browsing.
                        val sample = s.notes
                            .firstOrNull { it.folderRecordName == s.selectedFolder }
                            ?: s.notes.firstOrNull { it.folderRecordName != TRASH_RECORD_NAME }
                            ?: error("No existing note to copy folder reference from")
                        val sampleRecord = client.lookupNote(sample.recordName)
                        val folderRef = sampleRecord.rawFields["Folder"]
                            ?: error("Sample note has no Folder field")
                        // DEFERRED CREATE: do NOT call createNote yet. iCloud.com
                        // doesn't either — clicking "new note" just opens an
                        // empty editor; the server-side record only gets
                        // created on the first save with the user's actual
                        // content. Empty-create-then-modify races Mac's
                        // notesync housekeeping and produces records that
                        // Mac later trashes (we observed this: notes appearing
                        // briefly then vanishing on Mac restart).
                        //
                        // The draft gets a unique local recordName ("draft-<uuid>")
                        // so the post-save state-update can disambiguate
                        // multiple drafts in flight (back, FAB-again, type,
                        // save races back into the original Detail).
                        val draftId = "draft-" + java.util.UUID.randomUUID().toString()
                        val draftRecord = NoteRecord(
                            recordName = draftId,
                            recordType = "Note",
                            recordChangeTag = null,
                            rawFields = emptyMap(),
                        )
                        ScreenState.Detail(
                            session = s.session,
                            record = draftRecord,
                            notesCache = s.notes,
                            folders = s.folders,
                            selectedFolder = s.selectedFolder,
                            pendingFolderRef = folderRef,
                        )
                    } catch (e: Throwable) {
                        Log.e(TAG, "createNote (folder lookup) failed", e)
                        ScreenState.Error(e.message ?: e.toString())
                    }
                }
            },
            onSignOut = {
                cookieReader.clearAuthCookies()
                state = ScreenState.Login
            },
        )
        is ScreenState.Detail -> NoteDetailScreen(
            record = s.record,
            saving = saving,
            savedHint = lastSavedHint,
            lastSavedAtMs = lastSavedAtMs,
            onHintShown = { lastSavedHint = null },
            onFetchAttachment = { rn ->
                runCatching {
                    AppleNotesClient(httpClient, s.session).lookupNote(rn)
                }.getOrNull()
            },
            onFetchAsset = { url ->
                AppleNotesClient(httpClient, s.session).fetchAssetBytes(url)
            },
            onBack = {
                // Restore the cached list instantly. No network round trip —
                // `notesCache` was patched in place when we saved, so the snippet
                // for the just-edited note already reflects our changes.
                state = ScreenState.NotesList(s.session, s.notesCache, s.folders, s.selectedFolder)
            },
            onSave = { newBody, isPureAppend, alsoExit, newSpans ->
                scope.launch {
                    saving = true
                    try {
                        val uuid = ourReplicaUuid
                            ?: error("Device replica UUID not loaded yet — try again in a moment.")
                        val client = AppleNotesClient(httpClient, s.session)
                        val (newTitle, newSnippet) = computeTitleAndSnippet(newBody)

                        val updated: NoteRecord
                        val patchedCache: List<NoteSummary>

                        if (s.record.recordChangeTag == null) {
                            // DRAFT: this is the first save for a FAB-created
                            // note. Call createNote with the typed content in
                            // one shot — matching iCloud.com's pattern. The
                            // proto produced by NoteCreator already has the
                            // full content as a single substring + matching
                            // attribute_run, so Mac's notesync sees a
                            // self-consistent record on first sight.
                            val folderRef = s.pendingFolderRef
                                ?: error("Draft has no folderRef — refresh first.")
                            // Split fullBodyForSave back into title + body the
                            // same way it was assembled upstream (line 945-950
                            // in NoteDetailScreen): the first '\n' is the
                            // title/body separator. computeTitleAndSnippet's
                            // `newTitle` is derived heuristically (first
                            // non-blank line, possibly trimmed) and would
                            // misbehave under leading whitespace / leading
                            // blank lines, duplicating content into the proto.
                            val firstNlIdx = newBody.indexOf('\n')
                            val createTitle = if (firstNlIdx < 0) newBody else newBody.substring(0, firstNlIdx)
                            val createBody = if (firstNlIdx < 0) "" else newBody.substring(firstNlIdx + 1)
                            Log.i(
                                TAG,
                                "save (draft→createNote): title='${createTitle.take(30)}' " +
                                    "body.len=${createBody.length} snippet='${newSnippet.take(30)}'",
                            )
                            updated = client.createNote(
                                title = createTitle,
                                body = createBody,
                                folderRef = folderRef,
                                ourReplicaUuid = uuid,
                            )
                            // Pre-pend a fresh summary into the cache so back-nav
                            // shows the new note immediately without a refetch.
                            val folderRecordName = com.example.applenotes.client.extractReferenceRecordName(folderRef)
                            val newSummary = NoteSummary(
                                recordName = updated.recordName,
                                title = newTitle,
                                snippet = newSnippet,
                                modificationTimestampMs = System.currentTimeMillis(),
                                deleted = false,
                                folderRecordName = folderRecordName,
                            )
                            patchedCache = listOf(newSummary) + s.notesCache
                        } else {
                            // EXISTING NOTE: incremental modify path.
                            val tag = s.record.recordChangeTag!!
                            val textB64 = s.record.stringField("TextDataEncrypted")
                                ?: error("No body field on this note.")
                            val originalBody = NoteBodyEditor.readVisibleTextFromBase64(textB64)
                                ?: NoteBodyEditor.readTextFromBase64(textB64).orEmpty()
                            val now = System.currentTimeMillis() / 1000L
                            // Pure-append edits go through the cheap APPEND path
                            // (one new substring at the sentinel slot). Anything
                            // else (replace/insert/delete in the middle) goes
                            // through the splice path which mirrors iCloud.com's
                            // pattern: slot promotion + split-and-tombstone +
                            // fresh ts on tombstones + new chunks for inserts.
                            val newB64 = if (isPureAppend && newSpans == null) {
                                val appended = newBody.removePrefix(originalBody)
                                require(appended.isNotEmpty()) { "Pure-append marked but no new chars" }
                                NoteAppender.appendBase64(textB64, uuid, appended, now)
                            } else {
                                NoteAppender.setBodyBase64(textB64, uuid, newBody, now, newSpans)
                            }
                            val (oldTitle, oldSnippet) = computeTitleAndSnippet(originalBody)
                            Log.i(
                                TAG,
                                "save: title '${oldTitle.take(30)}'->'${newTitle.take(30)}' " +
                                    "snippet '${oldSnippet.take(30)}'->'${newSnippet.take(30)}'",
                            )
                            updated = client.modifyNoteBody(
                                s.record.recordName,
                                tag,
                                newB64,
                                newTitle = newTitle.takeIf { it != oldTitle },
                                newSnippet = newSnippet.takeIf { it != oldSnippet },
                                replicaVersionPassThroughB64 = s.record.stringField("ReplicaIDToNotesVersionDataEncrypted"),
                            )
                            // Patch the cached list summary for this note so when
                            // the user navigates back they see the updated
                            // snippet/title without a refetch.
                            patchedCache = s.notesCache.map { note ->
                                if (note.recordName == s.record.recordName) {
                                    note.copy(
                                        title = newTitle,
                                        snippet = newSnippet,
                                        modificationTimestampMs = System.currentTimeMillis(),
                                    )
                                } else note
                            }
                        }
                        // Defensive state update: the save coroutine may complete
                        // AFTER the user has navigated away (e.g. they tapped back,
                        // we triggered a save+exit, save took 500ms, user is now
                        // looking at the list). If the user is still on this same
                        // Detail, refresh with the updated record. If they've
                        // moved to NotesList, just patch its cache silently. Else
                        // (Error, Splash, Login, different Detail), leave alone.
                        //
                        // Match by recordName for both real notes (CloudKit ID)
                        // and drafts (the local "draft-<uuid>" we minted on FAB
                        // tap). This disambiguates if the user has multiple
                        // drafts in flight: each has its own draft ID, the
                        // save callback closure captured the originating
                        // draft's ID via `s`, and only the matching Detail
                        // gets replaced.
                        val current = state
                        val sameDetail = current is ScreenState.Detail &&
                            current.record.recordName == s.record.recordName
                        state = when {
                            sameDetail && current is ScreenState.Detail ->
                                if (alsoExit) ScreenState.NotesList(current.session, patchedCache, current.folders, current.selectedFolder)
                                else ScreenState.Detail(current.session, updated, patchedCache, current.folders, current.selectedFolder, pendingFolderRef = null)
                            current is ScreenState.NotesList ->
                                current.copy(notes = patchedCache)
                            else -> current
                        }
                        lastSavedAtMs = System.currentTimeMillis()
                    } catch (e: Throwable) {
                        Log.e(TAG, "save failed", e)
                        state = ScreenState.Error(e.message ?: e.toString())
                    } finally {
                        saving = false
                    }
                }
            },
            onDelete = {
                scope.launch {
                    // Draft (never saved to iCloud): just discard locally,
                    // no network call needed.
                    if (s.record.recordChangeTag == null) {
                        state = ScreenState.NotesList(s.session, s.notesCache, s.folders, s.selectedFolder)
                        return@launch
                    }
                    state = ScreenState.Loading("Deleting…")
                    state = try {
                        val tag = s.record.recordChangeTag!!
                        AppleNotesClient(httpClient, s.session).deleteNote(s.record.recordName, tag)
                        // Drop the deleted note from the cache and return to list.
                        ScreenState.NotesList(
                            s.session,
                            s.notesCache.filter { it.recordName != s.record.recordName },
                            s.folders,
                            s.selectedFolder,
                        )
                    } catch (e: Throwable) {
                        Log.e(TAG, "delete failed", e)
                        ScreenState.Error(e.message ?: e.toString())
                    }
                }
            },
        )
    }
}

@Composable
private fun CenteredSpinner(msg: String) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator()
        Spacer(Modifier.height(12.dp))
        Text(msg, style = MaterialTheme.typography.bodyMedium)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ErrorScreen(message: String, onRetry: () -> Unit, onSignOut: () -> Unit) {
    Scaffold(topBar = { TopAppBar(title = { Text("Notes of Fruit") }) }) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Something went wrong", style = MaterialTheme.typography.titleMedium)
            Text(message, style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onRetry) { Text("Retry") }
                Button(onClick = onSignOut) { Text("Sign in again") }
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun LoginScreen(onCookiesArrived: () -> Unit) {
    var detected by remember { mutableStateOf(false) }
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            // Configure cookies for the embedded idmsa.apple.com auth iframe.
            CookieManager.getInstance().setAcceptCookie(true)
            WebView(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.userAgentString = USER_AGENT
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        // Watch for X-APPLE-WEBAUTH-TOKEN landing in the cookie jar.
                        val cookies = CookieManager.getInstance().getCookie("https://www.icloud.com").orEmpty()
                        if (!detected && "X-APPLE-WEBAUTH-TOKEN" in cookies) {
                            detected = true
                            Log.i(TAG, "Login cookies detected (post URL=$url)")
                            onCookiesArrived()
                        }
                    }
                }
                loadUrl(ICLOUD_NOTES_URL)
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NotesListScreen(
    allNotes: List<NoteSummary>,
    folders: List<FolderSummary>,
    selectedFolder: String?,
    onSelectFolder: (String?) -> Unit,
    onSelect: (NoteSummary) -> Unit,
    onRefresh: () -> Unit,
    onCreateNote: () -> Unit,
    onSignOut: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    // Notes filtered to the currently-selected folder. null = "All notes"
    // (every folder except trash). TRASH_RECORD_NAME = trash only.
    val notesInFolder = remember(allNotes, selectedFolder) {
        when (selectedFolder) {
            null -> allNotes.filter { it.folderRecordName != TRASH_RECORD_NAME }
            else -> allNotes.filter { it.folderRecordName == selectedFolder }
        }
    }
    val filtered = remember(notesInFolder, query) {
        if (query.isBlank()) notesInFolder
        else notesInFolder.filter {
            (it.title?.contains(query, ignoreCase = true) == true) ||
                (it.snippet?.contains(query, ignoreCase = true) == true)
        }
    }
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val drawerScope = rememberCoroutineScope()
    // System back closes the drawer if it's open; otherwise let it fall
    // through (the activity-default behaviour exits the app from the list).
    androidx.activity.compose.BackHandler(enabled = drawerState.isOpen) {
        drawerScope.launch { drawerState.close() }
    }
    val selectedFolderName = remember(selectedFolder, folders) {
        when (selectedFolder) {
            null -> "All notes"
            TRASH_RECORD_NAME -> "Recently Deleted"
            DEFAULT_FOLDER_RECORD_NAME -> folders.firstOrNull { it.recordName == DEFAULT_FOLDER_RECORD_NAME }
                ?.displayName ?: "Notes"
            else -> folders.firstOrNull { it.recordName == selectedFolder }?.displayName ?: "Folder"
        }
    }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            FoldersDrawer(
                folders = folders,
                allNotes = allNotes,
                selectedFolder = selectedFolder,
                onSelect = { rn ->
                    onSelectFolder(rn)
                    drawerScope.launch { drawerState.close() }
                },
                onSignOut = {
                    drawerScope.launch { drawerState.close() }
                    onSignOut()
                },
            )
        },
    ) {
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                navigationIcon = {
                    IconButton(onClick = {
                        drawerScope.launch { drawerState.open() }
                    }) {
                        Icon(Icons.Default.Menu, contentDescription = "Folders")
                    }
                },
                title = {
                    Column {
                        Text(selectedFolderName, style = MaterialTheme.typography.displayLarge)
                        Text(
                            "${notesInFolder.size} note${if (notesInFolder.size == 1) "" else "s"}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onRefresh) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = onCreateNote,
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ) {
                Icon(Icons.Default.Add, contentDescription = "New note")
            }
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            // Search bar
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                placeholder = { Text("Search notes") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) {
                            Icon(Icons.Default.Close, contentDescription = "Clear")
                        }
                    }
                },
                singleLine = true,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surface,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                ),
                shape = MaterialTheme.shapes.large,
            )
            if (filtered.isEmpty()) {
                EmptyNotesState(query.isNotBlank())
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(filtered, key = { it.recordName }) { note ->
                        NoteCard(note, onClick = { onSelect(note) })
                    }
                    item { Spacer(Modifier.height(80.dp)) } // FAB clearance
                }
            }
        }
    }
    }  // ModalNavigationDrawer
}

@Composable
private fun FoldersDrawer(
    folders: List<FolderSummary>,
    allNotes: List<NoteSummary>,
    selectedFolder: String?,
    onSelect: (String?) -> Unit,
    onSignOut: () -> Unit,
) {
    // Per-folder note counts (excluding deleted=true outliers, which we
    // already filter at fetch time).
    val countByFolder = remember(allNotes) {
        allNotes.groupingBy { it.folderRecordName }.eachCount()
    }
    val nonTrashCount = remember(allNotes) {
        allNotes.count { it.folderRecordName != TRASH_RECORD_NAME }
    }
    ModalDrawerSheet {
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .verticalScroll(rememberScrollState()),
        ) {
            Text(
                "Notes of Fruit",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 24.dp, top = 24.dp, bottom = 8.dp),
            )
            // "All notes" pseudo-folder
            DrawerFolderRow(
                label = "All notes",
                count = nonTrashCount,
                selected = selectedFolder == null,
                onClick = { onSelect(null) },
            )
            // User-visible folders (not trash)
            for (folder in folders.filter { !it.isTrash }) {
                val display = folder.displayName ?: when {
                    folder.isDefault -> "Notes"
                    else -> folder.recordName.take(8)
                }
                DrawerFolderRow(
                    label = display,
                    count = countByFolder[folder.recordName] ?: 0,
                    selected = selectedFolder == folder.recordName,
                    onClick = { onSelect(folder.recordName) },
                )
            }
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            DrawerFolderRow(
                label = "Recently Deleted",
                count = countByFolder[TRASH_RECORD_NAME] ?: 0,
                selected = selectedFolder == TRASH_RECORD_NAME,
                onClick = { onSelect(TRASH_RECORD_NAME) },
                icon = Icons.Default.Delete,
            )
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onSignOut)
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.Logout,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(16.dp))
                Text(
                    "Sign out",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun DrawerFolderRow(
    label: String,
    count: Int,
    selected: Boolean,
    onClick: () -> Unit,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
) {
    val bg = if (selected) MaterialTheme.colorScheme.primaryContainer else androidx.compose.ui.graphics.Color.Transparent
    val fg = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp)
            .background(bg, MaterialTheme.shapes.medium)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = fg)
            Spacer(Modifier.width(12.dp))
        }
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            color = fg,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            modifier = Modifier.weight(1f),
        )
        if (count > 0) {
            Text(
                count.toString(),
                style = MaterialTheme.typography.labelLarge,
                color = if (selected) fg else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun EmptyNotesState(filtering: Boolean) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            Icons.AutoMirrored.Filled.Note,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            if (filtering) "No notes match your search." else "No notes yet.",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!filtering) {
            Spacer(Modifier.height(4.dp))
            Text(
                "Tap + to create a note, or sync one from Mac or iCloud.com.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NoteCard(note: NoteSummary, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        shape = MaterialTheme.shapes.large,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = note.title?.takeIf { it.isNotBlank() } ?: "Untitled",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                note.modificationTimestampMs?.let { ts ->
                    Text(
                        text = humanRelative(ts),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.secondary,
                        fontWeight = FontWeight.Medium,
                    )
                }
                if (!note.snippet.isNullOrBlank()) {
                    Text(
                        text = note.snippet,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

private fun humanRelative(epochMs: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - epochMs
    val s = diff / 1000
    return when {
        s < 60 -> "just now"
        s < 3600 -> "${s / 60}m"
        s < 86_400 -> "${s / 3600}h"
        s < 604_800 -> "${s / 86_400}d"
        else -> {
            val cal = java.util.Calendar.getInstance().apply { timeInMillis = epochMs }
            val month = cal.getDisplayName(java.util.Calendar.MONTH, java.util.Calendar.SHORT, java.util.Locale.getDefault()) ?: "?"
            val day = cal.get(java.util.Calendar.DAY_OF_MONTH)
            "$month $day"
        }
    }
}

private fun Modifier.clickableRow(onClick: () -> Unit): Modifier = clickable(onClick = onClick)

/**
 * Apple Notes derives Title and Snippet from the body's first non-empty line
 * (= title) and what follows (= snippet preview). The CloudKit `TitleEncrypted`
 * and `SnippetEncrypted` fields are server-cached copies of these — Apple's
 * clients (Mac, iCloud.com list view) treat them as the canonical visible
 * preview. Updating them along with TextDataEncrypted on every edit keeps
 * lists fresh and gives Mac a clean signal that the note actually changed.
 */
internal fun computeTitleAndSnippet(body: String): Pair<String, String> {
    val lines = body.split("\n")
    val title = lines.firstOrNull { it.isNotBlank() }?.trim().orEmpty()
    val titleIdx = lines.indexOfFirst { it.isNotBlank() }
    val rest = if (titleIdx >= 0) lines.drop(titleIdx + 1) else emptyList()
    val snippet = rest.firstOrNull { it.isNotBlank() }?.trim()?.take(120).orEmpty()
    return title to snippet
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NoteDetailScreen(
    record: NoteRecord,
    onBack: () -> Unit,
    onSave: (newBody: String, isPureAppend: Boolean, alsoExit: Boolean, newSpans: List<NoteBodyEditor.AttrSpan>?) -> Unit,
    onDelete: () -> Unit,
    saving: Boolean,
    savedHint: String? = null,
    lastSavedAtMs: Long? = null,
    onHintShown: () -> Unit = {},
    onFetchAttachment: (suspend (String) -> NoteRecord?)? = null,
    onFetchAsset: (suspend (String) -> ByteArray?)? = null,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val shareContext = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(savedHint) {
        if (savedHint != null) {
            snackbarHostState.showSnackbar(savedHint)
            onHintShown()
        }
    }
    val originalBody = remember(record.recordName, record.recordChangeTag) {
        record.stringField("TextDataEncrypted")?.let { b64 ->
            runCatching { NoteBodyEditor.readVisibleTextFromBase64(b64) }.getOrNull()
                ?: runCatching { NoteBodyEditor.readTextFromBase64(b64) }.getOrNull()
        }.orEmpty()
    }
    // Apple Notes stores the title as the first line of the body. We split the
    // raw originalBody into two editable pieces:
    //   - `titleDraft`  → rendered as a TextField inside the top app bar
    //   - `bodyDraft`   → rendered as the main BasicTextField below
    // On save we re-assemble `titleDraft + "\n" + bodyDraft` (omitting the
    // separator if either piece is empty) so the underlying CloudKit body
    // shape stays identical to what Mac expects (first line == title).
    val initialTitle = remember(originalBody) {
        val firstNl = originalBody.indexOf('\n')
        if (firstNl < 0) originalBody else originalBody.substring(0, firstNl)
    }
    val initialBody = remember(originalBody) {
        val firstNl = originalBody.indexOf('\n')
        if (firstNl < 0) "" else originalBody.substring(firstNl + 1)
    }
    var titleDraft by remember(record.recordName, record.recordChangeTag) {
        mutableStateOf(initialTitle)
    }
    var bodyDraft by remember(record.recordName, record.recordChangeTag) {
        mutableStateOf(androidx.compose.ui.text.input.TextFieldValue(initialBody))
    }
    val fullBodyForSave = when {
        titleDraft.isEmpty() && bodyDraft.text.isEmpty() -> ""
        titleDraft.isEmpty() -> bodyDraft.text
        bodyDraft.text.isEmpty() -> titleDraft
        else -> "$titleDraft\n${bodyDraft.text}"
    }
    // isModified is computed after spansDraft is declared below — see further down.
    val isPureAppend = remember(fullBodyForSave, originalBody) {
        fullBodyForSave.startsWith(originalBody) && fullBodyForSave.length > originalBody.length
    }
    var isModified = fullBodyForSave != originalBody

    // Parse the note's attribute_runs once per record load — kept around so
    // the splice path in NoteAppender can preserve paragraph styles through
    // edits (Phase D1). Display-time formatting via VisualTransformation is
    // a future enhancement; for now the editor shows plain text.
    val attrSpans = remember(record.recordName, record.recordChangeTag) {
        record.stringField("TextDataEncrypted")?.let { b64 ->
            runCatching { NoteBodyEditor.parseAttributeRunsFromBase64(b64) }.getOrNull()
        }.orEmpty()
    }
    // attrSpans cover the FULL visible text including the title line (which
    // we render separately in the app bar). Trim the title's worth of spans
    // off the front so styleAt indexes align with `initialBody`. Computed
    // from spansDraft so toolbar/tap mutations are reflected live.
    // (spansDraft is declared below; we wrap in remember after that.)
    // Default to the formatted view when the note has any non-body paragraph
    // style (heading, bullets, checkboxes, etc.). Plain notes go straight to
    // the editor — no extra tap needed. Tapping the formatted view drops
    // into the editor; we don't auto-toggle back, to avoid losing edits.
    val bodyAttrSpansInit = remember(originalBody, attrSpans) {
        val skip = if (originalBody.contains('\n')) initialTitle.length + 1
                   else initialTitle.length
        attrSpansAfter(attrSpans, skip)
    }
    val hasRichFormatting = remember(bodyAttrSpansInit) {
        bodyAttrSpansInit.any {
            it.style != NoteBodyEditor.ParagraphStyle.BODY &&
                it.style != NoteBodyEditor.ParagraphStyle.TITLE
        }
    }
    var viewMode by remember(record.recordName, record.recordChangeTag) {
        mutableStateOf(hasRichFormatting && initialBody.isNotEmpty())
    }

    // Mutable AttrSpan list driving the editor's formatting state. Covers the
    // FULL visible body (title + "\n" + body) — same coordinate space as the
    // proto. Toolbar mutations operate on body-relative ranges and we add the
    // title prefix length when applying.
    var spansDraft by remember(record.recordName, record.recordChangeTag) {
        mutableStateOf(attrSpans)
    }
    // Track the previous full-body text so we can keep [spansDraft] in sync
    // with character insertions/deletions.
    var prevFullBody by remember(record.recordName, record.recordChangeTag) {
        mutableStateOf(originalBody)
    }
    LaunchedEffect(fullBodyForSave) {
        if (fullBodyForSave == prevFullBody) return@LaunchedEffect
        spansDraft = syncSpansToText(prevFullBody, spansDraft, fullBodyForSave)
        prevFullBody = fullBodyForSave
    }
    // Format-only changes need to mark the note as modified so save fires.
    if (spansDraft != attrSpans) isModified = true

    // Live body-only spans for formatted view (mirrors any draft mutation).
    val bodyAttrSpans = remember(titleDraft, fullBodyForSave, spansDraft) {
        val skip = if (fullBodyForSave.contains('\n')) titleDraft.length + 1
                   else titleDraft.length
        attrSpansAfter(spansDraft, skip)
    }

    // Resolve attachment cards. For each AttrSpan with isAttachment + recordName
    // we kick off an async lookup, decode the result by attachment type
    // ("com.apple.notes.table" → table grid; "public.png/jpeg/heic" → image
    // bytes), and feed the cache into FormattedNoteBody.
    val attachmentCache = remember(record.recordName, record.recordChangeTag) {
        mutableStateOf<Map<String, AttachmentContent>>(emptyMap())
    }
    LaunchedEffect(record.recordName, record.recordChangeTag, onFetchAttachment) {
        val fetcher = onFetchAttachment ?: return@LaunchedEffect
        val assetFetcher = onFetchAsset ?: { _ -> null }
        val recordNames = bodyAttrSpans.mapNotNull { it.attachmentRecordName }.toSet()
        for (rn in recordNames) {
            if (attachmentCache.value.containsKey(rn)) continue
            val rec = runCatching { fetcher(rn) }.getOrNull() ?: continue
            val content = decodeAttachment(rec, assetFetcher, fetcher)
            if (content != null) {
                attachmentCache.value = attachmentCache.value + (rn to content)
            }
        }
    }

    var showDeleteDialog by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }

    // Auto-save on lifecycle ON_PAUSE (app backgrounded, screen turns off,
    // another activity covers us, etc.). Best-effort fire-and-forget — the
    // parent's onSave coroutine handles the actual network call. We pass
    // `alsoExit=false` because we're not navigating, just persisting.
    // Use `rememberUpdatedState` semantics by referencing the latest values
    // through the captured composable scope.
    DisposableEffect(lifecycleOwner, isModified, fullBodyForSave, isPureAppend) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE && isModified && !saving) {
                onSave(fullBodyForSave, isPureAppend, false, spansDraft)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // "Saved Xs ago" pill — recompute every 10s.
    var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(lastSavedAtMs) {
        while (lastSavedAtMs != null) {
            nowMs = System.currentTimeMillis()
            kotlinx.coroutines.delay(10_000)
        }
    }
    val savedAgoText: String? = lastSavedAtMs?.let { savedAt ->
        val deltaSec = (nowMs - savedAt) / 1000
        when {
            deltaSec < 5 -> "Saved"
            deltaSec < 60 -> "Saved ${deltaSec}s ago"
            deltaSec < 3600 -> "Saved ${deltaSec / 60}m ago"
            else -> "Saved ${deltaSec / 3600}h ago"
        }
    }

    val onBackOrSave: () -> Unit = {
        if (isModified && !saving) {
            // Save and navigate atomically. Parent handles the navigation in
            // the save's success callback (sees alsoExit=true).
            onSave(fullBodyForSave, isPureAppend, true, spansDraft)
        } else {
            onBack()
        }
    }

    // Intercept the system back button / gesture so it navigates to the list
    // instead of closing the activity. Mirrors the back-arrow's behaviour
    // (save-if-modified, then exit). Disabled while saving so a tap on
    // "Save" → back doesn't fire twice.
    androidx.activity.compose.BackHandler(enabled = !saving) {
        onBackOrSave()
    }

    Scaffold(
        modifier = Modifier.imePadding(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            Column {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = onBackOrSave) {
                            Icon(Icons.AutoMirrored.Default.ArrowBack, contentDescription = "Back")
                        }
                    },
                    title = {
                        Column {
                            // Editable title in the app bar — tap to type.
                            BasicTextField(
                                value = titleDraft,
                                onValueChange = { titleDraft = it.replace("\n", "") },
                                singleLine = true,
                                textStyle = MaterialTheme.typography.titleLarge.copy(
                                    color = MaterialTheme.colorScheme.onSurface,
                                    fontWeight = FontWeight.Bold,
                                ),
                                cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
                                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                                decorationBox = { inner ->
                                    if (titleDraft.isEmpty()) {
                                        Text(
                                            "Untitled",
                                            style = MaterialTheme.typography.titleLarge.copy(
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                fontWeight = FontWeight.Bold,
                                            ),
                                        )
                                    }
                                    inner()
                                },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            val pill = when {
                                saving -> "Saving…"
                                isModified -> "Unsaved"
                                savedAgoText != null -> savedAgoText
                                else -> null
                            }
                            if (pill != null) {
                                Text(
                                    pill,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                )
                            }
                        }
                    },
                    actions = {
                        if (isModified) {
                            IconButton(
                                enabled = !saving,
                                onClick = { onSave(fullBodyForSave, isPureAppend, false, spansDraft) },
                            ) {
                                Icon(Icons.Default.Check, contentDescription = "Save")
                            }
                        }
                        IconButton(
                            onClick = {
                                // Share the visible body (title + content) as
                                // plaintext via Android's standard chooser.
                                val sendIntent = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_TEXT, fullBodyForSave)
                                    titleDraft.takeIf { it.isNotBlank() }?.let {
                                        putExtra(Intent.EXTRA_SUBJECT, it)
                                    }
                                }
                                val chooser = Intent.createChooser(sendIntent, null).apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                shareContext.startActivity(chooser)
                            },
                        ) {
                            Icon(Icons.Default.Share, contentDescription = "Share")
                        }
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "More")
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("Delete note") },
                                leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
                                onClick = { menuOpen = false; showDeleteDialog = true },
                            )
                        }
                    },
                )
                if (saving) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            // Body display: formatted view by default for notes that carry
            // paragraph styling (headings, lists, checkboxes), plain editor
            // otherwise. Tapping the formatted view drops into the editor.
            if (viewMode) {
                FormattedNoteBody(
                    fullBody = bodyDraft.text,
                    titlePrefixLength = 0,
                    attrSpans = bodyAttrSpans,
                    attachmentContent = attachmentCache.value,
                    onTap = { viewMode = false },
                    onToggleCheckbox = { bodyParaStart ->
                        val skip = if (fullBodyForSave.contains('\n')) titleDraft.length + 1
                            else titleDraft.length
                        val fullPos = bodyParaStart + skip
                        spansDraft = NoteBodyEditor.toggleCheckbox(
                            fullBodyForSave, spansDraft, fullPos,
                        )
                        // Auto-save the toggle so check state persists.
                        onSave(fullBodyForSave, false, false, spansDraft)
                    },
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                )
            } else {
                BasicTextField(
                    value = bodyDraft,
                    onValueChange = { bodyDraft = it },
                    visualTransformation = remember(bodyAttrSpans) {
                        FormattingVisualTransformation(bodyAttrSpans)
                    },
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 16.dp),
                    textStyle = MaterialTheme.typography.bodyLarge.copy(
                        color = MaterialTheme.colorScheme.onSurface,
                    ),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    decorationBox = { inner ->
                        if (bodyDraft.text.isEmpty()) {
                            Text(
                                "Start typing…",
                                style = MaterialTheme.typography.bodyLarge.copy(
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                ),
                            )
                        }
                        inner()
                    },
                )
                // Format toolbar — only shown while editing.
                FormattingToolbar(
                    titlePrefixLength = if (titleDraft.isEmpty()) 0 else titleDraft.length + 1,
                    bodyValue = bodyDraft,
                    fullBody = fullBodyForSave,
                    spans = spansDraft,
                    onSpansChange = { newSpans ->
                        spansDraft = newSpans
                    },
                )
            }
            // Footer: modification date + char count + chord guidance.
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                val modDate = record.stringField("ModificationDate")?.toLongOrNull()
                Text(
                    text = modDate?.let { "Modified ${humanRelative(it)}" } ?: "—",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "${bodyDraft.text.length} chars",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Delete this note?") },
            text = { Text("This moves the note to Recently Deleted in iCloud Notes.") },
            confirmButton = {
                TextButton(onClick = { showDeleteDialog = false; onDelete() }) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text("Cancel") }
            },
        )
    }
}

/**
 * Formatted preview of a note's body. Walks the visible text paragraph by
 * paragraph, applies the matching attribute_run style (body / bullet /
 * checkbox / numbered / heading / title), and renders each paragraph as
 * a styled Text or Row composable. Title (the first line) is rendered as
 * a large heading; subsequent lines get their own attribute-run style.
 *
 * Tap anywhere to switch the screen into the plain-text editor.
 */
/** Holds decoded payload for an attachment record (table grid, image bytes, etc). */
sealed class AttachmentContent {
    data class Table(val grid: com.example.applenotes.proto.MergeableDataDecoder.TableGrid) : AttachmentContent()
    data class Image(val bytes: ByteArray, val mimeHint: String?) : AttachmentContent()
    data class Generic(val title: String?, val mime: String?) : AttachmentContent()
}

private suspend fun decodeAttachment(
    rec: NoteRecord,
    fetchAssetBytes: suspend (String) -> ByteArray?,
    fetchRecord: suspend (String) -> NoteRecord? = { null },
): AttachmentContent? {
    val uti = rec.stringField("UTI")?.let {
        runCatching { kotlin.io.encoding.Base64.decode(it).decodeToString() }.getOrNull()
    } ?: rec.stringField("UTIEncrypted")?.let {
        runCatching { kotlin.io.encoding.Base64.decode(it).decodeToString() }.getOrNull()
    }
    val title = rec.stringField("TitleEncrypted")?.let {
        runCatching { kotlin.io.encoding.Base64.decode(it).decodeToString() }.getOrNull()
    }
    val mergB64 = rec.stringField("MergeableDataEncrypted")
    if (mergB64 != null && (uti?.contains("table", ignoreCase = true) == true || title?.equals("Table", ignoreCase = true) == true)) {
        val grid = com.example.applenotes.proto.MergeableDataDecoder.decodeTableBase64(mergB64)
        if (grid != null) return AttachmentContent.Table(grid)
    }
    // Image attachment — fetch the Media CKAsset.
    val isImage = uti?.let {
        it.startsWith("public.png", ignoreCase = true) ||
            it.startsWith("public.jpeg", ignoreCase = true) ||
            it.startsWith("public.heic", ignoreCase = true) ||
            it.startsWith("public.image", ignoreCase = true) ||
            it.contains("gallery", ignoreCase = true)
    } == true
    if (isImage) {
        // The Attachment record's "Media" is a CKReference to a Media record
        // which actually carries the binary as a CKAsset. Follow the reference.
        val mediaField = rec.rawFields["Media"]
        val mediaRefName = mediaField?.let {
            com.example.applenotes.client.extractReferenceRecordName(it)
        }
        android.util.Log.i("AppleNotesUI", "Image attachment ${rec.recordName}: mediaRef=$mediaRefName uti=$uti")
        if (mediaRefName != null) {
            val mediaRec = fetchRecord(mediaRefName)
            // Media record's asset typically lives in field "MediaEncrypted" or "Media".
            val candidateFields = listOf("MediaEncrypted", "Media", "Asset", "MediaURL")
            for (fname in candidateFields) {
                val candidate = mediaRec?.rawFields?.get(fname) ?: continue
                val downloadUrl = com.example.applenotes.client.extractAssetDownloadUrl(candidate)
                if (downloadUrl != null) {
                    val bytes = fetchAssetBytes(downloadUrl)
                    if (bytes != null) return AttachmentContent.Image(bytes, uti)
                }
            }
        }
        // Direct asset URL on this record (rare, but check).
        val direct = mediaField?.let { com.example.applenotes.client.extractAssetDownloadUrl(it) }
        if (direct != null) {
            val bytes = fetchAssetBytes(direct)
            if (bytes != null) return AttachmentContent.Image(bytes, uti)
        }
        // Fall back to the first preview if Media isn't downloadable.
        val previews = rec.rawFields["PreviewImages"] as? kotlinx.serialization.json.JsonObject
        val firstPreviewUrl = previews?.get("value")?.let {
            (it as? kotlinx.serialization.json.JsonArray)?.firstOrNull()
                ?.let { ele -> com.example.applenotes.client.extractAssetDownloadUrl(ele) }
        }
        if (firstPreviewUrl != null) {
            val bytes = fetchAssetBytes(firstPreviewUrl)
            if (bytes != null) return AttachmentContent.Image(bytes, uti)
        }
    }
    return AttachmentContent.Generic(title, uti)
}

@Composable
private fun AlphaWarningDialog(onAcknowledge: () -> Unit) {
    AlertDialog(
        onDismissRequest = { /* mandatory: must tap continue */ },
        icon = {
            Icon(
                imageVector = androidx.compose.material.icons.Icons.Filled.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(40.dp),
            )
        },
        title = {
            Text(
                "Alpha — read this first",
                style = MaterialTheme.typography.headlineSmall,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Notes of Fruit is an unofficial, reverse-engineered, alpha-quality " +
                        "Android client for iCloud Notes. It is not affiliated with Apple.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "It might:",
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                )
                Text("• corrupt, duplicate, or delete notes in your iCloud account",
                    style = MaterialTheme.typography.bodyMedium)
                Text("• drop formatting in subtle ways when round-tripping through Mac",
                    style = MaterialTheme.typography.bodyMedium)
                Text("• stop working at any time if Apple changes their API",
                    style = MaterialTheme.typography.bodyMedium)
                Text("• fail to handle modern collaborative notes (read-only at best)",
                    style = MaterialTheme.typography.bodyMedium)
                Text(
                    "Use at your own risk. If you have notes you can't afford to lose, " +
                        "back them up first or just don't use this app.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onAcknowledge) {
                Text("I understand — continue")
            }
        },
    )
}

@Composable
private fun FormattedNoteBody(
    fullBody: String,
    titlePrefixLength: Int,
    attrSpans: List<NoteBodyEditor.AttrSpan>,
    attachmentContent: Map<String, AttachmentContent> = emptyMap(),
    onTap: () -> Unit,
    onToggleCheckbox: ((paragraphCharStart: Int) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    // Map each char index -> (style, checked). Walk attrSpans in order and
    // assign each char its span's style. Total span lengths usually match
    // fullBody.length; if they don't (which can happen if Apple emits trailing
    // ARs covering chars we don't render), we tolerate the mismatch by
    // clamping.
    val styleAt = remember(fullBody, attrSpans) {
        val arr = arrayOfNulls<NoteBodyEditor.AttrSpan>(fullBody.length)
        var pos = 0
        for (span in attrSpans) {
            val end = (pos + span.length).coerceAtMost(fullBody.length)
            for (i in pos until end) arr[i] = span
            pos = end
            if (pos >= fullBody.length) break
        }
        arr
    }

    // Split into paragraphs. Each paragraph is the chars between consecutive
    // newlines (the newline goes WITH its preceding paragraph).
    val paragraphs = remember(fullBody) {
        val out = mutableListOf<IntRange>()
        var start = 0
        for ((i, c) in fullBody.withIndex()) {
            if (c == '\n') {
                out.add(start..i)
                start = i + 1
            }
        }
        if (start < fullBody.length) out.add(start until fullBody.length)
        else if (start == fullBody.length && fullBody.endsWith('\n')) {
            out.add(start until start) // trailing empty paragraph
        }
        out
    }

    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp)
            .clickable(onClick = onTap),
    ) {
        var listCounter = 0
        for (range in paragraphs) {
            // Detect an "attachment paragraph" — one whose only visible char is
            // U+FFFC and whose span carries f12 (attachment_info). Render those
            // as a labeled card (Table / Image / etc.) instead of inline text.
            // Mac/iCloud also lay these out as block elements.
            val attachmentSpan = run {
                val firstNonNl = (range.first..range.last).firstOrNull {
                    it < fullBody.length && fullBody[it] != '\n'
                } ?: return@run null
                val s = if (firstNonNl < styleAt.size) styleAt[firstNonNl] else null
                if (s?.isAttachment == true && fullBody[firstNonNl] == '￼') s else null
            }
            if (attachmentSpan != null) {
                val content = attachmentSpan.attachmentRecordName
                    ?.let { attachmentContent[it] }
                AttachmentBlock(attachmentSpan, content)
                continue
            }

            // The dominant style of a paragraph = the style of its first char.
            val firstSpan = if (range.first < styleAt.size) styleAt[range.first] else null
            val effectiveStyle = when {
                range.first < titlePrefixLength -> NoteBodyEditor.ParagraphStyle.TITLE
                firstSpan != null -> firstSpan.style
                else -> NoteBodyEditor.ParagraphStyle.BODY
            }
            // Reset numbered-list counter when the previous para wasn't part of
            // the same list.
            if (effectiveStyle != NoteBodyEditor.ParagraphStyle.NUMBERED_LIST) {
                listCounter = 0
            }

            // Build an AnnotatedString for the paragraph's text, applying
            // per-character bold/italic/underline/strike/link/monospace styling
            // from each AttrSpan that intersects the paragraph. The newline at
            // the end of the range is dropped — paragraph spacing is the
            // wrapper's job.
            val annotated = buildParagraphAnnotated(fullBody, range, styleAt)

            when (effectiveStyle) {
                NoteBodyEditor.ParagraphStyle.TITLE -> {
                    if (annotated.isNotEmpty()) {
                        Text(
                            annotated,
                            style = MaterialTheme.typography.headlineLarge.copy(
                                fontWeight = FontWeight.Bold,
                            ),
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(bottom = 8.dp),
                        )
                    }
                }
                NoteBodyEditor.ParagraphStyle.HEADING -> {
                    Text(
                        annotated,
                        style = MaterialTheme.typography.headlineMedium.copy(
                            fontWeight = FontWeight.SemiBold,
                        ),
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                    )
                }
                NoteBodyEditor.ParagraphStyle.SUBHEADING -> {
                    Text(
                        annotated,
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.SemiBold,
                        ),
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(top = 6.dp, bottom = 2.dp),
                    )
                }
                NoteBodyEditor.ParagraphStyle.MONOSPACED -> {
                    Text(
                        annotated,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontFamily = FontFamily.Monospace,
                        ),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                NoteBodyEditor.ParagraphStyle.BULLETED_LIST -> {
                    BulletRow("•", annotated)
                }
                NoteBodyEditor.ParagraphStyle.DASHED_LIST -> {
                    BulletRow("–", annotated)
                }
                NoteBodyEditor.ParagraphStyle.CHECKBOX_LIST -> {
                    val checked = firstSpan?.checked ?: false
                    Row(verticalAlignment = Alignment.Top) {
                        Icon(
                            if (checked) Icons.Default.CheckBox else Icons.Default.CheckBoxOutlineBlank,
                            contentDescription = if (checked) "Checked" else "Unchecked",
                            tint = if (checked) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .padding(end = 8.dp, top = 2.dp)
                                .size(28.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .clickable(enabled = onToggleCheckbox != null) {
                                    onToggleCheckbox?.invoke(range.first)
                                },
                        )
                        Text(
                            annotated,
                            style = MaterialTheme.typography.bodyLarge.copy(
                                color = if (checked) MaterialTheme.colorScheme.onSurfaceVariant
                                    else MaterialTheme.colorScheme.onSurface,
                            ),
                        )
                    }
                }
                NoteBodyEditor.ParagraphStyle.NUMBERED_LIST -> {
                    listCounter++
                    BulletRow("$listCounter.", annotated)
                }
                NoteBodyEditor.ParagraphStyle.BODY -> {
                    if (annotated.isEmpty()) {
                        Spacer(modifier = Modifier.height(12.dp))
                    } else {
                        Text(
                            annotated,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        }
        if (paragraphs.isEmpty()) {
            Text(
                "Start typing…",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun AttachmentBlock(span: NoteBodyEditor.AttrSpan, content: AttachmentContent?) {
    when (content) {
        is AttachmentContent.Table -> TableGridView(content.grid)
        is AttachmentContent.Image -> ImageView(content)
        else -> AttachmentPlaceholder(span)
    }
}

@Composable
private fun TableGridView(grid: com.example.applenotes.proto.MergeableDataDecoder.TableGrid) {
    val borderColor = MaterialTheme.colorScheme.outlineVariant
    val headerBg = MaterialTheme.colorScheme.surfaceVariant
    androidx.compose.foundation.layout.Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .background(
                color = MaterialTheme.colorScheme.surface,
                shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
            )
            .androidxBorder(
                borderColor = borderColor,
                shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
            ),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            for ((rIdx, row) in grid.cells.withIndex()) {
                Row(modifier = Modifier.fillMaxWidth()) {
                    for ((cIdx, cell) in row.withIndex()) {
                        val cellModifier = Modifier
                            .weight(1f)
                            .background(if (rIdx == 0) headerBg else MaterialTheme.colorScheme.surface)
                            .padding(horizontal = 12.dp, vertical = 10.dp)
                        Text(
                            text = cell,
                            style = if (rIdx == 0)
                                MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
                            else
                                MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = cellModifier,
                        )
                        if (cIdx < row.size - 1) {
                            androidx.compose.foundation.layout.Box(
                                modifier = Modifier
                                    .width(1.dp)
                                    .fillMaxHeight()
                                    .background(borderColor),
                            )
                        }
                    }
                }
                if (rIdx < grid.cells.size - 1) {
                    HorizontalDivider(color = borderColor)
                }
            }
        }
    }
}

private fun Modifier.androidxBorder(
    borderColor: Color,
    shape: androidx.compose.ui.graphics.Shape,
): Modifier = this.border(
    width = 1.dp,
    color = borderColor,
    shape = shape,
)

@Composable
private fun ImageView(content: AttachmentContent.Image) {
    val bitmap = remember(content.bytes) {
        runCatching {
            android.graphics.BitmapFactory.decodeByteArray(content.bytes, 0, content.bytes.size)
        }.getOrNull()
    }
    if (bitmap != null) {
        androidx.compose.foundation.Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = "Image attachment",
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp)
                .background(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
                ),
        )
    } else {
        AttachmentPlaceholder(NoteBodyEditor.AttrSpan(
            length = 1,
            style = NoteBodyEditor.ParagraphStyle.BODY,
            isAttachment = true,
            attachmentType = content.mimeHint,
        ))
    }
}

@Composable
private fun AttachmentPlaceholder(span: NoteBodyEditor.AttrSpan) {
    val (icon, label) = labelForAttachmentType(span.attachmentType)
    androidx.compose.foundation.layout.Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .background(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
            )
            .padding(horizontal = 12.dp, vertical = 14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                icon,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(end = 10.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    label,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                val subtitle = span.attachmentType ?: "attachment"
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private fun labelForAttachmentType(uti: String?): Pair<String, String> = when {
    uti == null -> "📎" to "Attachment"
    uti.contains("table", ignoreCase = true) -> "📋" to "Table"
    uti.contains("gallery", ignoreCase = true) ||
        uti.contains("image", ignoreCase = true) ||
        uti.startsWith("public.png", ignoreCase = true) ||
        uti.startsWith("public.jpeg", ignoreCase = true) ||
        uti.startsWith("public.heic", ignoreCase = true) -> "🖼" to "Image"
    uti.contains("sketch", ignoreCase = true) ||
        uti.contains("drawing", ignoreCase = true) -> "✏️" to "Drawing"
    uti.contains("map", ignoreCase = true) -> "🗺" to "Map"
    uti.startsWith("public.url", ignoreCase = true) ||
        uti.contains("link", ignoreCase = true) -> "🔗" to "Link preview"
    uti.contains("audio", ignoreCase = true) -> "🎵" to "Audio"
    uti.contains("video", ignoreCase = true) -> "🎞" to "Video"
    uti.contains("pdf", ignoreCase = true) -> "📄" to "PDF"
    else -> "📎" to "Attachment"
}

@Composable
private fun BulletRow(marker: String, content: AnnotatedString) {
    Row(verticalAlignment = Alignment.Top) {
        Text(
            "$marker  ",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            content,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/**
 * Build an AnnotatedString for the chars at [range] in [fullBody], applying
 * inline styles (bold/italic/underline/strike/link/monospace) from the
 * AttrSpan covering each char. Coalesces consecutive chars that share an
 * AttrSpan so we only emit one styled span per run. Drops the trailing
 * newline if present (paragraphs include their newline; we don't want it
 * inside the visible text).
 */
private fun buildParagraphAnnotated(
    fullBody: String,
    range: IntRange,
    styleAt: Array<NoteBodyEditor.AttrSpan?>,
): AnnotatedString = buildAnnotatedString {
    var i = range.first
    val end = (range.last + 1).coerceAtMost(fullBody.length)
    while (i < end) {
        // Skip the trailing newline — only the very last char of the
        // paragraph range can be '\n', and we don't want it in the rendered
        // text.
        if (i == end - 1 && fullBody[i] == '\n') break
        val span = if (i < styleAt.size) styleAt[i] else null
        var j = i + 1
        while (j < end &&
            !(j == end - 1 && fullBody[j] == '\n') &&
            (if (j < styleAt.size) styleAt[j] else null) === span
        ) j++
        val sub = fullBody.substring(i, j)
        // U+FFFC stays inline only when the surrounding paragraph isn't
        // already being rendered as an attachment card (the caller checks
        // that and skips this codepath); show a small marker so the position
        // isn't invisible.
        val display = if (span?.isAttachment == true) sub.replace('￼', '⧉') else sub
        val style = inlineSpanStyle(span)
        if (span?.linkUrl != null) {
            val link = LinkAnnotation.Url(
                url = span.linkUrl,
                styles = TextLinkStyles(style = style ?: SpanStyle()),
            )
            withLink(link) { append(display) }
        } else if (style != null) {
            withStyle(style) { append(display) }
        } else {
            append(display)
        }
        i = j
    }
}

private fun inlineSpanStyle(s: NoteBodyEditor.AttrSpan?): SpanStyle? {
    if (s == null) return null
    val decorations = mutableListOf<TextDecoration>()
    if (s.underline) decorations.add(TextDecoration.Underline)
    if (s.strikethrough) decorations.add(TextDecoration.LineThrough)
    val hasInline = s.bold || s.italic || s.underline || s.strikethrough ||
        s.linkUrl != null || s.style == NoteBodyEditor.ParagraphStyle.MONOSPACED
    if (!hasInline) return null
    return SpanStyle(
        fontWeight = if (s.bold) FontWeight.Bold else null,
        fontStyle = if (s.italic) FontStyle.Italic else null,
        textDecoration = when (decorations.size) {
            0 -> if (s.linkUrl != null) TextDecoration.Underline else null
            1 -> decorations[0]
            else -> TextDecoration.combine(decorations)
        },
        color = if (s.linkUrl != null) Color(0xFF1A73E8) else Color.Unspecified,
        fontFamily = if (s.style == NoteBodyEditor.ParagraphStyle.MONOSPACED) FontFamily.Monospace else null,
    )
}

@Composable
private fun FormattingToolbar(
    titlePrefixLength: Int,
    bodyValue: androidx.compose.ui.text.input.TextFieldValue,
    fullBody: String,
    spans: List<NoteBodyEditor.AttrSpan>,
    onSpansChange: (List<NoteBodyEditor.AttrSpan>) -> Unit,
) {
    // Map body-relative selection → full-body coordinates.
    val sel = bodyValue.selection
    val rangeStart = (sel.min + titlePrefixLength).coerceIn(0, fullBody.length)
    val rangeEnd = (sel.max + titlePrefixLength).coerceIn(rangeStart, fullBody.length)
    val effectiveEnd = if (rangeEnd == rangeStart) {
        // No selection — apply to the paragraph of the cursor for paragraph
        // styles, and to a one-char neighborhood for inline (handled via
        // selectionExtended below).
        rangeEnd
    } else rangeEnd

    val perChar = remember(fullBody, spans) { NoteBodyEditor.expandSpansToChars(fullBody, spans) }
    val curSpan = if (rangeStart < perChar.size) perChar[rangeStart]
        else if (perChar.isNotEmpty()) perChar.last() else null
    // For "is this format on right now?" indicators: collapsed cursor uses the
    // span at the cursor; selection uses "is every char in selection on?".
    fun allHave(predicate: (NoteBodyEditor.AttrSpan) -> Boolean): Boolean {
        if (rangeStart >= rangeEnd) return curSpan?.let(predicate) == true
        for (i in rangeStart until rangeEnd) if (i in perChar.indices && !predicate(perChar[i])) return false
        return true
    }
    val isBold = allHave { it.bold }
    val isItalic = allHave { it.italic }
    val isUnderline = allHave { it.underline }
    val isStrike = allHave { it.strikethrough }
    val curStyle = curSpan?.style ?: NoteBodyEditor.ParagraphStyle.BODY

    val context = LocalContext.current

    // Style picker dropdown state.
    var styleMenuOpen by remember { mutableStateOf(false) }
    var linkDialogOpen by remember { mutableStateOf(false) }

    Surface(
        tonalElevation = 3.dp,
        shadowElevation = 0.dp,
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Paragraph-style picker.
                ToolbarPill(
                    label = paragraphStyleLabel(curStyle),
                    icon = "Aa",
                    selected = curStyle != NoteBodyEditor.ParagraphStyle.BODY,
                    onClick = { styleMenuOpen = true },
                )
                DropdownMenu(
                    expanded = styleMenuOpen,
                    onDismissRequest = { styleMenuOpen = false },
                ) {
                    listOf(
                        NoteBodyEditor.ParagraphStyle.TITLE,
                        NoteBodyEditor.ParagraphStyle.HEADING,
                        NoteBodyEditor.ParagraphStyle.SUBHEADING,
                        NoteBodyEditor.ParagraphStyle.BODY,
                        NoteBodyEditor.ParagraphStyle.MONOSPACED,
                    ).forEach { style ->
                        DropdownMenuItem(
                            text = { Text(paragraphStyleLabel(style)) },
                            trailingIcon = {
                                if (curStyle == style) Icon(Icons.Default.Check, null)
                            },
                            onClick = {
                                styleMenuOpen = false
                                onSpansChange(NoteBodyEditor.setParagraphStyle(
                                    fullBody, spans, rangeStart, rangeEnd.coerceAtLeast(rangeStart + 1), style,
                                ))
                            },
                        )
                    }
                }

                ToolbarDivider()

                // List paragraph styles.
                ToolbarIconButton(
                    icon = Icons.AutoMirrored.Filled.FormatListBulleted,
                    contentDescription = "Bulleted list",
                    selected = curStyle == NoteBodyEditor.ParagraphStyle.BULLETED_LIST,
                    onClick = {
                        val target = if (curStyle == NoteBodyEditor.ParagraphStyle.BULLETED_LIST)
                            NoteBodyEditor.ParagraphStyle.BODY
                        else NoteBodyEditor.ParagraphStyle.BULLETED_LIST
                        onSpansChange(NoteBodyEditor.setParagraphStyle(
                            fullBody, spans, rangeStart, rangeEnd.coerceAtLeast(rangeStart + 1), target,
                        ))
                    },
                )
                ToolbarIconButton(
                    icon = Icons.Filled.HorizontalRule,
                    contentDescription = "Dashed list",
                    selected = curStyle == NoteBodyEditor.ParagraphStyle.DASHED_LIST,
                    onClick = {
                        val target = if (curStyle == NoteBodyEditor.ParagraphStyle.DASHED_LIST)
                            NoteBodyEditor.ParagraphStyle.BODY
                        else NoteBodyEditor.ParagraphStyle.DASHED_LIST
                        onSpansChange(NoteBodyEditor.setParagraphStyle(
                            fullBody, spans, rangeStart, rangeEnd.coerceAtLeast(rangeStart + 1), target,
                        ))
                    },
                )
                ToolbarIconButton(
                    icon = Icons.Filled.FormatListNumbered,
                    contentDescription = "Numbered list",
                    selected = curStyle == NoteBodyEditor.ParagraphStyle.NUMBERED_LIST,
                    onClick = {
                        val target = if (curStyle == NoteBodyEditor.ParagraphStyle.NUMBERED_LIST)
                            NoteBodyEditor.ParagraphStyle.BODY
                        else NoteBodyEditor.ParagraphStyle.NUMBERED_LIST
                        onSpansChange(NoteBodyEditor.setParagraphStyle(
                            fullBody, spans, rangeStart, rangeEnd.coerceAtLeast(rangeStart + 1), target,
                        ))
                    },
                )
                ToolbarIconButton(
                    icon = Icons.Filled.CheckBoxOutlineBlank,
                    contentDescription = "Checkbox",
                    selected = curStyle == NoteBodyEditor.ParagraphStyle.CHECKBOX_LIST,
                    onClick = {
                        val target = if (curStyle == NoteBodyEditor.ParagraphStyle.CHECKBOX_LIST)
                            NoteBodyEditor.ParagraphStyle.BODY
                        else NoteBodyEditor.ParagraphStyle.CHECKBOX_LIST
                        onSpansChange(NoteBodyEditor.setParagraphStyle(
                            fullBody, spans, rangeStart, rangeEnd.coerceAtLeast(rangeStart + 1), target,
                        ))
                    },
                )

                ToolbarDivider()

                // Inline format toggles.
                ToolbarIconButton(
                    icon = Icons.Filled.FormatBold,
                    contentDescription = "Bold",
                    selected = isBold,
                    onClick = {
                        if (rangeStart < rangeEnd) {
                            onSpansChange(NoteBodyEditor.toggleInline(
                                fullBody, spans, rangeStart, rangeEnd, NoteBodyEditor.InlineKind.BOLD,
                            ))
                        }
                    },
                )
                ToolbarIconButton(
                    icon = Icons.Filled.FormatItalic,
                    contentDescription = "Italic",
                    selected = isItalic,
                    onClick = {
                        if (rangeStart < rangeEnd) {
                            onSpansChange(NoteBodyEditor.toggleInline(
                                fullBody, spans, rangeStart, rangeEnd, NoteBodyEditor.InlineKind.ITALIC,
                            ))
                        }
                    },
                )
                ToolbarIconButton(
                    icon = Icons.Filled.FormatUnderlined,
                    contentDescription = "Underline",
                    selected = isUnderline,
                    onClick = {
                        if (rangeStart < rangeEnd) {
                            onSpansChange(NoteBodyEditor.toggleInline(
                                fullBody, spans, rangeStart, rangeEnd, NoteBodyEditor.InlineKind.UNDERLINE,
                            ))
                        }
                    },
                )
                ToolbarIconButton(
                    icon = Icons.Filled.FormatStrikethrough,
                    contentDescription = "Strikethrough",
                    selected = isStrike,
                    onClick = {
                        if (rangeStart < rangeEnd) {
                            onSpansChange(NoteBodyEditor.toggleInline(
                                fullBody, spans, rangeStart, rangeEnd, NoteBodyEditor.InlineKind.STRIKETHROUGH,
                            ))
                        }
                    },
                )

                ToolbarDivider()

                // Link.
                ToolbarIconButton(
                    icon = Icons.Filled.Link,
                    contentDescription = "Link",
                    selected = curSpan?.linkUrl != null,
                    onClick = { linkDialogOpen = true },
                )
            }
        }
    }

    if (linkDialogOpen) {
        var urlText by remember { mutableStateOf(curSpan?.linkUrl ?: "https://") }
        AlertDialog(
            onDismissRequest = { linkDialogOpen = false },
            title = { Text("Add link") },
            text = {
                OutlinedTextField(
                    value = urlText,
                    onValueChange = { urlText = it },
                    label = { Text("URL") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    linkDialogOpen = false
                    if (rangeStart < rangeEnd) {
                        onSpansChange(NoteBodyEditor.setLink(
                            fullBody, spans, rangeStart, rangeEnd, urlText.takeIf { it.isNotBlank() },
                        ))
                    }
                }) { Text("Apply") }
            },
            dismissButton = {
                TextButton(onClick = {
                    linkDialogOpen = false
                    if (rangeStart < rangeEnd && curSpan?.linkUrl != null) {
                        onSpansChange(NoteBodyEditor.setLink(fullBody, spans, rangeStart, rangeEnd, null))
                    }
                }) { Text(if (curSpan?.linkUrl != null) "Remove" else "Cancel") }
            },
        )
    }
}

private fun paragraphStyleLabel(style: NoteBodyEditor.ParagraphStyle): String = when (style) {
    NoteBodyEditor.ParagraphStyle.TITLE -> "Title"
    NoteBodyEditor.ParagraphStyle.HEADING -> "Heading"
    NoteBodyEditor.ParagraphStyle.SUBHEADING -> "Subheading"
    NoteBodyEditor.ParagraphStyle.BODY -> "Body"
    NoteBodyEditor.ParagraphStyle.MONOSPACED -> "Monospaced"
    NoteBodyEditor.ParagraphStyle.BULLETED_LIST -> "Bulleted"
    NoteBodyEditor.ParagraphStyle.DASHED_LIST -> "Dashed"
    NoteBodyEditor.ParagraphStyle.NUMBERED_LIST -> "Numbered"
    NoteBodyEditor.ParagraphStyle.CHECKBOX_LIST -> "Checkbox"
}

@Composable
private fun ToolbarPill(
    label: String,
    icon: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val bg by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surfaceContainerHigh,
        label = "pillBg",
    )
    val fg by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.onPrimaryContainer
        else MaterialTheme.colorScheme.onSurface,
        label = "pillFg",
    )
    androidx.compose.foundation.layout.Box(
        modifier = Modifier
            .height(40.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                icon,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                color = fg,
                modifier = Modifier.padding(end = 8.dp),
            )
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                color = fg,
            )
            Icon(
                Icons.Filled.ArrowDropDown,
                contentDescription = null,
                tint = fg,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun ToolbarIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val bg by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
        label = "iconBg",
    )
    val fg by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.onPrimaryContainer
        else MaterialTheme.colorScheme.onSurface,
        label = "iconFg",
    )
    androidx.compose.foundation.layout.Box(
        modifier = Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(bg)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = fg,
            modifier = Modifier.size(22.dp),
        )
    }
}

@Composable
private fun ToolbarDivider() {
    androidx.compose.foundation.layout.Box(
        modifier = Modifier
            .padding(horizontal = 4.dp)
            .width(1.dp)
            .height(24.dp)
            .background(MaterialTheme.colorScheme.outlineVariant),
    )
}

/**
 * VisualTransformation that styles a plaintext body in real-time as the user
 * types. Inline runs (bold/italic/underline/strike/link/monospace) get
 * SpanStyles; heading paragraphs get a bigger fontSize. List paragraphs
 * (bulleted/dashed/numbered/checkbox) get a visible marker prefix that's not
 * part of the underlying text — the OffsetMapping skips it so the cursor and
 * selection stay aligned with the raw chars.
 *
 * Limits we accept (vs. true block-by-block layout):
 *  - Markers are inline characters, not Compose Icons. Looks reasonable
 *    using "•", "–", "1.", "☐", "☑".
 *  - Heading sizes via SpanStyle.fontSize, no per-paragraph block padding.
 *  - Tables/images render as their U+FFFC placeholder still — block-level
 *    embeds aren't possible inside a single BasicTextField.
 */
private class FormattingVisualTransformation(
    private val spans: List<NoteBodyEditor.AttrSpan>,
) : androidx.compose.ui.text.input.VisualTransformation {

    override fun filter(text: AnnotatedString): androidx.compose.ui.text.input.TransformedText {
        val raw = text.text
        val perChar = NoteBodyEditor.expandSpansToChars(raw, spans)
        val builder = AnnotatedString.Builder()
        // Per-raw-index running insertion offset: rawToTransformed[i] = position
        // in the displayed string corresponding to raw char i.
        val rawToTransformed = IntArray(raw.length + 1)
        var added = 0
        var listCounter = 0

        var i = 0
        while (i <= raw.length) {
            rawToTransformed[i] = i + added
            if (i == raw.length) break

            // At a paragraph start (idx 0 or just after a \n), maybe inject a
            // marker that visually represents the paragraph style.
            val isParaStart = i == 0 || raw[i - 1] == '\n'
            if (isParaStart) {
                val span = perChar.getOrNull(i)
                val style = span?.style ?: NoteBodyEditor.ParagraphStyle.BODY
                if (style == NoteBodyEditor.ParagraphStyle.NUMBERED_LIST) {
                    listCounter++
                } else {
                    listCounter = 0
                }
                val prefix = when (style) {
                    NoteBodyEditor.ParagraphStyle.BULLETED_LIST -> "•  "
                    NoteBodyEditor.ParagraphStyle.DASHED_LIST -> "–  "
                    NoteBodyEditor.ParagraphStyle.NUMBERED_LIST -> "$listCounter.  "
                    NoteBodyEditor.ParagraphStyle.CHECKBOX_LIST ->
                        if (span?.checked == true) "☑  " else "☐  "
                    else -> ""
                }
                if (prefix.isNotEmpty()) {
                    builder.withStyle(SpanStyle(color = MARKER_COLOR)) {
                        append(prefix)
                    }
                    added += prefix.length
                    rawToTransformed[i] = i + added
                }
            }

            // Group consecutive chars sharing a span (and not crossing
            // paragraph boundaries) and emit them with a single style block.
            var j = i + 1
            while (j < raw.length && raw[j - 1] != '\n' &&
                perChar.getOrNull(j) === perChar.getOrNull(i)
            ) j++
            val span = perChar.getOrNull(i)
            val sub = raw.substring(i, j)
            val style = paragraphSpanStyle(span) merge inlineSpanStyle(span)
            if (style != null) {
                builder.withStyle(style) { append(sub) }
            } else {
                builder.append(sub)
            }
            // Fill rawToTransformed for chars in (i..j].
            for (k in i + 1..j) rawToTransformed[k] = k + added
            i = j
        }

        return androidx.compose.ui.text.input.TransformedText(
            builder.toAnnotatedString(),
            object : androidx.compose.ui.text.input.OffsetMapping {
                override fun originalToTransformed(offset: Int): Int =
                    rawToTransformed[offset.coerceIn(0, raw.length)]

                override fun transformedToOriginal(offset: Int): Int {
                    // First raw index whose transformed offset >= offset.
                    var lo = 0
                    var hi = raw.length
                    while (lo < hi) {
                        val mid = (lo + hi) ushr 1
                        if (rawToTransformed[mid] < offset) lo = mid + 1 else hi = mid
                    }
                    return lo
                }
            },
        )
    }

    private fun paragraphSpanStyle(s: NoteBodyEditor.AttrSpan?): SpanStyle? {
        if (s == null) return null
        return when (s.style) {
            NoteBodyEditor.ParagraphStyle.TITLE ->
                SpanStyle(fontSize = 28.sp, fontWeight = FontWeight.Bold)
            NoteBodyEditor.ParagraphStyle.HEADING ->
                SpanStyle(fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            NoteBodyEditor.ParagraphStyle.SUBHEADING ->
                SpanStyle(fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            NoteBodyEditor.ParagraphStyle.MONOSPACED ->
                SpanStyle(fontFamily = FontFamily.Monospace)
            NoteBodyEditor.ParagraphStyle.CHECKBOX_LIST ->
                if (s.checked) SpanStyle(textDecoration = TextDecoration.LineThrough)
                else null
            else -> null
        }
    }

    /** SpanStyle merge — non-null fields of [other] win. */
    private infix fun SpanStyle?.merge(other: SpanStyle?): SpanStyle? = when {
        this == null -> other
        other == null -> this
        else -> this.merge(other)
    }

    companion object {
        private val MARKER_COLOR = Color(0xFF8A8A8A)
    }
}

/**
 * Re-align an AttrSpan list with edited text. Approach: longest-common-prefix
 * + longest-common-suffix diff; chars in the deleted middle are removed from
 * the per-char span array; chars in the inserted middle inherit the format of
 * the char immediately before the cut (or default body if at the start).
 *
 * Imperfect but good enough — handles typing/backspace/IME composition
 * without losing existing formatting on either side.
 */
internal fun syncSpansToText(
    oldText: String,
    oldSpans: List<NoteBodyEditor.AttrSpan>,
    newText: String,
): List<NoteBodyEditor.AttrSpan> {
    if (oldText == newText) return oldSpans
    val perChar = NoteBodyEditor.expandSpansToChars(oldText, oldSpans)
    var lcp = 0
    val maxPrefix = minOf(oldText.length, newText.length)
    while (lcp < maxPrefix && oldText[lcp] == newText[lcp]) lcp++
    var lcs = 0
    val maxSuffix = minOf(oldText.length - lcp, newText.length - lcp)
    while (lcs < maxSuffix &&
        oldText[oldText.length - 1 - lcs] == newText[newText.length - 1 - lcs]) lcs++
    val deletedRange = lcp until (oldText.length - lcs)
    val insertedLen = newText.length - lcp - lcs

    val template = (perChar.getOrNull(lcp - 1) ?: perChar.firstOrNull())
        ?.copy(length = 1)
        ?: NoteBodyEditor.AttrSpan(length = 1, style = NoteBodyEditor.ParagraphStyle.BODY)

    val newPerChar = ArrayList<NoteBodyEditor.AttrSpan>(newText.length)
    for (i in 0 until lcp) newPerChar.add(perChar[i])
    repeat(insertedLen) {
        // Newly inserted chars carry inline formatting from the previous char,
        // but reset to BODY paragraph style if the insert begins on or after a
        // paragraph break (avoids accidentally extending a Heading run when
        // hitting Enter at end of line).
        val carry = if (template.style == NoteBodyEditor.ParagraphStyle.TITLE ||
            template.style == NoteBodyEditor.ParagraphStyle.HEADING ||
            template.style == NoteBodyEditor.ParagraphStyle.SUBHEADING) {
            template.copy(style = NoteBodyEditor.ParagraphStyle.BODY)
        } else template
        newPerChar.add(carry)
    }
    for (i in (oldText.length - lcs) until oldText.length) newPerChar.add(perChar[i])
    if (newPerChar.size != newText.length) {
        // Defensive — return a single body span covering the whole new text.
        return listOf(NoteBodyEditor.AttrSpan(
            length = newText.length, style = NoteBodyEditor.ParagraphStyle.BODY,
        ))
    }
    return NoteBodyEditor.compactCharsToSpans(newPerChar.toTypedArray())
}

/**
 * Drop the first [skipChars] characters' worth of attribute spans, splitting
 * the boundary span if the cut falls inside one. Used to align spans parsed
 * from the full visible body (which includes the title line) with the body
 * text we render below the app bar.
 */
private fun attrSpansAfter(
    spans: List<NoteBodyEditor.AttrSpan>,
    skipChars: Int,
): List<NoteBodyEditor.AttrSpan> {
    if (skipChars <= 0) return spans
    val out = mutableListOf<NoteBodyEditor.AttrSpan>()
    var consumed = 0
    for (s in spans) {
        if (out.isNotEmpty()) {
            out.add(s)
            continue
        }
        val end = consumed + s.length
        when {
            end <= skipChars -> consumed = end
            consumed >= skipChars -> out.add(s)
            else -> {
                out.add(s.copy(length = end - skipChars))
                consumed = end
            }
        }
    }
    return out
}
