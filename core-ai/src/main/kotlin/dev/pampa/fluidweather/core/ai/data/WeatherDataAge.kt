package dev.pampa.fluidweather.core.ai.data

import dev.pampa.fluidweather.core.weather.WeatherSnapshot

internal fun weatherDataAgeMinutes(snapshot: WeatherSnapshot?, nowMillis: Long): Int? =
  snapshot?.sourceDataAtMillis?.takeIf { it in 0L..nowMillis }
    ?.let { ((nowMillis - it) / 60_000L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt() }
