package dev.pampa.fluidweather.feature.home

import dev.antigravity.fluidengine.foundation.EngineSettings
import dev.antigravity.fluidengine.foundation.ThemeMode
import dev.pampa.fluidweather.core.model.*
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class HomeThemeAndZoneTest {
  @Test fun nestedHomeThemeRetainsDisabledHapticsAndTheirLivePreference() {
    val disabled = EngineSettings(hapticsEnabled = false)
    assertFalse(homeThemeSettings(disabled).hapticsEnabled)
    assertEquals(ThemeMode.DARK, homeThemeSettings(disabled).themeMode)
    assertTrue(homeThemeSettings(disabled.copy(hapticsEnabled = true)).hapticsEnabled)
  }

  @Test fun newlyAcceptedSnapshotMetadataWinsOverAStillVisiblePreviousBrief() {
    val old = Place(7, "Current", null, 40.0, -74.0, "America/New_York")
    val state = HomeUiState(zoneId = "Asia/Tokyo", weatherBrief = WeatherBrief(
      LocalPrecipitationAssessment(PrecipitationState.UNAVAILABLE, 0, old)))
    assertEquals(ZoneId.of("Asia/Tokyo"), homeWeatherZone(state, ZoneId.of("Europe/Rome")).zoneId)
  }

  @Test fun absentOrInvalidMetadataUsesExplicitDeviceFallback() {
    val device = ZoneId.of("Europe/Rome")
    val resolved = homeWeatherZone(HomeUiState(zoneId = "invalid"), device)
    assertEquals(device, resolved.zoneId)
    assertEquals(WeatherZoneSource.DEVICE_FALLBACK, resolved.source)
    assertEquals("invalid", HomeUiState(zoneId = "invalid").zoneId)
  }
}
