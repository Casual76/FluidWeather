package dev.pampa.fluidweather.nowcast.verdict

import dev.pampa.fluidweather.nowcast.truth.TruthPanel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelVersionsTest {

  @Test
  fun `l'etichetta corrente mette insieme modello, verita' e classifica`() {
    assertEquals(TrainedNowcastV3.VERSION, ModelVersions.CURRENT_MODEL)
    assertEquals(TruthPanel.VERSION, ModelVersions.TRUTH)
    assertEquals("${TrainedNowcastV3.VERSION}+panel-1+board-2", ModelVersions.TAG)
    assertEquals(ModelVersions.TAG, ModelVersions.tag())
  }

  @Test
  fun `un'etichetta si scompone e si ricompone`() {
    val parsed = ModelVersions.parse(ModelVersions.TAG)!!
    assertEquals(VersionTag(TrainedNowcastV3.VERSION, "panel-1", "board-2"), parsed)
    assertEquals(ModelVersions.TAG, parsed.toString())
    assertNull(ModelVersions.parse("v2-2026-09-10+panel-1"))
    assertNull(ModelVersions.parse("v2++board-2"))
  }

  @Test
  fun `solo l'etichetta corrente e' corrente`() {
    assertTrue(ModelVersions.isCurrent(ModelVersions.TAG))
    assertFalse(ModelVersions.isCurrent(ModelVersions.tag(model = "v1")))
    assertFalse(ModelVersions.isCurrent(ModelVersions.tag(truth = "consensus-0")))
    assertFalse(ModelVersions.isCurrent(null))
  }

  @Test
  fun `il v3 e' il modello corrente, e le sue feature sono quelle che la ricalibrazione accetta`() {
    assertEquals(TrainedNowcastV3.VERSION, ModelVersions.V3_MODEL)
    assertEquals(ModelVersions.tag(model = ModelVersions.V3_MODEL), ModelVersions.V3_TAG)
    assertEquals(TrainedNowcastV3.VERSION, ModelVersions.CURRENT_MODEL)
    assertTrue(ModelVersions.isCurrent(ModelVersions.V3_TAG))
    assertEquals(dev.pampa.fluidweather.nowcast.features.FeatureExtractorV3.COUNT, ModelVersions.CURRENT_FEATURE_COUNT)
  }

  @Test(expected = IllegalArgumentException::class)
  fun `una versione col separatore dentro non si etichetta`() {
    ModelVersions.tag(model = "v3+beta")
  }
}
