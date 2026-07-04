package com.expensegarden.app.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.expensegarden.app.capture.UpiIntents
import com.expensegarden.app.core.Money
import com.expensegarden.app.gate.Severity
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EntryScreen(vm: MainViewModel, onDone: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val draft by vm.draft.collectAsState()
    val categories by vm.categories.collectAsState()
    var gate by remember { mutableStateOf<GatePrompt?>(null) }
    var categoryMenuOpen by remember { mutableStateOf(false) }

    fun fireAndFinish(amountPaise: Long, severity: Severity) {
        scope.launch {
            vm.savePendingFromDraft(amountPaise, severity)
            UpiIntents.launchPayment(context, draft.vpa!!, draft.payeeName, amountPaise, draft.note.ifBlank { null })
            onDone()
        }
    }

    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            if (draft.fromScan) "Paying ${draft.payeeName}" else "Log an expense",
            style = MaterialTheme.typography.headlineSmall,
        )

        OutlinedTextField(
            value = draft.amountText,
            onValueChange = { vm.draft.value = draft.copy(amountText = it) },
            label = { Text("Amount (₹)") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.fillMaxWidth(),
        )

        if (!draft.fromScan) {
            OutlinedTextField(
                value = draft.payeeName,
                onValueChange = { vm.draft.value = draft.copy(payeeName = it) },
                label = { Text("Paid to") },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        ExposedDropdownMenuBox(expanded = categoryMenuOpen, onExpandedChange = { categoryMenuOpen = it }) {
            OutlinedTextField(
                value = categories.find { it.id == draft.categoryId }?.name ?: "Pick a category",
                onValueChange = {},
                readOnly = true,
                label = { Text("Category") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = categoryMenuOpen) },
                modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable, true).fillMaxWidth(),
            )
            ExposedDropdownMenu(expanded = categoryMenuOpen, onDismissRequest = { categoryMenuOpen = false }) {
                categories.forEach { cat ->
                    DropdownMenuItem(
                        text = { Text(if (cat.parentId == null) cat.name else "   ${cat.name}") },
                        onClick = {
                            vm.draft.value = draft.copy(categoryId = cat.id)
                            categoryMenuOpen = false
                        },
                    )
                }
            }
        }

        OutlinedTextField(
            value = draft.note,
            onValueChange = { vm.draft.value = draft.copy(note = it) },
            label = { Text("Note (optional)") },
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(8.dp))

        Button(
            onClick = {
                val amountPaise = Money.parseToPaise(draft.amountText)
                when {
                    amountPaise == null -> Toast.makeText(context, "Enter a valid amount", Toast.LENGTH_SHORT).show()
                    draft.categoryId == null -> Toast.makeText(context, "Pick a category", Toast.LENGTH_SHORT).show()
                    draft.fromScan -> scope.launch {
                        val prompt = vm.prepareGate(amountPaise)
                        if (prompt.severity == Severity.OK) fireAndFinish(amountPaise, prompt.severity)
                        else gate = prompt
                    }
                    else -> {
                        vm.saveManualFromDraft(amountPaise)
                        onDone()
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (draft.fromScan) "Continue to pay" else "Log it")
        }
    }

    gate?.let { prompt ->
        val amountPaise = Money.parseToPaise(draft.amountText) ?: return@let
        AlertDialog(
            onDismissRequest = { gate = null },
            title = { Text(if (prompt.severity == Severity.BREACH) "Over budget" else "Ahead of pace") },
            text = { Text(prompt.quip) },
            confirmButton = {
                TextButton(onClick = {
                    gate = null
                    fireAndFinish(amountPaise, prompt.severity)
                }) { Text("Pay anyway") }
            },
            dismissButton = {
                TextButton(onClick = {
                    vm.recordDodge(amountPaise)
                    gate = null
                    onDone()
                }) { Text("Nope, saved") }
            },
        )
    }
}
