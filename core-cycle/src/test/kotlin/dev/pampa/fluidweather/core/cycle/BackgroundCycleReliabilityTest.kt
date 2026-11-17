package dev.pampa.fluidweather.core.cycle

import android.Manifest
import android.app.Application
import androidx.room.Room
import dev.antigravity.fluidengine.net.EngineHttp
import dev.pampa.fluidweather.core.data.*
import dev.pampa.fluidweather.core.model.*
import dev.pampa.fluidweather.core.sensor.LocationProvider
import dev.pampa.fluidweather.core.weather.*
import dev.pampa.fluidweather.nowcast.cleaning.CleaningPipeline
import java.io.File
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CancellationException
import dev.pampa.fluidweather.nowcast.verdict.AlertLevel
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Real cycle, DataStore ledger/settings, Room and Android posting; no network, GPS or sensors. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class BackgroundCycleReliabilityTest {
  private lateinit var app: Application
  private lateinit var database: FluidWeatherDatabase
  private lateinit var places: SavedLocationsRepository
  private lateinit var settings: NotificationSettingsStore
  private lateinit var ledger: NotificationLedgerStore
  private lateinit var snapshots: WeatherSnapshotStore
  private lateinit var cycle: BackgroundCycle
  private val noon = 1_781_517_600_000L
  private var now = noon
  private var onRefresh: () -> Unit = {}
  private var beforeLedger: () -> Unit = {}
  private val wipeGuard = DataWipeGuard()
  private val visibility = AppVisibility()
  private val tokyo = Place(7, "Tokyo", null, 35.68, 139.69, "Asia/Tokyo")
  private val berlin = Place(8, "Berlin", null, 52.52, 13.40, "Europe/Berlin")

  private fun row(offset: Int, wet: Boolean) = FusedHour(noon + (offset + 1) * 3_600_000L,
    mapOf(FusionVariables.PRECIP_PROBABILITY to FusedValue(if (wet) 90.0 else 0.0, emptyList())),
    if (wet) WeatherKind.RAIN else WeatherKind.CLEAR,
    precipitationInterval = WeatherInterval(noon + offset * 3_600_000L, noon + (offset + 1) * 3_600_000L))
  private fun snapshot(place: Place) = WeatherSnapshot(
    WeatherSnapshot.keyFor(place.id), place.latitude, place.longitude, noon,
    listOf(ProviderFetchSummary(ProviderRegistry.OPEN_METEO, 3, null, downloadedAtMillis = noon)),
    FusedForecast(listOf(row(0, false), row(1, true), row(2, true)), emptyMap()), null, zoneId = place.zoneId,
  )

  @Before fun setUp() = runBlocking {
    app = RuntimeEnvironment.getApplication()
    shadowOf(app).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
    shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    NotificationChannels.ensure(app)
    database = Room.inMemoryDatabaseBuilder(app, FluidWeatherDatabase::class.java).allowMainThreadQueries().build()
    places = SavedLocationsRepository(database.savedLocationsDao())
    places.save(tokyo)
    settings = NotificationSettingsStore(app)
    settings.setEnabled(NotificationChannelKind.NOWCAST_ALERT, false)
    settings.setEnabled(NotificationChannelKind.PRECIPITATION, true)
    settings.setEnabled(NotificationChannelKind.OFFICIAL_ALERTS, false)
    settings.setEnabled(NotificationChannelKind.DAILY_SUMMARY, false)
    ledger = NotificationLedgerStore(app)
    ledger.update { NotificationLedger() }
    snapshots = WeatherSnapshotStore(File(app.cacheDir, "notification-regression-snapshots"))
    snapshots.write(snapshot(tokyo))
    val pressure = PressureRepository(database.pressureDao())
    val cleaning = CleaningPipeline()
    val calibration = CalibrationStore(app)
    val learning = LearningRepository(database.learningDao())
    val history = NowcastHistoryStore(database.nowcastHistoryDao())
    val nowcast = NowcastUseCase(pressure, cleaning, calibration, learning, LearningStore(app), history,
      BarometerBaselineStore(app), sensorAvailable = { false })
    val source = object : RoundSource {
      override suspend fun refresh(latitude: Double, longitude: Double, registerPredictions: Boolean): WeatherRound {
        onRefresh()
        val descriptor = ProviderRegistry.all.first()
        val bundle = ForecastBundle(descriptor.id, now, latitude, longitude,
          listOf(HourlyPoint(now, temperatureC = 20.0)))
        return WeatherRound(listOf(ProviderFetch(descriptor, bundle, null)), snapshot(tokyo).fused)
      }
    }
    cycle = BackgroundCycle(
      locationProvider = LocationProvider(app), savedLocations = places,
      refresher = WeatherSnapshotRefresher(source, snapshots, clock = { now }),
      pressureRepository = pressure, cleaningPipeline = cleaning, calibrationStore = calibration,
      learningRepository = learning, samplingSettings = SamplingSettingsStore(app), notificationSettings = settings,
      ledgerStore = ledger, nowcastHistory = history, verificationStore = RoomVerificationStore(database.verificationDao()),
      nowcastUseCase = nowcast, lastFixStore = LastFixStore(app), latestActivityStore = LatestActivityStore(app),
      rainEventStore = RoomRainEventStore(database.rainEventDao()),
      officialAlerts = OfficialAlertsClient(ProviderHttp(EngineHttp(userAgent = "notification-regression"),
        UrlCache(File(app.cacheDir, "notification-regression-http")))),
      placeContext = PlaceContextResolver(app), notifier = SystemNotifier(app), inAppAlerts = InAppAlertBus(),
      appVisibility = visibility,
      texts = object : NotificationTexts by ItalianTexts {
        override fun cycleNote(nowMillis: Long, zone: ZoneId, trigger: CycleTrigger,
          providersResponding: Int?, providersTotal: Int?, fromSnapshot: Boolean, level: AlertLevel?, delivered: Int): String {
          beforeLedger()
          return ItalianTexts.cycleNote(nowMillis, zone, trigger, providersResponding, providersTotal, fromSnapshot, level, delivered)
        }
      }, clock = { now }, zone = { ZoneId.of("Europe/Rome") },
      networkLikelyAvailable = { true },
      wipeGuard = wipeGuard,
    )
  }

  @After fun tearDown() { database.close() }

  @Test fun `permission refusal does not consume precipitation and grant retries it`() = runBlocking {
    shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
    assertTrue(cycle.run(CycleTrigger.SAMPLING_PASS).delivered.isEmpty())
    assertNull("unseen event must remain eligible", ledger.current().precipitationOnsetMillis)
    shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    assertEquals(1, cycle.run(CycleTrigger.SAMPLING_PASS).delivered.size)
    assertEquals(noon + 3_600_000L, ledger.current().precipitationOnsetMillis)
    assertTrue(cycle.run(CycleTrigger.SAMPLING_PASS).delivered.isEmpty())
  }

  @Test fun `remote fallback identifies the place and uses its weather zone`() = runBlocking {
    val alert = cycle.run(CycleTrigger.SAMPLING_PASS).delivered.single()
    assertTrue("remote alert needs location identity: ${alert.text}", alert.text.contains("Tokyo"))
    assertTrue("Tokyo event is at 20h: ${alert.text}", alert.text.contains("20:00"))
    assertFalse(alert.text.contains("dove sei"))
  }

  @Test fun `another saved point is not suppressed by the previous points event`() = runBlocking {
    assertEquals(1, cycle.run(CycleTrigger.SAMPLING_PASS).delivered.size)
    places.remove(tokyo.id)
    places.save(berlin)
    snapshots.write(snapshot(berlin))
    assertEquals(1, cycle.run(CycleTrigger.SAMPLING_PASS).delivered.size)
    // DataStore maps serialized scope history back to domain objects before the next cycle.
    assertEquals(WeatherSnapshot.keyFor(tokyo.id), ledger.current().previousLocations.single().location.placeKey)
    places.remove(berlin.id)
    places.save(tokyo)
    assertTrue(cycle.run(CycleTrigger.SAMPLING_PASS).delivered.isEmpty())
  }

  @Test fun `current summary without a position remains pending and obsolete request does not`() = runBlocking {
    places.remove(tokyo.id)
    settings.setEnabled(NotificationChannelKind.DAILY_SUMMARY, true)
    settings.setSummaryTime(12, 0)
    assertTrue(cycle.run(CycleTrigger.DAILY_SUMMARY, noon).dailySummaryPending)
    assertFalse(cycle.run(CycleTrigger.DAILY_SUMMARY, noon - 86_400_000L).dailySummaryPending)
    settings.setEnabled(NotificationChannelKind.DAILY_SUMMARY, false)
    assertFalse(cycle.run(CycleTrigger.DAILY_SUMMARY, noon).dailySummaryPending)
  }

  @Test fun `a slow acquisition cannot post an onset that has already passed`() = runBlocking {
    // Force refresh; source returns usable rows but the real clock advances two hours while it runs.
    snapshots.clear()
    onRefresh = { now = noon + 2 * 3_600_000L }
    val outcome = cycle.run(CycleTrigger.MANUAL)
    assertNotNull("the provider round must succeed, not fail the fixture", outcome.snapshot)
    assertEquals(noon + 2 * 3_600_000L, now)
    assertTrue(outcome.delivered.isEmpty())
  }

  @Test fun wipeBeforeCommitDoesNotRestoreLocations() = runBlocking {
    ledger.update { it.copy(location = NotificationLocation("gps", 43.83, 11.20), precipitationOnsetMillis = noon) }
    beforeLedger = { runBlocking { wipeGuard.wipe { ledger.update { NotificationLedger() } } } }
    val result = runCatching { cycle.run(CycleTrigger.SAMPLING_PASS) }
    assertTrue("an invalidated cycle must stop", result.exceptionOrNull() is CancellationException)
    assertEquals(NotificationLedger(), ledger.current())
  }

  @Test fun foregroundWithoutBannerFallsBackToSystem() = runBlocking {
    visibility.set(true)
    val outcome = cycle.run(CycleTrigger.SAMPLING_PASS)
    assertEquals(1, outcome.delivered.size)
    assertEquals(noon + 3_600_000L, ledger.current().precipitationOnsetMillis)
    val manager = app.getSystemService(android.content.Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
    assertEquals(1, shadowOf(manager).size())
  }
}
