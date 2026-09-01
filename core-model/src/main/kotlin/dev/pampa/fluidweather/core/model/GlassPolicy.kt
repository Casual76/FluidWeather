package dev.pampa.fluidweather.core.model

/** Il livello del vetro (e della scena): pieno, ridotto, o superfici opache dichiarate. */
enum class GlassLevel { FULL, REDUCED, OFF }

/**
 * Le scelte dell'utente sull'aspetto adattivo, come da onboarding (fase 15): livello manuale,
 * "decidi tu" e "riduci in risparmio energia". Prima dell'onboarding: auto, con prudenza.
 */
data class AppearanceSettings(
  val autoGlass: Boolean = true,
  val manualLevel: GlassLevel = GlassLevel.FULL,
  val reduceOnPowerSave: Boolean = true,
)

/**
 * La politica del vetro adattivo, pura: (scelte, risparmio energia, tier del dispositivo) ->
 * livello effettivo. Il tier lo misura chi ha un Context; la decisione vive qui, coi test.
 */
object GlassPolicy {

  fun resolve(
    settings: AppearanceSettings,
    powerSaveActive: Boolean,
    deviceTier: GlassLevel,
  ): GlassLevel {
    val chosen = if (settings.autoGlass) deviceTier else settings.manualLevel
    return if (powerSaveActive && settings.reduceOnPowerSave) chosen.stepDown() else chosen
  }

  private fun GlassLevel.stepDown(): GlassLevel = when (this) {
    GlassLevel.FULL -> GlassLevel.REDUCED
    GlassLevel.REDUCED -> GlassLevel.OFF
    GlassLevel.OFF -> GlassLevel.OFF
  }
}
