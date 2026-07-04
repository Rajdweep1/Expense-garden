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
)

@Entity(
    tableName = "budget",
    indices = [Index(value = ["categoryId", "month"], unique = true)],
)
data class BudgetEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val categoryId: Long?,                 // null = overall budget (only kind used in 1A)
    val month: String,                     // "2026-07"
    val amountPaise: Long,
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
    @ColumnInfo(defaultValue = "STATIC") val origin: String = "STATIC", // LLM refresh comes in 1D
    val text: String,
    val usedAt: Long?,                     // null = never used; picker prefers these
)
