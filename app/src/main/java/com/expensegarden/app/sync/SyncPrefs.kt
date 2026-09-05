package com.expensegarden.app.sync

import android.content.Context

/** Device-local sync settings and the bearer token (spec §4).
 *
 *  A NEW prefs file rather than a field in `ai_secrets.xml`. 1D documented that filename as
 *  load-bearing — `res/xml/backup_rules.xml` and `res/xml/data_extraction_rules.xml` exclude
 *  it by exact name so the Gemini key never reaches Google's cloud backup. Widening that
 *  file's meaning invites precisely the rename that silently re-exposes a secret. This file
 *  is excluded by both XMLs under its own name, `sync_secrets`.
 *
 *  Plain SharedPreferences, not EncryptedSharedPreferences: that needs androidx.security.crypto
 *  and the dependency matrix is pinned. Same proportionality call as AiPrefs. */
class SyncPrefs(context: Context) : SyncClock.Store {
    private val prefs = context.getSharedPreferences("sync_secrets", Context.MODE_PRIVATE)

    /** Base URL of core-api, e.g. "http://10.0.2.2:8080". Blank = sync disabled. */
    var serverUrl: String
        get() = prefs.getString(KEY_URL, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_URL, value.trim().trimEnd('/')).apply()

    var token: String
        get() = prefs.getString(KEY_TOKEN, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_TOKEN, value.trim()).apply()

    /** High-water mark of `updatedAt` successfully pushed. */
    var lastPushedAt: Long
        get() = prefs.getLong(KEY_PUSHED_AT, 0L)
        set(value) = prefs.edit().putLong(KEY_PUSHED_AT, value).apply()

    /** High-water mark of `game_event.id` successfully pushed. */
    var lastPushedEventId: Long
        get() = prefs.getLong(KEY_PUSHED_EVENT, 0L)
        set(value) = prefs.edit().putLong(KEY_PUSHED_EVENT, value).apply()

    /** Epoch millis of the last 2xx. 0 = never. Drives the Settings status line. */
    var lastSuccessAt: Long
        get() = prefs.getLong(KEY_LAST_OK, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_OK, value).apply()

    /** SyncClock.Store — the logical clock's persisted high-water mark. */
    override var lastStamp: Long
        get() = prefs.getLong(KEY_LAST_STAMP, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_STAMP, value).apply()

    val isConfigured: Boolean get() = serverUrl.isNotBlank() && token.isNotBlank()

    private companion object {
        const val KEY_URL = "serverUrl"
        const val KEY_TOKEN = "token"
        const val KEY_PUSHED_AT = "lastPushedAt"
        const val KEY_PUSHED_EVENT = "lastPushedEventId"
        const val KEY_LAST_OK = "lastSuccessAt"
        const val KEY_LAST_STAMP = "lastStamp"
    }
}
