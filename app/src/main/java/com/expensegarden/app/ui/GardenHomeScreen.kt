package com.expensegarden.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.expensegarden.app.core.Money
import com.expensegarden.app.data.Regret
import com.expensegarden.app.data.TransactionEntity
import com.expensegarden.app.data.TxnRow
import com.expensegarden.app.gate.Severity
import com.expensegarden.app.render.GardenCanvas
import com.expensegarden.app.render.PlantPainter
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.launch

@Composable
fun GardenHomeScreen(
    gardenVm: GardenViewModel,
    vm: MainViewModel,
    painter: PlantPainter,
    onScan: () -> Unit,
    onManual: () -> Unit,
    onOpenDashboard: () -> Unit,
    onOpenGreenhouse: () -> Unit,
) {
    val garden by gardenVm.garden.collectAsState()
    val header by vm.homeHeader.collectAsState()
    val pending by vm.pendingConfirm.collectAsState()
    val scope = rememberCoroutineScope()
    var plantTarget by remember { mutableStateOf<TxnRow?>(null) }
    val dateFmt = remember { DateTimeFormatter.ofPattern("dd MMM") }

    Box(Modifier.fillMaxSize()) {
        garden?.let { g ->
            GardenCanvas(
                state = g,
                painter = painter,
                modifier = Modifier.fillMaxSize(),
                onPlantTap = { uuid -> scope.launch { plantTarget = gardenVm.plantRow(uuid) } },
            )
        }

        // Translucent stats strip — the same homeHeader the 1B home used.
        Surface(
            color = Color.White.copy(alpha = .82f),
            modifier = Modifier.statusBarsPadding().padding(horizontal = 12.dp, vertical = 6.dp)
                .fillMaxWidth().align(Alignment.TopCenter).clickable(onClick = onOpenDashboard),
            shape = MaterialTheme.shapes.medium,
        ) {
            Row(
                Modifier.padding(horizontal = 14.dp, vertical = 10.dp).fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically,
            ) {
                val h = header
                if (h == null) Text(" ", style = MaterialTheme.typography.titleMedium)
                else {
                    Text(Money.display(h.spentPaise), style = MaterialTheme.typography.titleMedium)
                    val streak = garden?.streakDays ?: 0
                    val streakSuffix = if (streak > 0) " · 🌱${streak}d" else ""   // the streaks-lite counter (spec §1)
                    Text(
                        (h.overallBudgetPaise?.let { "${Money.display(it)} · ${gardenHint(h.hint)}" } ?: "dashboard →") + streakSuffix,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
        }
        TextButton(
            onClick = onOpenGreenhouse,
            modifier = Modifier.align(Alignment.TopStart).statusBarsPadding().padding(start = 12.dp, top = 64.dp),
        ) { Text("🏡 greenhouse") }

        Column(Modifier.align(Alignment.BottomCenter).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val pendingTxn = pending.firstOrNull()
            var lastPending by remember { mutableStateOf<TransactionEntity?>(null) }
            LaunchedEffect(pendingTxn) { if (pendingTxn != null) lastPending = pendingTxn }
            AnimatedVisibility(
                visible = pendingTxn != null,
                enter = expandVertically(spring(dampingRatio = 0.85f, stiffness = 300f)) + fadeIn(spring(stiffness = Spring.StiffnessMedium)),
                exit = shrinkVertically(spring(stiffness = Spring.StiffnessMedium)) + fadeOut(spring(stiffness = Spring.StiffnessMedium)),
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
            Row(Modifier.align(Alignment.CenterHorizontally), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ExtendedFloatingActionButton(onClick = onScan) { Text("Scan & pay") }
                ExtendedFloatingActionButton(onClick = onManual) { Text("Log manually") }
            }
        }
    }

    plantTarget?.let { row ->
        AlertDialog(
            onDismissRequest = { plantTarget = null },
            title = { Text("${Money.display(row.amountPaise)} — ${row.payeeName}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${row.categoryName} · ${dateFmt.format(Instant.ofEpochMilli(row.occurredAt).atZone(ZoneId.systemDefault()))}")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = row.regret == Regret.WORTH_IT,
                            onClick = { vm.setRegret(row.uuid, Regret.WORTH_IT); plantTarget = null },
                            label = { Text("Worth it") })
                        FilterChip(selected = row.regret == Regret.REGRET,
                            onClick = { vm.setRegret(row.uuid, Regret.REGRET); plantTarget = null },
                            label = { Text("Regret") })
                    }
                }
            },
            confirmButton = { TextButton(onClick = { plantTarget = null }) { Text("Close") } },
        )
    }
}

private fun gardenHint(s: Severity) = when (s) {
    Severity.OK -> "on pace"
    Severity.PACE_WARNING -> "ahead of pace"
    Severity.BREACH -> "over budget"
}
