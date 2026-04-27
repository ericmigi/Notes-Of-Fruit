package com.example.applenotes.client

import android.util.Log
import com.example.applenotes.auth.ICloudSession
import com.example.applenotes.auth.LENIENT_JSON
import com.example.applenotes.auth.USER_AGENT
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
        val (status, raw) = postJson("/records/query", payload)
        if (status !in 200..299) error("CloudKit records/query HTTP $status: ${raw.take(800)}")
        val parsed = LENIENT_JSON.decodeFromString(QueryResponse.serializer(), raw)
        return (parsed.records ?: emptyList()).map { rec ->
            NoteSummary(
                recordName = rec.recordName ?: "",
                title = rec.fields?.get("TitleEncrypted")?.let(::decodeEncryptedString),
                snippet = rec.fields?.get("SnippetEncrypted")?.let(::decodeEncryptedString),
                modificationTimestampMs = rec.fields?.get("ModificationDate")
                    ?.let(::extractStringValue)?.toLongOrNull(),
                deleted = rec.fields?.get("Deleted")?.let(::extractStringValue) == "1",
            )
        }
    }

    suspend fun lookupNote(recordName: String): NoteRecord {
        val payload: JsonObject = buildJsonObject {
            put("zoneID", buildJsonObject { put("zoneName", "Notes") })
            putJsonArray("records") {
                add(buildJsonObject { put("recordName", recordName) })
            }
        }
        Log.i(TAG, "lookupNote: $recordName")
        val (status, raw) = postJson("/records/lookup", payload)
        if (status !in 200..299) error("CloudKit records/lookup HTTP $status: ${raw.take(800)}")
        val parsed = LENIENT_JSON.decodeFromString(QueryResponse.serializer(), raw)
        val rec = parsed.records?.firstOrNull()
            ?: error("records/lookup returned no record for $recordName")
        rec.serverErrorCode?.let { code ->
            error("lookup per-record error: $code reason=${rec.reason ?: "?"}")
        }
        return NoteRecord(
            recordName = rec.recordName ?: recordName,
            recordType = rec.recordType,
            recordChangeTag = rec.recordChangeTag,
            rawFields = rec.fields ?: emptyMap(),
        )
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
        return NoteRecord(
            recordName = rec.recordName ?: recordName,
            recordType = rec.recordType,
            recordChangeTag = rec.recordChangeTag,
            rawFields = rec.fields ?: emptyMap(),
        )
    }

    /** Soft-delete a note by setting Deleted=1 on its record. */
    suspend fun deleteNote(recordName: String, recordChangeTag: String): NoteRecord {
        return modifyFields(
            recordName = recordName,
            recordChangeTag = recordChangeTag,
            fields = mapOf("Deleted" to ("1" to "INT64")),
        )
    }

    /** Replace TextDataEncrypted on a Note record. */
    suspend fun modifyNoteBody(
        recordName: String,
        recordChangeTag: String,
        newTextDataEncryptedB64: String,
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
                        })
                    })
                })
            }
        }
        Log.i(TAG, "modifyNoteBody: $recordName tag=$recordChangeTag b64.len=${newTextDataEncryptedB64.length}")
        val (status, raw) = postJson("/records/modify", payload)
        Log.i(TAG, "modifyNoteBody response: HTTP $status, body[0..400]=${raw.take(400)}")
        if (status !in 200..299) error("CloudKit records/modify HTTP $status: ${raw.take(800)}")
        val parsed = LENIENT_JSON.decodeFromString(QueryResponse.serializer(), raw)
        val rec = parsed.records?.firstOrNull() ?: error("records/modify returned no record")
        rec.serverErrorCode?.let { code ->
            error("modifyNoteBody per-record error: $code reason=${rec.reason ?: "?"}")
        }
        return NoteRecord(
            recordName = rec.recordName ?: recordName,
            recordType = rec.recordType,
            recordChangeTag = rec.recordChangeTag,
            rawFields = rec.fields ?: emptyMap(),
        )
    }

    private suspend fun postJson(relPath: String, payload: JsonObject): Pair<Int, String> {
        val url = buildString {
            append(session.ckdatabasewsUrl)
            append("/database/1/com.apple.notes/production/private")
            append(relPath)
            append("?remapEnums=true")
            append("&getCurrentSyncToken=true")
            append("&clientBuildNumber=2426Hotfix3")
            append("&clientMasteringNumber=2426Hotfix3")
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
