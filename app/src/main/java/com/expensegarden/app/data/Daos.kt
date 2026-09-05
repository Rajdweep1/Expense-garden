package com.expensegarden.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
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

    /** Feeds PromptFacts.topCategories. Returns seeded taxonomy names, which PromptFacts
     *  whitelists — the table is `txn`, not `transaction`. */
    @Query(
        "SELECT c.name FROM txn t JOIN category c ON c.id = t.categoryId " +
            "WHERE t.status = 'LOGGED' AND t.occurredAt BETWEEN :fromMillis AND :toMillis " +
            "GROUP BY c.id ORDER BY SUM(t.amountPaise) DESC LIMIT 3"
    )
    suspend fun topCategoryNames(fromMillis: Long, toMillis: Long): List<String>

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

    /** The watermark head (spec §9): the highest id in the log, read BEFORE the window so
     *  nothing above it can be mistaken for "seen". O(1) off the primary key. */
    @Query("SELECT COALESCE(MAX(id), 0) FROM game_event")
    suspend fun headId(): Long

    /** The evaluation window: strictly after the previous watermark, up to and including the
     *  head read just before. Bounded by id, not createdAt — runReconciler calls
     *  System.currentTimeMillis() per row inside a tight loop, so a batch's timestamps
     *  typically collide and cannot order it. */
    @Query("SELECT * FROM game_event WHERE id > :afterId AND id <= :upToId ORDER BY id")
    suspend fun eventsInIdRange(afterId: Long, upToId: Long): List<GameEventEntity>
}

@Dao
interface QuipDao {
    /** Least-recently-used line for this (severity, tone); unused quips win first.
     *  NOTE: this CYCLES once the bucket is exhausted — it is least-recent, not no-repeat.
     *  Keeping repeats rare is QuipRefresher's job, not this query's (spec §7). */
    @Query(
        "SELECT * FROM quip WHERE severity = :severity AND tone = :tone " +
            "ORDER BY usedAt IS NOT NULL, usedAt ASC LIMIT 1"
    )
    suspend fun leastRecentlyUsed(severity: String, tone: String): QuipEntity?

    /** Fallback when a tone's bucket is empty: the seeded sharp-but-fair bank. */
    @Query(
        "SELECT * FROM quip WHERE severity = :severity AND origin = 'STATIC' " +
            "ORDER BY usedAt IS NOT NULL, usedAt ASC LIMIT 1"
    )
    suspend fun leastRecentlyUsedStatic(severity: String): QuipEntity?

    /** Unused stock in one bucket — drives the "below 5 lines" refresh trigger (spec §6). */
    @Query("SELECT COUNT(*) FROM quip WHERE severity = :severity AND tone = :tone AND usedAt IS NULL")
    suspend fun unusedCount(severity: String, tone: String): Int

    /** Dedup guard for the refresher: what this bucket already holds, used or not. */
    @Query("SELECT text FROM quip WHERE severity = :severity AND tone = :tone")
    suspend fun textsIn(severity: String, tone: String): List<String>

    @Query("UPDATE quip SET usedAt = :now WHERE id = :id")
    suspend fun markUsed(id: Long, now: Long)

    @Insert
    suspend fun insertAll(quips: List<QuipEntity>)
}

@Dao
interface DigestDao {
    /** The watermark row — the digest of any kind with the highest watermark. Ordered by
     *  lastEventId first so a future writer carrying an older head (a synced server digest,
     *  say) cannot move the baseline backwards and re-evaluate the gap. */
    @Query("SELECT * FROM digest ORDER BY lastEventId DESC, id DESC LIMIT 1")
    suspend fun latest(): DigestEntity?

    @Query("SELECT * FROM digest WHERE kind = :kind AND scopeKey = :scopeKey LIMIT 1")
    suspend fun byScope(kind: String, scopeKey: String): DigestEntity?

    /** The daily card the home screen shows: today's, if it exists and is undismissed. */
    @Query("SELECT * FROM digest WHERE kind = 'DAILY' AND scopeKey = :day AND dismissedAt IS NULL LIMIT 1")
    fun observeDaily(day: String): Flow<DigestEntity?>

    @Query("SELECT * FROM digest WHERE kind = 'MONTHLY' AND scopeKey = :monthKey LIMIT 1")
    suspend fun monthly(monthKey: String): DigestEntity?

    /** IGNORE, not REPLACE: the UNIQUE(kind, scopeKey) constraint is the concurrency guard,
     *  and a second write for the same scope must lose rather than overwrite the first. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(digest: DigestEntity): Long

    @Query("UPDATE digest SET dismissedAt = :now WHERE id = :id")
    suspend fun dismiss(id: Long, now: Long)
}
