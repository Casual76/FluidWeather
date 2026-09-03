package dev.pampa.fluidweather.core.ai.orchestrator

import dev.pampa.fluidweather.core.ai.keys.AiSettings
import dev.pampa.fluidweather.core.ai.keys.ThinkingLevel
import dev.pampa.fluidweather.core.ai.net.AiError
import dev.pampa.fluidweather.core.ai.net.RateLimitInfo
import dev.pampa.fluidweather.core.ai.provider.ChatDelta
import dev.pampa.fluidweather.core.ai.provider.ChatRequest
import dev.pampa.fluidweather.core.ai.provider.FinishReason
import dev.pampa.fluidweather.core.ai.provider.Message
import dev.pampa.fluidweather.core.ai.provider.ProviderId
import dev.pampa.fluidweather.core.ai.provider.ReadyProvider
import dev.pampa.fluidweather.core.ai.provider.ReasoningLevel
import dev.pampa.fluidweather.core.ai.provider.ToolCall
import dev.pampa.fluidweather.core.ai.provider.ToolCallAssembler
import dev.pampa.fluidweather.core.ai.provider.ToolChoice
import dev.pampa.fluidweather.core.ai.provider.ToolSpec
import dev.pampa.fluidweather.core.ai.provider.Usage
import dev.pampa.fluidweather.core.ai.tools.OpenTarget
import dev.pampa.fluidweather.core.ai.tools.ToolContext
import dev.pampa.fluidweather.core.ai.tools.ToolGroup
import dev.pampa.fluidweather.core.ai.tools.ToolRegistry
import dev.pampa.fluidweather.core.ai.tools.ToolText
import dev.pampa.fluidweather.core.weather.string
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull

/** Cio' che una domanda produce, oltre allo stato: la risposta e la sua traccia. */
data class AskResult(val answer: String, val chips: List<AnswerChip>, val provider: ProviderId, val usage: Usage?, val toolsUsed: List<String>, val log: AiRequestLog)

/** Cio' che serve all'orchestratore per una domanda, preparato dalla sessione. */
class AskInput(
  val question: String,
  val mode: AskMode,
  val language: String,
  val settings: AiSettings,
  val providers: List<ReadyProvider>,
  val toolContext: ToolContext,
  val systemPrompt: String,
  val conversation: Conversation,
)

/**
 * Il cervello (fase 19): stadio 1 (gruppi) -> giro dei tool in stream, con esecuzione parallela,
 * budget di tempo e di giri, cambio di provider sui 429 e sui 5xx, attesa col conto alla rovescia
 * quando non c'e' nessuno a cui passare. Tutto lo stato osservabile passa da [state].
 */
class AssistantOrchestrator(
  private val registry: ToolRegistry,
  private val classifier: GroupClassifier = GroupClassifier(),
  private val failover: FailoverPolicy = FailoverPolicy(),
  private val diagnostics: AiDiagnosticsLog,
  private val clock: () -> Long = System::currentTimeMillis,
  private val maxRounds: Int = MAX_ROUNDS,
) {

  private class Attempt(var provider: ReadyProvider, val switched: MutableList<ProviderId> = mutableListOf(), var waits: Int = 0, var retries: Int = 0)

  private class TurnOutcome(val text: String?, val calls: List<ToolCall>, val raw: kotlinx.serialization.json.JsonElement?, val usage: Usage?, val rateLimit: RateLimitInfo, val finish: FinishReason)

  suspend fun ask(input: AskInput, state: MutableStateFlow<AssistantState>): AskResult {
    val startedAt = clock()
    val budget = TimeBudget(startedAt, clock = clock)
    val question = input.question
    val conversation = input.conversation
    if (input.providers.isEmpty()) throw AssistantFailure(FailureKind.NO_KEYS, null)
    val attempt = Attempt(input.providers.first())
    val toolTraces = mutableListOf<ToolTrace>()
    var usageTotal: Usage? = null
    var waitedSeconds = 0
    var classifierUsed = false
    var lastRateLimit: RateLimitInfo? = null

    // Stadio 1: i gruppi, solo dove serve (su OpenRouter il catalogo va intero).
    var groups: Set<ToolGroup> = if (attempt.provider.provider.id == ProviderId.OPENROUTER) {
      allGroups(input)
    } else {
      state.value = AssistantState.Classifying(question, attempt.provider.provider.id)
      classifierUsed = true
      classifyWithFailover(input, attempt, budget, state, conversation)
    }
    conversation.lastGroups = groups
    var tools: List<ToolSpec> = specsFor(groups, input)
    var moreToolsUsed = 0

    val messages = mutableListOf<Message>()
    messages += Message.System(input.systemPrompt)
    messages += HistoryCompactor.compact(conversation, budgetTokens = historyBudget(attempt.provider.provider.id))
    messages += Message.User(question)
    val toolRound = mutableListOf<Message>()
    var answer: String? = null
    var answerProvider = attempt.provider.provider.id
    var steps = 0

    for (step in 1..maxRounds) {
      steps = step
      val forceFinal = step == maxRounds || budget.forceFinal
      val request = ChatRequest(
        model = attempt.provider.chatModel,
        messages = if (forceFinal) messages + Message.System(PromptBuilder.forceFinal(input.language)) else messages,
        tools = if (forceFinal) emptyList() else tools,
        toolChoice = if (forceFinal) ToolChoice.None else ToolChoice.Auto,
        reasoning = reasoningFor(input.settings.thinking, final = forceFinal),
        maxOutputTokens = MAX_OUTPUT_TOKENS,
        temperature = 0.3,
      )
      state.value = AssistantState.Working(question, step, maxRounds, "thinking", 0, attempt.provider.provider.id)
      val outcome = runTurnWithFailover(input, attempt, budget, state, request, messages) { s ->
        waitedSeconds += s
      }
      outcome.usage?.let { usageTotal = usageTotal?.plus(it) ?: it }
      lastRateLimit = outcome.rateLimit
      diagnostics.rateLimit(attempt.provider.provider.id, outcome.rateLimit)
      answerProvider = attempt.provider.provider.id
      // Al giro forzato non ci sono tool: una chiamata che arriva lo stesso si ignora e vale il testo.
      if (outcome.calls.isEmpty() || forceFinal) {
        answer = outcome.text?.trim().orEmpty()
        if (answer.isBlank() && outcome.finish == FinishReason.BLOCKED) throw AssistantFailure(FailureKind.BLOCKED, null)
        if (answer.isBlank() && forceFinal) throw AssistantFailure(FailureKind.TIMEOUT, null)
        break
      }
      val assistant = Message.Assistant(outcome.text, outcome.calls, raw = outcome.raw, rawProvider = attempt.provider.provider.id)
      messages += assistant
      toolRound += assistant
      state.value = AssistantState.Working(question, step, maxRounds, statusKeyFor(outcome.calls), outcome.calls.size - 1, attempt.provider.provider.id)
      val results = executeParallel(outcome.calls, input.toolContext, budget, toolTraces, state, question, attempt.provider.provider.id)
      results.forEach { (call, content) ->
        val message = Message.ToolResult(call.id, call.name, content)
        messages += message
        toolRound += message
      }
      // altri_tool: i gruppi chiesti entrano nel giro dopo, al massimo due volte per domanda.
      outcome.calls.filter { it.name == ToolRegistry.MORE_TOOLS }.forEach { call ->
        if (moreToolsUsed < MAX_MORE_TOOLS) {
          ToolGroup.fromId(call.arguments["gruppo"].string())?.let { group ->
            if (group !in groups && (group != ToolGroup.APP || input.toolContext.actionsEnabled)) {
              groups = groups + group
              tools = specsFor(groups, input)
              moreToolsUsed++
            }
          }
        }
      }
    }
    val finalAnswer = answer ?: throw AssistantFailure(FailureKind.TIMEOUT, null)
    val (cleanText, chips) = ChipParser.extract(finalAnswer)
    conversation.exchanges += Exchange(question, cleanText, chips, answerProvider, clock())
    conversation.lastToolRound = toolRound.toList()
    conversation.lastActivityMillis = clock()
    conversation.provider = answerProvider
    val log = AiRequestLog(
      startedAtMillis = startedAt,
      question = question,
      mode = input.mode,
      provider = input.providers.first().provider.id,
      model = attempt.provider.chatModel,
      switchedTo = attempt.switched.toList(),
      groups = groups.map { it.id },
      classifierUsed = classifierUsed,
      tools = toolTraces.toList(),
      steps = steps,
      usage = usageTotal,
      durationMillis = clock() - startedAt,
      outcome = "ok",
      error = null,
      rateLimit = lastRateLimit,
      waitedSeconds = waitedSeconds,
    )
    diagnostics.add(log)
    return AskResult(cleanText, chips, answerProvider, usageTotal, toolTraces.map { it.name }.distinct(), log)
  }

  private fun allGroups(input: AskInput): Set<ToolGroup> =
    ToolGroup.entries.filter { it != ToolGroup.APP || input.toolContext.actionsEnabled }.toSet()

  private fun specsFor(groups: Set<ToolGroup>, input: AskInput): List<ToolSpec> {
    val visible = groups.filter { it != ToolGroup.APP || input.toolContext.actionsEnabled }.toSet()
    val specs = registry.specsFor(visible)
    val missing = ToolGroup.entries.any { it !in visible && (it != ToolGroup.APP || input.toolContext.actionsEnabled) }
    return if (missing) specs + registry.moreTools else specs
  }

  private fun historyBudget(provider: ProviderId): Int = if (provider == ProviderId.GROQ) HISTORY_BUDGET_GROQ else HISTORY_BUDGET_OTHER

  private fun reasoningFor(level: ThinkingLevel, final: Boolean): ReasoningLevel = when (level) {
    ThinkingLevel.LOW -> if (final) ReasoningLevel.NONE else ReasoningLevel.LOW
    ThinkingLevel.MEDIUM -> if (final) ReasoningLevel.NONE else ReasoningLevel.MEDIUM
    ThinkingLevel.HIGH -> if (final) ReasoningLevel.LOW else ReasoningLevel.HIGH
  }

  private fun statusKeyFor(calls: List<ToolCall>): String {
    val first = calls.firstOrNull() ?: return "thinking"
    if (first.name == ToolRegistry.MORE_TOOLS) return "more_tools"
    return registry.find(first.name)?.group?.statusKey ?: "thinking"
  }

  private suspend fun classifyWithFailover(
    input: AskInput,
    attempt: Attempt,
    budget: TimeBudget,
    state: MutableStateFlow<AssistantState>,
    conversation: Conversation,
  ): Set<ToolGroup> {
    while (true) {
      val ready = attempt.provider
      val model = ready.classifierModel ?: ready.chatModel
      try {
        return classifier.classify(
          provider = ready.provider,
          model = model,
          question = input.question,
          previousQuestion = conversation.exchanges.lastOrNull()?.question,
          previousGroups = conversation.lastGroups,
          language = input.language,
          actionsEnabled = input.toolContext.actionsEnabled,
        )
      } catch (e: CancellationException) {
        throw e
      } catch (e: AiError.Unauthorized) {
        throw AssistantFailure(FailureKind.UNAUTHORIZED, e)
      } catch (e: Throwable) {
        // Lo stadio 1 non fa fallire la domanda: si prova il prossimo provider, poi il ripiego.
        val decision = failover.decide(e, ready.provider.id, remaining(input, attempt), attempt.waits, attempt.retries, budget.remainingMillis)
        when (decision) {
          is FailoverDecision.Switch -> {
            switchTo(input, attempt, decision.to, state)
            if (attempt.provider.provider.id == ProviderId.OPENROUTER) return allGroups(input)
          }
          is FailoverDecision.RetrySame -> attempt.retries++
          else -> return classifier.fallback(conversation.lastGroups)
        }
      }
    }
  }

  private fun remaining(input: AskInput, attempt: Attempt): List<ProviderId> {
    val order = input.providers.map { it.provider.id }
    val index = order.indexOf(attempt.provider.provider.id)
    return if (index < 0) order else order.drop(index + 1)
  }

  private fun switchTo(input: AskInput, attempt: Attempt, to: ProviderId, state: MutableStateFlow<AssistantState>) {
    val next = input.providers.first { it.provider.id == to }
    state.value = AssistantState.SwitchingProvider(input.question, attempt.provider.provider.id, to)
    attempt.switched += to
    attempt.provider = next
    attempt.retries = 0
  }

  private suspend fun runTurnWithFailover(
    input: AskInput,
    attempt: Attempt,
    budget: TimeBudget,
    state: MutableStateFlow<AssistantState>,
    request: ChatRequest,
    messages: List<Message>,
    onWaited: (Int) -> Unit,
  ): TurnOutcome {
    var current = request
    while (true) {
      try {
        return runTurn(attempt.provider, current, input, state)
      } catch (e: CancellationException) {
        throw e
      } catch (e: AssistantFailure) {
        throw e
      } catch (e: Throwable) {
        val decision = failover.decide(e, attempt.provider.provider.id, remaining(input, attempt), attempt.waits, attempt.retries, budget.remainingMillis)
        when (decision) {
          is FailoverDecision.Wait -> {
            attempt.waits++
            onWaited(decision.seconds)
            var left = decision.seconds
            while (left > 0) {
              state.value = AssistantState.WaitingRateLimit(input.question, attempt.provider.provider.id, left)
              delay(1_000)
              left--
            }
          }
          is FailoverDecision.Switch -> {
            switchTo(input, attempt, decision.to, state)
            // La stessa conversazione, riscritta per il nuovo provider: le parti grezze dell'altro non servono piu'.
            val neutral = messages.map { if (it is Message.Assistant) it.copy(raw = null, rawProvider = null) else it }
            val onOpenRouter = attempt.provider.provider.id == ProviderId.OPENROUTER
            current = current.copy(
              model = attempt.provider.chatModel,
              messages = if (current.messages.size > messages.size) neutral + current.messages.drop(messages.size) else neutral,
              tools = if (onOpenRouter && current.tools.isNotEmpty()) specsFor(allGroups(input), input) else current.tools,
            )
          }
          FailoverDecision.RetrySame -> {
            attempt.retries++
            delay(1_500)
          }
          is FailoverDecision.Fail -> throw AssistantFailure(decision.kind, e as? AiError, decision.retryAfterSec)
        }
      }
    }
  }

  /** Un giro in stream: testo (mostrato dopo 24 caratteri o 150 ms senza tool call), tool call, fine. */
  private suspend fun runTurn(ready: ReadyProvider, request: ChatRequest, input: AskInput, state: MutableStateFlow<AssistantState>): TurnOutcome {
    val assembler = ToolCallAssembler()
    val text = StringBuilder()
    var firstTextAt = 0L
    var published = false
    var raw: kotlinx.serialization.json.JsonElement? = null
    var finish: ChatDelta.Finish? = null
    ready.provider.stream(request).collect { delta ->
      when (delta) {
        is ChatDelta.Text -> {
          if (text.isEmpty()) firstTextAt = clock()
          text.append(delta.text)
          if (assembler.isEmpty && (text.length >= PUBLISH_MIN_CHARS || clock() - firstTextAt >= PUBLISH_MIN_MILLIS)) {
            published = true
            state.value = AssistantState.Answering(input.question, text.toString(), ready.provider.id)
          }
        }
        is ChatDelta.ToolCallPart -> {
          assembler.add(delta)
          if (published) {
            // Il testo era un preambolo ("controllo il radar..."): torna il lavoro, non la risposta.
            published = false
            state.value = AssistantState.Working(input.question, 0, maxRounds, "thinking", 0, ready.provider.id)
          }
        }
        is ChatDelta.Raw -> raw = delta.raw
        is ChatDelta.Finish -> finish = delta
      }
    }
    val calls = assembler.build()
    if (calls.isEmpty() && text.isNotEmpty() && !published) {
      state.value = AssistantState.Answering(input.question, text.toString(), ready.provider.id)
    }
    return TurnOutcome(
      text = text.toString().takeIf { it.isNotBlank() },
      calls = calls,
      raw = raw,
      usage = finish?.usage,
      rateLimit = finish?.rateLimit ?: RateLimitInfo.EMPTY,
      finish = finish?.reason ?: if (calls.isNotEmpty()) FinishReason.TOOL_CALLS else FinishReason.STOP,
    )
  }

  private suspend fun executeParallel(
    calls: List<ToolCall>,
    ctx: ToolContext,
    budget: TimeBudget,
    traces: MutableList<ToolTrace>,
    state: MutableStateFlow<AssistantState>,
    question: String,
    provider: ProviderId,
  ): List<Pair<ToolCall, String>> = coroutineScope {
    val semaphore = Semaphore(PARALLEL_TOOLS)
    val outcomes = calls.map { call ->
      async(Dispatchers.IO) {
        semaphore.withPermit {
          val started = clock()
          val content = if (call.name == ToolRegistry.MORE_TOOLS) {
            "ok: gli strumenti del gruppo ${call.arguments["gruppo"].string()} saranno disponibili dal prossimo passo"
          } else {
            val tool = registry.find(call.name)
            if (tool == null) {
              "errore: strumento sconosciuto \"${call.name}\""
            } else {
              val timeout = minOf(TOOL_TIMEOUT_MILLIS, (budget.remainingMillis - 5_000).coerceAtLeast(3_000))
              withTimeoutOrNull(timeout) {
                runCatching { tool.run(call.arguments, ctx) }.getOrElse { e ->
                  if (e is CancellationException) throw e
                  "errore: ${e.message ?: e::class.simpleName}"
                }
              } ?: "errore: lo strumento non ha risposto in tempo"
            }
          }
          val limited = ToolText.limit(content)
          Triple(call, limited, ToolTrace(call.name, clock() - started, !limited.startsWith("errore"), limited.length))
        }
      }
    }.awaitAll()
    // Le tracce nell'ordine delle chiamate, non in quello di arrivo: la diagnostica legge meglio.
    outcomes.forEach { traces += it.third }
    outcomes.map { it.first to it.second }
  }

  companion object {
    const val MAX_ROUNDS = 6
    const val MAX_MORE_TOOLS = 2
    const val MAX_OUTPUT_TOKENS = 1_200
    const val PARALLEL_TOOLS = 4
    const val TOOL_TIMEOUT_MILLIS = 20_000L
    const val PUBLISH_MIN_CHARS = 24
    const val PUBLISH_MIN_MILLIS = 150L
    const val HISTORY_BUDGET_GROQ = 5_000
    const val HISTORY_BUDGET_OTHER = 60_000
  }
}

/** La fine di una domanda che non ha risposta: la sessione la traduce in [AssistantState.Failed]. */
class AssistantFailure(val kind: FailureKind, val error: AiError?, val retryAfterSec: Int? = null) : Exception(error?.message ?: kind.name)

/** I marcatori `[[...]]` in fondo alla risposta diventano chip; il testo mostrato non li ha. */
object ChipParser {
  private val marker = Regex("\\[\\[([^\\]]+)]]")

  fun extract(text: String): Pair<String, List<AnswerChip>> {
    val chips = mutableListOf<AnswerChip>()
    marker.findAll(text).forEach { match ->
      val body = match.groupValues[1].trim()
      val chip = when {
        body.lowercase().startsWith("luogo:") -> body.substringAfter(':').trim().takeIf { it.isNotEmpty() }?.let { AnswerChip.Place(it) }
        body.lowercase().startsWith("place:") -> body.substringAfter(':').trim().takeIf { it.isNotEmpty() }?.let { AnswerChip.Place(it) }
        else -> OpenTarget.fromId(body)?.let { AnswerChip.Open(it) }
      }
      if (chip != null && chip !in chips && chips.size < 3) chips += chip
    }
    val clean = marker.replace(text, "").replace(Regex("[ \\t]+\\n"), "\n").replace(Regex("\\n{3,}"), "\n\n").trim()
    return clean to chips
  }
}
