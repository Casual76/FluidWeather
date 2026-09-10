package dev.pampa.fluidweather.core.weather

import dev.pampa.fluidweather.core.data.BarometerBaseline
import dev.pampa.fluidweather.core.data.BarometerBaselineStore
import dev.pampa.fluidweather.core.data.CalibrationStore
import dev.pampa.fluidweather.core.data.LearningRepository
import dev.pampa.fluidweather.core.data.LearningStore
import dev.pampa.fluidweather.core.data.NowcastHistoryStore
import dev.pampa.fluidweather.core.data.PressureRepository
import dev.pampa.fluidweather.core.model.BarometerReadiness
import dev.pampa.fluidweather.core.model.CalibrationRecord
import dev.pampa.fluidweather.core.model.DeviceCalibration
import dev.pampa.fluidweather.core.model.FusionVariables
import dev.pampa.fluidweather.core.model.NowcastReadiness
import dev.pampa.fluidweather.core.model.PressureSample
import dev.pampa.fluidweather.core.model.nearestHour
import dev.pampa.fluidweather.nowcast.cleaning.CleaningPipeline
import dev.pampa.fluidweather.nowcast.cleaning.CleaningResult
import dev.pampa.fluidweather.nowcast.features.FeatureExtractor
import dev.pampa.fluidweather.nowcast.learning.LearningState
import dev.pampa.fluidweather.nowcast.learning.LearningStateBuilder
import dev.pampa.fluidweather.nowcast.learning.NowcastEngine
import dev.pampa.fluidweather.nowcast.learning.NowcastExplanation
import dev.pampa.fluidweather.nowcast.learning.RainObservation
import dev.pampa.fluidweather.nowcast.cleaning.SeaLevel
import dev.pampa.fluidweather.nowcast.verdict.AlertLevel
import dev.pampa.fluidweather.nowcast.verdict.NowcastVerdict
import dev.pampa.fluidweather.nowcast.verdict.toRecord
import kotlin.math.abs
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Tutto quello che la pipeline del barometro sa dire adesso, in un colpo solo. */
data class NowcastSnapshot(
  val nowMillis: Long,
  val samples: List<PressureSample>,
  val calibration: CalibrationRecord?,
  val cleaning: CleaningResult?,
  /** Le 16 feature nell'ordine di [FeatureExtractor.names]; null se la storia non basta. */
  val features: DoubleArray?,
  val explanation: NowcastExplanation?,
  val readiness: BarometerReadiness,
  /** La quota con cui la riduzione ha lavorato, e la normale con cui si e' misurata l'anomalia. */
  val reductionAltitudeMeters: Double? = null,
  val normalHpa: Double? = null,
) {
  val verdict: NowcastVerdict? get() = explanation?.verdict
  val observation: RainObservation? get() = explanation?.observation
  val latestRawPressureHpa: Double? get() = samples.maxByOrNull { it.timestampMillis }?.pressureHpa
  val historyHours: Double get() = cleaning?.historyHours ?: 0.0
}

/**
 * Gli stadi 1-5 del nowcast in un solo punto: campioni delle ultime 24 ore -> pulizia (con la
 * taratura e la temperatura fusa) -> feature (col contesto dell'opinione piu' completa) -> verdetto
 * ricalibrato e corretto dagli analoghi. Prima viveva due volte, nella home e nel ciclo in
 * background (fase 19): due copie della stessa catena erano due modi di divergere.
 *
 * Lo stato dell'apprendimento e' caro da ricostruire (archivio delle issue) e cambia raramente:
 * si tiene in cache per un'ora, come faceva il ciclo.
 */
class NowcastUseCase(
  private val pressureRepository: PressureRepository,
  private val cleaningPipeline: CleaningPipeline,
  private val calibrationStore: CalibrationStore,
  private val learningRepository: LearningRepository,
  private val learningStore: LearningStore,
  private val nowcastHistory: NowcastHistoryStore,
  private val baselineStore: BarometerBaselineStore,
  private val engine: NowcastEngine = NowcastEngine.trained(),
  /**
   * Il radar, quando qualcuno lo fornisce. Sta dietro una lambda e non dietro una dipendenza
   * perche' costa tile da scaricare: si accende solo quando c'e' qualcosa da guardare (vedi
   * [worthLookingAtRadar]), non a ogni giro.
   */
  private val radarObservation: suspend (Double, Double) -> RainObservation? = { _, _ -> null },
  private val learningCacheMillis: Long = LEARNING_CACHE_MILLIS,
) {

  /**
   * Lo stato dell'apprendimento in cache, condiviso.
   *
   * Questa istanza e' unica per l'app: la home, il ciclo in background e l'assistente la chiamano
   * dai loro thread. Con un `var` semplice due chiamate vicine ricostruivano lo stato due volte
   * (l'archivio delle issue: disco e conti) e una poteva sovrascrivere la cache dell'altra con un
   * valore piu' vecchio. Il mutex serializza la ricostruzione; il riferimento e' volatile perche'
   * la lettura veloce, quella che nel 99% dei casi trova la cache buona, resta fuori dal lucchetto.
   */
  @Volatile
  private var learningCache: Pair<Long, LearningState>? = null
  private val learningMutex = Mutex()

  /**
   * Il verdetto di adesso. [record] = true scrive anche nello storico dei verdetti (throttlato
   * dallo store a uno ogni dieci minuti): lo fanno la home e il ciclo, non l'assistente.
   */
  suspend fun evaluate(
    snapshot: WeatherSnapshot?,
    nowMillis: Long,
    calibrationProgress: Pair<Int, Int>? = null,
    record: Boolean = false,
  ): NowcastSnapshot {
    val baseline = runCatching { baselineStore.current() }.getOrNull()
    // L'ultima temperatura nota, non i quindici gradi standard: offline e online devono ridurre
    // con lo stesso righello, altrimenti la stessa serie mostra due pressioni diverse.
    val temperature = snapshot?.fused?.hours
      ?.nearestHour(nowMillis)?.first
      ?.values?.get(FusionVariables.TEMPERATURE)?.value
      ?: baseline?.lastTemperatureC
    val samples = runCatching { pressureRepository.samplesSince(nowMillis - HISTORY_WINDOW_MILLIS) }.getOrDefault(emptyList())
    val calibration = runCatching { calibrationStore.current() }.getOrNull()
    val deviceCalibration = calibration?.toDeviceCalibration() ?: DeviceCalibration()
    val cleaning = runCatching {
      cleaningPipeline.process(
        samples,
        calibration = deviceCalibration,
        temperatureCelsius = temperature,
        // La quota di casa, la stessa a ogni giro. Passarla e' cio' che impedisce alla curva di
        // traslare quando la finestra scorre, e al ballonzolamento del GPS di entrare nel segnale.
        referenceAltitudeMeters = baseline?.referenceAltitudeMeters ?: calibration?.altitudeMeters,
        latitude = snapshot?.latitude ?: baseline?.latitude,
        longitude = snapshot?.longitude ?: baseline?.longitude,
      )
    }.getOrNull()

    val normal = runCatching { normalHpa(baseline, deviceCalibration, cleaning, temperature, nowMillis) }.getOrNull()
    val features = cleaning?.let {
      FeatureExtractor.extract(it, snapshot?.context?.toContext(nowMillis), normalHpa = normal, nowMillis = nowMillis)
    }

    val learning = learningState(nowMillis)
    // Due passate: la prima senza osservazione, per sapere se vale la pena accendere il radar;
    // la seconda con quello che si e' visto. Il motore e' aritmetica pura, e chiamarlo due volte
    // costa infinitamente meno che scaricare tile di radar a ogni giro.
    val blind = features?.let { engine.evaluate(it, learning) }
    val observation = runCatching { observe(snapshot, blind, nowMillis) }.getOrNull()
    val explanation = if (observation == null) blind else features?.let { engine.evaluate(it, learning, observation) }

    if (record) explanation?.verdict?.let { runCatching { nowcastHistory.record(it.toRecord(nowMillis)) } }
    runCatching { rememberAltitude(cleaning) }
    runCatching { baselineStore.observePlace(temperature, snapshot?.latitude, snapshot?.longitude) }

    val historyHours = cleaning?.historyHours ?: 0.0
    return NowcastSnapshot(
      nowMillis = nowMillis,
      samples = samples,
      calibration = calibration,
      cleaning = cleaning,
      features = features,
      explanation = explanation,
      readiness = NowcastReadiness.of(
        calibration = calibration,
        calibrationProgress = calibrationProgress,
        historyHours = historyHours,
        requiredHours = FeatureExtractor.MIN_HISTORY_HOURS,
      ),
      reductionAltitudeMeters = cleaning?.reductionAltitudeMeters,
      normalHpa = normal,
    )
  }

  /**
   * Il segnale pulito su una finestra qualsiasi, con la taratura e la quota di casa.
   *
   * Esiste perche' di segnali puliti ce n'erano tre: la home passava dalla taratura e dalla
   * temperatura fusa, la pagina Pressione e la Diagnostica dai valori di default. Cambiare il
   * chip del periodo traslava la curva esattamente di `biasHpa`, senza che nei dati fosse
   * cambiato niente — e la Diagnostica mostrava un terzo grafico diverso da entrambi.
   */
  suspend fun clean(
    sinceMillis: Long,
    temperatureCelsius: Double? = null,
  ): CleaningResult? {
    val samples = runCatching { pressureRepository.samplesSince(sinceMillis) }.getOrNull() ?: return null
    val calibration = runCatching { calibrationStore.current() }.getOrNull()
    val baseline = runCatching { baselineStore.current() }.getOrNull()
    return runCatching {
      cleaningPipeline.process(
        samples,
        calibration = calibration?.toDeviceCalibration() ?: DeviceCalibration(),
        temperatureCelsius = temperatureCelsius ?: baseline?.lastTemperatureC,
        referenceAltitudeMeters = baseline?.referenceAltitudeMeters ?: calibration?.altitudeMeters,
        latitude = baseline?.latitude,
        longitude = baseline?.longitude,
      )
    }.getOrNull()
  }

  /**
   * Solo "a che punto e' il barometro", senza far girare il modello.
   *
   * Serve alla home per tenere la barra viva mentre e' aperta: prima la readiness si calcolava
   * **una volta sola**, dentro il caricamento, e chi lasciava la home aperta vedeva un numero
   * fermo per ore. Ma rifare [evaluate] ogni minuto per una barra vorrebbe dire feature, motore,
   * analoghi e una scrittura nello storico: qui bastano i campioni puliti e la taratura.
   */
  suspend fun readiness(
    nowMillis: Long,
    calibrationProgress: Pair<Int, Int>?,
  ): BarometerReadiness = NowcastReadiness.of(
    calibration = runCatching { calibrationStore.current() }.getOrNull(),
    calibrationProgress = calibrationProgress,
    historyHours = clean(nowMillis - HISTORY_WINDOW_MILLIS)?.historyHours ?: 0.0,
    requiredHours = FeatureExtractor.MIN_HISTORY_HOURS,
  )

  /** La tendenza pulita delle ultime [hours] ore: il cambio di marcia della sorveglianza. */
  suspend fun cleanTrend(hours: Int, nowMillis: Long): Double? =
    clean(nowMillis - hours * 3_600_000L)?.latest?.trendHpaPerHour

  /** Cosa si vede fuori: il quarto d'ora sempre, il radar solo quando c'e' qualcosa da guardare. */
  private suspend fun observe(
    snapshot: WeatherSnapshot?,
    blind: NowcastExplanation?,
    nowMillis: Long,
  ): RainObservation? {
    val minutely = RainObservations.fromMinutely(snapshot?.context, nowMillis)
    if (snapshot == null || !worthLookingAtRadar(minutely, blind)) return minutely
    val radar = runCatching { radarObservation(snapshot.latitude, snapshot.longitude) }.getOrNull()
    return RainObservations.merge(minutely, radar)
  }

  /**
   * Il radar costa tile scaricate, quindi non si accende per abitudine. Si accende quando il
   * quarto d'ora dice che piove (per confermare e misurare meglio), quando il quarto d'ora non
   * c'e' affatto (e allora e' l'unica fonte che parla del presente), o quando il barometro e'
   * inquieto — che sono i tre casi in cui la risposta puo' cambiare il verdetto.
   *
   * Nessun verdetto ancora (le prime tredici ore dopo l'installazione) non e' inquietudine: e'
   * silenzio, e non c'e' niente da confermare.
   */
  private fun worthLookingAtRadar(minutely: RainObservation?, blind: NowcastExplanation?): Boolean = when {
    minutely == null -> true
    minutely.rainingNow -> true
    blind == null -> false
    else -> blind.verdict.level != AlertLevel.QUIETE
  }

  /**
   * La normale: media del livello sui 30 giorni, la stessa definizione con cui il modello e'
   * stato addestrato. Si ricalcola una volta ogni sei ore — e' la media di un mese, non cambia
   * fra un giro e l'altro — e non esiste finche' l'archivio non ha almeno quindici giorni dietro.
   */
  private suspend fun normalHpa(
    baseline: BarometerBaseline?,
    calibration: DeviceCalibration,
    cleaning: CleaningResult?,
    temperatureCelsius: Double?,
    nowMillis: Long,
  ): Double? {
    val fresh = baseline?.normalHpa?.takeIf { nowMillis - baseline.normalUpdatedAtMillis < NORMAL_REFRESH_MILLIS }
    if (fresh != null) return fresh

    val oldest = pressureRepository.oldestSampleMillis() ?: return baseline?.normalHpa
    val days = (nowMillis - oldest) / 86_400_000.0
    if (days < BarometerBaselineStore.MIN_NORMAL_DAYS) return null

    val windowStart = nowMillis - BarometerBaselineStore.NORMAL_WINDOW_DAYS * 86_400_000L
    val averageStation = pressureRepository.averageStationPressureSince(windowStart) ?: return baseline?.normalHpa
    // La riduzione e' una moltiplicazione: la media delle ridotte e' la ridotta della media, a
    // quota e temperatura fisse. Si usano quelle di adesso, le stesse con cui si riduce il
    // livello — cosi' l'anomalia e' una differenza fra grandezze omogenee, non fra due righelli.
    val altitude = cleaning?.reductionAltitudeMeters ?: baseline?.referenceAltitudeMeters ?: 0.0
    val normal = SeaLevel.reduce(
      averageStation - calibration.biasHpa,
      altitude,
      temperatureCelsius ?: SeaLevel.STANDARD_TEMPERATURE_CELSIUS,
    )
    runCatching { baselineStore.saveNormal(normal, nowMillis) }
    return normal
  }

  /**
   * La quota osservata torna nella memoria lenta: la mediana delle quote tenute in questa
   * finestra. Senza, la quota di riferimento resterebbe per sempre quella della taratura, e chi
   * trasloca ridurrebbe per sempre alla quota della casa vecchia.
   */
  private suspend fun rememberAltitude(cleaning: CleaningResult?) {
    val altitudes = cleaning?.cleaned?.mapNotNull { it.altitudeMeters }?.sorted().orEmpty()
    if (altitudes.size < MIN_ALTITUDES_TO_LEARN) return
    baselineStore.observeAltitude(altitudes[altitudes.size / 2])
  }

  /** Lo stato dell'apprendimento (mappe di Platt, analoghi), in cache per un'ora. */
  suspend fun learningState(nowMillis: Long): LearningState {
    fresh(nowMillis)?.let { return it }
    return learningMutex.withLock {
      // Ricontrollo dentro il lucchetto: chi ha aspettato in coda trova il lavoro gia' fatto.
      fresh(nowMillis)?.let { return@withLock it }
      val state = runCatching {
        LearningStateBuilder.build(
          platt = learningStore.current(),
          issues = learningRepository.issuesSince(nowMillis - LearningRepository.KEEP_MILLIS),
          outcomes = learningRepository.outcomesSince(nowMillis - LearningRepository.KEEP_MILLIS),
        )
      }.getOrDefault(LearningState.EMPTY)
      learningCache = nowMillis to state
      state
    }
  }

  private fun fresh(nowMillis: Long): LearningState? =
    learningCache?.takeIf { nowMillis - it.first < learningCacheMillis }?.second

  fun invalidateLearning() {
    learningCache = null
  }

  companion object {
    /**
     * Ventiquattro ore, non dodici: il modello vuole tredici ore di segnale pulito, e con una
     * finestra di dodici il verdetto non poteva esistere (baco visto sul telefono, 2026-09-02).
     */
    const val HISTORY_WINDOW_MILLIS: Long = 24 * 3_600_000L
    const val LEARNING_CACHE_MILLIS: Long = 3_600_000L

    /** La normale e' la media di un mese: ricalcolarla piu' spesso di cosi' e' lavoro sprecato. */
    const val NORMAL_REFRESH_MILLIS: Long = 6 * 3_600_000L

    /** Sotto una decina di fix la mediana delle quote non e' una mediana, e' un caso. */
    const val MIN_ALTITUDES_TO_LEARN: Int = 10
  }
}
