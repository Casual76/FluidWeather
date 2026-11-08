package dev.pampa.fluidweather.feature.appwidget

import androidx.annotation.DrawableRes
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import dev.pampa.fluidweather.core.model.DataAge
import dev.pampa.fluidweather.core.model.DayPhase
import dev.pampa.fluidweather.core.model.DataFreshness
import dev.pampa.fluidweather.core.model.FusedHour
import dev.pampa.fluidweather.core.model.FusionVariables
import dev.pampa.fluidweather.core.model.WeatherKind
import dev.pampa.fluidweather.core.model.*
import dev.pampa.fluidweather.strings.WidgetSharedRainFormatter
import dev.pampa.fluidweather.core.model.rainProbabilityPercent
import dev.pampa.fluidweather.core.model.todayRange
import dev.pampa.fluidweather.core.weather.WeatherSnapshot
import dev.pampa.fluidweather.nowcast.verdict.AlertLevel
import dev.pampa.fluidweather.core.model.NowcastVerdictRecord
import java.time.ZoneId

/** Quanto spazio ha il widget, e quindi quanto ha da dire. */
enum class AppWidgetTier { SMALL, MEDIUM, LARGE }

/**
 * La taglia dalla dimensione riportata dal launcher.
 *
 * **Due soglie indipendenti, e mai un confronto con le taglie dichiarate**: i launcher riportano
 * le loro misure, non quelle che abbiamo scritto noi, e un `==` fallirebbe su meta' dei telefoni.
 * Le due forme patologiche sono quelle che decidono la regola: 4x1 (largo e basso) non e' "grande"
 * perche' non ci stanno le righe, e 2x4 (alto e stretto) resta piccolo perche' non ci sta il testo.
 */
fun tierFor(size: DpSize): AppWidgetTier = when {
  size.width < 180.dp -> AppWidgetTier.SMALL
  size.height < 150.dp -> AppWidgetTier.MEDIUM
  else -> AppWidgetTier.LARGE
}

/** Un'ora della striscia, gia' pronta da scrivere. */
data class AppWidgetHour(
  val timestampMillis: Long,
  val temperatureC: Double?,
  @DrawableRes val iconRes: Int,
)

/** Tutto quello che il widget disegna, deciso fuori dalla composable. */
data class AppWidgetModel(
  val placeName: String?,
  val temperatureC: Double?,
  val kind: WeatherKind?,
  val cloudCover: Double?,
  @DrawableRes val iconRes: Int,
  val latitude: Double?,
  val longitude: Double?,
  /** Che ora e' in cielo: decide il disegno notturno e lo sfondo. */
  val dayPhase: DayPhase,
  /** Quando risale il giro dei provider: null se non c'e' niente da mostrare. */
  val dataAtMillis: Long?,
  val freshness: DataFreshness,
  val verdictLevel: AlertLevel?,
  val verdictProbabilityPercent: Int?,
  val verdictWindow: String?,
  val headline: BriefRainHeadline,
  val brief: WeatherBrief?,
  val assessedAtMillis: Long,
  val zone: ZoneId,
  val zoneFallback: Boolean,
  val verdictAtMillis: Long?,
  /** Minima e massima di oggi, e la pioggia piu' probabile in vista: la riga in fondo al compatto. */
  val minC: Double?,
  val maxC: Double?,
  val rainProbabilityPercent: Int?,
  val hours: List<AppWidgetHour>,
) {
  /** Precipitation atmosphere requires a shared known wet state, even when current kind is rainy. */
  val skyKind: WeatherKind? get() {
    val wetKinds = setOf(WeatherKind.DRIZZLE, WeatherKind.RAIN, WeatherKind.HEAVY_RAIN,
      WeatherKind.SNOW, WeatherKind.HEAVY_SNOW, WeatherKind.SLEET, WeatherKind.THUNDERSTORM)
    return if (headline in setOf(BriefRainHeadline.WET, BriefRainHeadline.MODEL_WET, BriefRainHeadline.RADAR_WET))
      kind?.takeIf { it in wetKinds } ?: WeatherKind.RAIN
    else if (kind in wetKinds) WeatherKind.CLOUDY else kind
  }

  /** Niente istantanea: il widget dice che non c'e' ancora niente e al tocco apre l'app. */
  val isEmpty: Boolean get() = temperatureC == null && hours.isEmpty() && dataAtMillis == null && brief == null
}

/** Cache-only presentation; fused hours are forecast details, never current observations. */
object AppWidgetModelBuilder {
  const val STRIP_HOURS = 4

  fun of(
    snapshot: WeatherSnapshot?,
    placeName: String?,
    verdict: NowcastVerdictRecord?,
    nowMillis: Long,
    tier: AppWidgetTier,
    zone: ZoneId = ZoneId.systemDefault(),
    brief: WeatherBrief? = null,
    zoneFallback: Boolean = true,
  ): AppWidgetModel {
    val current = WidgetSharedRainFormatter.current(brief, nowMillis)
    val kind = current?.kind
    val range = snapshot?.fused?.hours?.todayRange(nowMillis, zone) ?: (null to null)
    val phase = SkyScene.phaseOf(snapshot?.latitude, snapshot?.longitude, nowMillis, zone)
    return AppWidgetModel(
      placeName = placeName,
      temperatureC = current?.temperatureC,
      kind = kind,
      cloudCover = null,
      iconRes = kind.appWidgetIconRes(phase),
      latitude = snapshot?.latitude,
      longitude = snapshot?.longitude,
      dayPhase = phase,
      dataAtMillis = snapshot?.fetchedAtMillis,
      freshness = DataAge.of(snapshot?.fetchedAtMillis, nowMillis),
      // Il verdetto e' un fatto del barometro, non della taglia: si legge sempre, ma le taglie
      // che non lo disegnano non se lo portano dietro.
      verdictLevel = verdict?.levelOrNull()?.takeIf { tier != AppWidgetTier.SMALL },
      verdictProbabilityPercent = verdict?.strongest()?.let { (it.second * 100).toInt() }
        ?.takeIf { tier != AppWidgetTier.SMALL },
      verdictWindow = verdict?.strongest()?.first?.takeIf { tier != AppWidgetTier.SMALL },
      headline = WidgetSharedRainFormatter.headline(brief, nowMillis),
      brief = brief,
      assessedAtMillis = brief?.precipitation?.evaluatedAtMillis ?: nowMillis,
      zone = zone,
      zoneFallback = zoneFallback,
      verdictAtMillis = verdict?.timestampMillis?.takeIf { tier != AppWidgetTier.SMALL },
      // Massima, minima e pioggia riempiono lo spazio che restava in fondo alle due taglie
      // compatte. Nella grande no: li' sotto ci sono gia' le prossime ore, che dicono di piu'.
      minC = if (tier == AppWidgetTier.LARGE) null else range.first,
      maxC = if (tier == AppWidgetTier.LARGE) null else range.second,
      rainProbabilityPercent = if (tier == AppWidgetTier.LARGE) {
        null
      } else {
        snapshot?.fused?.hours?.rainProbabilityPercent(nowMillis)
      },
      // Le ore si calcolano solo se qualcuno le disegnera': un widget piccolo non deve pagare
      // una lista che non entra da nessuna parte.
      hours = if (tier == AppWidgetTier.LARGE) {
        stripOf(snapshot?.fused?.hours.orEmpty(), nowMillis, snapshot?.latitude, snapshot?.longitude, zone)
      } else {
        emptyList()
      },
    )
  }

  /**
   * La finestra piu' probabile fra le tre, come coppia etichetta-probabilita'.
   *
   * Lo storico salva le tre probabilita' sciolte, non l'oggetto del verdetto: e' quello che c'e'
   * su disco, ed e' anche il motivo per cui il widget non ricalcola niente — ricalcolare vorrebbe
   * dire rileggere ventiquattro ore di campioni e far girare la pipeline a ogni ridisegno.
   */
  private fun NowcastVerdictRecord.strongest(): Pair<String, Double>? = listOf(
    "0-1h" to probability01,
    "1-3h" to probability13,
    "3-6h" to probability36,
  ).maxByOrNull { it.second }?.takeIf { it.second > 0.0 }

  /** Il livello e' salvato come stringa: un nome ignoto vale "non lo so", non un crash. */
  private fun NowcastVerdictRecord.levelOrNull(): AlertLevel? =
    AlertLevel.entries.firstOrNull { it.name == level }

  /**
   * Ogni ora ha la **sua** fase, non quella di adesso: alle 18 la striscia arriva alle 22, e
   * mostrare quattro soli di fila per una notte serena sarebbe la stessa bugia in piccolo.
   */
  private fun stripOf(
    hours: List<FusedHour>,
    nowMillis: Long,
    latitude: Double?,
    longitude: Double?,
    zone: ZoneId,
  ): List<AppWidgetHour> = hours
    .filter { it.timestampMillis > nowMillis }
    .take(STRIP_HOURS)
    .map {
      AppWidgetHour(
        timestampMillis = it.timestampMillis,
        temperatureC = it.values[FusionVariables.TEMPERATURE]?.value,
        iconRes = it.kind.appWidgetIconRes(SkyScene.phaseOf(latitude, longitude, it.timestampMillis, zone)),
      )
    }
}

/**
 * Il disegno per un tipo di tempo, **all'ora giusta**.
 *
 * Undici vettori per tredici tipi, e non e' pigrizia: a 17 dp (14 quando il widget e' compatto) la
 * differenza fra "pioggia" e "pioggia forte" sono un paio di gocce che la griglia di pixel non
 * rende. La distinzione fine la porta la **tinta**, come gia' fa la tessera dentro l'app.
 *
 * La [phase] invece cambia il disegno, e prima non arrivava fin qui: un cielo sereno alle due di
 * notte prendeva `ic_weather_clear`, che e' un sole con i raggi. Solo le condizioni in cui si vede
 * il cielo hanno una versione notturna — sotto una coltre, nella nebbia o nella neve non si vede
 * ne' sole ne' luna, e due disegni sarebbero due modi di dire la stessa cosa.
 *
 * `when` esaustivo senza `else`: un quattordicesimo [WeatherKind] deve essere un errore di
 * compilazione, non un widget che disegna un punto interrogativo senza che nessuno se ne accorga.
 */
@DrawableRes
fun WeatherKind?.appWidgetIconRes(phase: DayPhase = DayPhase.DAY): Int {
  val night = phase == DayPhase.NIGHT
  return when (this) {
    WeatherKind.CLEAR, WeatherKind.MOSTLY_CLEAR ->
      if (night) R.drawable.ic_weather_clear_night else R.drawable.ic_weather_clear
    WeatherKind.PARTLY_CLOUDY ->
      if (night) R.drawable.ic_weather_partly_cloudy_night else R.drawable.ic_weather_partly_cloudy
    WeatherKind.CLOUDY -> R.drawable.ic_weather_cloudy
    WeatherKind.FOG -> R.drawable.ic_weather_fog
    WeatherKind.DRIZZLE, WeatherKind.RAIN, WeatherKind.HEAVY_RAIN -> R.drawable.ic_weather_rain
    WeatherKind.SLEET -> R.drawable.ic_weather_sleet
    WeatherKind.SNOW, WeatherKind.HEAVY_SNOW -> R.drawable.ic_weather_snow
    WeatherKind.THUNDERSTORM -> R.drawable.ic_weather_thunderstorm
    WeatherKind.UNKNOWN, null -> R.drawable.ic_weather_unknown
  }
}
