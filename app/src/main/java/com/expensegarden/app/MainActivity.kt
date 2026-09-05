package com.expensegarden.app

import android.os.Bundle
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
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
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

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
        // One push per open, on top of the per-write signals (spec §4).
        (application as GardenApp).container.scheduler.signal()
        setContent {
            MaterialTheme {
                Surface { GardenNav(vm, dashVm, gardenVm, aiVm) }
            }
        }
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

    val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        val contents = result.contents ?: return@rememberLauncherForActivityResult
        val payee = UpiUriParser.parse(contents)
        if (payee == null) {
            Toast.makeText(context, "Not a UPI QR", Toast.LENGTH_SHORT).show()
        } else {
            vm.startScanDraft(payee)
            nav.navigate("entry")
        }
    }

    NavHost(
        navController = nav,
        startDestination = "home",
        enterTransition = { fadeIn(animationSpec = spring(stiffness = Spring.StiffnessMedium)) },
        exitTransition = { fadeOut(animationSpec = spring(stiffness = Spring.StiffnessMedium)) },
    ) {
        composable("home") {
            GardenHomeScreen(
                gardenVm = gardenVm,
                vm = vm,
                aiVm = aiVm,
                painter = remember {
                    val container = (context.applicationContext as GardenApp).container
                    if (container.sprites.isEmpty()) com.expensegarden.app.render.ProceduralPainter()
                    else com.expensegarden.app.render.SpritePainter(container.sprites)
                },
                structures = remember { (context.applicationContext as GardenApp).container.structures },
                onScan = {
                    scanLauncher.launch(
                        ScanOptions()
                            .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                            .setPrompt("Scan a UPI QR")
                            .setBeepEnabled(false)
                            .setOrientationLocked(true)
                    )
                },
                onManual = { vm.startManualDraft(); nav.navigate("entry") },
                onOpenDashboard = { nav.navigate("dashboard") },
                onOpenGreenhouse = { nav.navigate("greenhouse") },
                onOpenSettings = { nav.navigate("settings") },
            )
        }
        composable("dashboard") { DashboardScreen(vm = dashVm, onBack = { nav.popBackStack() }) }
        composable("settings") {
            SettingsScreen(
                aiPrefs = (context.applicationContext as GardenApp).container.aiPrefs,
                syncPrefs = (context.applicationContext as GardenApp).container.syncPrefs,
                sync = (context.applicationContext as GardenApp).container.sync,
                onBack = { nav.popBackStack() },
            )
        }
        composable("greenhouse") {
            GreenhouseScreen(
                gardenVm = gardenVm,
                aiVm = aiVm,
                painter = remember {
                    val container = (context.applicationContext as GardenApp).container
                    if (container.sprites.isEmpty()) com.expensegarden.app.render.ProceduralPainter()
                    else com.expensegarden.app.render.SpritePainter(container.sprites)
                },
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
