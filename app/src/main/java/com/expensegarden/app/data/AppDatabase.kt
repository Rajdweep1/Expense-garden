package com.expensegarden.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.sqlite.db.SupportSQLiteDatabase

class EnumConverters {
    @TypeConverter fun sourceToString(v: TxnSource) = v.name
    @TypeConverter fun stringToSource(v: String) = TxnSource.valueOf(v)
    @TypeConverter fun statusToString(v: TxnStatus) = v.name
    @TypeConverter fun stringToStatus(v: String) = TxnStatus.valueOf(v)
    @TypeConverter fun regretToString(v: Regret) = v.name
    @TypeConverter fun stringToRegret(v: String) = Regret.valueOf(v)
}

@Database(
    entities = [
        CategoryEntity::class, PayeeEntity::class, TransactionEntity::class,
        BudgetEntity::class, GameEventEntity::class, QuipEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
@TypeConverters(EnumConverters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun categoryDao(): CategoryDao
    abstract fun payeeDao(): PayeeDao
    abstract fun transactionDao(): TransactionDao
    abstract fun budgetDao(): BudgetDao
    abstract fun gameEventDao(): GameEventDao
    abstract fun quipDao(): QuipDao

    companion object {
        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "garden.db")
                .addCallback(SeedCallback)
                .build()
    }
}

object SeedCallback : RoomDatabase.Callback() {
    override fun onCreate(db: SupportSQLiteDatabase) {
        super.onCreate(db)
        // Categories: (id, name, parentId, isNecessity). Parents first.
        val categories = listOf(
            "(1,'Food & Drinks',NULL,0)", "(2,'Groceries',NULL,1)", "(3,'Transport',NULL,1)",
            "(4,'Housing',NULL,1)", "(5,'Health',NULL,1)", "(6,'Entertainment',NULL,0)",
            "(7,'Shopping',NULL,0)", "(8,'Personal',NULL,0)", "(9,'Family',NULL,1)",
            "(10,'Investments',NULL,1)", "(11,'Misc',NULL,0)",
            "(101,'Restaurants',1,0)", "(102,'Delivery',1,0)", "(103,'Chai & Snacks',1,0)",
            "(301,'Fuel',3,1)", "(302,'Cab & Auto',3,0)", "(303,'Metro & Bus',3,1)",
            "(401,'Rent',4,1)", "(402,'Utilities',4,1)",
            "(601,'Streaming',6,0)", "(602,'Outings',6,0)",
        )
        db.execSQL("INSERT INTO category (id, name, parentId, isNecessity) VALUES ${categories.joinToString(",")}")

        // Sharp-but-fair static quip bank. Gate shows nothing on OK.
        val quips = listOf(
            "PACE_WARNING" to "You're spending like it's the 1st. It's not the 1st.",
            "PACE_WARNING" to "The budget is watching. It's not angry, just doing the math.",
            "PACE_WARNING" to "At this pace the month outlives the money. Proceed?",
            "PACE_WARNING" to "Bold pace. The garden's getting thirsty though.",
            "PACE_WARNING" to "This one's fine. The next three are the problem.",
            "BREACH" to "That's a weed and you know it. Plant it anyway?",
            "BREACH" to "Budget's already gone. This is just archaeology now.",
            "BREACH" to "This is how droughts start. Your call.",
            "BREACH" to "Somewhere, a future you is squinting at this line item.",
            "BREACH" to "The compost heap has room. Just saying.",
        )
        quips.forEach { (severity, text) ->
            db.execSQL(
                "INSERT INTO quip (severity, origin, text, usedAt) VALUES (?, 'STATIC', ?, NULL)",
                arrayOf(severity, text),
            )
        }
    }
}
