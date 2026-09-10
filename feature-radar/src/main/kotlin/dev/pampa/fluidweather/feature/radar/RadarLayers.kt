package dev.pampa.fluidweather.feature.radar

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.annotation.StringRes
import com.google.android.gms.maps.model.Tile
import com.google.android.gms.maps.model.TileProvider
import dev.pampa.fluidweather.core.weather.RadarFrame
import dev.pampa.fluidweather.core.weather.RadarFrames
import dev.pampa.fluidweather.strings.R
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
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

/**
 * Tile che oltre lo zoom nativo del servizio si ricavano dal tile "padre", ritagliato e
 * ingrandito.
 *
 * Prima la mappa era tappata a zoom 12 (`maxZoomPreference`), perche' oltre il servizio non ha
 * niente di nuovo da dire. Ma tappare lo zoom obbliga chi guarda a un livello scelto da noi,
 * mentre un radar non ha piu' dettaglio ma resta perfettamente leggibile ingrandito: si prende il
 * tile del livello nativo massimo, si ritaglia il quadrante giusto e lo si stira. E' quello che fa
 * qualunque mappa quando finisce i livelli, e nessuno se ne accorge.
 *
 * I tile padre restano in una piccola cache in memoria: a zoom 14 quattro tile su sedici hanno lo
 * stesso padre, e riscaricarlo ogni volta sarebbe quattro volte la banda per lo stesso pixel.
 */
abstract class OverzoomTileProvider(
  private val nativeMaxZoom: Int,
) : TileProvider {

  /** L'URL del tile al livello richiesto, che per costruzione e' al massimo [nativeMaxZoom]. */
  protected abstract fun tileUrl(x: Int, y: Int, zoom: Int): URL?

  override fun getTile(x: Int, y: Int, zoom: Int): Tile? {
    if (zoom <= nativeMaxZoom) {
      val bytes = fetch(tileUrl(x, y, zoom) ?: return TileProvider.NO_TILE) ?: return null
      return Tile(TILE_SIZE, TILE_SIZE, bytes)
    }
    // Il padre al livello nativo, e il quadrante di questo tile dentro di lui.
    val quadrant = Overzoom.of(x, y, zoom, nativeMaxZoom) ?: return TileProvider.NO_TILE
    val parent = parentBitmap(quadrant.parentX, quadrant.parentY) ?: return null
    val cropped = Bitmap.createBitmap(parent, quadrant.left, quadrant.top, quadrant.size, quadrant.size)
    val scaled = Bitmap.createScaledBitmap(cropped, TILE_SIZE, TILE_SIZE, true)
    if (cropped !== scaled) cropped.recycle()
    val out = ByteArrayOutputStream()
    scaled.compress(Bitmap.CompressFormat.PNG, 100, out)
    scaled.recycle()
    return Tile(TILE_SIZE, TILE_SIZE, out.toByteArray())
  }

  private fun parentBitmap(x: Int, y: Int): Bitmap? {
    val key = "$x/$y"
    parents.get(key)?.let { return it }
    val url = tileUrl(x, y, nativeMaxZoom) ?: return null
    val bytes = fetch(url) ?: return null
    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
    parents.put(key, bitmap)
    return bitmap
  }

  private fun fetch(url: URL): ByteArray? = runCatching {
    val connection = url.openConnection() as HttpURLConnection
    connection.connectTimeout = 10_000
    connection.readTimeout = 10_000
    try {
      if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
      connection.inputStream.use { it.readBytes() }
    } finally {
      connection.disconnect()
    }
  }.getOrNull()

  // Una cache per provider, cioe' per fotogramma: ventiquattro tile da 256x256 ARGB sono sei
  // megabyte, che e' cio' che una vista a zoom 14 tiene sotto gli occhi.
  private val parents = LruCache<String, Bitmap>(24)

  companion object {
    const val TILE_SIZE = 256
  }
}

/**
 * Dove sta un tile oltre lo zoom nativo dentro il suo padre: il padre stesso e il quadrante, in
 * pixel, da ritagliare. Pura, cosi' si collauda senza una bitmap.
 */
internal data class Overzoom(val parentX: Int, val parentY: Int, val left: Int, val top: Int, val size: Int) {
  companion object {
    /** Null se il tile e' cosi' oltre che il quadrante non arriva a un pixel: non c'e' piu' niente da stirare. */
    fun of(x: Int, y: Int, zoom: Int, nativeMaxZoom: Int, tileSize: Int = OverzoomTileProvider.TILE_SIZE): Overzoom? {
      val levels = zoom - nativeMaxZoom
      if (levels <= 0) return Overzoom(x, y, 0, 0, tileSize)
      val factor = 1 shl levels
      val size = tileSize / factor
      if (size < 1) return null
      val parentX = x shr levels
      val parentY = y shr levels
      return Overzoom(parentX, parentY, (x - parentX * factor) * size, (y - parentY * factor) * size, size)
    }
  }
}

/**
 * Un fotogramma del radar come sorgente di tile per Google Maps.
 *
 * RainViewer serve i tile fino allo zoom 12 con dettaglio pieno; oltre, si ingrandisce il 12.
 */
class RainViewerTileProvider(
  private val frames: RadarFrames,
  private val frame: RadarFrame,
) : OverzoomTileProvider(nativeMaxZoom = NATIVE_MAX_ZOOM) {

  override fun tileUrl(x: Int, y: Int, zoom: Int): URL? =
    runCatching { URL(frames.tileUrl(frame, zoom, x, y)) }.getOrNull()

  companion object {
    const val NATIVE_MAX_ZOOM = 12
  }
}

/** I tile di OpenWeatherMap, con la chiave dell'utente: temperatura e vento lisci. */
class OwmTileProvider(
  private val layer: String,
  private val apiKey: String,
) : OverzoomTileProvider(nativeMaxZoom = NATIVE_MAX_ZOOM) {

  override fun tileUrl(x: Int, y: Int, zoom: Int): URL? =
    runCatching { URL("https://tile.openweathermap.org/map/$layer/$zoom/$x/$y.png?appid=$apiKey") }.getOrNull()

  companion object {
    /** Le mappe meteo di OWM sono a griglia larga: oltre il 10 non c'e' piu' niente da vedere. */
    const val NATIVE_MAX_ZOOM = 10
  }
}
