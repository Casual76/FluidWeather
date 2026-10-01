package dev.pampa.fluidweather.core.weather

import dev.pampa.fluidweather.core.model.ForecastBundle
import dev.pampa.fluidweather.core.model.FusedForecast
import dev.pampa.fluidweather.core.model.HourlyPoint
import dev.pampa.fluidweather.core.model.NowcastIssueRecord
import dev.pampa.fluidweather.core.model.PressureSample
import dev.pampa.fluidweather.core.model.RainBoardIds
import dev.pampa.fluidweather.core.model.SampleSource
import dev.pampa.fluidweather.nowcast.cleaning.CleaningPipeline
import dev.pampa.fluidweather.nowcast.climatology.LocalBaselines
import dev.pampa.fluidweather.nowcast.climatology.WindowClimatology
import dev.pampa.fluidweather.nowcast.features.ContextTier
import dev.pampa.fluidweather.nowcast.features.FeatureExtractor
import dev.pampa.fluidweather.nowcast.features.NowcastContext
import dev.pampa.fluidweather.nowcast.learning.LearningState
import dev.pampa.fluidweather.nowcast.learning.NowcastEngine
import dev.pampa.fluidweather.nowcast.verdict.AlertLevel
import dev.pampa.fluidweather.nowcast.verdict.ModelVersions
import dev.pampa.fluidweather.nowcast.verdict.NowcastVerdict
import dev.pampa.fluidweather.nowcast.verdict.WindowVerdict
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * L'iscrizione di un giro: tutti sullo stesso giro, i giudici fuori, chi non ha PoP fuori, il
 * barometro solo dove c'e'.
 *
 * Il giro e' alle 10:20: la 0-1h e' lo slot delle 12, la 1-3h quelli delle 13 e 14, la 3-6h quelli
 * delle 15, 16 e 17.
 */
class LocalRoundRegistrarTest {

  private val roundId = MIDNIGHT + 10 * HOUR + 20 * 60_000L

  private fun at(hour: Int) = MIDNIGHT + hour * HOUR

  private fun fetch(providerId: String, hourly: List<HourlyPoint>?) = ProviderFetch(
    descriptor = ProviderRegistry.all.first { it.id == providerId },
    bundle = hourly?.let { ForecastBundle(providerId, roundId, 43.832, 11.199, it) },
    error = if (hourly == null) "niente rete" else null,
  )

  private fun pops(vararg values: Pair<Int, Double>) = values.map { (hour, pop) -> HourlyPoint(at(hour), precipitationProbabilityPercent = pop) }

  private val hourlyPops = pops(11 to 90.0, 12 to 30.0, 13 to 50.0, 14 to 70.0, 15 to 10.0, 16 to 20.0, 17 to 40.0)

  private val fetches = listOf(
    fetch(ProviderRegistry.OPEN_METEO, hourlyPops),
    // AROME e' un giudice del pannello: con la stessa PoP non deve comparire.
    fetch(ProviderRegistry.OPEN_METEO_AROME, hourlyPops),
    // MET Norway non ha PoP.
    fetch(ProviderRegistry.MET_NORWAY, listOf(HourlyPoint(at(12), precipitationMm = 1.0))),
    // Passi di tre ore che si chiudono alle 12, 15 e 18.
    fetch(ProviderRegistry.OPENWEATHERMAP, pops(12 to 20.0, 15 to 60.0, 18 to 80.0)),
    fetch(ProviderRegistry.OPEN_METEO_ICON, null),
  )

  private fun snapshot(
    placeKey: String = WeatherSnapshot.GPS_KEY,
    contextAgeMillis: Long? = 10 * 60_000L,
    contextLatitude: Double = 43.8321,
  ) = WeatherSnapshot(
    placeKey = placeKey,
    latitude = 43.8321,
    longitude = 11.1994,
    fetchedAtMillis = roundId,
    fetches = emptyList(),
    fused = FusedForecast(emptyList(), emptyMap()),
    context = contextAgeMillis?.let {
      ForecastBundle(ProviderRegistry.OPEN_METEO, roundId - it, contextLatitude, 11.1994, listOf(HourlyPoint(roundId, temperatureC = 20.0)))
    },
    predictionsRegisteredAtMillis = roundId,
  )

  private fun verdict(p01: Double, p13: Double, p36: Double) = NowcastVerdict(
    listOf(
      WindowVerdict("0-1h", p01, p01, p01, emptyList()),
      WindowVerdict("1-3h", p13, p13, p13, emptyList()),
      WindowVerdict("3-6h", p36, p36, p36, emptyList()),
    ),
    AlertLevel.QUIETE,
  )

  private val climate: LocalClimate = run {
    val truth = (1..96).associate { MIDNIGHT + it * HOUR to if (it % 17 < 3) 0.8 else 0.0 }
    val climatology = WindowClimatology.build(truth, 11.125)!!
    LocalClimate("c175_44", climatology, LocalBaselines.build(truth, climatology))
  }

  private inner class Bench(
    var hasBarometer: Boolean = true,
    var evaluation: LocalEvaluation = LocalEvaluation(
      verdict = verdict(0.15, 0.55, 0.35),
      rawVerdict = verdict(0.10, 0.50, 0.30),
      features = DoubleArray(FeatureExtractor.names.size) { it.toDouble() },
      soloVerdict = verdict(0.12, 0.40, 0.25),
    ),
    var localClimate: LocalClimate? = climate,
  ) {
    val store = InMemoryRainEventStore()
    val issues = mutableListOf<NowcastIssueRecord>()
    val evaluated = mutableListOf<Pair<WeatherSnapshot, Long>>()
    var now = roundId + 30_000L
    val registrar = LocalRoundRegistrar(
      evaluator = { snapshot, nowMillis ->
        evaluated += snapshot to nowMillis
        evaluation
      },
      store = store,
      recordIssue = { issues += it },
      climatology = { _, _ -> localClimate },
      sensorAvailable = { hasBarometer },
      clock = { now },
    )

    fun probability(providerId: String, window: String) =
      store.pending.single { it.providerId == providerId && it.window == window }.probability
  }

  @Test
  fun `tutte le righe stanno sullo stesso giro, con la versione corrente`() = runTest {
    val bench = Bench()
    val result = bench.registrar.register(RegisteredRound(roundId, snapshot(), fetches))!!

    val rows = bench.store.pending
    assertEquals(roundId, result.roundId)
    assertEquals(rows.size, result.rows)
    assertTrue(result.barometer)
    assertEquals(ContextTier.FRESH, result.tier)
    assertTrue(rows.all { it.roundId == roundId && it.issuedAtMillis == roundId })
    assertTrue(rows.all { it.modelVersion == ModelVersions.TAG })
    // Il livello del giro vale per tutte le righe tranne l'ombra, che e' per definizione senza contesto.
    val main = rows.filter { it.providerId != RainBoardIds.BAROMETER_SOLO }
    assertTrue(main.all { it.tier == ContextTier.FRESH.name })
    assertTrue(rows.filter { it.providerId == RainBoardIds.BAROMETER_SOLO }.all { it.tier == ContextTier.NONE.name })
    // Il posto della riga e' il punto a cento metri: la verita' si scarichera' li'.
    assertTrue(rows.all { it.placeKey == WeatherSnapshot.GPS_KEY && it.latitude == 43.832 && it.longitude == 11.199 })
    // Il barometro e' stato valutato su questo giro, a questo istante.
    assertEquals(roundId, bench.evaluated.single().second)
  }

  @Test
  fun `i provider parlano sugli slot esatti, i giudici e chi non ha PoP no`() = runTest {
    val bench = Bench()
    bench.registrar.register(RegisteredRound(roundId, snapshot(), fetches))

    val ids = bench.store.pending.map { it.providerId }.toSet()
    assertEquals(
      setOf(
        ProviderRegistry.OPEN_METEO,
        ProviderRegistry.OPENWEATHERMAP,
        RainBoardIds.ALWAYS_ZERO,
        RainBoardIds.CLIMATOLOGY,
        RainBoardIds.BAROMETER,
        RainBoardIds.BAROMETER_SOLO,
      ),
      ids,
    )
    assertEquals(0.30, bench.probability(ProviderRegistry.OPEN_METEO, "0-1h"), 1e-9)
    assertEquals(0.70, bench.probability(ProviderRegistry.OPEN_METEO, "1-3h"), 1e-9)
    assertEquals(0.40, bench.probability(ProviderRegistry.OPEN_METEO, "3-6h"), 1e-9)
    // OWM: il passo delle 12 per la 0-1h, quello delle 15 per la 1-3h, fino alle 18 per la 3-6h.
    assertEquals(0.20, bench.probability(ProviderRegistry.OPENWEATHERMAP, "0-1h"), 1e-9)
    assertEquals(0.60, bench.probability(ProviderRegistry.OPENWEATHERMAP, "1-3h"), 1e-9)
    assertEquals(0.80, bench.probability(ProviderRegistry.OPENWEATHERMAP, "3-6h"), 1e-9)
    assertEquals(0.0, bench.probability(RainBoardIds.ALWAYS_ZERO, "1-3h"), 0.0)
    assertEquals(climate.climatology.rate("1-3h", roundId)!!, bench.probability(RainBoardIds.CLIMATOLOGY, "1-3h"), 1e-12)
  }

  @Test
  fun `il barometro entra col verdetto finale, l'ombra col suo, e l'archivio col giro`() = runTest {
    val bench = Bench()
    bench.registrar.register(RegisteredRound(roundId, snapshot(), fetches))

    assertEquals(0.55, bench.probability(RainBoardIds.BAROMETER, "1-3h"), 1e-9)
    assertEquals(0.40, bench.probability(RainBoardIds.BAROMETER_SOLO, "1-3h"), 1e-9)

    val issue = bench.issues.single()
    assertEquals(roundId, issue.issuedAtMillis)
    assertEquals(roundId, issue.roundId)
    assertEquals(ModelVersions.TAG, issue.modelVersion)
    assertEquals(ContextTier.FRESH.name, issue.tier)
    // Le probabilita' GREZZE, non quelle ricalibrate.
    assertEquals(0.10, issue.rawProbability01, 1e-9)
    assertEquals(0.50, issue.rawProbability13, 1e-9)
    assertEquals(0.30, issue.rawProbability36, 1e-9)
    assertEquals(FeatureExtractor.names.size, issue.features.size)
  }

  @Test
  fun `l'ombra senza climatologia e' NONE_NOCLIMA, e il livello lo dice il valutatore`() = runTest {
    val noClimate = Bench(localClimate = null)
    noClimate.registrar.register(RegisteredRound(roundId, snapshot(), fetches))
    assertTrue(
      noClimate.store.pending.filter { it.providerId == RainBoardIds.BAROMETER_SOLO }
        .all { it.tier == ContextTier.NONE_NOCLIMA.name },
    )

    // Il valutatore dichiara un livello diverso da quello che il registratore ricalcolerebbe: vince il suo,
    // perche' e' quello con cui il verdetto e' stato corretto dalla mappa di Platt.
    val declared = Bench(
      evaluation = LocalEvaluation(
        verdict = verdict(0.15, 0.55, 0.35),
        rawVerdict = verdict(0.10, 0.50, 0.30),
        features = DoubleArray(FeatureExtractor.names.size),
        soloVerdict = verdict(0.12, 0.40, 0.25),
        tier = ContextTier.STALE,
        soloTier = ContextTier.NONE,
      ),
    )
    val result = declared.registrar.register(RegisteredRound(roundId, snapshot(), fetches))!!
    assertEquals(ContextTier.STALE, result.tier)
    assertEquals(ContextTier.STALE.name, declared.issues.single().tier)
    assertTrue(
      declared.store.pending.filter { it.providerId == RainBoardIds.BAROMETER }.all { it.tier == ContextTier.STALE.name },
    )
  }

  @Test
  fun `l'emissione ombra si scrive a un millisecondo dal giro, sullo stesso giro`() = runTest {
    val soloFeatures = DoubleArray(FeatureExtractor.names.size) { -1.0 }
    val bench = Bench(
      evaluation = LocalEvaluation(
        verdict = verdict(0.15, 0.55, 0.35),
        rawVerdict = verdict(0.10, 0.50, 0.30),
        features = DoubleArray(FeatureExtractor.names.size) { it.toDouble() },
        soloVerdict = verdict(0.12, 0.40, 0.25),
        tier = ContextTier.FRESH,
        soloRawVerdict = verdict(0.08, 0.35, 0.20),
        soloFeatures = soloFeatures,
        soloTier = ContextTier.NONE,
      ),
    )
    bench.registrar.register(RegisteredRound(roundId, snapshot(), fetches))

    assertEquals(2, bench.issues.size)
    val shadow = bench.issues.single { it.isShadow }
    assertEquals(roundId + NowcastIssueRecord.SHADOW_OFFSET_MILLIS, shadow.issuedAtMillis)
    assertEquals(roundId, shadow.roundId)
    assertEquals(roundId, shadow.outcomeKey)
    assertEquals(ContextTier.NONE.name, shadow.tier)
    assertEquals(ModelVersions.TAG, shadow.modelVersion)
    assertEquals(soloFeatures.toList(), shadow.features)
    assertEquals(0.35, shadow.rawProbability13, 1e-9)
    val main = bench.issues.single { !it.isShadow }
    assertEquals(roundId, main.issuedAtMillis)
    assertEquals(ContextTier.FRESH.name, main.tier)
  }

  @Test
  fun `senza barometro restano provider e riferimenti, senza livello e senza archivio`() = runTest {
    val bench = Bench(hasBarometer = false)
    val result = bench.registrar.register(RegisteredRound(roundId, snapshot(), fetches))!!

    val ids = bench.store.pending.map { it.providerId }.toSet()
    assertEquals(setOf(ProviderRegistry.OPEN_METEO, ProviderRegistry.OPENWEATHERMAP, RainBoardIds.ALWAYS_ZERO, RainBoardIds.CLIMATOLOGY), ids)
    assertTrue(bench.store.pending.all { it.tier == null })
    assertTrue("il sensore non c'e': niente valutazione", bench.evaluated.isEmpty())
    assertTrue(bench.issues.isEmpty())
    assertFalse(result.barometer)
    assertNull(result.tier)
  }

  @Test
  fun `un barometro senza verdetto non iscrive ne' riga ne' archivio`() = runTest {
    // Le prime tredici ore dopo l'installazione: il sensore c'e', la storia non basta.
    val bench = Bench(evaluation = LocalEvaluation(null, null, null, null))
    val result = bench.registrar.register(RegisteredRound(roundId, snapshot(), fetches))!!

    assertTrue(bench.store.pending.none { it.providerId == RainBoardIds.BAROMETER || it.providerId == RainBoardIds.BAROMETER_SOLO })
    assertTrue(bench.issues.isEmpty())
    assertFalse(result.barometer)
    assertEquals(ContextTier.FRESH, result.tier)
  }

  @Test
  fun `senza climatologia locale non c'e' la riga della climatologia`() = runTest {
    val bench = Bench(localClimate = null)
    bench.registrar.register(RegisteredRound(roundId, snapshot(), fetches))

    assertTrue(bench.store.pending.none { it.providerId == RainBoardIds.CLIMATOLOGY })
    assertTrue(bench.store.pending.any { it.providerId == RainBoardIds.ALWAYS_ZERO })
  }

  @Test
  fun `un giro vecchio o di un altro posto non si iscrive`() = runTest {
    val stale = Bench()
    stale.now = roundId + LocalRoundRegistrar.MAX_ROUND_LAG_MILLIS + 60_000L
    assertNull(stale.registrar.register(RegisteredRound(roundId, snapshot(), fetches)))
    assertTrue(stale.store.pending.isEmpty())
    assertTrue(stale.evaluated.isEmpty())

    val elsewhere = Bench()
    assertNull(elsewhere.registrar.register(RegisteredRound(roundId, snapshot(placeKey = WeatherSnapshot.keyFor(1)), fetches)))
    assertTrue(elsewhere.store.pending.isEmpty())
  }

  @Test
  fun `il livello di contesto - eta' del download, stesso posto, climatologia`() {
    fun tier(ageMillis: Long?, contextLatitude: Double = 43.8321, climatology: Boolean = true) =
      LocalRoundRegistrar.tierOf(snapshot(contextAgeMillis = ageMillis, contextLatitude = contextLatitude), roundId, climatology)

    assertEquals(ContextTier.FRESH, tier(30 * 60_000L))
    assertEquals(ContextTier.STALE, tier(3 * HOUR))
    assertEquals(ContextTier.NONE, tier(13 * HOUR))
    assertEquals(ContextTier.NONE, tier(null))
    assertEquals(ContextTier.NONE_NOCLIMA, tier(null, climatology = false))
    // Cinque km piu' a nord: un contesto fresco di un altro posto non e' un contesto.
    assertEquals(ContextTier.NONE, tier(30 * 60_000L, contextLatitude = 43.8771))
  }

  @Test
  fun `l'ombra solo barometro usa il contesto null`() {
    val start = roundId - 24 * HOUR
    val samples = (0..24 * 4).map { i ->
      PressureSample(start + i * 15 * 60_000L, 1016.0 - i * 0.05, SampleSource.PERIODIC)
    }
    val cleaning = CleaningPipeline().process(samples)
    val engine = NowcastEngine.trained()
    val humid = NowcastContext(
      relativeHumidityPercent = 97.0,
      dewPointSpreadC = 0.3,
      cloudCoverPercent = 100.0,
      windSpeedKmh = 25.0,
      rainLastHourMm = 2.0,
      rainLast3hMm = 5.0,
    )

    val solo = NowcastUseCase.soloExplanationOf(engine, cleaning, null, LearningState.EMPTY, roundId)!!
    val soloFeatures = FeatureExtractor.extract(cleaning, null, null, roundId)!!
    val withContext = FeatureExtractor.extract(cleaning, humid, null, roundId)!!

    assertFalse(FeatureExtractor.hasContext(soloFeatures))
    assertTrue(FeatureExtractor.hasContext(withContext))
    assertEquals(engine.evaluate(soloFeatures, LearningState.EMPTY).verdict, solo.verdict)
    assertNotEquals(engine.evaluate(withContext, LearningState.EMPTY).verdict, solo.verdict)
    assertNotNull(solo.rawVerdict)
  }

  @Test
  fun `la valutazione gira fuori dal thread di chi chiama`() = runTest {
    // La home chiama `refresh` dal thread dell'interfaccia, e il gancio del registratore gira li'
    // dentro: una valutazione intera del barometro non deve pesare sui fotogrammi.
    val caller = Thread.currentThread()
    var evaluatedOn: Thread? = null
    val bench = Bench()
    val registrar = LocalRoundRegistrar(
      evaluator = { _, _ ->
        evaluatedOn = Thread.currentThread()
        bench.evaluation
      },
      store = bench.store,
      recordIssue = {},
      climatology = { _, _ -> null },
      sensorAvailable = { true },
      clock = { bench.now },
    )

    registrar.register(RegisteredRound(roundId, snapshot(), fetches))

    val thread = evaluatedOn
    assertNotNull(thread)
    assertNotEquals(caller, thread)
    assertTrue(thread!!.name, thread.name.startsWith("DefaultDispatcher"))
  }
}
