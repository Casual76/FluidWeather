package dev.pampa.fluidweather.core.ui

import androidx.compose.ui.graphics.Color
import dev.antigravity.fluidengine.ui.theme.AccentPreset
import dev.antigravity.fluidengine.ui.theme.fluidAccentPresets
import dev.pampa.fluidweather.core.model.DayPhase
import dev.pampa.fluidweather.core.model.WeatherKind

/**
 * L'accento derivato dal meteo attuale: il default del piano. Non colora la home (li' comanda
 * il cielo intero) ma tinge componenti e pagine secondarie, cosi' l'app "sa che tempo fa"
 * anche nelle impostazioni. L'ametista resta il marchio di riserva quando il meteo non c'e'.
 */
object WeatherAccent {

  /** Ametista: il marchio finche' il meteo non ne detta uno, e la base della palette scelta a mano. */
  val Amethyst: AccentPreset = AccentPreset("amethyst", "Ametista", Color(0xFF8C52D9), Color(0xFFB88CF2))

  /** Le palette che l'utente puo' scegliere in Aspetto: ametista prima, poi quelle dell'engine. */
  val palettes: List<AccentPreset> = listOf(Amethyst) + fluidAccentPresets

  fun presetFor(kind: WeatherKind?, phase: DayPhase): AccentPreset {
    val (name, light, dark) = when {
      kind == WeatherKind.THUNDERSTORM -> Triple("storm", Color(0xFF6D5BC7), Color(0xFF9E8CF0))
      kind == WeatherKind.HEAVY_RAIN || kind == WeatherKind.RAIN ||
        kind == WeatherKind.DRIZZLE || kind == WeatherKind.SLEET ->
        Triple("rain", Color(0xFF3A6EA8), Color(0xFF7FAAD6))
      kind == WeatherKind.SNOW || kind == WeatherKind.HEAVY_SNOW ->
        Triple("snow", Color(0xFF5B8FBF), Color(0xFFA8CCE8))
      kind == WeatherKind.FOG -> Triple("fog", Color(0xFF6E7B8A), Color(0xFFA3B1C0))
      kind == WeatherKind.CLOUDY -> Triple("clouds", Color(0xFF54708C), Color(0xFF93AEC8))
      phase == DayPhase.NIGHT -> Triple("night", Color(0xFF4A5AA8), Color(0xFF8B9AE8))
      phase == DayPhase.DAWN || phase == DayPhase.DUSK ->
        Triple("golden", Color(0xFFC26D3F), Color(0xFFF0A870))
      else -> Triple("sky", Color(0xFF1E6FD6), Color(0xFF6FAAF0))
    }
    return AccentPreset(name = "weather-$name", label = "Meteo", light = light, dark = dark)
  }
}
