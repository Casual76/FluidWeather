package dev.pampa.fluidweather.core.ai.tools

import dev.pampa.fluidweather.core.ai.data.PlaceResolver
import dev.pampa.fluidweather.core.ai.radar.DbzPalette
import dev.pampa.fluidweather.core.ai.radar.RadarSample
import dev.pampa.fluidweather.core.ai.radar.RadarTrend
import dev.pampa.fluidweather.core.ai.tools.Args.int
import dev.pampa.fluidweather.core.ai.tools.Args.str
import dev.pampa.fluidweather.core.cycle.PrecipitationTransitions
import dev.pampa.fluidweather.core.cycle.TransitionKind
import dev.pampa.fluidweather.core.model.FusionVariables
import dev.pampa.fluidweather.core.model.WeatherKind
import dev.pampa.fluidweather.strings.compassPoint
import kotlin.math.roundToInt
import kotlinx.serialization.json.JsonObject

/** Pioggia e neve nelle prossime ore: probabilita', accumuli, inizio e fine, tipo. */
class UpcomingPrecipitationTool(private val resolver: PlaceResolver) : AiTool {
  override val name = "pioggia_prossime_ore"
  override val group = ToolGroup.PRECIP
  override val description = "Precipitazioni previste ora per ora nelle prossime N ore (max 48): probabilita', quantita', tipo (pioggia/neve/temporale), inizio e fine previsti."
  override val parameters = Schema.obj(mapOf("luogo" to Schema.place, "ore" to Schema.int("quante ore avanti (1-48)", 1, 48)))

  override suspend fun run(args: JsonObject, ctx: ToolContext): String {
    val place = resolver.resolve(args.str("luogo"), ctx.selected) ?: return ToolPhrases.PLACE_NOT_FOUND
    val snapshot = resolver.snapshot(place) ?: return ToolPhrases.NO_DATA
    val hours = (args.int("ore") ?: 12).coerceIn(1, 48)
    val end = ctx.nowMillis + hours * 3_600_000L
    val fused = snapshot.fused.hours.sortedBy { it.timestampMillis }
    val window = fused.filter { it.timestampMillis >= ctx.nowMillis - 30 * 60_000L && it.timestampMillis <= end }
    if (window.isEmpty()) return ToolPhrases.NO_DATA
    val u = ctx.units
    return ToolText.build {
      line(ToolPhrases.header(ctx, place, snapshot))
      val transition = PrecipitationTransitions.next(fused, ctx.nowMillis)
      val current = window.first()
      line("adesso", if (PrecipitationTransitions.isWet(current)) "ora bagnata (${ctx.kindLabel(current.kind)})" else "asciutto")
      transition?.let {
        line(
          if (it.kind == TransitionKind.ONSET) "inizio previsto" else "fine prevista",
          "${ctx.timeLabel(it.atMillis)} (${ctx.kindLabel(it.weatherKind)})",
        )
      }
      val firstWet = window.firstOrNull { PrecipitationTransitions.isWet(it) }
      if (transition == null && firstWet != null && firstWet !== current) line("prima ora bagnata", ctx.dayTimeLabel(firstWet.timestampMillis))
      if (transition == null && firstWet == null) line("nelle prossime $hours ore", "nessuna ora bagnata prevista")
      val total = window.sumOf { it.value(FusionVariables.PRECIPITATION) ?: 0.0 }
      line("accumulo totale previsto", u.precipitation(total))
      val maxPop = window.maxOfOrNull { it.value(FusionVariables.PRECIP_PROBABILITY) ?: 0.0 } ?: 0.0
      line("probabilita' massima", "${maxPop.roundToInt()}%")
      val types = window.mapNotNull { it.kind }.filter { it in WET_KINDS }.distinct()
      if (types.isNotEmpty()) line("tipo", types.joinToString(", ") { ctx.kindLabel(it) })
      blank()
      line("ora · probabilita' · quantita' · condizione")
      val step = if (window.size <= 24) 1 else 2
      window.filterIndexed { i, _ -> i % step == 0 }.forEach { h ->
        val pop = h.value(FusionVariables.PRECIP_PROBABILITY)?.let { "${it.roundToInt()}%" } ?: "—"
        val mm = h.value(FusionVariables.PRECIPITATION)?.let { if (it >= 0.05) u.precipitation(it) else "0" } ?: "—"
        line("${ctx.timeLabel(h.timestampMillis)} · $pop · $mm · ${ctx.kindLabel(h.kind)}")
      }
    }
  }

  companion object {
    val WET_KINDS = setOf(WeatherKind.DRIZZLE, WeatherKind.RAIN, WeatherKind.HEAVY_RAIN, WeatherKind.SLEET, WeatherKind.SNOW, WeatherKind.HEAVY_SNOW, WeatherKind.THUNDERSTORM)
  }
}

/** Il radar letto come numeri attorno al posto. */
class RadarAroundTool(private val resolver: PlaceResolver) : AiTool {
  override val name = "radar_intorno"
  override val group = ToolGroup.PRECIP
  override val description = "Il radar RainViewer attorno al posto: intensita' sul punto, eco piu' vicino (distanza e direzione), copertura nei 30 km, tendenza dell'ultima ora, moto delle celle, stima di arrivo o fine. E' una stima da immagini."
  override val parameters = Schema.obj(mapOf("luogo" to Schema.place))

  override suspend fun run(args: JsonObject, ctx: ToolContext): String {
    val place = resolver.resolve(args.str("luogo"), ctx.selected) ?: return ToolPhrases.PLACE_NOT_FOUND
    val sample = ctx.sources.radarSampler.sample(place.latitude, place.longitude)
    val reading = when (sample) {
      is RadarSample.Unavailable -> return "radar non disponibile: ${sample.reason}"
      is RadarSample.Ok -> sample.reading
    }
    val u = ctx.units
    fun label(dbz: Int): String = when (DbzPalette.intensity(dbz)) {
      DbzPalette.Intensity.NONE -> "nulla"
      DbzPalette.Intensity.DRIZZLE -> "pioviggine"
      DbzPalette.Intensity.LIGHT -> "leggera"
      DbzPalette.Intensity.MODERATE -> "moderata"
      DbzPalette.Intensity.HEAVY -> "forte"
      DbzPalette.Intensity.VERY_HEAVY -> "molto forte"
      DbzPalette.Intensity.HAIL -> "violenta/grandine"
    }
    return ToolText.build {
      line("luogo", place.label)
      line("fotogramma", "${ctx.timeLabel(reading.frameTimeMillis)} (${reading.frameAgeMin} min fa), riquadro ${reading.boxKm} km, fonte RainViewer")
      line("sul punto", "${label(reading.atPointDbz)} (${reading.atPointDbz} dBZ" + (if (reading.atPointDbz >= 10) ", ~${u.precipitation(DbzPalette.rateMmPerHour(reading.atPointDbz))}/h" else "") + ")")
      reading.nearest?.let {
        line("eco piu' vicino", "${u.distance(it.km, 1)} verso ${compassPoint(ctx.resources, it.bearingDeg.toDouble())} (${it.bearingDeg}°), ${label(it.maxDbz)} ${it.maxDbz} dBZ")
      } ?: line("eco piu' vicino", "nessuno entro ${u.distance(reading.searchedKm)}")
      line("copertura nei ${reading.boxKm} km", "${reading.areaRainingPct}% dell'area con eco, massimo ${reading.maxDbz} dBZ (${label(reading.maxDbz)})")
      line(
        "tendenza ultima ora",
        when (reading.trend) {
          RadarTrend.INTENSIFYING -> "in intensificazione"
          RadarTrend.WEAKENING -> "in attenuazione"
          RadarTrend.STEADY -> "stabile"
          RadarTrend.NO_ECHO -> "nessuna precipitazione nell'ultima ora"
        },
      )
      reading.motion?.let { m ->
        if (m.stationary) {
          line("moto delle celle", "quasi ferme (${u.wind(m.speedKmh)})")
        } else {
          line("moto delle celle", "${u.wind(m.speedKmh)} da ${compassPoint(ctx.resources, m.fromDeg)} verso ${compassPoint(ctx.resources, m.towardDeg)}, ${m.pairsUsed} coppie di fotogrammi" + if (!m.agreement) ", in disaccordo" else "")
        }
      } ?: line("moto delle celle", "non stimabile")
      reading.eta?.let { eta ->
        when {
          !reading.rainingNow && eta.arrivesInMin != null -> line("stima di arrivo", "tra ~${eta.arrivesInMin} min, intensita' attesa ${label(eta.expectedDbz ?: 0)} (estrapolazione del moto, orizzonte ${eta.horizonMin} min)")
          !reading.rainingNow -> line("stima di arrivo", "nessuna cella in rotta sul punto entro ${eta.horizonMin} min")
          eta.endsInMin != null -> line("stima di fine", "tra ~${eta.endsInMin} min")
          else -> line("stima di fine", "non entro ${eta.horizonMin} min")
        }
      }
      if (reading.nowcastAtPoint.isNotEmpty()) {
        line("previsione RainViewer sul punto", reading.nowcastAtPoint.joinToString(", ") { (min, dbz) -> "+$min min ${label(dbz)}" })
      }
      line("confidenza", "${reading.confidenceLabel} (${(reading.confidence * 100).roundToInt()}%)")
      line("note", reading.notes.joinToString("; "))
    }
  }
}

/** Gli orari dei fotogrammi disponibili: quanto indietro e quanto avanti guarda il radar. */
class RadarFramesTool : AiTool {
  override val name = "radar_fotogrammi"
  override val group = ToolGroup.PRECIP
  override val description = "Gli orari dei fotogrammi radar disponibili (passato e previsione) e quando sono stati generati."
  override val parameters = Schema.obj(emptyMap())

  override suspend fun run(args: JsonObject, ctx: ToolContext): String {
    val frames = runCatching { ctx.sources.rainViewer.frames() }.getOrNull() ?: return "radar non raggiungibile"
    return ToolText.build {
      line("generato", ctx.timeLabel(frames.generatedMillis))
      line("passato", frames.past.joinToString(", ") { ctx.timeLabel(it.timeMillis) })
      line("previsione", if (frames.nowcast.isEmpty()) "nessuna" else frames.nowcast.joinToString(", ") { ctx.timeLabel(it.timeMillis) })
    }
  }
}
