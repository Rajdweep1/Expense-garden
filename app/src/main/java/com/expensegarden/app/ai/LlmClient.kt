package com.expensegarden.app.ai

/** The single seam between this app and any LLM provider (spec §3, §11).
 *
 *  `complete` returns null on EVERY failure — no key, no network, non-200, malformed body,
 *  timeout. Callers therefore cannot distinguish failure modes, which is deliberate: there
 *  is no error state to render because no screen ever waits on this. Swapping Gemini for
 *  Groq, OpenRouter or Ollama (parent spec §8.2) is a new implementation and nothing else. */
interface LlmClient {
    suspend fun complete(prompt: String): String?
}

/** Used whenever no API key has been entered. The app is fully functional against this. */
object NoopLlmClient : LlmClient {
    override suspend fun complete(prompt: String): String? = null
}
