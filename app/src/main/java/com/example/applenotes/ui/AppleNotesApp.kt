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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
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
    data class NotesList(val session: ICloudSession, val notes: List<NoteSummary>) : ScreenState
    data class Detail(val session: ICloudSession, val record: NoteRecord) : ScreenState
}

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
        val notes = AppleNotesClient(httpClient, session).fetchRecents(limit = 100)
            .filter { !it.deleted }
            .sortedByDescending { it.modificationTimestampMs ?: 0L }
        ScreenState.NotesList(session, notes)
    } catch (e: Throwable) {
        Log.e(TAG, "bootstrap failed", e)
        ScreenState.Error(e.message ?: e.toString())
    }

    suspend fun refreshList(session: ICloudSession): List<NoteSummary> =
        AppleNotesClient(httpClient, session).fetchRecents(100)
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
            notes = s.notes,
            onSelect = { note ->
                scope.launch {
                    state = ScreenState.Loading("Loading note…")
                    state = try {
                        val rec = AppleNotesClient(httpClient, s.session).lookupNote(note.recordName)
                        ScreenState.Detail(s.session, rec)
                    } catch (e: Throwable) {
                        ScreenState.Error(e.message ?: e.toString())
                    }
                }
            },
            onRefresh = {
                scope.launch {
                    state = ScreenState.Loading("Refreshing…")
                    state = try {
                        ScreenState.NotesList(s.session, refreshList(s.session))
                    } catch (e: Throwable) {
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
            onBack = {
                // Re-fetch list to pick up our edits
                scope.launch {
                    state = ScreenState.Loading("Loading…")
                    state = try {
                        ScreenState.NotesList(s.session, refreshList(s.session))
                    } catch (e: Throwable) {
                        ScreenState.Error(e.message ?: e.toString())
                    }
                }
            },
            onSave = { newBody, isPureAppend ->
                scope.launch {
                    saving = true
                    try {
                        val uuid = ourReplicaUuid
                            ?: error("Device replica UUID not loaded yet — try again in a moment.")
                        val tag = s.record.recordChangeTag
                            ?: error("No recordChangeTag — refresh first.")
                        val textB64 = s.record.stringField("TextDataEncrypted")
                            ?: error("No body field on this note.")
                        val originalBody = NoteBodyEditor.readTextFromBase64(textB64).orEmpty()
                        val now = System.currentTimeMillis() / 1000L
                        // Only pure-append edits are CRDT-safe with the current implementation.
                        // Mid-string edits and deletions need tombstone-op semantics that aren't
                        // publicly reverse-engineered yet; corrupting the CRDT trashes the note
                        // across every device that has it. Refuse loudly instead.
                        check(isPureAppend) {
                            "Only pure-append edits are supported. Mid-string changes and " +
                                "deletions need CRDT tombstone ops which aren't implemented yet."
                        }
                        val appended = newBody.removePrefix(originalBody)
                        require(appended.isNotEmpty()) { "Pure-append marked but no new chars" }
                        val newB64 = NoteAppender.appendBase64(textB64, uuid, appended, now)
                        val updated = AppleNotesClient(httpClient, s.session)
                            .modifyNoteBody(s.record.recordName, tag, newB64)
                        state = ScreenState.Detail(s.session, updated)
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
                        // back to list, refreshed
                        ScreenState.NotesList(s.session, refreshList(s.session))
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
    notes: List<NoteSummary>,
    onSelect: (NoteSummary) -> Unit,
    onRefresh: () -> Unit,
    onSignOut: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Apple Notes (${notes.size})") },
                actions = {
                    IconButton(onClick = onRefresh) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                    IconButton(onClick = onSignOut) {
                        Icon(Icons.Default.Logout, contentDescription = "Sign out")
                    }
                },
            )
        },
    ) { padding ->
        if (notes.isEmpty()) {
            EmptyState(padding, "No notes returned. Pull to refresh.")
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
                items(notes, key = { it.recordName }) { note ->
                    NoteRow(note, onClick = { onSelect(note) })
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun EmptyState(padding: PaddingValues, msg: String) {
    Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
        Text(msg, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun NoteRow(note: NoteSummary, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickableRow(onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = note.title?.takeIf { it.isNotBlank() } ?: "(untitled)",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            note.modificationTimestampMs?.let { ts ->
                Text(
                    text = humanRelative(ts),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (!note.snippet.isNullOrBlank()) {
            Text(
                text = note.snippet,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NoteDetailScreen(
    record: NoteRecord,
    onBack: () -> Unit,
    onSave: (newBody: String, isPureAppend: Boolean) -> Unit,
    onDelete: () -> Unit,
    saving: Boolean,
) {
    val originalBody = remember(record.recordName, record.recordChangeTag) {
        record.stringField("TextDataEncrypted")?.let { b64 ->
            runCatching { NoteBodyEditor.readTextFromBase64(b64) }.getOrNull()
        }.orEmpty()
    }
    var bodyDraft by remember(record.recordName, record.recordChangeTag) {
        mutableStateOf(originalBody)
    }
    val isModified = bodyDraft != originalBody
    val isPureAppend = remember(bodyDraft, originalBody) {
        bodyDraft.startsWith(originalBody) && bodyDraft.length > originalBody.length
    }
    val titleForBar = bodyDraft.lineSequence().firstOrNull()?.takeIf { it.isNotBlank() } ?: "(untitled)"

    var showDeleteDialog by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }

    Scaffold(
        modifier = Modifier.imePadding(),
        topBar = {
            Column {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Default.ArrowBack, contentDescription = "Back")
                        }
                    },
                    title = { Text(titleForBar, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    actions = {
                        if (isModified) {
                            IconButton(
                                enabled = !saving && isPureAppend,
                                onClick = { onSave(bodyDraft, isPureAppend) },
                            ) {
                                Icon(Icons.Default.Check, contentDescription = "Save")
                            }
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
            if (isModified && !isPureAppend) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.errorContainer)
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    Text(
                        "Only pure-append edits can be saved right now. Mid-string edits and " +
                            "deletions need CRDT tombstone ops that aren't implemented yet — " +
                            "saving them would corrupt the note across all your devices.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            BasicTextField(
                value = bodyDraft,
                onValueChange = { bodyDraft = it },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
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
