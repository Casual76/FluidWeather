package dev.pampa.fluidweather.core.ai.orchestrator

import dev.pampa.fluidweather.core.ai.net.AiError
import dev.pampa.fluidweather.core.ai.provider.ProviderId
import dev.pampa.fluidweather.core.ai.provider.Usage
import dev.pampa.fluidweather.core.ai.tools.AssistantAction
import dev.pampa.fluidweather.core.ai.tools.OpenTarget

/** Come e' arrivata la domanda: decide se la risposta va anche letta ad alta voce. */
enum class AskMode { VOICE, TEXT }

/** Un chip sotto la risposta: il modello scrive `[[radar]]` o `[[luogo:Bologna]]`, la card lo rende toccabile. */
sealed interface AnswerChip {
  data class Open(val target: OpenTarget) : AnswerChip
  data class Place(val name: String) : AnswerChip
}

/** Perche' e' andata male, in termini che la UI sa tradurre in una frase. */
enum class FailureKind { NO_KEYS, UNAUTHORIZED, RATE_LIMITED, NETWORK, TIMEOUT, BLOCKED, PROVIDER, TRANSCRIPTION, NO_LOCATION, UNKNOWN }

/**
 * Lo stato dell'assistente, uno solo per volta: la UI lo osserva e disegna aureola, card e
 * stati leggibili. Le stringhe non stanno qui (le chiavi si', le parole le mette la UI).
 */
sealed interface AssistantState {
  data object Idle : AssistantState

  data class Listening(val level: Float, val speaking: Boolean, val elapsedMillis: Long) : AssistantState

  data object Transcribing : AssistantState

  /** Nessun parlato: la card lo dice e si chiude da sola. */
  data object HeardNothing : AssistantState

  data class Classifying(val question: String, val provider: ProviderId) : AssistantState

  /**
   * Il giro dei tool. [statusKey] e' la chiave del testo di stato: lo `statusKey` di un gruppo
   * ("nowcast", "radar"...), "thinking", "more_tools"; [statusExtra] quante chiamate in parallelo.
   */
  data class Working(val question: String, val step: Int, val maxSteps: Int, val statusKey: String, val statusExtra: Int, val provider: ProviderId) : AssistantState

  data class WaitingRateLimit(val question: String, val provider: ProviderId, val secondsLeft: Int) : AssistantState

  data class SwitchingProvider(val question: String, val from: ProviderId, val to: ProviderId) : AssistantState

  data class Answering(val question: String, val partial: String, val provider: ProviderId) : AssistantState

  /** Un'azione con conferma: la card mostra i chip Conferma/Annulla finche' l'utente non sceglie. */
  data class AwaitingConfirmation(val question: String, val action: AssistantAction, val provider: ProviderId) : AssistantState

  data class Done(
    val question: String,
    val answer: String,
    val chips: List<AnswerChip>,
    val provider: ProviderId,
    val mode: AskMode,
    val usage: Usage?,
    val toolsUsed: List<String>,
    val durationMillis: Long,
  ) : AssistantState

  data class Failed(val question: String?, val kind: FailureKind, val error: AiError?, val retryAfterSec: Int?, val partial: String?) : AssistantState

  data class Cancelled(val question: String?, val partial: String?) : AssistantState

  val isBusy: Boolean
    get() = when (this) {
      is Listening, Transcribing, is Classifying, is Working, is WaitingRateLimit, is SwitchingProvider, is Answering, is AwaitingConfirmation -> true
      else -> false
    }
}
