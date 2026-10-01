package dev.pampa.fluidweather.core.model

import java.util.Locale
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RainCsvTest {

  private val roundId = 1_781_517_600_000L // 2026-06-15T10:00:00Z

  private var localePrecedente: Locale = Locale.getDefault()

  @Before
  fun italiano() {
    // Un telefono italiano: e' la lingua in cui `%.3f` scrive la virgola, cioe' un CSV rotto.
    localePrecedente = Locale.getDefault()
    Locale.setDefault(Locale.ITALY)
  }

  @After
  fun ripristina() {
    Locale.setDefault(localePrecedente)
  }

  private fun giudizio(
    providerId: String = RainBoardIds.BAROMETER,
    window: String = "1-3h",
    probability: Double = 0.4567,
    rained: Boolean = true,
    sumMm: Double? = 1.25,
    tier: String? = "FRESH",
  ) = RainEventVerification(
    prediction = RainEventPending(
      placeKey = WeatherPlaceKey,
      latitude = 43.83456,
      longitude = 11.2,
      roundId = roundId,
      providerId = providerId,
      window = window,
      issuedAtMillis = roundId,
      probability = probability,
      modelVersion = "v2-2026-09-10+panel-1+board-2",
      tier = tier,
    ),
    rained = rained,
    truthSumMm = sumMm,
    truthVoters = 3,
    truthSource = "panel-1",
    settledAtMillis = roundId + 30 * 3_600_000L,
  )

  @Test
  fun `l'intestazione delle verifiche della pioggia e' esatta`() {
    assertEquals(
      "round_id,round_iso_utc,place_key,latitude,longitude,provider_id,window,issued_at_millis," +
        "probability,model_version,tier,outcome,truth_sum_mm,truth_voters,truth_source,settled_at_millis",
      RainVerificationCsv.HEADER,
    )
  }

  @Test
  fun `una riga ha un campo per colonna, col punto decimale anche in italiano`() {
    val line = RainVerificationCsv.line(giudizio())

    assertEquals(
      "1781517600000,2026-06-15T10:00:00Z,gps,43.835,11.200,barometro,1-3h,1781517600000," +
        "0.4567,v2-2026-09-10+panel-1+board-2,FRESH,1,1.25,3,panel-1,1781625600000",
      line,
    )
    assertEquals(RainVerificationCsv.HEADER.split(',').size, line.split(',').size)
  }

  @Test
  fun `somma e livello assenti sono campi vuoti, l'esito e' 1 o 0`() {
    val line = RainVerificationCsv.line(giudizio(rained = false, sumMm = null, tier = null)).split(',')

    assertEquals("", line[10])
    assertEquals("0", line[11])
    assertEquals("", line[12])
    assertEquals(16, line.size)
  }

  @Test
  fun `le righe si ordinano per giro, id e finestra`() {
    val rows = listOf(
      giudizio(providerId = "sempre-0", window = "0-1h"),
      giudizio(providerId = "barometro", window = "3-6h"),
      giudizio(providerId = "barometro", window = "0-1h"),
    )

    val lines = RainVerificationCsv.render(rows).trimEnd().split('\n')

    assertEquals(RainVerificationCsv.HEADER, lines[0])
    assertEquals(listOf("barometro,0-1h", "barometro,3-6h", "sempre-0,0-1h"), lines.drop(1).map {
      it.split(',').subList(5, 7).joinToString(",")
    })
  }

  @Test
  fun `senza verifiche resta la sola intestazione`() {
    assertEquals(RainVerificationCsv.HEADER + "\n", RainVerificationCsv.render(emptyList()))
  }

  // ------------------------------------------------------------------ l'archivio dell'apprendimento

  private val nomi = listOf("tendenza-1h", "umidita", "copertura")

  private fun emissione(
    at: Long = roundId,
    features: List<Double> = listOf(0.5, Double.NaN, -1.23456789),
    modelVersion: String = "v2-2026-09-10+panel-1+board-2",
    tier: String? = "STALE",
  ) = NowcastIssueRecord(at, features, 0.1, 0.25, 0.3333, modelVersion, tier, roundId = at)

  @Test
  fun `l'archivio dell'apprendimento ha le feature in colonne col loro nome`() {
    val csv = LearningCsv.render(listOf(emissione()), emptyList(), nomi).trimEnd().split('\n')

    assertEquals(
      LearningCsv.HEADER_PREFIX + ",f_tendenza-1h,f_umidita,f_copertura",
      csv[0],
    )
    assertEquals(
      "1781517600000,2026-06-15T10:00:00Z,1781517600000,v2-2026-09-10+panel-1+board-2,STALE," +
        "0.1000,0.2500,0.3333,,,,0.50000,,-1.23457",
      csv[1],
    )
  }

  @Test
  fun `una feature NaN o mancante e' un campo vuoto, e una riga a sedici feature si completa`() {
    val sedici = emissione(features = List(16) { 1.0 + it })
    val venti = nomi + List(17) { "x$it" }

    val csv = LearningCsv.render(listOf(sedici), emptyList(), venti).trimEnd().split('\n')

    val fields = csv[1].split(',')
    assertEquals(csv[0].split(',').size, fields.size)
    assertEquals("16.00000", fields[11 + 15])
    // dalla diciassettesima in poi la riga non ha nulla da dire
    assertTrue(fields.drop(11 + 16).all { it.isEmpty() })
    assertEquals(11 + 20, fields.size)
  }

  @Test
  fun `un vettore piu' lungo dei nomi ha colonne per indice`() {
    val csv = LearningCsv.render(listOf(emissione(features = listOf(1.0, 2.0, 3.0, 4.0))), emptyList(), nomi)

    assertTrue(csv.lineSequence().first().endsWith(",f_tendenza-1h,f_umidita,f_copertura,f_3"))
  }

  @Test
  fun `gli esiti si agganciano per emissione e finestra, e i mancanti restano vuoti`() {
    val outcomes = listOf(
      NowcastOutcomeRecord(roundId, "0-1h", false),
      NowcastOutcomeRecord(roundId, "3-6h", true),
      NowcastOutcomeRecord(roundId + 1, "1-3h", true), // un'altra emissione: non c'entra
    )

    val riga = LearningCsv.render(listOf(emissione()), outcomes, nomi).trimEnd().split('\n')[1].split(',')

    assertEquals(listOf("0", "", "1"), riga.subList(8, 11))
  }

  @Test
  fun `l'emissione ombra prende l'esito del suo giro, e lo dichiara ombra`() {
    val ombra = NowcastIssueRecord(
      issuedAtMillis = roundId + NowcastIssueRecord.SHADOW_OFFSET_MILLIS,
      features = List(3) { 0.0 },
      rawProbability01 = 0.1,
      rawProbability13 = 0.2,
      rawProbability36 = 0.3,
      tier = "NONE",
      roundId = roundId,
    )
    assertTrue(ombra.isShadow)
    assertEquals(roundId, ombra.outcomeKey)
    assertFalse(emissione().isShadow)

    val riga = LearningCsv.render(listOf(ombra), listOf(NowcastOutcomeRecord(roundId, "1-3h", true)), nomi)
      .trimEnd().split('\n')[1].split(',')

    assertEquals(listOf("", "1", ""), riga.subList(8, 11))
  }

  @Test
  fun `le righe di prima delle versioni si riconoscono dall'etichetta legacy`() {
    val vecchia = NowcastIssueRecord(roundId - 1, List(16) { 0.0 }, 0.1, 0.2, 0.3)

    val riga = LearningCsv.render(listOf(vecchia), emptyList(), nomi).trimEnd().split('\n')[1].split(',')

    assertEquals("legacy", riga[3])
    assertEquals("", riga[4])
    assertEquals("0", riga[2])
  }

  private companion object {
    const val WeatherPlaceKey = "gps"
  }

  /** Un `suspend` che non sospende mai, eseguito sul posto: core-model non dipende dalle coroutine. */
  private fun <T> sulPosto(block: suspend () -> T): T {
    var result: Result<T>? = null
    block.startCoroutine(Continuation(EmptyCoroutineContext) { result = it })
    return result!!.getOrThrow()
  }

  @Test
  fun `l'export a pagine scrive lo stesso CSV di render, senza tenerlo tutto in memoria`() {
    val righe = (0 until 7).map { i ->
      giudizio(providerId = "p$i").let { it.copy(prediction = it.prediction.copy(roundId = roundId + i * 3_600_000L)) }
    }
    val chieste = mutableListOf<Pair<Int, Int>>()
    val out = StringBuilder()

    val scritte = sulPosto {
      RainVerificationCsv.write(out, pageSize = 3) { offset, limit ->
        chieste += offset to limit
        righe.drop(offset).take(limit)
      }
    }

    assertEquals(7, scritte)
    assertEquals(listOf(0 to 3, 3 to 3, 6 to 3), chieste)
    assertEquals(RainVerificationCsv.render(righe), out.toString())
  }

  @Test
  fun `l'export a pagine si ferma anche quando le righe sono un multiplo esatto della pagina`() {
    val righe = (0 until 6).map { giudizio(providerId = "p$it") }
    val chieste = mutableListOf<Int>()

    val scritte = sulPosto {
      RainVerificationCsv.write(StringBuilder(), pageSize = 3) { offset, limit ->
        chieste += offset
        righe.drop(offset).take(limit)
      }
    }

    assertEquals(6, scritte)
    assertEquals("l'ultima pagina, vuota, chiude il giro", listOf(0, 3, 6), chieste)
  }
}
