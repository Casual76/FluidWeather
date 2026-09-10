package dev.pampa.fluidweather.feature.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Air
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.Nightlight
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.WaterDrop
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import dev.antigravity.fluidengine.foundation.EngineSettings
import dev.antigravity.fluidengine.foundation.ThemeMode
import dev.antigravity.fluidengine.ui.fluid.FluidEdgeOverscrollState
import dev.antigravity.fluidengine.ui.fluid.FluidGlassButton
import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.ScrollableDefaults
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.onSizeChanged
import dev.antigravity.fluidengine.ui.fluid.FluidGlassModalHost
import dev.antigravity.fluidengine.ui.fluid.FluidGlassQuality
import dev.antigravity.fluidengine.ui.fluid.FluidScreenDefaults
import dev.antigravity.fluidengine.ui.fluid.FluidSpinner
import dev.antigravity.fluidengine.ui.fluid.GlassBackdropState
import dev.antigravity.fluidengine.ui.fluid.LocalFluidGlassModalHostState
import dev.antigravity.fluidengine.ui.fluid.LocalFluidMotionPolicy
import dev.antigravity.fluidengine.ui.fluid.fluidGlassQualityScrollConnection
import dev.antigravity.fluidengine.ui.fluid.rememberFluidGlassModalHostState
import dev.antigravity.fluidengine.ui.haptics.FluidHapticEvent
import dev.antigravity.fluidengine.ui.haptics.rememberFluidHaptics
import dev.antigravity.fluidengine.ui.tutorial.fluidTutorialAnchor
import dev.pampa.fluidweather.core.ui.HeaderCollapse
import dev.antigravity.fluidengine.ui.fluid.LocalFluidCanvasBackdrop
import dev.antigravity.fluidengine.ui.fluid.LocalFluidGlassQuality
import dev.antigravity.fluidengine.ui.fluid.glassBackdropSource
import dev.antigravity.fluidengine.ui.fluid.rememberCombinedGlassBackdrop
import dev.antigravity.fluidengine.ui.fluid.rememberFluidGlassQuality
import dev.antigravity.fluidengine.ui.fluid.rememberGlassBackdrop
import dev.antigravity.fluidengine.ui.fluidphysics.FluidMorphMenuHost
import dev.antigravity.fluidengine.ui.fluidphysics.rememberFluidMorphMenuState
import dev.antigravity.fluidengine.ui.theme.FluidTheme
import dev.pampa.fluidweather.core.model.AppearanceSettings
import dev.pampa.fluidweather.core.model.GlassLevel
import dev.pampa.fluidweather.core.model.GlassPolicy
import dev.pampa.fluidweather.core.model.Place
import dev.pampa.fluidweather.core.model.PlaceCycle
import dev.pampa.fluidweather.core.model.WeatherKind
import dev.pampa.fluidweather.core.ui.GlassTile
import dev.pampa.fluidweather.core.ui.GridReorder
import dev.pampa.fluidweather.core.ui.HomeWidget
import dev.pampa.fluidweather.core.ui.SkyState
import dev.pampa.fluidweather.core.ui.TutorialScreen
import dev.pampa.fluidweather.core.ui.TutorialSlot
import dev.pampa.fluidweather.core.ui.WeatherAccent
import dev.pampa.fluidweather.core.ui.WeatherScene
import dev.pampa.fluidweather.core.ui.deviceGlassTier
import dev.pampa.fluidweather.core.ui.isPowerSaveActive
import dev.pampa.fluidweather.core.ui.toGlassCeiling
import dev.pampa.fluidweather.core.ui.toSceneQuality
import dev.pampa.fluidweather.nowcast.verdict.AlertLevel
import dev.pampa.fluidweather.nowcast.verdict.NowcastVerdict
import dev.pampa.fluidweather.strings.dataAgeLabel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import dev.pampa.fluidweather.strings.R
import androidx.compose.ui.res.stringResource
import dev.pampa.fluidweather.core.ui.rememberUnitFormatter
import dev.pampa.fluidweather.strings.labelRes
import dev.pampa.fluidweather.strings.windowLabelRes

/**
 * La home: cielo a tutto schermo, testata gigante che collassa con parallasse, griglia di
 * tessere in vetro riordinabile a pressione lunga, barra flottante a tre isole. Qui comanda
 * il cielo: il sottoalbero e' sempre in tema scuro, qualunque cosa dica il sistema.
 */
@Composable
fun HomeScreen(
  deps: HomeDependencies,
  onOpenRadar: () -> Unit,
  onOpenBenchmark: () -> Unit,
  onOpenSettings: () -> Unit,
  onOpenReport: () -> Unit,
  /** Il tasto dell'assistente nella barra (fase 19); null = niente tasto. */
  assistant: HomeAssistantBar? = null,
  /** Una pagina nera di widget chiesta da fuori (l'assistente, un chip): si apre e si consuma. */
  requestedWidget: HomeWidget? = null,
  onWidgetConsumed: () -> Unit = {},
  /** Cio' che sta sopra tutto (l'overlay dell'assistente), col backdrop della chrome per il vetro. */
  overlay: @Composable BoxScope.(GlassBackdropState) -> Unit = {},
) {
  val context = LocalContext.current
  val appearance by deps.appearanceStore.settings.collectAsState(initial = AppearanceSettings())
  val deviceTier = remember { deviceGlassTier(context) }
  val powerSave = remember { isPowerSaveActive(context) }
  val glassLevel = GlassPolicy.resolve(appearance, powerSave, deviceTier)

  // La localita' che comanda la home: GPS o una salvata (fase 10).
  val places by deps.savedLocations.places.collectAsState(initial = listOf(Place.gps()))
  val selectedId by deps.selectedPlaceStore.selectedId.collectAsState(initial = Place.GPS_ID)
  val selectedPlace = places.firstOrNull { it.id == selectedId } ?: Place.gps()

  val home = rememberHomeState(deps, selectedPlace)
  val state by home.state
  val haptics = rememberFluidHaptics()

  // La taratura parte dall'onboarding e finisce qui, minuti dopo, con l'utente che guarda altro:
  // il momento in cui il barometro diventa tarato e' l'unico che vale la pena far sentire.
  val calibrating = state.readiness?.calibrationRunning == true
  var wasCalibrating by remember { mutableStateOf(false) }
  LaunchedEffect(calibrating) {
    if (calibrating) {
      wasCalibrating = true
    } else if (wasCalibrating) {
      wasCalibrating = false
      haptics.play(FluidHapticEvent.Success)
    }
  }

  FluidTheme(
    settings = remember { EngineSettings(themeMode = ThemeMode.DARK, dynamicColorEnabled = false) },
    brand = WeatherAccent.presetFor(state.kind, state.phase),
  ) {
    HomeShell(
      state = state,
      glassLevel = glassLevel,
      deps = deps,
      places = places,
      selectedPlace = selectedPlace,
      onOpenRadar = onOpenRadar,
      onOpenBenchmark = onOpenBenchmark,
      onOpenSettings = onOpenSettings,
      onOpenReport = onOpenReport,
      assistant = assistant,
      onRefresh = home.refresh,
      requestedWidget = requestedWidget,
      onWidgetConsumed = onWidgetConsumed,
      overlay = overlay,
    )
  }
}

@Composable
private fun HomeShell(
  state: HomeUiState,
  glassLevel: GlassLevel,
  deps: HomeDependencies,
  places: List<Place>,
  selectedPlace: Place,
  onOpenRadar: () -> Unit,
  onOpenBenchmark: () -> Unit,
  onOpenSettings: () -> Unit,
  onOpenReport: () -> Unit,
  assistant: HomeAssistantBar?,
  onRefresh: () -> Unit,
  requestedWidget: HomeWidget?,
  onWidgetConsumed: () -> Unit,
  overlay: @Composable BoxScope.(GlassBackdropState) -> Unit,
) {
  val haptics = rememberFluidHaptics()
  val canvasBackdrop = rememberGlassBackdrop()
  val contentBackdrop = rememberGlassBackdrop()
  val chromeBackdrop = rememberCombinedGlassBackdrop(canvasBackdrop, contentBackdrop)
  val levelState = rememberUpdatedState(glassLevel)
  val glassQuality = rememberFluidGlassQuality(ceiling = { levelState.value.toGlassCeiling() })

  val gridState = rememberLazyGridState()
  val scope = rememberCoroutineScope()
  val reducedMotion = LocalFluidMotionPolicy.current.reducedMotion

  // Il pull to refresh e' lo stesso bordo elastico che l'engine usa nelle sue schermate
  // (`FluidEdgeOverscrollState`): un solo spostamento, la soglia e' un punto lungo quello
  // spostamento, e la rotella vive nello spazio che il gesto apre — cosi' arriva *con* il
  // contenuto invece di scorrergli sopra. La home non usa `FluidScreen` (e' una griglia sua),
  // quindi lo aggancia a mano, con gli stessi numeri.
  val overscroll = remember(reducedMotion, gridState) {
    FluidEdgeOverscrollState(
      reducedMotion = reducedMotion,
      canScroll = { gridState.canScrollForward || gridState.canScrollBackward },
    )
  }
  val density = LocalDensity.current
  val refreshTriggerPx = with(density) { FluidScreenDefaults.RefreshTrigger.toPx() }
  val refreshHoldPx = with(density) { FluidScreenDefaults.RefreshHold.toPx() }
  SideEffect {
    overscroll.refreshTriggerPx = refreshTriggerPx
    overscroll.refreshHoldPx = refreshHoldPx
    overscroll.onRefresh = onRefresh
  }
  LaunchedEffect(overscroll, state.refreshing) {
    if (!state.refreshing) overscroll.endRefresh()
  }

  // La testata ha due case e nessun indirizzo in mezzo: il fling finisce sempre il viaggio.
  val collapse = remember { HeaderCollapseState() }
  val platformFling = ScrollableDefaults.flingBehavior()
  val snapFling = remember(platformFling, reducedMotion, gridState, collapse) {
    HeaderSnapFlingBehavior(
      delegate = platformFling,
      animated = !reducedMotion,
      snapDeltaPx = {
        HeaderCollapse.snapDelta(
          firstVisibleIndex = gridState.firstVisibleItemIndex,
          scrolledPx = gridState.firstVisibleItemScrollOffset.toFloat(),
          travelPx = collapse.travelPx,
        )
      },
    )
  }

  // L'ordine: quello salvato, con le modifiche in corso sopra; si persiste al rilascio.
  val storedOrder by deps.layoutStore.order.collectAsState(initial = emptyList())
  var liveOrder by remember { mutableStateOf<List<String>?>(null) }
  val order = liveOrder ?: HomeWidget.ordered(storedOrder).map { it.id }
  val drag = remember(gridState) { GridDragController(gridState) }
  var selectedWidget by remember { mutableStateOf<HomeWidget?>(null) }
  // Un widget chiesto da fuori (assistente, chip) apre la sua pagina nera come un tocco.
  LaunchedEffect(requestedWidget) {
    if (requestedWidget != null) {
      selectedWidget = requestedWidget
      onWidgetConsumed()
    }
  }

  // La pillola e il suo pannello: il pannello nasce dal rettangolo della pillola.
  var locationOpen by remember { mutableStateOf(false) }
  var pillBounds by remember { mutableStateOf<Rect?>(null) }
  val modalHost = rememberFluidGlassModalHostState()

  // Quando un foglio nero o il pannello dei posti copre la home, sotto non c'e' niente da
   // guardare: la scena si ferma, e con lei le registrazioni del vetro che ne dipendono. E'
   // esattamente il fotogramma in cui la pagina di un widget sta componendo migliaia di nodi.
  val covered = selectedWidget != null || locationOpen
  // Durante lo scorrimento le sorgenti si congelano: il riflesso nella barra non insegue piu' il
  // contenuto per la durata del fling, e in cambio non si ri-registra due volte per fotogramma
  // tutto quello che c'e' sullo schermo.
  val scrolling = remember(gridState) { { gridState.isScrollInProgress } }

  Box(Modifier.fillMaxSize()) {
    WeatherScene(
      state = SkyState(state.phase, state.kind, state.cloudCover, state.latitude, state.longitude),
      quality = glassLevel.toSceneQuality(),
      running = !covered,
      modifier = Modifier
        .fillMaxSize()
        .glassBackdropSource(canvasBackdrop, frozen = scrolling),
    )

    CompositionLocalProvider(
      LocalFluidCanvasBackdrop provides canvasBackdrop.takeIf { glassLevel != GlassLevel.OFF },
      LocalFluidGlassQuality provides glassQuality,
      LocalFluidGlassModalHostState provides modalHost,
    ) {
      HomeGrid(
        state = state,
        gridState = gridState,
        overscroll = overscroll,
        collapse = collapse,
        flingBehavior = snapFling,
        order = order,
        quality = glassQuality,
        drag = drag,
        onMove = { dragged, target -> liveOrder = GridReorder.moved(order, dragged, target) },
        onDrop = {
          val finalOrder = liveOrder
          // L'ordine torna a essere quello salvato: tenendo `liveOrder` per sempre, la griglia
          // ignorava ogni aggiornamento successivo dell'ordine (per esempio dalle impostazioni).
          liveOrder = null
          if (finalOrder != null) scope.launch { deps.layoutStore.setOrder(finalOrder) }
        },
        onOpenWidget = { selectedWidget = it },
        modifier = Modifier
          .fillMaxSize()
          .glassBackdropSource(contentBackdrop, frozen = scrolling),
      )

      CompactHeader(
        state = state,
        gridState = gridState,
        collapse = collapse,
        backdrop = chromeBackdrop,
        onTap = { scope.launch { gridState.animateScrollToItem(0) } },
        hidden = assistant?.active == true,
        modifier = Modifier.align(Alignment.TopCenter),
      )

      WidgetSheetHost(
        selected = selectedWidget,
        state = state,
        deps = deps,
        onDismiss = { selectedWidget = null },
      )

      val morphMenu = rememberFluidMorphMenuState()
      HomeFloatingBar(
        locationName = if (selectedPlace.isGps) state.locationName else selectedPlace.name,
        backdrop = chromeBackdrop,
        menuState = morphMenu,
        locationExpanded = locationOpen,
        onLocationBounds = { pillBounds = it },
        onOpenRadar = onOpenRadar,
        onOpenBenchmark = onOpenBenchmark,
        onOpenSettings = onOpenSettings,
        onOpenReport = onOpenReport,
        onLocationTap = { locationOpen = true },
        assistant = assistant,
        onLocationSwipe = { forward ->
          val target = if (forward) {
            PlaceCycle.next(places, selectedPlace.id)
          } else {
            PlaceCycle.previous(places, selectedPlace.id)
          }
          if (target != null) deps.selectedPlaceStore.select(target.id)
        },
        modifier = Modifier
          .align(Alignment.BottomCenter)
          .navigationBarsPadding()
          .padding(bottom = 10.dp),
      )
      FluidMorphMenuHost(state = morphMenu, backdrop = chromeBackdrop)

      LocationPane(
        open = locationOpen,
        origin = { pillBounds },
        places = places,
        selectedId = selectedPlace.id,
        deps = deps,
        onDismiss = { locationOpen = false },
        onSelect = { place -> deps.selectedPlaceStore.select(place.id) },
        onSaveAndSelect = { place ->
          scope.launch {
            deps.savedLocations.save(place)
            deps.selectedPlaceStore.select(place.id)
            haptics.play(FluidHapticEvent.Confirm)
          }
        },
        onRemove = { place ->
          scope.launch {
            deps.savedLocations.remove(place.id)
            if (place.id == selectedPlace.id) deps.selectedPlaceStore.select(Place.GPS_ID)
            haptics.play(FluidHapticEvent.Reject)
          }
        },
      )
      // La rotella dell'aggiornamento: si rivela mentre il dito tira e resta finche' il giro
      // non e' finito. Niente disco che galleggia: sta a meta' dello spazio aperto.
      FluidSpinner(
        modifier = Modifier
          .align(Alignment.TopCenter)
          .statusBarsPadding()
          .graphicsLayer {
            val opened = overscroll.offsetPx.coerceAtLeast(0f)
            alpha = if (state.refreshing) 1f else smoothPull(overscroll.refreshPull)
            translationY = opened * 0.5f - 12.dp.toPx()
          },
        size = 24.dp,
        progress = if (state.refreshing) null else ({ overscroll.refreshPull }),
      )

      // I suggerimenti della home: la griglia che si riordina, la pillola, il tasto dell'IA.
      TutorialSlot(
        screen = TutorialScreen.HOME,
        busy = gridState.isScrollInProgress,
        // Con un foglio o il pannello dei posti aperti la home e' coperta: un callout qui sotto
        // verrebbe segnato come visto senza che nessuno l'abbia visto davvero.
        loading = state.loading || selectedWidget != null || locationOpen,
        hidden = if (state.verdict == null || state.verdict?.level == AlertLevel.QUIETE) setOf("home_alert_row") else emptySet(),
      )
      // Ultimo nella scatola: il pannello di vetro sta sopra la barra e sopra il menu'.
      FluidGlassModalHost(state = modalHost, backdrop = chromeBackdrop)
      // Sopra tutto, l'overlay dell'assistente (fase 19): aureola, barra, card.
      overlay(chromeBackdrop)
    }
  }
}

// ------------------------------------------------------------------------------------ griglia

private const val HEADER_KEY = "header"
private const val ALERT_KEY = "alert"

@Composable
private fun HomeGrid(
  state: HomeUiState,
  gridState: LazyGridState,
  collapse: HeaderCollapseState,
  flingBehavior: FlingBehavior,
  order: List<String>,
  quality: FluidGlassQuality,
  overscroll: FluidEdgeOverscrollState,
  drag: GridDragController,
  onMove: (String, String) -> Unit,
  onDrop: () -> Unit,
  onOpenWidget: (HomeWidget) -> Unit,
  modifier: Modifier = Modifier,
) {
  val haptics = rememberFluidHaptics()
  // La leva che l'engine ha e che nessuno tirava: mentre si scorre, lente, dispersione, ombre e
  // soprattutto la risoluzione della cattura scendono, e risalgono appena il dito si ferma.
  val qualityScroll = remember(quality) { fluidGlassQualityScrollConnection(quality) }
  val visible = remember(order, state.barometerApplies) {
    HomeWidget.visibleIds(order, state.barometerApplies)
  }
  LazyVerticalGrid(
    columns = GridCells.Fixed(2),
    state = gridState,
    flingBehavior = flingBehavior,
    modifier = modifier
      .nestedScroll(qualityScroll)
      .nestedScroll(overscroll)
      // Un solo spostamento per tutta la pagina: il bordo apre lo spazio, la griglia lo segue.
      .graphicsLayer { translationY = overscroll.offsetPx }
      .onSizeChanged { overscroll.updateViewport(it.height.toFloat()) }
      .pointerInput(haptics) {
      // Un tick a ogni aggancio, non a ogni fotogramma: il bersaglio resta lo stesso finche' il
      // dito non entra in un'altra cella, e ripetere la vibrazione la renderebbe un ronzio.
      var lastTarget: String? = null
      detectDragGesturesAfterLongPress(
        onDragStart = { offset ->
          drag.start(offset)
          lastTarget = null
          if (drag.draggingKey != null) haptics.play(FluidHapticEvent.GestureStart)
        },
        onDrag = { change, delta ->
          change.consume()
          val dragged = drag.draggingKey ?: return@detectDragGesturesAfterLongPress
          drag.move(delta)?.let { target ->
            onMove(dragged, target)
            if (target != lastTarget) {
              lastTarget = target
              haptics.play(FluidHapticEvent.Tick)
            }
          }
        },
        onDragEnd = {
          val wasDragging = drag.draggingKey != null
          drag.end()
          onDrop()
          if (wasDragging) haptics.play(FluidHapticEvent.GestureEnd)
        },
        onDragCancel = {
          drag.end()
          onDrop()
        },
      )
    },
    contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 0.dp, bottom = 130.dp),
    horizontalArrangement = Arrangement.spacedBy(12.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    item(key = HEADER_KEY, span = { GridItemSpan(2) }) {
      HomeHeader(state = state, gridState = gridState, collapse = collapse)
    }

    val verdict = state.verdict.takeIf { state.barometerApplies }
    if (verdict != null && verdict.level != AlertLevel.QUIETE) {
      item(key = ALERT_KEY, span = { GridItemSpan(2) }) {
        BarometerAlertRow(verdict)
      }
    }

    items(
      items = visible,
      key = { it },
      span = { id -> GridItemSpan(HomeWidget.entries.firstOrNull { it.id == id }?.span ?: 2) },
    ) { id ->
      val widget = HomeWidget.entries.firstOrNull { it.id == id } ?: return@items
      val dragging = drag.draggingKey == id
      // La prima tessera fa da ancora ai due suggerimenti della griglia (fase 21).
      val anchor = if (order.firstOrNull() == id) Modifier.fluidTutorialAnchor("home_first_tile") else Modifier
      GlassTile(
        onClick = { onOpenWidget(widget) },
        onClickLabel = stringResource(R.string.a11y_open_widget, stringResource(widget.titleRes)),
        modifier = anchor
          .zIndex(if (dragging) 1f else 0f)
          .then(if (dragging) Modifier else Modifier.animateItem())
          .graphicsLayer {
            if (dragging) {
              val bounds = drag.boundsOf(id)
              if (bounds != null) {
                translationX = drag.pointer.x - (bounds.left + bounds.width / 2f)
                translationY = drag.pointer.y - (bounds.top + bounds.height / 2f)
              }
              scaleX = 1.05f
              scaleY = 1.05f
              shadowElevation = 30f
            }
          }
          .heightIn(min = if (widget.span == 2) 148.dp else 168.dp),
      ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Icon(
            imageVector = widgetIcon(widget),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
            modifier = Modifier.size(16.dp),
          )
          Spacer(Modifier.size(6.dp))
          Text(
            text = stringResource(widget.titleRes).uppercase(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
          )
        }
        Spacer(Modifier.height(10.dp))
        WidgetTileContent(widget = widget, state = state)
      }
    }
  }
}

private fun widgetIcon(widget: HomeWidget) = when (widget) {
  HomeWidget.NOWCAST -> Icons.Rounded.Insights
  HomeWidget.HOURLY -> Icons.Rounded.Schedule
  HomeWidget.DAILY -> Icons.Rounded.CalendarMonth
  HomeWidget.PRECIPITATION -> Icons.Rounded.WaterDrop
  HomeWidget.PRESSURE -> Icons.Rounded.Speed
  HomeWidget.AIR_QUALITY -> Icons.Rounded.Air
  HomeWidget.SUN -> Icons.Rounded.WbSunny
  HomeWidget.MOON -> Icons.Rounded.Nightlight
  HomeWidget.DETAILS -> Icons.Rounded.GridView
}

// ------------------------------------------------------------------------------------ testata

@Composable
private fun HomeHeader(state: HomeUiState, gridState: LazyGridState, collapse: HeaderCollapseState) {
  val shadow = Shadow(color = Color.Black.copy(alpha = 0.35f), blurRadius = 14f)
  Column(
    horizontalAlignment = Alignment.CenterHorizontally,
    modifier = Modifier
      .fillMaxWidth()
      .statusBarsPadding()
      .padding(top = 34.dp, bottom = 18.dp)
      // La testata misura da sola il proprio viaggio: e' la sua altezza.
      .onSizeChanged { collapse.travelPx = it.height.toFloat() }
      .graphicsLayer {
        // Parallasse: la testata scorre a meta' velocita' della pagina e sfuma prima di
        // toccare la barra compatta. Legge lo stato QUI, cosi' invalida solo il disegno.
        val travel = collapse.travelPx
        val offset = minOf(collapse.scrolledPx(gridState), travel)
        val progress = HeaderCollapse.progress(offset, travel)
        translationY = offset * 0.45f
        alpha = 1f - progress * 1.15f
      },
  ) {
    val units = rememberUnitFormatter()
    Text(
      text = state.locationName ?: if (state.hasLocation) stringResource(R.string.place_my_location) else stringResource(R.string.place_unknown),
      style = MaterialTheme.typography.titleLarge.copy(shadow = shadow),
      color = Color.White,
    )
    Text(
      text = state.temperatureC?.let { units.degrees(it) } ?: "—",
      fontSize = 108.sp,
      fontWeight = FontWeight.ExtraLight,
      style = MaterialTheme.typography.displayLarge.copy(shadow = shadow),
      color = Color.White,
    )
    Text(
      text = kindLabel(state.kind) ?: if (state.loading) "…" else "",
      style = MaterialTheme.typography.titleMedium.copy(shadow = shadow),
      color = Color.White.copy(alpha = 0.92f),
      textAlign = TextAlign.Center,
    )
    if (state.maxC != null && state.minC != null) {
      Text(
        text = stringResource(R.string.home_max_min, units.degrees(state.maxC), units.degrees(state.minC)),
        style = MaterialTheme.typography.titleSmall.copy(shadow = shadow),
        color = Color.White.copy(alpha = 0.9f),
        modifier = Modifier.padding(top = 2.dp),
      )
    }
    // L'eta' dei dati, una riga sola e solo qui: le tessere non si marcano (decisione dell'utente).
    // `dataAtMillis` e' fisso ma "adesso" no, quindi senza un orologio la riga si congelerebbe su
    // "aggiornato 2 h fa" per sempre. Il battito e' della testata: esce di composizione con lei.
    var nowMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
      while (true) {
        delay(60_000L)
        nowMillis = System.currentTimeMillis()
      }
    }
    val age = dataAgeLabel(LocalContext.current.resources, state.dataAtMillis, nowMillis, state.loading)
    if (age != null) {
      Text(
        text = age,
        style = MaterialTheme.typography.titleSmall.copy(shadow = shadow),
        color = Color.White.copy(alpha = 0.75f),
        modifier = Modifier.padding(top = 6.dp),
      )
    }
  }
}

/**
 * La pillola compatta che prende il posto della testata quando questa se n'e' andata. Vetro
 * vero, dello stesso backdrop della barra in basso: la prima versione non riceveva nessun
 * backdrop e sul telefono era solo trasparente (2026-09-02). Un tocco riporta in cima.
 */
@Composable
private fun CompactHeader(
  state: HomeUiState,
  gridState: LazyGridState,
  collapse: HeaderCollapseState,
  backdrop: GlassBackdropState,
  onTap: () -> Unit,
  /** Con l'assistente in scena la riga compatta si ritira: e' proprio dove arriva l'aureola. */
  hidden: Boolean,
  modifier: Modifier = Modifier,
) {
  val collapsed by remember(gridState, collapse) {
    derivedStateOf { HeaderCollapse.compactVisible(collapse.progress(gridState)) }
  }
  Box(modifier = modifier.statusBarsPadding().padding(top = 6.dp)) {
    AnimatedVisibility(
      visible = collapsed && !hidden,
      enter = fadeIn() + slideInVertically { -it / 2 },
      exit = fadeOut() + slideOutVertically { -it / 2 },
    ) {
      val units = rememberUnitFormatter()
      FluidGlassButton(
        // `units.degrees` e non `toInt()`: la riga compatta scriveva i gradi a mano, quindi con
        // Fahrenheit selezionato mostrava i Celsius e tagliava i decimali verso lo zero.
        text = buildString {
          append(state.locationName ?: stringResource(R.string.place_my_location))
          state.temperatureC?.let { append("  ·  ${units.degrees(it)}") }
        },
        onClick = onTap,
        backdrop = backdrop,
      )
    }
  }
}

// -------------------------------------------------------------------------------------- righe

/** La riga d'allerta del barometro: compare solo quando il barometro ha qualcosa da dire. */
@Composable
private fun BarometerAlertRow(verdict: NowcastVerdict) {
  GlassTile {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Icon(
        imageVector = Icons.Rounded.Speed,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.primary,
        modifier = Modifier.size(18.dp),
      )
      Spacer(Modifier.size(8.dp))
      Column {
        Text(
          text = if (verdict.level == AlertLevel.ALLERTA) stringResource(R.string.notif_nowcast_title) else stringResource(R.string.level_watch),
          style = MaterialTheme.typography.titleSmall,
          color = MaterialTheme.colorScheme.onSurface,
        )
        val strongest = verdict.windows.maxByOrNull { it.probability }
        if (strongest != null) {
          Text(
            text = stringResource(R.string.home_banner_rain, stringResource(windowLabelRes(strongest.window)).lowercase(), (strongest.probability * 100).toInt()),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
          )
        }
      }
    }
  }
}

@Composable
private fun kindLabel(kind: WeatherKind?): String? = kind?.labelRes()?.let { stringResource(it) }

// ------------------------------------------------------------------------------ trascinamento

/** Il guscio dei gesti attorno a [GridReorder]: pointer nello spazio della griglia. */
private class GridDragController(private val gridState: LazyGridState) {

  var draggingKey by mutableStateOf<String?>(null)
    private set
  var pointer by mutableStateOf(Offset.Zero)
    private set

  private fun widgetCells(): List<GridReorder.CellBounds> =
    gridState.layoutInfo.visibleItemsInfo.mapNotNull { info ->
      val key = info.key as? String ?: return@mapNotNull null
      if (key == HEADER_KEY || key == ALERT_KEY) return@mapNotNull null
      GridReorder.CellBounds(
        key = key,
        left = info.offset.x.toFloat(),
        top = info.offset.y.toFloat(),
        width = info.size.width.toFloat(),
        height = info.size.height.toFloat(),
      )
    }

  fun start(position: Offset) {
    val cell = widgetCells().firstOrNull { it.contains(position.x, position.y) } ?: return
    draggingKey = cell.key
    pointer = position
  }

  fun move(delta: Offset): String? {
    val key = draggingKey ?: return null
    pointer += delta
    return GridReorder.targetKey(widgetCells(), key, pointer.x, pointer.y)
  }

  fun boundsOf(key: String): GridReorder.CellBounds? =
    widgetCells().firstOrNull { it.key == key }

  fun end() {
    draggingKey = null
  }
}

/** La rivelazione della rotella: compare nell'ultima meta' del tiro, non da subito. */
private fun smoothPull(pull: Float): Float {
  val t = ((pull - 0.35f) / 0.65f).coerceIn(0f, 1f)
  return t * t * (3f - 2f * t)
}
