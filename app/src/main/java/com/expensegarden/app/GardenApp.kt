package com.expensegarden.app

import android.app.Application
import com.expensegarden.app.data.AppDatabase
import com.expensegarden.app.data.GardenRepository
import com.expensegarden.app.data.LedgerRepository
import com.expensegarden.app.data.QuipRepository

class GardenApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

class AppContainer(app: Application) {
    val db: AppDatabase = AppDatabase.build(app)
    val ledger: LedgerRepository = LedgerRepository(db)
    val quips: QuipRepository = QuipRepository(db)
    val garden: GardenRepository = GardenRepository(db, ledger)
}
