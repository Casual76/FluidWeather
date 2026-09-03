package dev.pampa.fluidweather.core.ai.orchestrator

import dev.pampa.fluidweather.core.ai.net.AiError
import dev.pampa.fluidweather.core.ai.provider.ProviderId
import kotlin.math.ceil

/** Quanto tempo resta a una domanda: 90 secondi in tutto, poi si risponde con quello che si sa. */
class TimeBudget(private val startMillis: Long, private val totalMillis: Long = TOTAL_MILLIS, private val clock: () -> Long) {
  val remainingMillis: Long get() = (startMillis + totalMillis - clock()).coerceAtLeast(0)
  val elapsedMillis: Long get() = clock() - startMillis

  /** Vero quando conviene chiudere: l'ultimo giro deve avere il tempo di scrivere. */
  val forceFinal: Boolean get() = remainingMillis < FINAL_RESERVE_MILLIS

  companion object {
    const val TOTAL_MILLIS: Long = 90_000L
    const val FINAL_RESERVE_MILLIS: Long = 15_000L
  }
}

sealed interface FailoverDecision {
  data class Wait(val seconds: Int) : FailoverDecision
  data class Switch(val to: ProviderId) : FailoverDecision
  data object RetrySame : FailoverDecision
  data class Fail(val kind: FailureKind, val retryAfterSec: Int?) : FailoverDecision
}

/**
 * Cosa fare quando un provider fallisce, in una tabella: 429 -> il prossimo della lista, e se non
 * c'e' nessuno si aspetta il `retry-after` (al massimo due volte, mai piu' del budget); 5xx e
 * rete -> un secondo tentativo, poi il prossimo; chiave sbagliata -> ci si ferma e lo si dice,
 * perche' un ripiego silenzioso nasconderebbe l'errore di configurazione.
 */
class FailoverPolicy(
  private val maxWaits: Int = 2,
  private val maxWaitSec: Int = 60,
) {

  fun decide(
    error: Throwable,
    current: ProviderId,
    remaining: List<ProviderId>,
    waitsDone: Int,
    retriesDone: Int,
    budgetRemainingMillis: Long,
  ): FailoverDecision {
    val next = remaining.firstOrNull { it != current }
    return when (error) {
      is AiError.RateLimited -> when {
        next != null -> FailoverDecision.Switch(next)
        waitsDone < maxWaits -> {
          val wait = ceil(error.retryAfterSec ?: DEFAULT_WAIT_SEC).toInt().coerceAtLeast(1)
          val allowed = minOf(maxWaitSec.toLong(), budgetRemainingMillis / 1000 - 10)
          if (wait <= allowed) FailoverDecision.Wait(wait) else FailoverDecision.Fail(FailureKind.RATE_LIMITED, wait)
        }
        else -> FailoverDecision.Fail(FailureKind.RATE_LIMITED, error.retryAfterSec?.let { ceil(it).toInt() })
      }
      is AiError.Server, is AiError.Timeout, is AiError.Network -> when {
        retriesDone < 1 && budgetRemainingMillis > 20_000 -> FailoverDecision.RetrySame
        next != null -> FailoverDecision.Switch(next)
        else -> FailoverDecision.Fail(if (error is AiError.Timeout) FailureKind.TIMEOUT else if (error is AiError.Network) FailureKind.NETWORK else FailureKind.PROVIDER, null)
      }
      is AiError.Unauthorized -> FailoverDecision.Fail(FailureKind.UNAUTHORIZED, null)
      is AiError.BadRequest -> if (error.message.orEmpty().startsWith("bloccato")) FailoverDecision.Fail(FailureKind.BLOCKED, null) else FailoverDecision.Fail(FailureKind.PROVIDER, null)
      is AiError.Parse -> if (next != null) FailoverDecision.Switch(next) else FailoverDecision.Fail(FailureKind.PROVIDER, null)
      else -> FailoverDecision.Fail(FailureKind.UNKNOWN, null)
    }
  }

  companion object {
    const val DEFAULT_WAIT_SEC = 5.0
  }
}
