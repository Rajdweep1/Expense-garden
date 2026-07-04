package com.expensegarden.app

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.expensegarden.app.capture.UpiUriParser
import com.expensegarden.app.ui.EntryScreen
import com.expensegarden.app.ui.HomeScreen
import com.expensegarden.app.ui.MainViewModel
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels {
        MainViewModel.factory((application as GardenApp).container)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface { GardenNav(vm) }
            }
        }
    }
}

@Composable
private fun GardenNav(vm: MainViewModel) {
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

    NavHost(navController = nav, startDestination = "home") {
        composable("home") {
            HomeScreen(
                vm = vm,
                onScan = {
                    scanLauncher.launch(
                        ScanOptions()
                            .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                            .setPrompt("Scan a UPI QR")
                            .setBeepEnabled(false)
                            .setOrientationLocked(true)
                    )
                },
                onManual = {
                    vm.startManualDraft()
                    nav.navigate("entry")
                },
            )
        }
        composable("entry") {
            EntryScreen(vm = vm, onDone = { nav.popBackStack("home", inclusive = false) })
        }
    }
}
