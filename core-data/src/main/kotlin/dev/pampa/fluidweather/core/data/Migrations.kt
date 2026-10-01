package dev.pampa.fluidweather.core.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * 6 -> 7: la verifica onesta della pioggia.
 *
 * E' la prima migrazione del database, e fa due sole cose: **crea** le due tabelle nuove della
 * pioggia (`rain_event_pending`, `rain_event_verifications`) e **aggiunge** colonne a tre tabelle
 * esistenti. Non cancella e non riscrive niente: le letture di pressione, le verifiche dei
 * provider, i pesi della fusione, la classifica generale, lo storico dei verdetti e l'archivio
 * dell'apprendimento sono esattamente quelli di prima. Le righe di pioggia vecchie (quelle giudicate
 * contro un consenso di provider, una verita' circolare) restano dove sono: semplicemente nessuno
 * le legge piu'.
 *
 * Il testo SQL e' quello che Room ha scritto in `schemas/.../7.json`, parola per parola: e' quello
 * con cui Room confronta il database al primo apertura (`onValidateSchema`), e una differenza
 * anche solo di un default fa fallire l'apertura dell'app di chi aggiorna. `MigrationTest` lo
 * verifica contro la fotografia, su un emulatore, a ogni modifica.
 *
 * Le colonne aggiunte hanno un default o sono nullable, perche' SQLite non aggiunge una colonna
 * NOT NULL a una tabella piena senza un valore per le righe che ci sono gia':
 * - `pending_predictions.latitude/longitude`: nullable, le righe di prima non hanno un punto;
 * - `nowcast_issues.modelVersion`: "legacy", `tier`: nullable, `roundId`: 0;
 * - `nowcast_verdicts.variant`: "ind", cioe' il verdetto indipendente, che e' tutto lo storico.
 */
val MIGRATION_6_7: Migration = object : Migration(6, 7) {
  override fun migrate(db: SupportSQLiteDatabase) {
    db.execSQL(
      "CREATE TABLE IF NOT EXISTS `rain_event_pending` (`placeKey` TEXT NOT NULL, `latitude` REAL NOT NULL, " +
        "`longitude` REAL NOT NULL, `roundId` INTEGER NOT NULL, `providerId` TEXT NOT NULL, " +
        "`windowLabel` TEXT NOT NULL, `issuedAtMillis` INTEGER NOT NULL, `probability` REAL NOT NULL, " +
        "`modelVersion` TEXT NOT NULL, `tier` TEXT, PRIMARY KEY(`placeKey`, `roundId`, `providerId`, `windowLabel`))",
    )
    db.execSQL(
      "CREATE INDEX IF NOT EXISTS `index_rain_event_pending_issuedAtMillis` ON `rain_event_pending` (`issuedAtMillis`)",
    )
    db.execSQL(
      "CREATE TABLE IF NOT EXISTS `rain_event_verifications` (`placeKey` TEXT NOT NULL, `latitude` REAL NOT NULL, " +
        "`longitude` REAL NOT NULL, `roundId` INTEGER NOT NULL, `providerId` TEXT NOT NULL, " +
        "`windowLabel` TEXT NOT NULL, `issuedAtMillis` INTEGER NOT NULL, `probability` REAL NOT NULL, " +
        "`modelVersion` TEXT NOT NULL, `tier` TEXT, `outcome` INTEGER NOT NULL, `truthSumMm` REAL, " +
        "`truthVoters` INTEGER NOT NULL, `truthSource` TEXT NOT NULL, `settledAtMillis` INTEGER NOT NULL, " +
        "PRIMARY KEY(`placeKey`, `roundId`, `providerId`, `windowLabel`))",
    )
    db.execSQL(
      "CREATE INDEX IF NOT EXISTS `index_rain_event_verifications_modelVersion_issuedAtMillis` " +
        "ON `rain_event_verifications` (`modelVersion`, `issuedAtMillis`)",
    )
    db.execSQL(
      "CREATE INDEX IF NOT EXISTS `index_rain_event_verifications_issuedAtMillis` " +
        "ON `rain_event_verifications` (`issuedAtMillis`)",
    )
    db.execSQL("ALTER TABLE `pending_predictions` ADD COLUMN `latitude` REAL")
    db.execSQL("ALTER TABLE `pending_predictions` ADD COLUMN `longitude` REAL")
    db.execSQL("ALTER TABLE `nowcast_issues` ADD COLUMN `modelVersion` TEXT NOT NULL DEFAULT 'legacy'")
    db.execSQL("ALTER TABLE `nowcast_issues` ADD COLUMN `tier` TEXT")
    db.execSQL("ALTER TABLE `nowcast_issues` ADD COLUMN `roundId` INTEGER NOT NULL DEFAULT 0")
    db.execSQL("ALTER TABLE `nowcast_verdicts` ADD COLUMN `variant` TEXT NOT NULL DEFAULT 'ind'")
  }
}

/** Tutte le migrazioni, nell'ordine: e' quello che `FluidWeatherDatabase.build` passa a Room. */
val ALL_MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_6_7)
