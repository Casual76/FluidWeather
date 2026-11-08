package dev.pampa.fluidweather.core.model

/** A localized, evidence-derived sentence. A model may select its id, never rewrite its facts. */
data class WeatherSummaryFact(val id: String, val text: String, val required: Boolean = false) {
  init { require(id.isNotBlank() && text.isNotBlank()) }
}

/** Bounded context shared by deterministic rendering, the optional AI selector and chat. */
data class WeatherSummaryDocument(
  val contextKey: String,
  val dataRevision: String,
  val language: String,
  val units: UnitPreferences,
  val facts: List<WeatherSummaryFact>,
  val defaultFactIds: List<String>,
  val validUntilMillis: Long,
) {
  init {
    require(contextKey.isNotBlank() && language in setOf("it", "en"))
    require(facts.isNotEmpty() && facts.size <= 64 && facts.map { it.id }.distinct().size == facts.size)
    require(defaultFactIds.isNotEmpty() && defaultFactIds.size <= 8 && defaultFactIds.distinct().size == defaultFactIds.size)
    require(defaultFactIds.all { id -> facts.any { it.id == id } })
  }

  fun accepts(ids: List<String>): Boolean = ids.isNotEmpty() && ids.size <= 8 &&
    ids.distinct().size == ids.size && ids.all { id -> facts.any { it.id == id } }

  /** Mandatory source/uncertainty facts survive any model selection; unknown ids cannot add text. */
  fun render(ids: List<String> = defaultFactIds): String {
    val selected = if (accepts(ids)) ids else defaultFactIds
    return (facts.filter { it.required }.map { it.id } + selected).distinct()
      .mapNotNull { id -> facts.firstOrNull { it.id == id }?.text }.joinToString("\n")
  }
}

enum class WeatherSummaryStatus { READY, NOT_CONFIGURED, FAILED, INVALIDATED }

/** Contains references only; provider prose/errors are never product text. */
data class WeatherSummaryResult(
  val contextKey: String,
  val status: WeatherSummaryStatus,
  val factIds: List<String> = emptyList(),
  val provider: String? = null,
  val fromCache: Boolean = false,
)

/** Implemented by core-ai and injected into UI; home has no dependency on the AI module. */
fun interface WeatherSummaryGateway {
  suspend fun summarize(document: WeatherSummaryDocument): WeatherSummaryResult
  suspend fun clear() {}
}
