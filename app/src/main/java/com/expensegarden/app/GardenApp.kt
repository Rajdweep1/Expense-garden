package com.expensegarden.app

import android.app.Application
import com.expensegarden.app.data.AppDatabase

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
}
