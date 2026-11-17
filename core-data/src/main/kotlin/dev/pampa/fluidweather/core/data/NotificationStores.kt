package dev.pampa.fluidweather.core.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.pampa.fluidweather.core.model.NotificationChannelKind
import dev.pampa.fluidweather.core.model.NotificationLedger
import dev.pampa.fluidweather.core.model.NotificationSettings
import dev.pampa.fluidweather.core.model.NotificationLocation
import dev.pampa.fluidweather.core.model.LocalNotificationLedger
import java.util.Base64
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.notificationStore: DataStore<Preferences> by preferencesDataStore(
  name = "notifications",
  // Corrotto = si riparte da vuoto. Senza, ogni lettura E ogni scrittura falliscono per
  // sempre, in silenzio (i chiamanti hanno tutti un runCatching), e l'unico rimedio resta
  // cancellare i dati dell'app.
  corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

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
    location = this[Keys.Location]?.let(::readLocation),
    previousLocations = this[Keys.PreviousLocations]?.lineSequence()?.mapNotNull(::readLocalLedger)
      ?.toList()?.takeLast(NotificationLedger.MAX_LOCAL_LOCATIONS).orEmpty(),
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
    set(Keys.Location, ledger.location?.let(::writeLocation))
    this[Keys.PreviousLocations] = ledger.previousLocations.takeLast(NotificationLedger.MAX_LOCAL_LOCATIONS)
      .joinToString("\n", transform = ::writeLocalLedger)
  }

  private fun writeLocation(location: NotificationLocation): String = listOf(
    Base64.getUrlEncoder().withoutPadding().encodeToString(location.placeKey.toByteArray(Charsets.UTF_8)),
    location.latitude.toString(), location.longitude.toString(),
  ).joinToString("\t")

  private fun readLocation(encoded: String): NotificationLocation? = runCatching {
    val parts = encoded.split('\t')
    if (parts.size != 3) return null
    val latitude = parts[1].toDouble().takeIf { it.isFinite() && it in -90.0..90.0 } ?: return null
    val longitude = parts[2].toDouble().takeIf { it.isFinite() && it in -180.0..180.0 } ?: return null
    NotificationLocation(String(Base64.getUrlDecoder().decode(parts[0]), Charsets.UTF_8), latitude, longitude)
  }.getOrNull()

  private fun writeLocalLedger(local: LocalNotificationLedger): String = listOf(
    writeLocation(local.location), local.nowcastAlertAtMillis?.toString().orEmpty(),
    local.nowcastAlertProbability?.toString().orEmpty(), local.nowcastAlertActive.toString(),
    local.precipitationOnsetMillis?.toString().orEmpty(), local.precipitationEndMillis?.toString().orEmpty(),
  ).joinToString("\t")

  private fun readLocalLedger(encoded: String): LocalNotificationLedger? {
    val parts = encoded.split('\t')
    if (parts.size != 8) return null
    val location = readLocation(parts.take(3).joinToString("\t")) ?: return null
    return LocalNotificationLedger(location, parts[3].toLongOrNull(),
      parts[4].toDoubleOrNull()?.takeIf { it.isFinite() && it in 0.0..1.0 }, parts[5] == "true",
      parts[6].toLongOrNull(), parts[7].toLongOrNull())
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
    val Location = stringPreferencesKey("ledger_location")
    val PreviousLocations = stringPreferencesKey("ledger_previous_locations")
  }
}
