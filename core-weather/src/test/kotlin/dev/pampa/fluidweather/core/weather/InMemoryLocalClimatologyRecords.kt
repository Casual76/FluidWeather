package dev.pampa.fluidweather.core.weather

import dev.pampa.fluidweather.core.model.LocalClimatologyRecord
import dev.pampa.fluidweather.core.model.LocalClimatologyRecords
import dev.pampa.fluidweather.core.model.MaintenanceThrottle

/**
 * Le climatologie locali in memoria dei test, con la semantica del file su disco: una riuscita
 * (`put`) cancella la memoria del fallimento, e `touch` non inventa una cella che non c'e'.
 */
class InMemoryLocalClimatologyRecords : LocalClimatologyRecords {

  val records = linkedMapOf<String, LocalClimatologyRecord>()
  val failures = mutableMapOf<String, Long>()

  override suspend fun get(cellKey: String): LocalClimatologyRecord? = records[cellKey]

  override suspend fun all(): List<LocalClimatologyRecord> = records.values.toList()

  override suspend fun put(record: LocalClimatologyRecord) {
    records[record.cellKey] = record
    failures.remove(record.cellKey)
  }

  override suspend fun remove(cellKey: String) {
    records.remove(cellKey)
    failures.remove(cellKey)
  }

  override suspend fun touch(cellKey: String, usedAtMillis: Long) {
    records[cellKey]?.let { records[cellKey] = it.copy(usedAtMillis = usedAtMillis) }
  }

  override suspend fun lastFailureMillis(cellKey: String): Long? = failures[cellKey]

  override suspend fun markFailure(cellKey: String, atMillis: Long) {
    failures[cellKey] = atMillis
  }

  override suspend fun clear() {
    records.clear()
    failures.clear()
  }
}

/** Il freno della manutenzione in memoria dei test. */
class InMemoryThrottle(var last: Long? = null) : MaintenanceThrottle {

  var marks = 0

  override suspend fun lastRunMillis(): Long? = last

  override suspend fun markRun(atMillis: Long) {
    last = atMillis
    marks++
  }
}
