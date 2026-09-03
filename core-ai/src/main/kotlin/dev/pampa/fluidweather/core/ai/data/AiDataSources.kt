package dev.pampa.fluidweather.core.ai.data

import dev.pampa.fluidweather.core.ai.radar.RadarSampler
import dev.pampa.fluidweather.core.cycle.PlaceContextResolver
import dev.pampa.fluidweather.core.data.CalibrationStore
import dev.pampa.fluidweather.core.data.FusionSettingsStore
import dev.pampa.fluidweather.core.data.NotificationLedgerStore
import dev.pampa.fluidweather.core.data.NotificationSettingsStore
import dev.pampa.fluidweather.core.data.NowcastHistoryStore
import dev.pampa.fluidweather.core.data.ObservationRepository
import dev.pampa.fluidweather.core.data.PressureRepository
import dev.pampa.fluidweather.core.data.ProviderKeysStore
import dev.pampa.fluidweather.core.data.SamplingSettingsStore
import dev.pampa.fluidweather.core.data.SavedLocationsRepository
import dev.pampa.fluidweather.core.data.SelectedPlaceStore
import dev.pampa.fluidweather.core.data.UnitsStore
import dev.pampa.fluidweather.core.model.VerificationStore
import dev.pampa.fluidweather.core.sensor.CalibrationController
import dev.pampa.fluidweather.core.sensor.LocationProvider
import dev.pampa.fluidweather.core.sensor.ManualBurstController
import dev.pampa.fluidweather.core.weather.AirQualityClient
import dev.pampa.fluidweather.core.weather.GeocodingClient
import dev.pampa.fluidweather.core.weather.NowcastUseCase
import dev.pampa.fluidweather.core.weather.OfficialAlertsClient
import dev.pampa.fluidweather.core.weather.RainViewerClient
import dev.pampa.fluidweather.core.weather.WeatherRepository
import dev.pampa.fluidweather.core.weather.WeatherSnapshotRefresher
import dev.pampa.fluidweather.core.weather.WeatherSnapshotStore

/**
 * Tutto cio' che i tool possono leggere (e, con le azioni, toccare): gli stessi oggetti del grafo
 * dell'app, raccolti una volta in `:app`. Nessun tool costruisce niente da solo.
 */
class AiDataSources(
  val snapshotRefresher: WeatherSnapshotRefresher,
  val snapshotStore: WeatherSnapshotStore,
  val nowcast: NowcastUseCase,
  val pressureRepository: PressureRepository,
  val nowcastHistory: NowcastHistoryStore,
  val samplingSettings: SamplingSettingsStore,
  val calibrationStore: CalibrationStore,
  val calibrationController: CalibrationController,
  val locationProvider: LocationProvider,
  val savedLocations: SavedLocationsRepository,
  val selectedPlaceStore: SelectedPlaceStore,
  val geocodingClient: GeocodingClient,
  val airQualityClient: AirQualityClient,
  val officialAlerts: OfficialAlertsClient,
  val placeContext: PlaceContextResolver,
  val verificationStore: VerificationStore,
  val fusionSettings: FusionSettingsStore,
  val providerKeys: ProviderKeysStore,
  val weatherRepository: WeatherRepository,
  val rainViewer: RainViewerClient,
  val radarSampler: RadarSampler,
  val observations: ObservationRepository,
  val notificationSettings: NotificationSettingsStore,
  val notificationLedger: NotificationLedgerStore,
  val manualBurst: ManualBurstController,
  val unitsStore: UnitsStore,
)
