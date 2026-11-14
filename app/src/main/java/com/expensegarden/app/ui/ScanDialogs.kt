package com.expensegarden.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.expensegarden.app.capture.UpiApp
import com.expensegarden.app.capture.UpiScanner
import com.expensegarden.app.gate.PrePayDecision

/** The one card before paying, only when the month is already over (pay-first spec). A custom
 *  Dialog rather than AlertDialog because the emphasis inverts: the quiet option is the
 *  recommended one, and Material's confirm slot always renders loudest (the old gate's shell). */
@Composable
fun PrePayDialog(
    card: PrePayDecision.Card,
    payee: String,
    quip: String,
    onPay: () -> Unit,
    onNotNow: () -> Unit,
    onDismiss: () -> Unit,
) {
    // One answer per card: a second tap on Pay would hand off twice.
    var answered by remember(card) { mutableStateOf(false) }
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surface) {
            Column(
                Modifier.padding(24.dp).fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(PrePayLabels.headline(card, payee), style = MaterialTheme.typography.titleLarge)
                if (quip.isNotBlank()) Text(quip, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                Button(enabled = !answered, onClick = { answered = true; onNotNow() }, modifier = Modifier.fillMaxWidth()) { Text("Not now") }
                TextButton(enabled = !answered, onClick = { answered = true; onPay() }, modifier = Modifier.fillMaxWidth()) { Text("Pay") }
            }
        }
    }
}

/** The first person QR asks which UPI app to open; the pick is saved (Settings changes it). */
@Composable
fun UpiAppPickerDialog(apps: List<UpiApp>, onPick: (UpiApp) -> Unit, onDismiss: () -> Unit) {
    var picked by remember(apps) { mutableStateOf(false) }   // one answer per picker
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Pay people with…") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    "Pick the UPI app that opens for people's QRs — you can change it in Settings.",
                    style = MaterialTheme.typography.bodySmall,
                )
                apps.forEach { app ->
                    TextButton(
                        onClick = { if (!picked) { picked = true; onPick(app) } },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(Modifier.fillMaxWidth()) {
                            Text(app.label)
                            Text(UpiScanner.hint(app.pkg), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Not now") } },
    )
}
