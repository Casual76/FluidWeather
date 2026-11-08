package dev.pampa.fluidweather.core.weather

import dev.pampa.fluidweather.strings.R
import kotlinx.coroutines.CancellationException

/** Un fotogramma del radar: quando, e il pezzo di percorso che identifica i suoi tile. */
data class RadarFrame(
  val timeMillis: Long,
  val path: String,
)

/** Past frames have a ten-minute cadence. Future products were discontinued on 2026-01-01.
 * `nowcast` remains a display-format compatibility field, never observation evidence.
 * https://www.rainviewer.com/api/transition-faq.html
 */
data class RadarFrames(
  val host: String,
  val generatedMillis: Long,
  val past: List<RadarFrame>,
  val nowcast: List<RadarFrame>,
  val downloadedAtMillis: Long? = null,
) {
  fun coverageUrl(zoom: Int, x: Int, y: Int, size: Int = 256): String =
    "$host/v2/coverage/0/$size/$zoom/$x/$y/0/0_0.png"

  val all: List<RadarFrame> get() = past + nowcast

  val nowIndex: Int get() = (past.size - 1).coerceAtLeast(0)

  companion object {
    /**
     * L'ultimo livello di zoom che RainViewer disegna davvero. La mappa lo passa come `maxzoom` della
     * sorgente (MapLibre ingrandisce da se' il tile del livello massimo), e il radar letto come numeri
     * ([dev.pampa.fluidweather.core.weather.RadarFrames] per `RadarSampler`) non ne chiede mai di piu'.
     */
    const val MAX_ZOOM: Int = 7
  }

  /**
   * L'URL di un tile: `{host}{path}/{size}/{z}/{x}/{y}/{color}/{smooth}_{snow}.png`. **Lo zoom non
   * supera [MAX_ZOOM]**: oltre il servizio risponde 200 con un'immagine grigia "Zoom Level Not
   * Supported" (verificato dal vivo il 2026-11-02; il 2026-09-02 serviva qualunque zoom), che un
   * lettore di pixel prende per radar e una mappa incolla sopra il paese.
   */
  fun tileUrl(
    frame: RadarFrame,
    zoom: Int,
    x: Int,
    y: Int,
    colorScheme: Int = RainViewerPalette.UNIVERSAL_BLUE,
    smooth: Boolean = true,
    snow: Boolean = true,
    size: Int = 256,
  ): String = tileTemplate(frame, colorScheme, smooth, snow, size)
    .replace("{z}", zoom.toString())
    .replace("{x}", x.toString())
    .replace("{y}", y.toString())

  /**
   * Lo stesso indirizzo con i segnaposto `{z}/{x}/{y}` al posto dei numeri: e' la forma che una
   * sorgente raster di MapLibre vuole, e il formato resta scritto in un posto solo.
   */
  fun tileTemplate(
    frame: RadarFrame,
    colorScheme: Int = RainViewerPalette.UNIVERSAL_BLUE,
    smooth: Boolean = true,
    snow: Boolean = true,
    size: Int = 256,
  ): String = "$host${frame.path}/$size/{z}/{x}/{y}/$colorScheme/${if (smooth) 1 else 0}_${if (snow) 1 else 0}.png"
}

/**
 * RainViewer: radar composito mondiale, gratuito e senza registrazione per uso non commerciale,
 * con attribuzione ("Radar © RainViewer"). Il JSON dei fotogrammi si rinnova ogni dieci minuti;
 * la cache lo tiene cinque, cosi' un'apertura ripetuta non ricarica niente.
 */
class RainViewerClient(private val http: ProviderHttp) {

  suspend fun frames(): RadarFrames? {
    val response = try { http.readJsonTimed(MAPS_URL, FRAMES_TTL_MILLIS) }
      catch (cancelled: CancellationException) { throw cancelled }
      catch (_: Exception) { return null }
    val root = response.value
    val host = root["host"].string() ?: return null
    val radar = root["radar"]
    fun frames(name: String): List<RadarFrame> = radar[name].asArray().mapNotNull { frame ->
      val seconds = frame["time"].double()?.toLong() ?: return@mapNotNull null
      val path = frame["path"].string() ?: return@mapNotNull null
      RadarFrame(seconds * 1_000, path)
    }
    val past = frames("past")
    if (past.isEmpty()) return null
    return RadarFrames(
      host = host,
      generatedMillis = (root["generated"].double()?.toLong() ?: 0L) * 1_000,
      past = past,
      nowcast = frames("nowcast"),
      downloadedAtMillis = response.downloadedAtMillis,
    )
  }

  companion object {
    const val MAPS_URL = "https://api.rainviewer.com/public/weather-maps.json"
    const val FRAMES_TTL_MILLIS = 5 * 60_000L
    const val ATTRIBUTION = "Radar © RainViewer"
  }
}

/**
 * Lo schema colore "Universal Blue" (id 2) di RainViewer, dalla tabella ufficiale
 * (rainviewer.com/api/color-schemes, letta il 2026-09-02): dBZ -> colore ARGB. E' cio' che i
 * tile disegnano davvero, quindi la legenda e' onesta per costruzione.
 */
object RainViewerPalette {

  const val UNIVERSAL_BLUE = 2

  data class Stop(val dbz: Int, val argb: Long, val labelRes: Int?)

  /** I gradini della tabella ufficiale, ogni 5 dBZ; le etichette solo dove servono alla legenda. */
  val universalBlue: List<Stop> = listOf(
    Stop(5, 0x64928871L, null),
    Stop(10, 0x96CEC087L, R.string.radar_legend_drizzle),
    Stop(15, 0xFF88DDEEL, null),
    Stop(20, 0xFF00A3E0L, R.string.radar_legend_light),
    Stop(25, 0xFF0077AAL, null),
    Stop(30, 0xFF005588L, R.string.radar_legend_moderate),
    Stop(35, 0xFFFFEE00L, null),
    Stop(40, 0xFFFFAA00L, R.string.radar_legend_heavy),
    Stop(45, 0xFFFF4400L, null),
    Stop(50, 0xFFC10000L, R.string.radar_legend_very_heavy),
    Stop(55, 0xFFFFAAFFL, null),
    Stop(60, 0xFFFF77FFL, R.string.radar_legend_hail),
    Stop(65, 0xFFFFFFFFL, null),
  )

  val legend: List<Stop> get() = universalBlue.filter { it.labelRes != null }
}
