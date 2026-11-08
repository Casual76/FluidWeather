package dev.pampa.fluidweather.core.ai.tools

import dev.antigravity.fluidengine.ai.tools.Schema
import dev.antigravity.fluidengine.ai.tools.ToolText
import dev.pampa.fluidweather.core.ai.data.WeatherPlaceResolver
import dev.antigravity.fluidengine.ai.tools.Args.int
import dev.antigravity.fluidengine.ai.tools.Args.list
import dev.antigravity.fluidengine.ai.tools.Args.str
import dev.pampa.fluidweather.core.model.FusionVariables
import dev.pampa.fluidweather.core.model.nearestHour
import java.time.Instant
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.serialization.json.JsonObject

/**
 * Il quadro condiviso corrente e le finestre personali; i dettagli futuri hanno tool dedicati.
 */
class WeatherSummaryTool(private val resolver: WeatherPlaceResolver) : WeatherTool() {
  override val name = "meteo_riepilogo"
  override val group = ToolGroup.HOURLY
  override val description = "Quadro corrente condiviso: pioggia, transizioni approssimative, condizioni e finestre personali configurate. Per i prossimi giorni usa previsione_giorni o giorno_dettaglio; per le ore future usa previsione_oraria o pioggia_prossime_ore."
  override val parameters = Schema.obj(mapOf("luogo" to Schema.place))

  override suspend fun answer(args: JsonObject, ctx: ToolContext): String {
    val place = resolver.resolve(args.str("luogo"), ctx.selected) ?: return ToolPhrases.PLACE_NOT_FOUND
    return dev.pampa.fluidweather.core.ai.grounding.sharedWeatherFacts(place, resolver, ctx)
  }
}

/**
 * Pioggia condivisa e orari personali. Non deduce intervalli asciutti attraverso lacune.
 */
class DryWindowTool(private val resolver: WeatherPlaceResolver) : WeatherTool() {
  override val name = "finestra_asciutta"
  override val group = ToolGroup.PRECIP
  override val description = "Valutazione condivisa di pioggia, transizioni approssimative e finestre personali configurate; lacune sconosciute. Per altre ore future usa pioggia_prossime_ore; per i giorni usa previsione_giorni. Non calcola la finestra asciutta piu' lunga."
  override val parameters = Schema.obj(mapOf("luogo" to Schema.place))

  override suspend fun answer(args: JsonObject, ctx: ToolContext): String {
    val place = resolver.resolve(args.str("luogo"), ctx.selected) ?: return ToolPhrases.PLACE_NOT_FOUND
    return dev.pampa.fluidweather.core.ai.grounding.sharedWeatherFacts(place, resolver, ctx)
  }
}

/** Due o tre posti a confronto, per decidere dove andare. */
class ComparePlacesTool(private val resolver: WeatherPlaceResolver) : WeatherTool() {
  override val name = "confronta_luoghi"
  override val group = ToolGroup.PLACES
  override val description = "Confronta il tempo di due o tre localita' nello stesso momento (adesso o fra N ore): temperatura, condizione, pioggia, vento. Per \"dove c'e' piu' sole\", \"meglio il mare o la montagna?\"."
  override val parameters = Schema.obj(
    mapOf(
      "luoghi" to Schema.strArray("i nomi delle localita' da confrontare (da due a tre); \"qui\" vale per quella selezionata"),
      "fra_ore" to Schema.int("fra quante ore confrontare (0 = adesso, max 48)", 0, 48),
    ),
    required = listOf("luoghi"),
  )

  override suspend fun answer(args: JsonObject, ctx: ToolContext): String {
    val names = args.list("luoghi").filter { it.isNotBlank() }.distinct().take(3)
    if (names.size < 2) return "errore: servono almeno due localita' da confrontare"
    val offset = (args.int("fra_ore") ?: 0).coerceIn(0, 48)
    val at = ctx.nowMillis + offset * 3_600_000L
    val rows = names.map { name ->
      val place = resolver.resolve(name, ctx.selected)
      val snapshot = place?.let { resolver.snapshot(it) }
      val hour = snapshot?.fused?.nearestHour(at)?.first?.takeIf { abs(it.timestampMillis - at) <= 90 * 60_000L }
      Triple(name, place, hour)
    }
    return ToolText.build {
      line("momento", if (offset == 0) "adesso" else "fra $offset ore (${ctx.dayTimeLabel(at)})")
      rows.forEach { (name, place, hour) ->
        when {
          place == null -> line(ToolFailure.SEARCH_EMPTY.text(ctx))
          hour == null -> line(place.label, "nessun dato per quel momento")
          else -> line(
            place.label,
            listOfNotNull(
              ctx.kindLabel(hour.kind),
              hour.value(FusionVariables.TEMPERATURE)?.let { ctx.units.temperature(it) },
              hour.value(FusionVariables.PRECIP_PROBABILITY)?.let { "pioggia ${it.roundToInt()}%" },
              hour.value(FusionVariables.WIND_SPEED)?.let { "vento ${ctx.units.wind(it)}" },
              hour.value(FusionVariables.CLOUD_COVER)?.let { "nuvole ${it.roundToInt()}%" },
            ).joinToString(" · "),
          )
        }
      }
      val warmest = rows.mapNotNull { (_, place, hour) -> place?.let { p -> hour?.value(FusionVariables.TEMPERATURE)?.let { p to it } } }.maxByOrNull { it.second }
      warmest?.let { line("piu' caldo", "${it.first.label} (${ctx.units.temperature(it.second)})") }
      val driest = rows.mapNotNull { (_, place, hour) -> place?.let { p -> hour?.value(FusionVariables.PRECIP_PROBABILITY)?.let { p to it } } }.minByOrNull { it.second }
      driest?.let { line("meno probabilita' di pioggia", "${it.first.label} (${it.second.roundToInt()}%)") }
    }
  }
}

/** Per i test: la data di oggi nella zona del contesto. */
internal fun ToolContext.today(): LocalDate = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
