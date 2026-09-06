package com.expensegarden.app.stats

import com.expensegarden.app.data.TransactionEntity
import com.expensegarden.app.data.TxnSource
import com.expensegarden.app.data.TxnStatus
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

class RecurringPayeesTest {
    private val zone: ZoneId = ZoneId.of("Asia/Kolkata")

    private fun at(y: Int, m: Int, d: Int) =
        LocalDate.of(y, m, d).atStartOfDay(zone).toInstant().toEpochMilli()

    private fun txn(payeeId: Long, y: Int, m: Int, d: Int, amountPaise: Long = 1000) =
        TransactionEntity(
            uuid = "$payeeId-$y-$m-$d-$amountPaise", amountPaise = amountPaise, payeeId = payeeId,
            categoryId = 1, source = TxnSource.MANUAL, status = TxnStatus.LOGGED,
            breachedAtLogging = false, note = null,
            occurredAt = at(y, m, d), createdAt = at(y, m, d), updatedAt = 1L,
        )

    private fun detect(txns: List<TransactionEntity>, current: YearMonth = YearMonth.of(2026, 9)) =
        RecurringPayees.detect(txns, current, zone)

    @Test fun `a payee billed once a month in every look-back month is recurring`() {
        assertEquals(setOf(1L), detect(listOf(txn(1, 2026, 7, 1), txn(1, 2026, 8, 1))))
    }

    @Test fun `a payee used many times a month is not recurring`() {
        val many = (1..6).map { txn(2, 2026, 7, it) } + (1..7).map { txn(2, 2026, 8, it) }
        assertEquals(emptySet<Long>(), detect(many))
    }

    @Test fun `a payee missing from one look-back month is not recurring`() {
        // Rent you skipped, or a subscription started last month. Not yet a reliable fixed cost.
        // The other payee is load-bearing: a month counts as a look-back month only if the
        // dataset has data in it, so July has to EXIST before absence from it means anything.
        // Without it this is just a one-month-of-history dataset — see the test below.
        assertEquals(
            emptySet<Long>(),
            detect(listOf(txn(99, 2026, 7, 5), txn(99, 2026, 8, 5), txn(3, 2026, 8, 1))) - 99L,
        )
    }

    @Test fun `the current month is never examined`() {
        // THE stability rule. This payee looks like one-a-month only if September counts; on
        // the complete months alone it is a heavy user and must stay variable. Without this,
        // a food-delivery payee with one order on the 2nd would be classified fixed, then
        // reclassified as the month filled in, and the projection would lurch.
        val txns = (1..6).map { txn(4, 2026, 7, it) } + (1..6).map { txn(4, 2026, 8, it) } +
            listOf(txn(4, 2026, 9, 2))
        assertEquals(emptySet<Long>(), detect(txns))
    }

    @Test fun `a payee that appears twice in one look-back month is not recurring`() {
        assertEquals(
            emptySet<Long>(),
            detect(listOf(txn(5, 2026, 7, 1), txn(5, 2026, 8, 1), txn(5, 2026, 8, 20))),
        )
    }

    @Test fun `one complete month of history is enough`() {
        // A new install must not wait two months for the projection to become sane. Contrast
        // with the two tests above: there, July exists and the payee is absent from it; here
        // July does not exist at all, so August is the whole look-back window.
        assertEquals(setOf(6L), detect(listOf(txn(6, 2026, 8, 1))))
    }

    @Test fun `no complete months means nothing is recurring`() {
        // Degrades to the plain linear projection, which is the pre-fix behaviour.
        assertEquals(emptySet<Long>(), detect(listOf(txn(7, 2026, 9, 1))))
    }

    @Test fun `only LOGGED transactions count`() {
        // Payee 8's July payment never completed, so July is a month it did not appear in.
        // Payee 99 establishes July as a real look-back month.
        val pending = txn(8, 2026, 7, 1).copy(status = TxnStatus.PENDING_CONFIRM)
        val txns = listOf(pending, txn(8, 2026, 8, 1), txn(99, 2026, 7, 5), txn(99, 2026, 8, 5))
        assertEquals(emptySet<Long>(), detect(txns) - 99L)
    }

    @Test fun `fixed spend sums only this month's recurring payees`() {
        val month = listOf(
            txn(1, 2026, 9, 1, amountPaise = 1_800_000),   // rent, recurring
            txn(2, 2026, 9, 3, amountPaise = 45_000),      // lunch, not
            txn(1, 2026, 9, 9, amountPaise = 500),         // same payee again, still counted
        )
        assertEquals(1_800_500L, RecurringPayees.fixedSpentPaise(month, setOf(1L)))
    }

    @Test fun `fixed spend ignores non-LOGGED rows`() {
        val month = listOf(txn(1, 2026, 9, 1, amountPaise = 1_800_000).copy(status = TxnStatus.DISCARDED))
        assertEquals(0L, RecurringPayees.fixedSpentPaise(month, setOf(1L)))
    }
}
