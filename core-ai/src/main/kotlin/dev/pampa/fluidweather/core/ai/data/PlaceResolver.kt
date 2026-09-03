package dev.pampa.fluidweather.core.ai.data

import dev.pampa.fluidweather.core.model.Place
import dev.pampa.fluidweather.core.weather.WeatherSnapshot
import dev.pampa.fluidweather.core.weather.WeatherSnapshotRefresher
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.first

/** Un posto con tutto quello che un tool vuole sapere: dove, come si chiama, che istantanea usa. */
data class ResolvedPlace(
  val name: String,
  val region: String?,
  val latitude: Double,
  val longitude: Double,
  val snapshotKey: String,
  val placeId: Long?,
  val isSelected: Boolean,
  val isSaved: Boolean,
  val isGps: Boolean,
) {
  val label: String get() = if (region.isNullOrBlank()) name else "$name, $region"

  /** Le coordinate che viaggiano nel prompt: due decimali, ~1 km, come deciso per la privacy. */
  val roughCoordinates: String get() = "%.2f, %.2f".format(Locale.ROOT, latitude, longitude)
}

/**
 * "Qui", un posto salvato, un posto qualsiasi: il parametro `luogo` di quasi ogni tool passa
 * di qui. La localita' selezionata usa l'istantanea gia' in cache (zero rete); le salvate la
 * loro; un posto nuovo si cerca col geocoding e si scarica al volo **senza** seminare verifiche,
 * cosi' la pagella dei provider resta quella della vita vera dell'utente.
 */
class PlaceResolver(private val sources: AiDataSources) {

  suspend fun selected(): ResolvedPlace? {
    val places = sources.savedLocations.places.first()
    val selectedId = sources.selectedPlaceStore.current()
    val selected = places.firstOrNull { it.id == selectedId } ?: Place.gps()
    return resolvePlace(selected, isSelected = true)
  }

  suspend fun resolve(query: String?, selected: ResolvedPlace? = null): ResolvedPlace? {
    val text = query?.trim().orEmpty()
    if (text.isEmpty() || text.lowercase() in HERE_WORDS) return selected ?: selected()
    val places = sources.savedLocations.places.first()
    val selectedId = sources.selectedPlaceStore.current()
    val needle = text.lowercase()
    places.firstOrNull { !it.isGps && it.name.lowercase() == needle }
      ?.let { return resolvePlace(it, isSelected = it.id == selectedId) }
    places.firstOrNull { !it.isGps && (it.name.lowercase().contains(needle) || needle.contains(it.name.lowercase())) }
      ?.let { return resolvePlace(it, isSelected = it.id == selectedId) }
    val found = runCatching { sources.geocodingClient.search(text, language = Locale.getDefault().language) }
      .getOrDefault(emptyList())
      .firstOrNull() ?: return null
    return ResolvedPlace(
      name = found.name,
      region = found.region,
      latitude = found.latitude,
      longitude = found.longitude,
      snapshotKey = adHocKey(found.latitude, found.longitude),
      placeId = null,
      isSelected = false,
      isSaved = false,
      isGps = false,
    )
  }

  /** Il geocoding grezzo, per i tool che vogliono piu' risultati (cerca_luogo, salva_luogo). */
  suspend fun search(query: String, limit: Int = 5): List<Place> =
    runCatching { sources.geocodingClient.search(query, language = Locale.getDefault().language) }
      .getOrDefault(emptyList())
      .take(limit)

  /**
   * L'istantanea del posto: quella in cache se ha meno di [maxAgeMillis], altrimenti un giro
   * nuovo. Per i posti al volo il giro non registra previsioni da verificare.
   */
  suspend fun snapshot(place: ResolvedPlace, maxAgeMillis: Long = DEFAULT_MAX_AGE_MILLIS): WeatherSnapshot? {
    sources.snapshotRefresher.fresh(place.snapshotKey, place.latitude, place.longitude, maxAgeMillis)?.let { return it }
    return runCatching {
      sources.snapshotRefresher.refresh(
        placeKey = place.snapshotKey,
        latitude = place.latitude,
        longitude = place.longitude,
        registerPredictions = if (place.isSaved || place.isGps) null else false,
      )
    }.getOrNull() ?: sources.snapshotRefresher.fresh(place.snapshotKey, place.latitude, place.longitude, STALE_MAX_AGE_MILLIS)
  }

  private suspend fun resolvePlace(place: Place, isSelected: Boolean): ResolvedPlace? {
    if (place.isGps) {
      val here = sources.locationProvider.snapshot() ?: sources.snapshotStore.read(WeatherSnapshot.GPS_KEY)?.let {
        return ResolvedPlace(
          name = nameFor(it.latitude, it.longitude) ?: "posizione attuale",
          region = null,
          latitude = it.latitude,
          longitude = it.longitude,
          snapshotKey = WeatherSnapshot.GPS_KEY,
          placeId = Place.GPS_ID,
          isSelected = isSelected,
          isSaved = false,
          isGps = true,
        )
      } ?: return null
      return ResolvedPlace(
        name = nameFor(here.latitude, here.longitude) ?: "posizione attuale",
        region = null,
        latitude = here.latitude,
        longitude = here.longitude,
        snapshotKey = WeatherSnapshot.GPS_KEY,
        placeId = Place.GPS_ID,
        isSelected = isSelected,
        isSaved = false,
        isGps = true,
      )
    }
    return ResolvedPlace(
      name = place.name,
      region = place.region,
      latitude = place.latitude,
      longitude = place.longitude,
      snapshotKey = WeatherSnapshot.keyFor(place.id),
      placeId = place.id,
      isSelected = isSelected,
      isSaved = true,
      isGps = false,
    )
  }

  private suspend fun nameFor(latitude: Double, longitude: Double): String? =
    runCatching { sources.placeContext.resolve(latitude, longitude)?.locality }.getOrNull()

  companion object {
    val HERE_WORDS = setOf("qui", "qua", "here", "posizione attuale", "la mia posizione", "current location", "selezionata", "selected")
    const val DEFAULT_MAX_AGE_MILLIS: Long = 30 * 60_000L
    const val STALE_MAX_AGE_MILLIS: Long = 12 * 3_600_000L

    fun adHocKey(latitude: Double, longitude: Double): String =
      "ai-${(latitude * 100).roundToInt()}_${(longitude * 100).roundToInt()}"
  }
}
