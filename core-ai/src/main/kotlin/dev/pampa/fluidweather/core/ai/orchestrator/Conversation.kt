package dev.pampa.fluidweather.core.ai.orchestrator

import dev.pampa.fluidweather.core.ai.provider.Message
import dev.pampa.fluidweather.core.ai.provider.ProviderId
import dev.pampa.fluidweather.core.ai.tools.ToolGroup

/** Uno scambio finito, per la card: la domanda, la risposta, i chip. */
data class Exchange(val question: String, val answer: String, val chips: List<AnswerChip>, val provider: ProviderId, val atMillis: Long)

/**
 * La conversazione in memoria: gli scambi conclusi, il traffico tool dell'ultima domanda (per i
 * "e domani?"), i gruppi gia' aperti, il provider su cui si e' finiti. Scade dopo dieci minuti di
 * silenzio o alla chiusura dell'app; niente va su disco.
 */
class Conversation(val id: Long, val startedAtMillis: Long) {
  val exchanges = mutableListOf<Exchange>()

  /** `Assistant(toolCalls)` + `ToolResult` dell'ultima domanda, da riproporre una volta sola. */
  var lastToolRound: List<Message> = emptyList()
  var lastGroups: Set<ToolGroup> = emptySet()
  var lastActivityMillis: Long = startedAtMillis
  var provider: ProviderId? = null

  fun isExpired(nowMillis: Long, ttlMillis: Long = TTL_MILLIS): Boolean = nowMillis - lastActivityMillis > ttlMillis

  companion object {
    const val TTL_MILLIS: Long = 10 * 60_000L
  }
}

/**
 * Cosa del passato si manda al modello: le coppie domanda/risposta (solo testo, le vecchie
 * accorciate), il traffico tool dell'ultima domanda al suo posto, e una guardia sui token
 * (prima cade il traffico tool, poi le coppie piu' vecchie, mai l'ultima). Un `Assistant` con
 * tool call cade sempre insieme ai suoi risultati: i provider rispondono 400 a un risultato orfano.
 */
object HistoryCompactor {

  const val MAX_PAIRS = 8
  const val OLD_ANSWER_CHARS = 600
  const val CHARS_PER_TOKEN = 3.5

  fun compact(conversation: Conversation, budgetTokens: Int, includeToolRound: Boolean = true): List<Message> {
    val exchanges = conversation.exchanges.takeLast(MAX_PAIRS)
    if (exchanges.isEmpty()) return emptyList()
    fun build(pairs: List<Exchange>, withToolRound: Boolean): List<Message> {
      val out = mutableListOf<Message>()
      pairs.forEachIndexed { index, exchange ->
        val last = index == pairs.lastIndex
        out += Message.User(exchange.question)
        if (last && withToolRound) out += conversation.lastToolRound
        out += Message.Assistant(text = if (last) exchange.answer else exchange.answer.take(OLD_ANSWER_CHARS))
      }
      return out
    }
    var pairs = exchanges
    var withToolRound = includeToolRound && conversation.lastToolRound.isNotEmpty()
    while (true) {
      val messages = build(pairs, withToolRound)
      if (estimateTokens(messages) <= budgetTokens || (pairs.size == 1 && !withToolRound)) return messages
      if (withToolRound) withToolRound = false else pairs = pairs.drop(1)
    }
  }

  fun estimateTokens(messages: List<Message>): Int = (messages.sumOf { chars(it) } / CHARS_PER_TOKEN).toInt() + messages.size * 4

  fun estimateTokens(text: String): Int = (text.length / CHARS_PER_TOKEN).toInt()

  private fun chars(message: Message): Int = when (message) {
    is Message.System -> message.text.length
    is Message.User -> message.text.length
    is Message.Assistant -> (message.text?.length ?: 0) + message.toolCalls.sumOf { it.name.length + it.arguments.toString().length + 12 }
    is Message.ToolResult -> message.content.length + message.name.length + 12
  }
}
