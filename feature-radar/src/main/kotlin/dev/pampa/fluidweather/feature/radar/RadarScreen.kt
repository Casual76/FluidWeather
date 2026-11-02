package dev.pampa.fluidweather.feature.radar

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Air
import androidx.compose.material.icons.rounded.BlurOn
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Thermostat
import androidx.compose.material.icons.rounded.WaterDrop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.semantics.Role
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
import kotlin.math.roundToInt
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import dev.antigravity.fluidengine.foundation.EngineSettings
import dev.antigravity.fluidengine.foundation.ThemeMode
import dev.antigravity.fluidengine.storage.EngineSettingsStore
import dev.antigravity.fluidengine.ui.fluid.ContinuousCornerShape
import dev.antigravity.fluidengine.ui.fluid.FluidContextAction
import dev.antigravity.fluidengine.ui.fluid.FluidGlassIconButton
import dev.antigravity.fluidengine.ui.fluid.FluidRadius
import dev.antigravity.fluidengine.ui.fluid.FluidScreen
import dev.antigravity.fluidengine.ui.fluid.GlassBackdropState
import dev.antigravity.fluidengine.ui.fluid.GlassDefaults
import dev.antigravity.fluidengine.ui.fluid.GlassRole
import dev.antigravity.fluidengine.ui.fluid.glassSurface
import dev.antigravity.fluidengine.ui.fluid.rememberGlassBackdrop
import dev.antigravity.fluidengine.ui.fluidphysics.FluidMorphMenuButton
import dev.antigravity.fluidengine.ui.fluidphysics.FluidMorphMenuHost
import dev.antigravity.fluidengine.ui.fluidphysics.rememberFluidMorphMenuState
import dev.antigravity.fluidengine.ui.haptics.FluidHapticEvent
import dev.antigravity.fluidengine.ui.haptics.rememberFluidHaptics
import dev.antigravity.fluidengine.ui.theme.FluidListGroup
import dev.antigravity.fluidengine.ui.theme.FluidListRow
import dev.antigravity.fluidengine.ui.theme.FluidTheme
import dev.antigravity.fluidengine.ui.tutorial.fluidTutorialAnchor
import dev.pampa.fluidweather.core.data.ProviderKeysStore
import dev.pampa.fluidweather.core.data.SavedLocationsRepository
import dev.pampa.fluidweather.core.data.SelectedPlaceStore
import dev.pampa.fluidweather.core.model.DayPhase
import dev.pampa.fluidweather.core.sensor.LocationProvider
import dev.pampa.fluidweather.core.ui.TutorialScreen
import dev.pampa.fluidweather.core.ui.TutorialSlot
import dev.pampa.fluidweather.core.ui.WeatherAccent
import dev.pampa.fluidweather.core.ui.rememberUnitFormatter
import dev.pampa.fluidweather.core.weather.PointWeatherClient
import dev.pampa.fluidweather.core.weather.ProviderRegistry
import dev.pampa.fluidweather.core.weather.RadarFrames
import dev.pampa.fluidweather.core.weather.RainViewerClient
import dev.pampa.fluidweather.core.weather.RainViewerPalette
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import dev.pampa.fluidweather.strings.R
import androidx.compose.ui.res.stringResource

/** Tutto quello che il radar tocca; lo costruisce :app dal suo grafo. */
class RadarDependencies(
  /** Il tema dell'app: la mappa e i controlli lo seguono, chiaro compreso. */
  val engineSettings: EngineSettingsStore,
  val rainViewer: RainViewerClient,
  val pointWeather: PointWeatherClient,
  val savedLocations: SavedLocationsRepository,
  val selectedPlaceStore: SelectedPlaceStore,
  val locationProvider: LocationProvider,
  val providerKeys: ProviderKeysStore,
)

/** Un pin sulla mappa: una localita' salvata (o il telefono) con la sua temperatura. */
private data class RadarPin(val name: String, val latitude: Double, val longitude: Double, val temperatureC: Double?)

/**
 * I colori della schermata, dal tema.
 *
 * Erano cinque costanti scure, perche' la mappa era in tema scuro fisso: l'impostazione "Chiaro"
 * non toccava il radar. Ora la mappa segue il tema dell'app e questi seguono la mappa — senza,
 * con la mappa chiara legenda, pin e barra del tempo sarebbero rimasti neri su chiaro.
 *
 * Restano cinque nomi corti letti come prima (`Ink`, `Panel`, ...) perche' sono usati in una
 * ventina di punti e la loro identita' non e' cambiata: e' cambiato da dove viene il valore.
 */
internal class RadarPalette(val dark: Boolean) {
  val ink: Color = if (dark) Color(0xFF0B0B0E) else Color(0xFFEFF3F8)
  val panel: Color = if (dark) Color(0xCC0B0B0E) else Color(0xE6FFFFFF)
  val onPanel: Color = if (dark) Color.White else Color(0xFF11151C)
  val faint: Color = onPanel.copy(alpha = 0.55f)
  val accent: Color = if (dark) Color(0xFF8FC7F0) else Color(0xFF1B6FB8)
}

private val LocalRadarPalette = staticCompositionLocalOf { RadarPalette(dark = true) }

private val Ink: Color @Composable get() = LocalRadarPalette.current.ink
private val Panel: Color @Composable get() = LocalRadarPalette.current.panel
private val White: Color @Composable get() = LocalRadarPalette.current.onPanel
private val Faint: Color @Composable get() = LocalRadarPalette.current.faint
private val Accent: Color @Composable get() = LocalRadarPalette.current.accent

/**
 * Il radar a schermo intero, come nella reference: X in alto a sinistra, selettore dei livelli
 * in alto a destra (un morph di vetro), legenda in basso a sinistra, tasto posizione in basso a
 * destra, pin con la temperatura delle localita' salvate, barra del tempo con play e scrub, e
 * l'attribuzione. Base cartografica MapLibre con i tile liberi di OpenFreeMap (prima Google Maps,
 * uscito per la GPL-3.0 dell'engine 3.0: vedi [RadarMapCatalog]), chiara o scura col tema;
 * livello precipitazioni dal radar RainViewer, in loop sulle ultime due ore.
 */
@Composable
fun RadarScreen(deps: RadarDependencies, onBack: () -> Unit) {
  // Niente chiave e niente Play Services da controllare prima: l'unico modo di non avere la mappa
  // e' che lo stile non arrivi, e MapLibre lo dice. Allora lo si dice anche qui, invece di
  // lasciare un rettangolo vuoto.
  var mapFailed by remember { mutableStateOf(false) }
  if (mapFailed) {
    RadarUnavailable(onBack)
    return
  }
  // La stessa regola dell'engine: Sistema segue il telefono, Chiaro e' chiaro, Scuro e' scuro.
  val settings by deps.engineSettings.settings.collectAsState(initial = EngineSettings())
  val dark = when (settings.themeMode) {
    ThemeMode.LIGHT -> false
    ThemeMode.DARK, ThemeMode.AMOLED -> true
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
  }
  FluidTheme(
    settings = remember(dark) {
      EngineSettings(themeMode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT, dynamicColorEnabled = false)
    },
    brand = WeatherAccent.presetFor(null, if (dark) DayPhase.NIGHT else DayPhase.DAY),
  ) {
    CompositionLocalProvider(LocalRadarPalette provides RadarPalette(dark)) {
      RadarShell(deps, onBack, dark, onMapFailed = { mapFailed = true })
    }
  }
}

@Composable
private fun RadarUnavailable(onBack: () -> Unit) {
  FluidScreen(title = stringResource(R.string.radar_title), onBack = onBack) {
    item {
      FluidListGroup {
        FluidListRow(
          title = stringResource(R.string.radar_map_failed),
          subtitle = stringResource(R.string.radar_map_failed_desc),
        )
      }
    }
  }
}

@Composable
private fun RadarShell(
  deps: RadarDependencies,
  onBack: () -> Unit,
  dark: Boolean,
  onMapFailed: () -> Unit,
) {
  val scope = rememberCoroutineScope()
  // La mappa e' una View di sistema e non e' registrata come sorgente di vetro (sarebbe una
  // sorgente a schermo intero sempre viva, il costo misurato sull'S25). Il backdrop resta vuoto e
  // i controlli mostrano la loro pellicola: e' il degrado dichiarato, non un errore.
  val chromeBackdrop = rememberGlassBackdrop()
  val map = rememberRadarMapController()
  val places by deps.savedLocations.places.collectAsState(initial = emptyList())
  val selectedId by deps.selectedPlaceStore.selectedId.collectAsState()
  val keys by deps.providerKeys.keys.collectAsState(initial = emptyMap())
  val owmKey = keys[ProviderRegistry.OPENWEATHERMAP]
  // Il permesso si rilegge a ogni ritorno in primo piano: concesso dal sistema (un'altra
  // attivita', quindi un altro RESUMED) il puntino blu e il tasto "dove sono" restavano spenti
  // finche' la schermata non veniva ricreata.
  val lifecycle = LocalLifecycleOwner.current.lifecycle
  var hasLocationPermission by remember { mutableStateOf(deps.locationProvider.hasPermission()) }
  LaunchedEffect(lifecycle) {
    lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
      hasLocationPermission = deps.locationProvider.hasPermission()
    }
  }

  // I fotogrammi del radar e l'animazione: parte in play (decisione 2026-09-02), un tocco ferma.
  val frames by produceState<RadarFrames?>(initialValue = null) {
    value = runCatching { deps.rainViewer.frames() }.getOrNull()
  }
  var frameIndex by remember { mutableIntStateOf(0) }
  var playing by remember { mutableStateOf(true) }
  LaunchedEffect(frames) { frames?.let { frameIndex = it.nowIndex } }
  LaunchedEffect(playing, frames) {
    val available = frames ?: return@LaunchedEffect
    val count = available.all.size
    if (count <= 1) return@LaunchedEffect
    while (playing) {
      delay(RadarTimeline.frameDelayMillis(frameIndex, count))
      frameIndex = (frameIndex + 1) % count
    }
  }
  var layer by remember { mutableStateOf(RadarLayer.PRECIPITATION) }

  // La camera: sulla localita' scelta, altrimenti sul telefono, altrimenti sull'Italia (la
  // posizione iniziale della mappa, in RadarMap).
  // Si ricentra a ogni cambio di localita', non solo la prima volta: chi cambia posto nella
  // pillola della home e poi apre il radar si aspetta di trovarci quel posto. La chiave e' la
  // localita' su cui siamo GIA' centrati, non un "fatto" una volta per sempre: cosi' i gesti
  // sulla mappa restano dove li ha lasciati chi guarda.
  var centeredOn by remember { mutableStateOf<Long?>(null) }
  LaunchedEffect(places, selectedId) {
    if (places.isEmpty() || centeredOn == selectedId) return@LaunchedEffect
    val place = places.firstOrNull { it.id == selectedId && !it.isGps }
    val target = place?.let { it.latitude to it.longitude }
      ?: deps.locationProvider.snapshot()?.let { it.latitude to it.longitude }
    if (target != null) {
      centeredOn = selectedId
      map.flyTo(target.first, target.second, googleZoom = 7.0)
    }
  }

  // I pin: le salvate piu' il telefono, con la temperatura di adesso in UNA chiamata.
  // Il nome del pin "qui" si legge nel composable: produceState non lo e'.
  val hereLabel = stringResource(R.string.radar_here)
  // Al passo dell'ora: le temperature dei pin erano legate solo all'elenco delle localita', e su
  // una schermata lasciata aperta restavano quelle del momento in cui si era entrati. L'ora
  // (non i millisecondi) e' il passo dei dati: i provider danno un valore per ora.
  val pinHour by produceState(System.currentTimeMillis() / 3_600_000L) {
    while (true) {
      val now = System.currentTimeMillis()
      delay(3_600_000L - now % 3_600_000L + 1_000L)
      value = System.currentTimeMillis() / 3_600_000L
    }
  }
  val pins by produceState<List<RadarPin>>(initialValue = emptyList(), places, pinHour, hasLocationPermission) {
    val saved = places.filter { !it.isGps }
    val here = if (deps.locationProvider.hasPermission()) deps.locationProvider.snapshot() else null
    val points = saved.map { it.latitude to it.longitude } +
      listOfNotNull(here?.let { it.latitude to it.longitude })
    val now = runCatching { deps.pointWeather.current(points) }.getOrDefault(emptyList())
    value = saved.mapIndexed { index, place ->
      RadarPin(place.name, place.latitude, place.longitude, now.getOrNull(index)?.temperatureC)
    } + listOfNotNull(
      here?.let { RadarPin(hereLabel, it.latitude, it.longitude, now.getOrNull(saved.size)?.temperatureC) },
    )
  }

  val navigationBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
  val barHeight = 84.dp

  Box(
    Modifier
      .fillMaxSize()
      .background(Ink),
  ) {
    val density = LocalDensity.current
    val language = LocalConfiguration.current.locales[0]?.language ?: "en"
    val owmLayer = layer.owmLayer
    RadarMap(
      controller = map,
      scene = RadarMapScene(
        dark = dark,
        frames = frames,
        frameIndex = frameIndex,
        precipitation = layer == RadarLayer.PRECIPITATION,
        owmTemplate = if (owmLayer != null && owmKey != null) RadarMapCatalog.owmTemplate(owmLayer, owmKey) else null,
        location = hasLocationPermission,
        // La camera centra fra i tasti in alto e la barra del tempo, come il contentPadding di prima.
        topPaddingPx = with(density) { 72.dp.roundToPx() },
        bottomPaddingPx = with(density) { (navigationBottom + barHeight).roundToPx() },
        language = language,
      ),
      loadColor = Ink,
      onFailed = onMapFailed,
      modifier = Modifier.fillMaxSize(),
    )

    // Le etichette dei posti sopra la mappa, sotto i controlli.
    RadarPins(pins, map)

    // X in alto a sinistra.
    FluidGlassIconButton(
      onClick = onBack,
      backdrop = chromeBackdrop,
      modifier = Modifier
        .align(Alignment.TopStart)
        .statusBarsPadding()
        .padding(12.dp),
    ) {
      Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.common_close), tint = White, modifier = Modifier.size(20.dp))
    }

    // Selettore dei livelli in alto a destra: il tasto che diventa il proprio menu'.
    val menu = rememberFluidMorphMenuState()
    var menuBounds by remember { mutableStateOf<Rect?>(null) }
    // Le etichette si leggono nel composable; il menu' le riceve gia' pronte.
    val layerLabels = RadarLayer.entries.associateWith { candidate ->
      val note = candidate.unavailableNoteRes()
      if (candidate.available(owmKey != null) || note == null) {
        stringResource(candidate.labelRes)
      } else {
        stringResource(candidate.labelRes) + " · " + stringResource(note)
      }
    }
    val layerActions = {
      RadarLayer.entries.map { candidate ->
        val available = candidate.available(owmKey != null)
        FluidContextAction(
          label = layerLabels.getValue(candidate),
          icon = layerIcon(candidate),
          enabled = available,
        ) { layer = candidate }
      }
    }
    Box(
      Modifier
        .align(Alignment.TopEnd)
        .statusBarsPadding()
        .padding(12.dp)
        .onGloballyPositioned { menuBounds = it.boundsInRoot() },
    ) {
      FluidMorphMenuButton(
        state = menu,
        actions = layerActions,
        onClick = { menuBounds?.let { menu.open(it, null, Icons.Rounded.Layers, layerActions()) } },
        icon = Icons.Rounded.Layers,
        backdrop = chromeBackdrop,
        modifier = Modifier.fluidTutorialAnchor("radar_layers_button"),
      )
    }

    // Legenda in basso a sinistra, sopra la barra del tempo.
    if (layer == RadarLayer.PRECIPITATION) {
      RadarLegend(
        backdrop = chromeBackdrop,
        modifier = Modifier
          .align(Alignment.BottomStart)
          .padding(start = 12.dp, bottom = navigationBottom + barHeight + 30.dp),
      )
    }

    // Tasto posizione in basso a destra.
    if (hasLocationPermission) {
      FluidGlassIconButton(
        onClick = {
          scope.launch {
            deps.locationProvider.snapshot()?.let { map.flyTo(it.latitude, it.longitude, googleZoom = 8.0) }
          }
        },
        backdrop = chromeBackdrop,
        modifier = Modifier
          .align(Alignment.BottomEnd)
          .padding(end = 12.dp, bottom = navigationBottom + barHeight + 8.dp),
      ) {
        Icon(
          Icons.Rounded.MyLocation,
          contentDescription = stringResource(R.string.place_my_location),
          tint = White,
          modifier = Modifier
            .size(20.dp)
            .fluidTutorialAnchor("radar_locate_button"),
        )
      }
    }

    // La barra del tempo, con l'attribuzione sotto.
    RadarTimelineBar(
      frames = frames,
      index = frameIndex,
      playing = playing,
      backdrop = chromeBackdrop,
      onTogglePlay = { playing = !playing },
      onScrub = { chosen ->
        playing = false
        frameIndex = chosen
      },
      onAttribution = map::showAttribution,
      modifier = Modifier
        .align(Alignment.BottomCenter)
        .padding(start = 12.dp, end = 12.dp, bottom = navigationBottom + 8.dp)
        .fluidTutorialAnchor("radar_timeline_bar"),
    )

    // I suggerimenti del radar: la linea del tempo, gli strati, il tasto che riporta sul posto.
    TutorialSlot(screen = TutorialScreen.RADAR, loading = frames == null)

    FluidMorphMenuHost(state = menu, backdrop = chromeBackdrop)
  }
}

private fun layerIcon(layer: RadarLayer): ImageVector = when (layer) {
  RadarLayer.PRECIPITATION -> Icons.Rounded.WaterDrop
  RadarLayer.TEMPERATURE -> Icons.Rounded.Thermostat
  RadarLayer.AIR_QUALITY -> Icons.Rounded.BlurOn
  RadarLayer.WIND -> Icons.Rounded.Air
}

/**
 * Le etichette dei posti, appoggiate sulla mappa con la punta in basso al centro sul punto (come
 * l'`anchor (0.5, 1)` dei marker di Google). Sono Compose sopra la mappa, non simboli dentro lo
 * stile: restano esattamente [PinLabel], con il tema e le unita' dell'utente. Il posto si legge
 * nella fase di piazzamento, quindi un trascinamento le sposta senza ricomporre niente; senza
 * tocchi propri, il dito che parte da un'etichetta muove la mappa sotto.
 */
@Composable
private fun RadarPins(pins: List<RadarPin>, map: RadarMapController) {
  pins.forEach { pin ->
    key(pin.name, pin.latitude, pin.longitude) {
      Box(
        Modifier.layout { measurable, _ ->
          val placeable = measurable.measure(Constraints())
          layout(placeable.width, placeable.height) {
            // La lettura che fa ripiazzare l'etichetta a ogni movimento della camera.
            map.cameraTick.intValue
            val point = map.screenPoint(pin.latitude, pin.longitude) ?: return@layout
            placeable.place(
              (point.x - placeable.width / 2f).roundToInt(),
              (point.y - placeable.height).roundToInt(),
            )
          }
        },
      ) {
        PinLabel(pin)
      }
    }
  }
}

/**
 * L'etichetta di una localita' sulla mappa.
 *
 * Il vetro vero, che campiona cio' che ha dietro, qui non puo' esistere: la mappa non e' una
 * sorgente di vetro (vedi [RadarShell]). Si prende allora la stessa pellicola dei controlli di
 * vetro (il velo e il filo di luce del tema), cosi' l'etichetta somiglia ai tasti accanto e segue
 * il tema: prima era un nero scritto a mano, e con l'app in chiaro restava l'unica cosa scura.
 */
@Composable
private fun PinLabel(pin: RadarPin) {
  val shape = ContinuousCornerShape(FluidRadius.Group)
  val film = GlassDefaults.floatingTint()
  Row(
    verticalAlignment = Alignment.CenterVertically,
    modifier = Modifier
      .background(Panel, shape)
      .border(1.dp, film.hairline, shape)
      .padding(horizontal = 10.dp, vertical = 6.dp),
  ) {
    Text(pin.name, style = MaterialTheme.typography.labelMedium, color = White)
    val temperature = pin.temperatureC
    if (temperature != null) {
      // Le unita' dell'utente anche qui: l'etichetta scriveva i gradi a mano, quindi in
      // Fahrenheit mostrava i Celsius.
      val units = rememberUnitFormatter()
      Spacer(Modifier.width(6.dp))
      Text(
        units.degrees(temperature),
        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
        color = Accent,
      )
    }
  }
}

/** La legenda: i colori VERI dello schema dei tile (tabella ufficiale RainViewer), dal debole al forte. */
@Composable
private fun RadarLegend(backdrop: GlassBackdropState, modifier: Modifier = Modifier) {
  val stops = RainViewerPalette.universalBlue.filter { it.dbz >= 10 }
  Column(
    modifier = modifier
      .width(168.dp)
      .radarChrome(backdrop)
      .padding(10.dp),
  ) {
    Text(stringResource(R.string.radar_precipitation), style = MaterialTheme.typography.labelSmall, color = Faint)
    Spacer(Modifier.height(6.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(1.dp)) {
      stops.forEach { stop ->
        Box(
          Modifier
            .weight(1f)
            .height(8.dp)
            .background(Color(stop.argb), ContinuousCornerShape(2.dp)),
        )
      }
    }
    Spacer(Modifier.height(4.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
      Text(stringResource(R.string.radar_legend_light), style = MaterialTheme.typography.labelSmall, color = Faint)
      Text(stringResource(R.string.radar_legend_heavy), style = MaterialTheme.typography.labelSmall, color = Faint)
      Text(stringResource(R.string.radar_legend_hail), style = MaterialTheme.typography.labelSmall, color = Faint)
    }
  }
}

@Composable
private fun RadarTimelineBar(
  frames: RadarFrames?,
  index: Int,
  playing: Boolean,
  onTogglePlay: () -> Unit,
  onScrub: (Int) -> Unit,
  onAttribution: () -> Unit,
  backdrop: GlassBackdropState,
  modifier: Modifier = Modifier,
) {
  val haptics = rememberFluidHaptics()
  Column(modifier.fillMaxWidth()) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      modifier = Modifier
        .fillMaxWidth()
        .radarChrome(backdrop)
        .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
      IconButton(
        onClick = {
          haptics.play(FluidHapticEvent.Tap)
          onTogglePlay()
        },
        enabled = frames != null,
      ) {
        Icon(
          imageVector = if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
          contentDescription = if (playing) stringResource(R.string.radar_pause) else stringResource(R.string.radar_play),
          tint = White,
        )
      }
      val count = frames?.all?.size ?: 0
      val nowIndex = frames?.nowIndex ?: 0
      // Dentro il Canvas non si e' piu' in composizione: i colori del tema si leggono qui.
      val trackColour = Faint
      val pastColour = Accent
      val markColour = White
      val nowcastCount = frames?.nowcast?.size ?: 0
      Canvas(
        Modifier
          .weight(1f)
          .height(36.dp)
          .pointerInput(count, haptics) {
            detectTapGestures { offset ->
              haptics.play(FluidHapticEvent.Tap)
              onScrub(RadarTimeline.indexForFraction(offset.x / size.width, count))
            }
          }
          .pointerInput(count, haptics) {
            // Un fotogramma per tacca: trascinando in fretta ne passano tanti, e la tacca
            // fitta e' quella leggera (il motore le dirada comunque a 40 ms l'una).
            var last = -1
            detectHorizontalDragGestures(
              onDragStart = { offset ->
                val chosen = RadarTimeline.indexForFraction(offset.x / size.width, count)
                last = chosen
                haptics.play(FluidHapticEvent.Tick)
                onScrub(chosen)
              },
            ) { change, _ ->
              change.consume()
              val chosen = RadarTimeline.indexForFraction(change.position.x / size.width, count)
              if (chosen != last) {
                last = chosen
                haptics.play(FluidHapticEvent.FrequentTick)
              }
              onScrub(chosen)
            }
          },
      ) {
        val y = size.height / 2f
        val inset = 8f
        val trackEnd = size.width - inset
        drawLine(trackColour.copy(alpha = 0.3f), Offset(inset, y), Offset(trackEnd, y), strokeWidth = 4f, cap = StrokeCap.Round)
        if (count > 1) {
          val nowX = inset + RadarTimeline.fractionForIndex(nowIndex, count) * (trackEnd - inset)
          // Il passato in pieno, il nowcast tratteggiato dopo "adesso". Il tratto c'e' solo se il
          // servizio manda fotogrammi futuri: il JSON pubblico di RainViewer oggi ne manda zero
          // (`nowcast: []`, verificato il 2026-09-10 — la previsione e' passata al piano a
          // pagamento), quindi di solito il radar finisce al presente, e la barra non finge.
          drawLine(pastColour.copy(alpha = 0.55f), Offset(inset, y), Offset(nowX, y), strokeWidth = 4f, cap = StrokeCap.Round)
          if (nowcastCount > 0) {
            drawLine(
              pastColour.copy(alpha = 0.55f),
              Offset(nowX, y),
              Offset(trackEnd, y),
              strokeWidth = 4f,
              cap = StrokeCap.Round,
              pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 8f)),
            )
            // Una tacca per fotogramma futuro: sono pochi, e ognuno e' dieci minuti.
            for (step in 1..nowcastCount) {
              val tickX = inset + RadarTimeline.fractionForIndex(nowIndex + step, count) * (trackEnd - inset)
              drawLine(trackColour.copy(alpha = 0.6f), Offset(tickX, y - 4f), Offset(tickX, y + 4f), strokeWidth = 2f)
            }
          }
          drawLine(markColour, Offset(nowX, y - 9f), Offset(nowX, y + 9f), strokeWidth = 2f)
          val thumbX = inset + RadarTimeline.fractionForIndex(index, count) * (trackEnd - inset)
          drawCircle(markColour, radius = 8f, center = Offset(thumbX, y))
        }
      }
      val label = if (frames == null || count == 0) {
        stringResource(R.string.radar_loading)
      } else {
        timelineLabel(RadarTimeline.offset(frames.all[index.coerceIn(0, count - 1)].timeMillis, frames.past.last().timeMillis))
      }
      Text(
        label,
        style = MaterialTheme.typography.labelMedium,
        color = White,
        textAlign = TextAlign.End,
        modifier = Modifier.width(96.dp),
      )
    }
    // L'attribuzione del radar e della mappa, sempre a vista: OpenFreeMap la chiede scritta
    // ("© OpenMapTiles", i dati di OpenStreetMap) a chi non usa MapLibre GL JS. Un tocco apre le
    // fonti con i loro link.
    Text(
      stringResource(R.string.radar_attribution, RainViewerClient.ATTRIBUTION),
      style = MaterialTheme.typography.labelSmall,
      color = Faint,
      textAlign = TextAlign.End,
      modifier = Modifier
        .fillMaxWidth()
        .clickable(
          onClickLabel = stringResource(R.string.radar_attribution_open),
          role = Role.Button,
          onClick = onAttribution,
        )
        .padding(top = 4.dp, end = 4.dp),
    )
  }
}

/**
 * Il vetro dei pannelli del radar (legenda, barra del tempo): lo stesso materiale dei tasti
 * accanto, cosi' la schermata ha una sola pellicola. La mappa e' una View di sistema e il vetro
 * non puo' campionarla: resta il velo con il filo di luce e l'ombra, che e' il degrado dichiarato
 * in [RadarShell] — ma e' lo stesso dei tasti, mentre prima legenda e barra erano pannelli pieni.
 */
@Composable
private fun Modifier.radarChrome(backdrop: GlassBackdropState): Modifier = glassSurface(
  state = backdrop,
  tint = GlassDefaults.floatingTint(),
  shape = ContinuousCornerShape(FluidRadius.Group),
  role = GlassRole.Floating,
)

/** "adesso", "−1 h 40 min", "+20 min": la distanza del fotogramma, nella lingua del telefono. */
@Composable
private fun timelineLabel(offset: RadarTimeline.Offset): String = when {
  offset.isNow -> stringResource(R.string.radar_now)
  offset.hours == 0 -> stringResource(R.string.radar_offset_min, offset.sign, offset.rest)
  offset.rest == 0 -> stringResource(R.string.radar_offset_h, offset.sign, offset.hours)
  else -> stringResource(R.string.radar_offset_h_min, offset.sign, offset.hours, offset.rest)
}
