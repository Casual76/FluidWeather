package dev.pampa.fluidweather.core.model

/** Le etichette italiane delle condizioni: testata, riepilogo, notifiche parlano con una voce. */
object WeatherKindLabels {

  fun of(kind: WeatherKind?): String? = when (kind) {
    WeatherKind.CLEAR -> "Sereno"
    WeatherKind.MOSTLY_CLEAR -> "Poco nuvoloso"
    WeatherKind.PARTLY_CLOUDY -> "Parzialmente nuvoloso"
    WeatherKind.CLOUDY -> "Nuvoloso"
    WeatherKind.FOG -> "Nebbia"
    WeatherKind.DRIZZLE -> "Pioviggine"
    WeatherKind.RAIN -> "Pioggia"
    WeatherKind.HEAVY_RAIN -> "Pioggia forte"
    WeatherKind.SLEET -> "Pioggia gelata"
    WeatherKind.SNOW -> "Neve"
    WeatherKind.HEAVY_SNOW -> "Neve forte"
    WeatherKind.THUNDERSTORM -> "Temporale"
    WeatherKind.UNKNOWN, null -> null
  }

  /** Il nome della precipitazione che sta arrivando o finendo: "Neve in arrivo", non "Pioggia". */
  fun precipitationNoun(kind: WeatherKind?): String = when (kind) {
    WeatherKind.SNOW, WeatherKind.HEAVY_SNOW -> "Neve"
    WeatherKind.SLEET -> "Pioggia gelata"
    WeatherKind.THUNDERSTORM -> "Temporale"
    WeatherKind.DRIZZLE -> "Pioviggine"
    else -> "Pioggia"
  }
}
