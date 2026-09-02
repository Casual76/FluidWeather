package dev.pampa.fluidweather.feature.radar

import androidx.annotation.StringRes
import com.google.android.gms.maps.model.UrlTileProvider
import dev.pampa.fluidweather.core.weather.RadarFrame
import dev.pampa.fluidweather.core.weather.RadarFrames
import dev.pampa.fluidweather.strings.R
import java.net.URL

/**
 * I quattro livelli del piano. Senza chiave OpenWeatherMap (decisione 2026-09-02) solo le
 * precipitazioni sono servite, dal radar RainViewer; temperatura e vento arrivano come tile
 * di OWM quando la chiave e' nell'app; la qualita' dell'aria nessun servizio gratuito la serve
 * come mappa, e il selettore lo dice invece di fingere.
 */
enum class RadarLayer(@param:StringRes val labelRes: Int, val owmLayer: String?) {
  PRECIPITATION(R.string.radar_layer_precipitation, null),
  TEMPERATURE(R.string.radar_layer_temperature, "temp_new"),
  AIR_QUALITY(R.string.radar_layer_air, null),
  WIND(R.string.radar_layer_wind, "wind_new");

  fun available(hasOwmKey: Boolean): Boolean = when (this) {
    PRECIPITATION -> true
    TEMPERATURE, WIND -> hasOwmKey
    AIR_QUALITY -> false
  }

  /** Perche' non e' disponibile, quando non lo e': null se lo e' sempre. */
  @StringRes
  fun unavailableNoteRes(): Int? = when (this) {
    PRECIPITATION -> null
    TEMPERATURE, WIND -> R.string.radar_layer_owm_key
    AIR_QUALITY -> R.string.radar_layer_no_service
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
