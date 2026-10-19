package com.expensegarden.app.ui

import com.expensegarden.app.core.Money
import com.expensegarden.app.data.TxnRow
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** What the pending card and its Undo bar say. In its own file, like DashboardLabels, so the copy
 *  is JVM-tested. The card used to show only the amount — "Did ₹1 go through?" — so two pending
 *  payments looked identical and a wrong answer was permanent (review, 2026-10-18). */
object PendingLabels {
    private val time = DateTimeFormatter.ofPattern("d MMM, HH:mm", Locale.ENGLISH)

    fun question(row: TxnRow): String = "Did ${Money.display(row.amountPaise)} to ${row.payeeName} go through?"

    /** When the payment was made, and its place in the queue only when there is one. */
    fun detail(row: TxnRow, total: Int, zone: ZoneId): String {
        val at = time.format(Instant.ofEpochMilli(row.occurredAt).atZone(zone))
        return if (total > 1) "$at · 1 of $total" else at
    }

    fun logged(amountPaise: Long, payee: String): String = "Logged ${Money.display(amountPaise)} to $payee"

    fun discarded(amountPaise: Long, payee: String): String = "Discarded ${Money.display(amountPaise)} to $payee"
}
