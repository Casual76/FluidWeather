package dev.pampa.fluidweather.core.weather

import dev.pampa.fluidweather.strings.R

/** Un fotogramma del radar: quando, e il pezzo di percorso che identifica i suoi tile. */
data class RadarFrame(
  val timeMillis: Long,
  val path: String,
)

/**
 * La mappa dei fotogrammi disponibili: le ultime due ore a passi di dieci minuti (passato) e,
 * quando il servizio li serve, i fotogrammi di previsione a breve (nowcast). Il fotogramma di
 * "adesso" e' l'ultimo del passato.
 *
 * Il JSON pubblico oggi manda `nowcast: []` (verificato il 2026-09-10: la previsione a breve e'
 * passata al piano a pagamento). Il campo resta perche' il formato lo prevede e la barra del tempo
 * sa disegnarlo; se un giorno tornano fotogrammi futuri, compaiono da soli.
 */
data class RadarFrames(
  val host: String,
  val generatedMillis: Long,
  val past: List<RadarFrame>,
  val nowcast: List<RadarFrame>,
) {
  val all: List<RadarFrame> get() = past + nowcast

  val nowIndex: Int get() = (past.size - 1).coerceAtLeast(0)

  /**
   * L'URL di un tile: `{host}{path}/{size}/{z}/{x}/{y}/{color}/{smooth}_{snow}.png`. Il servizio
   * serve qualunque zoom (oltre il nativo riscalando lui): verificato dal vivo il 2026-09-02.
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
  ): String = "$host${frame.path}/$size/$zoom/$x/$y/$colorScheme/${if (smooth) 1 else 0}_${if (snow) 1 else 0}.png"
}

/**
 * RainViewer: radar composito mondiale, gratuito e senza registrazione per uso non commerciale,
 * con attribuzione ("Radar © RainViewer"). Il JSON dei fotogrammi si rinnova ogni dieci minuti;
 * la cache lo tiene cinque, cosi' un'apertura ripetuta non ricarica niente.
 */
class RainViewerClient(private val http: ProviderHttp) {

  suspend fun frames(): RadarFrames? {
    val root = runCatching { http.readJson(MAPS_URL, FRAMES_TTL_MILLIS) }.getOrNull() ?: return null
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
