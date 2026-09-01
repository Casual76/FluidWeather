package dev.pampa.fluidweather.feature.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import dev.antigravity.fluidengine.foundation.EngineSettings
import dev.antigravity.fluidengine.foundation.ThemeMode
import dev.antigravity.fluidengine.ui.fluid.FluidGlassButton
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
import dev.pampa.fluidweather.core.model.WeatherKind
import dev.pampa.fluidweather.core.ui.GlassTile
import dev.pampa.fluidweather.core.ui.GridReorder
import dev.pampa.fluidweather.core.ui.HomeWidget
import dev.pampa.fluidweather.core.ui.SkyState
import dev.pampa.fluidweather.core.ui.WeatherAccent
import dev.pampa.fluidweather.core.ui.WeatherScene
import dev.pampa.fluidweather.core.ui.deviceGlassTier
import dev.pampa.fluidweather.core.ui.isPowerSaveActive
import dev.pampa.fluidweather.core.ui.toGlassCeiling
import dev.pampa.fluidweather.core.ui.toSceneQuality
import dev.pampa.fluidweather.nowcast.verdict.AlertLevel
import dev.pampa.fluidweather.nowcast.verdict.NowcastVerdict
import kotlinx.coroutines.launch

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
) {
  val context = LocalContext.current
  val appearance by deps.appearanceStore.settings.collectAsState(initial = AppearanceSettings())
  val deviceTier = remember { deviceGlassTier(context) }
  val powerSave = remember { isPowerSaveActive(context) }
  val glassLevel = GlassPolicy.resolve(appearance, powerSave, deviceTier)

  val state by rememberHomeState(deps)

  FluidTheme(
    settings = remember { EngineSettings(themeMode = ThemeMode.DARK, dynamicColorEnabled = false) },
    brand = WeatherAccent.presetFor(state.kind, state.phase),
  ) {
    HomeShell(
      state = state,
      glassLevel = glassLevel,
      deps = deps,
      onOpenRadar = onOpenRadar,
      onOpenBenchmark = onOpenBenchmark,
      onOpenSettings = onOpenSettings,
      onOpenReport = onOpenReport,
    )
  }
}

@Composable
private fun HomeShell(
  state: HomeUiState,
  glassLevel: GlassLevel,
  deps: HomeDependencies,
  onOpenRadar: () -> Unit,
  onOpenBenchmark: () -> Unit,
  onOpenSettings: () -> Unit,
  onOpenReport: () -> Unit,
) {
  val canvasBackdrop = rememberGlassBackdrop()
  val contentBackdrop = rememberGlassBackdrop()
  val chromeBackdrop = rememberCombinedGlassBackdrop(canvasBackdrop, contentBackdrop)
  val levelState = rememberUpdatedState(glassLevel)
  val glassQuality = rememberFluidGlassQuality(ceiling = { levelState.value.toGlassCeiling() })

  val gridState = rememberLazyGridState()
  val scope = rememberCoroutineScope()

  // L'ordine: quello salvato, con le modifiche in corso sopra; si persiste al rilascio.
  val storedOrder by deps.layoutStore.order.collectAsState(initial = emptyList())
  var liveOrder by remember { mutableStateOf<List<String>?>(null) }
  val order = liveOrder ?: HomeWidget.ordered(storedOrder).map { it.id }
  val drag = remember(gridState) { GridDragController(gridState) }
  var selectedWidget by remember { mutableStateOf<HomeWidget?>(null) }

  Box(Modifier.fillMaxSize()) {
    WeatherScene(
      state = SkyState(state.phase, state.kind, state.cloudCover),
      quality = glassLevel.toSceneQuality(),
      modifier = Modifier
        .fillMaxSize()
        .glassBackdropSource(canvasBackdrop),
    )

    CompositionLocalProvider(
      LocalFluidCanvasBackdrop provides canvasBackdrop.takeIf { glassLevel != GlassLevel.OFF },
      LocalFluidGlassQuality provides glassQuality,
    ) {
      HomeGrid(
        state = state,
        gridState = gridState,
        order = order,
        drag = drag,
        onMove = { dragged, target -> liveOrder = GridReorder.moved(order, dragged, target) },
        onDrop = {
          liveOrder?.let { finalOrder -> scope.launch { deps.layoutStore.setOrder(finalOrder) } }
        },
        onOpenWidget = { selectedWidget = it },
        modifier = Modifier
          .fillMaxSize()
          .glassBackdropSource(contentBackdrop),
      )

      CompactHeader(
        state = state,
        gridState = gridState,
        modifier = Modifier.align(Alignment.TopCenter),
      )

      WidgetSheetHost(
        selected = selectedWidget,
        state = state,
        onDismiss = { selectedWidget = null },
      )

      val morphMenu = rememberFluidMorphMenuState()
      HomeFloatingBar(
        locationName = state.locationName,
        backdrop = chromeBackdrop,
        menuState = morphMenu,
        onOpenRadar = onOpenRadar,
        onOpenBenchmark = onOpenBenchmark,
        onOpenSettings = onOpenSettings,
        onOpenReport = onOpenReport,
        modifier = Modifier
          .align(Alignment.BottomCenter)
          .navigationBarsPadding()
          .padding(bottom = 10.dp),
      )
      FluidMorphMenuHost(state = morphMenu, backdrop = chromeBackdrop)
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
  order: List<String>,
  drag: GridDragController,
  onMove: (String, String) -> Unit,
  onDrop: () -> Unit,
  onOpenWidget: (HomeWidget) -> Unit,
  modifier: Modifier = Modifier,
) {
  LazyVerticalGrid(
    columns = GridCells.Fixed(2),
    state = gridState,
    modifier = modifier.pointerInput(Unit) {
      detectDragGesturesAfterLongPress(
        onDragStart = { offset -> drag.start(offset) },
        onDrag = { change, delta ->
          change.consume()
          val dragged = drag.draggingKey ?: return@detectDragGesturesAfterLongPress
          drag.move(delta)?.let { target -> onMove(dragged, target) }
        },
        onDragEnd = {
          drag.end()
          onDrop()
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
      HomeHeader(state = state, gridState = gridState)
    }

    val verdict = state.verdict
    if (verdict != null && verdict.level != AlertLevel.QUIETE) {
      item(key = ALERT_KEY, span = { GridItemSpan(2) }) {
        BarometerAlertRow(verdict)
      }
    }

    items(
      items = order,
      key = { it },
      span = { id -> GridItemSpan(HomeWidget.entries.firstOrNull { it.id == id }?.span ?: 2) },
    ) { id ->
      val widget = HomeWidget.entries.firstOrNull { it.id == id } ?: return@items
      val dragging = drag.draggingKey == id
      GlassTile(
        onClick = { onOpenWidget(widget) },
        modifier = Modifier
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
            text = widget.title.uppercase(),
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

/** Oltre questo scorrimento (px circa) la testata e' considerata collassata. */
private const val COLLAPSE_RANGE_PX = 420f

@Composable
private fun HomeHeader(state: HomeUiState, gridState: LazyGridState) {
  val shadow = Shadow(color = Color.Black.copy(alpha = 0.35f), blurRadius = 14f)
  Column(
    horizontalAlignment = Alignment.CenterHorizontally,
    modifier = Modifier
      .fillMaxWidth()
      .statusBarsPadding()
      .padding(top = 34.dp, bottom = 18.dp)
      .graphicsLayer {
        // Parallasse: la testata scorre a meta' velocita' della pagina e sfuma prima di
        // toccare la barra compatta. Legge lo stato QUI, cosi' invalida solo il disegno.
        val offset = if (gridState.firstVisibleItemIndex > 0) {
          COLLAPSE_RANGE_PX
        } else {
          gridState.firstVisibleItemScrollOffset.toFloat()
        }
        val progress = (offset / COLLAPSE_RANGE_PX).coerceIn(0f, 1f)
        translationY = offset * 0.45f
        alpha = 1f - progress * 1.15f
      },
  ) {
    Text(
      text = state.locationName ?: if (state.hasLocation) "La mia posizione" else "Posizione sconosciuta",
      style = MaterialTheme.typography.titleLarge.copy(shadow = shadow),
      color = Color.White,
    )
    Text(
      text = state.temperatureC?.let { "${it.toInt()}°" } ?: "—",
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
        text = "Max ${state.maxC.toInt()}°  Min ${state.minC.toInt()}°",
        style = MaterialTheme.typography.titleSmall.copy(shadow = shadow),
        color = Color.White.copy(alpha = 0.9f),
        modifier = Modifier.padding(top = 2.dp),
      )
    }
  }
}

/** La pillola compatta che prende il posto della testata quando questa se n'e' andata. */
@Composable
private fun CompactHeader(
  state: HomeUiState,
  gridState: LazyGridState,
  modifier: Modifier = Modifier,
) {
  val collapsed by remember {
    derivedStateOf {
      gridState.firstVisibleItemIndex > 0 ||
        gridState.firstVisibleItemScrollOffset > COLLAPSE_RANGE_PX * 0.8f
    }
  }
  Box(modifier = modifier.statusBarsPadding().padding(top = 6.dp)) {
    AnimatedVisibility(
      visible = collapsed,
      enter = fadeIn() + slideInVertically { -it / 2 },
      exit = fadeOut() + slideOutVertically { -it / 2 },
    ) {
      FluidGlassButton(
        text = buildString {
          append(state.locationName ?: "La mia posizione")
          state.temperatureC?.let { append("  ·  ${it.toInt()}°") }
        },
        onClick = {},
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
          text = if (verdict.level == AlertLevel.ALLERTA) "Allerta del barometro" else "Sorveglianza",
          style = MaterialTheme.typography.titleSmall,
          color = MaterialTheme.colorScheme.onSurface,
        )
        val strongest = verdict.windows.maxByOrNull { it.probability }
        if (strongest != null) {
          Text(
            text = "Pioggia ${strongest.window}: ${(strongest.probability * 100).toInt()}%",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
          )
        }
      }
    }
  }
}

private fun kindLabel(kind: WeatherKind?): String? = when (kind) {
  WeatherKind.CLEAR -> "Sereno"
  WeatherKind.MOSTLY_CLEAR -> "Poco nuvoloso"
  WeatherKind.PARTLY_CLOUDY -> "Parzialmente nuvoloso"
  WeatherKind.CLOUDY -> "Nuvoloso"
  WeatherKind.FOG -> "Nebbia"
  WeatherKind.DRIZZLE -> "Pioviggine"
  WeatherKind.RAIN -> "Pioggia"
  WeatherKind.HEAVY_RAIN -> "Pioggia forte"
  WeatherKind.SLEET -> "Pioggia gelata"
  WeatherKind.SNOW -> "Neve"
  WeatherKind.HEAVY_SNOW -> "Neve forte"
  WeatherKind.THUNDERSTORM -> "Temporale"
  WeatherKind.UNKNOWN, null -> null
}

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
