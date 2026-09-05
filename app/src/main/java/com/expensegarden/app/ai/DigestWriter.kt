package com.expensegarden.app.ai

import com.expensegarden.app.game.DigestKind
import com.expensegarden.app.game.DigestReason
import com.expensegarden.app.game.Tone
import com.expensegarden.app.game.Trigger

/** Turns a DigestReason into words (spec §3). It does NOT store them.
 *
 *  Everything that decides WHETHER to speak lives in DigestTrigger; this only decides how it
 *  is worded. The prompt IS unit-tested (DigestWriterTest); only the model's reply is not. Every row in a job shares one
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
        // Labelled so that, for a MONTHLY reason fed that month's facts, the block reads as
        // context rather than contradicting "the month has just closed" below it.
        appendLine("Where things stand:")
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
        // Spec §5: a CALENDAR-driven flip is always an improvement (BREACH has no day term),
        // but the trigger fires on any change, so only the upward case is hedged. The writer
        // cannot see spend, so it must never claim spending changed.
        is Trigger.WeatherChanged ->
            "The garden's weather moved from ${t.from} to ${t.to}. If it improved, that may " +
                "simply be a quiet day letting the month's pace catch up — say so plainly, and " +
                "never say or imply that they spent differently."
        is Trigger.HouseLevelled ->
            "The homestead grew from level ${t.from} to level ${t.to}. This is earned by " +
                "months tracked, not by spending. Congratulate it."
        is Trigger.StreakHit ->
            "They hit a ${t.days}-day streak without overspending. This is a win."
        Trigger.FirstRegretOfMonth ->
            "For the first time this month, they tagged a purchase as a regret. They " +
                "volunteered that. Be kind about the honesty; never punish the log."
        is Trigger.GateDodged ->
            "They backed out at the payment gate ${t.count} time(s). This is a WIN — they " +
                "chose not to spend. Celebrate it."
        is Trigger.MonthClosed ->
            "The month ${t.monthKey} has closed and been archived to the greenhouse."
    }

    /** The model sometimes wraps prose in quotes or a markdown block. Strip, cap, then apply
     *  the digest-shaped boundary check (spec §11): a person-attack nulls the whole text, and
     *  because the job writes all-or-nothing, nothing lands and it is retried next open. */
    private fun tidy(raw: String): String? =
        raw.trim()
            .replace(FENCE, "")             // fence FIRST — quotes inside a fence must still go
            .trim()
            .trim('"', '“', '”')
            .trim()
            .let(::capAtSentence)
            .takeIf { it.isNotBlank() && !QuipSanitizer.attacksThePerson(it) }

    /** Cut at the last sentence end at or before the cap, else the last space, else hard. A
     *  digest row is written once and never rewritten, so a mid-word cut would be permanent. */
    private fun capAtSentence(s: String): String {
        if (s.length <= MAX_LEN) return s
        val head = s.take(MAX_LEN)
        val end = head.lastIndexOfAny(charArrayOf('.', '!', '?'))
        if (end > MAX_LEN / 2) return head.take(end + 1)
        val space = head.lastIndexOf(' ')
        return if (space > 0) head.take(space) + "…" else head
    }

    private companion object {
        const val MAX_LEN = 600
        /** A leading fence line, with or without a language tag, and a trailing fence line. */
        val FENCE = Regex("^```[a-zA-Z]*[ \\t]*\\n?|\\n?```[ \\t]*$")
    }
}
