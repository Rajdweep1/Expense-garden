package com.expensegarden.app.sync

/** A server url and token, normalised the way SyncPrefs stores them. Restore reads what is typed
 *  in Settings through one of these, before anything is saved: saving first would let Save's
 *  check push this phone's rows into the account about to be restored (users spec, finding 1). */
class SyncEndpoint private constructor(val url: String, val token: String) {
    val isComplete: Boolean get() = url.isNotBlank() && token.isNotBlank()

    companion object {
        fun of(url: String, token: String) = SyncEndpoint(url.trim().trimEnd('/'), token.trim())
    }
}
