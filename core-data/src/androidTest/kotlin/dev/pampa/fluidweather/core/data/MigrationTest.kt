package dev.pampa.fluidweather.core.data

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * La migrazione 6 -> 7 su un database vero, con dentro le righe di chi aggiorna da 1.4.5.
 *
 * E' il cancello di tutta la verifica onesta della pioggia: se una riga di barometro sparisce, se
 * uno storico cambia significato o se l'app non riesce ad aprire il database, e' qui che si
 * scopre — e non dal telefono di qualcuno. `MigrationTestHelper` legge le fotografie dello schema
 * (`schemas/.../6.json` e `7.json`), quindi controlla anche che la migrazione scritta a mano
 * produca lo schema che Room si aspetta, colonna per colonna e default per default.
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {

  @get:Rule
  val helper = MigrationTestHelper(
    InstrumentationRegistry.getInstrumentation(),
    FluidWeatherDatabase::class.java,
  )

  private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

  @After
  fun pulisci() {
    context.deleteDatabase(DB)
    context.deleteDatabase(DB_ROOM_OPEN)
  }

  /** Un archivio 1.4.5 verosimile: una riga per ogni tabella che la migrazione potrebbe rovinare. */
  private fun seedV6(db: SupportSQLiteDatabase) {
    val now = 1_781_517_600_000L
    // Tre letture di pressione: l'archivio che non deve perdere nessuno.
    for (i in 0 until 3) {
      db.execSQL(
        "INSERT INTO pressure_samples (timestampMillis, pressureHpa, source, burstId, altitudeMeters, latitude, " +
          "longitude, activity, activityConfidence) VALUES (?, ?, 'SURVEILLANCE', NULL, 41.0, 43.83, 11.2, 'STILL', 90)",
        arrayOf<Any?>(now + i * 60_000L, 1013.2 - i * 0.1),
      )
    }
    // Una previsione della fusione in attesa, e due verifiche: una di vecchia specie del barometro.
    db.execSQL(
      "INSERT INTO pending_predictions (providerId, variable, targetTimestampMillis, predictedValue, issuedAtMillis) " +
        "VALUES ('open-meteo', 'temperature', ?, 21.5, ?)",
      arrayOf<Any?>(now + 3_600_000L, now),
    )
    db.execSQL(
      "INSERT INTO forecast_verifications (providerId, variable, horizonBucket, absoluteError, verifiedAtMillis) " +
        "VALUES ('barometro', 'rain_event_1_3', 'SHORT', 0.3, ?)",
      arrayOf<Any?>(now),
    )
    db.execSQL(
      "INSERT INTO forecast_verifications (providerId, variable, horizonBucket, absoluteError, verifiedAtMillis) " +
        "VALUES ('open-meteo', 'temperature', 'SHORT', 0.8, ?)",
      arrayOf<Any?>(now),
    )
    db.execSQL(
      "INSERT INTO saved_locations (id, name, region, latitude, longitude, sortOrder) " +
        "VALUES (1, 'Sesto', 'Toscana', 43.83, 11.2, 0)",
    )
    db.execSQL(
      "INSERT INTO observations (timestampMillis, condition, latitude, longitude, placeName) " +
        "VALUES (?, 'RAIN', 43.83, 11.2, 'Sesto')",
      arrayOf<Any?>(now),
    )
    db.execSQL(
      "INSERT INTO nowcast_verdicts (timestampMillis, probability01, probability13, probability36, level) " +
        "VALUES (?, 0.1, 0.2, 0.3, 'QUIETE')",
      arrayOf<Any?>(now),
    )
    db.execSQL(
      "INSERT INTO nowcast_issues (issuedAtMillis, features, rawProbability01, rawProbability13, rawProbability36) " +
        "VALUES (?, '0.5,NaN,1.25', 0.1, 0.2, 0.3)",
      arrayOf<Any?>(now),
    )
    db.execSQL(
      "INSERT INTO nowcast_outcomes (issuedAtMillis, window, rained) VALUES (?, '1-3h', 1)",
      arrayOf<Any?>(now),
    )
  }

  private fun SupportSQLiteDatabase.count(table: String): Int =
    query("SELECT COUNT(*) FROM `$table`").use {
      it.moveToFirst()
      it.getInt(0)
    }

  @Test
  fun la_migrazione_6_a_7_tiene_ogni_riga_e_da_i_default_alle_colonne_nuove() {
    helper.createDatabase(DB, 6).use { seedV6(it) }

    val db = helper.runMigrationsAndValidate(DB, 7, true, MIGRATION_6_7)

    // Niente si perde: ne' le letture, ne' i giudizi dei provider, ne' quelli (di vecchia specie) del barometro.
    assertEquals(3, db.count("pressure_samples"))
    assertEquals(1, db.count("pending_predictions"))
    assertEquals(2, db.count("forecast_verifications"))
    assertEquals(1, db.count("saved_locations"))
    assertEquals(1, db.count("observations"))
    assertEquals(1, db.count("nowcast_verdicts"))
    assertEquals(1, db.count("nowcast_issues"))
    assertEquals(1, db.count("nowcast_outcomes"))
    db.query("SELECT COUNT(*) FROM forecast_verifications WHERE providerId = 'barometro' AND variable = 'rain_event_1_3'")
      .use {
        it.moveToFirst()
        assertEquals("la verifica di vecchia specie resta", 1, it.getInt(0))
      }
    db.query("SELECT MIN(pressureHpa), MAX(pressureHpa) FROM pressure_samples").use {
      it.moveToFirst()
      assertEquals(1013.0, it.getDouble(0), 1e-9)
      assertEquals(1013.2, it.getDouble(1), 1e-9)
    }

    // I default: le emissioni di prima sono "legacy", senza livello, giro 0.
    db.query("SELECT modelVersion, tier, roundId, features FROM nowcast_issues").use {
      assertTrue(it.moveToFirst())
      assertEquals("legacy", it.getString(0))
      assertTrue(it.isNull(1))
      assertEquals(0L, it.getLong(2))
      assertEquals("0.5,NaN,1.25", it.getString(3))
    }
    // I verdetti di prima sono quelli indipendenti.
    db.query("SELECT variant, level FROM nowcast_verdicts").use {
      assertTrue(it.moveToFirst())
      assertEquals("ind", it.getString(0))
      assertEquals("QUIETE", it.getString(1))
    }
    // Le pendenti di prima non hanno un punto: si giudicheranno come sempre finche' non scadono.
    db.query("SELECT latitude, longitude, predictedValue FROM pending_predictions").use {
      assertTrue(it.moveToFirst())
      assertTrue(it.isNull(0))
      assertTrue(it.isNull(1))
      assertEquals(21.5, it.getDouble(2), 1e-9)
    }

    // Le tabelle della pioggia sono vuote e accettano una riga (colonne nullable comprese).
    assertEquals(0, db.count("rain_event_pending"))
    assertEquals(0, db.count("rain_event_verifications"))
    db.execSQL(
      "INSERT INTO rain_event_pending (placeKey, latitude, longitude, roundId, providerId, windowLabel, issuedAtMillis, " +
        "probability, modelVersion, tier) VALUES ('gps', 43.83, 11.2, 1, 'barometro', '1-3h', 1, 0.4, 'v', NULL)",
    )
    db.execSQL(
      "INSERT INTO rain_event_verifications (placeKey, latitude, longitude, roundId, providerId, windowLabel, " +
        "issuedAtMillis, probability, modelVersion, tier, outcome, truthSumMm, truthVoters, truthSource, settledAtMillis) " +
        "VALUES ('gps', 43.83, 11.2, 1, 'barometro', '1-3h', 1, 0.4, 'v', 'FRESH', 1, NULL, 3, 'panel-1', 2)",
    )
    assertEquals(1, db.count("rain_event_pending"))
    assertEquals(1, db.count("rain_event_verifications"))
    // E una scrittura sulle colonne aggiunte alle tabelle vecchie.
    db.execSQL(
      "INSERT INTO pending_predictions (providerId, variable, targetTimestampMillis, predictedValue, issuedAtMillis, " +
        "latitude, longitude) VALUES ('open-meteo', 'pressure_msl', 5, 1012.0, 1, 43.83, 11.2)",
    )
    db.execSQL(
      "INSERT INTO nowcast_verdicts (timestampMillis, probability01, probability13, probability36, level, variant) " +
        "VALUES (9, 0.1, 0.1, 0.1, 'QUIETE', 'solo')",
    )
    db.close()
  }

  @Test
  fun la_chiave_primaria_delle_pendenti_della_pioggia_rifiuta_il_doppione() {
    helper.createDatabase(DB, 6).close()
    val db = helper.runMigrationsAndValidate(DB, 7, true, MIGRATION_6_7)
    val insert = "INSERT INTO rain_event_pending (placeKey, latitude, longitude, roundId, providerId, windowLabel, " +
      "issuedAtMillis, probability, modelVersion, tier) VALUES ('gps', 43.83, 11.2, 1, 'barometro', '1-3h', 1, 0.4, 'v', NULL)"
    db.execSQL(insert)

    val rifiutato = runCatching { db.execSQL(insert) }.isFailure

    assertTrue("la chiave (posto, giro, id, finestra) e' unica", rifiutato)
    db.close()
  }

  @Test
  fun la_migrazione_non_serve_se_il_database_e_gia_alla_7_e_le_tabelle_nuove_sono_vuote() {
    // Un database creato direttamente alla 7 (installazione nuova) ha lo stesso schema di uno
    // migrato: se le due strade divergessero, una delle due aprirebbe e l'altra no.
    val fresco = helper.createDatabase(DB, 7)
    assertEquals(0, fresco.count("rain_event_pending"))
    assertEquals(0, fresco.count("rain_event_verifications"))
    fresco.close()
  }

  @Test
  fun il_database_si_apre_con_Room_vero_dopo_la_migrazione_e_gli_archivi_di_prima_restano_leggibili() = runBlocking {
    // createDatabase scrive il file come lo avrebbe lasciato la 1.4.5; poi lo apre Room, con lo
    // stesso builder dell'app, cioe' con onValidateSchema contro lo schema generato.
    helper.createDatabase(DB_ROOM_OPEN, 6).use { seedV6(it) }

    val database = Room.databaseBuilder(context, FluidWeatherDatabase::class.java, DB_ROOM_OPEN)
      .addMigrations(*ALL_MIGRATIONS)
      .build()
    try {
      assertEquals(0, database.rainEventDao().pendingCount())
      assertEquals(0, database.rainEventDao().verificationCount())
      assertEquals(1, database.learningDao().outcomeCount())

      val emissioni = database.learningDao().issuesSince(0)
      assertEquals(1, emissioni.size)
      assertEquals("legacy", emissioni[0].modelVersion)
      assertNull(emissioni[0].tier)
      assertEquals(0L, emissioni[0].roundId)

      val verdetti = database.nowcastHistoryDao().since(0)
      assertEquals(1, verdetti.size)
      assertEquals("ind", verdetti[0].variant)

      val pendenti = database.verificationDao().duePending(Long.MAX_VALUE)
      assertEquals(1, pendenti.size)
      assertNull(pendenti[0].latitude)
      assertNull(pendenti[0].longitude)

      assertEquals(3, database.pressureDao().samplesSince(0).size)
      assertEquals(1, database.verificationDao().verificationsFor("rain_event_1_3", "SHORT").size)
    } finally {
      database.close()
    }
  }

  private companion object {
    const val DB = "migration-test"
    const val DB_ROOM_OPEN = "migration-test-room-open"
  }
}
