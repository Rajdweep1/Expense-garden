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
    val waiting = when (unlogged) {
        0 -> ""
        1 -> " · 1 payment not logged"
        else -> " · $unlogged payments not logged"
    }
    val budget = overallBudgetPaise ?: return "set a budget$waiting  ›"
    val streak = if (hint == Severity.OK && streakDays > 0) " · 🌱${streakDays}d" else ""
    return "${Money.display(budget)} · ${paceLine(hint, budget)}$streak$waiting  ›"
}

/** What a day may cost and stay under budget — nothing once the budget is spent, where it used to
 *  read "₹0/day keeps you under" above "over budget" (phone, 2026-11-14). */
internal fun perDayLine(perDayPaise: Long?): String? =
    perDayPaise?.takeIf { it > 0 }?.let { "${Money.display(it)}/day keeps you under" }
