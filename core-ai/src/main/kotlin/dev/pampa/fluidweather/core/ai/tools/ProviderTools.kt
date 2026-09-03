package dev.pampa.fluidweather.core.ai.tools

import dev.pampa.fluidweather.core.ai.data.PlaceResolver
import dev.pampa.fluidweather.core.ai.tools.Args.int
import dev.pampa.fluidweather.core.ai.tools.Args.str
import dev.pampa.fluidweather.core.model.FusionVariables
import dev.pampa.fluidweather.core.weather.Benchmark
import dev.pampa.fluidweather.core.weather.Coverage
import dev.pampa.fluidweather.core.weather.ProviderRegistry
import dev.pampa.fluidweather.core.weather.RainEvent
import kotlin.math.roundToInt
import kotlinx.serialization.json.JsonObject

private fun providerLabel(id: String): String = ProviderRegistry.all.firstOrNull { it.id == id }?.label ?: if (id == RainEvent.LOCAL_BAROMETER_ID) "barometro del telefono" else id

/** Quali servizi coprono il posto, con chiave e orizzonte, e come e' andato l'ultimo giro. */
class ProvidersAvailableTool(private val resolver: PlaceResolver) : AiTool {
  override val name = "provider_disponibili"
  override val group = ToolGroup.PROVIDERS
  override val description = "I servizi meteo che coprono il posto: nome, copertura, se serve una chiave, orizzonte in ore, esito dell'ultimo giro (ore ricevute o errore), override 'usa solo questo'."
  override val parameters = Schema.obj(mapOf("luogo" to Schema.place))

  override suspend fun run(args: JsonObject, ctx: ToolContext): String {
    val place = resolver.resolve(args.str("luogo"), ctx.selected) ?: return ToolPhrases.PLACE_NOT_FOUND
    val keys = ctx.sources.providerKeys.current()
    val snapshot = resolver.snapshot(place)
    val only = ctx.sources.fusionSettings.currentOnlyProviderId()
    return ToolText.build {
      line("luogo", place.label)
      only?.let { line("override attivo", "usa solo ${providerLabel(it)} dove e' supportato") }
      ProviderRegistry.all.forEach { descriptor ->
        val covers = descriptor.coverage.contains(place.latitude, place.longitude)
        val keyed = !descriptor.requiresKey || keys.containsKey(descriptor.id)
        val fetch = snapshot?.fetches?.firstOrNull { it.providerId == descriptor.id }
        val status = when {
          !covers -> "non copre questo posto"
          !keyed -> "serve la chiave (non inserita)"
          fetch == null -> "non interrogato nell'ultimo giro"
          fetch.error != null -> "errore: ${fetch.error}"
          else -> "${fetch.hours} ore ricevute"
        }
        val coverage = if (descriptor.coverage is Coverage.Global) "mondiale" else "regionale"
        line("- ${descriptor.label} [${descriptor.id}]", "$coverage, orizzonte ${descriptor.horizonHours} h, $status")
      }
    }
  }
}

/** Le opinioni dei singoli servizi su una variabile nelle prossime ore, dai contributi della fusione. */
class ProviderComparisonTool(private val resolver: PlaceResolver) : AiTool {
  override val name = "confronto_provider"
  override val group = ToolGroup.PROVIDERS
  override val description = "Confronta i servizi su una variabile (temperatura, pioggia, probabilita_pioggia, vento, nuvole, pressione) nelle prossime N ore: valore per servizio, peso nella fusione, accordo o disaccordo."
  override val parameters = Schema.obj(
    mapOf(
      "luogo" to Schema.place,
      "variabile" to Schema.str("cosa confrontare", VARIABLES.keys.toList()),
      "ore" to Schema.int("quante ore avanti (1-24)", 1, 24),
    ),
    required = listOf("variabile"),
  )

  override suspend fun run(args: JsonObject, ctx: ToolContext): String {
    val place = resolver.resolve(args.str("luogo"), ctx.selected) ?: return ToolPhrases.PLACE_NOT_FOUND
    val snapshot = resolver.snapshot(place) ?: return ToolPhrases.NO_DATA
    val variable = VARIABLES[args.str("variabile")?.lowercase()] ?: return "errore: variabile non capita (${VARIABLES.keys.joinToString(", ")})"
    val hours = (args.int("ore") ?: 6).coerceIn(1, 24)
    val end = ctx.nowMillis + hours * 3_600_000L
    val fused = snapshot.fused.hours.filter { it.timestampMillis >= ctx.nowMillis - 30 * 60_000L && it.timestampMillis <= end }.sortedBy { it.timestampMillis }
    if (fused.isEmpty()) return ToolPhrases.NO_DATA
    val step = if (fused.size <= 8) 1 else if (fused.size <= 16) 2 else 3
    val u = ctx.units
    fun format(value: Double): String = when (variable) {
      FusionVariables.TEMPERATURE -> u.temperature(value)
      FusionVariables.PRECIPITATION -> u.precipitation(value)
      FusionVariables.PRECIP_PROBABILITY, FusionVariables.CLOUD_COVER, FusionVariables.HUMIDITY -> "${value.roundToInt()}%"
      FusionVariables.WIND_SPEED -> u.wind(value)
      FusionVariables.PRESSURE_MSL -> u.pressure(value, 1)
      else -> "%.1f".format(ctx.locale, value)
    }
    return ToolText.build {
      line(ToolPhrases.header(ctx, place, snapshot))
      val weights = snapshot.fused.providerWeights
      line("pesi nella fusione", weights.entries.sortedByDescending { it.value }.joinToString(", ") { "${providerLabel(it.key)} ${(it.value * 100).roundToInt()}%" })
      fused.filterIndexed { i, _ -> i % step == 0 }.forEach { hour ->
        val fusedValue = hour.values[variable] ?: return@forEach
        val contributions = fusedValue.contributions.sortedByDescending { it.weight }
        val values = contributions.map { it.value }
        val spread = if (values.size >= 2) values.max() - values.min() else 0.0
        val agreement = when {
          values.size < 2 -> ""
          spread <= agreementTolerance(variable) -> " · concordano"
          else -> " · divergono (${format(values.min())}-${format(values.max())})"
        }
        line("${ctx.timeLabel(hour.timestampMillis)} fuso ${format(fusedValue.value)}$agreement")
        line("  " + contributions.joinToString(", ") { "${providerLabel(it.providerId)} ${format(it.value)}" })
      }
    }
  }

  private fun agreementTolerance(variable: String): Double = when (variable) {
    FusionVariables.TEMPERATURE -> 1.5
    FusionVariables.PRECIPITATION -> 0.5
    FusionVariables.PRECIP_PROBABILITY -> 20.0
    FusionVariables.WIND_SPEED -> 8.0
    FusionVariables.CLOUD_COVER -> 25.0
    FusionVariables.PRESSURE_MSL -> 1.5
    else -> 1.0
  }

  companion object {
    val VARIABLES = linkedMapOf(
      "temperatura" to FusionVariables.TEMPERATURE,
      "pioggia" to FusionVariables.PRECIPITATION,
      "probabilita_pioggia" to FusionVariables.PRECIP_PROBABILITY,
      "vento" to FusionVariables.WIND_SPEED,
      "nuvole" to FusionVariables.CLOUD_COVER,
      "pressione" to FusionVariables.PRESSURE_MSL,
      "umidita" to FusionVariables.HUMIDITY,
    )
  }
}

/** La pagella: chi ci prende di piu' qui, per variabile, e il barometro in gara sull'evento pioggia. */
class BenchmarkTool : AiTool {
  override val name = "benchmark"
  override val group = ToolGroup.PROVIDERS
  override val description = "La classifica dei servizi sulle verifiche locali (ultimi 14 giorni): quota di peso, numero di verifiche, dove sono i migliori, errore medio per variabile a 0-6 h, e il barometro del telefono in gara sull'evento pioggia."
  override val parameters = Schema.obj(emptyMap())

  override suspend fun run(args: JsonObject, ctx: ToolContext): String {
    val since = ctx.nowMillis - Benchmark.DAYS * 86_400_000L
    val verifications = runCatching { ctx.sources.verificationStore.allVerifications(since) }.getOrDefault(emptyList())
    val report = Benchmark.build(verifications, ctx.nowMillis)
    if (report.totalVerifications == 0) return "nessuna verifica ancora: la pagella si forma dopo qualche giorno di uso"
    val u = ctx.units
    return ToolText.build {
      line("verifiche negli ultimi ${Benchmark.DAYS} giorni", report.totalVerifications)
      report.firstVerificationMillis?.let { line("prima verifica", ctx.dayTimeLabel(it)) }
      line("soglia per i pesi appresi", "${Benchmark.MIN_VERIFICATIONS} verifiche per variabile")
      line("classifica (quota di peso, verifiche, migliore su)")
      report.ranking.forEachIndexed { index, rank ->
        line("${index + 1}. ${providerLabel(rank.providerId)}: ${(rank.share * 100).roundToInt()}%, ${rank.verifications} verifiche" + if (rank.bestAt.isNotEmpty()) ", migliore su ${rank.bestAt.joinToString(", ") { variableLabel(it) }}" else "")
      }
      report.byVariable.forEach { (variable, scores) ->
        val best = scores.sortedBy { it.decayedMae }.take(3)
        if (best.isNotEmpty()) {
          line("errore medio ${variableLabel(variable)} (0-6 h)", best.joinToString(", ") { "${providerLabel(it.providerId)} ${formatError(u, variable, it.decayedMae)}${if (!it.learned) " (poche verifiche)" else ""}" })
        }
      }
      report.rainEvent.entries.firstOrNull()?.let { (window, scores) ->
        val label = window.removePrefix(RainEvent.PREFIX).replace('_', '-') + "h"
        line("evento pioggia $label (errore 0..1, il barometro in gara)", scores.sortedBy { it.decayedMae }.joinToString(", ") { "${providerLabel(it.providerId)} ${"%.2f".format(ctx.locale, it.decayedMae)}" })
      }
    }
  }

  private fun variableLabel(variable: String): String = when (variable) {
    FusionVariables.TEMPERATURE -> "temperatura"
    FusionVariables.PRESSURE_MSL -> "pressione"
    FusionVariables.PRECIPITATION -> "pioggia"
    FusionVariables.CLOUD_COVER -> "nuvole"
    FusionVariables.WIND_SPEED -> "vento"
    else -> variable
  }

  private fun formatError(u: dev.pampa.fluidweather.strings.UnitFormatter, variable: String, mae: Double): String = when (variable) {
    FusionVariables.TEMPERATURE -> u.temperatureSpan(mae)
    FusionVariables.PRESSURE_MSL -> u.pressureDeltaValue(mae) + " " + u.pressureSymbol()
    FusionVariables.PRECIPITATION -> u.precipitation(mae)
    FusionVariables.CLOUD_COVER -> "${mae.roundToInt()}%"
    FusionVariables.WIND_SPEED -> u.wind(mae)
    else -> "%.1f".format(mae)
  }
}
