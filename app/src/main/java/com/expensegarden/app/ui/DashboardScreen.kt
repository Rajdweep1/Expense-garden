package com.expensegarden.app.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.expensegarden.app.core.Money
import com.expensegarden.app.gate.Severity
import com.expensegarden.app.stats.ScopeStat

/** Which budget the dialog edits: overall (null) or a category. */
private data class BudgetTarget(val categoryId: Long?, val name: String, val currentPaise: Long?)

@Composable
fun DashboardScreen(vm: DashboardViewModel) {
    val stats by vm.stats.collectAsState()
    var target by remember { mutableStateOf<BudgetTarget?>(null) }

    Column(Modifier.statusBarsPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("This month", style = MaterialTheme.typography.headlineSmall)

        val s = stats
        if (s == null) {
            // Skeleton until Room's first emission — same trick as home.
            Card(Modifier.fillMaxWidth().height(120.dp).alpha(0.3f)) {}
        } else {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    // Odometer tick shared with home: exact values only, never interpolate money.
                    AnimatedContent(
                        targetState = s.spentPaise,
                        transitionSpec = {
                            (slideInVertically(spring(dampingRatio = 0.8f, stiffness = 380f)) { it / 2 } +
                                fadeIn(spring(stiffness = Spring.StiffnessMedium))) togetherWith
                                (slideOutVertically(spring(stiffness = Spring.StiffnessMedium)) { -it / 2 } +
                                    fadeOut(spring(stiffness = Spring.StiffnessMedium)))
                        },
                        label = "dashSpent",
                    ) { spent -> Text(Money.display(spent), style = MaterialTheme.typography.headlineMedium) }

                    TextButton(onClick = { target = BudgetTarget(null, "overall", s.overallBudgetPaise) }) {
                        Text(
                            if (s.overallBudgetPaise == null) "Set overall budget"
                            else "Budget: ${Money.display(s.overallBudgetPaise!!)}"
                        )
                    }
                    Text("Projected: ${Money.display(s.projectedPaise)} by month end", style = MaterialTheme.typography.bodyMedium)
                    s.perDayPaise?.let {
                        Text("${Money.display(it)}/day keeps you under", style = MaterialTheme.typography.bodyMedium)
                    }
                    Text(
                        severityLine(s.overallSeverity),
                        color = severityColor(s.overallSeverity),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }

            Text("Budgets", style = MaterialTheme.typography.titleMedium)
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 32.dp)) {
                items(s.rows, key = { it.categoryId ?: -1L }) { row ->
                    CategoryRow(row) { target = BudgetTarget(row.categoryId, row.name, row.budgetPaise) }
                }
            }
        }
    }

    target?.let { t ->
        var text by remember(t) { mutableStateOf(t.currentPaise?.let { Money.intentAmount(it) } ?: "") }
        AlertDialog(
            onDismissRequest = { target = null },
            title = { Text(if (t.categoryId == null) "Overall budget" else "${t.name} budget") },
            text = { OutlinedTextField(value = text, onValueChange = { text = it }, label = { Text("Amount (₹)") }) },
            confirmButton = {
                TextButton(onClick = {
                    vm.setBudget(t.categoryId, Money.parseToPaise(text))
                    target = null
                }) { Text("Save") }
            },
            dismissButton = {
                Row {
                    if (t.currentPaise != null) {
                        TextButton(onClick = { vm.setBudget(t.categoryId, null); target = null }) { Text("Clear") }
                    }
                    TextButton(onClick = { target = null }) { Text("Cancel") }
                }
            },
        )
    }
}

@Composable
private fun CategoryRow(row: ScopeStat, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(
        modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = if (row.indent) 16.dp else 0.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(row.name, style = MaterialTheme.typography.bodyLarge)
            Text(
                row.budgetPaise?.let { "${Money.display(row.spentPaise)} / ${Money.display(it)}" }
                    ?: Money.display(row.spentPaise),
                style = MaterialTheme.typography.bodyLarge,
            )
        }
        row.budgetPaise?.let { budget ->
            LinearProgressIndicator(
                progress = { (row.spentPaise.toFloat() / budget).coerceIn(0f, 1f) },
                color = severityColor(row.severity),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

private fun severityLine(s: Severity) = when (s) {
    Severity.OK -> "on pace"
    Severity.PACE_WARNING -> "ahead of pace"
    Severity.BREACH -> "over budget"
}

@Composable
private fun severityColor(s: Severity): Color = when (s) {
    Severity.OK -> MaterialTheme.colorScheme.primary
    Severity.PACE_WARNING -> MaterialTheme.colorScheme.tertiary
    Severity.BREACH -> MaterialTheme.colorScheme.error
}
