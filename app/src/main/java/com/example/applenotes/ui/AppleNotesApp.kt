package com.example.applenotes.ui

import android.annotation.SuppressLint
import android.util.Log
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
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
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
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
                        // Grab a Folder ref from any existing note (CloudKit
                        // requires a CKReference to attach the new note to).
                        // Prefer one from the currently-selected folder so
                        // the new note lands in the same folder the user is
                        // viewing.
                        val sample = s.notes
                            .firstOrNull { it.folderRecordName == s.selectedFolder }
                            ?: s.notes.firstOrNull { it.folderRecordName != TRASH_RECORD_NAME }
                            ?: error("No existing note to copy folder reference from")
                        val sampleRecord = client.lookupNote(sample.recordName)
                        val folderRef = sampleRecord.rawFields["Folder"]
                            ?: error("Sample note has no Folder field")
                        // Create with empty title + body. The user types and
                        // the first non-blank line becomes the title.
                        val created = client.createNote(
                            title = "",
                            body = "",
                            folderRef = folderRef,
                            ourReplicaUuid = uuid,
                        )
                        // Pre-pend the new (empty) summary into the cache so
                        // back-nav shows it immediately without a refetch.
                        val newSummary = NoteSummary(
                            recordName = created.recordName,
                            title = null,
                            snippet = null,
                            modificationTimestampMs = System.currentTimeMillis(),
                            deleted = false,
                            folderRecordName = sample.folderRecordName,
                        )
                        ScreenState.Detail(s.session, created, listOf(newSummary) + s.notes, s.folders, s.selectedFolder)
                    } catch (e: Throwable) {
                        Log.e(TAG, "createNote failed", e)
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
            onBack = {
                // Restore the cached list instantly. No network round trip —
                // `notesCache` was patched in place when we saved, so the snippet
                // for the just-edited note already reflects our changes.
                state = ScreenState.NotesList(s.session, s.notesCache, s.folders, s.selectedFolder)
            },
            onSave = { newBody, isPureAppend, alsoExit ->
                scope.launch {
                    saving = true
                    try {
                        val uuid = ourReplicaUuid
                            ?: error("Device replica UUID not loaded yet — try again in a moment.")
                        val tag = s.record.recordChangeTag
                            ?: error("No recordChangeTag — refresh first.")
                        val textB64 = s.record.stringField("TextDataEncrypted")
                            ?: error("No body field on this note.")
                        val originalBody = NoteBodyEditor.readVisibleTextFromBase64(textB64)
                            ?: NoteBodyEditor.readTextFromBase64(textB64).orEmpty()
                        val now = System.currentTimeMillis() / 1000L
                        // Pure-append edits go through the cheap APPEND path (one
                        // new substring at the sentinel slot). Anything else
                        // (replace/insert/delete in the middle) goes through the
                        // splice path which mirrors iCloud.com's pattern: slot
                        // promotion + split-and-tombstone with fresh ts on the
                        // tombstones + new chunks for inserted text.
                        val newB64 = if (isPureAppend) {
                            val appended = newBody.removePrefix(originalBody)
                            require(appended.isNotEmpty()) { "Pure-append marked but no new chars" }
                            NoteAppender.appendBase64(textB64, uuid, appended, now)
                        } else {
                            NoteAppender.setBodyBase64(textB64, uuid, newBody, now)
                        }
                        // Compute new title and snippet from the post-append body so
                        // Apple's Title/Snippet CloudKit fields stay in sync. Apple's
                        // clients (Mac, iCloud.com list view, our own list view) use
                        // these fields for "did this note visibly change?".
                        val (newTitle, newSnippet) = computeTitleAndSnippet(newBody)
                        val (oldTitle, oldSnippet) = computeTitleAndSnippet(originalBody)
                        Log.i(
                            TAG,
                            "save: title '${oldTitle.take(30)}'->'${newTitle.take(30)}' " +
                                "snippet '${oldSnippet.take(30)}'->'${newSnippet.take(30)}'",
                        )
                        val updated = AppleNotesClient(httpClient, s.session).modifyNoteBody(
                            s.record.recordName,
                            tag,
                            newB64,
                            newTitle = newTitle.takeIf { it != oldTitle },
                            newSnippet = newSnippet.takeIf { it != oldSnippet },
                            replicaVersionPassThroughB64 = s.record.stringField("ReplicaIDToNotesVersionDataEncrypted"),
                        )
                        // Patch the cached list summary for this note so when the
                        // user navigates back they see the updated snippet/title
                        // without a refetch.
                        val patchedCache = s.notesCache.map { note ->
                            if (note.recordName == s.record.recordName) {
                                note.copy(
                                    title = newTitle,
                                    snippet = newSnippet,
                                    modificationTimestampMs = System.currentTimeMillis(),
                                )
                            } else note
                        }
                        // Defensive state update: the save coroutine may complete
                        // AFTER the user has navigated away (e.g. they tapped back,
                        // we triggered a save+exit, save took 500ms, user is now
                        // looking at the list). If the user is still on this same
                        // Detail, refresh with the updated record. If they've
                        // moved to NotesList, just patch its cache silently. Else
                        // (Error, Splash, Login, different Detail), leave alone.
                        val current = state
                        state = when {
                            current is ScreenState.Detail &&
                                current.record.recordName == s.record.recordName ->
                                if (alsoExit) ScreenState.NotesList(current.session, patchedCache, current.folders, current.selectedFolder)
                                else ScreenState.Detail(current.session, updated, patchedCache, current.folders, current.selectedFolder)
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
                    state = ScreenState.Loading("Deleting…")
                    state = try {
                        val tag = s.record.recordChangeTag
                            ?: error("No recordChangeTag — refresh first.")
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
    Scaffold(topBar = { TopAppBar(title = { Text("Apple Notes") }) }) { padding ->
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
    onSave: (newBody: String, isPureAppend: Boolean, alsoExit: Boolean) -> Unit,
    onDelete: () -> Unit,
    saving: Boolean,
    savedHint: String? = null,
    lastSavedAtMs: Long? = null,
    onHintShown: () -> Unit = {},
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
        mutableStateOf(initialBody)
    }
    val fullBodyForSave = when {
        titleDraft.isEmpty() && bodyDraft.isEmpty() -> ""
        titleDraft.isEmpty() -> bodyDraft
        bodyDraft.isEmpty() -> titleDraft
        else -> "$titleDraft\n$bodyDraft"
    }
    val isModified = fullBodyForSave != originalBody
    val isPureAppend = remember(fullBodyForSave, originalBody) {
        fullBodyForSave.startsWith(originalBody) && fullBodyForSave.length > originalBody.length
    }

    // Parse the note's attribute_runs once per record load — kept around so
    // the splice path in NoteAppender can preserve paragraph styles through
    // edits (Phase D1). Display-time formatting via VisualTransformation is
    // a future enhancement; for now the editor shows plain text.
    val attrSpans = remember(record.recordName, record.recordChangeTag) {
        record.stringField("TextDataEncrypted")?.let { b64 ->
            runCatching { NoteBodyEditor.parseAttributeRunsFromBase64(b64) }.getOrNull()
        }.orEmpty()
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
                onSave(fullBodyForSave, isPureAppend, false)
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
            onSave(fullBodyForSave, isPureAppend, true)
        } else {
            onBack()
        }
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
                                onClick = { onSave(fullBodyForSave, isPureAppend, false) },
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
            // Single unified editor — no edit/view mode toggle. The user can
            // read or write at any time; the body is always editable. Title
            // lives in the app bar (editable there) so it's never duplicated
            // here.
            BasicTextField(
                value = bodyDraft,
                onValueChange = { bodyDraft = it },
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
                    if (bodyDraft.isEmpty()) {
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
                    text = "${bodyDraft.length} chars",
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
@Composable
private fun FormattedNoteBody(
    fullBody: String,
    titlePrefixLength: Int,
    attrSpans: List<NoteBodyEditor.AttrSpan>,
    onTap: () -> Unit,
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
        var lastListStyle: NoteBodyEditor.ParagraphStyle? = null
        for (range in paragraphs) {
            val rawText = fullBody.substring(range.first, range.last + 1).trimEnd('\n')
            // The dominant style of a paragraph = the style of its first char.
            val firstStyle = if (range.first < styleAt.size) styleAt[range.first] else null
            // Treat the very first paragraph (the title line) as TITLE if we
            // haven't seen a TITLE attribute_run already.
            val effectiveStyle = when {
                range.first < titlePrefixLength -> NoteBodyEditor.ParagraphStyle.TITLE
                firstStyle != null -> firstStyle.style
                else -> NoteBodyEditor.ParagraphStyle.BODY
            }
            // Reset numbered-list counter when the previous para wasn't the
            // same numbered list.
            if (effectiveStyle != NoteBodyEditor.ParagraphStyle.NUMBERED_LIST) {
                listCounter = 0
            }
            lastListStyle = effectiveStyle

            when (effectiveStyle) {
                NoteBodyEditor.ParagraphStyle.TITLE -> {
                    if (rawText.isNotEmpty()) {
                        Text(
                            rawText,
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
                        rawText,
                        style = MaterialTheme.typography.headlineMedium.copy(
                            fontWeight = FontWeight.SemiBold,
                        ),
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                    )
                }
                NoteBodyEditor.ParagraphStyle.SUBHEADING -> {
                    Text(
                        rawText,
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.SemiBold,
                        ),
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(top = 6.dp, bottom = 2.dp),
                    )
                }
                NoteBodyEditor.ParagraphStyle.MONOSPACED -> {
                    Text(
                        rawText,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        ),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                NoteBodyEditor.ParagraphStyle.BULLETED_LIST -> {
                    Row(verticalAlignment = Alignment.Top) {
                        Text(
                            "•  ",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            rawText,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
                NoteBodyEditor.ParagraphStyle.CHECKBOX_LIST -> {
                    val checked = firstStyle?.checked ?: false
                    Row(verticalAlignment = Alignment.Top) {
                        Icon(
                            if (checked) Icons.Default.CheckBox else Icons.Default.CheckBoxOutlineBlank,
                            contentDescription = if (checked) "Checked" else "Unchecked",
                            tint = if (checked) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .padding(end = 8.dp, top = 2.dp)
                                .height(20.dp),
                        )
                        Text(
                            rawText,
                            style = MaterialTheme.typography.bodyLarge.copy(
                                color = if (checked) MaterialTheme.colorScheme.onSurfaceVariant
                                    else MaterialTheme.colorScheme.onSurface,
                            ),
                        )
                    }
                }
                NoteBodyEditor.ParagraphStyle.NUMBERED_LIST -> {
                    listCounter++
                    Row(verticalAlignment = Alignment.Top) {
                        Text(
                            "$listCounter.  ",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            rawText,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
                NoteBodyEditor.ParagraphStyle.BODY -> {
                    if (rawText.isEmpty()) {
                        Spacer(modifier = Modifier.height(12.dp))
                    } else {
                        Text(
                            rawText,
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
