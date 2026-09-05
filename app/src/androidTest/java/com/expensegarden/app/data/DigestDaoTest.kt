package com.expensegarden.app.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.expensegarden.app.game.DigestEvent
import com.expensegarden.app.game.DigestKind
import com.expensegarden.app.game.DigestReason
import com.expensegarden.app.game.DigestSnapshot
import com.expensegarden.app.game.MonthFacts
import com.expensegarden.app.game.Trigger
import com.expensegarden.app.game.Weather
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DigestDaoTest {
    private lateinit var db: AppDatabase
    private lateinit var ledger: LedgerRepository
    private lateinit var repo: DigestRepository

    @Before fun setUp() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).build()
        ledger = LedgerRepository(db)
        repo = DigestRepository(db, ledger)
    }

    @After fun tearDown() = db.close()

    private fun event(type: String, payload: String, at: Long = 1_000L) =
        GameEventEntity(type = type, payloadJson = payload, transactionUuid = null, createdAt = at)

    @Test fun projects_raw_rows_into_typed_events_and_drops_the_irrelevant_ones() = runBlocking {
        db.gameEventDao().insert(event("month.closed", """{"month":"2026-08","spentPaise":100}"""))
        db.gameEventDao().insert(event("streak.hit", """{"month":"2026-09","days":7}"""))
        db.gameEventDao().insert(event("gate.dodged", """{}"""))
        db.gameEventDao().insert(event("transaction.regretted", """{"uuid":"abc"}"""))
        db.gameEventDao().insert(event("transaction.logged", """{"amountPaise":500}"""))

        val w = repo.window(lastDigest = null, todayStartMillis = 0L)

        assertEquals(5L, w.head)                        // the head is the max ROW id, not the max projected id
        assertEquals(4, w.events.size)                  // transaction.logged is not a trigger
        assertEquals("2026-08", (w.events[0] as DigestEvent.MonthClosed).monthKey)
        assertEquals(7, (w.events[1] as DigestEvent.StreakHit).days)
        assertTrue(w.events[2] is DigestEvent.GateDodged)
        assertTrue(w.events[3] is DigestEvent.Regretted)
    }

    @Test fun a_malformed_payload_is_skipped_rather_than_crashing_the_fold() = runBlocking {
        db.gameEventDao().insert(event("month.closed", "not json at all"))
        db.gameEventDao().insert(event("gate.dodged", """{}"""))
        assertEquals(1, repo.window(null, 0L).events.size)
    }

    @Test fun the_first_run_floor_excludes_older_events_and_keeps_one_stamped_exactly_at_midnight() = runBlocking {
        db.gameEventDao().insert(event("gate.dodged", "{}", at = 500L))
        db.gameEventDao().insert(event("gate.dodged", "{}", at = 1_000L))
        db.gameEventDao().insert(event("gate.dodged", "{}", at = 5_000L))
        val w = repo.window(lastDigest = null, todayStartMillis = 1_000L)
        assertEquals(listOf(2L, 3L), w.events.map { it.id })    // >= : a midnight-stamped event is today's
    }

    @Test fun the_window_starts_strictly_after_the_watermark_even_inside_a_same_millisecond_batch() = runBlocking {
        // runReconciler stamps a whole batch with one currentTimeMillis(); only the id can split it.
        repeat(4) { db.gameEventDao().insert(event("gate.dodged", "{}", at = 1_000L)) }
        val last = DigestSnapshot(Weather.SUNNY, 1, 0, lastEventId = 2L)
        val w = repo.window(last, todayStartMillis = 999_999L)  // the floor must NOT apply once a digest exists
        assertEquals(listOf(3L, 4L), w.events.map { it.id })
        assertEquals(4L, w.head)
    }

    @Test fun events_come_back_in_id_order_even_when_timestamps_disagree() = runBlocking {
        // Pins ORDER BY id. A createdAt ordering would return B before A and pass every other test.
        db.gameEventDao().insert(event("gate.dodged", "{}", at = 5_000L))            // id 1, later clock
        db.gameEventDao().insert(event("transaction.regretted", "{}", at = 1_000L))  // id 2, earlier clock
        assertEquals(listOf(1L, 2L), repo.window(null, 0L).events.map { it.id })
    }

    @Test fun month_facts_counts_only_regrets_created_inside_the_month() = runBlocking {
        val (from, to) = ledger.boundsOfMonth("2026-09")
        db.gameEventDao().insert(event("transaction.regretted", "{}", at = from))
        db.gameEventDao().insert(event("transaction.regretted", "{}", at = to))
        db.gameEventDao().insert(event("transaction.regretted", "{}", at = to + 1))   // October
        db.gameEventDao().insert(event("transaction.logged", "{}", at = from))
        assertEquals(MonthFacts("2026-09", regretCount = 2), repo.monthFacts("2026-09"))
    }

    @Test fun write_all_lands_every_row_under_one_shared_watermark() = runBlocking {
        val snapshot = DigestSnapshot(Weather.SUNNY, 1, 3, lastEventId = 42L)
        repo.writeAll(
            listOf(
                DigestReason(DigestKind.MONTHLY, "2026-08", listOf(Trigger.MonthClosed("2026-08"))) to "august",
                DigestReason(DigestKind.DAILY, "2026-09-05", listOf(Trigger.GateDodged(1))) to "today",
            ),
            snapshot, 7_000L,
        )
        assertEquals("august", db.digestDao().monthly("2026-08")?.text)
        assertEquals("today", db.digestDao().byScope("DAILY", "2026-09-05")?.text)
        assertEquals(42L, repo.latestSnapshot()?.lastEventId)
    }

    @Test fun writing_a_digest_round_trips_its_snapshot_and_watermark() = runBlocking {
        val snapshot = DigestSnapshot(Weather.DROUGHT, houseLevel = 3, streakDays = 9, lastEventId = 42L)
        repo.write(
            reason = DigestReason(DigestKind.DAILY, "2026-09-05", listOf(Trigger.GateDodged(2))),
            text = "You backed out. That counts.",
            snapshot = snapshot,
            nowMillis = 7_000L,
        )
        val back = repo.latestSnapshot()
        assertEquals(snapshot, back)
    }

    @Test fun a_second_digest_for_the_same_scope_does_not_overwrite_the_first() = runBlocking {
        val snapshot = DigestSnapshot(Weather.SUNNY, 1, 0, 1L)
        val reason = DigestReason(DigestKind.DAILY, "2026-09-05", listOf(Trigger.GateDodged(1)))
        repo.write(reason, "first", snapshot, 1_000L)
        repo.write(reason, "second", snapshot.copy(lastEventId = 99L), 2_000L)

        assertEquals("first", db.digestDao().byScope("DAILY", "2026-09-05")?.text)
        assertEquals(1L, repo.latestSnapshot()?.lastEventId)
    }

    @Test fun latestSnapshot_is_null_before_anything_has_been_said() = runBlocking {
        assertNull(repo.latestSnapshot())
    }

    /** Regression. Second open of a day whose DAILY was already written, with the reconciler's
     *  month.closed (id 101) and a fresh gate dodge (id 102) both in the window. The MONTHLY
     *  has no conflict of its own and would insert happily, carrying the shared watermark past
     *  the dodge — which the swallowed DAILY never spoke about. The whole job must roll back. */
    @Test fun a_conflicting_scope_rolls_the_whole_job_back_instead_of_advancing_the_watermark() = runBlocking {
        val morning = DigestReason(DigestKind.DAILY, "2026-10-01", listOf(Trigger.GateDodged(1)))
        assertTrue(repo.writeAll(listOf(morning to "morning card"), snapshotAt(100L), 1_000L))
        assertEquals(100L, repo.latestSnapshot()?.lastEventId)

        val monthly = DigestReason(DigestKind.MONTHLY, "2026-09", listOf(Trigger.MonthClosed("2026-09")))
        val afternoon = DigestReason(DigestKind.DAILY, "2026-10-01", listOf(Trigger.GateDodged(1)))
        assertFalse(
            repo.writeAll(
                listOf(monthly to "september recap", afternoon to "afternoon card"),
                snapshotAt(102L), 2_000L,
            )
        )

        // Nothing landed — not even the monthly, which had no conflict of its own.
        assertNull(db.digestDao().byScope("MONTHLY", "2026-09"))
        assertEquals("morning card", db.digestDao().byScope("DAILY", "2026-10-01")?.text)
        // The watermark still sits below the dodge at 102, so it is spoken next open.
        assertEquals(100L, repo.latestSnapshot()?.lastEventId)
    }

    @Test fun exists_reports_only_scopes_that_have_actually_been_written() = runBlocking {
        val daily = DigestReason(DigestKind.DAILY, "2026-10-01", listOf(Trigger.GateDodged(1)))
        val monthly = DigestReason(DigestKind.MONTHLY, "2026-09", listOf(Trigger.MonthClosed("2026-09")))
        assertFalse(repo.exists(daily))

        repo.writeAll(listOf(daily to "card"), snapshotAt(10L), 1_000L)

        assertTrue(repo.exists(daily))
        assertFalse(repo.exists(monthly))
    }

    private fun snapshotAt(lastEventId: Long) =
        DigestSnapshot(Weather.SUNNY, houseLevel = 2, streakDays = 3, lastEventId = lastEventId)
}
