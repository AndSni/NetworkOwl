package com.networkowl.app.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.store by preferencesDataStore("networkowl")

data class Settings(
    val host: String = "192.168.50.1",
    val port: Int = 22,
    val user: String = "",
    val hostKey: String? = null,
    val pollSeconds: Int = 3,
    /** Start the router-side recorder whenever the app connects (a reboot stops it). */
    val autoRecorder: Boolean = true,
    val recorderSeconds: Int = 30,
    /** MACs the owner has seen; anything else is flagged NEW. Empty until the first connect. */
    val knownDevices: Set<String> = emptySet(),
    val knownInitialised: Boolean = false,
) {
    val configured: Boolean get() = user.isNotBlank() && host.isNotBlank()
}

class Prefs(private val context: Context) {
    private object K {
        val host = stringPreferencesKey("host")
        val port = intPreferencesKey("port")
        val user = stringPreferencesKey("user")
        val hostKey = stringPreferencesKey("host_key")
        val poll = intPreferencesKey("poll_seconds")
        val autoRecorder = booleanPreferencesKey("auto_recorder")
        val recorderSeconds = intPreferencesKey("recorder_seconds")
        val known = stringSetPreferencesKey("known_devices")
        val knownInit = booleanPreferencesKey("known_initialised")
    }

    val settings: Flow<Settings> = context.store.data.map { p -> p.toSettings() }

    suspend fun current(): Settings = settings.first()

    private fun Preferences.toSettings() = Settings(
        host = this[K.host] ?: "192.168.50.1",
        port = this[K.port] ?: 22,
        user = this[K.user] ?: "",
        hostKey = this[K.hostKey],
        pollSeconds = this[K.poll] ?: 3,
        autoRecorder = this[K.autoRecorder] ?: true,
        recorderSeconds = this[K.recorderSeconds] ?: 30,
        knownDevices = this[K.known] ?: emptySet(),
        knownInitialised = this[K.knownInit] ?: false,
    )

    suspend fun saveConnection(host: String, port: Int, user: String) = context.store.edit {
        // A different router (or address) must not inherit the old pinned host key.
        if (it[K.host] != host || it[K.port] != port) it.remove(K.hostKey)
        it[K.host] = host
        it[K.port] = port
        it[K.user] = user
    }

    suspend fun pinHostKey(key: String?) = context.store.edit {
        if (key == null) it.remove(K.hostKey) else it[K.hostKey] = key
    }

    suspend fun setPollSeconds(s: Int) = context.store.edit { it[K.poll] = s }
    suspend fun setAutoRecorder(on: Boolean) = context.store.edit { it[K.autoRecorder] = on }
    suspend fun setRecorderSeconds(s: Int) = context.store.edit { it[K.recorderSeconds] = s }

    suspend fun markKnown(macs: Collection<String>) = context.store.edit {
        it[K.known] = (it[K.known] ?: emptySet()) + macs
        it[K.knownInit] = true
    }

    suspend fun forget(mac: String) = context.store.edit {
        it[K.known] = (it[K.known] ?: emptySet()) - mac
    }
}
