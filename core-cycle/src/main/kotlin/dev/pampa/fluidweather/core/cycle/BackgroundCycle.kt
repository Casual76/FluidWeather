package dev.pampa.fluidweather.core.cycle

import android.content.Context
import android.location.Geocoder
import dev.pampa.fluidweather.core.data.NotificationLedgerStore
import dev.pampa.fluidweather.core.data.NotificationSettingsStore
import dev.pampa.fluidweather.core.data.PressureRepository
import dev.pampa.fluidweather.core.data.SamplingSettingsStore
import dev.pampa.fluidweather.core.data.SavedLocationsRepository
import dev.pampa.fluidweather.core.model.AppNotification
import dev.pampa.fluidweather.core.model.FusionVariables
import dev.pampa.fluidweather.core.model.OfficialAlert
import dev.pampa.fluidweather.core.sensor.LocationProvider
import dev.pampa.fluidweather.core.weather.OfficialAlertsClient
import dev.pampa.fluidweather.core.weather.WeatherSnapshot
import dev.pampa.fluidweather.core.weather.WeatherSnapshotRefresher
import dev.pampa.fluidweather.core.weather.toContext
import dev.pampa.fluidweather.nowcast.cleaning.CleaningPipeline
import dev.pampa.fluidweather.nowcast.features.FeatureExtractor
import dev.pampa.fluidweather.nowcast.verdict.AlertLevel
import dev.pampa.fluidweather.nowcast.verdict.NowcastModel
import dev.pampa.fluidweather.nowcast.verdict.NowcastVerdict
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Chi ha chiesto il giro: ogni innesco ha il suo appetito di rete e di GPS. */
enum class CycleTrigger { SAMPLING_PASS, SURVEILLANCE_TICK, DAILY_SUMMARY, MANUAL }

/** Le notifiche che l'app in primo piano mostra come banner invece che in tendina. */
class InAppAlertBus {
  private val flow = MutableSharedFlow<AppNotification>(extraBufferCapacity = 8)
  val events: SharedFlow<AppNotification> = flow
  fun emit(notification: AppNotification): Boolean = flow.tryEmit(notification)
}

/** Se l'app e' davanti agli occhi dell'utente: lo dice l'Activity, lo legge il ciclo. */
class AppVisibility {
  private val state = MutableStateFlow(false)
  val inForeground: StateFlow<Boolean> = state
  fun set(visible: Boolean) {
    state.value = visible
  }
}

/** Il paese e i nomi d'area di un punto, dal geocoder del telefono: servono alle allerte ufficiali. */
data class PlaceContext(
  val countryCode: String?,
  val areaCandidates: List<String>,
  val locality: String?,
)

class PlaceContextResolver(private val context: Context) {

  private val cache = mutableMapOf<String, PlaceContext>()

  suspend fun resolve(latitude: Double, longitude: Double): PlaceContext? {
    val key = "%.2f,%.2f".format(Locale.ROOT, latitude, longitude)
    synchronized(cache) { cache[key] }?.let { return it }
    val resolved = withContext(Dispatchers.IO) {
      runCatching {
        @Suppress("DEPRECATION")
        Geocoder(context, Locale.getDefault()).getFromLocation(latitude, longitude, 1)?.firstOrNull()
      }.getOrNull()
    } ?: return null
    val result = PlaceContext(
      countryCode = resolved.countryCode,
      areaCandidates = listOfNotNull(resolved.adminArea, resolved.subAdminArea, resolved.locality),
      locality = resolved.locality ?: resolved.subAdminArea ?: resolved.adminArea,
    )
    synchronized(cache) {
      if (cache.size > 32) cache.clear()
      cache[key] = result
    }
    return result
  }
}

/** Com'e' andata: per la diagnostica e per i test del ciclo. */
data class CycleOutcome(
  val note: String,
  val snapshot: WeatherSnapshot?,
  val verdict: NowcastVerdict?,
  val delivered: List<AppNotification>,
)

/**
 * Il ciclo in background: a ogni passata del barometro (e a ogni minuto di sorveglianza) il
 * meteo si rinfresca, il verdetto si ricalcola e le notifiche escono. E' il "ritmo del
 * barometro" chiesto sul telefono il 2026-09-02: la home trova l'istantanea gia' pronta.
 *
 * Un mutex serializza le passate: worker, allarme esatto e servizio di sorveglianza possono
 * arrivare insieme, e due giri paralleli sullo stesso registro si contraddirebbero.
 */
class BackgroundCycle(
  private val locationProvider: LocationProvider,
  private val savedLocations: SavedLocationsRepository,
  private val refresher: WeatherSnapshotRefresher,
  private val pressureRepository: PressureRepository,
  private val cleaningPipeline: CleaningPipeline,
  private val samplingSettings: SamplingSettingsStore,
  private val notificationSettings: NotificationSettingsStore,
  private val ledgerStore: NotificationLedgerStore,
  private val officialAlerts: OfficialAlertsClient,
  private val placeContext: PlaceContextResolver,
  private val notifier: SystemNotifier,
  private val inAppAlerts: InAppAlertBus,
  private val appVisibility: AppVisibility,
  private val clock: () -> Long = System::currentTimeMillis,
  private val zone: () -> ZoneId = { ZoneId.systemDefault() },
) {

  private val mutex = Mutex()
  private var lastTickMillis = 0L

  suspend fun run(trigger: CycleTrigger): CycleOutcome = mutex.withLock {
    val now = clock()
    if (trigger == CycleTrigger.SURVEILLANCE_TICK && now - lastTickMillis < TICK_INTERVAL_MILLIS) {
      return CycleOutcome("tick saltato (troppo vicino al precedente)", null, null, emptyList())
    }
    lastTickMillis = now

    val point = resolvePoint()
    if (point == null) {
      val outcome = CycleOutcome("nessuna posizione: ne' GPS ne' localita' salvate", null, null, emptyList())
      record(now, outcome)
      return outcome
    }
    val (key, latitude, longitude) = point

    // 1) Il giro meteo, al ritmo del barometro; la sorveglianza si accontenta di mezz'ora.
    val cached = refresher.fresh(key, latitude, longitude, maxAgeMillis = SHOWABLE_AGE_MILLIS)
    val snapshot = when {
      trigger == CycleTrigger.SURVEILLANCE_TICK && cached != null && cached.ageMillis(now) <= TICK_WEATHER_AGE_MILLIS -> cached
      else -> runCatching { refresher.refresh(key, latitude, longitude) }.getOrNull() ?: cached
    }

    // 2) Il verdetto locale: barometro pulito + contesto dell'opinione piu' completa.
    val temperature = snapshot?.fused?.hours
      ?.minByOrNull { abs(it.timestampMillis - now) }
      ?.values?.get(FusionVariables.TEMPERATURE)?.value
    val samples = runCatching { pressureRepository.samplesSince(now - 12 * 3_600_000L) }.getOrDefault(emptyList())
    val cleaning = runCatching { cleaningPipeline.process(samples, temperatureCelsius = temperature) }.getOrNull()
    val verdict = cleaning?.let {
      FeatureExtractor.extract(it, snapshot?.context?.toContext(now), normalHpa = null, nowMillis = now)
        ?.let { features -> NowcastModel.trained().verdict(features) }
    }

    // 3) Le allerte ufficiali, solo se il canale e' acceso: niente rete per niente.
    val settings = notificationSettings.current()
    val context = if (settings.officialAlerts || trigger == CycleTrigger.DAILY_SUMMARY) {
      runCatching { placeContext.resolve(latitude, longitude) }.getOrNull()
    } else {
      null
    }
    val alerts: List<OfficialAlert> = if (settings.officialAlerts && context != null) {
      runCatching {
        officialAlerts.forPoint(latitude, longitude, context.countryCode, context.areaCandidates)
      }.getOrDefault(emptyList())
    } else {
      emptyList()
    }

    // 4) La politica decide, il registro ricorda, la consegna sceglie il mezzo.
    val ledger = ledgerStore.current()
    val decision = AlertPolicy.decide(
      AlertInputs(
        nowMillis = now,
        zone = zone(),
        settings = settings,
        ledger = ledger,
        verdict = verdict,
        fusedHours = snapshot?.fused?.hours ?: emptyList(),
        officialAlerts = alerts,
      ),
    )
    val delivered = decision.notifications.filter { deliver(it) }.toMutableList()
    if (decision.cancelNowcastAlert) notifier.cancel(AlertPolicy.NOWCAST_ID)
    var nextLedger = decision.ledger

    // 5) Il riepilogo, quando e' la sua ora e non e' gia' uscito oggi.
    if (trigger == CycleTrigger.DAILY_SUMMARY && settings.dailySummary) {
      val today = Instant.ofEpochMilli(now).atZone(zone()).toLocalDate().toEpochDay()
      if (nextLedger.summaryEpochDay != today) {
        val summary = DailySummary.compose(
          nowMillis = now,
          zone = zone(),
          hours = snapshot?.fused?.hours ?: emptyList(),
          verdict = verdict,
          pressureTrendHpaPerHour = cleaning?.latest?.trendHpaPerHour,
          locationName = context?.locality,
        )
        if (summary != null && deliver(summary)) {
          delivered += summary
          nextLedger = nextLedger.copy(summaryEpochDay = today)
        }
      }
    }

    val note = buildString {
      append(TimeFormatter.withZone(zone()).format(Instant.ofEpochMilli(now)))
      append(" · ").append(triggerLabel(trigger))
      if (snapshot != null) {
        append(" · ${snapshot.providersResponding}/${snapshot.fetches.size} provider")
        if (snapshot === cached) append(" (istantanea)")
      } else {
        append(" · meteo non disponibile")
      }
      append(" · verdetto ").append(verdict?.level?.let { levelLabel(it) } ?: "assente")
      append(" · ${delivered.size} notifiche")
    }
    val outcome = CycleOutcome(note, snapshot, verdict, delivered)
    ledgerStore.update { nextLedger.copy(lastCycleAtMillis = now, lastCycleNote = note) }
    outcome
  }

  /** In primo piano il banner in-app, altrimenti la tendina (decisione 2026-09-02). */
  private fun deliver(notification: AppNotification): Boolean =
    if (appVisibility.inForeground.value) inAppAlerts.emit(notification) else notifier.post(notification)

  /** GPS se c'e', altrimenti la prima localita' salvata: le notifiche sono per dove sei. */
  private suspend fun resolvePoint(): Triple<String, Double, Double>? {
    if (locationProvider.hasPermission()) {
      locationProvider.snapshot(timeoutMillis = LOCATION_TIMEOUT_MILLIS)?.let {
        return Triple(WeatherSnapshot.GPS_KEY, it.latitude, it.longitude)
      }
    }
    val saved = savedLocations.places.first().firstOrNull { !it.isGps } ?: return null
    return Triple(WeatherSnapshot.keyFor(saved.id), saved.latitude, saved.longitude)
  }

  private suspend fun record(now: Long, outcome: CycleOutcome) {
    ledgerStore.update { it.copy(lastCycleAtMillis = now, lastCycleNote = outcome.note) }
  }

  private fun triggerLabel(trigger: CycleTrigger) = when (trigger) {
    CycleTrigger.SAMPLING_PASS -> "passata"
    CycleTrigger.SURVEILLANCE_TICK -> "sorveglianza"
    CycleTrigger.DAILY_SUMMARY -> "riepilogo"
    CycleTrigger.MANUAL -> "manuale"
  }

  private fun levelLabel(level: AlertLevel) = when (level) {
    AlertLevel.QUIETE -> "quiete"
    AlertLevel.SORVEGLIANZA -> "sorveglianza"
    AlertLevel.ALLERTA -> "ALLERTA"
  }

  private companion object {
    /** In sorveglianza il sensore legge ogni minuto; il ciclo ragiona ogni cinque. */
    const val TICK_INTERVAL_MILLIS = 5 * 60_000L

    /** In sorveglianza il meteo di mezz'ora fa basta: e' il barometro che sta parlando. */
    const val TICK_WEATHER_AGE_MILLIS = 30 * 60_000L

    /** Un'istantanea piu' vecchia di cosi' non si mostra nemmeno come ripiego. */
    const val SHOWABLE_AGE_MILLIS = 12 * 3_600_000L

    const val LOCATION_TIMEOUT_MILLIS = 15_000L

    val TimeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
  }
}
