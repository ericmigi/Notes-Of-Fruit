package com.example.applenotes.auth

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import java.security.SecureRandom

private val Context.deviceIdentityStore by preferencesDataStore("device_identity")

/**
 * Stable 16-byte replica UUID for this Android install.
 *
 * Apple Notes' "topotext" CRDT identifies each editing replica by a 16-byte
 * UUID stored in `Note.replicas`. The replica's INDEX (used in CRDT ops as
 * `(replicaIndex, clock)` CharIDs) is the entry's 1-based position in that
 * list — assigned the first time we register against a given note.
 *
 * Key invariant: this device must use the SAME UUID forever. Using a fresh
 * UUID per session would register a new replica per session and quickly hit
 * Apple's per-note replica cap (and explode the registry size).
 */
object DeviceIdentity {
    // Old storage key held a raw-random UUID — byte 6's version nibble was
    // whatever SecureRandom produced (often NOT the 0x4 required for a v4
    // UUID per RFC 4122). Apple's clients (iCloud.com, Mac, iOS) all
    // produce properly-formatted v4 UUIDs, and we observed Mac's notesync
    // trashing records whose sole replica had a non-v4 UUID (e.g. our
    // accidentally-v3 UUID `632dbf57-3ba1-3699-...`). Bumping the storage
    // key forces every install to mint a fresh, properly-formatted UUID.
    // Old Android-created notes referencing the old UUID are already
    // doomed (already in Recently Deleted on Mac); no migration needed.
    private val UUID_KEY = stringPreferencesKey("replica_uuid_v4_hex")

    suspend fun getOrCreate(context: Context): ByteArray {
        val store = context.applicationContext.deviceIdentityStore
        val existing = store.data.first()[UUID_KEY]
        if (existing != null && existing.length == 32) {
            val bytes = hexDecode(existing)
            if (isValidV4(bytes)) return bytes
            // Stored UUID isn't valid v4 — regenerate.
        }
        val fresh = generateV4Uuid()
        store.edit { it[UUID_KEY] = hexEncode(fresh) }
        return fresh
    }

    /**
     * Generate a properly-formatted v4 (random) UUID per RFC 4122 §4.4.
     * Sets byte 6's high nibble to 0x4 (version=4) and byte 8's high two
     * bits to 0b10 (variant=DCE/RFC 4122). Apple Notes requires v4.
     */
    private fun generateV4Uuid(): ByteArray {
        val bytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
        bytes[6] = ((bytes[6].toInt() and 0x0F) or 0x40).toByte()  // version = 4
        bytes[8] = ((bytes[8].toInt() and 0x3F) or 0x80).toByte()  // variant = 10
        return bytes
    }

    private fun isValidV4(bytes: ByteArray): Boolean {
        if (bytes.size != 16) return false
        val versionNibble = (bytes[6].toInt() ushr 4) and 0x0F
        val variantBits = (bytes[8].toInt() ushr 6) and 0x03
        return versionNibble == 4 && variantBits == 0b10
    }

    fun hexEncode(b: ByteArray): String =
        b.joinToString("") { ((it.toInt() and 0xFF) + 0x100).toString(16).substring(1) }

    private fun hexDecode(s: String): ByteArray {
        val out = ByteArray(s.length / 2)
        for (i in out.indices) {
            val hi = Character.digit(s[i * 2], 16)
            val lo = Character.digit(s[i * 2 + 1], 16)
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }
}
