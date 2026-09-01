package dev.pampa.fluidweather.core.weather

import dev.pampa.fluidweather.core.model.Contribution
import dev.pampa.fluidweather.core.model.ForecastBundle
import dev.pampa.fluidweather.core.model.FusedForecast
import dev.pampa.fluidweather.core.model.FusedHour
import dev.pampa.fluidweather.core.model.FusedValue
import dev.pampa.fluidweather.core.model.FusionVariables
import dev.pampa.fluidweather.core.model.HorizonBucket
import dev.pampa.fluidweather.core.model.WeatherKind

/**
 * La fusione: per ogni ora e variabile, media pesata delle opinioni disponibili, con i pesi
 * della cascata ([ProviderScoreboard]) e la tracciabilita' completa — ogni valore porta la
 * lista di chi ha contribuito e con che peso, perche' il piano promette che ogni previsione
 * resta riconducibile alla sua fonte.
 *
 * L'override "usa solo questo dove e' supportato" agisce qui: se il provider scelto ha
 * un'opinione per l'ora, e' l'unica che conta; dove non arriva, la cascata riprende il volante
 * (l'override non deve mai trasformarsi in un buco nel forecast).
 */
class ForecastFusion(private val scoreboard: ProviderScoreboard) {

  suspend fun fuse(
    fetches: List<ProviderFetch>,
    latitude: Double,
    longitude: Double,
    nowMillis: Long,
    onlyProviderId: String? = null,
  ): FusedForecast {
    val bundles = fetches.mapNotNull { it.bundle }
    if (bundles.isEmpty()) return FusedForecast(emptyList(), emptyMap())
    val providerIds = bundles.map { it.providerId }

    // Pesi per variabile e fascia, calcolati una volta per fusione.
    val weightTable = HorizonBucket.entries.associateWith { bucket ->
      FusionVariables.all.associateWith { variable ->
        scoreboard.weights(variable, bucket, latitude, longitude, providerIds)
      }
    }

    val timeline = bundles.flatMap { bundle -> bundle.hourly.map { it.timestampMillis } }
      .filter { it >= nowMillis - 3_600_000L }
      .distinct()
      .sorted()

    val usedWeights = mutableMapOf<String, MutableList<Double>>()

    val hours = timeline.map { timestamp ->
      val bucket = HorizonBucket.of(((timestamp - nowMillis) / 3_600_000L).toInt().coerceAtLeast(0))
      val values = mutableMapOf<String, FusedValue>()
      var kind: WeatherKind? = null
      var kindWeight = -1.0

      for (variable in FusionVariables.all) {
        val weights = weightTable.getValue(bucket).getValue(variable)
        val contributions = bundles.mapNotNull { bundle ->
          val point = bundle.at(timestamp) ?: return@mapNotNull null
          val value = FusionVariables.of(point, variable) ?: return@mapNotNull null
          val eligible = onlyProviderId == null || bundle.providerId == onlyProviderId
          if (!eligible) return@mapNotNull null
          Contribution(
            providerId = bundle.providerId,
            weight = weights[bundle.providerId]?.weight ?: 0.0,
            value = value,
          )
        }.ifEmpty {
          // L'override non copre quest'ora: si torna alla cascata piena.
          if (onlyProviderId == null) {
            emptyList()
          } else {
            bundles.mapNotNull { bundle ->
              val point = bundle.at(timestamp) ?: return@mapNotNull null
              val value = FusionVariables.of(point, variable) ?: return@mapNotNull null
              Contribution(bundle.providerId, weights[bundle.providerId]?.weight ?: 0.0, value)
            }
          }
        }

        val totalWeight = contributions.sumOf { it.weight }
        if (contributions.isEmpty() || totalWeight <= 0.0) continue

        val normalized = contributions.map { it.copy(weight = it.weight / totalWeight) }
        values[variable] = FusedValue(
          value = normalized.sumOf { it.weight * it.value },
          contributions = normalized.sortedByDescending { it.weight },
        )
        normalized.forEach { usedWeights.getOrPut(it.providerId) { mutableListOf() } += it.weight }
      }

      // La condizione (icona) non si media: parla il provider col peso maggiore che ce l'ha.
      for (bundle in bundles) {
        if (onlyProviderId != null && bundle.providerId != onlyProviderId) continue
        val pointKind = bundle.at(timestamp)?.kind ?: continue
        val weight = weightTable.getValue(bucket)
          .getValue(FusionVariables.PRECIPITATION)[bundle.providerId]?.weight ?: 0.0
        if (weight > kindWeight) {
          kindWeight = weight
          kind = pointKind
        }
      }

      FusedHour(timestampMillis = timestamp, values = values, kind = kind)
    }.filter { it.values.isNotEmpty() }

    return FusedForecast(
      hours = hours,
      providerWeights = usedWeights.mapValues { (_, list) -> list.average() },
    )
  }
}
