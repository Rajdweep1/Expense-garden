package com.expensegarden.app.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v1→v2: budget gains FOREIGN KEY (categoryId) → category(id) ON DELETE CASCADE.
 * SQLite can't add a constraint in place: recreate, copy, swap, re-index.
 * The CREATE TABLE below must match 2.json's createSql exactly (Room validates in tests).
 */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `budget_new` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`categoryId` INTEGER, `month` TEXT NOT NULL, `amountPaise` INTEGER NOT NULL, " +
                "FOREIGN KEY(`categoryId`) REFERENCES `category`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )"
        )
        db.execSQL("INSERT INTO budget_new (id, categoryId, month, amountPaise) SELECT id, categoryId, month, amountPaise FROM budget")
        db.execSQL("DROP TABLE budget")
        db.execSQL("ALTER TABLE budget_new RENAME TO budget")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_budget_categoryId_month` ON `budget` (`categoryId`, `month`)")
    }
}

/**
 * v2→v3 (Phase 1D): adds the `digest` table and buckets `quip` by tone.
 *
 * `quip.tone` is a plain ADD COLUMN with a NOT NULL default, so no table rebuild is needed
 * and every existing row adopts 'SHARP' — the voice the seeded bank was written in.
 *
 * The CREATE statements below must match 3.json's createSql exactly (Room validates in
 * MigrationTest). Regenerate with the python one-liner in the plan's Task 8 Step 6 if the
 * entities change.
 */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE quip ADD COLUMN `tone` TEXT NOT NULL DEFAULT 'SHARP'")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `digest` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`kind` TEXT NOT NULL, `scopeKey` TEXT NOT NULL, `text` TEXT NOT NULL, " +
                "`reasonJson` TEXT NOT NULL, `snapshotJson` TEXT NOT NULL, " +
                "`lastEventId` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, " +
                "`dismissedAt` INTEGER)"
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_digest_kind_scopeKey` " +
                "ON `digest` (`kind`, `scopeKey`)"
        )
    }
}

/**
 * v3→v4 (Phase 2A): sync stamps and the tombstone table.
 *
 * Each ADD COLUMN carries `NOT NULL DEFAULT 0` to match the entities' @ColumnInfo default —
 * Room compares the two and refuses to open on a mismatch. The follow-up UPDATE then stamps
 * every pre-existing row with the migration time, which is what makes them all dirty against
 * a cursor starting at 0: the first sync uploads the entire history as one batch.
 *
 * The CREATE below matches 4.json's createSql exactly (MigrationTest validates it).
 */
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        val now = System.currentTimeMillis()
        for (table in listOf("category", "payee", "txn", "budget")) {
            db.execSQL("ALTER TABLE `$table` ADD COLUMN `updatedAt` INTEGER NOT NULL DEFAULT 0")
            db.execSQL("UPDATE `$table` SET `updatedAt` = $now")
        }
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `sync_tombstone` (`tableName` TEXT NOT NULL, " +
                "`rowKey` TEXT NOT NULL, `deletedAt` INTEGER NOT NULL, " +
                "PRIMARY KEY(`tableName`, `rowKey`))"
        )
    }
}
