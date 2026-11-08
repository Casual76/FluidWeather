package dev.pampa.fluidweather.core.ui

import org.junit.Assert.*
import org.junit.Test

class CloudVolumeTest {
  private val overcast = SkyAtmosphere(0.7f, 1f, 0.5f, 0.8f)

  @Test fun deterministicFiniteAndFeathered() {
    val a = CloudVolumeField(overcast, 2026166)
    val b = CloudVolumeField(overcast, 2026166)
    assertArrayEquals(a.opacity, b.opacity, 0f)
    assertArrayEquals(a.shade, b.shade, 0f)
    assertTrue(a.opacity.all { it.isFinite() && it in 0f..1f })
    assertTrue(a.shade.all { it.isFinite() && it in 0f..1f })
    // The lower edge is transparent before any rasterizer, even with maximum coverage/depth.
    for (x in 0..a.columns) assertEquals(0f, a.opacity[a.rows * (a.columns + 1) + x], 0f)
    val middle = (4..20).flatMap { y -> (12..52).map { x -> a.opacity[y * (a.columns + 1) + x] } }
    assertTrue("An overcast ceiling must occlude the sky", middle.average() > 0.85)
    assertTrue("Overcast must retain uneven thickness", middle.max() - middle.min() > 0.02f)
    assertTrue("Internal shading must retain structure", a.shade.max() - a.shade.min() > 0.2f)
  }

  @Test fun coverageAndDepthRemainContinuousOpticalControls() {
    val thin = CloudVolumeField(overcast.copy(cloudDepth = 0.2f), 43)
    val deep = CloudVolumeField(overcast.copy(cloudDepth = 0.9f), 43)
    val broken = CloudVolumeField(overcast.copy(cloudCoverage = 0.35f), 43)
    assertTrue(deep.opacity.sum() > thin.opacity.sum())
    assertTrue(deep.opacity.sum() > broken.opacity.sum())
    assertFalse(deep.opacity.contentEquals(CloudVolumeField(overcast.copy(cloudDepth = 0.9f), 44).opacity))
    val nearby = CloudVolumeField(overcast.copy(cloudDepth = 0.701f), 2026166)
    val original = CloudVolumeField(overcast, 2026166)
    assertTrue(original.opacity.indices.maxOf { kotlin.math.abs(original.opacity[it] - nearby.opacity[it]) } < 0.02f)
  }
}
