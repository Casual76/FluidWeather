package dev.pampa.fluidweather.core.weather

import dev.pampa.fluidweather.core.model.ForecastBundle

/** Un client sa parlare con UN provider e restituisce sempre la stessa forma normalizzata. */
interface WeatherClient {
  val descriptor: ProviderDescriptor

  /** [apiKey] arriva solo per i provider che la richiedono; per gli altri e' null e ignorata. */
  suspend fun fetch(latitude: Double, longitude: Double, apiKey: String?): ForecastBundle
}

/** Il TTL standard di una previsione: mezz'ora. Un modello nuovo esce ogni 1-6 ore. */
internal const val FORECAST_TTL_MILLIS: Long = 30 * 60_000L
