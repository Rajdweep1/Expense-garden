package com.expensegarden.app.ui

import com.expensegarden.app.gate.Severity

/** The pace verdict, or nothing when there is no budget to be on pace against. The dashboard used
 *  to say "on pace" with no budget at all (review, 2026-10-11). */
internal fun paceLine(severity: Severity, overallBudgetPaise: Long?): String? =
    if (overallBudgetPaise == null) null else when (severity) {
        Severity.OK -> "on pace"
        Severity.PACE_WARNING -> "ahead of pace"
        Severity.BREACH -> "over budget"
    }
