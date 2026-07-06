package com.expensegarden.app.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp
import com.expensegarden.app.core.Money
import com.expensegarden.app.data.Regret
import com.expensegarden.app.data.TransactionEntity
import com.expensegarden.app.data.TxnRow
import com.expensegarden.app.gate.Severity
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun HomeScreen(vm: MainViewModel, onScan: () -> Unit, onManual: () -> Unit, onOpenDashboard: () -> Unit) {
    val header by vm.homeHeader.collectAsState()
    val pending by vm.pendingConfirm.collectAsState()
    val recent by vm.recent.collectAsState(initial = emptyList())
    val dateFmt = remember { DateTimeFormatter.ofPattern("dd MMM") }
    var regretTarget by remember { mutableStateOf<TxnRow?>(null) }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.statusBarsPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Card(Modifier.fillMaxWidth().clickable(onClick = onOpenDashboard)) {
                val h = header
                if (h == null) {
                    // Skeleton: fixed-height quiet block until Room's first emission (kills the ₹0.00 flash).
                    Column(Modifier.padding(16.dp).fillMaxWidth().height(72.dp).alpha(0.3f)) {
                        Text("This month", style = MaterialTheme.typography.labelMedium)
                    }
                } else {
                    Column(Modifier.padding(16.dp)) {
                        Text("This month", style = MaterialTheme.typography.labelMedium)
                        // Odometer tick: exact values only — old amount floats up, new rises from below.
                        AnimatedContent(
                            targetState = h.spentPaise,
                            transitionSpec = {
                                (slideInVertically(spring(dampingRatio = 0.8f, stiffness = 380f)) { it / 2 } +
                                    fadeIn(spring(stiffness = Spring.StiffnessMedium))) togetherWith
                                    (slideOutVertically(spring(stiffness = Spring.StiffnessMedium)) { -it / 2 } +
                                        fadeOut(spring(stiffness = Spring.StiffnessMedium)))
                            },
                            label = "monthSpent",
                        ) { spent ->
                            Text(Money.display(spent), style = MaterialTheme.typography.headlineMedium)
                        }
                        Text(
                            h.overallBudgetPaise?.let { "Budget: ${Money.display(it)} · ${hintLine(h.hint)}" }
                                ?: "Tap for the dashboard",
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
            }

            val pendingTxn = pending.firstOrNull()
            // Keep the last card content alive so the exit animation has something to show.
            var lastPending by remember { mutableStateOf<TransactionEntity?>(null) }
            LaunchedEffect(pendingTxn) { if (pendingTxn != null) lastPending = pendingTxn }
            AnimatedVisibility(
                visible = pendingTxn != null,
                enter = expandVertically(spring(dampingRatio = 0.85f, stiffness = 300f)) +
                    fadeIn(spring(stiffness = Spring.StiffnessMedium)),
                exit = shrinkVertically(spring(stiffness = Spring.StiffnessMedium)) +
                    fadeOut(spring(stiffness = Spring.StiffnessMedium)),
            ) {
                (pendingTxn ?: lastPending)?.let { txn ->
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
            }

            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 96.dp),
            ) {
                items(recent, key = { it.uuid }) { row ->
                    Row(
                        Modifier.fillMaxWidth().animateItem().clickable { regretTarget = row },
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column {
                            Text(row.payeeName, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "${row.categoryName} · ${dateFmt.format(Instant.ofEpochMilli(row.occurredAt).atZone(ZoneId.systemDefault()))}" +
                                    when (row.regret) {
                                        Regret.REGRET -> " · regret"
                                        Regret.WORTH_IT -> " · worth it"
                                        Regret.UNRATED -> ""
                                    },
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

    regretTarget?.let { row ->
        AlertDialog(
            onDismissRequest = { regretTarget = null },
            title = { Text("${Money.display(row.amountPaise)} — ${row.payeeName}") },
            text = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = row.regret == Regret.WORTH_IT,
                        onClick = { vm.setRegret(row.uuid, Regret.WORTH_IT); regretTarget = null },
                        label = { Text("Worth it") },
                    )
                    FilterChip(
                        selected = row.regret == Regret.REGRET,
                        onClick = { vm.setRegret(row.uuid, Regret.REGRET); regretTarget = null },
                        label = { Text("Regret") },
                    )
                }
            },
            confirmButton = { TextButton(onClick = { regretTarget = null }) { Text("Close") } },
        )
    }
}

private fun hintLine(s: Severity) = when (s) {
    Severity.OK -> "on pace"
    Severity.PACE_WARNING -> "ahead of pace"
    Severity.BREACH -> "over budget"
}
