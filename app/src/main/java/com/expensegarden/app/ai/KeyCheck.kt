package com.expensegarden.app.ai

/** What Settings says about a Gemini key (review R16, 2026-10-18). A bad key used to look exactly
 *  like no network: both were silence. */
enum class KeyCheck {
    WORKS, REJECTED, MODEL_MISSING, UNREACHABLE;

    companion object {
        /** Gemini answers a bad key with 400 API_KEY_INVALID, and a disabled one with 401/403. A 429
         *  means the key was accepted and rate-limited; a 404 means this app's model is gone. */
        fun classify(code: Int?, error: Exception?): KeyCheck = when (code) {
            200, 429 -> WORKS
            400, 401, 403 -> REJECTED
            404 -> MODEL_MISSING
            else -> UNREACHABLE
        }
    }
}
