package dev.pampa.fluidweather.core.ai.tools

import dev.pampa.fluidweather.core.ai.data.PlaceResolver
import dev.pampa.fluidweather.core.ai.tools.Args.int
import dev.pampa.fluidweather.core.ai.tools.Args.list
import dev.pampa.fluidweather.core.ai.tools.Args.str
import dev.pampa.fluidweather.core.model.ApparentTemperature
import dev.pampa.fluidweather.core.model.FusedHour
import dev.pampa.fluidweather.core.model.FusionVariables
import dev.pampa.fluidweather.core.model.nearestHour
import dev.pampa.fluidweather.core.weather.WeatherSnapshot
import dev.pampa.fluidweather.strings.TimeFormats
import dev.pampa.fluidweather.strings.compassPoint
import dev.pampa.fluidweather.strings.dewPointLabelRes
import dev.pampa.fluidweather.strings.labelRes
import dev.pampa.fluidweather.strings.uvLabelRes
import dev.pampa.fluidweather.strings.visibilityLabelRes
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.serialization.json.JsonObject

/** Le frasi comuni ai tool sulle ore fuse: "il posto non c'e'", "i provider non hanno risposto". */
internal object ToolPhrases {
  const val PLACE_NOT_FOUND = "errore: localita' non trovata"
  const val NO_DATA = "nessun dato: i servizi meteo non hanno ancora risposto per questo posto"

  fun header(ctx: ToolContext, place: dev.pampa.fluidweather.core.ai.data.ResolvedPlace, snapshot: WeatherSnapshot): String {
    val ageMin = ((ctx.nowMillis - snapshot.fetchedAtMillis) / 60_000.0).roundToInt()
    return "luogo: ${place.label} · dati di ${ageMin} min fa da ${snapshot.providersResponding} servizi"
  }
}

internal fun FusedHour.value(variable: String): Double? = values[variable]?.value

internal fun ToolContext.kindLabel(kind: dev.pampa.fluidweather.core.model.WeatherKind?): String =
  kind?.labelRes()?.let { string(it) } ?: "—"

internal fun ToolContext.timeLabel(millis: Long): String = TimeFormats.time(millis, zone, locale)
internal fun ToolContext.dayTimeLabel(millis: Long): String = TimeFormats.dayTime(millis, zone, locale)

/** Le condizioni di adesso, tutte insieme: l'equivalente della testata piu' il widget dei dettagli. */
class NowTool(private val resolver: PlaceResolver) : AiTool {
  override val name = "adesso"
  override val group = ToolGroup.HOURLY
  override val description = "Le condizioni di adesso: temperatura, percepita, umidita', vento, pressione, nuvole, UV, visibilita', pioggia prevista in quest'ora."
  override val parameters = Schema.obj(mapOf("luogo" to Schema.place))

  override suspend fun run(args: JsonObject, ctx: ToolContext): String {
    val place = resolver.resolve(args.str("luogo"), ctx.selected) ?: return ToolPhrases.PLACE_NOT_FOUND
    val snapshot = resolver.snapshot(place) ?: return ToolPhrases.NO_DATA
    val hour = snapshot.fused.nearestHour(ctx.nowMillis)?.first
      ?.takeIf { abs(it.timestampMillis - ctx.nowMillis) <= 90 * 60_000L } ?: return ToolPhrases.NO_DATA
    val u = ctx.units
    val t = hour.value(FusionVariables.TEMPERATURE)
    val rh = hour.value(FusionVariables.HUMIDITY)
    val wind = hour.value(FusionVariables.WIND_SPEED)
    val dew = hour.value(FusionVariables.DEW_POINT)
    return ToolText.build {
      line(ToolPhrases.header(ctx, place, snapshot))
      line("ora di riferimento", ctx.timeLabel(hour.timestampMillis))
      line("condizione", ctx.kindLabel(hour.kind))
      line("temperatura", t?.let { u.temperature(it) })
      if (t != null && rh != null && wind != null) line("percepita", u.temperature(ApparentTemperature.celsius(t, rh, wind)))
      line("umidita'", rh?.let { "${it.roundToInt()}%" })
      line("punto di rugiada", dew?.let { "${u.temperature(it)} (${ctx.string(dewPointLabelRes(it))})" })
      val direction = hour.windDirectionDeg
      line(
        "vento",
        wind?.let { w ->
          val from = direction?.let { " da ${compassPoint(ctx.resources, it)} (${it.roundToInt()}°)" } ?: ""
          val gust = hour.value(FusionVariables.WIND_GUST)?.let { ", raffiche ${u.wind(it)}" } ?: ""
          "${u.wind(w)}$from$gust"
        },
      )
      line("pressione (livello del mare)", hour.value(FusionVariables.PRESSURE_MSL)?.let { u.pressure(it, 1) })
      line("nuvole", hour.value(FusionVariables.CLOUD_COVER)?.let { "${it.roundToInt()}%" })
      line("indice UV", hour.value(FusionVariables.UV_INDEX)?.let { "${it.roundToInt()} (${ctx.string(uvLabelRes(it))})" })
      line("visibilita'", hour.value(FusionVariables.VISIBILITY)?.let { "${u.visibilityMeters(it)} (${ctx.string(visibilityLabelRes(it))})" })
      line("probabilita' di pioggia in quest'ora", hour.value(FusionVariables.PRECIP_PROBABILITY)?.let { "${it.roundToInt()}%" })
      line("pioggia prevista in quest'ora", hour.value(FusionVariables.PRECIPITATION)?.let { u.precipitation(it) })
      val weights = snapshot.fused.providerWeights.entries.sortedByDescending { it.value }.take(3)
      if (weights.isNotEmpty()) line("servizi con piu' peso", weights.joinToString(", ") { "${it.key} ${(it.value * 100).roundToInt()}%" })
    }
  }
}

/** Ora per ora: le variabili chieste, sulle prossime ore, con passo che cresce se la finestra e' lunga. */
class HourlyForecastTool(private val resolver: PlaceResolver) : AiTool {
  override val name = "previsione_oraria"
  override val group = ToolGroup.HOURLY
  override val description = "Previsione ora per ora da 'da_ore' a 'a_ore' ore da adesso (max 48): condizione, temperatura, pioggia, vento; altre variabili su richiesta."
  override val parameters = Schema.obj(
    mapOf(
      "luogo" to Schema.place,
      "da_ore" to Schema.int("prima ora (0 = adesso)", 0, 240),
      "a_ore" to Schema.int("ultima ora inclusa", 1, 240),
      "variabili" to Schema.strArray("variabili extra", VARIABLES.keys.toList()),
    ),
  )

  override suspend fun run(args: JsonObject, ctx: ToolContext): String {
    val place = resolver.resolve(args.str("luogo"), ctx.selected) ?: return ToolPhrases.PLACE_NOT_FOUND
    val snapshot = resolver.snapshot(place) ?: return ToolPhrases.NO_DATA
    val from = (args.int("da_ore") ?: 0).coerceIn(0, 240)
    val to = (args.int("a_ore") ?: (from + 12)).coerceIn(from + 1, 240)
    val extra = args.list("variabili").mapNotNull { VARIABLES[it] }
    val start = ctx.nowMillis + from * 3_600_000L
    val end = ctx.nowMillis + to * 3_600_000L
    val hours = snapshot.fused.hours.filter { it.timestampMillis in (start - 30 * 60_000L)..end }.sortedBy { it.timestampMillis }
    if (hours.isEmpty()) return "nessuna ora fusa nell'intervallo (orizzonte dei servizi: ${(snapshot.fused.hours.maxOfOrNull { it.timestampMillis }?.let { (it - ctx.nowMillis) / 3_600_000 } ?: 0)} h)"
    val step = when {
      hours.size <= 24 -> 1
      hours.size <= 48 -> 2
      else -> 3
    }
    val u = ctx.units
    return ToolText.build {
      line(ToolPhrases.header(ctx, place, snapshot))
      if (step > 1) line("(una riga ogni $step ore)")
      hours.filterIndexed { index, _ -> index % step == 0 }.forEach { hour ->
        val parts = mutableListOf<String>()
        parts += ctx.dayTimeLabel(hour.timestampMillis)
        parts += ctx.kindLabel(hour.kind)
        hour.value(FusionVariables.TEMPERATURE)?.let { parts += u.temperature(it) }
        hour.value(FusionVariables.PRECIP_PROBABILITY)?.let { parts += "pioggia ${it.roundToInt()}%" }
        hour.value(FusionVariables.PRECIPITATION)?.takeIf { it >= 0.05 }?.let { parts += u.precipitation(it) }
        hour.value(FusionVariables.WIND_SPEED)?.let { w ->
          val dir = hour.windDirectionDeg?.let { " ${compassPoint(ctx.resources, it)}" } ?: ""
          parts += "vento ${u.wind(w)}$dir"
        }
        extra.forEach { variable ->
          hour.value(variable)?.let { value -> parts += "${labelOf(variable)} ${format(ctx, variable, value)}" }
        }
        line(parts.joinToString(" · "))
      }
    }
  }

  private fun labelOf(variable: String): String = VARIABLES.entries.first { it.value == variable }.key

  private fun format(ctx: ToolContext, variable: String, value: Double): String = when (variable) {
    FusionVariables.HUMIDITY, FusionVariables.CLOUD_COVER -> "${value.roundToInt()}%"
    FusionVariables.DEW_POINT -> ctx.units.temperature(value)
    FusionVariables.PRESSURE_MSL -> ctx.units.pressure(value, 1)
    FusionVariables.UV_INDEX -> value.roundToInt().toString()
    FusionVariables.VISIBILITY -> ctx.units.visibilityMeters(value)
    FusionVariables.WIND_GUST -> ctx.units.wind(value)
    else -> value.toString()
  }

  companion object {
    val VARIABLES = linkedMapOf(
      "umidita" to FusionVariables.HUMIDITY,
      "rugiada" to FusionVariables.DEW_POINT,
      "pressione" to FusionVariables.PRESSURE_MSL,
      "nuvole" to FusionVariables.CLOUD_COVER,
      "uv" to FusionVariables.UV_INDEX,
      "visibilita" to FusionVariables.VISIBILITY,
      "raffiche" to FusionVariables.WIND_GUST,
    )
  }
}
