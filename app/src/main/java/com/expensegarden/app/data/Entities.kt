package com.expensegarden.app.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

// Stored as TEXT via EnumConverters.
enum class TxnSource { QR_GATE, MANUAL, IMPORT }
enum class TxnStatus { PENDING_CONFIRM, LOGGED, DISCARDED }
enum class Regret { UNRATED, WORTH_IT, REGRET }

@Entity(tableName = "category")
data class CategoryEntity(
    @PrimaryKey val id: Long,
    val name: String,
    val parentId: Long?,          // self-FK deliberately omitted in 1A: rows are seed-only; enforced in Postgres later
    val isNecessity: Boolean,
    /** Sync stamp (Phase 2A). Written from SyncClock, never from System.currentTimeMillis()
     *  directly — the logical clock guarantees it is strictly increasing, which is what makes
     *  the `updatedAt > lastPushedAt` dirty-row predicate exact. The column carries a SQL
     *  default so MIGRATION_3_4's ADD COLUMN matches Room's schema validation; the Kotlin
     *  field deliberately has NO default, so every construction site must supply a stamp. */
    @ColumnInfo(defaultValue = "0") val updatedAt: Long,
)

@Entity(
    tableName = "payee",
    indices = [Index(value = ["vpa"], unique = true), Index("defaultCategoryId")],
    foreignKeys = [ForeignKey(
        entity = CategoryEntity::class,
        parentColumns = ["id"],
        childColumns = ["defaultCategoryId"],
        onDelete = ForeignKey.SET_NULL,
    )],
)
data class PayeeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val vpa: String?,             // null for cash payees
    val defaultCategoryId: Long?,
    @ColumnInfo(defaultValue = "0") val updatedAt: Long,
)

@Entity(
    tableName = "txn",
    indices = [Index("payeeId"), Index("categoryId"), Index("status"), Index("occurredAt")],
    foreignKeys = [
        ForeignKey(
            entity = PayeeEntity::class,
            parentColumns = ["id"],
            childColumns = ["payeeId"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = CategoryEntity::class,
            parentColumns = ["id"],
            childColumns = ["categoryId"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
)
data class TransactionEntity(
    @PrimaryKey val uuid: String,          // client-generated; survives future sync
    val amountPaise: Long,
    val payeeId: Long,
    val categoryId: Long,
    val source: TxnSource,
    val status: TxnStatus,
    val regret: Regret = Regret.UNRATED,
    val breachedAtLogging: Boolean,        // weed rule input, frozen at capture time (spec §9.3)
    val note: String?,
    val occurredAt: Long,                  // epoch millis; user-settable backdating UI lands in 1B
    val createdAt: Long,
    @ColumnInfo(defaultValue = "0") val updatedAt: Long,
)

@Entity(
    tableName = "budget",
    indices = [Index(value = ["categoryId", "month"], unique = true)],
    foreignKeys = [ForeignKey(
        entity = CategoryEntity::class,
        parentColumns = ["id"],
        childColumns = ["categoryId"],
        onDelete = ForeignKey.CASCADE,   // a budget without its category is meaningless; categories are seed-only, so belt-and-braces
    )],
)
data class BudgetEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val categoryId: Long?,                 // null = overall budget
    val month: String,                     // "2026-07"
    val amountPaise: Long,
    @ColumnInfo(defaultValue = "0") val updatedAt: Long,
)

@Entity(
    tableName = "game_event",
    indices = [Index("transactionUuid")],
    foreignKeys = [ForeignKey(
        entity = TransactionEntity::class,
        parentColumns = ["uuid"],
        childColumns = ["transactionUuid"],
        onDelete = ForeignKey.SET_NULL,
    )],
)
data class GameEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: String,                      // "transaction.logged", "gate.dodged", ...
    val payloadJson: String,
    val transactionUuid: String?,
    val createdAt: Long,
)

@Entity(tableName = "quip", indices = [Index("severity")])
data class QuipEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val severity: String,                  // Severity.name — PACE_WARNING or BREACH
    @ColumnInfo(defaultValue = "STATIC") val origin: String = "STATIC", // STATIC or LLM
    // 1D: the bank is bucketed by (severity × tone). The seeded STATIC lines were written in
    // the sharp-but-fair voice, so they migrate to SHARP and act as the fallback for every
    // tone whose LLM bucket is still empty (spec §6).
    @ColumnInfo(defaultValue = "SHARP") val tone: String = "SHARP",     // Tone.name
    val text: String,
    val usedAt: Long?,                     // null = never used; picker prefers these
)

/** One thing the persona said (spec §9).
 *
 *  `UNIQUE(kind, scopeKey)` is the whole concurrency story: several transitions on one day
 *  still produce at most one daily card, and a month cannot be summarized twice.
 *
 *  `lastEventId` is the watermark — the highest game_event.id seen at EVALUATION time,
 *  captured before the LLM call. A createdAt watermark would be unsound twice over: an event
 *  logged during the network round trip would land with an earlier timestamp and fall behind
 *  it forever, and runReconciler stamps a whole batch of month.closed rows with one
 *  System.currentTimeMillis(), so timestamps cannot even order them. */
@Entity(
    tableName = "digest",
    indices = [Index(value = ["kind", "scopeKey"], unique = true)],
)
data class DigestEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val kind: String,            // DigestKind.name — DAILY or MONTHLY
    val scopeKey: String,        // "2026-09-05" for DAILY, "2026-09" for MONTHLY
    val text: String,
    val reasonJson: String,      // why it spoke — traceability when a digest reads oddly
    val snapshotJson: String,    // weather / houseLevel / streakDays at the moment of speaking
    val lastEventId: Long,       // the watermark
    val createdAt: Long,
    val dismissedAt: Long?,      // null = still showing
)

/** A row that was deleted locally and must be deleted on the server too (spec §2.2).
 *
 *  A separate table rather than a soft-delete flag on `budget`: a flag would mean adding
 *  `WHERE deleted = 0` to every existing budget query, and one missed filter silently
 *  corrupts both the dashboard and the gate. `budget` is the only synced table with deletes.
 *
 *  `rowKey` encodes the sync key as "<categoryId or *>|<month>" — "3|2026-09" for a category
 *  budget, "*|2026-09" for the overall one. The sentinel is explicit because an empty segment
 *  would be indistinguishable from a malformed key. */
@Entity(tableName = "sync_tombstone", primaryKeys = ["tableName", "rowKey"])
data class SyncTombstoneEntity(
    val tableName: String,
    val rowKey: String,
    val deletedAt: Long,
)
