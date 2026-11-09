package com.expensegarden.app.sync

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** What Restore may do, decided before anything is touched (review R5, 2026-10-18).
 *
 *  Restore replaces the phone's ledger wholesale, so the user sees both sides first. The one case
 *  it refuses is an empty server over a phone with payments — pointing the app at a fresh database
 *  and tapping Restore to "test" it would otherwise erase everything. */
sealed interface RestorePlan {
    val message: String

    data class Refuse(override val message: String) : RestorePlan
    data class Confirm(override val message: String) : RestorePlan

    companion object {
        private fun payments(n: Int) = if (n == 1) "1 payment" else "$n payments"

        private val DATE = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)

        fun of(phoneTxns: Int, unsynced: Int, serverTxns: Int, serverLatestAt: Long?, zone: ZoneId): RestorePlan {
            if (serverTxns == 0 && phoneTxns > 0) {
                return Refuse("The server has no payments. Restoring would erase this phone's ${payments(phoneTxns)}, so it won't.")
            }
            val latest = serverLatestAt?.let { ", the latest on " + DATE.format(Instant.ofEpochMilli(it).atZone(zone)) } ?: ""
            val phone = if (phoneTxns == 0) "This phone has none." else "This phone has $phoneTxns"
            val loss = when {
                phoneTxns == 0 -> ""
                unsynced == 1 -> ", and 1 change isn't backed up yet — it will be lost."
                unsynced > 1 -> ", and $unsynced changes aren't backed up yet — they will be lost."
                else -> "."
            }
            return Confirm(
                "The server has ${payments(serverTxns)}$latest. $phone$loss Restoring replaces this phone's ledger and garden."
            )
        }
    }
}
