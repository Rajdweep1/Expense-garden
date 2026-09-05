package com.expensegarden.app.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class BudgetDaoTest {
    private lateinit var db: AppDatabase

    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .addCallback(SeedCallback)
            .allowMainThreadQueries()
            .build()
    }

    @After fun teardown() = db.close()

    private suspend fun logTxn(categoryId: Long, paise: Long, at: Long) {
        val payeeId = db.payeeDao().insert(PayeeEntity(name = "p$at", vpa = null, defaultCategoryId = null, updatedAt = 1L))
        db.transactionDao().insert(
            TransactionEntity(
                uuid = UUID.randomUUID().toString(), amountPaise = paise, payeeId = payeeId,
                categoryId = categoryId, source = TxnSource.MANUAL, status = TxnStatus.LOGGED,
                breachedAtLogging = false, note = null, occurredAt = at, createdAt = at, updatedAt = 1L,
            )
        )
    }

    @Test fun budget_scope_crud_null_and_category_are_distinct_rows() = runBlocking {
        db.budgetDao().insert(BudgetEntity(categoryId = null, month = "2026-07", amountPaise = 1_000_000, updatedAt = 1L))
        db.budgetDao().insert(BudgetEntity(categoryId = 1, month = "2026-07", amountPaise = 50_000, updatedAt = 1L))
        assertEquals(2, db.budgetDao().allForMonth("2026-07").size)

        // SQL NULL never matches `categoryId = ?` — the category delete must not touch the overall row.
        db.budgetDao().deleteForCategory(1, "2026-07")
        val left = db.budgetDao().allForMonth("2026-07")
        assertEquals(1, left.size)
        assertEquals(null, left.single().categoryId)

        db.budgetDao().deleteOverallForMonth("2026-07")
        assertEquals(0, db.budgetDao().allForMonth("2026-07").size)
    }

    @Test fun sums_by_category_group_logged_only() = runBlocking {
        logTxn(categoryId = 103, paise = 2_000, at = 1_000L)
        logTxn(categoryId = 103, paise = 3_000, at = 1_100L)
        logTxn(categoryId = 3, paise = 500, at = 1_200L)
        val sums = db.transactionDao().loggedSumsByCategory(0L, 2_000L).associate { it.categoryId to it.totalPaise }
        assertEquals(5_000L, sums[103L])
        assertEquals(500L, sums[3L])
    }

    @Test fun usage_counts_count_rows_not_amounts() = runBlocking {
        logTxn(categoryId = 103, paise = 1, at = 1_000L)
        logTxn(categoryId = 103, paise = 1, at = 1_100L)
        logTxn(categoryId = 3, paise = 999_999, at = 1_200L)
        val usage = db.transactionDao().categoryUsageSince(0L).associate { it.categoryId to it.uses }
        assertEquals(2, usage[103L])
        assertEquals(1, usage[3L])
    }
}
