package com.expensegarden.app.data

import android.content.Context
import com.expensegarden.app.game.Tone

/** Device-local AI settings and the Gemini API key (spec §2, §3, §8).
 *
 *  Separate from [GardenPrefs] on purpose: that file holds view state, this one holds a
 *  SECRET, and one store mixing both makes it hard to reason about either. The file name
 *  `ai_secrets` is load-bearing — `res/xml/backup_rules.xml` and
 *  `res/xml/data_extraction_rules.xml` exclude it by that exact name so the key never
 *  reaches Google's cloud backup. Renaming this file without updating both XML files
 *  silently re-exposes the key.
 *
 *  Plain SharedPreferences rather than EncryptedSharedPreferences: that needs
 *  androidx.security.crypto, and the pinned dependency matrix admits no new libraries
 *  (spec §2). Proportionate while the key has no billing attached — revisit if it ever does. */
class AiPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("ai_secrets", Context.MODE_PRIVATE)

    /** Blank = no key entered. The whole AI layer degrades to silence in that state. */
    var apiKey: String
        get() = prefs.getString(KEY_API_KEY, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_API_KEY, value.trim()).apply()

    var tone: Tone
        get() = runCatching { Tone.valueOf(prefs.getString(KEY_TONE, null) ?: "SHARP") }
            .getOrDefault(Tone.SHARP)
        set(value) = prefs.edit().putString(KEY_TONE, value.name).apply()

    /** Epoch millis of the last quip-refresh attempt. 0 = never. Throttles to once/day. */
    var lastQuipRefreshAt: Long
        get() = prefs.getLong(KEY_LAST_REFRESH, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_REFRESH, value).apply()

    /** Epoch millis until which the persona is muted by "not today" (spec §8). 0 = not muted. */
    var mutedUntil: Long
        get() = prefs.getLong(KEY_MUTED_UNTIL, 0L)
        set(value) = prefs.edit().putLong(KEY_MUTED_UNTIL, value).apply()

    val hasKey: Boolean get() = apiKey.isNotBlank()

    private companion object {
        const val KEY_API_KEY = "apiKey"
        const val KEY_TONE = "tone"
        const val KEY_LAST_REFRESH = "lastQuipRefreshAt"
        const val KEY_MUTED_UNTIL = "mutedUntil"
    }
}
