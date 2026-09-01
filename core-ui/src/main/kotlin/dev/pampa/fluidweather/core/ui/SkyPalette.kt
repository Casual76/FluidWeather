package dev.pampa.fluidweather.core.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import dev.pampa.fluidweather.core.model.DayPhase
import dev.pampa.fluidweather.core.model.WeatherKind

/**
 * I colori del cielo: la funzione pura dietro la scena. Fase del giorno -> gradiente base;
 * condizione e copertura -> quanto il cielo si incupisce (o si sbianca, per neve e nebbia).
 * Tutto qui dentro e' collaudabile senza un pixel.
 */
object SkyPalette {

  data class Sky(
    /** Dall'alto verso l'orizzonte. */
    val gradient: List<Color>,
    /** 0 = niente stelle; sale solo di notte e con cielo pulito. */
    val starAlpha: Float,
    /** Il corpo delle nuvole disegnate dalla scena. */
    val cloudColor: Color,
    /** Quanto e' cupo il cielo (0 sereno, 1 piombo): guida anche l'alert del contrasto. */
    val gloom: Float,
  )

  fun sky(phase: DayPhase, kind: WeatherKind?, cloudCoverPercent: Double?): Sky {
    val base = baseGradient(phase)
    val gloom = gloomOf(kind, cloudCoverPercent)
    val whiteout = whiteoutOf(kind)

    val gloomTone = when (phase) {
      DayPhase.DAY -> Color(0xFF47525E)
      DayPhase.DAWN, DayPhase.DUSK -> Color(0xFF3A3F4C)
      DayPhase.NIGHT -> Color(0xFF11141C)
    }
    val whiteTone = when (phase) {
      DayPhase.DAY -> Color(0xFFB9C2CC)
      DayPhase.DAWN, DayPhase.DUSK -> Color(0xFF8E8F9C)
      DayPhase.NIGHT -> Color(0xFF2A2F3D)
    }

    val gradient = base.map { color ->
      val darkened = lerp(color, gloomTone, gloom * 0.75f)
      lerp(darkened, whiteTone, whiteout)
    }

    val clearness = 1f - gloom
    return Sky(
      gradient = gradient,
      // Sopra ~55% di cupezza le stelle spariscono del tutto: dietro una coltre non si vede
      // "un po'" di cielo stellato, non si vede e basta.
      starAlpha = if (phase == DayPhase.NIGHT) ((clearness - 0.45f) * 1.6f).coerceIn(0f, 0.7f) else 0f,
      cloudColor = cloudColorOf(phase, gloom),
      gloom = gloom,
    )
  }

  /** I gradienti base, tarati sull'occhio della reference: cielo, non poster. */
  private fun baseGradient(phase: DayPhase): List<Color> = when (phase) {
    DayPhase.DAY -> listOf(Color(0xFF2160C4), Color(0xFF4E8AD8), Color(0xFF8FBCEC))
    DayPhase.DAWN -> listOf(Color(0xFF2B2F63), Color(0xFF7A6491), Color(0xFFE8A06B))
    DayPhase.DUSK -> listOf(Color(0xFF232858), Color(0xFF6E5380), Color(0xFFE0784F))
    DayPhase.NIGHT -> listOf(Color(0xFF060A1A), Color(0xFF0D1430), Color(0xFF1B2C55))
  }

  /**
   * Quanto la condizione incupisce il cielo. La copertura nuvolosa pesa fino a 0,65; i fenomeni
   * spingono oltre: un temporale e' buio anche quando il satellite dice 80%.
   */
  private fun gloomOf(kind: WeatherKind?, cloudCoverPercent: Double?): Float {
    val cover = ((cloudCoverPercent ?: defaultCover(kind)) / 100.0 * 0.65).toFloat()
    val kindFloor = when (kind) {
      WeatherKind.THUNDERSTORM -> 0.9f
      WeatherKind.HEAVY_RAIN -> 0.8f
      WeatherKind.RAIN, WeatherKind.SLEET -> 0.65f
      WeatherKind.DRIZZLE -> 0.55f
      WeatherKind.HEAVY_SNOW, WeatherKind.SNOW -> 0.4f
      WeatherKind.FOG -> 0.5f
      WeatherKind.CLOUDY -> 0.55f
      WeatherKind.PARTLY_CLOUDY -> 0.25f
      WeatherKind.MOSTLY_CLEAR -> 0.1f
      else -> 0f
    }
    return maxOf(cover, kindFloor).coerceIn(0f, 1f)
  }

  /** Neve e nebbia non anneriscono: sbiancano. */
  private fun whiteoutOf(kind: WeatherKind?): Float = when (kind) {
    WeatherKind.HEAVY_SNOW -> 0.55f
    WeatherKind.SNOW -> 0.4f
    WeatherKind.FOG -> 0.5f
    WeatherKind.SLEET -> 0.2f
    else -> 0f
  }

  private fun defaultCover(kind: WeatherKind?): Double = when (kind) {
    WeatherKind.CLEAR -> 5.0
    WeatherKind.MOSTLY_CLEAR -> 20.0
    WeatherKind.PARTLY_CLOUDY -> 45.0
    WeatherKind.CLOUDY, WeatherKind.FOG -> 90.0
    null -> 30.0
    else -> 85.0
  }

  private fun cloudColorOf(phase: DayPhase, gloom: Float): Color {
    val bright = when (phase) {
      DayPhase.DAY -> Color(0xFFF4F7FA)
      DayPhase.DAWN -> Color(0xFFEED8C8)
      DayPhase.DUSK -> Color(0xFFE3C4B2)
      DayPhase.NIGHT -> Color(0xFF232A3E)
    }
    val dark = when (phase) {
      DayPhase.NIGHT -> Color(0xFF151A28)
      else -> Color(0xFF9AA4B0)
    }
    return lerp(bright, dark, gloom)
  }
}
