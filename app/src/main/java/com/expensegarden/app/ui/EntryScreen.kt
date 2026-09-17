package com.expensegarden.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.expensegarden.app.capture.UpiIntents
import com.expensegarden.app.core.Money
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Surface
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.window.Dialog
import com.expensegarden.app.gate.GateView
import com.expensegarden.app.gate.severityForLogging
import com.expensegarden.app.render.SpriteLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
    var gate by remember { mutableStateOf<GateView?>(null) }
    var allCategoriesOpen by remember { mutableStateOf(false) }
    // Inline field state rather than Toasts. A Toast appears at the far bottom of the screen,
    // vanishes, is invisible to TalkBack in the way an error field is not, and surfaces only
    // one problem per attempt — so a user fixed the amount, tapped again, and only then
    // learned a category was also needed.
    var amountError by remember { mutableStateOf(false) }
    var categoryError by remember { mutableStateOf(false) }
    val amountFocus = remember { FocusRequester() }
    // The first input of the app's primary action; every log used to cost an extra tap.
    LaunchedEffect(Unit) { amountFocus.requestFocus() }
    SystemBarIcons(darkIcons = !isSystemInDarkTheme())

    fun fireAndFinish(amountPaise: Long, severity: Severity) {
        scope.launch {
            vm.savePendingFromDraft(amountPaise, severity)
            UpiIntents.launchPayment(context, draft.vpa!!, draft.payeeName, amountPaise, draft.note.ifBlank { null })
            onDone()
        }
    }

    // Scrolls and pads for the keyboard, exactly as SettingsScreen does and for the same
    // reason: "Log it" must never sit behind the IME. Edge-to-edge on targetSdk 35 means
    // adjustResize no longer shrinks the content, and autofocusing the amount field makes the
    // keyboard the DEFAULT state rather than an occasional one — so without this, the primary
    // action of the app would start every session out of reach. safeDrawing is the union of the
    // system bars, the cutout and the IME, so this one modifier keeps "Log it" clear of the
    // keyboard AND of the navigation bar, and cannot double-pad either.
    Column(
        Modifier.windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Explicit way home, as on dashboard/greenhouse/settings — gesture-nav phones hide the
        // system back affordance, and this was the one screen without a replacement.
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onDone, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("← garden") }
            Text(
                if (draft.fromScan) "Paying ${draft.payeeName}" else "Log an expense",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(start = 8.dp),
            )
        }

        OutlinedTextField(
            value = draft.amountText,
            onValueChange = { vm.draft.value = draft.copy(amountText = it); amountError = false },
            label = { Text("Amount (₹)") },
            isError = amountError,
            supportingText = if (amountError) ({ Text("Enter an amount") }) else null,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.fillMaxWidth().focusRequester(amountFocus),
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
                    onClick = { vm.draft.value = draft.copy(categoryId = cat.id); categoryError = false },
                    label = { Text(cat.name) },
                )
            }
            FilterChip(selected = false, onClick = { allCategoriesOpen = true }, label = { Text("All…") })
        }
        if (categoryError) {
            Text(
                "Pick a category",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
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
                                    categoryError = false
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
                // Both flags set in one pass, so a user learns about both problems at once
                // rather than discovering the second after fixing the first.
                amountError = amountPaise == null
                categoryError = draft.categoryId == null
                when {
                    amountPaise == null || draft.categoryId == null -> Unit
                    draft.fromScan -> scope.launch {
                        val view = vm.prepareGateView(amountPaise)
                        if (view == GateView.None) fireAndFinish(amountPaise, Severity.OK)
                        else gate = view
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

    gate?.let { view ->
        val amountPaise = Money.parseToPaise(draft.amountText) ?: return@let
        GateDialog(
            view = view,
            onProceed = {
                val severity = view.severityForLogging()
                gate = null
                fireAndFinish(amountPaise, severity)
            },
            onBackOut = {
                if (view.recordsDodge) vm.recordDodge(amountPaise)
                gate = null
                onDone()
            },
            onDismiss = { gate = null },
        )
    }
}

/** The gate. A custom Dialog rather than AlertDialog because the emphasis has to invert: on a
 *  discretionary purchase the quiet option is the recommended one, and Material's confirmButton
 *  slot always renders rightmost and loudest. */
@Composable
private fun GateDialog(
    view: GateView,
    onProceed: () -> Unit,
    onBackOut: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var sprite by remember(view) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(view) {
        sprite = (view as? GateView.Weed)?.let { weed ->
            withContext(Dispatchers.IO) { SpriteLoader.decodePlant(context, weed.archetype, weed.variant) }
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surface) {
            Column(
                Modifier.padding(24.dp).fillMaxWidth(),
                horizontalAlignment = if (view is GateView.Neutral) Alignment.Start else Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                when (view) {
                    is GateView.Weed -> {
                        // A missing sprite degrades to text: the heading already names the
                        // consequence, so an uninstalled pack must not leave a broken slot.
                        sprite?.let {
                            Image(bitmap = it, contentDescription = null, modifier = Modifier.size(96.dp))
                        }
                        Text("This grows a weed", style = MaterialTheme.typography.titleLarge)
                        Text(view.quip, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            listOfNotNull(view.scopeLabel, "${Money.display(view.overPaise)} over").joinToString(" · "),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    is GateView.Streak -> {
                        Text(
                            if (view.endsAStreak) "This ends a ${view.days}-day streak"
                            else "This puts you ahead of pace",
                            style = MaterialTheme.typography.titleLarge,
                        )
                        Text(view.quip, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "${Money.display(view.overPaise)} over today's ${Money.display(view.allowancePaise)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    is GateView.Neutral -> {
                        Text("${view.scopeLabel}, after this", style = MaterialTheme.typography.titleLarge)
                        Text(
                            "${Money.display(view.afterPaise)} of your ${Money.display(view.budgetPaise)} budget.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            "Nothing grows badly here — necessities never do.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    GateView.None -> Unit
                }

                Spacer(Modifier.height(8.dp))

                if (view is GateView.Neutral) {
                    // A necessity should be paid. The loud button is the one that proceeds.
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = onBackOut) { Text("Back") }
                        Button(onClick = onProceed, modifier = Modifier.padding(start = 8.dp)) { Text("Continue") }
                    }
                } else {
                    // Both stay visible, same tap target, no delay and no confirm step — the only
                    // change is which one is louder, and the user installed a budgeting app.
                    Button(onClick = onBackOut, modifier = Modifier.fillMaxWidth()) { Text("Not now") }
                    TextButton(onClick = onProceed, modifier = Modifier.fillMaxWidth()) { Text("Pay anyway") }
                }
            }
        }
    }
}
