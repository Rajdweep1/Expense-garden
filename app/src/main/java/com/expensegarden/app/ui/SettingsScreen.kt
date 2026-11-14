package com.expensegarden.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.ui.platform.LocalContext
import com.expensegarden.app.capture.UpiApps
import com.expensegarden.app.capture.UpiScanner
import com.expensegarden.app.data.UpiPrefs
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.expensegarden.app.ai.GeminiClient
import com.expensegarden.app.ai.KeyCheck
import com.expensegarden.app.data.AiPrefs
import com.expensegarden.app.data.TxnStatus
import com.expensegarden.app.game.Tone
import com.expensegarden.app.sync.RestorePlan
import com.expensegarden.app.sync.SyncEndpoint
import com.expensegarden.app.sync.SyncPrefs
import com.expensegarden.app.sync.SyncRepository
import com.expensegarden.app.sync.SyncResult
import com.expensegarden.app.sync.SyncSnapshot
import com.expensegarden.app.sync.syncStatusLine
import kotlinx.coroutines.launch
import java.time.ZoneId

/** The app's settings surface (1D spec §3, 2A spec §5).
 *
 *  Both secrets — the Gemini key and the sync token — are masked, never logged, and written to
 *  prefs files that are excluded from cloud backup and device transfer. Nothing here may ever
 *  be written to Room, because Room replicates to the server. */
@Composable
fun SettingsScreen(
    aiPrefs: AiPrefs,
    syncPrefs: SyncPrefs,
    sync: SyncRepository,
    upiPrefs: UpiPrefs,
    /** A saved key or voice takes effect now: Settings is in the same activity, so returning
     *  from it is no foreground (review R16). */
    onAiChanged: () -> Unit,
    onBack: () -> Unit,
) {
    // `remember`, not `rememberSaveable`, on purpose: a secret does not belong in the saved-state
    // Bundle, so an unsaved edit is dropped on rotation and re-seeded from prefs. Deliberate.
    var key by remember { mutableStateOf(aiPrefs.apiKey) }
    var tone by remember { mutableStateOf(aiPrefs.tone) }
    var url by remember { mutableStateOf(syncPrefs.serverUrl) }
    var token by remember { mutableStateOf(syncPrefs.token) }
    var saved by remember { mutableStateOf(false) }

    var pending by remember { mutableStateOf<Int?>(null) }
    var lastSuccessAt by remember { mutableStateOf(syncPrefs.lastSuccessAt) }
    var lastFailure by remember { mutableStateOf(syncPrefs.lastFailure) }
    var lastFailureAt by remember { mutableStateOf(syncPrefs.lastFailureAt) }
    var statusRefresh by remember { mutableStateOf(0) }
    var keyCheck by remember { mutableStateOf<String?>(null) }
    // The plan and the very snapshot it describes: confirming restores what was counted (ruling 1).
    var restoreOffer by remember { mutableStateOf<Triple<RestorePlan, SyncSnapshot, SyncEndpoint>?>(null) }
    var restoreResult by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    SystemBarIcons(darkIcons = !isSystemInDarkTheme())

    LaunchedEffect(statusRefresh, restoreResult) {
        pending = runCatching { sync.pendingCount() }.getOrNull()
        lastSuccessAt = syncPrefs.lastSuccessAt
        lastFailure = syncPrefs.lastFailure
        lastFailureAt = syncPrefs.lastFailureAt
    }

    // Scrolls and pads for the keyboard: Save must never be unreachable on this screen, since it
    // is the only way a key gets into the app (spec §3). Edge-to-edge on targetSdk 35 means
    // adjustResize no longer shrinks the content for us, and landscape has no room at all.
    // safeDrawing is the union of the system bars, the cutout and the IME, so this one inset holds
    // the keyboard and the navigation bar off the content at once and cannot double-pad either.
    Column(
        Modifier.windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("← garden") }
            Text("Settings", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(start = 8.dp))
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Gemini API key", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Free tier. Without a key the garden simply stays quiet — everything else " +
                        "works exactly as it does now.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = key,
                    onValueChange = { key = it; saved = false },
                    label = { Text("key") },
                    singleLine = true,
                    // Masking is visual; this tells the IME too, so it does not learn the key.
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                keyCheck?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Voice", style = MaterialTheme.typography.titleMedium)
                Text(
                    "The boundaries never change — necessities are never mocked, and it roasts " +
                        "the choice, not you. Only the delivery changes.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Tone.values().forEach { option ->
                    Row(
                        Modifier.fillMaxWidth()
                            .selectable(
                                selected = tone == option,
                                role = Role.RadioButton,        // one TalkBack item per option, not two
                                // Kept on tap, not on Save, and refreshed at once: the quip refresh
                                // is otherwise throttled to a day (review R16).
                                onClick = {
                                    if (option != tone) {
                                        tone = option
                                        aiPrefs.tone = option
                                        aiPrefs.lastQuipRefreshAt = 0L
                                        onAiChanged()
                                    }
                                },
                            )
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = tone == option, onClick = null)
                        Text(
                            when (option) {
                                Tone.SHARP -> "Sharp but fair"
                                Tone.SAVAGE -> "Savage"
                                Tone.GENTLE -> "Gentle"
                            },
                            Modifier.padding(start = 8.dp),
                        )
                    }
                }
            }
        }

        UpiAppCard(upiPrefs)

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Backup", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Your phone stays the source of truth. This copies the ledger and the " +
                        "garden's event log to your own server, so a lost phone is recoverable.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it; saved = false },
                    label = { Text("server url") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it; saved = false },
                    label = { Text("token") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    syncStatusLine(syncPrefs.isConfigured, lastSuccessAt, lastFailure, lastFailureAt, pending, System.currentTimeMillis()),
                    style = MaterialTheme.typography.labelMedium,
                )

                TextButton(
                    onClick = {
                        // What is typed, not what is saved: Save would push this phone's rows into
                        // the account first (users spec, finding 1). The endpoint travels with the
                        // snapshot so an edit while the dialog is open can't be saved in its place.
                        val endpoint = SyncEndpoint.of(url, token)
                        scope.launch {
                            restoreResult = "Checking the server…"
                            when (val r = sync.fetchSnapshot(endpoint)) {
                                is SyncResult.Failed -> restoreResult = "Couldn't read the server: ${r.failure.label}"
                                is SyncResult.Ok -> {
                                    val (phoneTxns, unsynced) = sync.localCounts()
                                    val snap = r.value
                                    val payments = snap.txns.filter { it.status != TxnStatus.DISCARDED }
                                    restoreOffer = Triple(
                                        RestorePlan.of(
                                            phoneTxns, unsynced, payments.size, payments.maxOfOrNull { it.occurredAt },
                                            ZoneId.systemDefault(),
                                        ),
                                        snap,
                                        endpoint,
                                    )
                                    restoreResult = null
                                }
                            }
                        }
                    },
                    enabled = url.isNotBlank() && token.isNotBlank(),
                    contentPadding = PaddingValues(horizontal = 8.dp),
                ) { Text("Restore from backup") }

                restoreResult?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        }

        Button(
            onClick = {
                // Compared and checked as stored: AiPrefs trims the key.
                val entered = key.trim()
                val keyChanged = entered != aiPrefs.apiKey
                aiPrefs.apiKey = entered
                syncPrefs.serverUrl = url
                syncPrefs.token = token
                saved = true
                // Before the checks, not after: leaving Settings cancels this scope, and a saved key
                // must take effect at once either way (cleanup plan, ruling 3).
                if (keyChanged) onAiChanged()
                // Save tells the truth about what was saved (review R15, R16).
                scope.launch {
                    if (keyChanged && entered.isNotBlank()) {
                        keyCheck = "Checking the key…"
                        keyCheck = when (GeminiClient(aiPrefs).checkKey(entered)) {
                            KeyCheck.WORKS -> "Key works"
                            KeyCheck.REJECTED -> "Key rejected — check it"
                            KeyCheck.MODEL_MISSING -> "Key works, but this app's Gemini model is gone — update the app"
                            KeyCheck.UNREACHABLE -> "Couldn't reach Gemini — saved anyway"
                        }
                    } else if (entered.isBlank()) {
                        keyCheck = null
                    }
                    if (syncPrefs.isConfigured) sync.checkConnection()
                    statusRefresh += 1
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (saved) "Saved" else "Save") }

    }

    restoreOffer?.let { (plan, snap, endpoint) ->
        AlertDialog(
            onDismissRequest = { restoreOffer = null },
            title = { Text(if (plan is RestorePlan.Refuse) "Not restoring" else "Replace everything on this phone?") },
            text = { Text(plan.message) },
            confirmButton = {
                if (plan is RestorePlan.Refuse) {
                    TextButton(onClick = { restoreOffer = null }) { Text("OK") }
                } else {
                    TextButton(onClick = {
                        restoreOffer = null
                        scope.launch {
                            val ok = runCatching { sync.restore(snap, endpoint) }.getOrDefault(false)
                            restoreResult = if (ok) "Restored from the server." else "Restore failed."
                            if (ok) { saved = true; statusRefresh += 1 }
                        }
                    }) { Text("Restore") }
                }
            },
            dismissButton = if (plan is RestorePlan.Refuse) null else {
                { TextButton(onClick = { restoreOffer = null }) { Text("Cancel") } }
            },
        )
    }
}

/** UPI apps refuse person-to-person payments started by another app, so a person's QR opens the
 *  app chosen here; each entry says whether it opens on its scanner (UpiScanner). Saved on tap. */
@Composable
private fun UpiAppCard(upiPrefs: UpiPrefs) {
    val context = LocalContext.current
    val apps = remember { UpiApps.installed(context) }
    var chosen by remember { mutableStateOf(upiPrefs.personAppPackage) }
    var menuOpen by remember { mutableStateOf(false) }
    val chosenApp = apps.firstOrNull { it.pkg == chosen }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("UPI app for people", style = MaterialTheme.typography.titleMedium)
            Text(
                "UPI apps won't take a payment to a person from another app, so a person's QR " +
                    "opens this app and you pay there.",
                style = MaterialTheme.typography.bodySmall,
            )
            Box {
                OutlinedButton(onClick = { menuOpen = true }, enabled = apps.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
                    Text(chosenApp?.label ?: if (apps.isEmpty()) "No UPI app installed" else "Not chosen — asks the first time")
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("None — ask next time") },
                        onClick = { upiPrefs.personAppPackage = null; chosen = null; menuOpen = false },
                    )
                    apps.forEach { app ->
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(app.label)
                                    Text(UpiScanner.hint(app.pkg), style = MaterialTheme.typography.bodySmall)
                                }
                            },
                            onClick = { upiPrefs.personAppPackage = app.pkg; chosen = app.pkg; menuOpen = false },
                        )
                    }
                }
            }
            chosenApp?.let { Text(UpiScanner.hint(it.pkg), style = MaterialTheme.typography.labelMedium) }
        }
    }
}
