package dev.pampa.fluidweather.feature.radar

import android.annotation.SuppressLint
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.graphics.PointF
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.pampa.fluidweather.core.weather.RadarFrames
import org.maplibre.android.MapLibre
import org.maplibre.android.WellKnownTileServer
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdate
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.modes.CameraMode
import org.maplibre.android.location.modes.RenderMode
import org.maplibre.android.maps.AttributionDialogManager
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.Layer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.RasterLayer
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.layers.TransitionOptions
import org.maplibre.android.style.sources.RasterSource
import org.maplibre.android.style.sources.TileSet

/**
 * Quello che la mappa deve mostrare, deciso dalla schermata. Un valore solo, confrontabile: il
 * controller lo riceve a ogni composizione e tocca la mappa solo dove e' cambiato.
 */
internal data class RadarMapScene(
  val dark: Boolean = false,
  val frames: RadarFrames? = null,
  val frameIndex: Int = 0,
  val precipitation: Boolean = true,
  /** L'indirizzo dei tile OWM del livello scelto, null se il livello non e' di OWM. */
  val owmTemplate: String? = null,
  val location: Boolean = false,
  /** Lo spazio in alto (tasti) e in basso (barra del tempo), in pixel: la camera centra fra i due. */
  val topPaddingPx: Int = 0,
  val bottomPaddingPx: Int = 0,
  /** La lingua dei nomi dei posti (`it`, `en`). */
  val language: String = "en",
)

/**
 * La mappa del radar dietro le quinte: tiene il riferimento a MapLibre, applica la scena e risponde
 * alla schermata (dove sta un punto sullo schermo, vola qui, mostra le fonti).
 *
 * Vive solo sul thread principale, come la mappa. Tutto cio' che arriva prima che MapLibre consegni
 * la mappa (`getMapAsync` arriva dopo la prima composizione) si ricorda e si applica al [bind]:
 * Fluid-transit l'ha imparato sul telefono, con una mappa che restava vuota e una camera che non
 * partiva.
 */
internal class RadarMapController(private val context: Context) {

  private var map: MapLibreMap? = null
  private var view: MapView? = null
  private var desired = RadarMapScene()
  private var pendingCamera: CameraUpdate? = null

  // Cio' che e' gia' sulla mappa, per non rifarlo a ogni fotogramma.
  private var styleDark: Boolean? = null
  private var installedFrames: RadarFrames? = null
  private var installedFrameCount = 0
  private var shownFrame = -1
  private var shownPrecipitation: Boolean? = null
  private var installedOwm: String? = null
  private var appliedPadding: Pair<Int, Int>? = null

  /**
   * Cresce a ogni movimento della camera. Le etichette dei posti lo leggono mentre si piazzano: un
   * trascinamento le rimette a posto senza ricomporre niente.
   */
  val cameraTick = mutableIntStateOf(0)

  fun attach(view: MapView) {
    this.view = view
  }

  fun bind(map: MapLibreMap) {
    this.map = map
    map.addOnCameraMoveListener { cameraTick.intValue++ }
    map.addOnCameraIdleListener { cameraTick.intValue++ }
    pendingCamera?.let { map.moveCamera(it) }
    pendingCamera = null
    render(desired)
    cameraTick.intValue++
  }

  /** La mappa e' stata distrutta: da qui in poi nessuno la tocca. */
  fun unbind() {
    map = null
    view = null
    styleDark = null
    installedFrames = null
    installedOwm = null
    appliedPadding = null
  }

  fun render(scene: RadarMapScene) {
    desired = scene
    val m = map ?: return
    applyPadding(m, scene)
    if (styleDark != scene.dark) {
      styleDark = scene.dark
      // Uno stile nuovo parte senza i nostri strati: tutto va rimesso.
      installedFrames = null
      installedOwm = null
      shownFrame = -1
      shownPrecipitation = null
      m.setStyle(Style.Builder().fromUri(RadarMapCatalog.styleUrl(scene.dark))) { style ->
        localizeLabels(style, scene.language)
        sync(style, desired)
        cameraTick.intValue++
      }
      return
    }
    val style = m.style ?: return
    if (style.isFullyLoaded) sync(style, scene)
  }

  /** Dove cade un punto sulla mappa, in pixel dal suo angolo in alto a sinistra. Null senza mappa. */
  fun screenPoint(latitude: Double, longitude: Double): PointF? =
    map?.projection?.toScreenLocation(LatLng(latitude, longitude))

  /** Porta la camera su un punto, con gli zoom che il radar usava con Google (vedi [RadarMapCatalog.zoom]). */
  fun flyTo(latitude: Double, longitude: Double, googleZoom: Double) {
    val update = CameraUpdateFactory.newLatLngZoom(LatLng(latitude, longitude), RadarMapCatalog.zoom(googleZoom))
    val m = map
    if (m == null) pendingCamera = update else m.animateCamera(update)
  }

  /**
   * Le fonti della mappa con i loro link, come le dichiara lo stile (il TileJSON di OpenFreeMap:
   * OpenFreeMap, OpenMapTiles, OpenStreetMap). E' il dialogo di MapLibre: l'attribuzione nativa
   * e' spenta perche' la schermata la scrive per intero sotto la barra del tempo, e quel testo
   * apre questo.
   */
  fun showAttribution() {
    val m = map ?: return
    val v = view ?: return
    runCatching { AttributionDialogManager(v.context, m).onClick(v) }
  }

  private fun applyPadding(m: MapLibreMap, scene: RadarMapScene) {
    val padding = scene.topPaddingPx to scene.bottomPaddingPx
    if (padding == appliedPadding) return
    appliedPadding = padding
    m.moveCamera(CameraUpdateFactory.paddingTo(0.0, padding.first.toDouble(), 0.0, padding.second.toDouble()))
  }

  private fun sync(style: Style, scene: RadarMapScene) {
    if (!style.isFullyLoaded) return
    syncFrames(style, scene)
    syncOwm(style, scene.owmTemplate)
    syncLocation(style, scene.location)
  }

  /**
   * Un sorgente e uno strato per fotogramma, tutti montati e uno solo visibile: come i
   * `TileOverlay` di prima. Con l'opacita' a zero MapLibre non scarica i tile di uno strato, quindi
   * il primo giro li chiede mentre passa e dal secondo arrivano dalla cache: lo stesso patto di
   * prima con il servizio, nessuna raffica di tredici fotogrammi all'apertura.
   */
  private fun syncFrames(style: Style, scene: RadarMapScene) {
    val frames = scene.frames
    if (frames !== installedFrames) {
      for (index in 0 until installedFrameCount) {
        style.removeLayer(RadarMapCatalog.frameLayerId(index))
        style.removeSource(RadarMapCatalog.frameSourceId(index))
      }
      installedFrameCount = 0
      shownFrame = -1
      shownPrecipitation = null
      installedFrames = frames
      if (frames != null) {
        frames.all.forEachIndexed { index, frame ->
          val tiles = TileSet("2.2.0", frames.tileTemplate(frame))
          tiles.setMaxZoom(RadarMapCatalog.RAINVIEWER_MAX_ZOOM)
          style.addSource(RasterSource(RadarMapCatalog.frameSourceId(index), tiles, RadarMapCatalog.TILE_SIZE))
          val layer = RasterLayer(RadarMapCatalog.frameLayerId(index), RadarMapCatalog.frameSourceId(index)).withProperties(
            PropertyFactory.rasterOpacity(0f),
            // Il fotogramma cambia di colpo, come con `fadeIn = false`: una dissolvenza su un
            // loop da 450 ms impasterebbe due istanti diversi.
            PropertyFactory.rasterFadeDuration(0f),
          )
          // E anche l'opacita': MapLibre anima ogni cambio di proprieta' in 300 ms, e sul loop due
          // fotogrammi restavano sovrapposti per meta' del tempo (visto sull'emulatore: i bordi
          // dei temporali doppi). Il cambio di fotogramma e' un taglio, non una dissolvenza.
          layer.setRasterOpacityTransition(TransitionOptions(0, 0))
          addBelowLabels(style, layer)
        }
        installedFrameCount = frames.all.size
      }
    }
    if (shownFrame == scene.frameIndex && shownPrecipitation == scene.precipitation) return
    for (index in 0 until installedFrameCount) {
      val wanted = RadarMapCatalog.frameOpacity(index, scene.frameIndex, scene.precipitation)
      val before = RadarMapCatalog.frameOpacity(index, shownFrame, shownPrecipitation == true)
      if (wanted == before && shownFrame >= 0) continue
      (style.getLayer(RadarMapCatalog.frameLayerId(index)) as? RasterLayer)
        ?.setProperties(PropertyFactory.rasterOpacity(wanted))
    }
    shownFrame = scene.frameIndex
    shownPrecipitation = scene.precipitation
  }

  private fun syncOwm(style: Style, template: String?) {
    if (template == installedOwm) return
    style.removeLayer(RadarMapCatalog.OWM_LAYER)
    style.removeSource(RadarMapCatalog.OWM_SOURCE)
    installedOwm = template
    if (template == null) return
    val tiles = TileSet("2.2.0", template)
    tiles.setMaxZoom(RadarMapCatalog.OWM_MAX_ZOOM)
    style.addSource(RasterSource(RadarMapCatalog.OWM_SOURCE, tiles, RadarMapCatalog.TILE_SIZE))
    addBelowLabels(
      style,
      RasterLayer(RadarMapCatalog.OWM_LAYER, RadarMapCatalog.OWM_SOURCE)
        .withProperties(PropertyFactory.rasterOpacity(RadarMapCatalog.OWM_OPACITY)),
    )
  }

  /**
   * Il radar sotto i nomi dei posti: sopra un temporale "Firenze" si legge ancora. Con Google i
   * tile coprivano anche le scritte.
   */
  private fun addBelowLabels(style: Style, layer: Layer) {
    val firstLabel = style.layers.firstOrNull { it is SymbolLayer }?.id
    if (firstLabel != null) style.addLayerBelow(layer, firstLabel) else style.addLayer(layer)
  }

  /**
   * Il puntino blu del telefono, quello che con Google era `isMyLocationEnabled`. Il motore della
   * posizione e' quello di serie di MapLibre (il LocationManager di Android, niente Play
   * Services). Con il permesso tolto, o un motore che rifiuta, resta una mappa senza puntino: mai
   * un'app che si chiude.
   */
  @SuppressLint("MissingPermission")
  private fun syncLocation(style: Style, enabled: Boolean) {
    val m = map ?: return
    val component = m.locationComponent
    runCatching {
      if (enabled) {
        if (!component.isLocationComponentActivated) {
          component.activateLocationComponent(
            LocationComponentActivationOptions.builder(context, style)
              .useDefaultLocationEngine(true)
              .build(),
          )
        }
        component.isLocationComponentEnabled = true
        component.cameraMode = CameraMode.NONE
        component.renderMode = RenderMode.NORMAL
      } else if (component.isLocationComponentActivated) {
        component.isLocationComponentEnabled = false
      }
    }
  }

  /**
   * I nomi della mappa nella lingua dell'app: gli stili scrivono `name_en`, e in italiano si
   * vuole Firenze, non Florence. Se lo schema dello stile cambiasse, il fallimento e' silenzioso
   * e innocuo: restano i nomi inglesi.
   */
  private fun localizeLabels(style: Style, language: String) {
    val lang = RadarMapCatalog.labelLanguage(language) ?: return
    val localized = Expression.coalesce(
      Expression.get("name:$lang"),
      Expression.get("name:latin"),
      Expression.get("name"),
    )
    for (layer in style.layers) {
      if (layer !is SymbolLayer) continue
      runCatching {
        val current = layer.textField.expression ?: return@runCatching
        if ("name_en" !in current.toString()) return@runCatching
        layer.setProperties(PropertyFactory.textField(localized))
      }
    }
  }
}

@Composable
internal fun rememberRadarMapController(): RadarMapController {
  val context = LocalContext.current
  return remember(context) { RadarMapController(context) }
}

/**
 * La MapView dentro Compose, con il ciclo di vita completo (compresi onLowMemory e la chiusura
 * quando la schermata esce di scena con l'attivita' ancora viva: MapLibre ferma il motore della
 * posizione in onStop, e saltarlo lasciava aggiornamenti in arrivo su una mappa gia' liberata,
 * visto in Fluid-transit su un S25).
 *
 * `textureMode`: una TextureView si muove con la pagina durante le transizioni di navigazione,
 * dove una SurfaceView resterebbe ferma in una finestra a parte. Lo sfondo prima dello stile e'
 * quello della schermata: quello di MapLibre e' un crema chiaro che di notte lampeggiava.
 */
@Composable
internal fun RadarMap(
  controller: RadarMapController,
  scene: RadarMapScene,
  loadColor: Color,
  onFailed: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val lifecycleOwner = LocalLifecycleOwner.current
  val currentOnFailed by rememberUpdatedState(onFailed)
  val holder = remember { arrayOfNulls<MapView>(1) }

  AndroidView(
    modifier = modifier,
    factory = { context ->
      // Senza chiave: OpenFreeMap non ne chiede. La forma a tre argomenti con la chiave nulla e'
      // quella che Fluid-transit usa da mesi.
      MapLibre.getInstance(context.applicationContext, null, WellKnownTileServer.MapLibre)
      val options = MapLibreMapOptions.createFromAttributes(context)
        .textureMode(true)
        .foregroundLoadColor(loadColor.toArgb())
        // Logo e "i" nativi spenti: l'attribuzione la scrive la schermata, per intero e sempre
        // visibile, sotto la barra del tempo (vedi RadarTimelineBar).
        .logoEnabled(false)
        .attributionEnabled(false)
        .compassEnabled(false)
        .rotateGesturesEnabled(false)
        .tiltGesturesEnabled(false)
        .minZoomPreference(RadarMapCatalog.MIN_ZOOM)
        .maxZoomPreference(RadarMapCatalog.MAX_ZOOM)
        // Sull'Italia, finche' la localita' non e' nota.
        .camera(
          CameraPosition.Builder()
            .target(LatLng(42.5, 12.5))
            .zoom(RadarMapCatalog.zoom(5.0))
            .build(),
        )
      MapView(context, options).apply {
        holder[0] = this
        onCreate(null)
        // Lo stile che non arriva (rete assente al primo avvio, OpenFreeMap giu'): la schermata
        // lo dice invece di restare un rettangolo vuoto.
        addOnDidFailLoadingMapListener { currentOnFailed() }
        controller.attach(this)
        getMapAsync { map -> controller.bind(map) }
      }
    },
  )

  SideEffect { controller.render(scene) }

  DisposableEffect(lifecycleOwner) {
    val observer = LifecycleEventObserver { _, event ->
      val view = holder[0] ?: return@LifecycleEventObserver
      when (event) {
        Lifecycle.Event.ON_START -> view.onStart()
        Lifecycle.Event.ON_RESUME -> view.onResume()
        Lifecycle.Event.ON_PAUSE -> view.onPause()
        Lifecycle.Event.ON_STOP -> view.onStop()
        Lifecycle.Event.ON_DESTROY -> view.onDestroy()
        else -> Unit
      }
    }
    val memory = object : ComponentCallbacks2 {
      override fun onConfigurationChanged(newConfig: Configuration) = Unit

      override fun onLowMemory() {
        holder[0]?.onLowMemory()
      }

      @Suppress("DEPRECATION")
      override fun onTrimMemory(level: Int) {
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) holder[0]?.onLowMemory()
      }
    }
    val appContext = holder[0]?.context?.applicationContext
    appContext?.registerComponentCallbacks(memory)
    lifecycleOwner.lifecycle.addObserver(observer)
    onDispose {
      lifecycleOwner.lifecycle.removeObserver(observer)
      appContext?.unregisterComponentCallbacks(memory)
      controller.unbind()
      holder[0]?.let { view ->
        val state = lifecycleOwner.lifecycle.currentState
        if (state.isAtLeast(Lifecycle.State.RESUMED)) view.onPause()
        if (state.isAtLeast(Lifecycle.State.STARTED)) view.onStop()
        view.onDestroy()
      }
      holder[0] = null
    }
  }
}
