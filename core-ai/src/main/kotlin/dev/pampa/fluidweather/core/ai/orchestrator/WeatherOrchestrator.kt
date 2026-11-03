package dev.pampa.fluidweather.core.ai.orchestrator

import dev.antigravity.fluidengine.ai.orchestrator.AiConfirmationGate
import dev.antigravity.fluidengine.ai.orchestrator.AiDiagnosticsLog
import dev.antigravity.fluidengine.ai.orchestrator.AiOrchestrator
import dev.antigravity.fluidengine.ai.orchestrator.AiOrchestratorConfig
import dev.antigravity.fluidengine.ai.orchestrator.AiRouter
import dev.antigravity.fluidengine.ai.orchestrator.AnswerChip
import dev.antigravity.fluidengine.ai.tools.ToolRegistry
import dev.pampa.fluidweather.core.ai.tools.OpenTarget
import dev.pampa.fluidweather.core.ai.tools.ToolContext
import dev.pampa.fluidweather.core.ai.tools.ToolGroup

/**
 * L'orchestratore di `engine-ai` tarato su FluidWeather: gli stessi numeri del vecchio orchestratore
 * di casa, cosi' chi usa l'assistente non si accorge del cambio. Una card a schermo, quindi un
 * minuto e mezzo per domanda; sei giri; risposte corte.
 *
 * Il livello profondo dell'engine (un terzo modello per i documenti e i testi lunghi) qui resta
 * spento: FluidWeather non ha allegati, e un modello diverso che risponde alle domande lunghe
 * sarebbe un cambio di comportamento che nessuno ha deciso. Lo spegne `deepTierEnabled = false`:
 * l'engine non offre `modello_avanzato`, non sale di livello e risponde sempre la chat.
 *
 * I tempi: venti secondi di lavoro per tool ([ToolGroup.APP] dichiara che aspetta una conferma, e
 * l'orchestratore ci somma la finestra del cancello, un minuto per conferma). Prima il tetto era
 * uno solo e una conferma che l'utente impiegava piu' di venti secondi a dare scadeva comunque.
 */
object WeatherOrchestrator {

  /** I numeri del vecchio `AssistantOrchestrator` (MAX_ROUNDS, MAX_OUTPUT_TOKENS, TimeBudget...). */
  val config = AiOrchestratorConfig(
    maxRounds = 6,
    maxMoreTools = 2,
    maxOutputTokens = 1_200,
    parallelTools = 4,
    toolTimeoutMillis = 20_000L,
    totalBudgetMillis = 90_000L,
    finalReserveMillis = 15_000L,
    historyBudgetGroq = 5_000,
    historyBudgetOther = 60_000,
    publishMinChars = 24,
    publishMinMillis = 150L,
    temperature = 0.3,
    confirmationTimeoutMillis = 60_000L,
    deepTierEnabled = false,
  )

  /** Su cosa ripiega lo stadio 1 quando non capisce: le tre cose che si chiedono di piu'. */
  val fallbackGroups: List<ToolGroup> = listOf(ToolGroup.HOURLY, ToolGroup.NOWCAST, ToolGroup.PRECIP)

  fun router(): AiRouter = AiRouter(
    groups = ToolGroup.entries,
    actionGroup = ToolGroup.APP,
    // Il prompt dice "l'assistente di un'app (...)": qui va solo cosa fa l'app. I gruppi hanno le loro righe.
    domainHint = "meteo: il barometro del telefono, le previsioni fuse di piu' servizi, il radar, aria, sole e luna",
    defaultGroups = fallbackGroups,
  )

  /** Il cancello va dato anche qui: l'orchestratore ne legge la finestra per il tetto dei tool che aspettano. */
  fun create(
    registry: ToolRegistry<ToolContext>,
    diagnostics: AiDiagnosticsLog,
    gate: AiConfirmationGate? = null,
  ): AiOrchestrator<ToolContext> =
    AiOrchestrator(registry = registry, router = router(), diagnostics = diagnostics, config = config, gate = gate)
}

/**
 * Un chip sotto la risposta, nel vocabolario dell'app: una pagina da aprire o un posto da scegliere.
 * L'engine porta solo `[[id]]` e `[[id:valore]]` ([AnswerChip]); questa e' la traduzione.
 */
sealed interface WeatherChip {
  data class Open(val target: OpenTarget) : WeatherChip
  data class Place(val name: String) : WeatherChip

  companion object {
    /** Gli id con cui il modello indica un posto: `[[luogo:Bologna]]`, o in inglese `[[place:Bologna]]`. */
    private val PLACE_IDS = setOf("luogo", "place")

    /** Il filtro per `AskInput.chipFilter`: le pagine senza valore, i posti con un nome. */
    fun accepts(chip: AnswerChip): Boolean = of(chip) != null

    fun of(chip: AnswerChip): WeatherChip? = when {
      chip.id in PLACE_IDS -> chip.value?.takeIf { it.isNotBlank() }?.let { Place(it) }
      chip.value == null -> OpenTarget.fromId(chip.id)?.let { Open(it) }
      else -> null
    }

    /** I chip di una risposta, tradotti e senza doppioni (un posto scritto `luogo:` e `place:` e' uno). */
    fun of(chips: List<AnswerChip>): List<WeatherChip> = chips.mapNotNull { of(it) }.distinct()
  }
}
