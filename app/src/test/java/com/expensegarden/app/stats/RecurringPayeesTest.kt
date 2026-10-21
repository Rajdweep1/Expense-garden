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

    private fun detect(
        txns: List<TransactionEntity>,
        current: YearMonth = YearMonth.of(2026, 9),
        ledgerBeganAt: Long? = null,
    ) = RecurringPayees.detect(txns, current, zone, ledgerBeganAt).keys

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

    @Test fun `a small extra payment doesn't stop the regular one being recurring`() {
        // Rent plus a ₹500 top-up in July; rent alone in August.
        val txns = listOf(
            txn(1, 2026, 7, 1, amountPaise = 1_800_000), txn(1, 2026, 7, 15, amountPaise = 50_000),
            txn(1, 2026, 8, 1, amountPaise = 1_800_000),
        )
        assertEquals(setOf(1L), detect(txns))
    }

    @Test fun `the cap is the smaller look-back month's regular payment plus a quarter`() {
        val txns = listOf(
            txn(1, 2026, 7, 1, amountPaise = 30_000), txn(1, 2026, 7, 2, amountPaise = 1_800_000),   // July: ₹300, then ₹18,000 rent
            txn(1, 2026, 8, 1, amountPaise = 2_000_000), txn(1, 2026, 8, 20, amountPaise = 50_000),  // August: rent up to ₹20,000
        )
        assertEquals(mapOf(1L to 2_250_000L), RecurringPayees.detect(txns, YearMonth.of(2026, 9), zone, null))
    }

    @Test fun `a one-off big payment doesn't become next month's allowance`() {
        // ₹300 in November, then a ₹10,000 one-off in December that the cap already called spending:
        // January's cap stays at ₹375, so the same order is spending again.
        val txns = listOf(txn(3, 2026, 11, 5, amountPaise = 30_000), txn(3, 2026, 12, 3, amountPaise = 1_000_000))
        assertEquals(mapOf(3L to 37_500L), RecurringPayees.detect(txns, YearMonth.of(2027, 1), zone, null))
    }

    @Test fun `payments to a recurring payee fill its cap in date order`() {
        val caps = mapOf(1L to 2_250_000L)                     // ₹18,000 rent last month
        val month = listOf(
            txn(1, 2026, 9, 9, amountPaise = 500_000),          // ₹5,000 more: only ₹4,000 fits
            txn(1, 2026, 9, 3, amountPaise = 1_800_000),        // rent
            txn(1, 2026, 9, 1, amountPaise = 50_000),           // ₹500 before rent
            txn(2, 2026, 9, 3, amountPaise = 45_000),           // lunch, not recurring
        )
        assertEquals(2_250_000L, RecurringPayees.fixedSpentPaise(month, caps))
        assertEquals(listOf(50_000L, 1_800_000L, 400_000L), RecurringPayees.fixedShares(month, caps).map { it.amountPaise })
    }

    @Test fun `the fill is the same whatever order the rows arrive in`() {
        // Same day, so the uuid breaks the tie.
        val small = txn(1, 2026, 9, 1, amountPaise = 500)
        val rent = txn(1, 2026, 9, 1, amountPaise = 1_800_000)
        val caps = mapOf(1L to 1_000_000L)
        val expected = listOf(rent.copy(amountPaise = 1_000_000))
        assertEquals(expected, RecurringPayees.fixedShares(listOf(small, rent), caps))
        assertEquals(expected, RecurringPayees.fixedShares(listOf(rent, small), caps))
    }

    @Test fun `a small payment before the regular one leaves the regular one fixed`() {
        val month = listOf(txn(1, 2026, 9, 9, amountPaise = 1_800_000), txn(1, 2026, 9, 1, amountPaise = 50_000))
        assertEquals(1_850_000L, RecurringPayees.fixedSpentPaise(month, mapOf(1L to 2_250_000L)))
    }

    @Test fun `a big order to a payee with a small regular payment is mostly spending`() {
        // ₹300 once last month made the shop "recurring"; this month only ₹375 of a ₹10,000 order is fixed.
        assertEquals(37_500L, RecurringPayees.fixedSpentPaise(listOf(txn(3, 2026, 9, 3, amountPaise = 1_000_000)), mapOf(3L to 37_500L)))
    }

    @Test fun `a new payment takes what is left of its payee's cap, as the fill gives it once logged`() {
        // The gate's share must equal what logging the payment adds to the month's fixed spend, or
        // the gate's line disagrees with the strip's a second later.
        val caps = mapOf(1L to 125_000L)                       // ₹1,000 last month
        fun share(paidSoFar: List<Long>, amount: Long): Long {
            val month = paidSoFar.mapIndexed { i, paise -> txn(1, 2026, 9, i + 1, amountPaise = paise) }
            val logged = RecurringPayees.fixedSpentPaise(month + txn(1, 2026, 9, 20, amountPaise = amount), caps) -
                RecurringPayees.fixedSpentPaise(month, caps)
            val atGate = RecurringPayees.newPaymentShare(month, caps, payeeId = 1L, amountPaise = amount)
            assertEquals(logged, atGate)
            return atGate
        }
        assertEquals(100_000L, share(emptyList(), 100_000))       // nothing paid yet
        assertEquals(25_000L, share(listOf(100_000), 200_000))      // ₹250 of the cap left
        assertEquals(0L, share(listOf(200_000), 100_000))           // already past the cap
        assertEquals(5_000L, share(listOf(50_000), 5_000))          // a small payment with room to spare
    }

    @Test fun `a payment to a payee without a cap has no fixed share`() {
        assertEquals(0L, RecurringPayees.newPaymentShare(emptyList(), mapOf(1L to 125_000L), payeeId = 2L, amountPaise = 100_000))
        assertEquals(0L, RecurringPayees.newPaymentShare(emptyList(), mapOf(1L to 125_000L), payeeId = null, amountPaise = 100_000))
    }

    @Test fun `fixed spend ignores non-LOGGED rows`() {
        val month = listOf(txn(1, 2026, 9, 1, amountPaise = 1_800_000).copy(status = TxnStatus.DISCARDED))
        assertEquals(0L, RecurringPayees.fixedSpentPaise(month, mapOf(1L to 2_250_000L)))
    }

    // ---- a ledger that wasn't watching (review fix, 2026-10-11) ----
    // One dinner in a partly observed October must not make the restaurant "rent" in November.

    private val dinnerInOctober = listOf(txn(7, 2026, 10, 24, amountPaise = 150_000))
    private val november = YearMonth.of(2026, 11)

    @Test fun `a month the ledger only partly watched is not a look-back month`() =
        assertEquals(emptySet<Long>(), detect(dinnerInOctober, november, ledgerBeganAt = at(2026, 10, 20)))

    @Test fun `a ledger that began on the 1st watched the whole month`() =
        assertEquals(setOf(7L), detect(dinnerInOctober, november, ledgerBeganAt = at(2026, 10, 1)))

    @Test fun `a ledger that began earlier counts the month in full`() =
        assertEquals(setOf(7L), detect(dinnerInOctober, november, ledgerBeganAt = at(2026, 9, 28)))

    @Test fun `an unknown beginning keeps every month with data`() =
        assertEquals(setOf(7L), detect(dinnerInOctober, november, ledgerBeganAt = null))

    // ---- early payments across a month boundary (final review I3, 2026-10-20) ----

    @Test fun `November's rent paid on 31 October keeps the landlord fixed through January`() {
        // Calendar months gave October two rents and November none. The ₹100 on 15 Nov gives November
        // data, so the landlord's absence from it counts.
        val rent = 1_800_000L
        val txns = listOf(
            txn(1, 2026, 9, 1, rent), txn(1, 2026, 10, 1, rent), txn(1, 2026, 10, 31, rent), txn(1, 2026, 12, 1, rent),
            txn(2, 2026, 11, 15, 10_000),
        )
        for (current in listOf(YearMonth.of(2026, 11), YearMonth.of(2026, 12), YearMonth.of(2027, 1))) {
            assertEquals("$current", mapOf(1L to 2_250_000L), RecurringPayees.detect(txns, current, zone, null))
        }
    }

    @Test fun `a payment counts toward the next month from the 25th, not the 24th`() {
        val rentOnThe1st = listOf(txn(1, 2026, 9, 1, 1_800_000), txn(1, 2026, 10, 1, 1_800_000))
        assertEquals(setOf(1L), detect(rentOnThe1st + txn(1, 2026, 10, 25, 1_800_000), november))        // November's rent, early
        assertEquals(emptySet<Long>(), detect(rentOnThe1st + txn(1, 2026, 10, 24, 1_800_000), november))  // a second October rent
    }

    @Test fun `a payee paid on the 24th every month is recurring`() {
        // Seven days before 1 Oct is 24 Sep: a fixed shift would put both payments in October.
        assertEquals(setOf(1L), detect(listOf(txn(1, 2026, 9, 24), txn(1, 2026, 10, 24)), november))
    }

    @Test fun `a ledger begun after the 25th still watched the next month`() {
        // Its first days count toward November, but a month is watched from its calendar 1st.
        assertEquals(
            setOf(1L),
            detect(listOf(txn(1, 2026, 10, 31, 1_800_000)), YearMonth.of(2026, 12), ledgerBeganAt = at(2026, 10, 28)),
        )
    }

    @Test fun `the look-back window starts on the 25th before its first month`() {
        // January looks back to November and December, and November's detection month begins on 25 Oct.
        assertEquals(at(2026, 10, 25), RecurringPayees.windowStart(YearMonth.of(2027, 1), zone))
    }
}
