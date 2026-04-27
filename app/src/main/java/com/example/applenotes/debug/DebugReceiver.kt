package com.example.applenotes.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.applenotes.auth.AppleNotesSession
import com.example.applenotes.auth.CookieReader
import com.example.applenotes.auth.DeviceIdentity
import com.example.applenotes.auth.ICloudSession
import com.example.applenotes.client.AppleNotesClient
import com.example.applenotes.client.NoteSummary
import com.example.applenotes.proto.NoteAppender
import com.example.applenotes.proto.NoteBodyEditor
import com.example.applenotes.ui.computeTitleAndSnippet
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

private const val TAG = "AppleNotesDebug"

/**
 * ADB-driven debug receiver. Lets a closed-loop test (or just plain debugging
 * from a terminal) drive the app's iCloud Notes operations without going
 * through the UI.
 *
 * Triggered by:
 *   adb shell am broadcast -p com.example.applenotes -a com.example.applenotes.<ACTION> [extras...]
 *
 * Actions:
 *   LIST                                    — fetchRecents, log every note
 *   LOOKUP --es recordName <name>           — lookupNote on a specific record
 *   LOOKUP_BY_TITLE --es title <substring>  — list, find first match, look it up
 *   APPEND --es recordName <name> --es text <chars>           — append text to a specific record
 *   APPEND_BY_TITLE --es title <sub> --es text <chars>        — list+match+append
 *
 * Every step logs under the "AppleNotesDebug" tag with a stable prefix so a
 * driver script can pull `adb logcat -s AppleNotesDebug:* AppleNotesClient:* AppleNotesAppender:*`
 * and parse outcomes.
 */
class DebugReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val extras = intent.extras?.keySet()?.associateWith { intent.extras?.get(it) } ?: emptyMap()
        Log.i(TAG, "RECV $action extras=$extras")
        // Optional `strategy` extra to switch the appender's write pattern.
        intent.getStringExtra("strategy")?.let { stratStr ->
            val strat = runCatching { NoteAppender.Strategy.valueOf(stratStr) }.getOrNull()
            if (strat != null) {
                NoteAppender.setStrategy(strat)
                Log.i(TAG, "Strategy set to $strat")
            }
        }
        when (action) {
            ACTION_LIST -> launchOp("LIST") { client, _ ->
                val notes = client.fetchRecents(200)
                Log.i(TAG, "LIST_OK count=${notes.size}")
                notes
            }
            ACTION_LOOKUP -> {
                val recordName = intent.getStringExtra("recordName")
                if (recordName == null) {
                    Log.e(TAG, "LOOKUP missing recordName")
                    return
                }
                launchOp("LOOKUP") { client, _ ->
                    client.lookupNote(recordName)
                    Log.i(TAG, "LOOKUP_OK $recordName")
                }
            }
            ACTION_LOOKUP_BY_TITLE -> {
                val title = intent.getStringExtra("title")
                if (title == null) {
                    Log.e(TAG, "LOOKUP_BY_TITLE missing title")
                    return
                }
                launchOp("LOOKUP_BY_TITLE") { client, _ ->
                    val match = findByTitle(client, title)
                    if (match == null) {
                        Log.w(TAG, "LOOKUP_BY_TITLE_NOT_FOUND title='$title'")
                    } else {
                        Log.i(TAG, "LOOKUP_BY_TITLE_FOUND '$title' -> ${match.recordName}")
                        client.lookupNote(match.recordName)
                        Log.i(TAG, "LOOKUP_BY_TITLE_OK")
                    }
                }
            }
            ACTION_APPEND -> {
                val recordName = intent.getStringExtra("recordName")
                val text = intent.getStringExtra("text")
                if (recordName == null || text == null) {
                    Log.e(TAG, "APPEND missing recordName or text (got rec=$recordName text=$text)")
                    return
                }
                launchOp("APPEND") { client, session ->
                    appendToNote(context, client, recordName, text)
                }
            }
            ACTION_APPEND_BY_TITLE -> {
                val title = intent.getStringExtra("title")
                val text = intent.getStringExtra("text")
                if (title == null || text == null) {
                    Log.e(TAG, "APPEND_BY_TITLE missing title or text")
                    return
                }
                launchOp("APPEND_BY_TITLE") { client, session ->
                    val match = findByTitle(client, title)
                    if (match == null) {
                        Log.w(TAG, "APPEND_BY_TITLE_NOT_FOUND title='$title'")
                    } else {
                        Log.i(TAG, "APPEND_BY_TITLE_FOUND '$title' -> ${match.recordName}")
                        appendToNote(context, client, match.recordName, text)
                    }
                }
            }
            ACTION_DELETE_BY_TITLE -> {
                val title = intent.getStringExtra("title")
                if (title == null) {
                    Log.e(TAG, "DELETE_BY_TITLE missing title")
                    return
                }
                launchOp("DELETE_BY_TITLE") { client, _ ->
                    val match = findByTitle(client, title)
                    if (match == null) {
                        Log.w(TAG, "DELETE_BY_TITLE_NOT_FOUND title='$title'")
                    } else {
                        Log.i(TAG, "DELETE_BY_TITLE_FOUND '$title' -> ${match.recordName}")
                        val record = client.lookupNote(match.recordName)
                        val tag = record.recordChangeTag ?: error("no tag")
                        client.deleteNote(match.recordName, tag)
                        Log.i(TAG, "DELETE_BY_TITLE_OK ${match.recordName}")
                    }
                }
            }
            ACTION_CREATE -> {
                val title = intent.getStringExtra("title") ?: ""
                val body = intent.getStringExtra("body") ?: ""
                launchOp("CREATE") { client, _ ->
                    val uuid = DeviceIdentity.getOrCreate(context)
                    // Find a folder reference from any existing note.
                    val anyNote = client.fetchRecents(1).firstOrNull()
                        ?: error("no existing notes to copy folder reference from")
                    val sample = client.lookupNote(anyNote.recordName)
                    val folderRef = sample.rawFields["Folder"]
                        ?: error("sample note has no Folder field")
                    val created = client.createNote(title, body, folderRef, uuid)
                    Log.i(TAG, "CREATE_OK ${created.recordName}")
                }
            }
            else -> Log.w(TAG, "RECV unknown action=$action")
        }
    }

    private fun launchOp(label: String, op: suspend (AppleNotesClient, ICloudSession) -> Unit) {
        scope.launch {
            try {
                val session = bootstrap()
                val client = AppleNotesClient(httpClient, session)
                op(client, session)
                Log.i(TAG, "$label DONE")
            } catch (e: Throwable) {
                Log.e(TAG, "$label FAILED ${e.javaClass.simpleName}: ${e.message}", e)
            }
        }
    }

    private suspend fun bootstrap(): ICloudSession {
        val cookieReader = CookieReader()
        return AppleNotesSession(httpClient, cookieReader).bootstrap().getOrThrow()
    }

    private suspend fun findByTitle(client: AppleNotesClient, title: String): NoteSummary? {
        val notes = client.fetchRecents(200)
        return notes.firstOrNull { !it.deleted && (it.title?.contains(title, ignoreCase = true) == true) }
    }

    private suspend fun appendToNote(
        context: Context,
        client: AppleNotesClient,
        recordName: String,
        text: String,
    ) {
        val uuid = DeviceIdentity.getOrCreate(context)
        val record = client.lookupNote(recordName)
        val tag = record.recordChangeTag ?: error("no recordChangeTag on $recordName")
        val textB64 = record.stringField("TextDataEncrypted") ?: error("no body on $recordName")
        val originalBody = NoteBodyEditor.readTextFromBase64(textB64).orEmpty()
        val now = System.currentTimeMillis() / 1000L
        val newB64 = NoteAppender.appendBase64(textB64, uuid, text, now)
        val newBody = originalBody + text
        val (oldTitle, oldSnippet) = computeTitleAndSnippet(originalBody)
        val (newTitle, newSnippet) = computeTitleAndSnippet(newBody)
        Log.i(
            TAG,
            "APPEND rec=$recordName text='${text.take(40)}' " +
                "title '${oldTitle.take(30)}'->'${newTitle.take(30)}' " +
                "snippet '${oldSnippet.take(30)}'->'${newSnippet.take(30)}'",
        )
        client.modifyNoteBody(
            recordName, tag, newB64,
            newTitle = newTitle.takeIf { it != oldTitle },
            newSnippet = newSnippet.takeIf { it != oldSnippet },
        )
        Log.i(TAG, "APPEND_OK rec=$recordName")
    }

    companion object {
        private const val ACTION_LIST = "com.example.applenotes.LIST"
        private const val ACTION_LOOKUP = "com.example.applenotes.LOOKUP"
        private const val ACTION_LOOKUP_BY_TITLE = "com.example.applenotes.LOOKUP_BY_TITLE"
        private const val ACTION_APPEND = "com.example.applenotes.APPEND"
        private const val ACTION_APPEND_BY_TITLE = "com.example.applenotes.APPEND_BY_TITLE"
        private const val ACTION_DELETE_BY_TITLE = "com.example.applenotes.DELETE_BY_TITLE"
        private const val ACTION_CREATE = "com.example.applenotes.CREATE"

        private val httpClient by lazy {
            HttpClient(OkHttp) { expectSuccess = false }
        }
        private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    }
}
