package dev.pampa.fluidweather.core.ai.tools

import dev.pampa.fluidweather.core.ai.data.PlaceResolver
import dev.pampa.fluidweather.core.ai.tools.Args.str
import dev.pampa.fluidweather.core.model.ObservedCondition
import dev.pampa.fluidweather.core.model.Place
import dev.pampa.fluidweather.strings.labelRes
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonObject

private const val ACTIONS_OFF = "le azioni nell'app sono disattivate nelle impostazioni dell'assistente: l'utente puo' farlo a mano"

private fun outcomeText(outcome: ActionOutcome, done: String): String = when (outcome) {
  ActionOutcome.DONE -> done
  ActionOutcome.REJECTED -> "l'utente ha annullato"
  ActionOutcome.TIMEOUT -> "nessuna conferma dall'utente: non fatto"
  ActionOutcome.UNAVAILABLE -> ACTIONS_OFF
}

/** Cambia la localita' mostrata in home (subito, senza conferma). */
class SelectPlaceTool(private val resolver: PlaceResolver) : AiTool {
  override val name = "seleziona_localita"
  override val group = ToolGroup.APP
  override val description = "Mostra in home un'altra localita' (salvata o nuova): la seleziona subito."
  override val parameters = Schema.obj(mapOf("luogo" to Schema.str("nome della localita', oppure 'qui' per la posizione attuale")), required = listOf("luogo"))

  override suspend fun run(args: JsonObject, ctx: ToolContext): String {
    if (!ctx.actionsEnabled) return ACTIONS_OFF
    val resolved = resolver.resolve(args.str("luogo"), ctx.selected) ?: return ToolPhrases.PLACE_NOT_FOUND
    val place = when {
      resolved.isGps -> Place.gps()
      resolved.placeId != null -> ctx.sources.savedLocations.places.first().firstOrNull { it.id == resolved.placeId } ?: return ToolPhrases.PLACE_NOT_FOUND
      else -> return "\"${resolved.label}\" non e' fra le localita' salvate: usa salva_luogo prima, oppure l'utente la cerca dalla pillola"
    }
    val outcome = ctx.actions.perform(AssistantAction.SelectPlace(place))
    return outcomeText(outcome, "fatto: in home ora c'e' ${resolved.label}")
  }
}

/** Apre una pagina o la pagina nera di un widget (subito). */
class OpenPageTool : AiTool {
  override val name = "apri"
  override val group = ToolGroup.APP
  override val description = "Apre una pagina dell'app: radar, benchmark, segnalazione, impostazioni, impostazioni-ia, oppure la pagina di un widget (nowcast, orario, giornaliero, precipitazioni, pressione, aria, sole, luna, dettagli)."
  override val parameters = Schema.obj(mapOf("pagina" to Schema.str("cosa aprire", OpenTarget.entries.map { it.id })), required = listOf("pagina"))

  override suspend fun run(args: JsonObject, ctx: ToolContext): String {
    if (!ctx.actionsEnabled) return ACTIONS_OFF
    val target = OpenTarget.fromId(args.str("pagina")) ?: return "errore: pagina sconosciuta"
    val outcome = ctx.actions.perform(AssistantAction.Open(target))
    return outcomeText(outcome, "fatto: aperto ${target.id}")
  }
}

/** Avvia la raffica manuale del barometro (con conferma). */
class StartBurstTool : AiTool {
  override val name = "avvia_raffica"
  override val group = ToolGroup.APP
  override val description = "Avvia la raffica manuale del barometro (5 minuti di letture a 1 Hz) per un verdetto piu' preciso. Chiede conferma all'utente."
  override val parameters = Schema.obj(emptyMap())

  override suspend fun run(args: JsonObject, ctx: ToolContext): String {
    if (!ctx.actionsEnabled) return ACTIONS_OFF
    if (ctx.sources.manualBurst.progress.value != null) return "una raffica e' gia' in corso"
    if (ctx.sources.calibrationController.progress.value != null) return "la taratura iniziale e' in corso: la raffica non serve adesso"
    val outcome = ctx.actions.perform(AssistantAction.StartBurst)
    return outcomeText(outcome, "fatto: raffica avviata, durera' 5 minuti")
  }
}

/** Registra un'osservazione del momento (con conferma): la verita' che tara il barometro. */
class RecordObservationTool : AiTool {
  override val name = "registra_osservazione"
  override val group = ToolGroup.APP
  override val description = "Registra cosa l'utente vede adesso dal vivo (sereno, nuvoloso, pioggia, neve...): alimenta la taratura del barometro. Chiede conferma."
  override val parameters = Schema.obj(
    mapOf("condizione" to Schema.str("la condizione osservata", ObservedCondition.entries.map { it.name.lowercase() })),
    required = listOf("condizione"),
  )

  override suspend fun run(args: JsonObject, ctx: ToolContext): String {
    if (!ctx.actionsEnabled) return ACTIONS_OFF
    val raw = args.str("condizione")?.uppercase() ?: return "errore: manca la condizione"
    val condition = ObservedCondition.entries.firstOrNull { it.name == raw } ?: return "errore: condizione sconosciuta"
    val outcome = ctx.actions.perform(AssistantAction.RecordObservation(condition))
    return outcomeText(outcome, "fatto: registrato \"${ctx.string(condition.labelRes())}\"")
  }
}

/** Salva una localita' nuova (con conferma). */
class SavePlaceTool(private val resolver: PlaceResolver) : AiTool {
  override val name = "salva_luogo"
  override val group = ToolGroup.APP
  override val description = "Salva una localita' fra quelle dell'utente, cercandola per nome. Chiede conferma."
  override val parameters = Schema.obj(mapOf("nome" to Schema.str("nome del posto")), required = listOf("nome"))

  override suspend fun run(args: JsonObject, ctx: ToolContext): String {
    if (!ctx.actionsEnabled) return ACTIONS_OFF
    val query = args.str("nome") ?: return "errore: manca il nome"
    val found = resolver.search(query, limit = 1).firstOrNull() ?: return "nessun risultato per \"$query\""
    val already = ctx.sources.savedLocations.places.first().any { it.id == found.id }
    if (already) return "${found.name} e' gia' salvata"
    val outcome = ctx.actions.perform(AssistantAction.SavePlace(found))
    return outcomeText(outcome, "fatto: salvata ${found.name}${found.region?.let { ", $it" } ?: ""}")
  }
}

/** Le ultime osservazioni registrate dall'utente (lettura, sempre disponibile). */
class RecentObservationsTool : AiTool {
  override val name = "osservazioni_recenti"
  override val group = ToolGroup.APP
  override val description = "Le ultime osservazioni dal vivo registrate dall'utente (cosa ha visto e quando)."
  override val parameters = Schema.obj(emptyMap())

  override suspend fun run(args: JsonObject, ctx: ToolContext): String {
    val recent = ctx.sources.observations.recent(10).first()
    if (recent.isEmpty()) return "nessuna osservazione registrata"
    return ToolText.build {
      recent.forEach { line("- ${ctx.dayTimeLabel(it.timestampMillis)}: ${ctx.string(it.condition.labelRes())}" + (it.placeName?.let { p -> " a $p" } ?: "")) }
    }
  }
}
