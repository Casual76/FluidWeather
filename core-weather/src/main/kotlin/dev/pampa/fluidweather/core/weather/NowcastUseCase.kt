package dev.pampa.fluidweather.core.weather

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
) {
  val verdict: NowcastVerdict? get() = explanation?.verdict
  val latestRawPressureHpa: Double? get() = samples.maxByOrNull { it.timestampMillis }?.pressureHpa
  val historyHours: Double
    get() = cleaning?.filtered?.takeIf { it.size >= 2 }
      ?.let { (it.last().timestampMillis - it.first().timestampMillis) / 3_600_000.0 } ?: 0.0
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
  private val engine: NowcastEngine = NowcastEngine.trained(),
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
    val temperature = snapshot?.fused?.hours
      ?.nearestHour(nowMillis)?.first
      ?.values?.get(FusionVariables.TEMPERATURE)?.value
    val samples = runCatching { pressureRepository.samplesSince(nowMillis - HISTORY_WINDOW_MILLIS) }.getOrDefault(emptyList())
    val calibration = runCatching { calibrationStore.current() }.getOrNull()
    val cleaning = runCatching {
      cleaningPipeline.process(
        samples,
        calibration = calibration?.toDeviceCalibration() ?: DeviceCalibration(),
        temperatureCelsius = temperature,
      )
    }.getOrNull()
    val features = cleaning?.let {
      FeatureExtractor.extract(it, snapshot?.context?.toContext(nowMillis), normalHpa = null, nowMillis = nowMillis)
    }
    val explanation = features?.let { engine.evaluate(it, learningState(nowMillis)) }
    if (record) explanation?.verdict?.let { runCatching { nowcastHistory.record(it.toRecord(nowMillis)) } }
    val historyHours = cleaning?.filtered?.takeIf { it.size >= 2 }
      ?.let { (it.last().timestampMillis - it.first().timestampMillis) / 3_600_000.0 } ?: 0.0
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
    )
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
  }
}
