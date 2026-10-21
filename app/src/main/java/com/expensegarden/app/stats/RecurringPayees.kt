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
 *  necessity at all. No amount THRESHOLD decides whether a payee is recurring — one would
 *  classify a one-off impulse buy as fixed, which is precisely the spend the gate exists to
 *  notice; a payee's payments are only compared with each other. Amounts also cap HOW MUCH of a
 *  month's spend with a recurring payee counts as fixed: the smaller of its regular payments in the
 *  look-back months, plus a quarter ([fixedShares]).
 *
 *  COMPLETE MONTHS ONLY, and that is the load-bearing part. Were the current month included, a
 *  payee's status would flip mid-month: on the 2nd a food-delivery payee has a single order
 *  and looks recurring, so its spend would leave the extrapolation, then rejoin it as the
 *  month fills in. Judging on finished months alone keeps the answer constant for the whole
 *  month it is used in.
 *
 *  Months here run from the 25th to the 24th: a payment from the 25th on counts toward the next
 *  month, so November's rent paid on 31 October is November's. On calendar months October held two
 *  rents and November none, and the landlord dropped out of fixed costs for three months (final
 *  review, 2026-10-20). The edge is a day of the month, not a number of days before the 1st,
 *  because months differ in length: seven days before 1 October is 24 September, which would count
 *  a payee paid on the 24th twice in October and never in September. Only detection shifts;
 *  spending is still counted by calendar month.
 *
 *  The edge moved rather than vanished. A payee whose payment day crosses it drops out the way rent
 *  did, such as a bill usually paid on the 27th that once goes out on the 23rd, and a payee paid
 *  from the 25th on is detected a month later. That costs less here than on the 1st: from about the
 *  27th the pace line is at or above the budget anyway, so being fixed matters for days, not weeks.
 *
 *  A false positive costs little. A genuinely one-off payment is added once rather than
 *  extrapolated, which is arguably the more honest treatment of a one-off anyway, and the cap
 *  holds what passes as fixed to about last month's payment. */
object RecurringPayees {

    /** How many complete months back to look. Two is enough to tell rent from lunch, and short
     *  enough that a cancelled subscription stops counting quickly. */
    private const val LOOK_BACK_MONTHS = 2

    /** A detection month begins on this day of the calendar month before it, so rent paid in the
     *  week before the 1st counts toward the month it is for. */
    private const val NEXT_MONTH_FROM_DAY = 25

    /** Where a query that feeds [detect] must start: the first day of its earliest look-back month,
     *  the 25th of the calendar month before that one. A window from a calendar 1st cut off the rent
     *  paid early for that month, and the landlord dropped out again. The only way in: the constants
     *  are private, so no caller rebuilds the window from whole calendar months. */
    fun windowStart(currentMonth: YearMonth, zone: ZoneId): Long =
        currentMonth.minusMonths(LOOK_BACK_MONTHS + 1L).atDay(NEXT_MONTH_FROM_DAY)
            .atStartOfDay(zone).toInstant().toEpochMilli()

    /**
     * @param txns any superset of the window from [windowStart]; non-LOGGED rows are ignored.
     * @param ledgerBeganAt when the ledger began (StreakBaseline.ledgerBeganAt); null = unknown,
     *   which keeps every month with data eligible. Required rather than defaulted: four callers
     *   compute fixed spend, and a default would let one quietly skip the floor.
     * @return payee id → its monthly cap in paise: the smaller of its regular payments in the
     *   complete look-back months, plus a quarter. Empty when there is no complete month of history
     *   yet, which degrades to the plain linear projection.
     */
    fun detect(
        txns: List<TransactionEntity>,
        currentMonth: YearMonth,
        zone: ZoneId,
        ledgerBeganAt: Long?,
    ): Map<Long, Long> {
        val byMonth = txns.asSequence()
            .filter { it.status == TxnStatus.LOGGED }
            .groupBy { detectionMonth(it.occurredAt, zone) }

        // A month counts as a look-back month only if the dataset has data in it AND the ledger
        // watched it from its calendar 1st (see observedInFull).
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
        // The smaller month sets the cap, so a one-off big payment can't become next month's
        // allowance (final review, 2026-10-20); a quarter's slack absorbs a bill that drifts or a
        // modest rent hike.
        return recurring.associateWith { id -> regularByMonth.minOf { it.getValue(id) } * 5 / 4 }
    }

    private fun detectionMonth(occurredAt: Long, zone: ZoneId): YearMonth {
        val date = Instant.ofEpochMilli(occurredAt).atZone(zone).toLocalDate()
        val month = YearMonth.from(date)
        return if (date.dayOfMonth >= NEXT_MONTH_FROM_DAY) month.plusMonths(1) else month
    }

    /** Day granularity, as in StreakBaseline: a ledger that began on the 1st watched the month.
     *  Keyed to the calendar 1st although a detection month starts on the 25th before it: a ledger
     *  begun in that last week missed at most a few early payments, and requiring the 25th would
     *  cost it its first full month of detection, with rent on the 1st tripping the gate again. */
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

    /** The fixed share a new payment takes: what is left of its payee's cap after the month's
     *  LOGGED payments, never more than the payment. It is what [fixedShares] adds once the payment
     *  is logged after them, so the gate's line and the strip's agree (final review, 2026-10-20). */
    fun newPaymentShare(monthTxns: List<TransactionEntity>, caps: Map<Long, Long>, payeeId: Long?, amountPaise: Long): Long {
        val cap = payeeId?.let { caps[it] } ?: return 0L
        val paid = monthTxns.filter { it.status == TxnStatus.LOGGED && it.payeeId == payeeId }.sumOf { it.amountPaise }
        return minOf(amountPaise, (cap - paid).coerceAtLeast(0L))
    }

    /** Each LOGGED payment's fixed share. Payments to a recurring payee fill its cap in
     *  (`occurredAt`, `uuid`) order, the same whatever order a caller's rows arrive in, and anything
     *  past the cap is spending: a ₹500 top-up before rent leaves the rent fixed, and a ₹10,000
     *  order to a shop paid ₹300 last month is mostly spending (review, 2026-10-20).
     *  Returns copies of the rows carrying only their share, for folds and sums: never write one
     *  back. */
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
