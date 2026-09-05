package com.expensegarden.app.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Spec §4.1: the island must be identical on every replay of the log.
 *
 *  If a rare were rolled at runtime this would fail, and — worse — the greenhouse's archived
 *  months would silently drift, because each fold would decorate a different purchase. Same
 *  defect class as a wall-clock watermark, which this project has been bitten by twice, so it
 *  gets a test against a real database rather than an assumption.
 *
 *  Also covers the JSON projection, which cannot be unit-tested: `org.json` is an Android stub
 *  that throws "not mocked" on the JVM. */
@RunWith(AndroidJUnit4::class)
class RareDeterminismTest {
    private lateinit var db: AppDatabase
    private lateinit var garden: GardenRepository

    @Before fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java)
            .addCallback(SeedCallback)
            .allowMainThreadQueries()
            .build()
        garden = GardenRepository(db, LedgerRepository(db))
    }

    @After fun tearDown() = db.close()

    private suspend fun payee(): Long =
        db.payeeDao().insert(PayeeEntity(name = "P", vpa = null, defaultCategoryId = null, updatedAt = 1L))

    private suspend fun logTxn(uuid: String, categoryId: Long, at: Long, payeeId: Long) {
        db.transactionDao().insert(
            TransactionEntity(
                uuid = uuid, amountPaise = 5_000L, payeeId = payeeId, categoryId = categoryId,
                source = TxnSource.MANUAL, status = TxnStatus.LOGGED, breachedAtLogging = false,
                note = null, occurredAt = at, createdAt = at, updatedAt = 1L,
            )
        )
    }

    private suspend fun dodge(at: Long) {
        db.gameEventDao().insert(
            GameEventEntity(type = "gate.dodged", payloadJson = "{}", transactionUuid = null, createdAt = at)
        )
    }

    @Test fun the_same_log_folds_to_the_same_rares_twice() = runBlocking {
        val p = payee()
        val t0 = System.currentTimeMillis()
        repeat(3) { dodge(t0) }
        repeat(4) { i -> logTxn(UUID.randomUUID().toString(), 103, t0 + 60_000L + i, p) }

        val first = garden.observeAllTimeGarden().first().plants.map { it.txnUuid to it.rare?.id }
        val second = garden.observeAllTimeGarden().first().plants.map { it.txnUuid to it.rare?.id }

        assertEquals(first, second)
    }

    @Test fun three_dodges_grow_exactly_one_rare_on_a_later_purchase() = runBlocking {
        val p = payee()
        val t0 = System.currentTimeMillis()
        repeat(3) { dodge(t0) }
        logTxn("later", 103, t0 + 60_000L, p)

        val plants = garden.observeAllTimeGarden().first().plants
        assertEquals(1, plants.count { it.rare != null })
    }

    @Test fun two_dodges_grow_no_rare() = runBlocking {
        val p = payee()
        val t0 = System.currentTimeMillis()
        repeat(2) { dodge(t0) }
        logTxn("later", 103, t0 + 60_000L, p)

        assertNull(garden.observeAllTimeGarden().first().plants.single().rare)
    }

    @Test fun a_rare_always_matches_the_purchases_own_archetype() = runBlocking {
        // The honesty rule, verified end to end: a Groceries purchase must never come back
        // wearing another category's species.
        val p = payee()
        val t0 = System.currentTimeMillis()
        repeat(3) { dodge(t0) }
        logTxn("groceries", 2, t0 + 60_000L, p)

        val plant = garden.observeAllTimeGarden().first().plants.single()
        assertNotNull(plant.rare)
        assertEquals(plant.archetype, plant.rare!!.baseArchetype)
    }

    @Test fun a_malformed_payload_is_skipped_rather_than_crashing_the_fold() = runBlocking {
        val p = payee()
        val t0 = System.currentTimeMillis()
        db.gameEventDao().insert(
            GameEventEntity(type = "streak.hit", payloadJson = "not json", transactionUuid = null, createdAt = t0)
        )
        repeat(3) { dodge(t0) }
        logTxn("later", 103, t0 + 60_000L, p)

        // The bad row costs one trigger, not the whole garden.
        assertEquals(1, garden.observeAllTimeGarden().first().plants.count { it.rare != null })
    }

    @Test fun re_tagging_a_regret_repeatedly_still_grows_only_one_rare() = runBlocking {
        // The anti-farming guarantee (spec §3.3), verified where it actually runs. setRegret
        // no-ops only on an unchanged value, so this really does emit regret_cleared twice.
        val p = payee()
        val t0 = System.currentTimeMillis()
        val ledger = LedgerRepository(db)
        logTxn("target", 103, t0, p)

        ledger.setRegret("target", Regret.REGRET)
        ledger.setRegret("target", Regret.WORTH_IT)
        ledger.setRegret("target", Regret.REGRET)
        ledger.setRegret("target", Regret.WORTH_IT)

        logTxn("clean1", 103, t0 + 60_000L, p)
        logTxn("clean2", 103, t0 + 120_000L, p)

        val plants = garden.observeAllTimeGarden().first().plants
        assertEquals(1, plants.count { it.rare != null })
    }
}
