package dev.pampa.fluidweather.core.ai.tools

import dev.antigravity.fluidengine.ai.tools.Schema
import dev.antigravity.fluidengine.ai.tools.ToolText
import dev.pampa.fluidweather.core.ai.data.PlaceResolver
import dev.pampa.fluidweather.core.ai.data.ResolvedPlace
import dev.pampa.fluidweather.core.weather.radar.DbzPalette
import dev.pampa.fluidweather.core.weather.radar.RadarSample
import dev.pampa.fluidweather.core.weather.radar.RadarTrend
import dev.antigravity.fluidengine.ai.tools.Args.int
import dev.antigravity.fluidengine.ai.tools.Args.str
import dev.pampa.fluidweather.core.model.WeatherKind
import dev.pampa.fluidweather.strings.compassPoint
import kotlin.math.roundToInt
import kotlinx.serialization.json.JsonObject
import kotlinx.coroutines.CancellationException

/** Pioggia e neve nelle prossime ore: probabilita', accumuli, inizio e fine, tipo. */
class UpcomingPrecipitationTool(private val resolver: PlaceResolver) : WeatherTool() {
  override val name = "pioggia_prossime_ore"
  override val group = ToolGroup.PRECIP
  override val description = "Precipitazioni previste ora per ora nelle prossime N ore (max 48): probabilita', quantita', tipo (pioggia/neve/temporale), valutazione condivisa di pioggia e transizioni; dettagli del modello distinti."
  override val parameters = Schema.obj(mapOf("luogo" to Schema.place, "ore" to Schema.int("quante ore avanti (1-48)", 1, 48)))

  override suspend fun answer(args: JsonObject, ctx: ToolContext): String {
    val place = resolver.resolve(args.str("luogo"), ctx.selected) ?: return ToolPhrases.PLACE_NOT_FOUND
    val facts = dev.pampa.fluidweather.core.ai.grounding.sharedWeatherFacts(place, resolver, ctx)
    val detail = HourlyForecastTool(resolver).answer(kotlinx.serialization.json.buildJsonObject {
      put("luogo", kotlinx.serialization.json.JsonPrimitive(""))
      put("a_ore", kotlinx.serialization.json.JsonPrimitive((args.int("ore") ?: 12).coerceIn(1, 48)))
    }, ToolContext({ ctx.sources }, { ctx.units }, { ctx.resources }, ctx.locale, ctx.zone, ctx.nowMillis, place, ctx.actionsEnabled, ctx.actions))
    return "$facts\n\nMODEL forecast samples (not measurements or onset/end times; gaps remain unknown):\n$detail"
  }

  companion object {
    val WET_KINDS = setOf(WeatherKind.DRIZZLE, WeatherKind.RAIN, WeatherKind.HEAVY_RAIN, WeatherKind.SLEET, WeatherKind.SNOW, WeatherKind.HEAVY_SNOW, WeatherKind.THUNDERSTORM)
  }
}

/** Il radar letto come numeri attorno al posto. */
class RadarAroundTool internal constructor(
  private val resolvePlace: suspend (String?, ResolvedPlace?) -> ResolvedPlace?,
  private val sampleRadar: suspend (Double, Double, ToolContext) -> RadarSample,
  private val clock: () -> Long,
  private val shared: (suspend (ResolvedPlace, ToolContext) -> String)? = null,
) : WeatherTool() {
  constructor(resolver: PlaceResolver, clock: () -> Long = System::currentTimeMillis) : this(
    { query, selected -> resolver.resolve(query, selected) },
    { latitude, longitude, ctx -> ctx.sources.radarSampler.sample(latitude, longitude) },
    clock,
    { place, ctx -> dev.pampa.fluidweather.core.ai.grounding.sharedWeatherFacts(place, resolver, ctx) },
  )
  override val name = "radar_intorno"
  override val group = ToolGroup.PRECIP
  override val description = "Il radar RainViewer attorno al posto: intensita' sul punto, eco piu' vicino (distanza e direzione), copertura nei 30 km, tendenza dell'ultima ora, moto delle celle; pioggia e transizioni solo dalla valutazione condivisa. E' una stima da immagini."
  override val parameters = Schema.obj(mapOf("luogo" to Schema.place))

  override suspend fun answer(args: JsonObject, ctx: ToolContext): String {
    val place = resolvePlace(args.str("luogo"), ctx.selected) ?: return ToolPhrases.PLACE_NOT_FOUND
    val sharedFacts = shared?.invoke(place, ctx)
    val sample = sampleRadar(place.latitude, place.longitude, ctx)
    // The chat context predates orchestration and IO. Recheck at actual consumption.
    val consumedAtMillis = clock()
    val reading = when (sample) {
      is RadarSample.Unavailable -> return listOfNotNull(sharedFacts, "radar non disponibile").joinToString("\n")
      is RadarSample.Ok -> sample.reading
    }
    if (!reading.isUsableAt(consumedAtMillis)) return listOfNotNull(sharedFacts, "radar non disponibile: fotogramma vecchio, copertura sconosciuta o raster incompleto").joinToString("\n")
    val eta = reading.absoluteEta(consumedAtMillis)
    if (eta?.transitionAlreadyPassed == true) return listOfNotNull(sharedFacts, "radar: transizione stimata gia' trascorsa; serve un nuovo fotogramma. Fonte RainViewer, stima da immagini; il fotogramma precedente non basta a stabilire la pioggia attuale.").joinToString("\n")
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
      sharedFacts?.let { line(it) }
      line("luogo", place.label)
      val frameAgeMin = ((consumedAtMillis - reading.frameTimeMillis) / 60_000.0).roundToInt().coerceAtLeast(0)
      line("fotogramma", "${ctx.timeLabel(reading.frameTimeMillis)} ($frameAgeMin min fa), riquadro ${reading.boxKm} km, fonte RainViewer")
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
          RadarTrend.NO_ECHO -> "nessun eco nei fotogrammi completi disponibili"
        },
      )
      reading.motion?.let { m ->
        if (m.stationary) {
          line("moto delle celle", "quasi ferme (${u.wind(m.speedKmh)})")
        } else {
          line("moto delle celle", "${u.wind(m.speedKmh)} da ${compassPoint(ctx.resources, m.fromDeg)} verso ${compassPoint(ctx.resources, m.towardDeg)}, ${m.pairsUsed} coppie di fotogrammi" + if (!m.agreement) ", in disaccordo" else "")
        }
      } ?: line("moto delle celle", "non stimabile")
      line("ETA", "solo la valutazione congiunta condivisa determina pioggia attuale e transizioni; nessuna ETA indipendente da questo diagnostico")
      line("confidenza euristica", "${reading.confidenceLabel}; non e' una probabilita' calibrata")
      line("natura", "RADAR_ESTIMATE, stima da immagini composite; assenza eco nella zona coperta non prova assenza di pioggia al suolo")
      line("note", reading.notes.joinToString("; "))
    }
  }
}

/** Gli orari dei fotogrammi disponibili: quanto indietro e quanto avanti guarda il radar. */
class RadarFramesTool : WeatherTool() {
  override val name = "radar_fotogrammi"
  override val group = ToolGroup.PRECIP
  override val description = "Gli orari dei fotogrammi radar disponibili (passato e previsione) e quando sono stati generati."
  override val parameters = Schema.obj(emptyMap())

  override suspend fun answer(args: JsonObject, ctx: ToolContext): String {
    val frames = try { ctx.sources.rainViewer.frames() } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { null } ?: return "radar non raggiungibile"
    return ToolText.build {
      line("generato", ctx.timeLabel(frames.generatedMillis))
      line("passato", frames.past.joinToString(", ") { ctx.timeLabel(it.timeMillis) })
      line("previsione", if (frames.nowcast.isEmpty()) "nessuna" else frames.nowcast.joinToString(", ") { ctx.timeLabel(it.timeMillis) })
    }
  }
}
