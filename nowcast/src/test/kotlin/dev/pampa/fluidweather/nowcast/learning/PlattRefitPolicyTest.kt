package dev.pampa.fluidweather.nowcast.learning

import dev.pampa.fluidweather.core.model.NowcastIssueRecord
import dev.pampa.fluidweather.core.model.NowcastOutcomeRecord
import dev.pampa.fluidweather.nowcast.features.ContextTier
import dev.pampa.fluidweather.nowcast.features.FeatureExtractor
import dev.pampa.fluidweather.nowcast.verdict.ModelVersions
import java.util.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlattRefitPolicyTest {

  private val day = 86_400_000L
  private val origin = 1_750_000_000_000L - 1_750_000_000_000L % day

  // Le colonne della regola barometrica restano NaN: questi test parlano della mappa, non della prova "modello o regola".
  private fun features(): List<Double> = List(ModelVersions.CURRENT_FEATURE_COUNT) { if (it in 36..38) Double.NaN else 0.0 }

  private class Corpus(val issues: List<NowcastIssueRecord>, val outcomes: List<NowcastOutcomeRecord>)

  /**
   * [perDay] emissioni al giorno per [days] giorni, tutte sulla finestra 0-1h (le altre due
   * copiano la stessa probabilita'). La verita' e' sigma(slope * logit(p) + shift(giorno)).
   */
  private fun corpus(
    days: Int,
    perDay: Int,
    tier: String? = ContextTier.FRESH.name,
    seed: Long = 21,
    shift: (Int) -> Double = { 0.0 },
    modelVersion: String = ModelVersions.TAG,
    featureCount: Int = ModelVersions.CURRENT_FEATURE_COUNT,
  ): Corpus {
    val random = Random(seed)
    val issues = mutableListOf<NowcastIssueRecord>()
    val outcomes = mutableListOf<NowcastOutcomeRecord>()
    for (d in 0 until days) {
      for (k in 0 until perDay) {
        val at = origin + d * day + k * 600_000L + 1L
        val p = 0.05 + 0.9 * random.nextDouble()
        val truth = PlattParams.sigmoid(PlattParams.logit(p) + shift(d))
        val rained = random.nextDouble() < truth
        issues += NowcastIssueRecord(
          issuedAtMillis = at,
          features = List(featureCount) { 0.0 },
          rawProbability01 = p,
          rawProbability13 = p,
          rawProbability36 = p,
          modelVersion = modelVersion,
          tier = tier,
          roundId = at,
        )
        outcomes += NowcastOutcomeRecord(at, "0-1h", rained)
        outcomes += NowcastOutcomeRecord(at, "1-3h", rained)
        outcomes += NowcastOutcomeRecord(at, "3-6h", rained)
      }
    }
    return Corpus(issues, outcomes)
  }

  private fun Corpus.fit(variant: PlattVariant = PlattVariant.FRESH, window: String = "0-1h") =
    PlattRefitPolicy.refit(issues, outcomes, origin + 100 * day).maps.first { it.variant == variant && it.window == window }

  @Test
  fun `dati gia' calibrati non guadagnano niente fuori campione, quindi la mappa resta spenta`() {
    val fit = corpus(days = 60, perDay = 10).fit()
    assertNotNull(fit.params)
    assertEquals(PlattStatus.NO_OUT_OF_SAMPLE_GAIN, fit.status)
    assertFalse(fit.status.active)
    assertTrue(fit.guardUpperBound!! >= 0.0)
  }

  @Test
  fun `un grezzo che sovrastima sempre guadagna davvero fuori campione e la mappa si accende`() {
    val fit = corpus(days = 60, perDay = 10, shift = { -1.5 }).fit()
    assertEquals(PlattStatus.ACTIVE, fit.status)
    assertTrue("limite alto ${fit.guardUpperBound}", fit.guardUpperBound!! < 0.0)
    assertTrue(fit.guardDeltaBrier!! < 0.0)
    assertTrue(fit.guardTestDays >= PlattRefitPolicy.MIN_TEST_DAYS)
    assertTrue(fit.params!!.b < -0.5)
  }

  @Test
  fun `una scorrettezza solo nel 70 per cento piu' vecchio non supera la prova sul recente`() {
    // I primi 42 giorni su 60 sono sbilanciati, gli ultimi 18 calibrati: la mappa stimata sui vecchi
    // peggiora i nuovi. Una prova dentro il campione la accenderebbe; fuori campione no.
    val fit = corpus(days = 60, perDay = 10, shift = { if (it < 42) -1.5 else 0.0 }).fit()
    assertNotNull(fit.params)
    assertEquals(PlattStatus.NO_OUT_OF_SAMPLE_GAIN, fit.status)
    assertTrue(fit.guardDeltaBrier!! > 0.0)
  }

  @Test
  fun `meno di cinque giorni di prova e la mappa resta spenta per prudenza`() {
    val fit = corpus(days = 4, perDay = 40, shift = { -1.5 }).fit()
    assertNotNull(fit.params)
    assertEquals(PlattStatus.GUARD_TOO_FEW_TEST, fit.status)
    assertNull(fit.guardUpperBound)
  }

  @Test
  fun `il taglio non spezza i giorni`() {
    val corpus = corpus(days = 30, perDay = 10, shift = { -1.5 })
    val samples = PlattRefitPolicy.corpus(PlattVariant.FRESH, "0-1h", corpus.issues, corpus.outcomes)
    val guard = PlattRefitPolicy.guard(samples)
    // 300 casi: il 70% cade proprio all'inizio del ventiduesimo giorno, quindi la prova e' di 9 giorni interi (90 casi).
    assertEquals(9, guard.testDays)
  }

  @Test
  fun `il livello instrada alla variante, e nessun livello vuol dire nessuna variante`() {
    val fresh = corpus(days = 3, perDay = 10, tier = ContextTier.FRESH.name, seed = 1)
    val none = corpus(days = 3, perDay = 10, tier = ContextTier.NONE.name, seed = 2)
    // NONE e NONE_NOCLIMA vanno nella stessa variante, quindi su giri diversi (cinque minuti dopo):
    // un giro solo non ha due emissioni vere, e nella stessa variante conterebbe una volta.
    val noClima = corpus(days = 3, perDay = 10, tier = ContextTier.NONE_NOCLIMA.name, seed = 3).let { c ->
      Corpus(
        c.issues.map { it.copy(issuedAtMillis = it.issuedAtMillis + 300_000L, roundId = it.roundId + 300_000L) },
        c.outcomes.map { it.copy(issuedAtMillis = it.issuedAtMillis + 300_000L) },
      )
    }
    val stale = corpus(days = 3, perDay = 10, tier = ContextTier.STALE.name, seed = 4)
    val untiered = corpus(days = 3, perDay = 10, tier = null, seed = 5)
    val issues = fresh.issues + none.issues + noClima.issues + stale.issues + untiered.issues
    val outcomes = fresh.outcomes + none.outcomes + noClima.outcomes + stale.outcomes + untiered.outcomes
    // Gli stessi istanti in tutti i corpi: si distinguono solo per il livello. Le chiavi d'esito si
    // sovrappongono, ed e' il caso peggiore per l'instradamento.
    fun count(variant: PlattVariant) = PlattRefitPolicy.corpus(variant, "0-1h", issues, outcomes.distinctBy { it.issuedAtMillis to it.window }).size

    assertEquals(30, count(PlattVariant.FRESH))
    assertEquals(30, count(PlattVariant.STALE))
    assertEquals(60, count(PlattVariant.NONE))
    assertEquals(0, count(PlattVariant.ENHANCED))
  }

  @Test
  fun `l'emissione ombra trova l'esito del suo giro e finisce nella variante none`() {
    val round = origin + 5 * day
    val main = NowcastIssueRecord(
      round, features(), 0.4, 0.4, 0.4, ModelVersions.TAG, ContextTier.FRESH.name, roundId = round,
    )
    val shadow = NowcastIssueRecord(
      round + NowcastIssueRecord.SHADOW_OFFSET_MILLIS, features(), 0.1, 0.1, 0.1,
      ModelVersions.TAG, ContextTier.NONE_NOCLIMA.name, roundId = round,
    )
    val outcomes = listOf(NowcastOutcomeRecord(round, "0-1h", true))

    val none = PlattRefitPolicy.corpus(PlattVariant.NONE, "0-1h", listOf(main, shadow), outcomes)
    val fresh = PlattRefitPolicy.corpus(PlattVariant.FRESH, "0-1h", listOf(main, shadow), outcomes)

    assertEquals(listOf(TimedCalibrationSample(round, 0.1, true)), none)
    assertEquals(listOf(TimedCalibrationSample(round, 0.4, true)), fresh)
  }

  @Test
  fun `un giro gia' senza contesto conta una volta sola nella variante none, anche con la sua ombra`() {
    val round = origin + 5 * day
    // Il verdetto vero e' NONE: l'ombra e' la stessa riga, e non deve raddoppiare il giro.
    val main = NowcastIssueRecord(
      round, features(), 0.3, 0.3, 0.3, ModelVersions.TAG, ContextTier.NONE.name, roundId = round,
    )
    val shadow = NowcastIssueRecord(
      // Un soffio diverso solo per riconoscere quale delle due e' entrata.
      round + NowcastIssueRecord.SHADOW_OFFSET_MILLIS, features(), 0.31, 0.31, 0.31,
      ModelVersions.TAG, ContextTier.NONE.name, roundId = round,
    )
    val outcomes = listOf(NowcastOutcomeRecord(round, "0-1h", false))

    // L'ordine d'ingresso non conta: vince sempre l'emissione vera.
    val none = PlattRefitPolicy.corpus(PlattVariant.NONE, "0-1h", listOf(shadow, main), outcomes)

    assertEquals(listOf(TimedCalibrationSample(round, 0.3, false)), none)
  }

  @Test
  fun `altre versioni e vettori di un'altra lunghezza non entrano nel corpo`() {
    val good = corpus(days = 2, perDay = 5, seed = 1)
    val oldModel = corpus(days = 2, perDay = 5, seed = 1, modelVersion = "v1-2025+panel-1+board-1")
    val legacy = corpus(days = 2, perDay = 5, seed = 1, modelVersion = NowcastIssueRecord.LEGACY_VERSION)
    val shortVector = corpus(days = 2, perDay = 5, seed = 1, featureCount = 16)

    val issues = good.issues + oldModel.issues + legacy.issues + shortVector.issues
    val samples = PlattRefitPolicy.corpus(PlattVariant.FRESH, "0-1h", issues, good.outcomes)

    assertEquals(good.issues.size, samples.size)
    assertEquals(good.issues.map { it.issuedAtMillis }, samples.map { it.atMillis })
  }

  @Test
  fun `la ristima da' sempre nove voci, e quelle fallite non hanno mappa`() {
    val data = corpus(days = 60, perDay = 10, shift = { -1.5 })
    val refit = PlattRefitPolicy.refit(data.issues, data.outcomes, origin + 100 * day)

    assertEquals(9, refit.maps.size)
    assertEquals(PlattVariant.FITTED.flatMap { v -> PlattRefitPolicy.WINDOWS.map { v to it } }, refit.maps.map { it.variant to it.window })
    val stale = refit.maps.filter { it.variant == PlattVariant.STALE }
    assertTrue(stale.all { it.params == null && it.status == PlattStatus.TOO_FEW_SAMPLES && it.samples == 0 })
    val fresh = refit.maps.filter { it.variant == PlattVariant.FRESH }
    assertTrue(fresh.all { it.params != null && it.status == PlattStatus.ACTIVE })

    val records = refit.records()
    assertEquals(9, records.size)
    assertTrue(records.filter { it.variant == "fresh" }.all { it.active && it.a != null && it.b != null })
    assertTrue(records.filter { it.variant == "stale" }.none { it.active || it.a != null })
    assertEquals(PlattRefitPolicy.VERSION, refit.version)
  }

  @Test
  fun `la ristima e' deterministica`() {
    val data = corpus(days = 40, perDay = 10, shift = { -0.8 })
    val one = PlattRefitPolicy.refit(data.issues, data.outcomes, origin + 100 * day)
    val two = PlattRefitPolicy.refit(data.issues.reversed(), data.outcomes.reversed(), origin + 100 * day)
    assertEquals(one, two)
  }

  @Test
  fun `tocca ristimare per versione diversa, per tempo o per orologio tornato indietro`() {
    val now = origin + 10 * day
    val version = PlattRefitPolicy.VERSION
    assertFalse(PlattRefitPolicy.isDue(now, now - 3_600_000L, version))
    assertTrue(PlattRefitPolicy.isDue(now, now - PlattRefitPolicy.REFIT_INTERVAL_MILLIS, version))
    assertTrue(PlattRefitPolicy.isDue(now, now - 3_600_000L, "altra"))
    assertTrue(PlattRefitPolicy.isDue(now, now - 3_600_000L, null))
    assertTrue(PlattRefitPolicy.isDue(now, now + 1, version))
    assertEquals(ModelVersions.TAG + "#" + PlattRefitPolicy.RULES_VERSION, version)
  }

  @Test
  fun `le varianti si leggono dal livello e dalla chiave`() {
    assertEquals(PlattVariant.NONE, PlattVariant.of(ContextTier.NONE_NOCLIMA))
    assertEquals(PlattVariant.STALE, PlattVariant.ofTierName("STALE"))
    assertNull(PlattVariant.ofTierName("BOH"))
    assertNull(PlattVariant.ofTierName(null))
    assertEquals(PlattVariant.ENHANCED, PlattVariant.byKey("enh"))
    assertNull(PlattVariant.byKey("x"))
    assertEquals("platt_none", "platt_" + PlattVariant.NONE.key)
  }
}
