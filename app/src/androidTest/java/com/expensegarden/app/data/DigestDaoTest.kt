package com.expensegarden.app.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.expensegarden.app.game.DigestEvent
import com.expensegarden.app.game.DigestKind
import com.expensegarden.app.game.DigestReason
import com.expensegarden.app.game.DigestSnapshot
import com.expensegarden.app.game.Trigger
import com.expensegarden.app.game.Weather
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DigestDaoTest {
    private lateinit var db: AppDatabase
    private lateinit var repo: DigestRepository

    @Before fun setUp() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).build()
        repo = DigestRepository(db, LedgerRepository(db))
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

        val out = repo.eventsSince(afterId = 0L, floorMillis = null)

        assertEquals(4, out.size)                       // transaction.logged is not a trigger
        assertEquals("2026-08", (out[0] as DigestEvent.MonthClosed).monthKey)
        assertEquals(7, (out[1] as DigestEvent.StreakHit).days)
        assertTrue(out[2] is DigestEvent.GateDodged)
        assertTrue(out[3] is DigestEvent.Regretted)
    }

    @Test fun a_malformed_payload_is_skipped_rather_than_crashing_the_fold() = runBlocking {
        db.gameEventDao().insert(event("month.closed", "not json at all"))
        db.gameEventDao().insert(event("gate.dodged", """{}"""))
        assertEquals(1, repo.eventsSince(0L, null).size)
    }

    @Test fun the_floor_excludes_events_older_than_it() = runBlocking {
        db.gameEventDao().insert(event("gate.dodged", "{}", at = 500L))
        db.gameEventDao().insert(event("gate.dodged", "{}", at = 5_000L))
        assertEquals(1, repo.eventsSince(afterId = 0L, floorMillis = 1_000L).size)
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
}
