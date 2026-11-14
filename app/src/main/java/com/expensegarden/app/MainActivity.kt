package com.expensegarden.app

import android.os.Bundle
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.platform.LocalLifecycleOwner
import com.expensegarden.app.capture.UpiApp
import com.expensegarden.app.gate.PrePayDecision
import com.expensegarden.app.ui.HandoffOutcome
import com.expensegarden.app.ui.PrePayDialog
import com.expensegarden.app.ui.PrePayResult
import com.expensegarden.app.ui.ScanHandoff
import com.expensegarden.app.ui.UpiAppPickerDialog
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.expensegarden.app.capture.UpiUriParser
import com.expensegarden.app.ui.AiViewModel
import com.expensegarden.app.ui.DashboardScreen
import com.expensegarden.app.ui.DashboardViewModel
import com.expensegarden.app.ui.EntryScreen
import com.expensegarden.app.ui.GardenHomeScreen
import com.expensegarden.app.ui.GardenViewModel
import com.expensegarden.app.ui.GreenhouseScreen
import com.expensegarden.app.ui.MainViewModel
import com.expensegarden.app.ui.SettingsScreen
import com.expensegarden.app.ui.theme.GardenTheme
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels {
        MainViewModel.factory((application as GardenApp).container)
    }
    private val dashVm: DashboardViewModel by viewModels {
        DashboardViewModel.factory((application as GardenApp).container)
    }
    private val gardenVm: GardenViewModel by viewModels {
        GardenViewModel.factory((application as GardenApp).container)
    }
    private val aiVm: AiViewModel by viewModels {
        AiViewModel.factory((application as GardenApp).container)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // targetSdk 35 enforces edge-to-edge anyway; saying so explicitly keeps the layout's
        // inset handling correct if that target ever moves back.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        // One push per open, on top of the per-write signals (spec §4).
        (application as GardenApp).container.scheduler.signal()
        setContent {
            GardenTheme {
                Surface { GardenNav(vm, dashVm, gardenVm, aiVm) }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // Every foreground, so a month that turns while the app sits in memory still carries its
        // budgets and closes the old month (GardenViewModel.onForeground), and a new day still
        // gets its card and digest (AiViewModel.onForeground).
        // The AI job waits for the garden's: started together, the digest could read the month
        // before its month.closed existed, and the 1st's postcard waited a day (cleanup plan, ruling 4).
        val garden = gardenVm.onForeground()
        lifecycleScope.launch { garden.join(); aiVm.onForeground() }
    }
}

@Composable
private fun GardenNav(
    vm: MainViewModel,
    dashVm: DashboardViewModel,
    gardenVm: GardenViewModel,
    aiVm: AiViewModel,
) {
    val nav = rememberNavController()
    val context = LocalContext.current

    val scope = rememberCoroutineScope()
    val upiPrefs = remember { (context.applicationContext as GardenApp).container.upiPrefs }
    // Pay first: the card and the first-time picker are dialogs over home, so an OK month goes
    // from the scanner straight to the UPI app with no screen in between (plan Q11).
    var prePayCard by remember { mutableStateOf<PrePayResult?>(null) }
    var picker by remember { mutableStateOf<Pair<PrePayResult, List<UpiApp>>?>(null) }

    fun handOff(r: PrePayResult) {
        val out = ScanHandoff.start(context, vm, upiPrefs, r)
        if (out is HandoffOutcome.NeedPicker) picker = r to out.apps
    }

    val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        val contents = result.contents ?: return@rememberLauncherForActivityResult
        val payee = UpiUriParser.parse(contents)
        if (payee == null) {
            Toast.makeText(context, "Not a UPI QR", Toast.LENGTH_SHORT).show()
        } else {
            scope.launch {
                val r = vm.prePay(payee)
                if (r.decision is PrePayDecision.Card) prePayCard = r else handOff(r)
            }
        }
    }

    // Back from the UPI app: the payment just handed off opens its Log it form (spec D8). The
    // scanner's own return resumes too, but no payment is marked yet at that point.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event != Lifecycle.Event.ON_RESUME) return@LifecycleEventObserver
            vm.refreshAwaiting()
            // Already on a form (a manual entry typed while the scan was being checked): leave it;
            // the payment keeps its marker and opens on the next resume (code review, 2026-11-14).
            if (nav.currentDestination?.route == "entry") return@LifecycleEventObserver
            vm.takePaymentToOpen()?.let { id ->
                vm.startAfterPayDraft(id)
                nav.navigate("entry")
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    prePayCard?.let { r ->
        val card = r.decision as PrePayDecision.Card
        PrePayDialog(
            card = card, payee = r.payee.name ?: r.payee.vpa, quip = r.quip,
            onPay = { prePayCard = null; handOff(r) },
            onNotNow = { prePayCard = null; if (card.recordsDodge) vm.recordDodge(r) },
            onDismiss = { prePayCard = null },
        )
    }
    picker?.let { (r, apps) ->
        UpiAppPickerDialog(
            apps = apps,
            onPick = { app ->
                picker = null
                upiPrefs.personAppPackage = app.pkg
                if (!ScanHandoff.openForPerson(context, vm, r, app)) {
                    Toast.makeText(context, "Couldn't open ${app.label}", Toast.LENGTH_LONG).show()
                }
            },
            onDismiss = { picker = null },
        )
    }

    NavHost(
        navController = nav,
        startDestination = "home",
        enterTransition = { fadeIn(animationSpec = spring(stiffness = Spring.StiffnessMedium)) },
        exitTransition = { fadeOut(animationSpec = spring(stiffness = Spring.StiffnessMedium)) },
    ) {
        composable("home") {
            // The garden canvas never goes dark, so neither does the chrome drawn over it. Under
            // the system theme, dark mode put light text on the strip's white and the links on
            // the sky: 1.21:1 and 1.41:1 (review, 2026-10-11). GardenHomeScreen pins dark
            // status-bar icons for the same reason.
            GardenTheme(dark = false) {
                GardenHomeScreen(
                    gardenVm = gardenVm,
                    vm = vm,
                    aiVm = aiVm,
                    painter = remember {
                        val container = (context.applicationContext as GardenApp).container
                        // No isEmpty() check: asking cost the whole 48 MB decode, on the main thread,
                        // inside composition. SpritePainter already falls back to procedural art per
                        // plant on a miss, so an uninstalled pack renders exactly as it did before.
                        com.expensegarden.app.render.SpritePainter(container.spriteCache.sprites)
                    },
                    spriteCache = (context.applicationContext as GardenApp).container.spriteCache,
                    structures = remember { (context.applicationContext as GardenApp).container.structures },
                    onScan = {
                        scanLauncher.launch(
                            ScanOptions()
                                .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                                .setPrompt("Scan a UPI QR")
                                .setBeepEnabled(false)
                                .setOrientationLocked(false)
                        )
                    },
                    onManual = { vm.startManualDraft(); nav.navigate("entry") },
                    onOpenAwaiting = { id -> vm.startAfterPayDraft(id); nav.navigate("entry") },
                    onOpenDashboard = { nav.navigate("dashboard") },
                    onOpenGreenhouse = { nav.navigate("greenhouse") },
                    onOpenSettings = { nav.navigate("settings") },
                )
            }
        }
        composable("dashboard") { DashboardScreen(vm = dashVm, onBack = { nav.popBackStack() }) }
        composable("settings") {
            SettingsScreen(
                aiPrefs = (context.applicationContext as GardenApp).container.aiPrefs,
                syncPrefs = (context.applicationContext as GardenApp).container.syncPrefs,
                sync = (context.applicationContext as GardenApp).container.sync,
                upiPrefs = (context.applicationContext as GardenApp).container.upiPrefs,
                onAiChanged = aiVm::onForeground,
                onBack = { nav.popBackStack() },
            )
        }
        composable("greenhouse") {
            GreenhouseScreen(
                gardenVm = gardenVm,
                aiVm = aiVm,
                painter = remember {
                    val container = (context.applicationContext as GardenApp).container
                    // No isEmpty() check: asking cost the whole 48 MB decode, on the main thread,
                    // inside composition. SpritePainter already falls back to procedural art per
                    // plant on a miss, so an uninstalled pack renders exactly as it did before.
                    com.expensegarden.app.render.SpritePainter(container.spriteCache.sprites)
                },
                spriteCache = (context.applicationContext as GardenApp).container.spriteCache,
                onBack = { nav.popBackStack() },
            )
        }
        composable(
            "entry",
            // Entry rises like a payment sheet: springy in, brisk non-bouncy out.
            enterTransition = {
                slideIntoContainer(
                    AnimatedContentTransitionScope.SlideDirection.Up,
                    animationSpec = spring(
                        dampingRatio = 0.8f,
                        stiffness = 380f,
                        visibilityThreshold = IntOffset.VisibilityThreshold,
                    ),
                )
            },
            exitTransition = {
                slideOutOfContainer(
                    AnimatedContentTransitionScope.SlideDirection.Down,
                    animationSpec = spring(
                        stiffness = Spring.StiffnessMedium,
                        visibilityThreshold = IntOffset.VisibilityThreshold,
                    ),
                )
            },
        ) {
            EntryScreen(vm = vm, onDone = { nav.popBackStack("home", inclusive = false) })
        }
    }
}
