package com.example.applenotes.client

import android.util.Log
import com.example.applenotes.auth.ICloudSession
import com.example.applenotes.auth.LENIENT_JSON
import com.example.applenotes.auth.USER_AGENT
import com.example.applenotes.proto.NoteBodyEditor
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.headers
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.jsonPrimitive

private const val TAG = "AppleNotesClient"

/** Lightweight summary returned by recents(). title is decoded base64 → UTF-8 best effort. */
data class NoteSummary(
    val recordName: String,
    val title: String?,
    val snippet: String?,
    val modificationTimestampMs: Long?,
    val deleted: Boolean,
)

/** Full record for the note detail view. */
data class NoteRecord(
    val recordName: String,
    val recordType: String?,
    val recordChangeTag: String?,
    val rawFields: Map<String, JsonElement>,
) {
    fun stringField(name: String): String? = rawFields[name]?.let { extractStringValue(it) }
    fun decodedTitle(): String? = rawFields["TitleEncrypted"]?.let { decodeEncryptedString(it) }
}

/**
 * Minimal CloudKit JSON client for `com.apple.notes` (private DB). Mirrors the
 * request shape pyicloud uses post-cookie-bootstrap.
 *
 * `*Encrypted` fields come back as base64 of plaintext when authenticated via
 * the web cookies (the X-APPLE-WEBAUTH-PCS-Notes cookie carries the unwrapped
 * Notes PCS key, so the server pre-decrypts).
 */
class AppleNotesClient(
    private val httpClient: HttpClient,
    private val session: ICloudSession,
) {
    suspend fun fetchRecents(limit: Int = 50): List<NoteSummary> {
        val payload: JsonObject = buildJsonObject {
            put("zoneID", buildJsonObject { put("zoneName", "Notes") })
            put("resultsLimit", limit)
            put("query", buildJsonObject {
                put("recordType", "SearchIndexes")
                putJsonArray("filterBy") {
                    add(buildJsonObject {
                        put("comparator", "EQUALS")
                        put("fieldName", "indexName")
                        put("fieldValue", buildJsonObject {
                            put("type", "STRING")
                            put("value", "recents")
                        })
                    })
                }
                putJsonArray("sortBy") {
                    add(buildJsonObject {
                        put("fieldName", "modTime")
                        put("ascending", false)
                    })
                }
            })
            putJsonArray("desiredKeys") {
                listOf("TitleEncrypted", "SnippetEncrypted", "ModificationDate", "Deleted", "Folder")
                    .forEach { add(JsonPrimitive(it)) }
            }
        }
        Log.i(TAG, "fetchRecents: requesting up to $limit notes")
        val t0 = System.currentTimeMillis()
        val (status, raw) = postJson("/records/query", payload)
        val elapsed = System.currentTimeMillis() - t0
        Log.i(TAG, "fetchRecents response: HTTP $status, ${raw.length}B in ${elapsed}ms")
        if (status !in 200..299) {
            Log.e(TAG, "fetchRecents FAILED body=${raw.take(800)}")
            error("CloudKit records/query HTTP $status: ${raw.take(800)}")
        }
        val parsed = LENIENT_JSON.decodeFromString(QueryResponse.serializer(), raw)
        val results = (parsed.records ?: emptyList()).map { rec ->
            NoteSummary(
                recordName = rec.recordName ?: "",
                title = rec.fields?.get("TitleEncrypted")?.let(::decodeEncryptedString),
                snippet = rec.fields?.get("SnippetEncrypted")?.let(::decodeEncryptedString),
                modificationTimestampMs = rec.fields?.get("ModificationDate")
                    ?.let(::extractStringValue)?.toLongOrNull(),
                deleted = rec.fields?.get("Deleted")?.let(::extractStringValue) == "1",
            )
        }
        Log.i(TAG, "fetchRecents: returning ${results.size} records (continuationMarker=${parsed.continuationMarker != null})")
        for ((i, n) in results.withIndex()) {
            Log.i(
                TAG,
                "  [$i] ${n.recordName} title='${n.title?.take(40)}' " +
                    "snippet='${n.snippet?.take(40)}' " +
                    "mod=${n.modificationTimestampMs} deleted=${n.deleted}",
            )
        }
        return results
    }

    @OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
    suspend fun lookupNote(recordName: String): NoteRecord {
        val payload: JsonObject = buildJsonObject {
            put("zoneID", buildJsonObject { put("zoneName", "Notes") })
            putJsonArray("records") {
                add(buildJsonObject { put("recordName", recordName) })
            }
        }
        Log.i(TAG, "lookupNote: $recordName")
        val t0 = System.currentTimeMillis()
        val (status, raw) = postJson("/records/lookup", payload)
        val elapsed = System.currentTimeMillis() - t0
        Log.i(TAG, "lookupNote response: HTTP $status, ${raw.length}B in ${elapsed}ms")
        if (status !in 200..299) {
            Log.e(TAG, "lookupNote FAILED body=${raw.take(800)}")
            error("CloudKit records/lookup HTTP $status: ${raw.take(800)}")
        }
        val parsed = LENIENT_JSON.decodeFromString(QueryResponse.serializer(), raw)
        val rec = parsed.records?.firstOrNull()
            ?: error("records/lookup returned no record for $recordName")
        rec.serverErrorCode?.let { code ->
            Log.e(TAG, "lookupNote per-record error: $code reason=${rec.reason}")
            error("lookup per-record error: $code reason=${rec.reason ?: "?"}")
        }
        val record = NoteRecord(
            recordName = rec.recordName ?: recordName,
            recordType = rec.recordType,
            recordChangeTag = rec.recordChangeTag,
            rawFields = rec.fields ?: emptyMap(),
        )
        Log.i(TAG, "lookupNote: tag=${record.recordChangeTag} fields=${record.rawFields.keys}")
        record.stringField("TitleEncrypted")?.let {
            val decoded = runCatching { kotlin.io.encoding.Base64.decode(it).decodeToString() }.getOrNull()
            Log.i(TAG, "lookupNote TitleEncrypted decoded='${decoded?.take(60)}'")
        }
        record.stringField("SnippetEncrypted")?.let {
            val decoded = runCatching { kotlin.io.encoding.Base64.decode(it).decodeToString() }.getOrNull()
            Log.i(TAG, "lookupNote SnippetEncrypted decoded='${decoded?.take(60)}'")
        }
        record.stringField("ModificationDate")?.let {
            Log.i(TAG, "lookupNote ModificationDate=$it")
        }
        logRecordSummary("lookupNote", record)
        return record
    }

    /**
     * Modify arbitrary fields on a Note record in one call. Each entry is
     * (fieldName, value, type) where type matches CloudKit's wire type
     * (e.g. "ENCRYPTED_BYTES" for body proto, "STRING" for arbitrary text).
     */
    suspend fun modifyFields(
        recordName: String,
        recordChangeTag: String,
        fields: Map<String, Pair<String, String>>, // fieldName -> (value, type)
    ): NoteRecord {
        val payload: JsonObject = buildJsonObject {
            put("zoneID", buildJsonObject { put("zoneName", "Notes") })
            putJsonArray("operations") {
                add(buildJsonObject {
                    put("operationType", "update")
                    put("record", buildJsonObject {
                        put("recordName", recordName)
                        put("recordType", "Note")
                        put("recordChangeTag", recordChangeTag)
                        put("fields", buildJsonObject {
                            for ((name, vt) in fields) {
                                put(name, buildJsonObject {
                                    put("type", vt.second)
                                    put("value", vt.first)
                                })
                            }
                        })
                    })
                })
            }
        }
        Log.i(TAG, "modifyFields: $recordName tag=$recordChangeTag fields=${fields.keys}")
        val (status, raw) = postJson("/records/modify", payload)
        Log.i(TAG, "modifyFields response: HTTP $status, body[0..400]=${raw.take(400)}")
        if (status !in 200..299) error("CloudKit records/modify HTTP $status: ${raw.take(800)}")
        val parsed = LENIENT_JSON.decodeFromString(QueryResponse.serializer(), raw)
        val rec = parsed.records?.firstOrNull() ?: error("records/modify returned no record")
        rec.serverErrorCode?.let { code ->
            error("modifyFields per-record error: $code reason=${rec.reason ?: "?"}")
        }
        val record = NoteRecord(
            recordName = rec.recordName ?: recordName,
            recordType = rec.recordType,
            recordChangeTag = rec.recordChangeTag,
            rawFields = rec.fields ?: emptyMap(),
        )
        Log.i(TAG, "modifyFields returned tag=${record.recordChangeTag} fieldKeys=${record.rawFields.keys}")
        return record
    }

    /**
     * Delete a note via a hard CloudKit delete operation. Mac/iOS Notes
     * surface this as moving the note to "Recently Deleted" (server-managed).
     */
    suspend fun deleteNote(recordName: String, recordChangeTag: String): String {
        val payload: JsonObject = buildJsonObject {
            put("zoneID", buildJsonObject { put("zoneName", "Notes") })
            putJsonArray("operations") {
                add(buildJsonObject {
                    put("operationType", "forceDelete")
                    put("record", buildJsonObject {
                        put("recordName", recordName)
                        put("recordType", "Note")
                        put("recordChangeTag", recordChangeTag)
                    })
                })
            }
        }
        Log.i(TAG, "deleteNote: $recordName tag=$recordChangeTag")
        val (status, raw) = postJson("/records/modify", payload)
        Log.i(TAG, "deleteNote response: HTTP $status, ${raw.length}B body[0..400]=${raw.take(400)}")
        if (status !in 200..299) error("CloudKit deleteNote HTTP $status: ${raw.take(800)}")
        return raw
    }

    /**
     * Create a brand-new Note record with the given title + body. We need a
     * Folder reference (CKReference) to attach it under — easiest is to copy
     * one from any existing note. The TextDataEncrypted body is a bare
     * topotext.String registering us as the sole replica with one substring
     * covering the entire content.
     */
    @OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
    suspend fun createNote(
        title: String,
        body: String,
        folderRef: JsonElement,
        ourReplicaUuid: ByteArray,
    ): NoteRecord {
        val recordName = java.util.UUID.randomUUID().toString().uppercase()
        // Combine title and body into a single note_text where line 1 = title.
        val noteText = if (title.isNotEmpty() && body.isNotEmpty()) "$title\n$body"
            else if (title.isNotEmpty()) title
            else body
        val textDataB64 = com.example.applenotes.proto.NoteCreator.buildEmptyNoteB64(
            ourReplicaUuid, noteText,
        )
        val payload: JsonObject = buildJsonObject {
            put("zoneID", buildJsonObject { put("zoneName", "Notes") })
            putJsonArray("operations") {
                add(buildJsonObject {
                    put("operationType", "create")
                    put("record", buildJsonObject {
                        put("recordName", recordName)
                        put("recordType", "Note")
                        put("fields", buildJsonObject {
                            put("TextDataEncrypted", buildJsonObject {
                                put("type", "ENCRYPTED_BYTES")
                                put("value", textDataB64)
                            })
                            put("TitleEncrypted", buildJsonObject {
                                put("type", "ENCRYPTED_STRING")
                                put("value", kotlin.io.encoding.Base64.encode(title.encodeToByteArray()))
                            })
                            put("SnippetEncrypted", buildJsonObject {
                                put("type", "ENCRYPTED_STRING")
                                put("value", kotlin.io.encoding.Base64.encode(body.take(120).encodeToByteArray()))
                            })
                            // Reuse Folder reference structure verbatim from the sample note.
                            put("Folder", folderRef)
                        })
                    })
                })
            }
        }
        Log.i(TAG, "createNote: $recordName title='${title.take(40)}' body.len=${body.length}")
        val (status, raw) = postJson("/records/modify", payload)
        Log.i(TAG, "createNote response: HTTP $status, ${raw.length}B body[0..400]=${raw.take(400)}")
        if (status !in 200..299) error("CloudKit createNote HTTP $status: ${raw.take(800)}")
        val parsed = LENIENT_JSON.decodeFromString(QueryResponse.serializer(), raw)
        val rec = parsed.records?.firstOrNull() ?: error("createNote returned no record")
        rec.serverErrorCode?.let { code ->
            error("createNote per-record error: $code reason=${rec.reason ?: "?"}")
        }
        val record = NoteRecord(
            recordName = rec.recordName ?: recordName,
            recordType = rec.recordType,
            recordChangeTag = rec.recordChangeTag,
            rawFields = rec.fields ?: emptyMap(),
        )
        Log.i(TAG, "createNote returned tag=${record.recordChangeTag} fields=${record.rawFields.keys}")
        return record
    }

    /**
     * Replace TextDataEncrypted on a Note record, AND optionally update the
     * sibling TitleEncrypted / SnippetEncrypted fields. Apple's clients seem
     * to use Title and Snippet as the "did this note visibly change?" signal —
     * iCloud.com sets them whenever it edits. Skipping them is what kept Mac
     * from refreshing earlier (and the Android list view from updating, since
     * fetchRecents reads only Title/Snippet).
     */
    @OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
    suspend fun modifyNoteBody(
        recordName: String,
        recordChangeTag: String,
        newTextDataEncryptedB64: String,
        newTitle: String? = null,
        newSnippet: String? = null,
    ): NoteRecord {
        val payload: JsonObject = buildJsonObject {
            put("zoneID", buildJsonObject { put("zoneName", "Notes") })
            putJsonArray("operations") {
                add(buildJsonObject {
                    put("operationType", "update")
                    put("record", buildJsonObject {
                        put("recordName", recordName)
                        put("recordType", "Note")
                        put("recordChangeTag", recordChangeTag)
                        put("fields", buildJsonObject {
                            put("TextDataEncrypted", buildJsonObject {
                                put("type", "ENCRYPTED_BYTES")
                                put("value", newTextDataEncryptedB64)
                            })
                            if (newTitle != null) {
                                put("TitleEncrypted", buildJsonObject {
                                    put("type", "ENCRYPTED_STRING")
                                    put("value", kotlin.io.encoding.Base64.encode(newTitle.encodeToByteArray()))
                                })
                            }
                            if (newSnippet != null) {
                                put("SnippetEncrypted", buildJsonObject {
                                    put("type", "ENCRYPTED_STRING")
                                    put("value", kotlin.io.encoding.Base64.encode(newSnippet.encodeToByteArray()))
                                })
                            }
                        })
                    })
                })
            }
        }
        Log.i(
            TAG,
            "modifyNoteBody: rec=$recordName tag=$recordChangeTag b64.len=${newTextDataEncryptedB64.length} " +
                "title=${newTitle?.let { "'${it.take(40)}'" } ?: "<unchanged>"} " +
                "snippet=${newSnippet?.let { "'${it.take(40)}'" } ?: "<unchanged>"}",
        )
        Log.i(TAG, "modifyNoteBody SENT proto: ${NoteBodyEditor.summarizeBase64(newTextDataEncryptedB64)}")
        val t0 = System.currentTimeMillis()
        val (status, raw) = postJson("/records/modify", payload)
        val elapsed = System.currentTimeMillis() - t0
        Log.i(TAG, "modifyNoteBody response: HTTP $status, ${raw.length}B in ${elapsed}ms")
        Log.i(TAG, "modifyNoteBody response body[0..600]=${raw.take(600)}")
        if (status !in 200..299) {
            Log.e(TAG, "modifyNoteBody FAILED body=${raw.take(800)}")
            error("CloudKit records/modify HTTP $status: ${raw.take(800)}")
        }
        val parsed = LENIENT_JSON.decodeFromString(QueryResponse.serializer(), raw)
        val rec = parsed.records?.firstOrNull() ?: error("records/modify returned no record")
        rec.serverErrorCode?.let { code ->
            Log.e(TAG, "modifyNoteBody per-record error: $code reason=${rec.reason}")
            error("modifyNoteBody per-record error: $code reason=${rec.reason ?: "?"}")
        }
        val record = NoteRecord(
            recordName = rec.recordName ?: recordName,
            recordType = rec.recordType,
            recordChangeTag = rec.recordChangeTag,
            rawFields = rec.fields ?: emptyMap(),
        )
        Log.i(TAG, "modifyNoteBody returned tag=${record.recordChangeTag} fieldKeys=${record.rawFields.keys}")
        // Decoded values for the human-readable fields if echoed.
        record.stringField("TitleEncrypted")?.let {
            val decoded = runCatching { kotlin.io.encoding.Base64.decode(it).decodeToString() }.getOrNull()
            Log.i(TAG, "modifyNoteBody returned TitleEncrypted.b64='${it.take(60)}' decoded='${decoded?.take(40)}'")
        }
        record.stringField("SnippetEncrypted")?.let {
            val decoded = runCatching { kotlin.io.encoding.Base64.decode(it).decodeToString() }.getOrNull()
            Log.i(TAG, "modifyNoteBody returned SnippetEncrypted.b64='${it.take(60)}' decoded='${decoded?.take(40)}'")
        }
        logRecordSummary("modifyNoteBody", record, sentB64 = newTextDataEncryptedB64)

        // Verify-after-save: re-fetch and compare.
        val verified = runCatching { lookupNote(recordName) }.getOrElse {
            Log.w(TAG, "verify-after-save lookup failed", it)
            return record
        }
        val verifiedB64 = verified.stringField("TextDataEncrypted")
        if (verifiedB64 != null) {
            val matchesSent = verifiedB64 == newTextDataEncryptedB64
            Log.i(TAG, "verify-after-save: tag=${verified.recordChangeTag} text.b64.len=${verifiedB64.length} matchesSent=$matchesSent")
        }
        return verified
    }

    private fun logRecordSummary(label: String, record: NoteRecord, sentB64: String? = null) {
        val textB64 = record.stringField("TextDataEncrypted")
        if (textB64 != null) {
            val tag = if (sentB64 != null) " matchesSent=${textB64 == sentB64}" else ""
            Log.i(TAG, "$label TextDataEncrypted.b64.len=${textB64.length}$tag")
            // Log first 32 raw bytes so we can identify the encoding (gzip
            // magic 1f 8b, raw proto, encrypted, etc.) when summarize fails.
            runCatching {
                @OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
                val raw = kotlin.io.encoding.Base64.decode(textB64)
                val prefix = raw.take(32).joinToString("") {
                    ((it.toInt() and 0xFF) + 0x100).toString(16).substring(1)
                }
                Log.i(TAG, "$label TextDataEncrypted raw[0..32] hex=$prefix (totalRaw=${raw.size})")
            }
            Log.i(TAG, "$label TextDataEncrypted proto: ${NoteBodyEditor.summarizeBase64(textB64)}")
            if (label == "lookupNote") {
                Log.i(TAG, "$label replicas dump:\n${NoteBodyEditor.dumpReplicasBase64(textB64)}")
                Log.i(TAG, "$label attribute_runs dump:\n${NoteBodyEditor.dumpAttributeRunsBase64(textB64)}")
            }
        } else {
            Log.i(TAG, "$label has no TextDataEncrypted in returned fields (keys=${record.rawFields.keys})")
        }
        val replicaRegistryB64 = record.stringField("ReplicaIDToNotesVersionDataEncrypted")
        if (replicaRegistryB64 != null) {
            Log.i(TAG, "$label ReplicaIDToNotesVersionDataEncrypted.b64.len=${replicaRegistryB64.length}")
            Log.i(TAG, "$label ReplicaIDToNotesVersion structure:\n${NoteBodyEditor.dumpRawBase64(replicaRegistryB64)}")
        }
    }

    private suspend fun postJson(relPath: String, payload: JsonObject): Pair<Int, String> {
        val url = buildString {
            append(session.ckdatabasewsUrl)
            append("/database/1/com.apple.notes/production/private")
            append(relPath)
            append("?remapEnums=true")
            append("&getCurrentSyncToken=true")
            append("&clientBuildNumber=2612Build21")
            append("&clientMasteringNumber=2612Build21")
            append("&clientId=").append(session.clientId)
            append("&dsid=").append(session.dsid)
        }
        val response = httpClient.post(url) {
            headers {
                append(HttpHeaders.Cookie, session.cookieHeader)
                append(HttpHeaders.Origin, "https://www.icloud.com")
                append(HttpHeaders.Referrer, "https://www.icloud.com/")
                append(HttpHeaders.UserAgent, USER_AGENT)
            }
            contentType(ContentType.Application.Json)
            setBody(LENIENT_JSON.encodeToString(JsonObject.serializer(), payload))
        }
        return response.status.value to response.body<String>()
    }
}

internal fun extractStringValue(field: JsonElement): String? {
    val obj = field as? JsonObject ?: return null
    val value = obj["value"] ?: return null
    return runCatching { value.jsonPrimitive.content }.getOrNull()
}

@OptIn(ExperimentalEncodingApi::class)
internal fun decodeEncryptedString(field: JsonElement): String? {
    val raw = extractStringValue(field) ?: return null
    return runCatching { Base64.decode(raw).decodeToString() }.getOrNull() ?: raw
}

@Serializable
private data class QueryResponse(
    val records: List<RecordSummary>? = null,
    @SerialName("continuationMarker") val continuationMarker: String? = null,
    val syncToken: String? = null,
)

@Serializable
private data class RecordSummary(
    val recordName: String? = null,
    val recordType: String? = null,
    val recordChangeTag: String? = null,
    val fields: Map<String, JsonElement>? = null,
    val created: JsonElement? = null,
    val modified: JsonElement? = null,
    val serverErrorCode: String? = null,
    val reason: String? = null,
)
