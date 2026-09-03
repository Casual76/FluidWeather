package dev.pampa.fluidweather.core.ai.tools

import dev.pampa.fluidweather.core.ai.data.PlaceResolver
import dev.pampa.fluidweather.core.ai.tools.Args.int
import dev.pampa.fluidweather.core.ai.tools.Args.str
import dev.pampa.fluidweather.core.model.PressureTrend
import dev.pampa.fluidweather.core.weather.NowcastSnapshot
import dev.pampa.fluidweather.strings.featureLabelRes
import dev.pampa.fluidweather.strings.labelRes
import dev.pampa.fluidweather.strings.windowLabelRes
import kotlin.math.roundToInt
import kotlinx.serialization.json.JsonObject

/**
 * Il barometro del telefono e' della posizione attuale: per un altro posto il verdetto non
 * esiste, e il tool lo dice invece di inventarlo.
 */
internal suspend fun ToolContext.nowcastFor(place: dev.pampa.fluidweather.core.ai.data.ResolvedPlace?, resolver: PlaceResolver): NowcastSnapshot? {
  val here = place ?: selected ?: return null
  if (!here.isGps && !here.isSelected) return null
  val snapshot = resolver.snapshot(here)
  return sources.nowcast.evaluate(
    snapshot = snapshot,
    nowMillis = nowMillis,
    calibrationProgress = sources.calibrationController.progress.value?.let { it.completedSeconds to it.totalSeconds },
    record = false,
  )
}

internal const val NOWCAST_ELSEWHERE = "il barometro e' quello del telefono: vale solo per la posizione attuale, non per altri posti"

/** Il verdetto delle prossime sei ore con bande, fattori, analoghi e prontezza. */
class NowcastVerdictTool(private val resolver: PlaceResolver) : AiTool {
  override val name = "nowcast_verdetto"
  override val group = ToolGroup.NOWCAST
  override val description = "Il verdetto del barometro del telefono: probabilita' di pioggia a 0-1, 1-3 e 3-6 ore con banda d'incertezza, livello, fattori principali, prontezza."
  override val parameters = Schema.obj(emptyMap())

  override suspend fun run(args: JsonObject, ctx: ToolContext): String {
    val place = ctx.selected
    if (place != null && !place.isGps && !place.isSelected) return NOWCAST_ELSEWHERE
    val nowcast = ctx.nowcastFor(place, resolver) ?: return "nessun verdetto: posizione del telefono sconosciuta"
    val readiness = nowcast.readiness
    val explanation = nowcast.explanation
    return ToolText.build {
      line("luogo", place?.label ?: "posizione attuale")
      if (readiness.calibrationRunning) line("taratura", "in corso (${readiness.calibrationCompletedSeconds}/${readiness.calibrationTotalSeconds} s)")
      else line("taratura", if (readiness.calibrated) "fatta" else "non fatta")
      line("storia barometrica", "${"%.1f".format(ctx.locale, nowcast.historyHours)} h su ${readiness.requiredHours.roundToInt()} h necessarie")
      if (explanation == null) {
        line("verdetto", "non ancora disponibile: ${if (nowcast.samples.isEmpty()) "nessun campione registrato" else "servono ${readiness.requiredHours.roundToInt()} ore di storia pulita"}")
        return@build
      }
      val verdict = explanation.verdict
      line("livello", ctx.string(verdict.level.labelRes()))
      verdict.windows.forEach { window ->
        val label = ctx.string(windowLabelRes(window.window))
        val p = (window.probability * 100).roundToInt()
        val low = (window.probabilityLow * 100).roundToInt()
        val high = (window.probabilityHigh * 100).roundToInt()
        val raw = explanation.rawVerdict.forWindow(window.window)?.probability?.let { (it * 100).roundToInt() }
        val recal = if (window.window in explanation.recalibrated && raw != null && raw != p) " (modello grezzo $raw%, ricalibrato sul posto)" else ""
        val analog = explanation.analogs[window.window]?.takeIf { it.neighbours > 0 }?.let { " · analoghi storici: ${it.rained}/${it.neighbours} volte piovve" } ?: ""
        line("$label", "$p% (banda $low-$high%)$recal$analog")
        val factors = window.topFactors.take(3).joinToString(", ") { factor ->
          val sign = if (factor.contribution >= 0) "+" else "−"
          "${ctx.string(featureLabelRes(factor.name))} ($sign${"%.2f".format(ctx.locale, kotlin.math.abs(factor.contribution))})"
        }
        if (factors.isNotEmpty()) line("  fattori", factors)
      }
      line("nota", "i fattori sono contributi in log-odds: positivo spinge verso la pioggia")
    }
  }
}

/** Lo stato del sensore: grezzo, livello del mare, tendenze, marea, taratura, scarti. */
class BarometerStateTool(private val resolver: PlaceResolver) : AiTool {
  override val name = "barometro_stato"
  override val group = ToolGroup.NOWCAST
  override val description = "Lo stato del barometro: pressione grezza e al livello del mare, tendenza a 1/3/6/12 ore, incertezza, marea atmosferica, taratura, campioni scartati e perche', modalita' di campionamento."
  override val parameters = Schema.obj(emptyMap())

  override suspend fun run(args: JsonObject, ctx: ToolContext): String {
    val place = ctx.selected
    if (place != null && !place.isGps && !place.isSelected) return NOWCAST_ELSEWHERE
    val nowcast = ctx.nowcastFor(place, resolver) ?: return "barometro non disponibile: posizione sconosciuta"
    val u = ctx.units
    val cleaning = nowcast.cleaning
    val sampling = ctx.sources.samplingSettings.current()
    return ToolText.build {
      line("campioni nelle ultime 24 h", nowcast.samples.size)
      nowcast.samples.maxByOrNull { it.timestampMillis }?.let { latest ->
        line("ultima lettura grezza", "${u.pressure(latest.pressureHpa, 1)} alle ${ctx.timeLabel(latest.timestampMillis)}" + (latest.altitudeMeters?.let { " a ${it.roundToInt()} m" } ?: ""))
      }
      val latest = cleaning?.latest
      if (latest != null) {
        line("livello del mare (filtro di Kalman)", "${u.pressure(latest.levelHpa, 1)} ± ${u.pressureSigma(latest.levelSigmaHpa)}")
        line("tendenza (Kalman)", "${u.pressureRate(latest.trendHpaPerHour)} ± ${u.pressureRate(latest.trendSigmaHpaPerHour)}")
      }
      cleaning?.filtered?.let { filtered ->
        listOf(1, 3, 6, 12).forEach { hours ->
          val since = nowcast.nowMillis - hours * 3_600_000L
          val start = filtered.firstOrNull { it.timestampMillis >= since }
          val end = filtered.lastOrNull()
          if (start != null && end != null && end.timestampMillis > start.timestampMillis + 20 * 60_000L) {
            line("variazione ultime $hours h", u.pressureDelta(end.levelHpa - start.levelHpa))
          }
        }
      }
      PressureTrend.hPaPerHour(nowcast.samples.filter { it.timestampMillis >= nowcast.nowMillis - 3 * 3_600_000L })?.let {
        line("tendenza grezza 3 h", u.pressureRate(it) + if (PressureTrend.callsForSurveillance(it)) " (soglia di sorveglianza superata)" else "")
      }
      cleaning?.tide?.let { tide ->
        line("marea atmosferica", "S1 ${u.pressureDeltaValue(tide.s1AmplitudeHpa)} / S2 ${u.pressureDeltaValue(tide.s2AmplitudeHpa)} ${u.pressureSymbol()}, adesso ${u.pressureDelta(tide.tideAtLatestHpa)}")
      }
      nowcast.calibration?.let { calibration ->
        line("taratura", "bias ${u.pressureDelta(calibration.biasHpa)}, fiducia ${(calibration.confidence * 100).roundToInt()}%, ${calibration.sampleCount} campioni, il ${ctx.dayTimeLabel(calibration.calibratedAtMillis)}")
      } ?: line("taratura", "non fatta")
      cleaning?.rejectionCounts()?.takeIf { it.isNotEmpty() }?.let { counts ->
        line("campioni scartati", counts.entries.joinToString(", ") { "${ctx.string(it.key.labelRes())}: ${it.value}" })
      }
      cleaning?.discardedBursts?.takeIf { it.isNotEmpty() }?.let { line("raffiche scartate (rumore)", it.size) }
      line("modalita' di campionamento", "${ctx.string(sampling.mode.labelRes())} (ogni ${sampling.mode.cadenceMinutes} min)" + if (sampling.continuousWhileOpen) ", continuo con l'app aperta" else "")
      ctx.sources.calibrationController.progress.value?.let { line("taratura in corso", "${it.completedSeconds}/${it.totalSeconds} s") }
      ctx.sources.manualBurst.progress.value?.let { line("raffica manuale in corso", "${it.completedSeconds}/${it.totalSeconds} s") }
    }
  }
}

/** La serie oraria del livello del mare pulito, per chi vuole la forma della curva. */
class PressureSeriesTool(private val resolver: PlaceResolver) : AiTool {
  override val name = "barometro_serie"
  override val group = ToolGroup.NOWCAST
  override val description = "La pressione al livello del mare del telefono, media per ora, nelle ultime N ore (max 24)."
  override val parameters = Schema.obj(mapOf("ore" to Schema.int("quante ore indietro (1-24)", 1, 24)))

  override suspend fun run(args: JsonObject, ctx: ToolContext): String {
    val nowcast = ctx.nowcastFor(ctx.selected, resolver) ?: return "barometro non disponibile"
    val hours = (args.int("ore") ?: 12).coerceIn(1, 24)
    val since = ctx.nowMillis - hours * 3_600_000L
    val points = nowcast.cleaning?.filtered?.filter { it.timestampMillis >= since } ?: emptyList()
    if (points.isEmpty()) return "nessun punto pulito nelle ultime $hours ore"
    val u = ctx.units
    val byHour = points.groupBy { (it.timestampMillis - since) / 3_600_000L }.toSortedMap()
    return ToolText.build {
      line("livello del mare, media oraria (${u.pressureSymbol()})")
      byHour.forEach { (index, group) ->
        val at = since + index * 3_600_000L
        line("${ctx.timeLabel(at)}: ${u.pressureValue(group.map { it.levelHpa }.average(), 1)}")
      }
      line("adesso", points.last().let { "${u.pressure(it.levelHpa, 1)} (${u.pressureRate(it.trendHpaPerHour)})" })
    }
  }
}

/** I verdetti registrati nelle ultime ore: come e' cambiata l'opinione del barometro. */
class NowcastHistoryTool : AiTool {
  override val name = "nowcast_storico"
  override val group = ToolGroup.NOWCAST
  override val description = "I verdetti passati del barometro nelle ultime N ore (max 48): ora, probabilita' 0-1/1-3/3-6 h, livello."
  override val parameters = Schema.obj(mapOf("ore" to Schema.int("quante ore indietro (1-48)", 1, 48)))

  override suspend fun run(args: JsonObject, ctx: ToolContext): String {
    val hours = (args.int("ore") ?: 12).coerceIn(1, 48)
    val records = ctx.sources.nowcastHistory.since(ctx.nowMillis - hours * 3_600_000L).sortedBy { it.timestampMillis }
    if (records.isEmpty()) return "nessun verdetto registrato nelle ultime $hours ore"
    val step = (records.size / 12).coerceAtLeast(1)
    return ToolText.build {
      line("ora · p(0-1h) · p(1-3h) · p(3-6h) · livello")
      records.filterIndexed { i, _ -> i % step == 0 || i == records.lastIndex }.forEach { r ->
        line("${ctx.dayTimeLabel(r.timestampMillis)} · ${(r.probability01 * 100).roundToInt()}% · ${(r.probability13 * 100).roundToInt()}% · ${(r.probability36 * 100).roundToInt()}% · ${r.level.lowercase()}")
      }
    }
  }
}
