package com.expensegarden.app.ui

import com.expensegarden.app.core.Money
import com.expensegarden.app.gate.Severity

/** The pace verdict, or nothing when there is no budget to be on pace against. The dashboard used
 *  to say "on pace" with no budget at all (review, 2026-10-11). */
internal fun paceLine(severity: Severity, overallBudgetPaise: Long?): String? =
    if (overallBudgetPaise == null) null else when (severity) {
        Severity.OK -> "on pace"
        Severity.PACE_WARNING -> "over pace"
        Severity.BREACH -> "over budget"
    }

/** The home strip's label. The streak shows only while the month is on pace: past today's line,
 *  today can't extend it, and a streak beside "over pace" read as good news (review R13). */
internal fun stripLabel(overallBudgetPaise: Long?, hint: Severity, streakDays: Int, unlogged: Int = 0): String {
    val budget = overallBudgetPaise ?: return "set a budget  ›"
    val streak = if (hint == Severity.OK && streakDays > 0) " · 🌱${streakDays}d" else ""
    return "${Money.display(budget)} · ${paceLine(hint, budget)}$streak  ›"
}
