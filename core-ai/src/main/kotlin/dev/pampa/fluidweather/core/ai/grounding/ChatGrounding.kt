package dev.pampa.fluidweather.core.ai.grounding

import dev.antigravity.fluidengine.ai.orchestrator.*
import dev.pampa.fluidweather.core.model.WeatherSummaryDocument
import dev.pampa.fluidweather.core.weather.WeatherSnapshot
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineStart
import kotlinx.serialization.json.*

/** Request-local capability catalogue: no provider prose ever becomes a displayed fact. */
class ChatGrounding(val italian: Boolean) : AbstractCoroutineContextElement(Key) {
  companion object Key : CoroutineContext.Key<ChatGrounding>
  private data class Entry(val text: String, val expires: Long)
  private val entries = linkedMapOf<String, Entry>()
  private val mandatory = linkedSetOf<String>()
  private val placeNames = linkedSetOf<String>()
  private val actionResults = mutableListOf<String>()
  private val snapshots = linkedMapOf<String, WeatherSnapshot?>()
  private var mixed = false
  private var sequence = 0
  @Synchronized fun invalidate() { mixed = true }
  @Synchronized fun observe(key: String, snapshot: WeatherSnapshot?, name: String? = null) {
    name?.let { placeNames += it }
    if (snapshots.containsKey(key) && snapshots[key] != snapshot) mixed = true
    snapshots[key] = snapshot
  }
  @Synchronized fun observations(): Map<String, WeatherSnapshot?> = snapshots.toMap()
  @Synchronized fun document(document: WeatherSummaryDocument): String {
    if (entries.size >= 48) { mixed = true; return changed() }
    val id = "fact${++sequence}"
    entries[id] = Entry(document.render(), document.validUntilMillis)
    mandatory += id
    return "$id: ${document.render()}"
  }
  @Synchronized fun chips(chips: List<AnswerChip>): List<AnswerChip> = chips.filter {
    dev.pampa.fluidweather.core.ai.orchestrator.WeatherChip.accepts(it) &&
      (it.value == null || it.value in placeNames)
  }
  @Synchronized fun returned(text: String, now: Long, actionResult: Boolean = false): String {
    var body = text
    mandatory.forEach { id -> entries[id]?.let { body = body.replace("$id: ${it.text}", "") } }
    body = body.trim()
    if (body.isEmpty()) return "Available fact ids: ${mandatory.joinToString()}"
    if (actionResult) actionResults += body
    if (entries.size >= 48 || text.length > 24000) return "No additional display facts available."
    val id = "fact${++sequence}"
    entries[id] = Entry(body, now + 60_000L)
    return "$id: $body"
  }
  /** Strict JSON, exact ids only. Parsing does not certify prose: prose is never rendered. */
  @Synchronized fun render(raw: String, now: Long, contextValid: Boolean): String {
    if (!contextValid || mixed || entries.values.any { now >= it.expires }) return (listOf(changed()) + actionResults).joinToString("\n\n")
    val ids = try {
      val obj = Json.parseToJsonElement(raw) as? JsonObject ?: error("object")
      require(obj.keys == setOf("factIds"))
      val list = (obj["factIds"] as JsonArray).map { (it as JsonPrimitive).also { p -> require(p.isString) }.content }
      require(list.isNotEmpty() && list.size <= 8 && list.distinct().size == list.size && list.all { it in entries })
      list
    } catch (_: Exception) { emptyList() }
    val chosen = if (ids.isEmpty()) entries.keys.take(8) else ids
    if (chosen.isEmpty()) return unavailable()
    return (mandatory + chosen).mapNotNull { entries[it]?.text }.distinct().joinToString("\n\n")
  }
  fun changed() = if (italian) "Il contesto o i dati sono cambiati. Ripeti la domanda per una risposta aggiornata." else "The context or data changed. Ask again for an updated answer."
  fun unavailable() = if (italian) "Non ho fatti verificati per questa domanda. Puoi chiedere una previsione, dettagli su aria, cielo o sensore, oppure aprire una pagina dell'app." else "I have no verified facts for this question. You can ask for a forecast, air, sky or sensor details, or open an app page."
}

/** The engine mutates only this isolated conversation. Commit safe text atomically after validation. */
internal fun Conversation.requestCopy(): Conversation = Conversation(id, startedAtMillis).also {
  it.exchanges.addAll(exchanges); it.lastToolRound = emptyList()
  it.lastGroups = lastGroups; it.loadedGroups.addAll(loadedGroups)
  it.loadedCategories.addAll(loadedCategories); it.lastActivityMillis = lastActivityMillis; it.provider = provider
}

internal fun commitGroundedHistory(target: Conversation, request: Conversation, safe: String, chips: List<AnswerChip> = emptyList()) {
  val exchange = request.exchanges.lastOrNull() ?: return
  target.exchanges += exchange.copy(answer = safe, chips = chips)
  // Tool messages may contain prompt injections and expired facts. Re-fetch on follow-up.
  target.lastToolRound = emptyList()
  target.lastGroups = request.lastGroups
  target.loadedGroups.clear(); target.loadedGroups.addAll(request.loadedGroups)
  target.provider = request.provider; target.lastActivityMillis = request.lastActivityMillis
}

/** The same firewall used by AssistantSession, exercised with fake engine flows in JVM tests. */
internal suspend fun <T> bufferedAnswer(
  visible: MutableStateFlow<AssistantState>,
  ask: suspend (MutableStateFlow<AssistantState>) -> T,
): T = coroutineScope {
  val privateState = MutableStateFlow<AssistantState>(AssistantState.Idle)
  val relay = launch(start = CoroutineStart.UNDISPATCHED) {
    privateState.collect { state ->
      when (state) {
        is AssistantState.Classifying, is AssistantState.Working,
        is AssistantState.WaitingRateLimit, is AssistantState.SwitchingProvider -> visible.value = state
        else -> Unit // Answering, failures with partial text, Done and Cancelled stay private.
      }
    }
  }
  try { ask(privateState) } finally { relay.cancel() }
}
