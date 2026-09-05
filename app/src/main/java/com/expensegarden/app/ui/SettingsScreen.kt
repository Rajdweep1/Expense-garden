package com.expensegarden.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.expensegarden.app.data.AiPrefs
import com.expensegarden.app.game.Tone

/** The app's first settings surface (spec §3).
 *
 *  The key field is masked and never logged. It is written to AiPrefs, whose backing file is
 *  excluded from cloud backup and device transfer (spec §2.1) — nothing here may ever be
 *  written to Room, because Room replicates to the Phase 2 backend. */
@Composable
fun SettingsScreen(aiPrefs: AiPrefs, onBack: () -> Unit) {
    var key by remember { mutableStateOf(aiPrefs.apiKey) }
    var tone by remember { mutableStateOf(aiPrefs.tone) }
    var saved by remember { mutableStateOf(false) }

    Column(
        Modifier.statusBarsPadding().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("← garden") }
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
                            .selectable(selected = tone == option, onClick = { tone = option; saved = false })
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = tone == option, onClick = { tone = option; saved = false })
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

        Button(
            onClick = { aiPrefs.apiKey = key; aiPrefs.tone = tone; saved = true },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (saved) "Saved" else "Save") }

        if (saved) {
            Text(
                "The garden will speak on your next visit home, if anything changed.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
