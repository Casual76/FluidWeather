package dev.pampa.fluidweather.core.weather

/**
 * Le macro-regioni dei priori. Grossolane per scelta: servono solo finche' le verifiche locali
 * non raggiungono la soglia minima — dopo, comanda l'esperienza del posto.
 */
enum class MacroRegion {
  NORDICS,
  EUROPE,
  NORTH_AMERICA,
  EAST_ASIA,
  TROPICS,
  ELSEWHERE;

  companion object {
    fun of(latitude: Double, longitude: Double): MacroRegion = when {
      kotlin.math.abs(latitude) < 23.5 -> TROPICS
      latitude > 55.0 && longitude in -25.0..45.0 -> NORDICS
      latitude in 35.0..72.0 && longitude in -25.0..45.0 -> EUROPE
      latitude in 15.0..72.0 && longitude in -170.0..-50.0 -> NORTH_AMERICA
      latitude in 20.0..55.0 && longitude in 100.0..150.0 -> EAST_ASIA
      else -> ELSEWHERE
    }
  }
}

/**
 * I priori per regione: chi parte avvantaggiato dove, PRIMA che le verifiche locali parlino.
 *
 * Onesta' dichiarata: questi numeri sono un editoriale informato (i servizi nazionali sul loro
 * territorio, ICON/ECMWF sull'Europa, GFS/NWS sul Nord America, JMA sull'Asia orientale), messi
 * al posto delle tabelle di benchmark pubbliche che il piano prevede di incorporare. Sono fatti
 * per essere scavalcati in fretta: bastano 20 verifiche locali su una variabile e la classifica
 * diventa quella del TUO cielo.
 */
object RegionalPriors {

  private val byRegion: Map<MacroRegion, Map<String, Double>> = mapOf(
    MacroRegion.NORDICS to mapOf(
      ProviderRegistry.MET_NORWAY to 1.0,
      ProviderRegistry.OPEN_METEO_ICON to 0.9,
      ProviderRegistry.OPEN_METEO_ECMWF to 0.9,
      ProviderRegistry.OPEN_METEO to 0.85,
      ProviderRegistry.OPEN_METEO_GFS to 0.7,
    ),
    MacroRegion.EUROPE to mapOf(
      ProviderRegistry.OPEN_METEO_ICON to 1.0,
      ProviderRegistry.OPEN_METEO_AROME to 0.95,
      ProviderRegistry.OPEN_METEO_ECMWF to 0.95,
      ProviderRegistry.OPEN_METEO to 0.9,
      ProviderRegistry.MET_NORWAY to 0.8,
      ProviderRegistry.OPEN_METEO_GFS to 0.7,
    ),
    MacroRegion.NORTH_AMERICA to mapOf(
      ProviderRegistry.NWS to 1.0,
      ProviderRegistry.OPEN_METEO to 0.9,
      ProviderRegistry.OPEN_METEO_GFS to 0.9,
      ProviderRegistry.OPEN_METEO_ECMWF to 0.9,
      ProviderRegistry.OPEN_METEO_ICON to 0.75,
    ),
    MacroRegion.EAST_ASIA to mapOf(
      ProviderRegistry.OPEN_METEO_JMA to 1.0,
      ProviderRegistry.OPEN_METEO_ECMWF to 0.95,
      ProviderRegistry.OPEN_METEO to 0.9,
      ProviderRegistry.OPEN_METEO_GFS to 0.8,
    ),
    MacroRegion.TROPICS to mapOf(
      ProviderRegistry.OPEN_METEO to 1.0,
      ProviderRegistry.OPEN_METEO_ECMWF to 0.95,
      ProviderRegistry.OPEN_METEO_GFS to 0.85,
      ProviderRegistry.OPEN_METEO_ICON to 0.8,
    ),
    MacroRegion.ELSEWHERE to mapOf(
      ProviderRegistry.OPEN_METEO to 1.0,
      ProviderRegistry.OPEN_METEO_ECMWF to 0.95,
      ProviderRegistry.OPEN_METEO_GFS to 0.85,
      ProviderRegistry.OPEN_METEO_ICON to 0.8,
      ProviderRegistry.MET_NORWAY to 0.75,
    ),
  )

  /** I provider a chiave partono prudenti ovunque: qualita' ignota finche' non verificata. */
  private const val KEYED_PRIOR = 0.7

  /** Il fondo per chiunque non sia nominato: nessuno parte da zero. */
  private const val FLOOR_PRIOR = 0.6

  fun prior(region: MacroRegion, providerId: String): Double =
    byRegion.getValue(region)[providerId]
      ?: if (ProviderRegistry.all.firstOrNull { it.id == providerId }?.requiresKey == true) {
        KEYED_PRIOR
      } else {
        FLOOR_PRIOR
      }
}
