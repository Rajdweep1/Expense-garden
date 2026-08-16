package com.expensegarden.app

import android.app.Application
import androidx.compose.ui.graphics.ImageBitmap
import com.expensegarden.app.data.AppDatabase
import com.expensegarden.app.data.GardenPrefs
import com.expensegarden.app.data.GardenRepository
import com.expensegarden.app.data.LedgerRepository
import com.expensegarden.app.data.QuipRepository
import com.expensegarden.app.game.Archetype
import com.expensegarden.app.render.SpriteLoader

class GardenApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

class AppContainer(private val app: Application) {
    val db: AppDatabase = AppDatabase.build(app)
    val ledger: LedgerRepository = LedgerRepository(db)
    val quips: QuipRepository = QuipRepository(db)
    val garden: GardenRepository = GardenRepository(db, ledger)
    val prefs: GardenPrefs = GardenPrefs(app)

    /** Lazy: decoded on first painter selection, not app start. Empty map = pack not installed. */
    val sprites: Map<Pair<Archetype, Int>, ImageBitmap> by lazy { SpriteLoader.load(app) }

    /** House-level sprites (house_0..3), keyed by base name. Empty = not installed → house skipped. */
    val structures: Map<String, ImageBitmap> by lazy { SpriteLoader.loadStructures(app) }
}
