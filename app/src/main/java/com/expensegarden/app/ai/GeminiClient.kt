package com.expensegarden.app.ai

import com.expensegarden.app.data.AiPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL

/** The only file in the app that touches the network (spec §3).
 *
 *  Deliberately the thinnest file in the layer, because it is the only one that cannot be
 *  unit-tested offline — everything worth testing lives outside it. No Retrofit, no OkHttp,
 *  no kotlinx-serialization: HttpURLConnection and org.json are framework, and the pinned
 *  matrix admits no new dependencies (spec §2).
 *
 *  The `withContext(Dispatchers.IO)` is INSIDE complete(), not at the call sites. The
 *  background job runs on `viewModelScope`, which is Dispatchers.Main — an HttpURLConnection
 *  there throws NetworkOnMainThreadException. Putting the switch at the lowest level means no
 *  future caller can get it wrong (spec §3). */
class GeminiClient(private val aiPrefs: AiPrefs) : LlmClient {

    override suspend fun complete(prompt: String): String? = withContext(Dispatchers.IO) {
        val key = aiPrefs.apiKey
        if (key.isBlank()) return@withContext null

        var conn: HttpURLConnection? = null
        try {
            conn = (URL("$ENDPOINT?key=$key").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json")
                doOutput = true
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
            }
            conn.outputStream.use { it.write(requestBody(prompt).toString().toByteArray()) }

            if (conn.responseCode != HttpURLConnection.HTTP_OK) return@withContext null
            val body = conn.inputStream.bufferedReader().use(BufferedReader::readText)
            firstCandidateText(body)
        } catch (e: Exception) {
            // Every failure is silence (spec §11): no key, no network, DNS, timeout, 429, 500,
            // malformed JSON. There is no error state to render because nothing waits on this.
            null
        } finally {
            conn?.disconnect()
        }
    }

    private fun requestBody(prompt: String): JSONObject =
        JSONObject().put(
            "contents",
            org.json.JSONArray().put(
                JSONObject().put(
                    "parts",
                    org.json.JSONArray().put(JSONObject().put("text", prompt)),
                ),
            ),
        )

    /** Gemini nests the answer at candidates[0].content.parts[0].text. Any shape we don't
     *  recognise (including a safety block, which omits `content` entirely) is silence. */
    private fun firstCandidateText(body: String): String? = runCatching {
        JSONObject(body)
            .getJSONArray("candidates").getJSONObject(0)
            .getJSONObject("content")
            .getJSONArray("parts").getJSONObject(0)
            .getString("text")
            .trim()
            .ifBlank { null }
    }.getOrNull()

    private companion object {
        /** Verified against this key on 2026-09-05 (Task 15). `gemini-2.0-flash` and even
         *  `gemini-2.5-flash` now 404 with "no longer available to new users"; the API's own
         *  error names this as the replacement. Pinned rather than `gemini-flash-latest`, for
         *  the same reason `libs.versions.toml` is pinned: the persona's voice should not
         *  change under us without a commit saying so. */
        const val MODEL = "gemini-3.6-flash"
        const val ENDPOINT =
            "https://generativelanguage.googleapis.com/v1beta/models/$MODEL:generateContent"

        /** Establishing the connection should be quick; a slow handshake means no network. */
        const val CONNECT_TIMEOUT_MS = 15_000

        /** Generous on purpose. 3.x models think before answering — a measured 22.7s for an
         *  eight-line quip prompt, against the 15s this used to allow, which turned every call
         *  into a timeout and every timeout into silence. Waiting costs nothing here because
         *  §1 keeps the LLM out of every read path: no screen blocks on this, so the only
         *  thing a long read timeout delays is a background write. Preferred over a
         *  thinking-budget knob, whose shape is not stable across model generations
         *  (`thinkingConfig.thinkingBudget: 0` is a 400 on this model). */
        const val READ_TIMEOUT_MS = 60_000
    }
}
