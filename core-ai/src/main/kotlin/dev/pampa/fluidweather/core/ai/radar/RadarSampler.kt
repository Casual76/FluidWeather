package dev.pampa.fluidweather.core.ai.radar

import dev.pampa.fluidweather.core.weather.RadarFrame
import dev.pampa.fluidweather.core.weather.RadarFrames
import dev.pampa.fluidweather.core.weather.RainViewerPalette
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

enum class RadarTrend { INTENSIFYING, STEADY, WEAKENING, NO_ECHO }

/** La lettura numerica del radar attorno a un punto: tutto cio' che il tool riporta al modello. */
data class RadarReading(
  val frameTimeMillis: Long,
  val frameAgeMin: Int,
  val boxKm: Int,
  val atPointDbz: Int,
  val nearest: NearestEcho?,
  val searchedKm: Double,
  val areaRainingPct: Int,
  val maxDbz: Int,
  val trend: RadarTrend,
  val trendDbzPer10Min: Double,
  val motion: Motion?,
  val eta: Eta?,
  /** I fotogrammi di previsione di RainViewer campionati sul punto: (+minuti, dBZ). */
  val nowcastAtPoint: List<Pair<Int, Int>>,
  val coverage: Double,
  val confidence: Double,
  val notes: List<String>,
) {
  val rainingNow: Boolean get() = atPointDbz >= EtaEstimator.RAIN_DBZ
  val confidenceLabel: String get() = if (confidence >= 0.7) "alta" else if (confidence >= 0.4) "media" else "bassa"
}

sealed interface RadarSample {
  data class Ok(val reading: RadarReading) : RadarSample
  data class Unavailable(val reason: String) : RadarSample
}

/**
 * Il radar letto come numeri (fase 19): una finestra di 128 px a zoom 8 attorno al punto, per gli
 * ultimi fotogrammi e per quelli di previsione; da li' intensita' sul posto, eco piu' vicino,
 * tendenza, moto e ETA. E' una stima da immagini, e la confidenza lo dice.
 */
class RadarSampler(
  private val frames: suspend () -> RadarFrames?,
  private val tiles: TileSource,
  private val clock: () -> Long = System::currentTimeMillis,
  private val zoom: Int = ZOOM,
  private val windowSize: Int = WINDOW,
  private val prune: () -> Unit = {},
) {

  suspend fun sample(latitude: Double, longitude: Double): RadarSample {
    if (abs(latitude) > TileMath.MAX_LATITUDE) return RadarSample.Unavailable("fuori dalla copertura del radar")
    val all = runCatching { frames() }.getOrNull() ?: return RadarSample.Unavailable("radar non raggiungibile")
    if (all.past.isEmpty()) return RadarSample.Unavailable("nessun fotogramma radar disponibile")
    runCatching { prune() }
    val now = clock()
    val center = TileMath.toGlobalPixel(latitude, longitude, zoom)
    val mpp = TileMath.metersPerPixel(latitude, zoom)
    val boxPx = (BOX_METERS / mpp).roundToInt().coerceIn(48, windowSize - 16)
    val builder = WindowBuilder(tiles, zoom, windowSize)
    val pastFrames = all.past.takeLast(PAST_FRAMES)
    val nowcastFrames = all.nowcast.take(NOWCAST_FRAMES)
    val urlOf: (RadarFrame, Int, Int) -> String = { frame, x, y ->
      all.tileUrl(frame, zoom, x, y, colorScheme = RainViewerPalette.UNIVERSAL_BLUE, smooth = false, snow = false)
    }
    val semaphore = Semaphore(4)
    val (pastWindows, nowcastWindows) = coroutineScope {
      val past = pastFrames.map { frame -> async { semaphore.withPermit { builder.build(all, frame, center, urlOf) } } }
      val next = nowcastFrames.map { frame -> async { semaphore.withPermit { builder.build(all, frame, center, urlOf) } } }
      past.awaitAll() to next.awaitAll()
    }
    val nowWindow = pastWindows.lastOrNull() ?: return RadarSample.Unavailable("tile del radar non scaricata")
    val notes = mutableListOf<String>()
    val c = nowWindow.center
    val atPoint = nowWindow.maxAround(c, c, 1).coerceAtLeast(0)
    val rainingNow = atPoint >= EtaEstimator.RAIN_DBZ
    val nearest = EtaEstimator.nearest(nowWindow, boxPx, mpp)
    val (areaFraction, maxDbz) = EtaEstimator.coverage(nowWindow, boxPx)

    // Tendenza: la pendenza del dBZ medio nel riquadro lungo i fotogrammi passati, per 10 minuti.
    val validPast = pastWindows.filterNotNull()
    val means = validPast.map { EtaEstimator.meanDbz(it, boxPx) }
    val frameStepMin = if (validPast.size >= 2) ((validPast.last().timeMillis - validPast.first().timeMillis) / 60_000.0 / (validPast.size - 1)).coerceAtLeast(1.0) else 10.0
    val slopePer10 = EtaEstimator.slope(means) * (10.0 / frameStepMin)
    val trend = when {
      means.all { it < 0.5 } -> RadarTrend.NO_ECHO
      slopePer10 > 1.5 -> RadarTrend.INTENSIFYING
      slopePer10 < -1.5 -> RadarTrend.WEAKENING
      else -> RadarTrend.STEADY
    }

    // Moto: le ultime coppie consecutive di fotogrammi passati.
    val motionWindows = validPast.takeLast(MOTION_FRAMES)
    val pairs = mutableListOf<PairMotion>()
    var pairMinutes = 10.0
    for (i in 1 until motionWindows.size) {
      val previous = motionWindows[i - 1]
      val next = motionWindows[i]
      pairMinutes = ((next.timeMillis - previous.timeMillis) / 60_000.0).coerceAtLeast(1.0)
      MotionEstimator.pairMotion(previous, next)?.let { pairs += it }
    }
    val motion = MotionEstimator.combine(pairs, mpp, pairMinutes)
    val eta = motion?.takeIf { !it.stationary }?.let { EtaEstimator.estimate(nowWindow, it, rainingNow) }
    val nowcastAtPoint = nowcastWindows.filterNotNull().map { window ->
      (((window.timeMillis - nowWindow.timeMillis) / 60_000.0).roundToInt()) to window.maxAround(c, c, 1).coerceAtLeast(0)
    }

    // Confidenza: parte da 1 e paga ogni cosa che non torna.
    var confidence = 1.0
    if (nowWindow.coverage < 0.9) { confidence -= 0.30; notes += "tile mancanti" }
    val unknownRatio = nowWindow.unknownColours.toDouble() / (windowSize * windowSize)
    if (unknownRatio > 0.02) { confidence -= 0.15; notes += "colori fuori palette" }
    if (motion == null) {
      confidence -= 0.30
      notes += if (pairs.isEmpty()) "nessun eco da seguire" else "moto non stimabile"
    } else {
      if (motion.meanPsr < 3) confidence -= 0.30 else if (motion.meanPsr < 5) confidence -= 0.15
      if (!motion.agreement) { confidence -= 0.25; notes += "fotogrammi in disaccordo sul moto" }
      if (motion.atSearchEdge) { confidence -= 0.30; notes += "celle troppo veloci per la finestra" }
      if (pairs.isNotEmpty() && pairs.last().echoFraction < 0.05) confidence -= 0.20
    }
    val ageMin = ((now - nowWindow.timeMillis) / 60_000.0).roundToInt()
    if (ageMin > 20) { confidence -= 0.15; notes += "fotogramma vecchio di $ageMin minuti" }
    val etaArrival = eta?.arrivesInMin
    val nowcastArrival = nowcastAtPoint.firstOrNull { it.second >= EtaEstimator.RAIN_DBZ }?.first
    if (etaArrival != null && nowcastArrival != null && abs(etaArrival - nowcastArrival) <= 10) confidence += 0.10
    if (validPast.all { EtaEstimator.coverage(it, boxPx).second < EtaEstimator.RAIN_DBZ } && nowcastAtPoint.all { it.second < EtaEstimator.RAIN_DBZ }) {
      notes += "nessun eco entro il riquadro nell'ultima ora, oppure zona non coperta dal radar"
    }
    if (notes.isEmpty()) notes += "nessun tile mancante"

    return RadarSample.Ok(
      RadarReading(
        frameTimeMillis = nowWindow.timeMillis,
        frameAgeMin = ageMin,
        boxKm = (boxPx * mpp / 1000.0).roundToInt(),
        atPointDbz = atPoint,
        nearest = nearest,
        searchedKm = boxPx / 2.0 * mpp / 1000.0,
        areaRainingPct = (areaFraction * 100).roundToInt(),
        maxDbz = maxDbz,
        trend = trend,
        trendDbzPer10Min = slopePer10,
        motion = motion,
        eta = eta,
        nowcastAtPoint = nowcastAtPoint,
        coverage = nowWindow.coverage,
        confidence = confidence.coerceIn(0.0, 1.0),
        notes = notes,
      ),
    )
  }

  companion object {
    /** Zoom 8: ~440 m/px a 44 gradi, una tile = 112 km; la finestra di 128 px copre ~56 km. */
    const val ZOOM = 8
    const val WINDOW = 128

    /** Il riquadro di campionamento: trenta chilometri, quanti ne coprono le celle di un'ora. */
    const val BOX_METERS = 30_000.0
    const val PAST_FRAMES = 7
    const val MOTION_FRAMES = 4
    const val NOWCAST_FRAMES = 3
  }
}
