package com.expensegarden.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface CategoryDao {
    @Query("SELECT * FROM category ORDER BY isNecessity DESC, name")
    fun observeAll(): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM category WHERE id = :id")
    suspend fun byId(id: Long): CategoryEntity?

    @Query("SELECT * FROM category")
    suspend fun all(): List<CategoryEntity>
}

@Dao
interface PayeeDao {
    @Query("SELECT * FROM payee WHERE vpa = :vpa LIMIT 1")
    suspend fun byVpa(vpa: String): PayeeEntity?

    @Query("SELECT * FROM payee WHERE vpa IS NULL AND name = :name LIMIT 1")
    suspend fun cashPayeeByName(name: String): PayeeEntity?

    @Insert
    suspend fun insert(payee: PayeeEntity): Long

    @Query("UPDATE payee SET defaultCategoryId = :categoryId WHERE id = :payeeId")
    suspend fun setDefaultCategory(payeeId: Long, categoryId: Long)
}

data class TxnRow(
    val uuid: String,
    val amountPaise: Long,
    val payeeName: String,
    val categoryName: String,
    val categoryId: Long,
    val regret: Regret,
    val occurredAt: Long,
)

data class CategorySum(val categoryId: Long, val totalPaise: Long)
data class CategoryUsage(val categoryId: Long, val uses: Int)

@Dao
interface TransactionDao {
    @Insert
    suspend fun insert(txn: TransactionEntity)

    @Query("UPDATE txn SET status = :status WHERE uuid = :uuid")
    suspend fun setStatus(uuid: String, status: TxnStatus)

    @Query("SELECT * FROM txn WHERE status = 'PENDING_CONFIRM' ORDER BY createdAt")
    fun observePendingConfirm(): Flow<List<TransactionEntity>>

    @Query("SELECT COALESCE(SUM(amountPaise), 0) FROM txn WHERE status = 'LOGGED' AND occurredAt BETWEEN :fromMillis AND :toMillis")
    suspend fun loggedSumBetween(fromMillis: Long, toMillis: Long): Long

    @Query("SELECT COALESCE(SUM(amountPaise), 0) FROM txn WHERE status = 'LOGGED' AND occurredAt BETWEEN :fromMillis AND :toMillis")
    fun observeLoggedSumBetween(fromMillis: Long, toMillis: Long): Flow<Long>

    @Query(
        """SELECT t.uuid, t.amountPaise, p.name AS payeeName, c.name AS categoryName,
                  t.categoryId, t.regret, t.occurredAt
           FROM txn t JOIN payee p ON p.id = t.payeeId JOIN category c ON c.id = t.categoryId
           WHERE t.status = 'LOGGED' ORDER BY t.occurredAt DESC LIMIT 50"""
    )
    fun observeRecent(): Flow<List<TxnRow>>

    @Query("SELECT * FROM txn WHERE uuid = :uuid")
    suspend fun byUuid(uuid: String): TransactionEntity?

    @Query("UPDATE txn SET regret = :regret WHERE uuid = :uuid")
    suspend fun setRegret(uuid: String, regret: Regret)

    @Query(
        """SELECT categoryId, COALESCE(SUM(amountPaise), 0) AS totalPaise FROM txn
           WHERE status = 'LOGGED' AND occurredAt BETWEEN :fromMillis AND :toMillis GROUP BY categoryId"""
    )
    suspend fun loggedSumsByCategory(fromMillis: Long, toMillis: Long): List<CategorySum>

    @Query(
        """SELECT categoryId, COALESCE(SUM(amountPaise), 0) AS totalPaise FROM txn
           WHERE status = 'LOGGED' AND occurredAt BETWEEN :fromMillis AND :toMillis GROUP BY categoryId"""
    )
    fun observeLoggedSumsByCategory(fromMillis: Long, toMillis: Long): Flow<List<CategorySum>>

    @Query(
        """SELECT categoryId, COUNT(*) AS uses FROM txn
           WHERE status = 'LOGGED' AND occurredAt >= :sinceMillis GROUP BY categoryId"""
    )
    fun observeCategoryUsageSince(sinceMillis: Long): Flow<List<CategoryUsage>>

    @Query(
        """SELECT categoryId, COUNT(*) AS uses FROM txn
           WHERE status = 'LOGGED' AND occurredAt >= :sinceMillis GROUP BY categoryId"""
    )
    suspend fun categoryUsageSince(sinceMillis: Long): List<CategoryUsage>

    @Query("SELECT * FROM txn WHERE status = 'LOGGED' AND occurredAt BETWEEN :fromMillis AND :toMillis")
    suspend fun loggedBetween(fromMillis: Long, toMillis: Long): List<TransactionEntity>

    @Query("SELECT * FROM txn WHERE status = 'LOGGED' AND occurredAt BETWEEN :fromMillis AND :toMillis")
    fun observeLoggedBetween(fromMillis: Long, toMillis: Long): Flow<List<TransactionEntity>>

    @Query(
        """SELECT t.uuid, t.amountPaise, p.name AS payeeName, c.name AS categoryName,
                  t.categoryId, t.regret, t.occurredAt
           FROM txn t JOIN payee p ON p.id = t.payeeId JOIN category c ON c.id = t.categoryId
           WHERE t.uuid = :uuid"""
    )
    suspend fun rowByUuid(uuid: String): TxnRow?

    @Query("SELECT COUNT(*) FROM txn WHERE status = 'LOGGED' AND categoryId IN (:categoryIds)")
    fun observeLoggedCountIn(categoryIds: List<Long>): Flow<Int>

    @Query("SELECT MIN(occurredAt) FROM txn WHERE status = 'LOGGED'")
    suspend fun earliestLoggedAt(): Long?
}

@Dao
interface BudgetDao {
    @Query("SELECT * FROM budget WHERE categoryId IS NULL AND month = :month LIMIT 1")
    suspend fun overallForMonth(month: String): BudgetEntity?

    @Query("SELECT * FROM budget WHERE categoryId IS NULL AND month = :month LIMIT 1")
    fun observeOverallForMonth(month: String): Flow<BudgetEntity?>

    @Query("DELETE FROM budget WHERE categoryId IS NULL AND month = :month")
    suspend fun deleteOverallForMonth(month: String)

    @Insert
    suspend fun insert(budget: BudgetEntity)

    @Query("SELECT * FROM budget WHERE month = :month")
    suspend fun allForMonth(month: String): List<BudgetEntity>

    @Query("SELECT * FROM budget WHERE month = :month")
    fun observeAllForMonth(month: String): Flow<List<BudgetEntity>>

    // NULL never matches `categoryId = ?` in SQL — overall rows need the dedicated IS NULL delete above.
    @Query("DELETE FROM budget WHERE categoryId = :categoryId AND month = :month")
    suspend fun deleteForCategory(categoryId: Long, month: String)
}

@Dao
interface GameEventDao {
    @Insert
    suspend fun insert(event: GameEventEntity)

    @Query("SELECT * FROM game_event ORDER BY id")
    suspend fun allByIdAsc(): List<GameEventEntity>

    @Query("SELECT * FROM game_event WHERE createdAt BETWEEN :fromMillis AND :toMillis ORDER BY id")
    suspend fun eventsBetween(fromMillis: Long, toMillis: Long): List<GameEventEntity>

    @Query("SELECT * FROM game_event WHERE createdAt BETWEEN :fromMillis AND :toMillis ORDER BY id")
    fun observeEventsBetween(fromMillis: Long, toMillis: Long): Flow<List<GameEventEntity>>

    @Query("SELECT * FROM game_event WHERE type = :type ORDER BY id")
    suspend fun ofType(type: String): List<GameEventEntity>
}

@Dao
interface QuipDao {
    @Query("SELECT * FROM quip WHERE severity = :severity ORDER BY usedAt IS NOT NULL, usedAt ASC LIMIT 1")
    suspend fun leastRecentlyUsed(severity: String): QuipEntity?

    @Query("UPDATE quip SET usedAt = :now WHERE id = :id")
    suspend fun markUsed(id: Long, now: Long)
}
