package dev.pampa.fluidweather.pampai

import android.content.Context
import android.content.Intent
import android.net.Uri
import dev.pampa.fluidweather.FluidWeatherApp
import dev.pampa.fluidweather.core.ai.tools.ActionOutcome
import dev.pampa.fluidweather.core.ai.tools.ActionSink
import dev.pampa.fluidweather.core.ai.tools.AssistantAction
import dev.pampa.fluidweather.core.ai.tools.ToolContext
import dev.pampa.fluidweather.core.ai.tools.ToolGroup
import dev.pampa.fluidweather.strings.UnitFormatter
import dev.antigravity.fluidengine.ai.bridge.AiToolHostProvider
import dev.antigravity.fluidengine.ai.bridge.ReadyState
import dev.antigravity.fluidengine.ai.bridge.RemoteCall
import dev.antigravity.fluidengine.ai.tools.AiTool as EngineTool
import dev.antigravity.fluidengine.ai.tools.AiToolGroup
import dev.antigravity.fluidengine.ai.tools.ToolOutput
import dev.antigravity.fluidengine.ai.tools.ToolRegistry
import java.time.ZoneId
import java.util.Locale
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject

/**
 * I gruppi di Fluid Weather nel vocabolario dell'engine: stessi id, stessi suggerimenti. Sono i
 * nomi che il router di PampAI legge per capire quando aprire il meteo, e da qui in poi vivono
 * prefissati (`meteo_orario`, `meteo_precipitazioni`) nel catalogo di Aria.
 */
private enum class BridgeGroup(
  override val id: String,
  override val statusKey: String,
  override val hint: String,
  override val loadsWithCategory: Boolean = false,
) : AiToolGroup {
  PLACES("luogo", "places", "cercare o elencare localita', salvate o nuove, e confrontarle fra loro"),
  NOWCAST("nowcast", "nowcast", "il barometro del telefono: probabilita' di pioggia nelle prossime ore, pressione, tendenza, taratura"),
  HOURLY("orario", "hourly", "il tempo di adesso, il riepilogo completo e le previsioni ora per ora", loadsWithCategory = true),
  DAILY("giornaliero", "daily", "i prossimi giorni: minime, massime, pioggia, vento, alba e tramonto"),
  PRECIP("precipitazioni", "precip", "pioggia e neve nelle prossime ore, quando inizia e finisce, le finestre asciutte, il radar", loadsWithCategory = true),
  AIR("aria", "air", "qualita' dell'aria, inquinanti, pollini"),
  SKY("cielo", "sky", "sole (alba, tramonto, durata del giorno) e luna (fase, sorgere)"),
  PROVIDERS("provider", "providers", "i servizi meteo usati, il confronto fra le loro previsioni, chi ci prende di piu' qui"),
  ALERTS("allerte", "alerts", "allerte ufficiali della protezione civile e stato delle notifiche"),
  APP("app", "app", "azioni nell'app: cambiare localita', aprire pagine, unita' di misura, salvare o togliere un posto, osservazioni, raffica e taratura del barometro"),
  ;

  companion object {
    fun of(group: ToolGroup): BridgeGroup = entries.first { it.id == group.id }
  }
}

/**
 * Un tool di Fluid Weather visto dall'engine. L'app ha i suoi tipi (il `core-ai` di casa, che
 * precede `engine-ai`): invece di riscrivere ventisette strumenti si traduce la firma — testo
 * dentro, [ToolOutput] fuori — e il resto resta esattamente com'e', compresi i messaggi d'errore
 * che il modello gia' conosce.
 */
private class BridgedTool(private val inner: dev.pampa.fluidweather.core.ai.tools.AiTool) : EngineTool<ToolContext> {
  override val name: String = inner.name
  override val group: AiToolGroup = BridgeGroup.of(inner.group)
  override val description: String = inner.description
  override val parameters: JsonObject = inner.parameters

  /** Le azioni dell'app: chi le chiama da fuori deve chiedere prima. */
  override val isAction: Boolean = inner.group == ToolGroup.APP
  override val needsConfirmation: Boolean = inner.name in CONFIRMED

  override suspend fun run(args: JsonObject, ctx: ToolContext): ToolOutput {
    val text = inner.run(args, ctx)
    return if (text.startsWith("errore")) ToolOutput.error(text.removePrefix("errore:").trim()) else ToolOutput(text)
  }

  private companion object {
    /** Quelle che scrivono qualcosa: la conferma la chiede il chiamante, con le sue parole. */
    val CONFIRMED = setOf("avvia_raffica", "registra_osservazione", "salva_luogo", "localita_rimuovi", "osservazione_elimina", "calibrazione_stato")
  }
}

/**
 * Fluid Weather vista da PampAI/Aria: gli stessi strumenti dell'assistente interno, eseguiti qui
 * dove ci sono i dati fusi, il barometro e le localita' salvate. Il permesso e' `signature`: solo
 * un'app firmata con la stessa chiave puo' chiamare.
 */
class WeatherToolHostProvider : AiToolHostProvider<ToolContext>() {

  private val app: FluidWeatherApp get() = context!!.applicationContext as FluidWeatherApp

  private val bridged: ToolRegistry<ToolContext> by lazy {
    val tools = app.graph.aiAssistant.registry.tools.map { BridgedTool(it) }
    ToolRegistry(tools, BridgeGroup.entries.toList(), actionGroup = null)
  }

  override fun registry(): ToolRegistry<ToolContext> = bridged

  override suspend fun context(call: RemoteCall): ToolContext {
    val assistant = app.graph.aiAssistant
    val sources = assistant.dataSources
    val resources = context!!.resources
    val units = UnitFormatter(resources, sources.unitsStore.current(), Locale.getDefault())
    val selected = runCatching { assistant.resolver.selected() }.getOrNull()
    return ToolContext(
      sourcesProvider = { sources },
      unitsProvider = { units },
      resourcesProvider = { resources },
      locale = Locale.getDefault(),
      zone = ZoneId.systemDefault(),
      nowMillis = System.currentTimeMillis(),
      selected = selected,
      actionsEnabled = true,
      actions = BridgeActionSink(app, context!!),
    )
  }

  override fun domain(): String = "meteo"

  override fun appLabel(): String = "Fluid Weather"

  override fun routerHint(): String =
    "il meteo: com'e' adesso e nei prossimi giorni, quando piove e quando smette, il radar, il barometro del telefono, qualita' dell'aria, sole e luna, allerte della protezione civile"

  /** Le localita' salvate: sono i nomi che l'utente dice a voce. */
  override fun vocabulary(): List<String> = runCatching {
    runBlocking { app.graph.aiAssistant.dataSources.savedLocations.places.first().map { it.name } }
  }.getOrDefault(emptyList())

  override fun ready(): ReadyState = ReadyState(true)

  override fun partsAuthority(): String? = null
}

/** Le azioni chieste da fuori: gia' confermate. "Apri" porta anche l'app davanti. */
private class BridgeActionSink(private val app: FluidWeatherApp, private val context: Context) : ActionSink {
  override suspend fun perform(action: AssistantAction): ActionOutcome {
    val outcome = app.graph.aiAssistant.session.performPreConfirmed(action)
    if (action is AssistantAction.Open) {
      // Aprire qualcosa che resta dietro non e' aprire niente: si porta l'app davanti con l'Uri
      // che `MainActivity` gia' legge per i widget (l'ultimo segmento e' la pagina).
      val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
        ?.setData(Uri.parse("fluidweather://open/${action.target.id}"))
        ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      runCatching { intent?.let { context.startActivity(it) } }
    }
    return outcome
  }
}
