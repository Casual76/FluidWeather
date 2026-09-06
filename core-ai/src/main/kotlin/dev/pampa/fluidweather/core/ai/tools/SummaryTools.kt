package dev.pampa.fluidweather.core.ai.tools

import dev.pampa.fluidweather.core.ai.data.PlaceResolver
import dev.pampa.fluidweather.core.ai.tools.Args.int
import dev.pampa.fluidweather.core.ai.tools.Args.list
import dev.pampa.fluidweather.core.ai.tools.Args.str
import dev.pampa.fluidweather.core.cycle.PrecipitationTransitions
import dev.pampa.fluidweather.core.model.DailyAggregate
import dev.pampa.fluidweather.core.model.FusedHour
import dev.pampa.fluidweather.core.model.FusionVariables
import dev.pampa.fluidweather.core.model.nearestHour
import java.time.Instant
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.serialization.json.JsonObject

/**
 * Il meteo di un posto in una chiamata sola: adesso, le prossime ore, i prossimi giorni. Nasce
 * dalla domanda piu' frequente — "che tempo fa?" — che prima costava tre strumenti e tre giri.
 */
class WeatherSummaryTool(private val resolver: PlaceResolver) : AiTool {
  override val name = "meteo_riepilogo"
  override val group = ToolGroup.HOURLY
  override val description = "Il quadro completo di un posto in una volta sola: condizioni di adesso, le prossime ore (quando piove, quanto sale o scende la temperatura) e i prossimi giorni con minime e massime. Usalo per \"che tempo fa\", \"com'e' il meteo\", \"che tempo farà\"."
  override val parameters = Schema.obj(mapOf("luogo" to Schema.place, "giorni" to Schema.int("quanti giorni includere (1-7, default 3)", 1, 7)))

  override suspend fun run(args: JsonObject, ctx: ToolContext): String {
    val place = resolver.resolve(args.str("luogo"), ctx.selected) ?: return ToolPhrases.PLACE_NOT_FOUND
    val snapshot = resolver.snapshot(place) ?: return ToolPhrases.NO_DATA
    val u = ctx.units
    val hours = snapshot.fused.hours.sortedBy { it.timestampMillis }
    val now = snapshot.fused.nearestHour(ctx.nowMillis)?.first?.takeIf { abs(it.timestampMillis - ctx.nowMillis) <= 90 * 60_000L }
    val days = (args.int("giorni") ?: 3).coerceIn(1, 7)
    val today = Instant.ofEpochMilli(ctx.nowMillis).atZone(ctx.zone).toLocalDate()
    val summaries = DailyAggregate.of(hours, ctx.zone, place.latitude, place.longitude, today, days)
    return ToolText.build {
      line(ToolPhrases.header(ctx, place, snapshot))
      if (now == null) {
        line("adesso", "nessuna ora recente nei dati")
      } else {
        val t = now.value(FusionVariables.TEMPERATURE)
        val wind = now.value(FusionVariables.WIND_SPEED)
        line(
          "adesso",
          listOfNotNull(
            ctx.kindLabel(now.kind),
            t?.let { u.temperature(it) },
            now.value(FusionVariables.HUMIDITY)?.let { "umidita' ${it.roundToInt()}%" },
            wind?.let { "vento ${u.wind(it)}" },
          ).joinToString(" · "),
        )
      }
      // Le prossime dodici ore in tre righe: quando piove, il caldo e il freddo.
      val window = hours.filter { it.timestampMillis in ctx.nowMillis..(ctx.nowMillis + 12 * 3_600_000L) }
      if (window.isNotEmpty()) {
        val wet = window.firstOrNull { PrecipitationTransitions.isWet(it) }
        line("prossime 12 ore", if (wet == null) "nessuna ora bagnata prevista" else "prima ora bagnata: ${ctx.dayTimeLabel(wet.timestampMillis)} (${ctx.kindLabel(wet.kind)})")
        window.maxByOrNull { it.value(FusionVariables.TEMPERATURE) ?: Double.MIN_VALUE }?.let { max ->
          max.value(FusionVariables.TEMPERATURE)?.let { line("massima nelle 12 ore", "${u.temperature(it)} verso le ${ctx.timeLabel(max.timestampMillis)}") }
        }
        window.minByOrNull { it.value(FusionVariables.TEMPERATURE) ?: Double.MAX_VALUE }?.let { min ->
          min.value(FusionVariables.TEMPERATURE)?.let { line("minima nelle 12 ore", "${u.temperature(it)} verso le ${ctx.timeLabel(min.timestampMillis)}") }
        }
      }
      blank()
      line("giorno · condizione · min/max · pioggia")
      summaries.forEach { day ->
        val pop = day.precipitationProbabilityMaxPercent?.let { "${it.roundToInt()}%" } ?: "—"
        line(
          "${day.date} · ${ctx.kindLabel(day.kind)} · ${u.temperature(day.minC)}/${u.temperature(day.maxC)} · " +
            pop + (day.precipitationMm.takeIf { it >= 0.05 }?.let { ", ${u.precipitation(it)}" } ?: ""),
        )
      }
    }
  }
}

/**
 * "Quando posso uscire senza prendere acqua": la finestra asciutta piu' lunga, invece delle ore
 * bagnate una per una. La domanda che l'utente fa davvero non e' "quanto piove", e' "quando smette".
 */
class DryWindowTool(private val resolver: PlaceResolver) : AiTool {
  override val name = "finestra_asciutta"
  override val group = ToolGroup.PRECIP
  override val description = "Le finestre senza pioggia nelle prossime ore: quando comincia e quanto dura la piu' lunga. Per \"quando posso uscire\", \"quando smette\", \"c'e' un momento asciutto oggi?\"."
  override val parameters = Schema.obj(mapOf("luogo" to Schema.place, "ore" to Schema.int("quante ore guardare avanti (1-48, default 12)", 1, 48)))

  override suspend fun run(args: JsonObject, ctx: ToolContext): String {
    val place = resolver.resolve(args.str("luogo"), ctx.selected) ?: return ToolPhrases.PLACE_NOT_FOUND
    val snapshot = resolver.snapshot(place) ?: return ToolPhrases.NO_DATA
    val horizon = (args.int("ore") ?: 12).coerceIn(1, 48)
    val end = ctx.nowMillis + horizon * 3_600_000L
    val window = snapshot.fused.hours.sortedBy { it.timestampMillis }
      .filter { it.timestampMillis >= ctx.nowMillis - 30 * 60_000L && it.timestampMillis <= end }
    if (window.isEmpty()) return ToolPhrases.NO_DATA
    val runs = dryRuns(window)
    return ToolText.build {
      line(ToolPhrases.header(ctx, place, snapshot))
      line("finestra guardata", "prossime $horizon ore")
      if (runs.isEmpty()) {
        line("nessuna finestra asciutta", "piove (o e' previsto bagnato) per tutte le ore guardate")
        return@build
      }
      val longest = runs.maxBy { it.size }
      if (runs.size == 1 && longest.size == window.size) {
        line("asciutto", "tutte le ore guardate sono asciutte")
      } else {
        line("finestra piu' lunga", "${ctx.dayTimeLabel(longest.first().timestampMillis)} → ${ctx.timeLabel(longest.last().timestampMillis)} (${longest.size} ore)")
        if (runs.size > 1) {
          line("altre finestre asciutte")
          runs.filter { it !== longest }.take(4).forEach { run ->
            line("  ${ctx.dayTimeLabel(run.first().timestampMillis)} → ${ctx.timeLabel(run.last().timestampMillis)} (${run.size} ore)")
          }
        }
      }
      val wet = window.filter { PrecipitationTransitions.isWet(it) }
      if (wet.isNotEmpty()) line("ore bagnate", wet.joinToString(", ") { ctx.timeLabel(it.timestampMillis) })
    }
  }

  /** Le sequenze contigue di ore non bagnate. */
  private fun dryRuns(window: List<FusedHour>): List<List<FusedHour>> {
    val runs = mutableListOf<MutableList<FusedHour>>()
    window.forEach { hour ->
      if (PrecipitationTransitions.isWet(hour)) {
        if (runs.lastOrNull()?.isNotEmpty() == true) runs.add(mutableListOf())
      } else {
        if (runs.isEmpty()) runs.add(mutableListOf())
        runs.last().add(hour)
      }
    }
    return runs.filter { it.isNotEmpty() }
  }
}

/** Due o tre posti a confronto, per decidere dove andare. */
class ComparePlacesTool(private val resolver: PlaceResolver) : AiTool {
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

  override suspend fun run(args: JsonObject, ctx: ToolContext): String {
    val names = args.list("luoghi").filter { it.isNotBlank() }.distinct().take(3)
    if (names.size < 2) return "errore: servono almeno due localita' da confrontare"
    val offset = (args.int("fra_ore") ?: 0).coerceIn(0, 48)
    val at = ctx.nowMillis + offset * 3_600_000L
    val u = ctx.units
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
          place == null -> line(name, "localita' non trovata")
          hour == null -> line(place.label, "nessun dato per quel momento")
          else -> line(
            place.label,
            listOfNotNull(
              ctx.kindLabel(hour.kind),
              hour.value(FusionVariables.TEMPERATURE)?.let { u.temperature(it) },
              hour.value(FusionVariables.PRECIP_PROBABILITY)?.let { "pioggia ${it.roundToInt()}%" },
              hour.value(FusionVariables.WIND_SPEED)?.let { "vento ${u.wind(it)}" },
              hour.value(FusionVariables.CLOUD_COVER)?.let { "nuvole ${it.roundToInt()}%" },
            ).joinToString(" · "),
          )
        }
      }
      val warmest = rows.mapNotNull { (_, place, hour) -> place?.let { p -> hour?.value(FusionVariables.TEMPERATURE)?.let { p to it } } }.maxByOrNull { it.second }
      warmest?.let { line("piu' caldo", "${it.first.label} (${u.temperature(it.second)})") }
      val driest = rows.mapNotNull { (_, place, hour) -> place?.let { p -> hour?.value(FusionVariables.PRECIP_PROBABILITY)?.let { p to it } } }.minByOrNull { it.second }
      driest?.let { line("meno probabilita' di pioggia", "${it.first.label} (${it.second.roundToInt()}%)") }
    }
  }
}

/** Per i test: la data di oggi nella zona del contesto. */
internal fun ToolContext.today(): LocalDate = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
