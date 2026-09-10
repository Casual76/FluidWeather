package dev.pampa.fluidweather.core.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import dev.pampa.fluidweather.core.model.DayPhase
import dev.pampa.fluidweather.core.model.WeatherKind

/**
 * I colori del cielo: la funzione pura dietro la scena. Fase del giorno -> gradiente base;
 * condizione e copertura -> quanto il cielo si incupisce (o si sbianca, per neve e nebbia).
 * Tutto qui dentro e' collaudabile senza un pixel.
 *
 * Il gradiente ha **cinque fermate**, non tre: un cielo vero non e' una rampa lineare. Lo zenit e'
 * saturo e scuro, a meta' si apre, e verso l'orizzonte si sbianca in una fascia di foschia che al
 * tramonto diventa la striscia calda. Con tre fermate quella fascia non esisteva, e il gradiente
 * si vedeva a bande.
 */
object SkyPalette {

  data class Sky(
    /** Le fermate del gradiente, dall'alto (0) all'orizzonte (1). */
    val stops: List<Pair<Float, Color>>,
    /** Zenit, meta' cielo, orizzonte: le tre tinte rappresentative, per chi ne vuole tre. */
    val gradient: List<Color>,
    /** 0 = niente stelle; sale solo di notte e con cielo pulito. */
    val starAlpha: Float,
    /** La faccia illuminata delle nuvole. */
    val cloudColor: Color,
    /** La pancia delle nuvole, in ombra. */
    val cloudShadow: Color,
    /** La foschia: nebbia, veli di pioggia, aria umida all'orizzonte. */
    val hazeColor: Color,
    /** Quanto e' cupo il cielo (0 sereno, 1 piombo): guida anche l'alert del contrasto. */
    val gloom: Float,
    /** Quanto neve e nebbia sbiancano tutto. */
    val whiteout: Float,
    val phase: DayPhase,
  )

  /**
   * Il disco del sole quando e' alto: bianco appena caldo. Non giallo — il giallo e' il colore
   * del sole in un disegno per bambini, non di quello che si guarda a mezzogiorno.
   */
  val SunCoreHigh: Color = Color(0xFFFFFBEE)
  val SunGlowHigh: Color = Color(0xFFFFE6A8)

  /** Il disco e la luce del sole basso sull'orizzonte: arancio pieno, e la luce diventa rossastra. */
  val SunCoreLow: Color = Color(0xFFFFC479)
  val SunGlowLow: Color = Color(0xFFFF7B2E)

  /** Il disco della luna: freddo, appena azzurrato, mai bianco puro (che sembra un buco). */
  val MoonColor: Color = Color(0xFFF1F3FA)
  val MoonLimb: Color = Color(0xFFC4CBDC)
  val MoonMaria: Color = Color(0xFF8E97AD)
  val MoonHalo: Color = Color(0xFFCAD5F2)

  fun sky(phase: DayPhase, kind: WeatherKind?, cloudCoverPercent: Double?): Sky {
    val base = baseStops(phase)
    val gloom = gloomOf(kind, cloudCoverPercent)
    val whiteout = whiteoutOf(kind)

    val gloomTone = when (phase) {
      DayPhase.DAY -> Color(0xFF4B5563)
      DayPhase.DAWN, DayPhase.DUSK -> Color(0xFF3B3F4C)
      DayPhase.NIGHT -> Color(0xFF0E1119)
    }
    val whiteTone = when (phase) {
      DayPhase.DAY -> Color(0xFFC3CBD4)
      DayPhase.DAWN, DayPhase.DUSK -> Color(0xFF9A9AA6)
      DayPhase.NIGHT -> Color(0xFF2A2F3D)
    }

    // Un cielo coperto e' piu' chiaro verso l'orizzonte che allo zenit: la coltre e' illuminata
    // di taglio. La rampa base va gia' in quella direzione, e la cupezza la conserva.
    val stops = base.map { (at, color) ->
      val darkened = lerp(color, gloomTone, gloom * 0.9f)
      at to lerp(darkened, whiteTone, whiteout)
    }

    val clearness = 1f - gloom
    return Sky(
      stops = stops,
      gradient = listOf(stops[0].second, stops[2].second, stops[4].second),
      // Sopra ~55% di cupezza le stelle spariscono del tutto: dietro una coltre non si vede
      // "un po'" di cielo stellato, non si vede e basta.
      starAlpha = if (phase == DayPhase.NIGHT) ((clearness - 0.45f) * 1.6f).coerceIn(0f, 0.7f) else 0f,
      cloudColor = lerp(litCloud(phase), litCloudGloomy(phase), gloom),
      cloudShadow = lerp(shadowCloud(phase), shadowCloudGloomy(phase), gloom),
      hazeColor = lerp(haze(phase), whiteTone, whiteout * 0.5f),
      gloom = gloom,
      whiteout = whiteout,
      phase = phase,
    )
  }

  /**
   * Le rampe base. Tarate sull'occhio, non su una formula: cielo, non poster.
   *
   * Le fermate sono a 0, 0,3, 0,6, 0,85 e 1: la foschia dell'orizzonte occupa l'ultimo quindici
   * per cento e non di piu', perche' la home ci appoggia sopra le tessere e un orizzonte
   * troppo alto finirebbe dietro il vetro.
   */
  private fun baseStops(phase: DayPhase): List<Pair<Float, Color>> = when (phase) {
    DayPhase.DAY -> listOf(
      0f to Color(0xFF1D5CBF),
      0.3f to Color(0xFF3B7DD4),
      0.6f to Color(0xFF6DA3E3),
      0.85f to Color(0xFFA6C8EE),
      1f to Color(0xFFCBDDF1),
    )
    DayPhase.DAWN -> listOf(
      0f to Color(0xFF262A5E),
      0.3f to Color(0xFF574A85),
      0.6f to Color(0xFFB0737F),
      0.85f to Color(0xFFE59560),
      1f to Color(0xFFF5C27C),
    )
    DayPhase.DUSK -> listOf(
      0f to Color(0xFF1C2151),
      0.3f to Color(0xFF4A3D73),
      0.6f to Color(0xFF945A6C),
      0.85f to Color(0xFFD56E40),
      1f to Color(0xFFEF9E58),
    )
    DayPhase.NIGHT -> listOf(
      0f to Color(0xFF04060E),
      0.3f to Color(0xFF090F22),
      0.6f to Color(0xFF101A35),
      0.85f to Color(0xFF1A2746),
      1f to Color(0xFF26354F),
    )
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
      WeatherKind.FOG -> 0.4f
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
    WeatherKind.FOG -> 0.7f
    WeatherKind.SLEET -> 0.2f
    else -> 0f
  }

  fun defaultCover(kind: WeatherKind?): Double = when (kind) {
    WeatherKind.CLEAR -> 5.0
    WeatherKind.MOSTLY_CLEAR -> 20.0
    WeatherKind.PARTLY_CLOUDY -> 45.0
    WeatherKind.CLOUDY, WeatherKind.FOG -> 90.0
    null -> 30.0
    else -> 85.0
  }

  private fun litCloud(phase: DayPhase): Color = when (phase) {
    DayPhase.DAY -> Color(0xFFFFFFFF)
    DayPhase.DAWN -> Color(0xFFFFDCC4)
    DayPhase.DUSK -> Color(0xFFF8CDB2)
    DayPhase.NIGHT -> Color(0xFF2C3450)
  }

  private fun litCloudGloomy(phase: DayPhase): Color = when (phase) {
    DayPhase.DAY -> Color(0xFF9CA7B5)
    DayPhase.DAWN, DayPhase.DUSK -> Color(0xFF8A8894)
    DayPhase.NIGHT -> Color(0xFF181D2C)
  }

  private fun shadowCloud(phase: DayPhase): Color = when (phase) {
    DayPhase.DAY -> Color(0xFF8DA0B8)
    DayPhase.DAWN -> Color(0xFF8C7690)
    DayPhase.DUSK -> Color(0xFF7A6684)
    DayPhase.NIGHT -> Color(0xFF12172A)
  }

  private fun shadowCloudGloomy(phase: DayPhase): Color = when (phase) {
    DayPhase.DAY -> Color(0xFF4F5A68)
    DayPhase.DAWN, DayPhase.DUSK -> Color(0xFF474A58)
    DayPhase.NIGHT -> Color(0xFF0A0D16)
  }

  private fun haze(phase: DayPhase): Color = when (phase) {
    DayPhase.DAY -> Color(0xFFD7DEE6)
    DayPhase.DAWN -> Color(0xFFD9C4BD)
    DayPhase.DUSK -> Color(0xFFCDB3B0)
    DayPhase.NIGHT -> Color(0xFF3A4256)
  }
}
