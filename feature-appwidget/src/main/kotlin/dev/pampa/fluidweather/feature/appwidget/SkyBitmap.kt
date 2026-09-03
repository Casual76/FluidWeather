package dev.pampa.fluidweather.feature.appwidget

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import androidx.compose.ui.graphics.toArgb
import dev.pampa.fluidweather.core.model.DayPhase
import dev.pampa.fluidweather.core.model.SolarEphemeris
import dev.pampa.fluidweather.core.model.WeatherKind
import dev.pampa.fluidweather.core.ui.SkyPalette
import java.time.Instant
import java.time.ZoneId

/**
 * Il cielo del widget: lo stesso dell'app, dipinto con lo stesso meteo di adesso.
 *
 * Glance non ha una tela — le sue composable diventano RemoteViews, e li' non si disegna — quindi
 * il gradiente si rasterizza in una bitmap e si mette come sfondo. [SkyPalette.sky] e' la stessa
 * funzione che decide il cielo della home: non una copia con gli stessi colori, proprio la stessa,
 * cosi' il widget e l'app non possono divergere.
 */
object SkyBitmap {

  /**
   * La bitmap e' minuscola di proposito: 32 x 64 pixel, stirata dal launcher.
   *
   * Un gradiente verticale si stira senza artefatti, e un `RemoteViews` ha un budget complessivo
   * di circa un megabyte e mezzo per aggiornamento: una bitmap a piena dimensione (500x300 in
   * ARGB_8888 sono ~600 KB) se lo mangerebbe quasi tutto e il launcher taglierebbe il widget
   * **senza dire niente**. Cosi' sono 8 KB.
   */
  const val WIDTH_PX = 32
  const val HEIGHT_PX = 64

  fun of(
    kind: WeatherKind?,
    cloudCoverPercent: Double?,
    latitude: Double?,
    longitude: Double?,
    nowMillis: Long,
    zone: ZoneId = ZoneId.systemDefault(),
  ): Bitmap = render(SkyPalette.sky(phaseOf(latitude, longitude, nowMillis, zone), kind, cloudCoverPercent).gradient)

  /**
   * La fase del giorno.
   *
   * Con le coordinate dell'istantanea e' l'effemeride vera (aritmetica pura, nessuna rete); senza,
   * si ripiega sull'orologio locale — grossolano ma mai spento, la stessa scelta che fa la home
   * quando non ha ancora una posizione.
   */
  fun phaseOf(latitude: Double?, longitude: Double?, nowMillis: Long, zone: ZoneId = ZoneId.systemDefault()): DayPhase {
    if (latitude != null && longitude != null) return SolarEphemeris.phaseAt(nowMillis, latitude, longitude)
    return when (Instant.ofEpochMilli(nowMillis).atZone(zone).hour) {
      in 6..7 -> DayPhase.DAWN
      in 8..17 -> DayPhase.DAY
      in 18..19 -> DayPhase.DUSK
      else -> DayPhase.NIGHT
    }
  }

  private fun render(gradient: List<androidx.compose.ui.graphics.Color>): Bitmap {
    val colors = gradient.map { it.toArgb() }.toIntArray()
    val bitmap = Bitmap.createBitmap(WIDTH_PX, HEIGHT_PX, Bitmap.Config.ARGB_8888)
    val paint = Paint().apply {
      shader = if (colors.size >= 2) {
        LinearGradient(0f, 0f, 0f, HEIGHT_PX.toFloat(), colors, null, Shader.TileMode.CLAMP)
      } else {
        null
      }
      color = colors.firstOrNull() ?: android.graphics.Color.BLACK
    }
    Canvas(bitmap).drawRect(0f, 0f, WIDTH_PX.toFloat(), HEIGHT_PX.toFloat(), paint)
    return bitmap
  }
}
