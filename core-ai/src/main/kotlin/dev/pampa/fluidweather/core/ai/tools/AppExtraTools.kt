package dev.pampa.fluidweather.core.ai.tools

import dev.pampa.fluidweather.core.ai.data.PlaceResolver
import dev.pampa.fluidweather.core.ai.tools.Args.str
import dev.pampa.fluidweather.core.model.DistanceUnit
import dev.pampa.fluidweather.core.model.PrecipitationUnit
import dev.pampa.fluidweather.core.model.PressureUnit
import dev.pampa.fluidweather.core.model.TemperatureUnit
import dev.pampa.fluidweather.core.model.WindUnit
import dev.pampa.fluidweather.strings.TimeFormats
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonObject

private const val ACTIONS_OFF_EXTRA = "le azioni nell'app sono disattivate nelle impostazioni dell'assistente: l'utente puo' farlo a mano"

private fun outcome(outcome: ActionOutcome, done: String): String = when (outcome) {
  ActionOutcome.DONE -> done
  ActionOutcome.REJECTED -> "l'utente ha annullato"
  ActionOutcome.TIMEOUT -> "nessuna conferma dall'utente: non fatto"
  ActionOutcome.UNAVAILABLE -> ACTIONS_OFF_EXTRA
}

/** Le unita' di misura dell'utente: leggerle, e cambiarle quando lo chiede. */
class UnitsTool : AiTool {
  override val name = "unita_imposta"
  override val group = ToolGroup.APP
  override val description = "Legge o cambia le unita' di misura dell'app: temperatura (celsius/fahrenheit), vento (kmh/ms/mph/nodi/beaufort), pressione (hpa/mbar/mmhg/inhg), pioggia (mm/pollici), distanza (km/miglia). Senza argomenti dice quelle in uso."
  override val parameters = Schema.obj(
    mapOf(
      "grandezza" to Schema.str("cosa cambiare", listOf("temperatura", "vento", "pressione", "pioggia", "distanza")),
      "unita" to Schema.str("la nuova unita' (es. celsius, fahrenheit, kmh, mph, hpa, mmhg, mm, pollici, km, miglia); \"automatica\" torna a quella del paese"),
    ),
  )

  override suspend fun run(args: JsonObject, ctx: ToolContext): String {
    val store = ctx.sources.unitsStore
    val kind = args.str("grandezza")
    val unit = args.str("unita")
    if (kind == null || unit == null) {
      val current = store.current()
      return ToolText.build {
        line("temperatura", current.temperature.name.lowercase())
        line("vento", current.wind.name.lowercase())
        line("pressione", current.pressure.name.lowercase())
        line("pioggia", current.precipitation.name.lowercase())
        line("distanza", current.distance.name.lowercase())
        line("nota", "per cambiarne una: unita_imposta(grandezza=\"temperatura\", unita=\"fahrenheit\")")
      }
    }
    if (!ctx.actionsEnabled) return ACTIONS_OFF_EXTRA
    val normalized = unit.lowercase().trim()
    val auto = normalized.startsWith("autom") || normalized == "paese" || normalized == "default"
    val action = when (kind.lowercase().trim()) {
      "temperatura" -> AssistantAction.SetUnit("temperatura", if (auto) null else temperature(normalized) ?: return "errore: unita' di temperatura sconosciuta (celsius, fahrenheit)")
      "vento" -> AssistantAction.SetUnit("vento", if (auto) null else wind(normalized) ?: return "errore: unita' di vento sconosciuta (kmh, ms, mph, nodi, beaufort)")
      "pressione" -> AssistantAction.SetUnit("pressione", if (auto) null else pressure(normalized) ?: return "errore: unita' di pressione sconosciuta (hpa, mbar, mmhg, inhg)")
      "pioggia" -> AssistantAction.SetUnit("pioggia", if (auto) null else precipitation(normalized) ?: return "errore: unita' di pioggia sconosciuta (mm, pollici)")
      "distanza" -> AssistantAction.SetUnit("distanza", if (auto) null else distance(normalized) ?: return "errore: unita' di distanza sconosciuta (km, miglia)")
      else -> return "errore: grandezza sconosciuta (temperatura, vento, pressione, pioggia, distanza)"
    }
    return outcome(ctx.actions.perform(action), "fatto: ${kind.lowercase()} ora in ${if (auto) "unita' del paese" else normalized}")
  }

  private fun temperature(raw: String) = when {
    raw.startsWith("c") -> TemperatureUnit.CELSIUS.name
    raw.startsWith("f") -> TemperatureUnit.FAHRENHEIT.name
    else -> null
  }

  private fun wind(raw: String) = when {
    raw.startsWith("km") -> WindUnit.KMH.name
    raw == "ms" || raw.startsWith("m/s") || raw.startsWith("metri") -> WindUnit.MS.name
    raw.startsWith("mph") || raw.startsWith("migl") -> WindUnit.MPH.name
    raw.startsWith("nod") || raw.startsWith("knot") -> WindUnit.KNOTS.name
    raw.startsWith("beau") -> WindUnit.BEAUFORT.name
    else -> null
  }

  private fun pressure(raw: String) = when {
    raw.startsWith("hpa") -> PressureUnit.HPA.name
    raw.startsWith("mbar") || raw.startsWith("millibar") -> PressureUnit.MBAR.name
    raw.startsWith("mmhg") || raw.startsWith("mm hg") -> PressureUnit.MMHG.name
    raw.startsWith("inhg") || raw.startsWith("in hg") || raw.startsWith("polli") -> PressureUnit.INHG.name
    else -> null
  }

  private fun precipitation(raw: String) = when {
    raw.startsWith("mm") || raw.startsWith("milli") -> PrecipitationUnit.MM.name
    raw.startsWith("poll") || raw.startsWith("inch") -> PrecipitationUnit.INCH.name
    else -> null
  }

  private fun distance(raw: String) = when {
    raw.startsWith("km") || raw.startsWith("chilo") -> DistanceUnit.KM.name
    raw.startsWith("migl") || raw.startsWith("mile") -> DistanceUnit.MILES.name
    else -> null
  }
}

/** Toglie una localita' dai preferiti (con conferma: e' una cosa che l'utente aveva salvato). */
class RemovePlaceTool(private val resolver: PlaceResolver) : AiTool {
  override val name = "localita_rimuovi"
  override val group = ToolGroup.APP
  override val description = "Toglie una localita' dall'elenco di quelle salvate. Chiede conferma."
  override val parameters = Schema.obj(mapOf("luogo" to Schema.str("il nome della localita' salvata da togliere")), required = listOf("luogo"))

  override suspend fun run(args: JsonObject, ctx: ToolContext): String {
    if (!ctx.actionsEnabled) return ACTIONS_OFF_EXTRA
    val query = args.str("luogo") ?: return "errore: dimmi quale localita'"
    val saved = ctx.sources.savedLocations.places.first()
    if (saved.isEmpty()) return "non ci sono localita' salvate"
    val place = saved.firstOrNull { it.name.equals(query, ignoreCase = true) }
      ?: saved.firstOrNull { it.name.contains(query, ignoreCase = true) }
      ?: return "\"$query\" non e' fra le localita' salvate: ci sono ${saved.joinToString(", ") { it.name }}"
    return outcome(ctx.actions.perform(AssistantAction.RemovePlace(place.id, place.name)), "fatto: ${place.name} tolta dalle localita' salvate")
  }
}

/** Cancella un'osservazione registrata per sbaglio: e' un dato che tara il barometro, quindi conta. */
class DeleteObservationTool : AiTool {
  override val name = "osservazione_elimina"
  override val group = ToolGroup.APP
  override val description = "Cancella un'osservazione registrata (quella piu' recente, o quella di un momento preciso). Chiede conferma."
  override val parameters = Schema.obj(mapOf("quale" to Schema.str("\"ultima\" per la piu' recente, oppure l'ora dell'osservazione (es. 14:30)")))

  override suspend fun run(args: JsonObject, ctx: ToolContext): String {
    if (!ctx.actionsEnabled) return ACTIONS_OFF_EXTRA
    val recent = ctx.sources.observations.recent(20).first()
    if (recent.isEmpty()) return "non ci sono osservazioni registrate"
    val which = args.str("quale")?.lowercase()?.trim()
    val target = when {
      which == null || which.startsWith("ultim") -> recent.first()
      else -> recent.firstOrNull { TimeFormats.time(it.timestampMillis, ctx.zone, ctx.locale).contains(which) }
        ?: return "nessuna osservazione a \"$which\": le ultime sono ${recent.take(5).joinToString(", ") { TimeFormats.dayTime(it.timestampMillis, ctx.zone, ctx.locale) }}"
    }
    val label = "${TimeFormats.dayTime(target.timestampMillis, ctx.zone, ctx.locale)} · ${target.condition.name.lowercase()}"
    return outcome(ctx.actions.perform(AssistantAction.DeleteObservation(target.id, label)), "fatto: osservazione del $label cancellata")
  }
}

/** A che punto e' la taratura del barometro, e la avvia se serve. */
class CalibrationStateTool : AiTool {
  override val name = "calibrazione_stato"
  override val group = ToolGroup.NOWCAST
  override val description = "Lo stato della taratura del barometro (fatta o no, quanto e' affidabile, quando): con avvia=si la fa partire, chiedendo conferma. Serve quando il nowcast sembra sbagliato."
  override val parameters = Schema.obj(mapOf("avvia" to Schema.str("si per avviare una nuova taratura")))

  override suspend fun run(args: JsonObject, ctx: ToolContext): String {
    val record = ctx.sources.calibrationStore.current()
    val progress = ctx.sources.calibrationController.progress.value
    val start = args.str("avvia")?.lowercase()?.trim()?.let { it == "si" || it == "sì" || it == "true" } == true
    if (start) {
      if (!ctx.actionsEnabled) return ACTIONS_OFF_EXTRA
      if (progress != null) return "la taratura e' gia' in corso: ${progress.completedSeconds} secondi su ${progress.totalSeconds}"
      return outcome(ctx.actions.perform(AssistantAction.StartCalibration), "fatto: taratura avviata, tieni il telefono fermo per qualche minuto")
    }
    return ToolText.build {
      if (progress != null) {
        line("taratura in corso", "${progress.completedSeconds} secondi su ${progress.totalSeconds}")
      }
      if (record == null) {
        line("taratura", "mai fatta: il barometro usa la pressione grezza del sensore")
        line("nota", "si avvia con calibrazione_stato(avvia=\"si\")")
        return@build
      }
      line("taratura", "fatta")
      line("scarto corretto", String.format(Locale.ROOT, "%+.2f hPa", record.biasHpa))
      line("affidabilita'", "${(record.confidence * 100).roundToInt()}%")
      line("quando", TimeFormats.dayTime(record.calibratedAtMillis, ctx.zone, ctx.locale))
      line("letture usate", record.sampleCount)
      record.altitudeMeters?.let { line("quota stimata", "${it.roundToInt()} m") }
      ctx.sources.calibrationController.lastOutcome.value?.let { line("ultimo esito", it) }
    }
  }
}
