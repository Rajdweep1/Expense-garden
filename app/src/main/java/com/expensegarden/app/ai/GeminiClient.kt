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
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
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
        const val MODEL = "gemini-2.0-flash"
        const val ENDPOINT =
            "https://generativelanguage.googleapis.com/v1beta/models/$MODEL:generateContent"
        const val TIMEOUT_MS = 15_000
    }
}
