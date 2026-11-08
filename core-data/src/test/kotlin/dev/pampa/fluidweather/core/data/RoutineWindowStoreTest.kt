package dev.pampa.fluidweather.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import dev.pampa.fluidweather.core.model.RoutineWindow
import java.io.File
import java.io.IOException
import java.time.DayOfWeek
import java.time.LocalTime
import java.util.Base64
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test

class RoutineWindowStoreTest {
  private fun rule(id: String = "id") = RoutineWindow(id, "夜の散歩 🌧️ / caffè\n\"", setOf(DayOfWeek.MONDAY, DayOfWeek.SUNDAY),
    LocalTime.of(23, 17, 12, 123), LocalTime.of(1, 2, 3, 456))

  @Test fun `codec preserves unicode nanoseconds days overnight and ids`() {
    val rules = listOf(rule(), rule("second"))
    assertEquals(rules, RoutineWindowCodec.decode(RoutineWindowCodec.encode(rules)))
    assertEquals(emptyList<RoutineWindow>(), RoutineWindowCodec.decode(null))
  }

  @Test fun `codec rejects malformed unknown version duplicate and trailing data`() {
    val encoded = RoutineWindowCodec.encode(listOf(rule()))
    val bytes = Base64.getDecoder().decode(encoded)
    assertNull(RoutineWindowCodec.decode("not base64"))
    assertNull(RoutineWindowCodec.decode(Base64.getEncoder().encodeToString(bytes.dropLast(1).toByteArray())))
    assertNull(RoutineWindowCodec.decode(Base64.getEncoder().encodeToString(bytes + byteArrayOf(0))))
    val repeated = bytes + bytes.copyOfRange(8, bytes.size)
    repeated[7] = 2 // count=2 with the same complete record twice
    assertNull(RoutineWindowCodec.decode(Base64.getEncoder().encodeToString(repeated)))
    val badUtf8 = bytes.copyOf(); badUtf8[12] = 0xC3.toByte()
    assertNull(RoutineWindowCodec.decode(Base64.getEncoder().encodeToString(badUtf8)))
    bytes[3] = 2
    assertNull(RoutineWindowCodec.decode(Base64.getEncoder().encodeToString(bytes)))
    try { RoutineWindowCodec.encode(listOf(rule(), rule())); fail("duplicate") } catch (_: IllegalArgumentException) { }
  }

  @Test fun `empty real store survives concurrent edits and disk recreation`() = runBlocking {
    val file = File.createTempFile("routine-test", ".preferences_pb").also { it.delete() }
    var job = SupervisorJob()
    fun disk() = PreferenceDataStoreFactory.create(scope = CoroutineScope(job + Dispatchers.IO), produceFile = { file })
    try {
      val data = disk(); val store = RoutineWindowStore(data)
      assertTrue(store.current().isEmpty())
      coroutineScope { (1..30).map { async(Dispatchers.Default) { store.upsert(rule("$it")) } }.awaitAll() }
      assertEquals(30, store.current().size)
      store.upsert(rule("1").copy(name = "updated")); store.remove("2")
      val expected = store.current()
      job.cancelAndJoin(); job = SupervisorJob()
      val recreated = RoutineWindowStore(disk())
      assertEquals(expected, recreated.current())
      assertEquals("updated", recreated.current().single { it.id == "1" }.name)
      recreated.clear(); assertTrue(recreated.current().isEmpty())
    } finally { job.cancelAndJoin(); file.delete() }
  }

  @Test fun `unreadable version does not get overwritten by edit but clear recovers`() = runBlocking {
    val fake = MemoryStore()
    fake.value.value = mutablePreferencesOf(stringPreferencesKey("rules") to "future")
    val store = RoutineWindowStore(fake)
    assertTrue(store.current().isEmpty())
    try { store.upsert(rule()); fail("must expose write failure") } catch (_: IOException) { }
    assertEquals("future", fake.value.value[stringPreferencesKey("rules")])
    store.clear(); store.upsert(rule()); assertEquals(listOf(rule()), store.current())
  }

  @Test fun `read cancellation write cancellation and IO failures propagate`() = runBlocking {
    val cancel = object : DataStore<Preferences> {
      override val data: Flow<Preferences> = flow { throw CancellationException("cancelled") }
      override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences = throw CancellationException("cancelled")
    }
    try { RoutineWindowStore(cancel).current(); fail("cancelled read") } catch (_: CancellationException) { }
    try { RoutineWindowStore(cancel).upsert(rule()); fail("cancelled write") } catch (_: CancellationException) { }
    val failing = object : DataStore<Preferences> {
      override val data = flowOf(emptyPreferences())
      override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences = throw IOException("disk full")
    }
    try { RoutineWindowStore(failing).upsert(rule()); fail("disk failure") } catch (_: IOException) { }
  }

  private class MemoryStore : DataStore<Preferences> {
    val value = MutableStateFlow<Preferences>(emptyPreferences())
    override val data: Flow<Preferences> = value
    override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences = transform(value.value).also { value.value = it }
  }
}
