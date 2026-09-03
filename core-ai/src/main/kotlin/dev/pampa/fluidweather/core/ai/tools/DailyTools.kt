package dev.pampa.fluidweather.core.ai.tools

import dev.pampa.fluidweather.core.ai.data.PlaceResolver
import dev.pampa.fluidweather.core.ai.tools.Args.int
import dev.pampa.fluidweather.core.ai.tools.Args.str
import dev.pampa.fluidweather.core.model.DailyAggregate
import dev.pampa.fluidweather.core.model.FusionVariables
import dev.pampa.fluidweather.strings.TimeFormats
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeParseException
import kotlin.math.roundToInt
import kotlinx.serialization.json.JsonObject

/** I prossimi giorni, uno per riga: l'aggregato condiviso con la pagina del giornaliero. */
class DailyForecastTool(private val resolver: PlaceResolver) : AiTool {
  override val name = "previsione_giorni"
  override val group = ToolGroup.DAILY
  override val description = "I prossimi giorni (max 10): condizione prevalente, minima, massima, probabilita' e quantita' di pioggia, vento massimo, alba e tramonto."
  override val parameters = Schema.obj(mapOf("luogo" to Schema.place, "giorni" to Schema.int("quanti giorni (1-10)", 1, 10)))

  override suspend fun run(args: JsonObject, ctx: ToolContext): String {
    val place = resolver.resolve(args.str("luogo"), ctx.selected) ?: return ToolPhrases.PLACE_NOT_FOUND
    val snapshot = resolver.snapshot(place) ?: return ToolPhrases.NO_DATA
    val days = (args.int("giorni") ?: 7).coerceIn(1, 10)
    val today = Instant.ofEpochMilli(ctx.nowMillis).atZone(ctx.zone).toLocalDate()
    val summaries = DailyAggregate.of(snapshot.fused.hours, ctx.zone, place.latitude, place.longitude, today, days)
    if (summaries.isEmpty()) return ToolPhrases.NO_DATA
    val u = ctx.units
    return ToolText.build {
      line(ToolPhrases.header(ctx, place, snapshot))
      summaries.forEach { day ->
        val parts = mutableListOf<String>()
        parts += TimeFormats.shortDate(day.date, ctx.locale) + (if (day.date == today) " (oggi)" else if (day.date == today.plusDays(1)) " (domani)" else "")
        parts += ctx.kindLabel(day.kind)
        parts += "min ${u.temperature(day.minC)} max ${u.temperature(day.maxC)}"
        day.precipitationProbabilityMaxPercent?.let { parts += "pioggia fino a ${it.roundToInt()}%" }
        if (day.precipitationMm >= 0.1) parts += "accumulo ${u.precipitation(day.precipitationMm)}"
        day.windMaxKmh?.let { parts += "vento max ${u.wind(it)}" }
        val sunrise = day.sunriseMillis
        val sunset = day.sunsetMillis
        if (sunrise != null && sunset != null) parts += "sole ${ctx.timeLabel(sunrise)}-${ctx.timeLabel(sunset)}"
        if (day.hours.size < 20) parts += "(${day.hours.size} ore di dati)"
        line(parts.joinToString(" · "))
      }
    }
  }
}

/** Un giorno nelle sue quattro fasce, piu' le ore salienti. */
class DayDetailTool(private val resolver: PlaceResolver) : AiTool {
  override val name = "giorno_dettaglio"
  override val group = ToolGroup.DAILY
  override val description = "Il dettaglio di un giorno (data AAAA-MM-GG, 'oggi' o 'domani'): notte, mattina, pomeriggio, sera, con temperature, pioggia e vento per fascia."
  override val parameters = Schema.obj(mapOf("luogo" to Schema.place, "data" to Schema.str("AAAA-MM-GG, oppure oggi / domani")), required = listOf("data"))

  override suspend fun run(args: JsonObject, ctx: ToolContext): String {
    val place = resolver.resolve(args.str("luogo"), ctx.selected) ?: return ToolPhrases.PLACE_NOT_FOUND
    val snapshot = resolver.snapshot(place) ?: return ToolPhrases.NO_DATA
    val today = Instant.ofEpochMilli(ctx.nowMillis).atZone(ctx.zone).toLocalDate()
    val date = when (val raw = args.str("data")?.lowercase()) {
      null, "oggi", "today" -> today
      "domani", "tomorrow" -> today.plusDays(1)
      "dopodomani" -> today.plusDays(2)
      else -> try { LocalDate.parse(raw) } catch (e: DateTimeParseException) { return "errore: data non capita ($raw); usa AAAA-MM-GG" }
    }
    val day = DailyAggregate.of(snapshot.fused.hours, ctx.zone, place.latitude, place.longitude, date, 1).firstOrNull { it.date == date }
      ?: return "nessun dato per il ${TimeFormats.shortDate(date, ctx.locale)}: fuori dall'orizzonte dei servizi"
    val u = ctx.units
    return ToolText.build {
      line(ToolPhrases.header(ctx, place, snapshot))
      line("giorno", TimeFormats.longDate(date, ctx.locale))
      line("prevalente", ctx.kindLabel(day.kind))
      line("minima / massima", "${u.temperature(day.minC)} / ${u.temperature(day.maxC)}")
      DailyAggregate.parts(day, ctx.zone).forEach { part ->
        val label = when (part.part) {
          DailyAggregate.DayPart.NIGHT -> "notte (0-6)"
          DailyAggregate.DayPart.MORNING -> "mattina (6-12)"
          DailyAggregate.DayPart.AFTERNOON -> "pomeriggio (12-18)"
          DailyAggregate.DayPart.EVENING -> "sera (18-24)"
        }
        val bits = mutableListOf<String>()
        bits += ctx.kindLabel(part.kind)
        val partMin = part.minC
        val partMax = part.maxC
        if (partMin != null && partMax != null) bits += "${u.temperature(partMin)}-${u.temperature(partMax)}"
        part.precipitationProbabilityMaxPercent?.let { bits += "pioggia ${it.roundToInt()}%" }
        if (part.precipitationMm >= 0.1) bits += u.precipitation(part.precipitationMm)
        part.windMaxKmh?.let { bits += "vento ${u.wind(it)}" }
        line(label, bits.joinToString(" · "))
      }
      val wettest = day.hours.maxByOrNull { it.value(FusionVariables.PRECIP_PROBABILITY) ?: 0.0 }
      wettest?.value(FusionVariables.PRECIP_PROBABILITY)?.takeIf { it >= 30 }?.let {
        line("ora piu' a rischio pioggia", "${ctx.timeLabel(wettest.timestampMillis)} (${it.roundToInt()}%)")
      }
      val windiest = day.hours.maxByOrNull { it.value(FusionVariables.WIND_SPEED) ?: 0.0 }
      windiest?.value(FusionVariables.WIND_SPEED)?.let { line("ora piu' ventosa", "${ctx.timeLabel(windiest.timestampMillis)} (${u.wind(it)})") }
      val sunrise = day.sunriseMillis
      val sunset = day.sunsetMillis
      if (sunrise != null && sunset != null) line("alba / tramonto", "${ctx.timeLabel(sunrise)} / ${ctx.timeLabel(sunset)}")
    }
  }
}
