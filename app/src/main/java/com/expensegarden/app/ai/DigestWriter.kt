package com.expensegarden.app.ai

import com.expensegarden.app.game.DigestKind
import com.expensegarden.app.game.DigestReason
import com.expensegarden.app.game.Tone
import com.expensegarden.app.game.Trigger

/** Turns a DigestReason into words (spec §3). It does NOT store them.
 *
 *  Everything that decides WHETHER to speak lives in DigestTrigger; this only decides how it
 *  is worded, which is the part that cannot be unit-tested. Every row in a job shares one
 *  watermark, so the job composes all of its reasons first and writes them in a single
 *  transaction, or writes none (spec §9) — a null here means the whole verdict is retried
 *  on the next open. */
class DigestWriter(private val llm: LlmClient) {

    /** Words for one reason, or null if the model did not answer. */
    suspend fun compose(reason: DigestReason, facts: PromptFacts, tone: Tone): String? =
        llm.complete(prompt(reason, facts, tone))?.let(::tidy)

    private fun prompt(reason: DigestReason, facts: PromptFacts, tone: Tone): String = buildString {
        appendLine(Persona.systemPrompt(tone))
        appendLine()
        appendLine(facts.render())
        appendLine()
        appendLine("What changed since you last spoke:")
        reason.triggers.forEach { appendLine("- ${describe(it)}") }
        appendLine()
        appendLine(
            when (reason.kind) {
                DigestKind.DAILY ->
                    "Write two or three sentences about that. Speak directly to them. " +
                        "No greeting, no sign-off, no bullet points."
                DigestKind.MONTHLY ->
                    "That month has just closed. Write three or four sentences looking back " +
                        "on it. Speak directly to them. No greeting, no sign-off, no bullets."
            }
        )
    }.trim()

    /** Plain-English trigger descriptions. Kept here rather than on Trigger itself so the
     *  game package stays free of prompt-shaped strings. */
    private fun describe(t: Trigger): String = when (t) {
        // Spec §5: this is calendar-driven and can only ever be an improvement, because
        // BREACH has no day term. It must read as "a quiet day pulled you back under pace",
        // never as "you spent differently".
        is Trigger.WeatherChanged ->
            "The garden's weather moved from ${t.from} to ${t.to}. If it improved, that may " +
                "simply be a quiet day letting the month's pace catch up — say so plainly."
        is Trigger.HouseLevelled ->
            "The homestead grew from level ${t.from} to level ${t.to}. This is earned by " +
                "months tracked, not by spending. Congratulate it."
        is Trigger.StreakHit ->
            "They hit a ${t.days}-day streak without overspending. This is a win."
        Trigger.FirstRegretOfMonth ->
            "They tagged their first purchase of the month as a regret. They volunteered " +
                "that. Be kind about the honesty; never punish the log."
        is Trigger.GateDodged ->
            "They backed out at the payment gate ${t.count} time(s). This is a WIN — they " +
                "chose not to spend. Celebrate it."
        is Trigger.MonthClosed ->
            "The month ${t.monthKey} has closed and been archived to the greenhouse."
    }

    /** The model sometimes wraps prose in quotes or a markdown block. Strip, then cap. */
    private fun tidy(raw: String): String =
        raw.trim().trim('"', '“', '”').removePrefix("```").removeSuffix("```").trim()
            .take(MAX_LEN)

    private companion object { const val MAX_LEN = 600 }
}
