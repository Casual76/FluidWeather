package dev.pampa.fluidweather.core.data

import dev.pampa.fluidweather.core.model.Place
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SavedLocationBackfillTest {
  @Test fun metadataWriteFailureDoesNotBlockTheForecastCaller() = runBlocking {
    val repository = SavedLocationsRepository(FakeDao { throw IllegalStateException("storage unavailable") })
    assertFalse(repository.backfillZoneId(42, "Europe/Rome"))
  }

  @Test fun cancelledPlaceLoadIsNotConvertedToAMetadataFailure() {
    val repository = SavedLocationsRepository(FakeDao { throw CancellationException("obsolete place") })
    val failure = runCatching { runBlocking { repository.backfillZoneId(42, "Europe/Rome") } }.exceptionOrNull()
    assertTrue(failure is CancellationException)
  }

  @Test fun invalidZoneAndGpsDoNotAttemptAWrite() = runBlocking {
    var writes = 0
    val repository = SavedLocationsRepository(FakeDao { writes++; 1 })
    assertFalse(repository.backfillZoneId(42, "invalid"))
    assertFalse(repository.backfillZoneId(Place.GPS_ID, "Europe/Rome"))
    assertTrue(repository.backfillZoneId(42, "Europe/Rome"))
    assertTrue(writes == 1)
  }

  private class FakeDao(val write: () -> Int) : SavedLocationsDao {
    override fun all() = flowOf(emptyList<SavedLocationEntity>())
    override suspend fun upsert(location: SavedLocationEntity) = Unit
    override suspend fun backfillZoneId(id: Long, zoneId: String) = write()
    override suspend fun remove(id: Long) = Unit
    override suspend fun nextSortOrder() = 0
  }
}
