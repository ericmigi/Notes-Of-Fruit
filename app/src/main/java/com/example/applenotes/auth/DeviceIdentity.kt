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
    private val UUID_KEY = stringPreferencesKey("replica_uuid_hex")

    suspend fun getOrCreate(context: Context): ByteArray {
        val store = context.applicationContext.deviceIdentityStore
        val existing = store.data.first()[UUID_KEY]
        if (existing != null && existing.length == 32) {
            return hexDecode(existing)
        }
        val fresh = ByteArray(16).also { SecureRandom().nextBytes(it) }
        store.edit { it[UUID_KEY] = hexEncode(fresh) }
        return fresh
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
