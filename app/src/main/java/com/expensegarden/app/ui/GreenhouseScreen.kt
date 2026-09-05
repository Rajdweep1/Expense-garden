package com.expensegarden.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.expensegarden.app.core.Money
import com.expensegarden.app.game.CollectionState
import com.expensegarden.app.game.RareCatalog
import com.expensegarden.app.game.RareTier
import com.expensegarden.app.game.RareTrigger
import com.expensegarden.app.game.GardenState
import com.expensegarden.app.render.GardenCanvas
import com.expensegarden.app.render.PlantPainter
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun GreenhouseScreen(
    gardenVm: GardenViewModel,
    aiVm: AiViewModel,
    painter: PlantPainter,
    onBack: () -> Unit = {},
) {
    var months by remember { mutableStateOf<List<GardenState>?>(null) }
    var collection by remember { mutableStateOf<CollectionState?>(null) }
    var selected by remember { mutableStateOf<GardenState?>(null) }
    LaunchedEffect(Unit) { months = gardenVm.archivedGardens() }
    LaunchedEffect(Unit) { collection = runCatching { gardenVm.collection() }.getOrNull() }
    val monthFmt = remember { DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH) }

    Column(Modifier.statusBarsPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // Explicit way home — gesture-nav phones hide the system back affordance.
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("← garden") }
            Text("Greenhouse", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(start = 8.dp))
        }
        collection?.let { CollectionCard(it) }
        when {
            months == null -> Card(Modifier.fillMaxWidth().height(120.dp)) {}
            months!!.isEmpty() -> Text("No archived months yet — your first bed archives at month end.")
            else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(months!!, key = { it.monthKey }) { g ->
                    Card(Modifier.fillMaxWidth().clickable { selected = g }) {
                        Column {
                            GardenCanvas(
                                state = g, painter = painter, animated = false,
                                modifier = Modifier.fillMaxWidth().height(180.dp),
                                topReservePx = 60f, bottomReservePx = 30f,
                            )
                            Row(
                                Modifier.fillMaxWidth().padding(12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(monthFmt.format(YearMonth.parse(g.monthKey).atDay(1)), style = MaterialTheme.typography.titleMedium)
                                Text(Money.display(g.spentPaise), style = MaterialTheme.typography.titleMedium)
                            }
                            var monthly by remember(g.monthKey) { mutableStateOf<String?>(null) }
                            LaunchedEffect(g.monthKey) { monthly = aiVm.monthlyFor(g.monthKey)?.text }
                            monthly?.let {
                                Text(
                                    it,
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    selected?.let { g ->
        Box(Modifier.fillMaxSize()) {
            GardenCanvas(state = g, painter = painter, animated = false, modifier = Modifier.fillMaxSize())
            TextButton(
                onClick = { selected = null },
                modifier = Modifier.statusBarsPadding().padding(12.dp),
            ) { Text("← back") }
        }
    }
}

/** The album (spec §5). Silhouettes with their condition, because a collection that only shows
 *  what you already have records rather than invites — and because you would otherwise never
 *  learn a lotus exists.
 *
 *  The nag risk is acceptable here for one specific reason: every condition is a behaviour the
 *  app already wants. There is nothing on this list a user could reach by spending more. */
@Composable
private fun CollectionCard(state: CollectionState) {
    var expanded by remember { mutableStateOf(false) }
    val total = RareCatalog.all().size

    Card(Modifier.fillMaxWidth().clickable { expanded = !expanded }) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Collection", style = MaterialTheme.typography.titleMedium)
                Text("${state.foundIds.size} of $total", style = MaterialTheme.typography.titleMedium)
            }
            if (state.pendingCount > 0) {
                // A banked seed is invisible until a purchase lands on it; saying so stops an
                // earned reward from looking lost.
                Text(
                    "${state.pendingCount} waiting — your next purchase will grow one",
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            Text(
                if (expanded) "tap to collapse" else "tap to see what else grows here",
                style = MaterialTheme.typography.labelSmall,
            )

            if (expanded) {
                for (tier in RareTier.values()) {
                    Text(
                        tier.name.lowercase().replaceFirstChar { it.uppercase() },
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                    for (species in RareCatalog.pool(tier)) {
                        val earnedBy = state.foundBy[species.id]
                        Text(
                            if (earnedBy != null) {
                                // Spec §5 asks for the species AND how it was earned. Showing
                                // the specific trigger beats the tier's generic condition line:
                                // it tells you what YOU did, not what someone could do.
                                "\u2022 ${species.displayName} — ${howEarned(earnedBy)}"
                            } else {
                                "\u2022 ???"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (earnedBy != null) MaterialTheme.colorScheme.onSurface
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        conditionFor(tier),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** What a locked slot tells you. Lives next to the UI rather than on RareSpecies so the
 *  catalogue stays free of presentation strings. */
private fun conditionFor(tier: RareTier): String = when (tier) {
    RareTier.UNCOMMON -> "a 7-day streak, 3 gate dodges, a no-spend week, or redeeming a regret"
    RareTier.RARE -> "close a month under budget, a 30-day streak, or spend across 8 categories"
    RareTier.LANDMARK -> "keep tracking — 6 months, then 12"
}

/** How a species you already have was earned. Past tense on purpose — this is a record of
 *  something you did, not an instruction. */
private fun howEarned(trigger: RareTrigger): String = when (trigger) {
    RareTrigger.STREAK_7 -> "a 7-day streak"
    RareTrigger.STREAK_30 -> "a 30-day streak"
    RareTrigger.GATE_DODGES -> "backing out at the gate"
    RareTrigger.NO_SPEND_DAYS -> "a no-spend week"
    RareTrigger.MONTH_UNDER_BUDGET -> "a month under budget"
    RareTrigger.CATEGORY_BREADTH -> "a broad month"
    RareTrigger.REDEEMED -> "redeeming a regret"
    RareTrigger.HOUSE_LEVEL -> "months tracked"
}
