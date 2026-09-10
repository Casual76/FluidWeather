package dev.pampa.fluidweather.testbench.stages

import dev.pampa.fluidweather.core.model.ActivityKind
import dev.pampa.fluidweather.core.model.PressureSample
import dev.pampa.fluidweather.core.model.SampleSource
import dev.pampa.fluidweather.nowcast.cleaning.CleaningPipeline
import dev.pampa.fluidweather.nowcast.cleaning.RejectionReason
import dev.pampa.fluidweather.nowcast.cleaning.SeaLevel
import dev.pampa.fluidweather.nowcast.tide.ClimatologicalTide
import dev.pampa.fluidweather.nowcast.tide.FittedTide
import dev.pampa.fluidweather.nowcast.tide.HarmonicFitter
import dev.pampa.fluidweather.testbench.data.StationDataset
import dev.pampa.fluidweather.testbench.events.EventWindow
import dev.pampa.fluidweather.testbench.events.PrecipitationEvents
import dev.pampa.fluidweather.testbench.replay.SampleSynthesizer
import dev.pampa.fluidweather.testbench.replay.SamplingProfile
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sqrt

/** "Ogni stadio della pipeline ha la sua metrica, cosi' si sa quale pezzo e' peggiorato." */
object StageBenches {

  // ---------------------------------------------------------------- riduzione al mare (stadio 2)

  data class ReductionReport(
    val samples: Int,
    /** MAE della nostra ipsometrica con temperatura reale contro la MSL del provider. */
    val maeRealTemperatureHpa: Double,
    /** La stessa, fingendo di non conoscere la temperatura (atmosfera standard). */
    val maeStandardTemperatureHpa: Double,
  )

  /**
   * Il provider riduce con la sua fisica e i suoi dati: la sua MSL e' il riferimento. La
   * distanza fra le due colonne dice quanto vale conoscere la temperatura vera — a Denver
   * (1600 m) e' la differenza fra un barometro e un generatore di numeri.
   */
  fun reduction(dataset: StationDataset): ReductionReport {
    var realSum = 0.0
    var standardSum = 0.0
    var count = 0
    for (record in dataset.records) {
      val surface = record.surfacePressureHpa ?: continue
      val msl = record.pressureMslHpa ?: continue
      val temperature = record.temperatureC ?: continue
      realSum += abs(SeaLevel.reduce(surface, dataset.elevationMeters, temperature) - msl)
      standardSum += abs(SeaLevel.reduce(surface, dataset.elevationMeters) - msl)
      count++
    }
    return ReductionReport(
      samples = count,
      maeRealTemperatureHpa = realSum / count,
      maeStandardTemperatureHpa = standardSum / count,
    )
  }

  // ------------------------------------------------------- la persistenza, misurata (stadio 5b)

  data class PersistenceReport(val cases: Int, val byWindow: Map<String, Double>)

  /**
   * **Dato che sta piovendo adesso, quanto e' probabile che piova in ciascuna finestra?**
   *
   * Non e' curiosita': e' il numero che autorizza il pavimento dell'osservazione. Se il quarto
   * d'ora o il radar dicono che sta piovendo, il verdetto non puo' stare sotto questa frequenza —
   * e la frequenza va misurata sugli archivi, non scelta a occhio.
   */
  fun persistence(dataset: StationDataset): PersistenceReport {
    val events = PrecipitationEvents(dataset.records)
    val wet = mutableMapOf<String, Int>()
    val total = mutableMapOf<String, Int>()
    var cases = 0
    for (record in dataset.records) {
      val now = record.timestampMillis
      if (events.rainingAt(now) != true) continue
      cases++
      for (window in EventWindow.Standard) {
        val occurred = events.occurred(now, window) ?: continue
        total[window.label] = (total[window.label] ?: 0) + 1
        if (occurred) wet[window.label] = (wet[window.label] ?: 0) + 1
      }
    }
    return PersistenceReport(
      cases = cases,
      byWindow = total.mapValues { (label, count) -> (wet[label] ?: 0).toDouble() / count },
    )
  }

  // --------------------------------------------------------- il rumore della quota (stadio 2b)

  data class AltitudeNoiseReport(
    val evaluations: Int,
    /** Scarto medio della tendenza a 3 ore fra GPS che balla e GPS perfetto (hPa/h). */
    val trendMaeHpaPerHour: Double,
    /** Il peggiore dei due mondi: la differenza massima vista. */
    val trendMaxHpaPerHour: Double,
    /** Ruvidita' del livello filtrato: media di |L(i) - L(i-1)| (hPa). */
    val roughnessJitterHpa: Double,
    val roughnessCleanHpa: Double,
    /** Le stesse due misure con la pipeline di prima: quota del singolo campione, sempre. */
    val legacyTrendMaeHpaPerHour: Double,
    val legacyRoughnessHpa: Double,
  )

  /**
   * La pipeline com'era prima della traccia di quota: finestra della mediana a zero e nessuna
   * isteresi vuol dire "ogni punto si riduce con la propria quota GPS", che e' esattamente il
   * comportamento vecchio. Serve solo al confronto: nessuno la usa per davvero.
   */
  private fun legacyPipeline() = CleaningPipeline(
    altitudeStepMeters = 0.0,
    altitudeMedianWindowMillis = 0L,
  )

  /**
   * La domanda a cui questo stadio risponde: **quanto della tendenza che leggiamo e' meteo, e
   * quanto e' il GPS?**
   *
   * Si rigioca lo stesso telefono due volte, sugli stessi identici istanti e con lo stesso
   * rumore di sensore: una volta con l errore verticale del fused provider, una volta con un GPS
   * che non sbaglia mai. Se la traccia di quota fa il suo mestiere le due tendenze coincidono; se
   * non lo facesse, dieci metri di ballonzolamento varrebbero 1,2 hPa e la differenza si
   * vedrebbe a occhio nudo.
   */
  fun altitudeNoise(
    dataset: StationDataset,
    pipeline: CleaningPipeline = CleaningPipeline(),
    days: Int = 30,
  ): AltitudeNoiseReport {
    val jittery = SampleSynthesizer(dataset, SamplingProfile.TELEFONO)
    val clean = SampleSynthesizer(dataset, SamplingProfile.TELEFONO, altitudeSigmaMeters = 0.0)
    val first = dataset.records.first().timestampMillis + 24 * 3_600_000L
    val last = minOf(dataset.records.last().timestampMillis, first + days * 86_400_000L)

    val legacy = legacyPipeline()
    var sum = 0.0
    var worst = 0.0
    var legacySum = 0.0
    var roughJitter = 0.0
    var roughClean = 0.0
    var roughLegacy = 0.0
    var count = 0
    var now = first
    while (now <= last) {
      val from = now - 24 * 3_600_000L
      val jitterySamples = jittery.samplesBetween(from, now)
      val a = pipeline.process(jitterySamples, referenceAltitudeMeters = dataset.elevationMeters)
      val b = pipeline.process(clean.samplesBetween(from, now), referenceAltitudeMeters = dataset.elevationMeters)
      val old = legacy.process(jitterySamples, referenceAltitudeMeters = dataset.elevationMeters)
      val ta = a.latest?.trendHpaPerHour
      val tb = b.latest?.trendHpaPerHour
      if (ta != null && tb != null) {
        val delta = abs(ta - tb)
        sum += delta
        if (delta > worst) worst = delta
        old.latest?.let { legacySum += abs(it.trendHpaPerHour - tb) }
        roughJitter += roughness(a.filtered.map { it.levelHpa })
        roughClean += roughness(b.filtered.map { it.levelHpa })
        roughLegacy += roughness(old.filtered.map { it.levelHpa })
        count++
      }
      now += 3 * 3_600_000L
    }
    if (count == 0) {
      return AltitudeNoiseReport(0, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN)
    }
    return AltitudeNoiseReport(
      evaluations = count,
      trendMaeHpaPerHour = sum / count,
      trendMaxHpaPerHour = worst,
      roughnessJitterHpa = roughJitter / count,
      roughnessCleanHpa = roughClean / count,
      legacyTrendMaeHpaPerHour = legacySum / count,
      legacyRoughnessHpa = roughLegacy / count,
    )
  }

  private fun roughness(levels: List<Double>): Double {
    if (levels.size < 2) return 0.0
    var sum = 0.0
    for (i in 1 until levels.size) sum += abs(levels[i] - levels[i - 1])
    return sum / (levels.size - 1)
  }

  // ------------------------------------------------------------------- marea residua (stadio 3)

  data class TideReport(
    val s1InDataHpa: Double,
    val s2InDataHpa: Double,
    val s1AfterPriorHpa: Double,
    val s2AfterPriorHpa: Double,
    val s1AfterLocalFitHpa: Double,
    val s2AfterLocalFitHpa: Double,
  )

  /**
   * Quanta marea c'e' nei dati, quanta ne resta dopo il prior climatologico, quanta dopo il
   * fit locale. Il fit e' in-sample (stimato e valutato sugli stessi due anni): e' il tetto di
   * quello che lo stadio 3 puo' togliere, non una promessa out-of-sample — dichiarato.
   */
  fun tide(dataset: StationDataset): TideReport {
    val fitter = HarmonicFitter()
    val longitude = dataset.location.longitude
    val seaLevel = seaLevelSeries(dataset)

    fun residualAmplitudes(subtract: (Long) -> Double): Pair<Double, Double> {
      val residual = seaLevel.map { HarmonicFitter.Sample(it.timestampMillis, it.valueHpa - subtract(it.timestampMillis)) }
      val fit = fitter.fit(residual, longitude) ?: return Double.NaN to Double.NaN
      return hypot(fit.a1, fit.b1) to hypot(fit.a2, fit.b2)
    }

    val (s1Data, s2Data) = residualAmplitudes { 0.0 }

    val prior = ClimatologicalTide(
      dataset.location.latitude,
      longitude,
      dataset.records.last().timestampMillis,
    )
    val (s1Prior, s2Prior) = residualAmplitudes { prior.tideAt(it) }

    val localFit = fitter.fit(seaLevel, longitude)
    val local = localFit?.let { FittedTide(longitude, it.a1, it.b1, it.a2, it.b2) }
    val (s1Local, s2Local) = if (local != null) residualAmplitudes { local.tideAt(it) } else Double.NaN to Double.NaN

    return TideReport(s1Data, s2Data, s1Prior, s2Prior, s1Local, s2Local)
  }

  // ---------------------------------------------------------------------- screening (stadi 1-2)

  data class ScreeningReport(
    val jumpInjected: Int,
    val jumpCaught: Int,
    val jumpFalsePositives: Int,
    val vehicleInjected: Int,
    val vehicleCaught: Int,
  )

  /**
   * Artefatti iniettati in dati veri, in posizioni deterministiche. I salti si provano su
   * fette dense (campionamento continuo a 10 s, interpolato sugli ancoraggi orari): e' la
   * cadenza a cui un ascensore e' fisicamente distinguibile dal meteo. A cadenza oraria non lo
   * e' — quel limite e' scritto nel progetto, non nascosto nei numeri.
   */
  fun screening(dataset: StationDataset, pipeline: CleaningPipeline = CleaningPipeline()): ScreeningReport {
    var jumpInjected = 0
    var jumpCaught = 0
    var jumpFalsePositives = 0

    // Cinque fette dense di 6 ore, distribuite lungo il dataset.
    val sliceStarts = (1..5).map { i ->
      dataset.records.first().timestampMillis + i * (dataset.records.size / 6) * 3_600_000L
    }
    for (sliceStart in sliceStarts) {
      val dense = denseSlice(dataset, sliceStart, hours = 6) ?: continue
      // Tre ascensori per fetta: +1,2 hPa per due minuti, a offset fissi.
      val artifactStarts = listOf(60, 180, 300).map { sliceStart + it * 60_000L }
      val contaminated = dense.map { sample ->
        val inArtifact = artifactStarts.any { start ->
          sample.timestampMillis >= start && sample.timestampMillis < start + 2 * 60_000L
        }
        if (inArtifact) sample.copy(pressureHpa = sample.pressureHpa - 1.2) else sample
      }
      jumpInjected += artifactStarts.size
      val result = pipeline.process(contaminated)
      val rejectedJumps = result.rejected.filter { it.reason == RejectionReason.NON_WEATHER_JUMP }
      for (start in artifactStarts) {
        val caught = rejectedJumps.any {
          it.point.timestampMillis >= start && it.point.timestampMillis < start + 2 * 60_000L
        }
        if (caught) jumpCaught++
      }
      jumpFalsePositives += rejectedJumps.count { rejected ->
        artifactStarts.none { start ->
          rejected.point.timestampMillis >= start && rejected.point.timestampMillis < start + 2 * 60_000L
        }
      }
    }

    // Viaggi in auto: quattro ore etichettate IN_VEHICLE dentro 48 ore di serie oraria.
    var vehicleInjected = 0
    var vehicleCaught = 0
    val synthesizer = SampleSynthesizer(dataset)
    val rideStart = dataset.records.first().timestampMillis + 24 * 3_600_000L
    val window = synthesizer.samplesBetween(rideStart - 24 * 3_600_000L, rideStart + 24 * 3_600_000L)
    if (window.size > 40) {
      val contaminated = window.map { sample ->
        val hoursIn = (sample.timestampMillis - rideStart) / 3_600_000.0
        if (hoursIn >= 0 && hoursIn < 4) {
          vehicleInjected++
          sample.copy(activity = ActivityKind.IN_VEHICLE, activityConfidence = 90)
        } else {
          sample
        }
      }
      val result = pipeline.process(contaminated)
      vehicleCaught = result.rejected.count { it.reason == RejectionReason.VEHICLE }
    }

    return ScreeningReport(jumpInjected, jumpCaught, jumpFalsePositives, vehicleInjected, vehicleCaught)
  }

  /** Campioni CONTINUOUS a 10 s interpolati sugli ancoraggi orari, con rumore da sensore. */
  private fun denseSlice(dataset: StationDataset, fromMillis: Long, hours: Int): List<PressureSample>? {
    val anchors = dataset.records
      .filter { it.timestampMillis in (fromMillis - 3_600_000L)..(fromMillis + (hours + 1) * 3_600_000L) }
      .mapNotNull { record -> record.surfacePressureHpa?.let { record.timestampMillis to it } }
    if (anchors.size < hours) return null
    val samples = mutableListOf<PressureSample>()
    var index = 0
    var t = fromMillis
    val end = fromMillis + hours * 3_600_000L
    while (t < end) {
      while (index < anchors.size - 2 && anchors[index + 1].first <= t) index++
      val (t0, p0) = anchors[index]
      val (t1, p1) = anchors[index + 1]
      if (t !in t0..t1) return null
      val fraction = (t - t0).toDouble() / (t1 - t0)
      val noise = if ((t / 10_000L) % 2 == 0L) 0.02 else -0.02
      samples += PressureSample(
        timestampMillis = t,
        pressureHpa = p0 + fraction * (p1 - p0) + noise,
        source = SampleSource.CONTINUOUS,
        altitudeMeters = dataset.elevationMeters,
        latitude = dataset.location.latitude,
        longitude = dataset.location.longitude,
        activity = ActivityKind.STILL,
        activityConfidence = 100,
      )
      t += 10_000L
    }
    return samples
  }

  // -------------------------------------------------------------- predittivita' della tendenza

  data class TrendReport(
    val evaluations: Int,
    /** Correlazione fra la tendenza stimata adesso e la variazione sinottica delle 3 ore dopo. */
    val correlation: Double,
    /** MAE fra il cambiamento previsto (tendenza x 3h) e quello osservato, de-tidalizzati. */
    val maeHpa: Double,
  )

  /**
   * La tendenza del Kalman e' un'affermazione sul futuro prossimo: qui viene presa in parola.
   * Il confronto e' sulla variazione della MSL del provider, de-tidalizzata con lo stesso
   * prior, cosi' si misura il sinottico e non il ciclo solare.
   */
  fun trend(dataset: StationDataset, pipeline: CleaningPipeline = CleaningPipeline()): TrendReport {
    val synthesizer = SampleSynthesizer(dataset)
    val prior = ClimatologicalTide(
      dataset.location.latitude,
      dataset.location.longitude,
      dataset.records.last().timestampMillis,
    )
    val mslByTime = dataset.records
      .mapNotNull { r -> r.pressureMslHpa?.let { r.timestampMillis to (it - prior.tideAt(r.timestampMillis)) } }
      .toMap()

    val predicted = mutableListOf<Double>()
    val observed = mutableListOf<Double>()
    var now = dataset.records.first().timestampMillis + 72 * 3_600_000L
    val last = dataset.records.last().timestampMillis - 3 * 3_600_000L
    while (now <= last) {
      val mslNow = mslByTime[now]
      val mslLater = mslByTime[now + 3 * 3_600_000L]
      if (mslNow != null && mslLater != null) {
        val samples = synthesizer.samplesBetween(now - 72 * 3_600_000L, now)
        if (samples.size >= 24) {
          val temperature = dataset.records.lastOrNull { it.timestampMillis <= now }?.temperatureC
          val trend = pipeline.process(samples, temperatureCelsius = temperature)
            .latest?.trendHpaPerHour
          if (trend != null) {
            predicted += trend * 3.0
            observed += mslLater - mslNow
          }
        }
      }
      now += 6 * 3_600_000L
    }

    return TrendReport(
      evaluations = predicted.size,
      correlation = correlation(predicted, observed),
      maeHpa = predicted.indices.sumOf { abs(predicted[it] - observed[it]) } / predicted.size,
    )
  }

  private fun correlation(a: List<Double>, b: List<Double>): Double {
    if (a.size < 2) return Double.NaN
    val meanA = a.average()
    val meanB = b.average()
    var covariance = 0.0
    var varianceA = 0.0
    var varianceB = 0.0
    for (i in a.indices) {
      covariance += (a[i] - meanA) * (b[i] - meanB)
      varianceA += (a[i] - meanA) * (a[i] - meanA)
      varianceB += (b[i] - meanB) * (b[i] - meanB)
    }
    return covariance / sqrt(varianceA * varianceB)
  }

  private fun seaLevelSeries(dataset: StationDataset): List<HarmonicFitter.Sample> =
    dataset.records.mapNotNull { record ->
      val surface = record.surfacePressureHpa ?: return@mapNotNull null
      val temperature = record.temperatureC ?: return@mapNotNull null
      HarmonicFitter.Sample(
        record.timestampMillis,
        SeaLevel.reduce(surface, dataset.elevationMeters, temperature),
      )
    }
}
