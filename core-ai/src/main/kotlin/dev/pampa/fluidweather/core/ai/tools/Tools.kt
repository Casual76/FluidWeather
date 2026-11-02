package dev.pampa.fluidweather.core.ai.tools

import android.content.res.Resources
import dev.antigravity.fluidengine.ai.tools.AiTool
import dev.antigravity.fluidengine.ai.tools.AiToolGroup
import dev.antigravity.fluidengine.ai.tools.Schema
import dev.antigravity.fluidengine.ai.tools.ToolOutput
import dev.antigravity.fluidengine.ai.tools.ToolRegistry
import dev.pampa.fluidweather.core.ai.data.AiDataSources
import dev.pampa.fluidweather.core.ai.data.ResolvedPlace
import dev.pampa.fluidweather.strings.UnitFormatter
import java.time.ZoneId
import java.util.Locale
import kotlinx.serialization.json.JsonObject

/**
 * I gruppi del catalogo a due stadi: lo stadio 1 sceglie i gruppi, lo stadio 2 riceve i tool di
 * quei gruppi. Gli id sono le parole che il modello legge nello schema, quindi in italiano come
 * il resto dei tool; il testo di stato ("Leggo il nowcast") lo aggiunge la UI dalla chiave.
 */
enum class ToolGroup(override val id: String, override val statusKey: String, override val hint: String) : AiToolGroup {
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

/**
 * Un tool del meteo, nel vocabolario di `engine-ai`. I tool di FluidWeather rispondono con testo
 * (righe `chiave: valore`, "errore: ..." quando qualcosa manca) e nessuno porta allegati: scrivono
 * [answer], e il passaggio a [ToolOutput] lo fa questa classe, in un posto solo.
 *
 * Qui stanno anche i metadati che servono a chi chiama i tool da fuori (PampAI/Aria, dal bridge):
 * il gruppo APP e' fatto di azioni, e quelle che scrivono qualcosa chiedono conferma prima.
 */
abstract class WeatherTool : AiTool<ToolContext> {
  abstract override val group: ToolGroup

  /** Il risultato e' testo compatto per il modello: righe `chiave: valore`, mai JSON verboso. */
  abstract suspend fun answer(args: JsonObject, ctx: ToolContext): String

  final override suspend fun run(args: JsonObject, ctx: ToolContext): ToolOutput = ToolOutput(answer(args, ctx))

  override val isAction: Boolean get() = group == ToolGroup.APP
  override val needsConfirmation: Boolean get() = name in WRITES

  companion object {
    /** I tool che scrivono qualcosa: dentro l'app chiedono conferma da se', da fuori la chiede il chiamante. */
    val WRITES = setOf("avvia_raffica", "registra_osservazione", "salva_luogo", "localita_rimuovi", "osservazione_elimina", "calibrazione_stato")
  }
}

/** Il parametro che quasi ogni tool ha: dove. */
val Schema.place: JsonObject
  get() = Schema.str("localita': nome di un posto salvato, un posto qualsiasi, oppure vuoto per quella selezionata")

/** Il catalogo dei tool del meteo, coi gruppi dell'app e il gruppo delle azioni che si spegne. */
fun weatherRegistry(tools: List<WeatherTool>): ToolRegistry<ToolContext> =
  ToolRegistry(tools, groups = ToolGroup.entries, actionGroup = ToolGroup.APP)
