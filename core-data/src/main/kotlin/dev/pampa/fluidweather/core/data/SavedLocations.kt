package dev.pampa.fluidweather.core.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import dev.pampa.fluidweather.core.model.Place
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map

/** Una localita' salvata: l'id e' quello del geocoder, cosi' i doppioni non esistono. */
@Entity(tableName = "saved_locations")
data class SavedLocationEntity(
  @PrimaryKey val id: Long,
  val name: String,
  val region: String?,
  val latitude: Double,
  val longitude: Double,
  val sortOrder: Int,
)

@Dao
interface SavedLocationsDao {

  @Query("SELECT * FROM saved_locations ORDER BY sortOrder ASC")
  fun all(): Flow<List<SavedLocationEntity>>

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun upsert(location: SavedLocationEntity)

  @Query("DELETE FROM saved_locations WHERE id = :id")
  suspend fun remove(id: Long)

  @Query("SELECT COALESCE(MAX(sortOrder), -1) + 1 FROM saved_locations")
  suspend fun nextSortOrder(): Int
}

/** Le localita' dell'utente piu' la voce GPS in testa, come [Place] di dominio. */
class SavedLocationsRepository(private val dao: SavedLocationsDao) {

  val places: Flow<List<Place>> = dao.all().map { entities ->
    listOf(Place.gps()) + entities.map { it.toPlace() }
  }

  suspend fun save(place: Place) {
    if (place.isGps) return
    dao.upsert(
      SavedLocationEntity(
        id = place.id,
        name = place.name,
        region = place.region,
        latitude = place.latitude,
        longitude = place.longitude,
        sortOrder = dao.nextSortOrder(),
      ),
    )
  }

  suspend fun remove(placeId: Long) {
    if (placeId == Place.GPS_ID) return
    dao.remove(placeId)
  }

  private fun SavedLocationEntity.toPlace() =
    Place(id = id, name = name, region = region, latitude = latitude, longitude = longitude)
}

/**
 * Quale posto sta mostrando la home. Di SESSIONE, non persistito: all'apertura dell'app si
 * riparte sempre dalla posizione attuale, e la scelta vive finche' vive il processo. Era su
 * DataStore, e riaprire l'app su una citta' salvata invece che su dov'era il telefono e' stata
 * la prima cosa notata sul device (2026-09-02).
 */
class SelectedPlaceStore {

  private val state = MutableStateFlow(Place.GPS_ID)

  val selectedId: StateFlow<Long> = state.asStateFlow()

  fun current(): Long = state.value

  fun select(placeId: Long) {
    state.value = placeId
  }
}
