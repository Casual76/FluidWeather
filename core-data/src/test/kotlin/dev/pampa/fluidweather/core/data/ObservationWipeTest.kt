package dev.pampa.fluidweather.core.data

import dev.pampa.fluidweather.core.model.ObservedCondition
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ObservationWipeTest {
  @Test fun acquiredObservationCannotRepopulateArchive() = runBlocking {
    val guard = DataWipeGuard()
    val rows = mutableListOf<ObservationEntity>()
    val store = ObservationRepository(object : ObservationDao {
      override suspend fun insert(observation: ObservationEntity): Long {
        rows.add(observation)
        return rows.size.toLong()
      }
      override fun recent(limit: Int) = flowOf(rows.takeLast(limit))
      override suspend fun since(sinceMillis: Long) = rows.filter { it.timestampMillis >= sinceMillis }
      override suspend fun delete(id: Long) { rows.removeAll { it.id == id } }
      override suspend fun deleteAll() { rows.clear() }
    }, guard)
    val condition = ObservedCondition.entries.first()
    store.record(condition, 10, 43.8, 11.2, "Old city")
    val entered = CompletableDeferred<Unit>()
    val resume = CompletableDeferred<Unit>()
    val request = async {
      runCatching {
        withDataWipeGeneration(guard) {
          val oldLatitude = store.since(0).single().latitude
          entered.complete(Unit)
          resume.await()
          store.record(condition, 20, oldLatitude, 11.2, "Old city")
        }
      }
    }
    entered.await()
    guard.wipe { store.clear() }
    resume.complete(Unit)
    assertTrue(request.await().exceptionOrNull() is DataWipeInvalidatedException)
    assertTrue(rows.isEmpty())
    store.record(condition, 30, 45.0, 9.0, "New city")
    assertEquals("New city", store.since(0).single().placeName)
  }
}
