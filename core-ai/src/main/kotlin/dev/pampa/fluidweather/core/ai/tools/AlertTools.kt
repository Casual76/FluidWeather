package dev.pampa.fluidweather.core.ai.tools

import dev.pampa.fluidweather.core.ai.data.PlaceResolver
import dev.pampa.fluidweather.core.ai.tools.Args.str
import dev.pampa.fluidweather.core.model.NotificationChannelKind
import dev.pampa.fluidweather.strings.labelRes
import kotlin.math.roundToInt
import kotlinx.serialization.json.JsonObject

/** Le allerte ufficiali (NWS negli USA, Meteoalarm in Europa) per il posto, senza reinterpretarle. */
class OfficialAlertsTool(private val resolver: PlaceResolver) : AiTool {
  override val name = "allerte_ufficiali"
  override val group = ToolGroup.ALERTS
  override val description = "Le allerte ufficiali attive per il posto (protezione civile / servizi nazionali): evento, severita', inizio e fine, area, fonte. Riportarle senza reinterpretarle."
  override val parameters = Schema.obj(mapOf("luogo" to Schema.place))

  override suspend fun run(args: JsonObject, ctx: ToolContext): String {
    val place = resolver.resolve(args.str("luogo"), ctx.selected) ?: return ToolPhrases.PLACE_NOT_FOUND
    val context = runCatching { ctx.sources.placeContext.resolve(place.latitude, place.longitude) }.getOrNull()
    val alerts = runCatching {
      ctx.sources.officialAlerts.forPoint(place.latitude, place.longitude, context?.countryCode, context?.areaCandidates ?: emptyList())
    }.getOrElse { return "allerte non raggiungibili adesso (${it.message ?: "rete"})" }
    val active = alerts.filter { it.isActive(ctx.nowMillis) }
    if (active.isEmpty()) return "nessuna allerta ufficiale attiva per ${place.label}" + (context?.countryCode?.let { " (paese $it)" } ?: "")
    return ToolText.build {
      line("luogo", place.label)
      active.take(6).forEach { alert ->
        line("- ${alert.event}" + (alert.severity?.let { " [$it]" } ?: ""), alert.headline ?: "")
        alert.onsetMillis?.let { line("  da", ctx.dayTimeLabel(it)) }
        alert.expiresMillis?.let { line("  fino a", ctx.dayTimeLabel(it)) }
        alert.areaDescription?.let { line("  area", it.take(120)) }
        alert.description?.takeIf { it.isNotBlank() }?.let { line("  testo", it.replace('\n', ' ').take(240)) }
        alert.instruction?.takeIf { it.isNotBlank() }?.let { line("  istruzioni", it.replace('\n', ' ').take(160)) }
        line("  fonte", alert.source.label + (alert.sender?.let { " · $it" } ?: ""))
      }
      if (active.size > 6) line("(altre ${active.size - 6} allerte)")
    }
  }
}

/** Cosa fanno le notifiche dell'app: canali accesi, ultima allerta del barometro, ultimo ciclo. */
class NotificationsStateTool : AiTool {
  override val name = "notifiche_stato"
  override val group = ToolGroup.ALERTS
  override val description = "Lo stato delle notifiche dell'app: quali canali sono accesi, l'ultima allerta del barometro inviata, l'ultimo inizio/fine pioggia notificato, l'ultimo ciclo in background."
  override val parameters = Schema.obj(emptyMap())

  override suspend fun run(args: JsonObject, ctx: ToolContext): String {
    val settings = ctx.sources.notificationSettings.current()
    val ledger = ctx.sources.notificationLedger.current()
    return ToolText.build {
      line("canali", NotificationChannelKind.entries.joinToString(", ") { "${ctx.string(it.labelRes())}: ${if (settings.enabled(it)) "acceso" else "spento"}" })
      if (settings.dailySummary) line("riepilogo giornaliero", "alle ${"%02d:%02d".format(settings.summaryHour, settings.summaryMinute)}")
      ledger.nowcastAlertAtMillis?.let { at ->
        line("ultima allerta del barometro", "${ctx.dayTimeLabel(at)}" + (ledger.nowcastAlertProbability?.let { " (${(it * 100).roundToInt()}%)" } ?: "") + if (ledger.nowcastAlertActive) ", ancora attiva" else ", rientrata")
      } ?: line("ultima allerta del barometro", "nessuna")
      ledger.precipitationOnsetMillis?.let { line("ultimo inizio pioggia notificato", ctx.dayTimeLabel(it)) }
      ledger.precipitationEndMillis?.let { line("ultima fine pioggia notificata", ctx.dayTimeLabel(it)) }
      if (ledger.officialAlertIds.isNotEmpty()) line("allerte ufficiali gia' notificate", ledger.officialAlertIds.size)
      ledger.lastCycleAtMillis?.let { line("ultimo ciclo in background", "${ctx.dayTimeLabel(it)}" + (ledger.lastCycleNote?.let { n -> " · $n" } ?: "")) }
    }
  }
}
