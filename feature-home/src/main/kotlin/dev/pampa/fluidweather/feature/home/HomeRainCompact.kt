package dev.pampa.fluidweather.feature.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import dev.pampa.fluidweather.strings.R

/** Short visual value with the full metric name available to assistive technology. */
@Composable
internal fun RainGlanceMetric(icon: ImageVector, value: String, description: String, tag: String) {
  Row(Modifier.testTag(tag).clearAndSetSemantics { contentDescription = description },
    horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
    Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface,
      modifier = Modifier.size(18.dp))
    Text(value, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
  }
}

/** Same dotted mark as timeline holes; zero precipitation is a solid segment instead. */
@Composable
internal fun RainGapKey() {
  val ink = MaterialTheme.colorScheme.onSurfaceVariant
  val description = stringResource(R.string.rain_quality_gaps)
  Row(Modifier.testTag("home_rain_gap_key").clearAndSetSemantics { contentDescription = description },
    horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
    Canvas(Modifier.size(20.dp, 12.dp)) {
      drawLine(ink, Offset(0f, size.height / 2), Offset(size.width, size.height / 2),
        strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round,
        pathEffect = PathEffect.dashPathEffect(floatArrayOf(1.dp.toPx(), 4.dp.toPx())))
    }
    Text(stringResource(R.string.glance_rain_gaps), style = MaterialTheme.typography.labelMedium, color = ink)
  }
}
