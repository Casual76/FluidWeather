package dev.pampa.fluidweather.testbench.train.v3

import dev.pampa.fluidweather.nowcast.features.ContextTier
import dev.pampa.fluidweather.nowcast.features.FeatureExtractorV3
import dev.pampa.fluidweather.nowcast.learning.LearningState
import dev.pampa.fluidweather.nowcast.learning.NowcastEngine
import dev.pampa.fluidweather.nowcast.learning.RainObservation
import dev.pampa.fluidweather.nowcast.truth.RainWindows
import dev.pampa.fluidweather.nowcast.verdict.ModelFamily
import dev.pampa.fluidweather.nowcast.verdict.ObservationFloors
import dev.pampa.fluidweather.nowcast.verdict.RainNowFloor
import dev.pampa.fluidweather.nowcast.verdict.TieredNowcastModel
import dev.pampa.fluidweather.nowcast.verdict.Tree
import dev.pampa.fluidweather.nowcast.verdict.TreeEnsemble
import dev.pampa.fluidweather.testbench.replay.V3Pipeline
import java.nio.file.Files
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** L'artefatto del v3 su disco, l'impronta, la politica dei pavimenti e la parita' dei pavimenti col motore. */
class V3ArtefactTest {

  private val count = FeatureExtractorV3.COUNT

  private fun table(tier: ContextTier, intercept: Double): LogisticTableV3 = LogisticTableV3(
    tier = tier,
    means = DoubleArray(count) { it * 0.01 },
    sds = DoubleArray(count) { 1.0 + it * 0.1 },
    used = List(3) { intArrayOf(1, 5, FeatureExtractorV3.BAROMETRIC_RULE + it) },
    bags = List(3) { w ->
      List(5) { b ->
        DoubleArray(count + 1).also {
          it[0] = intercept + 0.1 * w + 0.01 * b
          it[2] = -0.7
          it[6] = 0.2
          it[FeatureExtractorV3.BAROMETRIC_RULE + w + 1] = 1.0 + 1e-17 * b
        }
      }
    },
  )

  private fun trees(): Map<String, ByteArray> = ContextTier.entries.flatMap { tier ->
    RainWindows.ALL.indices.map { w ->
      TieredNowcastModel.resourceName(tier, w) to TreeEnsemble(
        count, tier.ordinal, w, FeatureExtractorV3.BAROMETRIC_RULE + w, -0.1, -1.7,
        listOf(Tree(byteArrayOf(Tree.NAN_LEFT.toByte(), 1, 1), floatArrayOf(0f, 0.2f, -0.2f), intArrayOf(1, -1, -1), floatArrayOf(-0.5f, Float.NaN, Float.NaN), intArrayOf(2, -1, -1))),
      ).encode()
    }
  }.toMap()

  private fun artefact(family: ModelFamily = ModelFamily.GBM, floors: Map<ContextTier, ObservationFloors> = ObservationFloors.none()) = CandidateArtefact(
    version = "v3-candidato-2026-01-01",
    requestedFamily = family,
    logistic = ContextTier.entries.associateWith { table(it, -2.0 + it.ordinal * 0.3) },
    trees = trees(),
    pooled = "PP1|40",
    floors = floors,
    info = mapOf("cell.FRESH.0-1h.logistic" to LogisticChoice(FeatureExtractorV3.PERSISTENCE, 1e-3, 0.5).encode()),
  )

  @Test
  fun `l'artefatto si scrive e si rilegge con la stessa impronta e gli stessi numeri`() {
    val dir = Files.createTempDirectory("v3-artefact").toFile()
    try {
      val original = artefact()
      original.write(dir)
      val again = CandidateArtefact.read(dir)
      assertEquals(original.version, again.version)
      assertEquals(original.requestedFamily, again.requestedFamily)
      for (family in ModelFamily.entries) assertEquals(original.fingerprint(family), again.fingerprint(family))
      for (tier in ContextTier.entries) {
        assertEquals(original.logistic.getValue(tier).encode(), again.logistic.getValue(tier).encode())
        // Double.toString si rilegge identico, anche nell'ultima cifra.
        assertArrayEquals(original.logistic.getValue(tier).bags[1][3], again.logistic.getValue(tier).bags[1][3], 0.0)
      }
      assertEquals(original.info, again.info)
      val x = DoubleArray(count) { 0.3 * it - 2 }
      for (tier in ContextTier.entries) {
        assertEquals(original.toModel().verdict(tier, x).windows.map { it.probability }, again.toModel().verdict(tier, x).windows.map { it.probability })
      }
    } finally {
      dir.deleteRecursively()
    }
  }

  @Test
  fun `l'impronta cambia con la famiglia, con i pavimenti e con un solo byte degli alberi`() {
    val base = artefact()
    assertNotEquals(base.fingerprint(ModelFamily.GBM), base.fingerprint(ModelFamily.LOGISTICA))
    val floors = ObservationFloors.none().toMutableMap().also { it[ContextTier.FRESH] = ObservationFloors(ContextTier.FRESH, listOf(RainNowFloor(0.2, mapOf("0-1h" to 0.6)))) }
    assertNotEquals(base.fingerprint(), base.copy(floors = floors).fingerprint())
    val tampered = CandidateArtefact(base.version, base.requestedFamily, base.logistic, base.trees!!.mapValues { (name, bytes) ->
      if (name == TieredNowcastModel.RESOURCE_NAMES.last()) bytes.copyOf().also { it[it.size - 1] = 1 } else bytes
    }, base.pooled, base.floors)
    assertNotEquals(base.fingerprint(ModelFamily.GBM), tampered.fingerprint(ModelFamily.GBM))
    assertEquals(base.fingerprint(ModelFamily.LOGISTICA), tampered.fingerprint(ModelFamily.LOGISTICA))
  }

  @Test
  fun `gli iperparametri congelati si rileggono`() {
    val choice = LogisticChoice(FeatureExtractorV3.BAROMETRIC_RULE + 1, 3e-5, 0.25)
    assertEquals(choice, LogisticChoice.decode(choice.encode()))
    assertEquals(LogisticChoice(null, 1.0, 1.0), LogisticChoice.decode(LogisticChoice(null, 1.0, 1.0).encode()))
    val config = GbmConfig(3, 0.03, 10.0, 50.0)
    val (decoded, rounds) = TrainV3Command.decodeConfig(TrainV3Command.encodeConfig(config) + ";rounds=321")
    assertEquals(config.copy(maxRounds = decoded.maxRounds), decoded)
    assertEquals(321, rounds)
    assertEquals(V3Levers(wideGrid = true, gateCriterion = true), V3Levers.parse(V3Levers(wideGrid = true, gateCriterion = true).label))
  }

  @Test
  fun `i pavimenti della pipeline del banco sono quelli del motore`() {
    val model = artefact(ModelFamily.LOGISTICA).toModel()
    val logistic = model.logisticFor(ContextTier.FRESH)
    val engine = NowcastEngine(logistic, DoubleArray(count), DoubleArray(count) { 1.0 })
    val policy = ObservationFloors(
      ContextTier.FRESH,
      listOf(RainNowFloor(0.2, RainObservation.FLOORS_WHEN_RAINING), RainNowFloor(2.0, mapOf("0-1h" to 0.9, "1-3h" to 0.8, "3-6h" to 0.7))),
    )
    for (rain in listOf(0.0, 0.3, 5.0)) {
      for (shift in listOf(-3.0, 0.0, 2.5)) {
        val x = DoubleArray(count) { shift + 0.1 * it }
        val observation = policy.observation(rain, "prova")
        val expected = engine.evaluate(x, LearningState.EMPTY, observation).verdict
        val actual = V3Pipeline.applyFloors(model.verdict(ContextTier.FRESH, x), policy.floorsFor(rain))
        for (w in RainWindows.ALL.indices) {
          assertEquals(expected.windows[w].probability, actual.windows[w].probability, 1e-12)
          assertEquals(expected.windows[w].probabilityLow, actual.windows[w].probabilityLow, 1e-12)
          assertEquals(expected.windows[w].probabilityHigh, actual.windows[w].probabilityHigh, 1e-12)
        }
      }
    }
  }

  @Test
  fun `la politica dei pavimenti unisce le finestre scelte da varianti diverse`() {
    val variants = FloorsV3Command.variants().associateBy { it.name }
    val decisions = listOf(
      FloorCellDecision(ContextTier.FRESH, 0, variants.getValue("F3"), emptyList()),
      FloorCellDecision(ContextTier.FRESH, 1, variants.getValue("F1"), emptyList()),
      FloorCellDecision(ContextTier.FRESH, 2, null, emptyList()),
      FloorCellDecision(ContextTier.STALE, 0, null, emptyList()),
      FloorCellDecision(ContextTier.STALE, 1, null, emptyList()),
      FloorCellDecision(ContextTier.STALE, 2, null, emptyList()),
    )
    val merged = FloorsV3Command.merge(decisions)
    val fresh = merged.getValue(ContextTier.FRESH)
    assertEquals(listOf(0.2, 0.5, 2.0), fresh.whenRainingNow.map { it.fromMm })
    assertEquals(mapOf("1-3h" to 0.60), fresh.floorsFor(0.3))
    assertEquals(mapOf("0-1h" to 0.45, "1-3h" to 0.60), fresh.floorsFor(1.0))
    assertEquals(mapOf("0-1h" to 0.70, "1-3h" to 0.60), fresh.floorsFor(3.0))
    assertTrue(merged.getValue(ContextTier.STALE).whenRainingNow.isEmpty())
    assertTrue(merged.getValue(ContextTier.NONE).whenRainingNow.isEmpty())
  }
}
