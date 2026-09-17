package com.expensegarden.app

import android.app.Application
import androidx.compose.ui.graphics.ImageBitmap
import com.expensegarden.app.ai.GeminiClient
import com.expensegarden.app.ai.LlmClient
import com.expensegarden.app.ai.NoopLlmClient
import com.expensegarden.app.data.AiPrefs
import com.expensegarden.app.data.AppDatabase
import com.expensegarden.app.data.BudgetRepository
import com.expensegarden.app.data.DigestRepository
import com.expensegarden.app.data.GardenPrefs
import com.expensegarden.app.data.GardenRepository
import com.expensegarden.app.data.LedgerRepository
import com.expensegarden.app.data.QuipRepository
import com.expensegarden.app.render.SpriteCache
import com.expensegarden.app.render.SpriteLoader
import com.expensegarden.app.sync.SyncClient
import com.expensegarden.app.sync.SyncClock
import com.expensegarden.app.sync.SyncPrefs
import com.expensegarden.app.sync.SyncRepository
import com.expensegarden.app.sync.SyncScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

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

    // Declared before every repository that takes them: Kotlin initialises properties in
    // declaration order, so moving these below `ledger` would hand it a null clock.
    val syncPrefs: SyncPrefs = SyncPrefs(app)
    val clock: SyncClock = SyncClock({ System.currentTimeMillis() }, syncPrefs)
    val syncClient: SyncClient = SyncClient(syncPrefs)
    val sync: SyncRepository = SyncRepository(db, syncClient, syncPrefs)
    val scheduler: SyncScheduler = SyncScheduler(sync)

    // Every ledger write signals the scheduler, which debounces into one push (spec §4).
    // The repositories stay unaware that sync exists — they just report that something changed.
    val ledger: LedgerRepository = LedgerRepository(db, clock) { scheduler.signal() }
    val budgets: BudgetRepository = BudgetRepository(db, clock) { scheduler.signal() }
    val quips: QuipRepository = QuipRepository(db)
    // Declared above `garden`, which now takes it. Same declaration-order hazard as the clock
    // above: leaving it below would hand GardenRepository a null GardenPrefs.
    val prefs: GardenPrefs = GardenPrefs(app)
    val garden: GardenRepository = GardenRepository(db, ledger, prefs)
    val digests: DigestRepository = DigestRepository(db, ledger)
    val aiPrefs: AiPrefs = AiPrefs(app)

    init {
        // Stamped once, on the first launch this build ever sees. A device upgrading from an
        // older build gets "now", which is why StreakBaseline also consults the oldest
        // game_event — for an existing user that is the correct, earlier answer.
        prefs.recordFirstRunIfAbsent(System.currentTimeMillis())
    }

    /** Re-read per call rather than cached: the key can be entered at any moment from the
     *  settings screen, and a cached NoopLlmClient would keep the app silent until restart. */
    val llm: LlmClient get() = if (aiPrefs.hasKey) GeminiClient(aiPrefs) else NoopLlmClient

    /** Scope for work that outlives any one screen. Supervisor so one failed sprite decode
     *  cannot cancel the rest. */
    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Plant sprites, decoded per-island rather than all 48 at the first garden frame. */
    val spriteCache: SpriteCache = SpriteCache(app, appScope)

    /** House-level sprites (house_0..3), keyed by base name. Empty = not installed → house skipped. */
    val structures: Map<String, ImageBitmap> by lazy { SpriteLoader.loadStructures(app) }
}
