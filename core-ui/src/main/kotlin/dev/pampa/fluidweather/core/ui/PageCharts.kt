package dev.pampa.fluidweather.core.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** I disegni delle pagine complete (fase 11b): barre di quantita', bussola, banda di probabilita'. */
object PageCharts {

  /** Barre verticali di una quantita' (mm, ug/m3): la scala e' il massimo, o [maxValue] se dato. */
  @Composable
  fun Bars(
    values: List<Double>,
    modifier: Modifier = Modifier,
    color: Color,
    maxValue: Double? = null,
    gapPx: Float = 4f,
  ) {
    Canvas(modifier) {
      if (values.isEmpty()) return@Canvas
      val top = (maxValue ?: values.maxOrNull() ?: 0.0).takeIf { it > 1e-9 } ?: 1.0
      val barWidth = (size.width - gapPx * (values.size - 1)) / values.size
      values.forEachIndexed { index, value ->
        val fraction = (value / top).toFloat().coerceIn(0f, 1f)
        val height = if (value > 0) (size.height * fraction).coerceAtLeast(3f) else 2f
        drawRoundRect(
          color = color.copy(alpha = if (value > 0) 1f else 0.25f),
          topLeft = Offset(index * (barWidth + gapPx), size.height - height),
          size = Size(barWidth, height),
          cornerRadius = CornerRadius(barWidth / 3f),
        )
      }
    }
  }

  /**
   * La bussola del vento: l'anello coi quattro punti cardinali (nord piu' lungo) e la freccia che
   * indica dove il vento VA — il vento "da nord" spinge verso sud, e la freccia lo dice.
   */
  @Composable
  fun Compass(
    directionFromDeg: Double,
    modifier: Modifier = Modifier,
    ringColor: Color,
    arrowColor: Color,
  ) {
    Canvas(modifier) {
      val center = Offset(size.width / 2f, size.height / 2f)
      val radius = minOf(size.width, size.height) / 2f - 4f
      drawCircle(color = ringColor.copy(alpha = 0.35f), radius = radius, center = center, style = Stroke(width = 2f))
      for (i in 0 until 4) {
        val angle = Math.toRadians(i * 90.0 - 90.0)
        val length = if (i == 0) 12f else 7f
        val outer = Offset(center.x + radius * cos(angle).toFloat(), center.y + radius * sin(angle).toFloat())
        val inner = Offset(
          center.x + (radius - length) * cos(angle).toFloat(),
          center.y + (radius - length) * sin(angle).toFloat(),
        )
        drawLine(ringColor, inner, outer, strokeWidth = if (i == 0) 3f else 2f, cap = StrokeCap.Round)
      }
      // La freccia: dalla direzione di provenienza verso quella opposta.
      val toward = Math.toRadians(directionFromDeg + 180.0 - 90.0)
      val tip = Offset(
        center.x + (radius - 14f) * cos(toward).toFloat(),
        center.y + (radius - 14f) * sin(toward).toFloat(),
      )
      val tail = Offset(
        center.x - (radius - 18f) * cos(toward).toFloat(),
        center.y - (radius - 18f) * sin(toward).toFloat(),
      )
      drawLine(arrowColor, tail, tip, strokeWidth = 4f, cap = StrokeCap.Round)
      val head = Path().apply {
        val left = toward + PI * 0.8
        val right = toward - PI * 0.8
        moveTo(tip.x, tip.y)
        lineTo(tip.x + 12f * cos(left).toFloat(), tip.y + 12f * sin(left).toFloat())
        lineTo(tip.x + 12f * cos(right).toFloat(), tip.y + 12f * sin(right).toFloat())
        close()
      }
      drawPath(head, arrowColor)
    }
  }

  /**
   * La banda d'incertezza di una probabilita': la traccia 0-100, la banda [low, high] e il
   * pallino sul valore. Un verdetto onesto porta la sua banda, non un numero solo.
   */
  @Composable
  fun ProbabilityBand(
    low: Double,
    high: Double,
    value: Double,
    modifier: Modifier = Modifier,
    trackColor: Color,
    bandColor: Color,
    markerColor: Color,
  ) {
    Canvas(modifier) {
      val radius = size.height / 2f
      fun xOf(fraction: Double): Float =
        radius + fraction.toFloat().coerceIn(0f, 1f) * (size.width - size.height)
      drawLine(trackColor, Offset(radius, radius), Offset(size.width - radius, radius), size.height, StrokeCap.Round)
      drawLine(bandColor, Offset(xOf(low), radius), Offset(xOf(high), radius), size.height, StrokeCap.Round)
      drawCircle(markerColor, radius = radius * 0.8f, center = Offset(xOf(value), radius))
    }
  }
}
