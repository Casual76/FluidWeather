package dev.pampa.fluidweather.feature.radar

import com.google.android.gms.maps.model.UrlTileProvider
import dev.pampa.fluidweather.core.weather.RadarFrame
import dev.pampa.fluidweather.core.weather.RadarFrames
import java.net.URL

/**
 * I quattro livelli del piano. Senza chiave OpenWeatherMap (decisione 2026-09-02) solo le
 * precipitazioni sono servite, dal radar RainViewer; temperatura e vento arrivano come tile
 * di OWM quando la chiave e' nell'app; la qualita' dell'aria nessun servizio gratuito la serve
 * come mappa, e il selettore lo dice invece di fingere.
 */
enum class RadarLayer(val label: String, val owmLayer: String?) {
  PRECIPITATION("Precipitazioni", null),
  TEMPERATURE("Temperatura", "temp_new"),
  AIR_QUALITY("Qualita' aria", null),
  WIND("Vento", "wind_new");

  fun available(hasOwmKey: Boolean): Boolean = when (this) {
    PRECIPITATION -> true
    TEMPERATURE, WIND -> hasOwmKey
    AIR_QUALITY -> false
  }

  fun unavailableNote(): String = when (this) {
    PRECIPITATION -> ""
    TEMPERATURE, WIND -> "con chiave OpenWeatherMap"
    AIR_QUALITY -> "nessun servizio gratuito lo serve come mappa"
  }
}

/** Un fotogramma del radar come sorgente di tile per Google Maps. */
class RainViewerTileProvider(
  private val frames: RadarFrames,
  private val frame: RadarFrame,
) : UrlTileProvider(TILE_SIZE, TILE_SIZE) {

  override fun getTileUrl(x: Int, y: Int, zoom: Int): URL? =
    runCatching { URL(frames.tileUrl(frame, zoom, x, y)) }.getOrNull()

  private companion object {
    const val TILE_SIZE = 256
  }
}

/** I tile di OpenWeatherMap, con la chiave dell'utente: temperatura e vento lisci. */
class OwmTileProvider(
  private val layer: String,
  private val apiKey: String,
) : UrlTileProvider(256, 256) {

  override fun getTileUrl(x: Int, y: Int, zoom: Int): URL? =
    runCatching { URL("https://tile.openweathermap.org/map/$layer/$zoom/$x/$y.png?appid=$apiKey") }.getOrNull()
}
