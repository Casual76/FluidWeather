package dev.pampa.fluidweather.feature.appwidget

import android.graphics.Bitmap
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageBitmapConfig
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import dev.pampa.fluidweather.core.model.DayPhase
import dev.pampa.fluidweather.core.model.SolarEphemeris
import dev.pampa.fluidweather.core.model.WeatherKind
import dev.pampa.fluidweather.core.ui.SceneQuality
import dev.pampa.fluidweather.core.ui.SkyFrame
import dev.pampa.fluidweather.core.ui.SkyState
import java.time.Instant
import java.time.ZoneId

/**
 * Il cielo del widget: **la stessa scena della home**, ferma, rasterizzata in una bitmap.
 *
 * Glance non ha una tela — le sue composable diventano RemoteViews, e li' non si disegna — quindi
 * il fotogramma si dipinge in un [ImageBitmap] con un `CanvasDrawScope` e si mette come sfondo.
 * Cio' che ci disegna dentro e' [SkyFrame], che e' anche cio' che compone la scena viva dell'app:
 * non una copia con gli stessi colori, proprio le stesse funzioni.
 *
 * Prima qui c'era solo il gradiente: `SkyPalette.sky` restituisce anche le stelle, il colore delle
 * nuvole e la cupezza, e il widget li buttava. Un cielo sereno di mezzanotte e uno di mezzogiorno
 * si distinguevano per la tinta e basta, e con la pioggia in corso lo sfondo non lo diceva.
 */
object SkyScene {

  /**
   * Le bitmap sono piccole di proposito, e il motivo e' un vincolo duro.
   *
   * Un `RemoteViews` ha un budget complessivo di circa un megabyte e mezzo per aggiornamento, e
   * con `SizeMode.Responsive` le **tre** taglie viaggiano insieme: a piena risoluzione (una 4x4 su
   * uno schermo a densita' 3 sono 750x600 px, ~1,8 MB in ARGB_8888) il launcher taglierebbe il
   * widget **senza dire niente**. Duecentoventi pixel sul lato lungo, con le proporzioni della
   * taglia, fanno in tutto circa 420 KB.
   *
   * E si puo' fare perche' questa scena e' fatta di gradienti e di lobi radiali: ingrandita non
   * mostra scalini. E' lo stesso motivo per cui al gradiente da solo bastavano 32x64.
   */
  const val LONG_EDGE_PX = 220

  /** Le tre tele, con le proporzioni delle tre taglie dichiarate dal widget. */
  fun sizeFor(tier: AppWidgetTier): Pair<Int, Int> = when (tier) {
    AppWidgetTier.SMALL -> LONG_EDGE_PX to LONG_EDGE_PX
    AppWidgetTier.MEDIUM -> LONG_EDGE_PX to (LONG_EDGE_PX * 110 / 250)
    AppWidgetTier.LARGE -> LONG_EDGE_PX to (LONG_EDGE_PX * 200 / 250)
  }

  /** Quanto costa in tutto un aggiornamento, in byte: il test lo confronta col budget. */
  fun totalBytes(): Int = AppWidgetTier.entries.sumOf { tier ->
    val (width, height) = sizeFor(tier)
    width * height * 4
  }

  fun of(
    tier: AppWidgetTier,
    kind: WeatherKind?,
    cloudCoverPercent: Double?,
    latitude: Double?,
    longitude: Double?,
    nowMillis: Long,
    zone: ZoneId = ZoneId.systemDefault(),
    /** La fotografia della luna, caricata da chi ha le risorse. */
    moon: ImageBitmap? = null,
  ): Bitmap {
    val (width, height) = sizeFor(tier)
    val bitmap = ImageBitmap(width, height, ImageBitmapConfig.Argb8888)
    CanvasDrawScope().draw(
      density = Density(1f),
      layoutDirection = LayoutDirection.Ltr,
      canvas = Canvas(bitmap),
      size = Size(width.toFloat(), height.toFloat()),
    ) {
      SkyFrame.draw(
        scope = this,
        state = SkyState(
          phase = phaseOf(latitude, longitude, nowMillis, zone),
          kind = kind,
          cloudCoverPercent = cloudCoverPercent,
          latitude = latitude,
          longitude = longitude,
        ),
        nowMillis = nowMillis,
        // La qualita' piena non costa niente qui: e' un fotogramma solo, e la pioggia disegnata
        // a meta' densita' su una tela cosi' piccola sparirebbe.
        quality = SceneQuality.FULL,
        zone = zone,
        // Il velo sotto il testo: il widget scrive in bianco fisso, e una nevicata o una nebbia
        // portano il cielo verso il grigio chiaro. Vedi ScrimPainter.
        scrim = true,
        moon = moon,
      )
    }
    return bitmap.asAndroidBitmap()
  }

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
}
