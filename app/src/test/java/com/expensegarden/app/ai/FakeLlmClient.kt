package com.expensegarden.app.ai

/** Records prompts and replays canned responses, so the refresher and the writer can be
 *  tested with zero network. `responses` is consumed in order; once exhausted it returns
 *  null, which exercises the failure path for free. */
class FakeLlmClient(private val responses: MutableList<String?> = mutableListOf()) : LlmClient {
    val prompts = mutableListOf<String>()

    fun enqueue(vararg replies: String?) { responses.addAll(replies) }

    override suspend fun complete(prompt: String): String? {
        prompts += prompt
        return if (responses.isEmpty()) null else responses.removeAt(0)
    }
}
