package dev.pampa.fluidweather.nowcast.learning

import dev.pampa.fluidweather.core.model.NowcastIssueRecord
import dev.pampa.fluidweather.core.model.NowcastOutcomeRecord
import dev.pampa.fluidweather.core.model.PlattMapRecord
import dev.pampa.fluidweather.nowcast.features.ContextTier
import dev.pampa.fluidweather.nowcast.features.FeatureExtractorV3
import dev.pampa.fluidweather.nowcast.verdict.ModelVersions
import dev.pampa.fluidweather.nowcast.verdict.TieredNowcastModel
import java.util.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * La rete di sicurezza della garanzia sul telefono: senza contesto, la regola barometrica locale
 * prende il posto del modello solo dove, sui giri verificati di questo telefono, ha dimostrato di
 * fare meglio — e solo li'.
 */
class RuleFallbackTest {

  private val day = 86_400_000L
  private val origin = 1_750_000_000_000L - 1_750_000_000_000L % day

  private fun logit(p: Double) = kotlin.math.ln(p / (1 - p))

  /**
   * Giri senza contesto in cui la verita' segue la regola ([ruleIsRight]) o il modello. Il modello
   * dice sempre 0,5 (non sa niente); la regola dice 0,1 o 0,9.
   */
  private fun corpus(days: Int, perDay: Int, ruleIsRight: Boolean, seed: Long = 7): Pair<List<NowcastIssueRecord>, List<NowcastOutcomeRecord>> {
    val random = Random(seed)
    val issues = mutableListOf<NowcastIssueRecord>()
    val outcomes = mutableListOf<NowcastOutcomeRecord>()
    for (d in 0 until days) for (k in 0 until perDay) {
      val at = origin + d * day + k * 900_000L + 1L
      val rule = if (random.nextBoolean()) 0.9 else 0.1
      val truth = if (ruleIsRight) rule else 0.5
      val rained = random.nextDouble() < truth
      val features = List(FeatureExtractorV3.COUNT) { i -> if (i in 36..38) logit(rule) else 0.0 }
      issues += NowcastIssueRecord(at, features, 0.5, 0.5, 0.5, ModelVersions.TAG, ContextTier.NONE.name, roundId = at)
      for (w in listOf("0-1h", "1-3h", "3-6h")) outcomes += NowcastOutcomeRecord(at, w, rained)
    }
    return issues to outcomes
  }

  @Test
  fun `la regola subentra solo dove batte il modello con certezza, e solo nella variante none`() {
    val (issues, outcomes) = corpus(days = 20, perDay = 12, ruleIsRight = true)
    val refit = PlattRefitPolicy.refit(issues, outcomes, nowMillis = origin + 30 * day)

    val none = refit.maps.filter { it.variant == PlattVariant.NONE }
    assertEquals(3, none.size)
    assertTrue(none.all { it.useRule })
    assertTrue(none.all { (it.ruleUpperBound ?: 1.0) < 0.0 })
    assertTrue(refit.maps.filter { it.variant != PlattVariant.NONE }.none { it.useRule })
    assertTrue(refit.records().filter { it.variant == "none" }.all { it.useRule })
  }

  @Test
  fun `quando la regola non sa piu' del modello resta il modello`() {
    val (issues, outcomes) = corpus(days = 20, perDay = 12, ruleIsRight = false)
    val refit = PlattRefitPolicy.refit(issues, outcomes, nowMillis = origin + 30 * day)

    assertTrue(refit.maps.none { it.useRule })
  }

  @Test
  fun `pochi giorni di prova e la regola non subentra, per prudenza`() {
    val (issues, outcomes) = corpus(days = 4, perDay = 40, ruleIsRight = true)
    val refit = PlattRefitPolicy.refit(issues, outcomes, nowMillis = origin + 30 * day)

    assertTrue(refit.maps.none { it.useRule })
  }

  @Test
  fun `i vettori del v2 non hanno la regola e non entrano nella prova`() {
    assertEquals(null, PlattRefitPolicy.ruleProbabilityOf(List(20) { 0.0 }, "0-1h"))
    assertEquals(0.5, PlattRefitPolicy.ruleProbabilityOf(List(FeatureExtractorV3.COUNT) { 0.0 }, "1-3h")!!, 1e-12)
  }

  @Test
  fun `il motore dice la regola solo senza contesto e solo nelle finestre dimostrate`() {
    val engine = NowcastEngine.v3(TieredNowcastModel.trained())
    val rule = 0.8
    val features = DoubleArray(FeatureExtractorV3.COUNT) { i -> if (i in 36..38) logit(rule) else 0.0 }
    val record = PlattMapRecord("none", "1-3h", null, null, 0, 0, 0, "TOO_FEW_SAMPLES", false, useRule = true, fittedAtMillis = 0L)
    val learning = LearningStateBuilder.buildForVariants(listOf(record), emptyList(), emptyList(), includeCases = false)
    assertEquals(setOf("1-3h"), learning.ruleWindows)

    val offline = engine.evaluate(features, learning, tier = ContextTier.NONE)
    assertEquals(rule, offline.verdict.forWindow("1-3h")!!.probability, 1e-9)
    assertEquals(setOf("1-3h"), offline.ruleFallback)
    assertFalse(offline.verdict.forWindow("0-1h")!!.probability == rule)

    val fresh = engine.evaluate(features, learning, tier = ContextTier.FRESH)
    assertTrue(fresh.ruleFallback.isEmpty())
  }
}
