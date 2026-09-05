package com.expensegarden.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
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
import com.expensegarden.app.data.AiPrefs
import com.expensegarden.app.game.Tone
import com.expensegarden.app.sync.SyncPrefs
import com.expensegarden.app.sync.SyncRepository
import kotlinx.coroutines.launch

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
    var confirmingRestore by remember { mutableStateOf(false) }
    var restoreResult by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(saved, restoreResult) {
        pending = runCatching { sync.pendingCount() }.getOrNull()
        lastSuccessAt = syncPrefs.lastSuccessAt
    }

    // Scrolls and pads for the keyboard: Save must never be unreachable on this screen, since it
    // is the only way a key gets into the app (spec §3). Edge-to-edge on targetSdk 35 means
    // adjustResize no longer shrinks the content for us, and landscape has no room at all.
    Column(
        Modifier.statusBarsPadding().imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
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
                                onClick = { tone = option; saved = false },
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
                Text(syncStatus(lastSuccessAt, pending), style = MaterialTheme.typography.labelMedium)

                TextButton(
                    onClick = { confirmingRestore = true },
                    enabled = syncPrefs.isConfigured,
                    contentPadding = PaddingValues(horizontal = 8.dp),
                ) { Text("Restore from backup") }

                restoreResult?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        }

        Button(
            onClick = {
                aiPrefs.apiKey = key
                aiPrefs.tone = tone
                syncPrefs.serverUrl = url
                syncPrefs.token = token
                saved = true
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (saved) "Saved" else "Save") }

        if (saved) {
            Text(
                "The garden will speak on your next visit home, if anything changed.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }

    if (confirmingRestore) {
        AlertDialog(
            onDismissRequest = { confirmingRestore = false },
            title = { Text("Replace everything on this phone?") },
            text = {
                Text(
                    "This deletes the local ledger and garden, then rebuilds them from the " +
                        "server. It cannot be undone."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmingRestore = false
                    scope.launch {
                        val ok = runCatching { sync.restore() }.getOrDefault(false)
                        restoreResult = if (ok) "Restored from the server." else "Restore failed — check the url and token."
                    }
                }) { Text("Restore") }
            },
            dismissButton = { TextButton(onClick = { confirmingRestore = false }) { Text("Cancel") } },
        )
    }
}

/** Spec §5. The AI layer routes every failure to silence because nothing waits on it. A
 *  backup is the opposite: a dead one looks exactly like a healthy one until the day it
 *  matters, so this refuses to let that happen quietly. It never blocks or interrupts —
 *  it just tells the truth. */
private fun syncStatus(lastSuccessAt: Long, pending: Int?): String {
    val waiting = pending?.let { if (it == 0) "" else " · $it waiting" } ?: ""
    if (lastSuccessAt == 0L) return "Never backed up$waiting"
    val ageMs = System.currentTimeMillis() - lastSuccessAt
    val hours = ageMs / 3_600_000
    val stale = if (hours >= 24) "  ⚠ not backed up in ${hours / 24}d" else ""
    return when {
        ageMs < 60_000 -> "Backed up just now$waiting$stale"
        ageMs < 3_600_000 -> "Backed up ${ageMs / 60_000}m ago$waiting$stale"
        else -> "Backed up ${hours}h ago$waiting$stale"
    }
}
