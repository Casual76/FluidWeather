package dev.pampa.fluidweather.feature.radar

import androidx.annotation.StringRes
import dev.pampa.fluidweather.strings.R

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
 * Da dove viene ogni pezzo della mappa del radar, in un posto solo.
 *
 * La base cartografica era Google Maps SDK: proprietario, quindi fuori da un'app GPL-3.0 (l'engine
 * 3.0 lo e', decisione di Alessio). Ora e' MapLibre Native (BSD-2-Clause) con i tile vettoriali di
 * OpenFreeMap: niente chiave, niente registrazione, uso da app permesso anche commerciale, a una
 * condizione, l'attribuzione ("© OpenMapTiles", "Data from OpenStreetMap"), che la schermata
 * scrive sotto la barra del tempo. tile.openstreetmap.org no: la sua policy vieta l'uso pesante da
 * un'app. OpenFreeMap e' infrastruttura di altri, a donazioni e senza garanzie: il giorno in cui
 * andasse cambiata, si cambia qui.
 */
internal object RadarMapCatalog {

  /**
   * Gli stili per tema. Il radar chiede una mappa che stia zitta sotto i colori della pioggia:
   * `positron` (chiaro, grigio) e `dark` (scuro, quasi nero), non `liberty`, che e' una stradale
   * a colori pieni. Lo stesso principio della mappa Google in tema chiaro o scuro di prima, con
   * meno rumore.
   */
  const val STYLE_LIGHT = "https://tiles.openfreemap.org/styles/positron"
  const val STYLE_DARK = "https://tiles.openfreemap.org/styles/dark"

  fun styleUrl(dark: Boolean): String = if (dark) STYLE_DARK else STYLE_LIGHT

  /** I tile dei servizi del radar sono da 256 px, come quelli che Google chiedeva. */
  const val TILE_SIZE = 256

  /**
   * Fin dove RainViewer disegna davvero. Il codice di prima chiedeva fino al 12: dal vivo
   * (2026-11-02) oltre il 7 il servizio risponde 200 con un'immagine grigia "Zoom Level Not
   * Supported", e la mappa la incollava sopra l'Italia. MapLibre ingrandisce da se' il tile del
   * livello massimo di una sorgente (`maxzoom`): e' quello che faceva a mano
   * l'OverzoomTileProvider, senza scaricare niente di piu'.
   */
  const val RAINVIEWER_MAX_ZOOM = 7f

  /** Le mappe meteo di OWM sono a griglia larga: oltre il 10 non c'e' piu' niente da vedere. */
  const val OWM_MAX_ZOOM = 10f

  /** Le opacita' di prima: `transparency` 0,15 per la pioggia e 0,1 per OWM. */
  const val PRECIPITATION_OPACITY = 0.85f
  const val OWM_OPACITY = 0.9f

  fun frameSourceId(index: Int): String = "radar-frame-$index"
  fun frameLayerId(index: Int): String = "radar-frame-layer-$index"
  const val OWM_SOURCE = "radar-owm"
  const val OWM_LAYER = "radar-owm-layer"

  /** I tile di OpenWeatherMap, con la chiave dell'utente: temperatura e vento lisci. */
  fun owmTemplate(layer: String, apiKey: String): String =
    "https://tile.openweathermap.org/map/$layer/{z}/{x}/{y}.png?appid=$apiKey"

  /**
   * Lo zoom di MapLibre per lo zoom che il radar usava con Google. MapLibre misura il mondo in
   * tile da 512 punti, Google da 256: a parita' di numero MapLibre e' un livello piu' vicino. I
   * numeri del radar (Italia a 5, la localita' a 7, "dove sono" a 8) restano quelli decisi, e
   * l'inquadratura resta la stessa.
   */
  fun zoom(googleZoom: Double): Double = googleZoom - 1.0

  /** Da 3 a 16 come prima: lontano quanto basta per un continente, vicino quanto una citta'. */
  val MIN_ZOOM: Double = zoom(3.0)
  val MAX_ZOOM: Double = zoom(16.0)

  /** Un solo fotogramma si vede; gli altri restano montati, trasparenti. */
  fun frameOpacity(index: Int, shown: Int, precipitation: Boolean): Float =
    if (precipitation && index == shown) PRECIPITATION_OPACITY else 0f

  /**
   * Il nome dei posti nella lingua dell'app. Gli stili di OpenFreeMap scrivono `name_en`; in
   * italiano Firenze resterebbe "Florence". Null quando lo stile va gia' bene cosi'.
   */
  fun labelLanguage(language: String): String? = language.lowercase().takeIf { it.isNotBlank() && it != "en" }
}
