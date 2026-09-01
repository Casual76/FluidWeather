package dev.pampa.fluidweather.core.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import dev.pampa.fluidweather.core.model.Place
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
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

/** Quale posto sta mostrando la home. GPS di default; sopravvive al riavvio. */
class SelectedPlaceStore(private val context: Context) {

  val selectedId: Flow<Long> = context.fluidWeatherStore.data.map { preferences ->
    preferences[Key] ?: Place.GPS_ID
  }

  suspend fun current(): Long = selectedId.first()

  suspend fun select(placeId: Long) {
    context.fluidWeatherStore.edit { it[Key] = placeId }
  }

  private companion object {
    val Key = longPreferencesKey("selected_place_id")
  }
}
