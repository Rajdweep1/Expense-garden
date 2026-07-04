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
    val occurredAt: Long,
)

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
        """SELECT t.uuid, t.amountPaise, p.name AS payeeName, c.name AS categoryName, t.occurredAt
           FROM txn t JOIN payee p ON p.id = t.payeeId JOIN category c ON c.id = t.categoryId
           WHERE t.status = 'LOGGED' ORDER BY t.occurredAt DESC LIMIT 50"""
    )
    fun observeRecent(): Flow<List<TxnRow>>
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
}

@Dao
interface GameEventDao {
    @Insert
    suspend fun insert(event: GameEventEntity)
}

@Dao
interface QuipDao {
    @Query("SELECT * FROM quip WHERE severity = :severity ORDER BY usedAt IS NOT NULL, usedAt ASC LIMIT 1")
    suspend fun leastRecentlyUsed(severity: String): QuipEntity?

    @Query("UPDATE quip SET usedAt = :now WHERE id = :id")
    suspend fun markUsed(id: Long, now: Long)
}
