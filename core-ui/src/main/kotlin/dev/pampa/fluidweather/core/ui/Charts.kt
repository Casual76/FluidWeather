package dev.pampa.fluidweather.core.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * I grafici della griglia: Canvas puro, niente librerie. Curve morbide alla Catmull-Rom (una
 * temperatura non e' una spezzata), barre per le probabilita', range per il giornaliero, arco
 * per il sole, disco col terminatore per la luna.
 */
object Charts {

  /** La curva morbida con riempimento sfumato sotto: temperatura, pressione, quello che serve. */
  @Composable
  fun SmoothLine(
    values: List<Double>,
    modifier: Modifier = Modifier,
    color: Color,
    fill: Boolean = true,
    strokeWidth: Float = 5f,
    /**
     * Margine verticale esatto, in px, sopra e sotto la curva. Null = respiro proporzionale
     * (8% per lato). Il valore esatto serve a chi allinea altro alla curva — i gradi che la
     * cavalcano nella striscia oraria — e deve sapere dove passa davvero.
     */
    insetPx: Float? = null,
    /** Un punto evidenziato sulla curva (oggi nell'anno, adesso nel giorno). */
    markerIndex: Int? = null,
  ) {
    Canvas(modifier) {
      if (values.size < 2) return@Canvas
      val min = values.min()
      val max = values.max()
      val span = (max - min).takeIf { it > 1e-9 } ?: 1.0
      val stepX = size.width / (values.size - 1)
      // Margine verticale: la curva respira invece di toccare i bordi.
      val topPad = insetPx ?: size.height * 0.08f
      val chartHeight = if (insetPx != null) size.height - 2f * insetPx else size.height * 0.84f
      fun pointAt(index: Int): Offset {
        val normalized = ((values[index] - min) / span).toFloat()
        return Offset(index * stepX, topPad + (1f - normalized) * chartHeight)
      }

      val path = Path()
      path.moveTo(pointAt(0).x, pointAt(0).y)
      for (i in 0 until values.size - 1) {
        // Catmull-Rom -> Bezier: i punti di controllo vengono dai vicini, la curva passa dai dati.
        val p0 = pointAt((i - 1).coerceAtLeast(0))
        val p1 = pointAt(i)
        val p2 = pointAt(i + 1)
        val p3 = pointAt((i + 2).coerceAtMost(values.size - 1))
        val c1 = Offset(p1.x + (p2.x - p0.x) / 6f, p1.y + (p2.y - p0.y) / 6f)
        val c2 = Offset(p2.x - (p3.x - p1.x) / 6f, p2.y - (p3.y - p1.y) / 6f)
        path.cubicTo(c1.x, c1.y, c2.x, c2.y, p2.x, p2.y)
      }

      if (fill) {
        val fillPath = Path().apply {
          addPath(path)
          lineTo(size.width, size.height)
          lineTo(0f, size.height)
          close()
        }
        drawPath(
          fillPath,
          brush = Brush.verticalGradient(
            colors = listOf(color.copy(alpha = 0.30f), color.copy(alpha = 0f)),
          ),
        )
      }
      drawPath(path, color = color, style = Stroke(width = strokeWidth, cap = StrokeCap.Round))
      if (markerIndex != null && markerIndex in values.indices) {
        val point = pointAt(markerIndex)
        drawCircle(color = color, radius = 8f, center = point)
        drawCircle(color = Color.White, radius = 4f, center = point)
      }
    }
  }

  /** Barre di probabilita' [0..100]: piene quanto serve, mai invisibili quando non e' zero. */
  @Composable
  fun ProbabilityBars(
    percentages: List<Double>,
    modifier: Modifier = Modifier,
    color: Color,
  ) {
    Canvas(modifier) {
      if (percentages.isEmpty()) return@Canvas
      val gap = 6f
      val barWidth = (size.width - gap * (percentages.size - 1)) / percentages.size
      percentages.forEachIndexed { index, percent ->
        val fraction = (percent / 100.0).toFloat().coerceIn(0f, 1f)
        val height = if (percent > 0) (size.height * fraction).coerceAtLeast(4f) else 3f
        val alpha = if (percent > 0) 1f else 0.25f
        drawRoundRect(
          color = color.copy(alpha = alpha),
          topLeft = Offset(index * (barWidth + gap), size.height - height),
          size = androidx.compose.ui.geometry.Size(barWidth, height),
          cornerRadius = androidx.compose.ui.geometry.CornerRadius(barWidth / 3f),
        )
      }
    }
  }

  /**
   * La barra dei range del giornaliero: il segmento del giorno dentro l'intervallo del periodo,
   * col pallino di "adesso" quando e' il giorno di oggi.
   */
  @Composable
  fun RangeBar(
    periodMin: Double,
    periodMax: Double,
    dayMin: Double,
    dayMax: Double,
    nowValue: Double?,
    modifier: Modifier = Modifier,
    trackColor: Color,
    barBrushColors: List<Color>,
  ) {
    Canvas(modifier) {
      val span = (periodMax - periodMin).takeIf { it > 1e-9 } ?: 1.0
      val radius = size.height / 2f
      fun xOf(value: Double): Float =
        (((value - periodMin) / span).toFloat().coerceIn(0f, 1f)) * (size.width - size.height) + radius

      drawLine(
        color = trackColor,
        start = Offset(radius, size.height / 2),
        end = Offset(size.width - radius, size.height / 2),
        strokeWidth = size.height,
        cap = StrokeCap.Round,
      )
      drawLine(
        brush = Brush.horizontalGradient(barBrushColors, startX = xOf(dayMin), endX = xOf(dayMax)),
        start = Offset(xOf(dayMin), size.height / 2),
        end = Offset(xOf(dayMax), size.height / 2),
        strokeWidth = size.height,
        cap = StrokeCap.Round,
      )
      if (nowValue != null) {
        drawCircle(
          color = Color.White,
          radius = radius * 0.72f,
          center = Offset(xOf(nowValue), size.height / 2),
        )
      }
    }
  }

  /** L'arco del sole: la giornata come semicerchio, il sole dove sta adesso. */
  @Composable
  fun SunArc(
    dayProgress: Float?,
    modifier: Modifier = Modifier,
    arcColor: Color,
    sunColor: Color,
  ) {
    Canvas(modifier) {
      val stroke = Stroke(width = 4f, cap = StrokeCap.Round)
      // La geometria si ricava da ENTRAMBE le dimensioni: il raggio piu' grande che sta nella
      // tessera, con l'alone del sole dentro. Ricavarlo dalla sola larghezza faceva uscire la
      // cupola dal bordo alto (visto sul telefono, 2026-09-02).
      val glowRadius = 26f
      val inset = glowRadius + 2f
      val baseline = size.height - inset * 0.5f
      val radius = minOf(size.width / 2f - inset, baseline - inset)
      if (radius <= 0f) return@Canvas
      val center = Offset(size.width / 2f, baseline)
      // L'orizzonte: la linea da cui il sole nasce e in cui torna.
      drawLine(
        color = arcColor.copy(alpha = 0.16f),
        start = Offset(0f, baseline),
        end = Offset(size.width, baseline),
        strokeWidth = 2f,
      )
      val path = Path()
      var first = true
      var degrees = 180f
      while (degrees <= 360f) {
        val radians = degrees * PI.toFloat() / 180f
        val point = Offset(center.x + radius * cos(radians), center.y + radius * sin(radians))
        if (first) {
          path.moveTo(point.x, point.y)
          first = false
        } else {
          path.lineTo(point.x, point.y)
        }
        degrees += 4f
      }
      drawPath(path, color = arcColor.copy(alpha = 0.45f), style = stroke)

      if (dayProgress != null) {
        val angle = (180f + 180f * dayProgress.coerceIn(0f, 1f)) * PI.toFloat() / 180f
        val sun = Offset(center.x + radius * cos(angle), center.y + radius * sin(angle))
        drawCircle(
          brush = Brush.radialGradient(
            listOf(sunColor, sunColor.copy(alpha = 0f)),
            center = sun,
            radius = glowRadius,
          ),
          radius = glowRadius,
          center = sun,
        )
        drawCircle(color = sunColor, radius = 9f, center = sun)
      }
    }
  }

  /**
   * La luna della pagina: la stessa fotografia e la stessa ombra di fase del cielo, cosi' le due
   * lune sono la stessa. Prima era un cerchio color avorio con due archi di ombra: non sembrava
   * troppo lunare, e aveva ragione chi lo diceva.
   *
   * [moonColor] resta per compatibilita' con chi lo passa: la fotografia ha i suoi colori.
   */
  @Composable
  fun MoonDisc(
    illuminatedFraction: Double,
    waxing: Boolean,
    modifier: Modifier = Modifier,
    moonColor: Color = Color(0xFFE8E4D8),
    shadowColor: Color = Color(0xFF11141C),
  ) {
    val moon = ImageBitmap.imageResource(R.drawable.moon_nearside)
    Canvas(modifier) {
      val radius = minOf(size.width, size.height) / 2f
      val center = Offset(size.width / 2f, size.height / 2f)
      MoonPainter.disc(
        scope = this,
        image = moon,
        center = center,
        radius = radius,
        illuminated = illuminatedFraction.coerceIn(0.0, 1.0).toFloat(),
        waxing = waxing,
        shadow = shadowColor,
      )
      // Un filo di bordo per staccare dal cielo della tessera.
      drawCircle(
        color = Color.White.copy(alpha = 0.12f),
        radius = radius,
        center = center,
        style = Stroke(width = 2f),
      )
    }
  }
}
