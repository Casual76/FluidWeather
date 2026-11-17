package dev.pampa.fluidweather.core.data

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import dev.pampa.fluidweather.core.model.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.json.JSONArray
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Actual SQLite files from archived Room schemas, opened and validated by current Room. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class LocalLearningMigrationTest {
  private val context get() = RuntimeEnvironment.getApplication()
  private val name = "local-learning-migration.db"
  private val now = 1_800_000_000_000L
  private var room: FluidWeatherDatabase? = null

  @After fun close() {
    room?.close()
    context.deleteDatabase(name)
  }

  private fun archivedSchema(version: Int): JSONObject {
    val resource = "dev.pampa.fluidweather.core.data.FluidWeatherDatabase/$version.json"
    val stream = requireNotNull(javaClass.classLoader!!.getResourceAsStream(resource)) { resource }
    return stream.bufferedReader().use { JSONObject(it.readText()).getJSONObject("database") }
  }

  private fun Cursor.rows(): List<Map<String, String?>> = use {
    val columns = columnNames
    buildList { while (moveToNext()) add(columns.indices.associate { columns[it] to if (isNull(it)) null else getString(it) }) }
  }

  private fun seed(version: Int): Map<String, List<Map<String, String?>>> {
    context.deleteDatabase(name)
    val path = context.getDatabasePath(name)
    path.parentFile!!.mkdirs()
    val schema = archivedSchema(version)
    return SQLiteDatabase.openOrCreateDatabase(path, null).use { db ->
      val entities = schema.getJSONArray("entities")
      for (index in 0 until entities.length()) {
        val entity = entities.getJSONObject(index)
        val table = entity.getString("tableName")
        db.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
        val indices = entity.optJSONArray("indices") ?: JSONArray()
        for (i in 0 until indices.length()) {
          db.execSQL(indices.getJSONObject(i).getString("createSql").replace("\${TABLE_NAME}", table))
        }
        val values = ContentValues()
        val fields = entity.getJSONArray("fields")
        for (i in 0 until fields.length()) {
          val field = fields.getJSONObject(i)
          val column = field.getString("columnName")
          when (column) {
            "providerId" -> values.put(column, "open-meteo")
            "variable" -> values.put(column, FusionVariables.TEMPERATURE)
            "horizonBucket" -> values.put(column, HorizonBucket.SHORT.name)
            "latitude" -> values.put(column, 43.83)
            "longitude" -> values.put(column, 11.2)
            "issuedAtMillis" -> values.put(column, now - 3_600_000L)
            "targetTimestampMillis", "verifiedAtMillis", "timestampMillis" -> values.put(column, now)
            "zoneId" -> values.put(column, "Europe/Rome")
            else -> when (field.getString("affinity")) {
              "INTEGER" -> values.put(column, 42L)
              "REAL" -> values.put(column, 12.5)
              else -> values.put(column, "preserved-$table-$column")
            }
          }
        }
        assertTrue("seed $table", db.insertOrThrow(table, null, values) != -1L)
      }
      val setup = schema.getJSONArray("setupQueries")
      for (i in 0 until setup.length()) db.execSQL(setup.getString(i))
      db.version = version
      (0 until entities.length()).associate { index ->
        val table = entities.getJSONObject(index).getString("tableName")
        table to db.rawQuery("SELECT * FROM `$table`", null).rows()
      }
    }
  }

  private fun openAndCheck(before: Map<String, List<Map<String, String?>>>) {
    val opened = Room.databaseBuilder(context, FluidWeatherDatabase::class.java, name)
      .addMigrations(*ALL_MIGRATIONS).allowMainThreadQueries().build()
    room = opened
    val db = opened.openHelper.writableDatabase // Executes real migration and Room onValidateSchema.
    assertEquals(9, db.version)
    for ((table, oldRows) in before) {
      val columns = oldRows.first().keys
      val actual = db.query("SELECT ${columns.joinToString { "`$it`" }} FROM `$table`").rows()
      assertEquals("every archived value in $table", oldRows, actual)
    }
    db.query("SELECT latitude, longitude, semanticsVersion FROM forecast_verifications").use {
      assertTrue(it.moveToFirst())
      assertTrue(it.isNull(0))
      assertTrue(it.isNull(1))
      assertEquals(ForecastLearning.LEGACY_SEMANTICS, it.getString(2))
    }
    db.query("SELECT placeKey, semanticsVersion FROM pending_predictions").use {
      assertTrue(it.moveToFirst())
      assertEquals("legacy", it.getString(0))
      assertEquals(ForecastLearning.LEGACY_SEMANTICS, it.getString(1))
    }
  }

  @Test fun `8 to 9 preserves every old value and never invents judgment location`() {
    openAndCheck(seed(8))
    room!!.openHelper.writableDatabase.query("SELECT latitude, longitude FROM pending_predictions").use {
      assertTrue(it.moveToFirst())
      assertEquals(43.83, it.getDouble(0), 0.0)
      assertEquals(11.2, it.getDouble(1), 0.0)
    }
  }

  @Test fun `6 to 9 chain preserves all original tables and unknown pending coordinates`() {
    openAndCheck(seed(6))
    room!!.openHelper.writableDatabase.query("SELECT latitude, longitude FROM pending_predictions").use {
      assertTrue(it.moveToFirst())
      assertTrue(it.isNull(0))
      assertTrue(it.isNull(1))
    }
  }

  // Keep the method name short: Robolectric includes it in the native SQLite temp path on Windows.
  @Test fun daoKeepsPlacesAndVersionsDistinct() = runBlocking {
    openAndCheck(seed(8))
    val store = RoomVerificationStore(room!!.verificationDao())
    val a = PendingPrediction("open-meteo", FusionVariables.TEMPERATURE, now, 20.0, now - 3_600_000L,
      43.83, 11.2, semanticsVersion = ForecastLearning.CURRENT_SEMANTICS)
    val b = a.copy(latitude = 45.46, longitude = 9.19, placeKey = ForecastLearning.placeKey(45.46, 9.19))
    store.addPending(listOf(a, b))
    val pending = store.duePending(now)
    assertEquals(3, pending.size) // The original legacy row is still present too.
    assertEquals(a, pending.single { it.placeKey == a.placeKey })
    assertEquals(b, pending.single { it.placeKey == b.placeKey })
    store.removePending(listOf(a))
    assertEquals(setOf("legacy", b.placeKey), store.duePending(now).map { it.placeKey }.toSet())
    val judgment = ForecastVerification(a.providerId, a.variable, HorizonBucket.SHORT, 2.0, now,
      a.latitude, a.longitude, ForecastLearning.CURRENT_SEMANTICS)
    store.addVerifications(listOf(judgment))
    room!!.close()
    room = Room.databaseBuilder(context, FluidWeatherDatabase::class.java, name)
      .addMigrations(*ALL_MIGRATIONS).allowMainThreadQueries().build()
    val reopened = RoomVerificationStore(room!!.verificationDao())
    assertEquals(setOf("legacy", b.placeKey), reopened.duePending(now).map { it.placeKey }.toSet())
    assertEquals(judgment, reopened.allVerifications(0L).single { it.semanticsVersion == ForecastLearning.CURRENT_SEMANTICS })
    assertEquals(2, reopened.allVerifications(0L).size)
  }
}
