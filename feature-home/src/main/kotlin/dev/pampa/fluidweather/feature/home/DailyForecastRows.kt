package dev.pampa.fluidweather.feature.home

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.pampa.fluidweather.core.model.WeatherKind
import dev.pampa.fluidweather.core.ui.Charts
import dev.pampa.fluidweather.core.ui.rememberUnitFormatter
import dev.pampa.fluidweather.strings.R

/** Stateless rows shared by the production Daily tile and layout regressions. */
data class DailyForecastRow(val id: String, val day: String, val minC: Double, val maxC: Double,
  val kind: WeatherKind?, val nowC: Double? = null)

@Composable
fun DailyForecastRows(rows: List<DailyForecastRow>, periodMin: Double, periodMax: Double, compact: Boolean = false) {
  val units = rememberUnitFormatter()
  Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
    rows.forEach { row ->
      if (compact) Row(Modifier.fillMaxWidth().testTag("daily_row_${row.id}").semantics(mergeDescendants = true) {},
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(row.day, style = MaterialTheme.typography.bodySmall, maxLines = 1, modifier = Modifier.width(42.dp))
        WeatherKindIcon(row.kind, size = 16.dp)
        Text(units.degrees(row.minC), style = MaterialTheme.typography.labelMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        Charts.RangeBar(periodMin = periodMin, periodMax = periodMax, dayMin = row.minC,
          dayMax = row.maxC, nowValue = row.nowC,
          trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.15f),
          barBrushColors = listOf(Color(0xFF6FAAF0), Color(0xFFF0C060)),
          modifier = Modifier.weight(1f).height(5.dp))
        Text(units.degrees(row.maxC), style = MaterialTheme.typography.labelMedium, maxLines = 1)
      } else Column(Modifier.fillMaxWidth().testTag("daily_row_${row.id}").semantics(mergeDescendants = true) {}) {
        // Day gets the full available width. Values flow as complete labelled phrases.
        Text(row.day, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("daily_day_${row.id}"))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
          WeatherKindIcon(row.kind, size = 20.dp)
          Text(stringResource(R.string.accessibility_minimum, units.degrees(row.minC)),
            style = MaterialTheme.typography.labelMedium, modifier = Modifier.testTag("daily_min_${row.id}"))
          Text(stringResource(R.string.accessibility_maximum, units.degrees(row.maxC)),
            style = MaterialTheme.typography.labelMedium, modifier = Modifier.testTag("daily_max_${row.id}"))
        }
        Charts.RangeBar(periodMin = periodMin, periodMax = periodMax, dayMin = row.minC,
          dayMax = row.maxC, nowValue = row.nowC,
          trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.15f),
          barBrushColors = listOf(Color(0xFF6FAAF0), Color(0xFFF0C060)),
          modifier = Modifier.fillMaxWidth().height(5.dp))
      }
    }
  }
}
