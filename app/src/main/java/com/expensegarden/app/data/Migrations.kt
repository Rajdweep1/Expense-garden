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
