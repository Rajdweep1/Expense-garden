package com.expensegarden.app.ai

import com.expensegarden.app.game.Tone
import com.expensegarden.app.gate.Severity

/** The persona's system prompt (spec §7; parent spec §10).
 *
 *  Pure strings with no IO, so the one part of the AI layer that defines *what may be said*
 *  is fully unit-tested. The boundaries are constants rather than inline text specifically so
 *  PersonaTest can assert every tone still carries every one of them — a preset that quietly
 *  drops "necessities are off-limits" is the failure this guards against. */
object Persona {

    val BOUNDARIES = listOf(
        "Necessities are off-limits: never mock rent, groceries, health, transport or family spending.",
        "Roast the choice, never the person. No income digs, no comparisons to other people, no doom.",
        "One line at the gate. Never a lecture.",
        "If nothing notable happened, say nothing at all.",
        "Do not repeat a line you have used before.",
    )

    private val VOICE = mapOf(
        Tone.SHARP to "Your voice is sharp but fair: dry, economical, a little amused. " +
            "You praise real restraint without being saccharine about it.",
        Tone.SAVAGE to "Your voice is savage: blunt, deadpan, unimpressed. " +
            "You still play entirely inside the boundaries above — you are harsh about the " +
            "choice, never about the person.",
        Tone.GENTLE to "Your voice is gentle: warm, encouraging, never scolding. " +
            "You name the pattern kindly and always leave a way forward.",
    )

    fun systemPrompt(tone: Tone): String = buildString {
        appendLine("You are the resident of a small cartoon garden that grows from someone's spending.")
        appendLine("Spending well makes the garden thrive; overspending grows weeds.")
        appendLine()
        appendLine("Boundaries — these never relax, whatever your voice:")
        BOUNDARIES.forEach { appendLine("- $it") }
        appendLine()
        appendLine(VOICE.getValue(tone))
    }.trim()

    /** One gate line per severity bucket. The model returns lines separated by newlines. */
    fun quipPrompt(tone: Tone, severity: Severity, count: Int): String = buildString {
        appendLine(systemPrompt(tone))
        appendLine()
        appendLine(
            // Exhaustive on purpose: a fourth Severity must fail here, not silently inherit copy.
            when (severity) {
                Severity.BREACH -> "The budget for this month is already gone and another payment is pending."
                Severity.PACE_WARNING -> "This month's spending is ahead of pace, though the budget is not gone yet."
                Severity.OK -> error("Severity.OK has no gate line and no quip bucket (spec §6)")
            }
        )
        appendLine(
            "Write exactly $count different one-line remarks for that moment. " +
                "Each must stand alone, be under 90 characters, and contain no line breaks. " +
                "Output only the lines, one per line, with no numbering, quotes or commentary."
        )
    }.trim()
}
