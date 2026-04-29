package com.example.applenotes

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * One-time first-launch acknowledgement of the alpha-software disclaimer.
 *
 * We block the UI behind a dialog the first time the app is opened so the
 * user is forced to read & accept the warning before it touches their iCloud
 * Notes. Persisted in DataStore — survives reinstalls of the same data
 * partition but resets on uninstall (which clears the store).
 */
private val Context.alphaWarningStore by preferencesDataStore("alpha_warning")

object AlphaWarning {
    private val ACK_KEY = booleanPreferencesKey("acknowledged_v1")

    suspend fun isAcknowledged(context: Context): Boolean {
        val store = context.applicationContext.alphaWarningStore
        return store.data.first()[ACK_KEY] == true
    }

    suspend fun acknowledge(context: Context) {
        val store = context.applicationContext.alphaWarningStore
        store.edit { it[ACK_KEY] = true }
    }
}
