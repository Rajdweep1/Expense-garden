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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.expensegarden.app.core.Money
import com.expensegarden.app.game.GardenState
import com.expensegarden.app.render.GardenCanvas
import com.expensegarden.app.render.PlantPainter
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun GreenhouseScreen(gardenVm: GardenViewModel, painter: PlantPainter) {
    var months by remember { mutableStateOf<List<GardenState>?>(null) }
    var selected by remember { mutableStateOf<GardenState?>(null) }
    LaunchedEffect(Unit) { months = gardenVm.archivedGardens() }
    val monthFmt = remember { DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH) }

    Column(Modifier.statusBarsPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Greenhouse", style = MaterialTheme.typography.headlineSmall)
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
