package dev.pampa.fluidweather.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import dev.pampa.fluidweather.core.model.PlattMapRecord
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Le mappe di Platt su disco, provate sul computer con un DataStore vero su un file temporaneo.
 */
class LearningStoreTest {

  private lateinit var file: File
  private lateinit var scope: CoroutineScope
  private lateinit var dataStore: DataStore<Preferences>
  private lateinit var store: LearningStore

  @Before
  fun setUp() {
    file = File.createTempFile("learning-test", ".preferences_pb").also { it.delete() }
    scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    dataStore = PreferenceDataStoreFactory.create(scope = scope, produceFile = { file })
    store = LearningStore(dataStore)
  }

  @After
  fun tearDown() {
    scope.cancel()
    file.delete()
  }

  private fun record(
    variant: String,
    window: String,
    active: Boolean = true,
    a: Double? = 1.25,
    b: Double? = -0.5,
    at: Long = 1_000L,
  ) = PlattMapRecord(
    variant = variant,
    window = window,
    a = a,
    b = b,
    samples = 240,
    wet = 60,
    dry = 180,
    status = if (active) "ACTIVE" else "NO_OUT_OF_SAMPLE_GAIN",
    active = active,
    guardDeltaBrier = -0.004,
    guardUpperBound = -0.001,
    guardTestDays = 12,
    fittedAtMillis = at,
  )

  @Test
  fun `un archivio vuoto non ha mappe, ne' versione`() = runBlocking {
    val maps = store.snapshot()
    assertNull(maps.version)
    assertEquals(0L, maps.lastFitMillis)
    assertTrue(maps.records.isEmpty())
  }

  @Test
  fun `le mappe tornano come sono state scritte, con la diagnosi`() = runBlocking {
    val written = listOf(
      record("fresh", "0-1h"),
      record("none", "3-6h", active = false),
      record("stale", "1-3h", active = false, a = null, b = null),
    )
    store.replaceAll("v-uno", written, fittedAtMillis = 5_000L)

    val maps = store.snapshot()
    assertEquals("v-uno", maps.version)
    assertEquals(5_000L, maps.lastFitMillis)
    assertEquals(5_000L, store.lastFitMillis())
    // Nell'ordine fisso del lettore: variante, poi finestra.
    assertEquals(listOf("fresh" to "0-1h", "stale" to "1-3h", "none" to "3-6h"), maps.records.map { it.variant to it.window })
    assertEquals(written.toSet(), maps.records.toSet())
  }

  @Test
  fun `salvare sostituisce tutto, e una finestra non ristimata perde la sua vecchia mappa`() = runBlocking {
    store.replaceAll("v", listOf(record("fresh", "0-1h"), record("fresh", "1-3h"), record("none", "0-1h")), 1L)
    store.replaceAll("v", listOf(record("fresh", "0-1h", a = 0.9, b = 0.1)), 2L)

    val maps = store.snapshot()
    assertEquals(listOf("fresh" to "0-1h"), maps.records.map { it.variant to it.window })
    assertEquals(0.9, maps.records.single().a!!, 0.0)
    assertEquals(2L, maps.lastFitMillis)
  }

  @Test
  fun `una versione diversa azzera tutto, comprese le vecchie chiavi di prima delle varianti`() = runBlocking {
    // Lo stato di 1.4.5: una mappa per finestra con le chiavi platt_a_<finestra>, nessuna versione.
    dataStore.edit {
      it[doublePreferencesKey("platt_a_0-1h")] = 1.4
      it[doublePreferencesKey("platt_b_0-1h")] = -0.7
    }
    store.replaceAll("vecchia", listOf(record("fresh", "0-1h")), 9L)
    dataStore.edit { it[doublePreferencesKey("platt_a_0-1h")] = 1.4 }

    val cleared = store.clearIfVersionMismatch("nuova")

    assertTrue(cleared)
    val maps = store.snapshot()
    assertEquals("nuova", maps.version)
    assertTrue(maps.records.isEmpty())
    assertEquals(0L, maps.lastFitMillis)
    val raw = dataStore.data.first()
    assertFalse(raw.asMap().keys.any { it.name.startsWith("platt_a_") || it.name.startsWith("platt_b_") })
  }

  @Test
  fun `la versione assente conta come diversa`() = runBlocking {
    dataStore.edit { it[doublePreferencesKey("platt_a_0-1h")] = 1.4 }
    assertTrue(store.clearIfVersionMismatch("v"))
    assertFalse(dataStore.data.first().asMap().keys.any { it.name == "platt_a_0-1h" })
  }

  @Test
  fun `con la stessa versione non si tocca niente`() = runBlocking {
    store.replaceAll("v", listOf(record("fresh", "0-1h")), 7L)

    assertFalse(store.clearIfVersionMismatch("v"))

    val maps = store.snapshot()
    assertEquals(1, maps.records.size)
    assertEquals(7L, maps.lastFitMillis)
  }

  @Test
  fun `clear cancella anche la versione`() = runBlocking {
    store.replaceAll("v", listOf(record("fresh", "0-1h")), 7L)
    store.clear()
    val maps = store.snapshot()
    assertNull(maps.version)
    assertTrue(maps.records.isEmpty())
  }
}
