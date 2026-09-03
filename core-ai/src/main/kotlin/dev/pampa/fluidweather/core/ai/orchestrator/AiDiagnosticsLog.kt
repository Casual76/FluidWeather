package dev.pampa.fluidweather.core.ai.orchestrator

import dev.pampa.fluidweather.core.ai.net.RateLimitInfo
import dev.pampa.fluidweather.core.ai.provider.ProviderId
import dev.pampa.fluidweather.core.ai.provider.Usage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Una chiamata a un tool, com'e' andata. */
data class ToolTrace(val name: String, val millis: Long, val ok: Boolean, val chars: Int)

/** Una domanda intera, per la pagina Diagnostica: chi ha risposto, con cosa, quanto e' costata. */
data class AiRequestLog(
  val startedAtMillis: Long,
  val question: String,
  val mode: AskMode,
  val provider: ProviderId,
  val model: String,
  val switchedTo: List<ProviderId>,
  val groups: List<String>,
  val classifierUsed: Boolean,
  val tools: List<ToolTrace>,
  val steps: Int,
  val usage: Usage?,
  val durationMillis: Long,
  val outcome: String,
  val error: String?,
  val rateLimit: RateLimitInfo?,
  val waitedSeconds: Int,
)

/** Le ultime dieci domande, in memoria: nessun contenuto sopravvive alla chiusura dell'app. */
class AiDiagnosticsLog(private val capacity: Int = 10) {
  private val state = MutableStateFlow<List<AiRequestLog>>(emptyList())
  val entries: StateFlow<List<AiRequestLog>> = state

  /** Per provider: cio' che gli header hanno detto l'ultima volta sui limiti. */
  private val limits = MutableStateFlow<Map<ProviderId, RateLimitInfo>>(emptyMap())
  val rateLimits: StateFlow<Map<ProviderId, RateLimitInfo>> = limits

  fun add(log: AiRequestLog) {
    state.value = (listOf(log) + state.value).take(capacity)
  }

  fun rateLimit(provider: ProviderId, info: RateLimitInfo) {
    if (info == RateLimitInfo.EMPTY) return
    limits.value = limits.value + (provider to info)
  }

  fun clear() {
    state.value = emptyList()
  }
}
