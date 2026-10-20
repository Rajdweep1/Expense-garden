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
 *  The rule is deliberately simple: a payee is recurring when, in each complete look-back month,
 *  one payment carries at least four fifths of everything paid to it. Rent with a small top-up
 *  still counts; two comparable orders do not. (It used to be exactly one payment, so a ₹500
 *  top-up to the landlord dropped rent for two months — review, 2026-10-20.) `isNecessity` is
 *  wrong in both directions: Groceries is a necessity bought weekly, and Netflix is not a
 *  necessity at all. Amounts never decide WHETHER a payee is recurring — an amount threshold
 *  would classify a one-off impulse buy as fixed, which is precisely the spend the gate exists to
 *  notice. They only cap HOW MUCH of a month's spend with a recurring payee counts as fixed: its
 *  latest regular payment plus a quarter ([fixedShares]).
 *
 *  COMPLETE MONTHS ONLY, and that is the load-bearing part. Were the current month included, a
 *  payee's status would flip mid-month: on the 2nd a food-delivery payee has a single order
 *  and looks recurring, so its spend would leave the extrapolation, then rejoin it as the
 *  month fills in. Judging on finished months alone keeps the answer constant for the whole
 *  month it is used in.
 *
 *  A false positive costs little. A genuinely one-off payment is added once rather than
 *  extrapolated, which is arguably the more honest treatment of a one-off anyway, and the cap
 *  holds what passes as fixed to about last month's payment. */
object RecurringPayees {

    /** How many complete months back to look. Two is enough to tell rent from lunch, and short
     *  enough that a cancelled subscription stops counting quickly. */
    const val LOOK_BACK_MONTHS = 2

    /**
     * @param txns any superset of the look-back window; non-LOGGED rows are ignored.
     * @param ledgerBeganAt when the ledger began (StreakBaseline.ledgerBeganAt); null = unknown,
     *   which keeps every month with data eligible. Required rather than defaulted: four callers
     *   compute fixed spend, and a default would let one quietly skip the floor.
     * @return payee id → its monthly cap in paise: its regular payment in the latest complete
     *   look-back month plus a quarter. Empty when there is no complete month of history yet, which
     *   degrades to the plain linear projection.
     */
    fun detect(
        txns: List<TransactionEntity>,
        currentMonth: YearMonth,
        zone: ZoneId,
        ledgerBeganAt: Long?,
    ): Map<Long, Long> {
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
        if (lookBack.isEmpty()) return emptyMap()

        // A payee's regular payment is its largest in the month, and it must carry at least four
        // fifths of everything paid to the payee that month.
        val regularByMonth = lookBack.map { month ->
            byMonth.getValue(month).groupBy { it.payeeId }
                .filterValues { paid -> paid.maxOf { it.amountPaise } * 5 >= paid.sumOf { it.amountPaise } * 4 }
                .mapValues { (_, paid) -> paid.maxOf { it.amountPaise } }
        }
        val recurring = regularByMonth.map { it.keys }.reduce { acc, ids -> acc intersect ids }
        // lookBack runs newest first, so the latest month sets the cap: a quarter's slack absorbs a
        // bill that drifts or a rent hike.
        return recurring.associateWith { regularByMonth.first().getValue(it) * 5 / 4 }
    }

    /** Day granularity, as in StreakBaseline: a ledger that began on the 1st watched the month. */
    private fun observedInFull(month: YearMonth, ledgerBeganAt: Long?, zone: ZoneId): Boolean =
        ledgerBeganAt == null ||
            !Instant.ofEpochMilli(ledgerBeganAt).atZone(zone).toLocalDate().isAfter(month.atDay(1))

    /** This month's fixed spend: what each recurring payee was paid this month, up to its cap
     *  ([fixedShares]).
     *
     *  @param monthTxns the current month's transactions only — the caller slices the window,
     *    because [detect] needs a wider one and passing the same list to both would count
     *    previous months' rent as spent this month. */
    fun fixedSpentPaise(monthTxns: List<TransactionEntity>, caps: Map<Long, Long>): Long =
        fixedShares(monthTxns, caps).sumOf { it.amountPaise }

    /** Each LOGGED payment's fixed share. Payments to a recurring payee fill its cap in
     *  (`occurredAt`, `uuid`) order, the same whatever order a caller's rows arrive in, and anything
     *  past the cap is spending: a ₹500 top-up before rent leaves the rent fixed, and a ₹10,000
     *  order to a shop paid ₹300 last month is mostly spending (review, 2026-10-20).
     *  Returns copies of the rows carrying only their share, for folds and sums: never write one
     *  back. */
    /** The fixed share a new payment takes: what is left of its payee's cap after the month's
     *  LOGGED payments, never more than the payment. It is what [fixedShares] adds once the payment
     *  is logged after them, so the gate's line and the strip's agree (final review, 2026-10-20). */
    fun newPaymentShare(monthTxns: List<TransactionEntity>, caps: Map<Long, Long>, payeeId: Long?, amountPaise: Long): Long {
        val cap = payeeId?.let { caps[it] } ?: return 0L
        val paid = monthTxns.filter { it.status == TxnStatus.LOGGED && it.payeeId == payeeId }.sumOf { it.amountPaise }
        return minOf(amountPaise, (cap - paid).coerceAtLeast(0L))
    }

    fun fixedShares(monthTxns: List<TransactionEntity>, caps: Map<Long, Long>): List<TransactionEntity> {
        val left = caps.toMutableMap()
        return monthTxns
            .filter { it.status == TxnStatus.LOGGED && it.payeeId in caps }
            .sortedWith(compareBy({ it.occurredAt }, { it.uuid }))
            .mapNotNull { txn ->
                val share = minOf(txn.amountPaise, left.getValue(txn.payeeId))
                left[txn.payeeId] = left.getValue(txn.payeeId) - share
                if (share > 0) txn.copy(amountPaise = share) else null
            }
    }
}
