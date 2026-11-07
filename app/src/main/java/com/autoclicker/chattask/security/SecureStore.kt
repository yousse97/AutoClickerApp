package com.autoclicker.chattask.security

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys

/** Keystore-backed encrypted storage for the per-device room settings. */
class SecureStore(context: Context) {
    private val appContext = context.applicationContext

    private val prefs: SharedPreferences? by lazy {
        // An unreadable file cannot be repaired, so it is replaced and the rooms are entered again.
        val opened = runCatching(::open).getOrNull() ?: run {
            appContext.deleteSharedPreferences(FILE_NAME)
            runCatching(::open).getOrNull()
        }
        // Clears what the licensing system stored in earlier builds.
        opened?.takeIf { it.contains(KEY_LEGACY_ROLE) }?.edit()?.remove(KEY_LEGACY_ROLE)?.apply()
        opened
    }

    var taskRoom: String
        get() = prefs?.getString(KEY_TASK_ROOM, null).orEmpty()
        set(value) = putString(KEY_TASK_ROOM, value)

    var reactiveRoom: String
        get() = prefs?.getString(KEY_REACTIVE_ROOM, null).orEmpty()
        set(value) = putString(KEY_REACTIVE_ROOM, value)

    var restrictTaskRoom: Boolean
        get() = prefs?.getBoolean(KEY_RESTRICT_TASK, false) == true
        set(value) = putBoolean(KEY_RESTRICT_TASK, value)

    private fun putString(key: String, value: String) {
        prefs?.edit()?.putString(key, value)?.apply()
    }

    private fun putBoolean(key: String, value: Boolean) {
        prefs?.edit()?.putBoolean(key, value)?.apply()
    }

    private fun open(): SharedPreferences = EncryptedSharedPreferences.create(
        FILE_NAME,
        MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC),
        appContext,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    private companion object {
        const val FILE_NAME = "secure"
        const val KEY_LEGACY_ROLE = "role"
        const val KEY_TASK_ROOM = "task_room"
        const val KEY_REACTIVE_ROOM = "reactive_room"
        const val KEY_RESTRICT_TASK = "restrict_task"
    }
}
