package dev.pampa.fluidweather.core.model

/**
 * Un posto per cui l'app fa il meteo. [GPS_ID] e' la voce speciale "posizione attuale": non e'
 * una riga salvata, e' il telefono — e sta sempre in cima all'elenco.
 */
data class Place(
  val id: Long,
  val name: String,
  val region: String?,
  val latitude: Double,
  val longitude: Double,
) {
  val isGps: Boolean get() = id == GPS_ID

  companion object {
    const val GPS_ID: Long = -1L

    fun gps(): Place = Place(GPS_ID, "La mia posizione", null, Double.NaN, Double.NaN)
  }
}

/** Il giro della pillola: swipe avanti/indietro nell'elenco [gps, salvate...], ad anello. */
object PlaceCycle {

  fun next(places: List<Place>, currentId: Long): Place? = step(places, currentId, +1)

  fun previous(places: List<Place>, currentId: Long): Place? = step(places, currentId, -1)

  private fun step(places: List<Place>, currentId: Long, delta: Int): Place? {
    if (places.isEmpty()) return null
    val index = places.indexOfFirst { it.id == currentId }.takeIf { it >= 0 } ?: 0
    return places[(index + delta + places.size) % places.size]
  }
}
