package dev.pampa.fluidweather.core.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.pampa.fluidweather.core.model.NotificationChannelKind
import dev.pampa.fluidweather.core.model.NotificationLedger
import dev.pampa.fluidweather.core.model.NotificationSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.notificationStore: DataStore<Preferences> by preferencesDataStore(name = "notifications")

/** Le scelte sui canali; i default sono quelli di [NotificationChannelKind]. */
class NotificationSettingsStore(private val context: Context) {

  val settings: Flow<NotificationSettings> = context.notificationStore.data.map { preferences ->
    NotificationSettings(
      nowcastAlert = preferences[Keys.NowcastAlert] ?: NotificationChannelKind.NOWCAST_ALERT.defaultEnabled,
      precipitation = preferences[Keys.Precipitation] ?: NotificationChannelKind.PRECIPITATION.defaultEnabled,
      officialAlerts = preferences[Keys.OfficialAlerts] ?: NotificationChannelKind.OFFICIAL_ALERTS.defaultEnabled,
      dailySummary = preferences[Keys.DailySummary] ?: NotificationChannelKind.DAILY_SUMMARY.defaultEnabled,
      summaryHour = preferences[Keys.SummaryHour] ?: 7,
      summaryMinute = preferences[Keys.SummaryMinute] ?: 30,
    )
  }

  suspend fun current(): NotificationSettings = settings.first()

  suspend fun setEnabled(kind: NotificationChannelKind, enabled: Boolean) {
    context.notificationStore.edit { it[Keys.of(kind)] = enabled }
  }

  suspend fun setSummaryTime(hour: Int, minute: Int) {
    context.notificationStore.edit {
      it[Keys.SummaryHour] = hour.coerceIn(0, 23)
      it[Keys.SummaryMinute] = minute.coerceIn(0, 59)
    }
  }

  private object Keys {
    val NowcastAlert = booleanPreferencesKey("channel_nowcast_alert")
    val Precipitation = booleanPreferencesKey("channel_precipitation")
    val OfficialAlerts = booleanPreferencesKey("channel_official_alerts")
    val DailySummary = booleanPreferencesKey("channel_daily_summary")
    val SummaryHour = intPreferencesKey("summary_hour")
    val SummaryMinute = intPreferencesKey("summary_minute")

    fun of(kind: NotificationChannelKind) = when (kind) {
      NotificationChannelKind.NOWCAST_ALERT -> NowcastAlert
      NotificationChannelKind.PRECIPITATION -> Precipitation
      NotificationChannelKind.OFFICIAL_ALERTS -> OfficialAlerts
      NotificationChannelKind.DAILY_SUMMARY -> DailySummary
    }
  }
}

/**
 * Il registro di cio' che e' gia' stato detto. [update] e' atomico: il ciclo legge, decide e
 * scrive dentro una sola transazione, cosi' due passate ravvicinate non si pestano i piedi.
 */
class NotificationLedgerStore(private val context: Context) {

  val ledger: Flow<NotificationLedger> = context.notificationStore.data.map { it.toLedger() }

  suspend fun current(): NotificationLedger = ledger.first()

  suspend fun update(transform: (NotificationLedger) -> NotificationLedger) {
    context.notificationStore.edit { preferences ->
      preferences.write(transform(preferences.toLedger()))
    }
  }

  private fun Preferences.toLedger() = NotificationLedger(
    nowcastAlertAtMillis = this[Keys.NowcastAt],
    nowcastAlertProbability = this[Keys.NowcastProbability],
    nowcastAlertActive = this[Keys.NowcastActive] ?: false,
    precipitationOnsetMillis = this[Keys.PrecipOnset],
    precipitationEndMillis = this[Keys.PrecipEnd],
    officialAlertIds = this[Keys.OfficialIds]?.split('\n')?.filter { it.isNotBlank() } ?: emptyList(),
    summaryEpochDay = this[Keys.SummaryDay],
    lastCycleAtMillis = this[Keys.CycleAt],
    lastCycleNote = this[Keys.CycleNote],
  )

  private fun MutablePreferences.write(ledger: NotificationLedger) {
    fun <T> set(key: Preferences.Key<T>, value: T?) {
      if (value == null) remove(key) else this[key] = value
    }
    set(Keys.NowcastAt, ledger.nowcastAlertAtMillis)
    set(Keys.NowcastProbability, ledger.nowcastAlertProbability)
    this[Keys.NowcastActive] = ledger.nowcastAlertActive
    set(Keys.PrecipOnset, ledger.precipitationOnsetMillis)
    set(Keys.PrecipEnd, ledger.precipitationEndMillis)
    this[Keys.OfficialIds] = ledger.officialAlertIds
      .takeLast(NotificationLedger.MAX_OFFICIAL_IDS)
      .joinToString("\n")
    set(Keys.SummaryDay, ledger.summaryEpochDay)
    set(Keys.CycleAt, ledger.lastCycleAtMillis)
    set(Keys.CycleNote, ledger.lastCycleNote)
  }

  private object Keys {
    val NowcastAt = longPreferencesKey("ledger_nowcast_at")
    val NowcastProbability = doublePreferencesKey("ledger_nowcast_probability")
    val NowcastActive = booleanPreferencesKey("ledger_nowcast_active")
    val PrecipOnset = longPreferencesKey("ledger_precip_onset")
    val PrecipEnd = longPreferencesKey("ledger_precip_end")
    val OfficialIds = stringPreferencesKey("ledger_official_ids")
    val SummaryDay = longPreferencesKey("ledger_summary_day")
    val CycleAt = longPreferencesKey("ledger_cycle_at")
    val CycleNote = stringPreferencesKey("ledger_cycle_note")
  }
}
