package com.expensegarden.app.ui

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
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
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun EntryScreen(vm: MainViewModel, onDone: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val draft by vm.draft.collectAsState()
    val categories by vm.categories.collectAsState()
    var gate by remember { mutableStateOf<GatePrompt?>(null) }
    var allCategoriesOpen by remember { mutableStateOf(false) }

    fun fireAndFinish(amountPaise: Long, severity: Severity) {
        scope.launch {
            vm.savePendingFromDraft(amountPaise, severity)
            UpiIntents.launchPayment(context, draft.vpa!!, draft.payeeName, amountPaise, draft.note.ifBlank { null })
            onDone()
        }
    }

    Column(Modifier.statusBarsPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
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

            var datePickerOpen by remember { mutableStateOf(false) }
            val zone = remember { ZoneId.systemDefault() }
            val entryDateFmt = remember { DateTimeFormatter.ofPattern("dd MMM yyyy") }
            OutlinedButton(onClick = { datePickerOpen = true }, modifier = Modifier.fillMaxWidth()) {
                Text("On ${entryDateFmt.format(Instant.ofEpochMilli(draft.occurredAt).atZone(zone))}")
            }
            if (datePickerOpen) {
                val todayUtc = LocalDate.now(zone).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
                val state = rememberDatePickerState(
                    initialSelectedDateMillis = Instant.ofEpochMilli(draft.occurredAt).atZone(zone)
                        .toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
                    selectableDates = object : SelectableDates {
                        override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis <= todayUtc
                    },
                )
                DatePickerDialog(
                    onDismissRequest = { datePickerOpen = false },
                    confirmButton = {
                        TextButton(onClick = {
                            state.selectedDateMillis?.let { utc ->
                                // Picker returns UTC midnight; pin the txn to local noon of that date
                                // (steers clear of DST/midnight month-boundary weirdness).
                                val local = Instant.ofEpochMilli(utc).atZone(ZoneOffset.UTC).toLocalDate()
                                    .atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
                                vm.setDraftDate(local)
                            }
                            datePickerOpen = false
                        }) { Text("OK") }
                    },
                ) { DatePicker(state = state) }
            }
        }

        val chips by vm.chipCategories.collectAsState()
        val selectedId = draft.categoryId
        Text("Category", style = MaterialTheme.typography.labelMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            // Selected category always visible, even when outside the top-8 (e.g. payee prefill).
            val shown = if (selectedId != null && chips.none { it.id == selectedId })
                chips + categories.filter { it.id == selectedId } else chips
            shown.forEach { cat ->
                FilterChip(
                    selected = cat.id == selectedId,
                    onClick = { vm.draft.value = draft.copy(categoryId = cat.id) },
                    label = { Text(cat.name) },
                )
            }
            FilterChip(selected = false, onClick = { allCategoriesOpen = true }, label = { Text("All…") })
        }

        if (allCategoriesOpen) {
            // Parent-then-children order (seed ids) — the DAO's necessity-first sort scatters indented children.
            val grouped = remember(categories) {
                categories.filter { it.parentId == null }.sortedBy { it.id }.flatMap { parent ->
                    listOf(parent) + categories.filter { it.parentId == parent.id }.sortedBy { it.id }
                }
            }
            ModalBottomSheet(onDismissRequest = { allCategoriesOpen = false }) {
                LazyColumn(contentPadding = PaddingValues(bottom = 32.dp)) {
                    items(grouped, key = { it.id }) { cat ->
                        Text(
                            text = if (cat.parentId == null) cat.name else "    ${cat.name}",
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    vm.draft.value = draft.copy(categoryId = cat.id)
                                    allCategoriesOpen = false
                                }
                                .padding(horizontal = 24.dp, vertical = 12.dp),
                        )
                    }
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
            title = {
                val base = if (prompt.severity == Severity.BREACH) "Over budget" else "Ahead of pace"
                Text(prompt.scopeLabel?.let { "$base — $it" } ?: base)
            },
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
