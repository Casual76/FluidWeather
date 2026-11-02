package dev.pampa.fluidweather.core.ai.tools

import dev.pampa.fluidweather.core.model.ObservedCondition
import dev.pampa.fluidweather.core.model.Place
import dev.pampa.fluidweather.strings.R

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
 * Il nome della pagina, per i chip sotto la risposta e per la frase della conferma ("Apro il
 * radar?"). Stava nella UI; ora serve anche alla sessione, che scrive le parole della conferma.
 */
fun OpenTarget.titleRes(): Int = when (this) {
  OpenTarget.RADAR -> R.string.radar_title
  OpenTarget.BENCHMARK -> R.string.bench_title
  OpenTarget.REPORT -> R.string.report_title
  OpenTarget.SETTINGS -> R.string.settings_title
  OpenTarget.AI_SETTINGS -> R.string.ai_title
  OpenTarget.NOWCAST -> R.string.ai_target_nowcast
  OpenTarget.HOURLY -> R.string.ai_target_hourly
  OpenTarget.DAILY -> R.string.ai_target_daily
  OpenTarget.PRECIPITATION -> R.string.ai_target_precipitation
  OpenTarget.PRESSURE -> R.string.ai_target_pressure
  OpenTarget.AIR_QUALITY -> R.string.ai_target_air
  OpenTarget.SUN -> R.string.ai_target_sun
  OpenTarget.MOON -> R.string.ai_target_moon
  OpenTarget.DETAILS -> R.string.ai_target_details
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
