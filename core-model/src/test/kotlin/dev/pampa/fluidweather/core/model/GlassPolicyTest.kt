package dev.pampa.fluidweather.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

class GlassPolicyTest {

  @Test
  fun `in automatico decide il tier del dispositivo`() {
    val settings = AppearanceSettings(autoGlass = true, manualLevel = GlassLevel.OFF)
    assertEquals(
      GlassLevel.FULL,
      GlassPolicy.resolve(settings, powerSaveActive = false, deviceTier = GlassLevel.FULL),
    )
  }

  @Test
  fun `in manuale comanda l'utente, anche contro il tier`() {
    val settings = AppearanceSettings(autoGlass = false, manualLevel = GlassLevel.FULL)
    assertEquals(
      GlassLevel.FULL,
      GlassPolicy.resolve(settings, powerSaveActive = false, deviceTier = GlassLevel.OFF),
    )
  }

  @Test
  fun `il risparmio energia scala di un gradino, se il toggle lo permette`() {
    val reducing = AppearanceSettings(autoGlass = false, manualLevel = GlassLevel.FULL, reduceOnPowerSave = true)
    assertEquals(GlassLevel.REDUCED, GlassPolicy.resolve(reducing, true, GlassLevel.FULL))

    val stubborn = reducing.copy(reduceOnPowerSave = false)
    assertEquals(GlassLevel.FULL, GlassPolicy.resolve(stubborn, true, GlassLevel.FULL))
  }

  @Test
  fun `sotto OFF non si scende`() {
    val settings = AppearanceSettings(autoGlass = false, manualLevel = GlassLevel.OFF)
    assertEquals(GlassLevel.OFF, GlassPolicy.resolve(settings, true, GlassLevel.FULL))
  }
}
