package dev.pampa.fluidweather.core.ai.tools

import android.content.res.Resources
import dev.pampa.fluidweather.core.ai.data.AiDataSources
import dev.pampa.fluidweather.core.ai.data.ResolvedPlace
import dev.pampa.fluidweather.core.ai.provider.ToolSpec
import dev.pampa.fluidweather.strings.UnitFormatter
import java.time.ZoneId
import java.util.Locale
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put

/**
 * I gruppi del catalogo a due stadi: lo stadio 1 sceglie i gruppi, lo stadio 2 riceve i tool di
 * quei gruppi. Gli id sono le parole che il modello legge nello schema, quindi in italiano come
 * il resto dei tool; il testo di stato ("Leggo il nowcast") lo aggiunge la UI dalla chiave.
 */
enum class ToolGroup(val id: String, val statusKey: String, val hint: String) {
  PLACES("luogo", "places", "cercare o elencare localita', salvate o nuove"),
  NOWCAST("nowcast", "nowcast", "il barometro del telefono: probabilita' di pioggia nelle prossime 6 ore, pressione, tendenza, fattori"),
  HOURLY("orario", "hourly", "il tempo di adesso e ora per ora (temperatura, vento, umidita', UV, nuvole)"),
  DAILY("giornaliero", "daily", "i prossimi giorni: minime, massime, pioggia, vento, alba e tramonto per giorno"),
  PRECIP("precipitazioni", "precip", "pioggia o neve nelle prossime ore, quando inizia e finisce, il radar (intensita', celle in arrivo)"),
  AIR("aria", "air", "qualita' dell'aria, inquinanti, pollini"),
  SKY("cielo", "sky", "sole (alba, tramonto, crepuscoli, durata del giorno) e luna (fase, sorgere, distanza)"),
  PROVIDERS("provider", "providers", "i servizi meteo usati, il confronto fra le loro previsioni, la classifica di chi ci prende di piu' qui"),
  ALERTS("allerte", "alerts", "allerte ufficiali della protezione civile e stato delle notifiche dell'app"),
  APP("app", "app", "azioni nell'app: cambiare localita', aprire pagine, avviare una raffica del barometro, registrare un'osservazione, salvare un posto");

  companion object {
    fun fromId(id: String?): ToolGroup? = entries.firstOrNull { it.id == id?.trim()?.lowercase() }
  }
}

/** Cosa un tool puo' toccare mentre gira: i dati dell'app, le unita' dell'utente, l'ora, il posto. */
class ToolContext(
  private val sourcesProvider: () -> AiDataSources,
  private val unitsProvider: () -> UnitFormatter,
  private val resourcesProvider: () -> Resources,
  val locale: Locale,
  val zone: ZoneId,
  val nowMillis: Long,
  /** La localita' mostrata in home, gia' risolta; null se il telefono non sa dov'e'. */
  val selected: ResolvedPlace?,
  val actionsEnabled: Boolean,
  val actions: ActionSink,
) {
  /** Pigri, cosi' un test dell'orchestratore costruisce un contesto senza Android sotto. */
  val sources: AiDataSources by lazy { sourcesProvider() }
  val units: UnitFormatter by lazy { unitsProvider() }
  val resources: Resources by lazy { resourcesProvider() }

  fun string(res: Int): String = resources.getString(res)
  fun string(res: Int, vararg args: Any): String = resources.getString(res, *args)
}

interface AiTool {
  val name: String
  val group: ToolGroup
  val description: String
  val parameters: JsonObject

  /** Il risultato e' testo compatto per il modello: righe `chiave: valore`, mai JSON verboso. */
  suspend fun run(args: JsonObject, ctx: ToolContext): String

  val spec: ToolSpec get() = ToolSpec(name, description, parameters)
}

/**
 * Il catalogo: tutti i tool, quelli di un insieme di gruppi, e il tool-scappatoia con cui il
 * modello chiede un gruppo che lo stadio 1 non gli ha dato.
 */
class ToolRegistry(val tools: List<AiTool>) {

  init {
    val duplicates = tools.groupBy { it.name }.filterValues { it.size > 1 }.keys
    require(duplicates.isEmpty()) { "tool duplicati: $duplicates" }
  }

  fun specsFor(groups: Set<ToolGroup>): List<ToolSpec> = tools.filter { it.group in groups }.map { it.spec }

  fun allSpecs(): List<ToolSpec> = tools.map { it.spec }

  fun find(name: String): AiTool? = tools.firstOrNull { it.name == name }

  val moreTools: ToolSpec = ToolSpec(
    name = MORE_TOOLS,
    description = "Chiede altri strumenti di un gruppo non ancora disponibile. Gruppi: " +
      ToolGroup.entries.joinToString("; ") { "${it.id} = ${it.hint}" },
    parameters = Schema.obj(
      mapOf("gruppo" to Schema.str("il gruppo di strumenti che serve", ToolGroup.entries.map { it.id })),
      required = listOf("gruppo"),
    ),
  )

  companion object {
    const val MORE_TOOLS = "altri_tool"
  }
}

/** Gli schemi JSON dei parametri, brevi: ogni parola nello schema costa token a ogni giro. */
object Schema {
  fun obj(properties: Map<String, JsonObject>, required: List<String> = emptyList()): JsonObject = buildJsonObject {
    put("type", "object")
    put("properties", buildJsonObject { properties.forEach { (name, schema) -> put(name, schema) } })
    if (required.isNotEmpty()) put("required", buildJsonArray { required.forEach { add(JsonPrimitive(it)) } })
  }

  fun str(description: String, enum: List<String>? = null): JsonObject = buildJsonObject {
    put("type", "string")
    put("description", description)
    enum?.let { values -> put("enum", buildJsonArray { values.forEach { add(JsonPrimitive(it)) } }) }
  }

  fun int(description: String, minimum: Int? = null, maximum: Int? = null): JsonObject = buildJsonObject {
    put("type", "integer")
    put("description", description)
    minimum?.let { put("minimum", it) }
    maximum?.let { put("maximum", it) }
  }

  fun bool(description: String): JsonObject = buildJsonObject {
    put("type", "boolean")
    put("description", description)
  }

  fun strArray(description: String, enum: List<String>? = null): JsonObject = buildJsonObject {
    put("type", "array")
    put("description", description)
    put("items", str("", enum).let { item -> buildJsonObject { put("type", "string"); enum?.let { put("enum", item["enum"]!!) } } })
  }

  /** Il parametro che quasi ogni tool ha: dove. */
  val place: JsonObject = str("localita': nome di un posto salvato, un posto qualsiasi, oppure vuoto per quella selezionata")
}

/** Lettura tollerante degli argomenti: il modello scrive numeri come stringhe e viceversa. */
object Args {
  fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() && it != "null" }
  fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.let { it.doubleOrNull?.toInt() ?: it.contentOrNull?.trim()?.toDoubleOrNull()?.toInt() }
  fun JsonObject.double(key: String): Double? = (this[key] as? JsonPrimitive)?.let { it.doubleOrNull ?: it.contentOrNull?.trim()?.toDoubleOrNull() }
  fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.let { it.booleanOrNull ?: it.contentOrNull?.trim()?.toBooleanStrictOrNull() }
  fun JsonObject.list(key: String): List<String> = (this[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList()
}

/**
 * Il testo che torna al modello: righe brevi, un budget di caratteri (~600 token) oltre il quale
 * si tronca dicendo quante righe mancano. I tool tagliano prima le loro liste; questo e' l'ultimo
 * argine, e serve soprattutto su Groq.
 */
object ToolText {
  const val MAX_CHARS = 2400

  fun limit(text: String, maxChars: Int = MAX_CHARS): String {
    if (text.length <= maxChars) return text
    val lines = text.lines()
    val kept = StringBuilder()
    var count = 0
    for (line in lines) {
      if (kept.length + line.length + 1 > maxChars - 40) break
      kept.append(line).append('\n')
      count++
    }
    val missing = lines.size - count
    return kept.toString().trimEnd() + "\n… (altre $missing righe omesse)"
  }

  class Builder {
    private val lines = mutableListOf<String>()
    fun line(text: String) { lines += text }
    fun line(key: String, value: Any?) { lines += "$key: ${value ?: "—"}" }
    fun blank() { lines += "" }
    fun build(): String = limit(lines.joinToString("\n").trim())
  }

  inline fun build(block: Builder.() -> Unit): String = Builder().apply(block).build()
}
