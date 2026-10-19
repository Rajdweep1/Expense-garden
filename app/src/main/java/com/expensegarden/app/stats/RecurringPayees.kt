package com.expensegarden.app.stats

import com.expensegarden.app.data.TransactionEntity
import com.expensegarden.app.data.TxnStatus
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId

/** Which payees are fixed monthly costs — rent, utilities, subscriptions, the SIP.
 *
 *  This is the "recurring detection" the parent spec listed as a stats capability and which
 *  [PaceProjector] was built as though it already had. Without it, linear extrapolation
 *  multiplies a day-1 rent payment by the length of the month. Measured on three months of
 *  real spending: a projected month-end of ₹1,65,305 on day 6, against actual months of
 *  ₹43–49k — and, worse, a pace line the user sat above from day 1 to day 22, so the payment
 *  gate raised a dialog on essentially every purchase.
 *
 *  The rule is deliberately blunt — exactly one LOGGED transaction in each complete look-back
 *  month — because the alternatives are worse. `isNecessity` is wrong in both directions:
 *  Groceries is a necessity bought weekly, and Netflix is not a necessity at all. An amount
 *  threshold would classify a one-off impulse buy as fixed, which is precisely the spend the
 *  gate exists to notice.
 *
 *  COMPLETE MONTHS ONLY, and that is the load-bearing part. Were the current month included, a
 *  payee's status would flip mid-month: on the 2nd a food-delivery payee has a single order
 *  and looks recurring, so its spend would leave the extrapolation, then rejoin it as the
 *  month fills in. Judging on finished months alone keeps the answer constant for the whole
 *  month it is used in.
 *
 *  A false positive costs little. A genuinely one-off payment is added once rather than
 *  extrapolated, which is arguably the more honest treatment of a one-off anyway. */
object RecurringPayees {

    /** How many complete months back to look. Two is enough to tell rent from lunch, and short
     *  enough that a cancelled subscription stops counting quickly. */
    const val LOOK_BACK_MONTHS = 2

    /**
     * @param txns any superset of the look-back window; non-LOGGED rows are ignored.
     * @param ledgerBeganAt when the ledger began (StreakBaseline.ledgerBeganAt); null = unknown,
     *   which keeps every month with data eligible. Required rather than defaulted: four callers
     *   compute fixed spend, and a default would let one quietly skip the floor.
     * @return payee ids that behave like a fixed monthly cost. Empty when there is no complete
     *   month of history yet, which degrades to the plain linear projection.
     */
    fun detect(
        txns: List<TransactionEntity>,
        currentMonth: YearMonth,
        zone: ZoneId,
        ledgerBeganAt: Long?,
    ): Set<Long> {
        val byMonth = txns.asSequence()
            .filter { it.status == TxnStatus.LOGGED }
            .groupBy { YearMonth.from(Instant.ofEpochMilli(it.occurredAt).atZone(zone)) }

        // A month counts as a look-back month only if the dataset has data in it AND the ledger
        // watched all of it.
        //
        // The first half is what lets a fresh install with one month of history get detection
        // instead of waiting for a second. It means "absent from a look-back month" only
        // disqualifies a payee when some OTHER payee proves that month existed. The two cases
        // look identical from one payee's rows alone, which is worth knowing before changing this.
        //
        // The second half is the 2026-10-11 fix. A fresh install's partial first month makes every
        // payee seen once look like rent, so next month's spend with them passed straight through
        // the pace line: PACE_WARNING became OK (GateScopeTest pins it). StreakBaseline fixed the
        // same mistake for streaks.
        val lookBack = (1..LOOK_BACK_MONTHS)
            .map { currentMonth.minusMonths(it.toLong()) }
            .filter { byMonth.containsKey(it) && observedInFull(it, ledgerBeganAt, zone) }
        if (lookBack.isEmpty()) return emptySet()

        return lookBack
            .map { month -> byMonth.getValue(month).groupingBy { it.payeeId }.eachCount() }
            .map { counts -> counts.filterValues { it == 1 }.keys }
            .reduce { acc, ids -> acc intersect ids }
    }

    /** Day granularity, as in StreakBaseline: a ledger that began on the 1st watched the month. */
    private fun observedInFull(month: YearMonth, ledgerBeganAt: Long?, zone: ZoneId): Boolean =
        ledgerBeganAt == null ||
            !Instant.ofEpochMilli(ledgerBeganAt).atZone(zone).toLocalDate().isAfter(month.atDay(1))

    /** This month's spend attributable to fixed costs: each recurring payee's first payment of the
     *  month ([fixedPayments]).
     *
     *  @param monthTxns the current month's transactions only — the caller slices the window,
     *    because [detect] needs a wider one and passing the same list to both would count
     *    previous months' rent as spent this month. */
    fun fixedSpentPaise(monthTxns: List<TransactionEntity>, recurring: Set<Long>): Long =
        fixedPayments(monthTxns, recurring).sumOf { it.amountPaise }

    /** The month's fixed-cost payments: each recurring payee's first LOGGED payment, by
     *  `occurredAt` then `uuid`, so every caller picks the same row. A fixed cost is paid once a
     *  month, which is how [detect] recognised it, so a second payment to the same payee is
     *  spending: counting it too let a marketplace misread as recurring take every order past the
     *  pace line (review, 2026-10-19). */
    fun fixedPayments(monthTxns: List<TransactionEntity>, recurring: Set<Long>): List<TransactionEntity> =
        monthTxns.asSequence()
            .filter { it.status == TxnStatus.LOGGED && it.payeeId in recurring }
            .sortedWith(compareBy({ it.occurredAt }, { it.uuid }))
            .distinctBy { it.payeeId }
            .toList()
}
