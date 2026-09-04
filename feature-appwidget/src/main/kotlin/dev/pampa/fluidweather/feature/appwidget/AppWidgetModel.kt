package dev.pampa.fluidweather.feature.appwidget

import androidx.annotation.DrawableRes
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import dev.pampa.fluidweather.core.model.DataAge
import dev.pampa.fluidweather.core.model.DataFreshness
import dev.pampa.fluidweather.core.model.FusedHour
import dev.pampa.fluidweather.core.model.FusionVariables
import dev.pampa.fluidweather.core.model.WeatherKind
import dev.pampa.fluidweather.core.model.nearestHour
import dev.pampa.fluidweather.core.model.rainProbabilityPercent
import dev.pampa.fluidweather.core.model.todayRange
import dev.pampa.fluidweather.core.weather.WeatherSnapshot
import dev.pampa.fluidweather.nowcast.verdict.AlertLevel
import dev.pampa.fluidweather.core.model.NowcastVerdictRecord

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
  /** Quando risale il giro dei provider: null se non c'e' niente da mostrare. */
  val dataAtMillis: Long?,
  val freshness: DataFreshness,
  val verdictLevel: AlertLevel?,
  val verdictProbabilityPercent: Int?,
  val verdictWindow: String?,
  /** Minima e massima di oggi, e la pioggia piu' probabile in vista: la riga in fondo al compatto. */
  val minC: Double?,
  val maxC: Double?,
  val rainProbabilityPercent: Int?,
  val hours: List<AppWidgetHour>,
) {
  /** Niente istantanea: il widget dice che non c'e' ancora niente e al tocco apre l'app. */
  val isEmpty: Boolean get() = temperatureC == null && hours.isEmpty() && dataAtMillis == null
}

/**
 * Il modello dal disco, senza toccare rete ne' GPS.
 *
 * Il widget mostra quello che il ciclo ha gia' scritto: l'istantanea porta dentro le proprie
 * coordinate, quindi non serve chiedere una posizione (che costerebbe fino a quindici secondi
 * proprio mentre il launcher aspetta un disegno). E l'ora corrente esce da `nearestHour`, la
 * stessa funzione della home: cosi' il widget e l'app non possono dire due temperature diverse.
 */
object AppWidgetModelBuilder {

  /** Quante ore mostra la taglia grande: quattro colonne stanno in una riga senza stringersi. */
  const val STRIP_HOURS = 4

  fun of(
    snapshot: WeatherSnapshot?,
    placeName: String?,
    verdict: NowcastVerdictRecord?,
    nowMillis: Long,
    tier: AppWidgetTier,
  ): AppWidgetModel {
    val current = snapshot?.fused?.hours?.nearestHour(nowMillis)?.first
    val kind = current?.kind
    val range = snapshot?.fused?.hours?.todayRange(nowMillis) ?: (null to null)
    return AppWidgetModel(
      placeName = placeName,
      temperatureC = current?.values?.get(FusionVariables.TEMPERATURE)?.value,
      kind = kind,
      cloudCover = current?.values?.get(FusionVariables.CLOUD_COVER)?.value,
      iconRes = kind.appWidgetIconRes(),
      latitude = snapshot?.latitude,
      longitude = snapshot?.longitude,
      dataAtMillis = snapshot?.fetchedAtMillis,
      freshness = DataAge.of(snapshot?.fetchedAtMillis, nowMillis),
      // Il verdetto e' un fatto del barometro, non della taglia: si legge sempre, ma le taglie
      // che non lo disegnano non se lo portano dietro.
      verdictLevel = verdict?.levelOrNull()?.takeIf { tier != AppWidgetTier.SMALL },
      verdictProbabilityPercent = verdict?.strongest()?.let { (it.second * 100).toInt() }
        ?.takeIf { tier != AppWidgetTier.SMALL },
      verdictWindow = verdict?.strongest()?.first?.takeIf { tier != AppWidgetTier.SMALL },
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
      hours = if (tier == AppWidgetTier.LARGE) stripOf(snapshot?.fused?.hours.orEmpty(), nowMillis) else emptyList(),
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

  private fun stripOf(hours: List<FusedHour>, nowMillis: Long): List<AppWidgetHour> = hours
    .filter { it.timestampMillis > nowMillis }
    .take(STRIP_HOURS)
    .map {
      AppWidgetHour(
        timestampMillis = it.timestampMillis,
        temperatureC = it.values[FusionVariables.TEMPERATURE]?.value,
        iconRes = it.kind.appWidgetIconRes(),
      )
    }
}

/**
 * Il disegno per un tipo di tempo.
 *
 * Nove vettori per tredici tipi, e non e' pigrizia: a 17 dp (14 quando il widget e' compatto) la
 * differenza fra "pioggia" e "pioggia forte" sono un paio di gocce che la griglia di pixel non
 * rende. La distinzione fine la porta la **tinta**, come gia' fa la tessera dentro l'app.
 *
 * `when` esaustivo senza `else`: un quattordicesimo [WeatherKind] deve essere un errore di
 * compilazione, non un widget che disegna un punto interrogativo senza che nessuno se ne accorga.
 */
@DrawableRes
fun WeatherKind?.appWidgetIconRes(): Int = when (this) {
  WeatherKind.CLEAR, WeatherKind.MOSTLY_CLEAR -> R.drawable.ic_weather_clear
  WeatherKind.PARTLY_CLOUDY -> R.drawable.ic_weather_partly_cloudy
  WeatherKind.CLOUDY -> R.drawable.ic_weather_cloudy
  WeatherKind.FOG -> R.drawable.ic_weather_fog
  WeatherKind.DRIZZLE, WeatherKind.RAIN, WeatherKind.HEAVY_RAIN -> R.drawable.ic_weather_rain
  WeatherKind.SLEET -> R.drawable.ic_weather_sleet
  WeatherKind.SNOW, WeatherKind.HEAVY_SNOW -> R.drawable.ic_weather_snow
  WeatherKind.THUNDERSTORM -> R.drawable.ic_weather_thunderstorm
  WeatherKind.UNKNOWN, null -> R.drawable.ic_weather_unknown
}
