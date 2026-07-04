package com.expensegarden.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.expensegarden.app.core.Money
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun HomeScreen(vm: MainViewModel, onScan: () -> Unit, onManual: () -> Unit) {
    val monthSpent by vm.monthSpent.collectAsState()
    val budget by vm.monthBudget.collectAsState()
    val pending by vm.pendingConfirm.collectAsState()
    val recent by vm.recent.collectAsState(initial = emptyList())
    var budgetDialogOpen by remember { mutableStateOf(false) }
    val dateFmt = remember { DateTimeFormatter.ofPattern("dd MMM") }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("This month", style = MaterialTheme.typography.labelMedium)
                    Text(Money.display(monthSpent), style = MaterialTheme.typography.headlineMedium)
                    val b = budget
                    TextButton(onClick = { budgetDialogOpen = true }) {
                        Text(if (b == null) "Set a budget" else "Budget: ${Money.display(b.amountPaise)}")
                    }
                }
            }

            pending.firstOrNull()?.let { txn ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Did ${Money.display(txn.amountPaise)} go through?", style = MaterialTheme.typography.titleMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Button(onClick = { vm.confirmPending(txn.uuid) }) { Text("Log it") }
                            OutlinedButton(onClick = { vm.discardPending(txn.uuid) }) { Text("Discard") }
                        }
                    }
                }
            }

            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 96.dp),
            ) {
                items(recent, key = { it.uuid }) { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column {
                            Text(row.payeeName, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "${row.categoryName} · ${dateFmt.format(Instant.ofEpochMilli(row.occurredAt).atZone(ZoneId.systemDefault()))}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Text(Money.display(row.amountPaise), style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        }

        Row(
            Modifier.align(Alignment.BottomCenter).padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ExtendedFloatingActionButton(onClick = onScan) { Text("Scan & pay") }
            ExtendedFloatingActionButton(onClick = onManual) { Text("Log manually") }
        }
    }

    if (budgetDialogOpen) {
        var text by remember { mutableStateOf(budget?.let { Money.intentAmount(it.amountPaise) } ?: "") }
        AlertDialog(
            onDismissRequest = { budgetDialogOpen = false },
            title = { Text("Monthly budget") },
            text = {
                OutlinedTextField(value = text, onValueChange = { text = it }, label = { Text("Amount (₹)") })
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.setOverallBudget(Money.parseToPaise(text))
                    budgetDialogOpen = false
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { budgetDialogOpen = false }) { Text("Cancel") } },
        )
    }
}
