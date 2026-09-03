package dev.pampa.fluidweather.core.ai.tools

import dev.pampa.fluidweather.core.ai.data.PlaceResolver
import dev.pampa.fluidweather.core.ai.tools.Args.str
import dev.pampa.fluidweather.core.model.Moon
import dev.pampa.fluidweather.core.model.MoonEphemeris
import dev.pampa.fluidweather.core.model.SolarEphemeris
import dev.pampa.fluidweather.core.model.SunTimes
import dev.pampa.fluidweather.core.model.Twilight
import dev.pampa.fluidweather.strings.TimeFormats
import dev.pampa.fluidweather.strings.labelRes
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeParseException
import kotlin.math.roundToInt
import kotlinx.serialization.json.JsonObject

/** Qualita' dell'aria e pollini, adesso e nelle prossime ore. */
class AirQualityTool(private val resolver: PlaceResolver) : AiTool {
  override val name = "qualita_aria"
  override val group = ToolGroup.AIR
  override val description = "Qualita' dell'aria (indice europeo EAQI): fascia, inquinante dominante, inquinanti con sotto-indice, pollini (Europa), andamento nelle prossime ore."
  override val parameters = Schema.obj(mapOf("luogo" to Schema.place))

  override suspend fun run(args: JsonObject, ctx: ToolContext): String {
    val place = resolver.resolve(args.str("luogo"), ctx.selected) ?: return ToolPhrases.PLACE_NOT_FOUND
    val air = runCatching { ctx.sources.airQualityClient.now(place.latitude, place.longitude) }.getOrNull()
      ?: return "qualita' dell'aria non disponibile per ${place.label}"
    return ToolText.build {
      line("luogo", place.label)
      line("indice EAQI", "${air.europeanAqi} (${ctx.string(air.band.labelRes())})")
      line("inquinante dominante", air.dominantPollutant?.let { d -> "$d" + (air.dominantValue?.let { " ${"%.0f".format(ctx.locale, it)} µg/m³" } ?: "") })
      if (air.pollutants.isNotEmpty()) {
        line("inquinanti", air.pollutants.joinToString(", ") { "${it.name} ${"%.0f".format(ctx.locale, it.valueUgm3)} µg/m³ (indice ${it.subIndex.roundToInt()}, ${ctx.string(it.band.labelRes())})" })
      }
      air.pollen?.takeIf { it.any }?.let { pollen ->
        val parts = listOfNotNull(
          pollen.alder?.let { "ontano ${it.roundToInt()}" },
          pollen.birch?.let { "betulla ${it.roundToInt()}" },
          pollen.grass?.let { "graminacee ${it.roundToInt()}" },
          pollen.olive?.let { "olivo ${it.roundToInt()}" },
          pollen.ragweed?.let { "ambrosia ${it.roundToInt()}" },
        )
        line("pollini (granuli/m³)", parts.joinToString(", "))
      } ?: line("pollini", "non disponibili qui (solo Europa)")
      val forecast = air.forecast.filter { it.timestampMillis > ctx.nowMillis }.take(12)
      if (forecast.isNotEmpty()) {
        val worst = forecast.maxBy { it.europeanAqi }
        val best = forecast.minBy { it.europeanAqi }
        line("prossime 12 ore", "da ${best.europeanAqi} (${ctx.timeLabel(best.timestampMillis)}) a ${worst.europeanAqi} (${ctx.timeLabel(worst.timestampMillis)})")
      }
    }
  }
}

private fun ToolContext.parseDate(raw: String?): LocalDate? {
  val today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
  return when (raw?.lowercase()) {
    null, "", "oggi", "today" -> today
    "domani", "tomorrow" -> today.plusDays(1)
    "ieri", "yesterday" -> today.minusDays(1)
    else -> try { LocalDate.parse(raw) } catch (e: DateTimeParseException) { null }
  }
}

/** Il sole di un giorno: alba, tramonto, mezzogiorno, crepuscoli, durata e differenza con ieri. */
class SunTool(private val resolver: PlaceResolver) : AiTool {
  override val name = "sole"
  override val group = ToolGroup.SKY
  override val description = "Alba, tramonto, mezzogiorno solare, crepuscoli civile/nautico/astronomico, durata del giorno e differenza con ieri, altezza del sole adesso. Data AAAA-MM-GG, oggi o domani."
  override val parameters = Schema.obj(mapOf("luogo" to Schema.place, "data" to Schema.str("AAAA-MM-GG, oggi, domani")))

  override suspend fun run(args: JsonObject, ctx: ToolContext): String {
    val place = resolver.resolve(args.str("luogo"), ctx.selected) ?: return ToolPhrases.PLACE_NOT_FOUND
    val date = ctx.parseDate(args.str("data")) ?: return "errore: data non capita"
    val dayStart = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    val times = SunTimes.forDay(dayStart, place.latitude, place.longitude)
    val civil = SunTimes.forDay(dayStart, place.latitude, place.longitude, Twilight.CIVIL)
    val nautical = SunTimes.forDay(dayStart, place.latitude, place.longitude, Twilight.NAUTICAL)
    val astronomical = SunTimes.forDay(dayStart, place.latitude, place.longitude, Twilight.ASTRONOMICAL)
    val yesterday = SunTimes.forDay(dayStart - 86_400_000L, place.latitude, place.longitude)
    val (noonMillis, noonElevation) = SunTimes.solarNoon(dayStart, place.latitude, place.longitude)
    fun t(millis: Long?): String = millis?.let { ctx.timeLabel(it) } ?: "—"
    fun length(t: SunTimes.Times): Long? = if (t.sunriseMillis != null && t.sunsetMillis != null) t.sunsetMillis!! - t.sunriseMillis!! else null
    return ToolText.build {
      line("luogo", place.label)
      line("giorno", TimeFormats.longDate(date, ctx.locale))
      line("alba / tramonto", "${t(times.sunriseMillis)} / ${t(times.sunsetMillis)}")
      if (times.sunriseMillis == null) line("nota", "sole sotto o sopra l'orizzonte tutto il giorno a questa latitudine")
      line("mezzogiorno solare", "${ctx.timeLabel(noonMillis)} (altezza ${noonElevation.roundToInt()}°)")
      line("crepuscolo civile", "${t(civil.sunriseMillis)} / ${t(civil.sunsetMillis)}")
      line("crepuscolo nautico", "${t(nautical.sunriseMillis)} / ${t(nautical.sunsetMillis)}")
      line("crepuscolo astronomico", "${t(astronomical.sunriseMillis)} / ${t(astronomical.sunsetMillis)}")
      val today = length(times)
      val before = length(yesterday)
      if (today != null) {
        val h = today / 3_600_000
        val m = (today % 3_600_000) / 60_000
        val delta = before?.let { (today - it) / 60_000.0 }
        line("durata del giorno", "${h} h ${m} min" + (delta?.let { " (${if (it >= 0) "+" else "−"}${"%.1f".format(ctx.locale, kotlin.math.abs(it))} min rispetto al giorno prima)" } ?: ""))
      }
      val elevationNow = SolarEphemeris.elevationDegrees(ctx.nowMillis, place.latitude, place.longitude)
      line("altezza del sole adesso", "${elevationNow.roundToInt()}° (${if (elevationNow > 0) "sopra" else "sotto"} l'orizzonte)")
    }
  }
}

/** La luna: fase, illuminazione, sorgere e tramontare, distanza, prossime piena e nuova. */
class MoonTool(private val resolver: PlaceResolver) : AiTool {
  override val name = "luna"
  override val group = ToolGroup.SKY
  override val description = "Fase lunare, illuminazione, eta', sorgere e tramontare, distanza (perigeo/apogeo), prossima luna piena e nuova. Data AAAA-MM-GG, oggi o domani."
  override val parameters = Schema.obj(mapOf("luogo" to Schema.place, "data" to Schema.str("AAAA-MM-GG, oggi, domani")))

  override suspend fun run(args: JsonObject, ctx: ToolContext): String {
    val place = resolver.resolve(args.str("luogo"), ctx.selected) ?: return ToolPhrases.PLACE_NOT_FOUND
    val date = ctx.parseDate(args.str("data")) ?: return "errore: data non capita"
    val dayStart = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    val at = if (date == Instant.ofEpochMilli(ctx.nowMillis).atZone(ctx.zone).toLocalDate()) ctx.nowMillis else dayStart + 12 * 3_600_000L
    val phase = Moon.phase(at)
    val illumination = Moon.illuminatedFraction(at)
    val age = Moon.ageDays(at)
    val riseSet = MoonEphemeris.riseSet(dayStart, place.latitude, place.longitude)
    val distance = MoonEphemeris.distanceKm(at)
    val nextFull = Moon.nextFullMoonMillis(at)
    val nextNew = at + ((Moon.SYNODIC_MONTH_DAYS - age) * 86_400_000L).toLong()
    val u = ctx.units
    return ToolText.build {
      line("luogo", place.label)
      line("giorno", TimeFormats.longDate(date, ctx.locale))
      line("fase", "${ctx.string(phase.labelRes())}, illuminata al ${(illumination * 100).roundToInt()}%, eta' ${"%.1f".format(ctx.locale, age)} giorni")
      line("sorge / tramonta", "${riseSet.riseMillis?.let { ctx.timeLabel(it) } ?: "—"} / ${riseSet.setMillis?.let { ctx.timeLabel(it) } ?: "—"}")
      val where = when {
        distance < (MoonEphemeris.PERIGEE_KM + MoonEphemeris.APOGEE_KM) / 2 - 10_000 -> "vicina al perigeo"
        distance > (MoonEphemeris.PERIGEE_KM + MoonEphemeris.APOGEE_KM) / 2 + 10_000 -> "vicina all'apogeo"
        else -> "a meta' strada fra perigeo e apogeo"
      }
      line("distanza", "${u.distanceGrouped(distance)} ($where)")
      line("prossima luna piena", ctx.dayTimeLabel(nextFull))
      line("prossima luna nuova", ctx.dayTimeLabel(nextNew))
      val altitude = MoonEphemeris.altitudeDegrees(at, place.latitude, place.longitude)
      line("altezza della luna ${if (at == ctx.nowMillis) "adesso" else "a mezzogiorno"}", "${altitude.roundToInt()}°")
    }
  }
}
