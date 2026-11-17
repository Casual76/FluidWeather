package dev.pampa.fluidweather.core.weather

import dev.pampa.fluidweather.core.model.Contribution
import dev.pampa.fluidweather.core.model.ForecastBundle
import dev.pampa.fluidweather.core.model.FusedForecast
import dev.pampa.fluidweather.core.model.FusedHour
import dev.pampa.fluidweather.core.model.FusedValue
import dev.pampa.fluidweather.core.model.FusionVariables
import dev.pampa.fluidweather.core.model.HorizonBucket
import dev.pampa.fluidweather.core.model.HourlyPoint
import dev.pampa.fluidweather.core.model.WeatherKind
import dev.pampa.fluidweather.core.model.WeatherInterval

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
    val bundles = fetches.filter { it.error == null }.mapNotNull { it.bundle }
      .groupBy { it.providerId }.values.mapNotNull { it.distinct().singleOrNull() }
    if (bundles.isEmpty()) return FusedForecast(emptyList(), emptyMap())
    val products = bundles.associate { it.providerId to ForecastProducts(it) }
    val providerIds = bundles.map { it.providerId }

    // Pesi per variabile e fascia, calcolati una volta per fusione. Le variabili verificate
    // hanno la loro classifica; le altre (probabilita', UV, visibilita'...) ereditano la media
    // dei pesi verificati del provider: chi ci prende sul misurabile e' credibile anche li'.
    val weightTable = HorizonBucket.entries.associateWith { bucket ->
      val verifiedWeights = FusionVariables.verified.associateWith { variable ->
        scoreboard.weights(variable, bucket, latitude, longitude, providerIds)
      }
      val aggregate = providerIds.associateWith { providerId ->
        val mean = verifiedWeights.values.mapNotNull { it[providerId]?.weight }.average()
        ProviderWeight(providerId, mean, WeightSource.PRIOR, Double.NaN, 0)
      }
      FusionVariables.all.associateWith { variable ->
        verifiedWeights[variable] ?: aggregate
      }
    }

    val timeline = products.values.flatMap { it.timestamps }
      .filter { it >= nowMillis - 3_600_000L && it <= nowMillis + 10 * 24 * 3_600_000L }
      .distinct()
      .sorted()

    val usedWeights = mutableMapOf<String, MutableList<Double>>()

    val hours = timeline.map { timestamp ->
      val bucket = HorizonBucket.of(((timestamp - nowMillis) / 3_600_000L).toInt().coerceAtLeast(0))
      val values = mutableMapOf<String, FusedValue>()
      var kind: WeatherKind? = null
      var kindWeight = -1.0
      var kindProvider: String? = null

      for (variable in FusionVariables.all) {
        val weights = weightTable.getValue(bucket).getValue(variable)
        val contributions = bundles.mapNotNull { bundle ->
          val value = products.getValue(bundle.providerId).valueAt(timestamp, variable) ?: return@mapNotNull null
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
              val value = products.getValue(bundle.providerId).valueAt(timestamp, variable) ?: return@mapNotNull null
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

      // Condizione e direzione del vento non si mediano: parla il provider col peso maggiore.
      var windDirection: Double? = null
      var windWeight = -1.0
      var windProvider: String? = null
      val categorical = bundles.mapNotNull { bundle -> products.getValue(bundle.providerId).pointAt(timestamp)?.let { bundle to it } }
      val selectedKind = categorical.filter { it.second.kind != null && it.second.kind != WeatherKind.UNKNOWN }
      val selectedWind = categorical.filter { it.second.windDirectionDeg?.let { value -> value.isFinite() && value in 0.0..360.0 } == true }
      fun chosen(candidates: List<Pair<ForecastBundle, HourlyPoint>>) =
        candidates.filter { onlyProviderId == null || it.first.providerId == onlyProviderId }.ifEmpty { candidates }
      for ((bundle, point) in chosen(selectedKind)) {
        val weight = weightTable.getValue(bucket)
          .getValue(FusionVariables.PRECIPITATION)[bundle.providerId]?.weight ?: 0.0
        if (weight > kindWeight) {
          kindWeight = weight
          kind = point.kind
          kindProvider = bundle.providerId
        }
      }
      for ((bundle, point) in chosen(selectedWind)) {
        val weight = weightTable.getValue(bucket)
          .getValue(FusionVariables.WIND_SPEED)[bundle.providerId]?.weight ?: 0.0
        if (weight > windWeight) {
          windWeight = weight
          windDirection = point.windDirectionDeg
          windProvider = bundle.providerId
        }
      }

      FusedHour(
        timestampMillis = timestamp,
        values = values,
        kind = kind,
        windDirectionDeg = windDirection,
        kindProviderId = kindProvider,
        windDirectionProviderId = windProvider,
        precipitationInterval = if (FusionVariables.PRECIPITATION in values || FusionVariables.PRECIP_PROBABILITY in values)
          WeatherInterval(timestamp - ForecastProducts.HOUR_MILLIS, timestamp) else null,
      )
    }.filter { it.values.isNotEmpty() || it.kind != null || it.windDirectionDeg != null }

    return FusedForecast(
      hours = hours,
      providerWeights = usedWeights.mapValues { (_, list) -> list.average() },
    )
  }
}
