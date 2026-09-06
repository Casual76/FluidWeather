package dev.pampa.fluidweather.core.ai.tools

import dev.pampa.fluidweather.core.model.ObservedCondition
import dev.pampa.fluidweather.core.model.Place

/** Dove l'assistente puo' portare l'utente: le pagine dell'app e le pagine nere dei widget. */
enum class OpenTarget(val id: String) {
  RADAR("radar"),
  BENCHMARK("benchmark"),
  REPORT("segnalazione"),
  SETTINGS("impostazioni"),
  AI_SETTINGS("impostazioni-ia"),
  NOWCAST("nowcast"),
  HOURLY("orario"),
  DAILY("giornaliero"),
  PRECIPITATION("precipitazioni"),
  PRESSURE("pressione"),
  AIR_QUALITY("aria"),
  SUN("sole"),
  MOON("luna"),
  DETAILS("dettagli");

  companion object {
    fun fromId(id: String?): OpenTarget? = entries.firstOrNull { it.id == id?.trim()?.lowercase() }
  }
}

/**
 * Le azioni sicure (toggle nelle impostazioni, spento di default). Navigazione e selezione si
 * eseguono subito; le scritture chiedono conferma nella card.
 */
sealed interface AssistantAction {
  val needsConfirmation: Boolean

  data class Open(val target: OpenTarget) : AssistantAction {
    override val needsConfirmation: Boolean = false
  }

  data class SelectPlace(val place: Place) : AssistantAction {
    override val needsConfirmation: Boolean = false
  }

  data object StartBurst : AssistantAction {
    override val needsConfirmation: Boolean = true
  }

  data class RecordObservation(val condition: ObservedCondition) : AssistantAction {
    override val needsConfirmation: Boolean = true
  }

  data class SavePlace(val place: Place) : AssistantAction {
    override val needsConfirmation: Boolean = true
  }

  /** Un'unita' di misura: [unitName] null = torna a quella del paese. Reversibile, quindi subito. */
  data class SetUnit(val kind: String, val unitName: String?) : AssistantAction {
    override val needsConfirmation: Boolean = false
  }

  data class RemovePlace(val placeId: Long, val label: String) : AssistantAction {
    override val needsConfirmation: Boolean = true
  }

  data class DeleteObservation(val observationId: Long, val label: String) : AssistantAction {
    override val needsConfirmation: Boolean = true
  }

  data object StartCalibration : AssistantAction {
    override val needsConfirmation: Boolean = true
  }
}

enum class ActionOutcome { DONE, REJECTED, TIMEOUT, UNAVAILABLE }

/** Chi esegue davvero le azioni (la UI, tramite l'orchestratore) e ne riporta l'esito al tool. */
interface ActionSink {
  suspend fun perform(action: AssistantAction): ActionOutcome

  object Disabled : ActionSink {
    override suspend fun perform(action: AssistantAction): ActionOutcome = ActionOutcome.UNAVAILABLE
  }
}
