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
import dev.pampa.fluidweather.core.model.SamplingCoverage
import dev.pampa.fluidweather.core.model.nearestHour
import dev.pampa.fluidweather.nowcast.cleaning.CleaningPipeline
import dev.pampa.fluidweather.nowcast.cleaning.CleaningResult
import dev.pampa.fluidweather.nowcast.features.ContextTier
import dev.pampa.fluidweather.nowcast.features.FeatureExtractor
import dev.pampa.fluidweather.nowcast.climatology.LocalPriors
import dev.pampa.fluidweather.nowcast.features.NowcastContext
import dev.pampa.fluidweather.nowcast.features.FeatureExtractorV3
import dev.pampa.fluidweather.nowcast.learning.AnalogPolicy
import dev.pampa.fluidweather.nowcast.learning.LearningState
import dev.pampa.fluidweather.nowcast.learning.LearningStateBuilder
import dev.pampa.fluidweather.nowcast.learning.NowcastEngine
import dev.pampa.fluidweather.nowcast.learning.NowcastExplanation
import dev.pampa.fluidweather.nowcast.learning.PlattRefitPolicy
import dev.pampa.fluidweather.nowcast.learning.RainObservation
import dev.pampa.fluidweather.nowcast.cleaning.SeaLevel
import dev.pampa.fluidweather.nowcast.verdict.AlertLevel
import dev.pampa.fluidweather.nowcast.verdict.ModelVersions
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
  /** Le feature del motore (le 42 di [FeatureExtractorV3.names] col v3); null se la storia non basta. */
  val features: DoubleArray?,
  val explanation: NowcastExplanation?,
  val readiness: BarometerReadiness,
  /** La quota con cui la riduzione ha lavorato, e la normale con cui si e' misurata l'anomalia. */
  val reductionAltitudeMeters: Double? = null,
  val normalHpa: Double? = null,
  /**
   * L'ombra "solo barometro": lo stesso segnale e lo stesso motore, senza il contesto dei provider e
   * senza osservazione. Null se non richiesta o se la storia non basta. Non si mostra: si registra
   * accanto al verdetto vero per sapere quanto vale il barometro da solo.
   */
  val soloExplanation: NowcastExplanation? = null,
  /**
   * Il livello di contesto con cui e' stato emesso il verdetto: quanto contesto dei provider c'era
   * (eta' dal download vero, stesso posto) e se la climatologia locale era nota. Decide quale mappa
   * di Platt corregge il verdetto, e con quale livello si iscrive il giro.
   */
  val tier: ContextTier? = null,
  /** Il livello dell'ombra "solo barometro": NONE (o NONE_NOCLIMA), mai quello del verdetto vero. */
  val soloTier: ContextTier? = null,
  /** Le feature dell'ombra (contesto null): l'archivio da cui impara la mappa "none". */
  val soloFeatures: DoubleArray? = null,
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
  private val engine: NowcastEngine = NowcastEngine.v3(),
  /**
   * Il radar, quando qualcuno lo fornisce. Sta dietro una lambda e non dietro una dipendenza
   * perche' costa tile da scaricare: si accende solo quando c'e' qualcosa da guardare (vedi
   * [worthLookingAtRadar]), non a ogni giro.
   */
  private val radarObservation: suspend (Double, Double) -> RainObservation? = { _, _ -> null },
  private val learningCacheMillis: Long = LEARNING_CACHE_MILLIS,
  /**
   * Il dispositivo ha un barometro. Una lambda e non un Boolean perche' il sensore lo conosce
   * core-sensor, che sta sopra questo modulo: il grafo dell'app fa da ponte.
   */
  private val sensorAvailable: () -> Boolean = { true },
  /**
   * La climatologia locale della cella del punto e' gia' su disco? Serve al livello di contesto
   * (NONE contro NONE_NOCLIMA). Una lambda, come `sensorAvailable`: la climatologia sta nello
   * stesso modulo ma la sua cache e' del grafo dell'app. Senza rete e senza cella: false.
   */
  private val climatologyAvailable: suspend (latitude: Double, longitude: Double) -> Boolean = { _, _ -> false },
  /**
   * Le tabelle locali (climatologia e baseline) della cella del punto, se gia' su disco. Il v3 le usa
   * come feature: sono le stesse regole "a occhio" che deve battere, cosi' in ogni posto puo' almeno
   * riprodurle. Senza, il riferimento di tutti i posti ([V3Priors.pooled]).
   */
  private val localClimate: suspend (latitude: Double, longitude: Double) -> LocalClimate? = { _, _ -> null },
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
   * [withSoloShadow] = true calcola anche l'ombra "solo barometro" ([NowcastSnapshot.soloExplanation]):
   * la chiede solo il registratore dei giri, perche' serve solo alla classifica.
   */
  suspend fun evaluate(
    snapshot: WeatherSnapshot?,
    nowMillis: Long,
    calibrationProgress: Pair<Int, Int>? = null,
    record: Boolean = false,
    withSoloShadow: Boolean = false,
    /**
     * Il punto del verdetto, quando chi chiama lo sa meglio dell'istantanea (il ciclo in background
     * risolve il punto dall'ultimo fix). Senza, si usa l'istantanea e poi la memoria lenta.
     */
    pointLatitude: Double? = null,
    pointLongitude: Double? = null,
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
    val point = pointOf(pointLatitude, pointLongitude, snapshot, baseline)
    val priors = priorsAt(point)
    val hasClimatology = priors.isLocal || climatologyKnownAt(point)
    val detected = ContextTierDetection.detect(snapshot?.context, point?.first, point?.second, nowMillis, hasClimatology)
    // Il contesto entra solo se il livello dice che e' usabile: uno vecchio di ore o di un altro
    // posto descriverebbe un altro tempo, e trattarlo da presente farebbe piu' danno che ignorarlo.
    val usable = if (detected.hasContext) snapshot?.context?.toContext(nowMillis) else null
    // Senza la pioggia dell'ultima ora chiusa il v3 non sa dire "piove adesso": a banco quelle righe
    // non esistevano, e trattarle da contesto vorrebbe dire un regime mai visto. Si scende al livello
    // senza contesto, che e' onesto sull'ignoranza.
    val context = usable?.takeIf { !engine.speaksV3 || it.rainSlotsMm?.firstOrNull() != null }
    val tier = if (detected.hasContext && context == null) noContextTier(priors) else detected
    val referenceKnown = (baseline?.referenceAltitudeMeters ?: calibration?.altitudeMeters) != null
    val features = cleaning?.let { featuresOf(engine, it, context, normal, nowMillis, priors, referenceKnown) }

    val learning = learningState(nowMillis)
    // Due passate: la prima senza osservazione, per sapere se vale la pena accendere il radar;
    // la seconda con quello che si e' visto. Il motore e' aritmetica pura, e chiamarlo due volte
    // costa infinitamente meno che scaricare tile di radar a ogni giro.
    val blind = features?.let { engine.evaluate(it, learning, tier = tier) }
    val observation = runCatching { observe(snapshot, blind, nowMillis) }.getOrNull()
    val explanation = if (observation == null) blind else features?.let { engine.evaluate(it, learning, observation, tier) }
    val soloTier = noContextTier(priors)
    val solo = if (withSoloShadow) cleaning?.let { soloOf(engine, it, normal, learning, nowMillis, soloTier, priors, referenceKnown) } else null

    if (record) explanation?.verdict?.let { runCatching { nowcastHistory.record(it.toRecord(nowMillis)) } }
    runCatching { rememberAltitude(cleaning) }
    // Solo il posto del telefono: la memoria lenta del barometro (temperatura e coordinate di casa)
    // non deve imparare da una localita' salvata o da un posto chiesto all'assistente, dove il
    // sensore non c'e'.
    if (snapshot?.placeKey == WeatherSnapshot.GPS_KEY) {
      runCatching { baselineStore.observePlace(temperature, snapshot.latitude, snapshot.longitude) }
    }

    val historyHours = cleaning?.historyHours ?: 0.0
    val blocked = samplingBlocked(samples, nowMillis)
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
        sensorAvailable = sensorAvailable(),
        samplingBlocked = blocked,
      ),
      reductionAltitudeMeters = cleaning?.reductionAltitudeMeters,
      normalHpa = normal,
      soloExplanation = solo?.first,
      tier = tier,
      soloTier = if (solo != null) soloTier else null,
      soloFeatures = solo?.second,
    )
  }

  /**
   * Il livello di contesto di un punto (l'istantanea GPS di solito), senza far girare il modello:
   * serve alla pagina Precisione del motore. Non lancia mai.
   */
  suspend fun currentTier(
    snapshot: WeatherSnapshot?,
    nowMillis: Long,
    pointLatitude: Double? = null,
    pointLongitude: Double? = null,
  ): ContextTier {
    val baseline = runCatching { baselineStore.current() }.getOrNull()
    val point = pointOf(pointLatitude, pointLongitude, snapshot, baseline)
    return ContextTierDetection.detect(
      snapshot?.context, point?.first, point?.second, nowMillis, climatologyKnownAt(point),
    )
  }

  private fun pointOf(
    latitude: Double?,
    longitude: Double?,
    snapshot: WeatherSnapshot?,
    baseline: BarometerBaseline?,
  ): Pair<Double, Double>? {
    val lat = latitude ?: snapshot?.latitude ?: baseline?.latitude
    val lon = longitude ?: snapshot?.longitude ?: baseline?.longitude
    return if (lat != null && lon != null) lat to lon else null
  }

  /** La climatologia si chiede per il punto; senza punto non si sa, e "non si sa" e' "non c'e'". */
  /** Le tabelle del punto per il v3: quelle locali se la cella e' su disco, altrimenti quelle di tutti. */
  private suspend fun priorsAt(point: Pair<Double, Double>?): LocalPriors {
    val climate = point?.let { runCatching { localClimate(it.first, it.second) }.getOrNull() }
    return V3Priors.of(climate)
  }

  /** Il livello senza contesto: NONE se le tabelle del posto ci sono, NONE_NOCLIMA altrimenti. */
  private fun noContextTier(priors: LocalPriors): ContextTier =
    if (priors.isLocal) ContextTier.NONE else ContextTier.NONE_NOCLIMA

  private suspend fun climatologyKnownAt(point: Pair<Double, Double>?): Boolean =
    point != null && runCatching { climatologyAvailable(point.first, point.second) }.getOrDefault(false)

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
    return cleanSamples(samples, temperatureCelsius)
  }

  private suspend fun cleanSamples(
    samples: List<PressureSample>,
    temperatureCelsius: Double? = null,
  ): CleaningResult? {
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
  ): BarometerReadiness {
    val calibration = runCatching { calibrationStore.current() }.getOrNull()
    if (!sensorAvailable()) {
      return NowcastReadiness.of(calibration, calibrationProgress, historyHours = 0.0, sensorAvailable = false)
    }
    val samples = runCatching { pressureRepository.samplesSince(nowMillis - HISTORY_WINDOW_MILLIS) }.getOrNull()
    return NowcastReadiness.of(
      calibration = calibration,
      calibrationProgress = calibrationProgress,
      historyHours = samples?.let { cleanSamples(it) }?.historyHours ?: 0.0,
      requiredHours = FeatureExtractor.MIN_HISTORY_HOURS,
      samplingBlocked = samples != null && samplingBlocked(samples, nowMillis),
    )
  }

  /**
   * L'archivio dice che il campionamento e' fermo (vedi [SamplingCoverage]). Non lancia: nel
   * dubbio la risposta e' "no", e la barra torna a dire quello che diceva prima.
   */
  private suspend fun samplingBlocked(samples: List<PressureSample>, nowMillis: Long): Boolean {
    if (!sensorAvailable()) return false
    val oldest = runCatching { pressureRepository.oldestSampleMillis() }.getOrNull()
    return SamplingCoverage.isBlocked(samples.map { it.timestampMillis }, nowMillis, oldest)
  }

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

  /**
   * Lo stato dell'apprendimento (mappe di Platt attive, analoghi se la politica li vuole), in cache
   * per un'ora o fino a una ristima ([invalidateLearning]).
   *
   * Le mappe si usano solo se la loro versione e' quella che il codice parla ([PlattRefitPolicy.VERSION]):
   * una mappa di un altro modello o di altre regole non corregge questo verdetto. Con gli analoghi
   * spenti (l'app) l'archivio delle emissioni non si legge affatto: erano due anni di righe da portare
   * in memoria per niente.
   */
  suspend fun learningState(nowMillis: Long): LearningState {
    fresh(nowMillis)?.let { return it }
    return learningMutex.withLock {
      // Ricontrollo dentro il lucchetto: chi ha aspettato in coda trova il lavoro gia' fatto.
      fresh(nowMillis)?.let { return@withLock it }
      val state = runCatching {
        val maps = learningStore.snapshot()
        val records = if (maps.version == PlattRefitPolicy.VERSION) maps.records else emptyList()
        val withAnalogs = engine.analogPolicy.enabled
        val since = nowMillis - LearningRepository.KEEP_MILLIS
        LearningStateBuilder.buildForVariants(
          maps = records,
          issues = if (withAnalogs) learningRepository.issuesSince(since) else emptyList(),
          outcomes = if (withAnalogs) learningRepository.outcomesSince(since) else emptyList(),
          modelVersion = ModelVersions.TAG,
          includeCases = withAnalogs,
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

    /**
     * L'ombra "solo barometro": lo stesso segnale pulito, la stessa normale e lo stesso stato
     * dell'apprendimento del verdetto vero, ma **contesto null** e nessuna osservazione — il quarto
     * d'ora e il radar sono dati dei provider anche loro. E' cio' che il telefono direbbe senza
     * rete: misurarlo accanto al verdetto vero dice quanto del merito e' del sensore. Il livello e'
     * NONE (o NONE_NOCLIMA): la mappa di Platt consultata e' quella del regime senza contesto, non
     * quella del verdetto vero.
     */
    internal fun soloExplanationOf(
      engine: NowcastEngine,
      cleaning: CleaningResult,
      normalHpa: Double?,
      learning: LearningState,
      nowMillis: Long,
      tier: ContextTier = ContextTier.NONE,
      priors: LocalPriors = V3Priors.pooledOnly,
    ): NowcastExplanation? = soloOf(engine, cleaning, normalHpa, learning, nowMillis, tier, priors)?.first

    /** L'ombra e le feature da cui viene: l'archivio della mappa "none" impara da queste. */
    internal fun soloOf(
      engine: NowcastEngine,
      cleaning: CleaningResult,
      normalHpa: Double?,
      learning: LearningState,
      nowMillis: Long,
      tier: ContextTier,
      priors: LocalPriors = V3Priors.pooledOnly,
      referenceAltitudeKnown: Boolean = true,
    ): Pair<NowcastExplanation, DoubleArray>? {
      val features = featuresOf(engine, cleaning, null, normalHpa, nowMillis, priors, referenceAltitudeKnown)
        ?: return null
      return engine.evaluate(features, learning, tier = tier) to features
    }

    /**
     * Le feature nella lingua del motore: le quarantadue del v3 quando il motore parla v3, le venti del
     * v2 altrimenti (il banco e i test che costruiscono un motore v2).
     */
    internal fun featuresOf(
      engine: NowcastEngine,
      cleaning: CleaningResult,
      context: NowcastContext?,
      normalHpa: Double?,
      nowMillis: Long,
      priors: LocalPriors,
      referenceAltitudeKnown: Boolean,
    ): DoubleArray? = if (engine.speaksV3) {
      FeatureExtractorV3.extract(cleaning, context, normalHpa, nowMillis, priors, referenceAltitudeKnown)
    } else {
      FeatureExtractor.extract(cleaning, context, normalHpa = normalHpa, nowMillis = nowMillis)
    }
  }
}
