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
