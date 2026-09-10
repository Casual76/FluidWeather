package dev.pampa.fluidweather.nowcast.cleaning

import dev.pampa.fluidweather.core.model.PressureSample
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot

/**
 * Un tratto **fermo** della raffica di taratura: stessa quota, stesso posto, telefono non in
 * viaggio. E' l'unita' su cui si stima il bias.
 *
 * Prima l'unita' era la raffica intera: dieci minuti di letture ridotte al mare con **una sola**
 * quota, la mediana di tutte. Per chi resta in salotto e' la stessa cosa; per chi durante quei
 * dieci minuti passa per tre paesi a quote diverse e' un numero che non descrive nessuno dei tre —
 * dieci metri di quota valgono circa 1,2 hPa, e la taratura intera vale qualche decimo.
 */
data class CalibrationSegment(
  val fromMillis: Long,
  val toMillis: Long,
  /** Le letture di stazione del tratto, in ordine di tempo. */
  val pressures: List<Double>,
  /** La quota mediana del tratto; null quando il permesso manca o il fix non e' arrivato. */
  val altitudeMeters: Double?,
  val latitude: Double?,
  val longitude: Double?,
) {
  val durationSeconds: Int get() = (((toMillis - fromMillis) / 1000L) + 1).toInt()
}

/**
 * La raffica spezzata nei suoi tratti fermi. Puro, senza Android: si collauda sul computer.
 *
 * Il criterio e' deliberatamente ottuso: **nel dubbio, si tiene**. Un telefono senza permesso di
 * posizione non ha quota ne' coordinate, e li' non c'e' niente da segmentare — la taratura si
 * comporta esattamente come prima, un tratto solo, e la fiducia lo dichiara come ha sempre fatto.
 * L'informazione che manca non deve diventare un sospetto.
 */
object CalibrationSegments {

  /**
   * Quanto puo' ballare la quota dentro un tratto. Il rumore verticale del GPS e' ~±10 m
   * all'aperto, quindi otto metri e' stretto: ma dentro una raffica di taratura i campioni
   * arrivano ogni secondo, e un tratto che si spezza per rumore diventa due tratti buoni, non un
   * tratto perso. Sbagliare per prudenza, qui, costa poco.
   */
  const val STABLE_ALTITUDE_M: Double = 8.0

  /** Oltre questo raggio non e' piu' lo stesso posto, e il riferimento dei provider nemmeno. */
  const val STABLE_RADIUS_M: Double = 150.0

  /**
   * Sotto un minuto non e' un tratto: e' il tempo che ci mette il GPS a decidersi, e una mediana
   * su pochi secondi eredita il rumore del sensore invece di mediarlo via.
   */
  const val MIN_SEGMENT_SECONDS: Int = 60

  fun of(
    samples: List<PressureSample>,
    stableAltitudeMeters: Double = STABLE_ALTITUDE_M,
    stableRadiusMeters: Double = STABLE_RADIUS_M,
    minSegmentSeconds: Int = MIN_SEGMENT_SECONDS,
  ): List<CalibrationSegment> {
    val ordered = samples.sortedBy { it.timestampMillis }
    val segments = mutableListOf<CalibrationSegment>()
    var run = mutableListOf<PressureSample>()

    fun close() {
      val segment = run.toSegment()
      run = mutableListOf()
      if (segment != null && segment.durationSeconds >= minSegmentSeconds) segments += segment
    }

    for (sample in ordered) {
      // In viaggio si butta, e si chiude il tratto: e' la scelta esplicita di scartare il
      // movimento invece di pesarlo poco. Una lettura presa in auto non e' una lettura imprecisa,
      // e' la pressione di una cabina.
      if (TransitRule.isInTransit(sample.activity, sample.activityConfidence)) {
        close()
        continue
      }
      val anchor = run.firstOrNull()
      if (anchor != null && !sample.staysWith(anchor, stableAltitudeMeters, stableRadiusMeters)) {
        close()
      }
      run += sample
    }
    close()
    return segments
  }

  /**
   * Il tempo utile totale: quanto della raffica e' finito dentro un tratto valido.
   *
   * E' questo, non i minuti passati, cio' che la raffica aspetta prima di stimare — ed e' anche
   * cio' che ha senso mostrare come progresso a chi sta camminando.
   */
  fun usefulSeconds(segments: List<CalibrationSegment>): Int = segments.sumOf { it.durationSeconds }

  /**
   * Distanza in metri, approssimazione equirettangolare.
   *
   * Sotto il chilometro l'errore contro la formula dell'emisenoverso e' di centimetri, e qui la
   * soglia e' centocinquanta metri: la trigonometria sferica sarebbe precisione spesa per niente.
   */
  fun metersBetween(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val meanLatRad = Math.toRadians((lat1 + lat2) / 2.0)
    val dLat = Math.toRadians(lat2 - lat1) * EARTH_RADIUS_M
    val dLon = Math.toRadians(lon2 - lon1) * EARTH_RADIUS_M * cos(meanLatRad)
    return hypot(dLat, dLon)
  }

  private fun PressureSample.staysWith(
    anchor: PressureSample,
    stableAltitudeMeters: Double,
    stableRadiusMeters: Double,
  ): Boolean {
    val altitudeHere = altitudeMeters
    val altitudeThere = anchor.altitudeMeters
    if (altitudeHere != null && altitudeThere != null &&
      abs(altitudeHere - altitudeThere) > stableAltitudeMeters
    ) {
      return false
    }
    val latHere = latitude
    val lonHere = longitude
    val latThere = anchor.latitude
    val lonThere = anchor.longitude
    if (latHere != null && lonHere != null && latThere != null && lonThere != null) {
      if (metersBetween(latHere, lonHere, latThere, lonThere) > stableRadiusMeters) return false
    }
    return true
  }

  private fun List<PressureSample>.toSegment(): CalibrationSegment? {
    if (isEmpty()) return null
    return CalibrationSegment(
      fromMillis = first().timestampMillis,
      toMillis = last().timestampMillis,
      pressures = map { it.pressureHpa },
      altitudeMeters = CalibrationMath.median(mapNotNull { it.altitudeMeters }),
      latitude = CalibrationMath.median(mapNotNull { it.latitude }),
      longitude = CalibrationMath.median(mapNotNull { it.longitude }),
    )
  }

  private const val EARTH_RADIUS_M = 6_371_000.0
}

/**
 * Il riferimento dei provider **con il suo quando e il suo dove**.
 *
 * Prima era un numero solo, chiesto una volta a raffica finita: qualunque cosa fosse successa nei
 * dieci minuti, tutte le letture venivano confrontate con la pressione al mare dell'ultimo posto.
 * Adesso se ne raccoglie uno per tratto, e ogni tratto trova il suo.
 */
data class CalibrationReferenceSample(
  val atMillis: Long,
  val mslHpa: Double,
  val temperatureCelsius: Double?,
  val latitude: Double?,
  val longitude: Double?,
) {

  /**
   * Quanto questo riferimento vale per quel tratto: 1 se e' li' e adesso, e scende con la
   * distanza nel tempo e nello spazio. Non e' una soglia perche' un riferimento lontano non e'
   * sbagliato — la pressione al mare e' un campo liscio — e' solo meno informativo.
   */
  fun weightFor(segment: CalibrationSegment): Double {
    val middle = (segment.fromMillis + segment.toMillis) / 2
    val minutes = abs(middle - atMillis) / 60_000.0
    val latHere = latitude
    val lonHere = longitude
    val latThere = segment.latitude
    val lonThere = segment.longitude
    val km = if (latHere != null && lonHere != null && latThere != null && lonThere != null) {
      CalibrationSegments.metersBetween(latHere, lonHere, latThere, lonThere) / 1000.0
    } else {
      0.0
    }
    return 1.0 / (1.0 + minutes / TOLERATED_MINUTES + km / TOLERATED_KM)
  }

  private companion object {
    const val TOLERATED_MINUTES = 30.0
    const val TOLERATED_KM = 10.0
  }
}
