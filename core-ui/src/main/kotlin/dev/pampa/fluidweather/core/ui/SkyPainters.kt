package dev.pampa.fluidweather.core.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.util.lerp
import dev.pampa.fluidweather.core.model.WeatherKind
import java.time.Instant
import java.time.ZoneId
import java.util.Random
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * I pennelli del cielo.
 *
 * Stanno fuori dalla composable perche' il widget di sistema deve mostrare LA STESSA cosa, e
 * Glance non ha una tela: rasterizza un fotogramma in una bitmap chiamando [SkyFrame], che e' la
 * stessa sequenza che compone [WeatherScene]. Non una copia con gli stessi colori: le stesse
 * funzioni, nello stesso ordine.
 *
 * Il criterio di ogni pennello e' "come si vede fuori dalla finestra", non "come si disegna un
 * meteo". Il sole non e' un disco giallo: e' una luce che invade il cielo intorno, e il disco e'
 * quasi bianco. Le nuvole non sono chiazze: hanno una faccia al sole, una pancia in ombra, una
 * base piatta e i bordi che sfilacciano. La neve non e' una fila di pallini uguali: ha fiocchi
 * vicini grandi e sfocati e fiocchi lontani minuscoli. La pioggia ha due piani. Il temporale
 * lampeggia.
 *
 * Le soglie sono tarate a occhio sul banco di anteprima (tools/skypreview), che disegna questi
 * pennelli con Skia esattamente come il telefono: si giudica dai fotogrammi, non dai numeri.
 */

/**
 * Il seme della variazione: cambia col giorno, e per un dato giorno e' uguale ovunque.
 *
 * Serve perche' un cielo con le nuvole sempre allo stesso posto si riconosce dopo due giorni come
 * un'immagine, non come un cielo. Ma app e widget devono disegnare la stessa scena nello stesso
 * momento, quindi il seme non puo' essere casuale: e' il giorno.
 */
object SkyVariation {
  fun seedFor(nowMillis: Long, zone: ZoneId = ZoneId.systemDefault()): Int {
    val date = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
    return date.year * 400 + date.dayOfYear
  }
}

/**
 * Un fotogramma completo della scena, con la geometria gia' decisa.
 *
 * Si costruisce una volta (le nuvole, le stelle, i fiocchi hanno una posizione che deve restare
 * la stessa da un fotogramma all'altro) e si disegna a ogni [draw] col tempo che avanza.
 */
class SkyScenePainter(
  private val state: SkyState,
  private val quality: SceneQuality,
  seed: Int,
) {
  private val random = Random(seed.toLong())
  private val clouds = CloudLayer.forState(state, quality, Random(random.nextLong()))
  private val stars = StarField(quality, Random(random.nextLong()))
  private val precipitation = PrecipitationField(state.kind, quality, Random(random.nextLong()))
  private val fog = if (state.kind == WeatherKind.FOG) FogLayer(Random(random.nextLong())) else null
  private val lightning = if (state.kind == WeatherKind.THUNDERSTORM) LightningField(Random(random.nextLong())) else null

  /**
   * [moon] e' la fotografia del lato visibile della luna (NASA/LRO), che chi ha delle risorse
   * Android carica e passa; senza (il banco senza file, un errore di caricamento) si disegna una
   * luna procedurale, che e' meno luna ma e' sempre una luna.
   */
  fun draw(scope: DrawScope, sky: SkyPalette.Sky, body: CelestialBody?, t: Float, moon: ImageBitmap? = null) {
    SkyGradientPainter.draw(scope, sky)
    if (sky.starAlpha > 0f) stars.draw(scope, t, sky)
    // L'ordine e' il punto: l'astro sta DIETRO le nuvole, come fuori dalla finestra.
    body?.let { CelestialPainter.draw(scope, it, sky, moon) }
    clouds.draw(scope, t, sky, body)
    fog?.draw(scope, t, sky)
    lightning?.draw(scope, t)
    precipitation.draw(scope, t, sky)
  }

  /** Vero se in questo istante c'e' un lampo: il banco di anteprima lo usa per fotografarne uno. */
  fun flashing(t: Float): Boolean = lightning?.flashing(t) == true
}

/**
 * Un fotogramma fermo della scena, per chi non ha una tela viva.
 *
 * E' la porta pubblica dei pennelli: il widget di sistema disegna il cielo chiamando questa, che
 * e' la stessa sequenza che compone [WeatherScene].
 */
object SkyFrame {

  fun draw(
    scope: DrawScope,
    state: SkyState,
    nowMillis: Long,
    quality: SceneQuality = SceneQuality.FULL,
    zone: ZoneId = ZoneId.systemDefault(),
    /** Il velo sotto il testo: lo vuole il widget, che ci scrive sopra in bianco. */
    scrim: Boolean = false,
    /** Il tempo della scena in secondi: il widget lo lascia a zero, il banco di anteprima lo muove. */
    t: Float = 0f,
    /** La fotografia della luna, se chi chiama ce l'ha. */
    moon: ImageBitmap? = null,
  ) {
    val sky = SkyPalette.sky(state.phase, state.kind, state.cloudCoverPercent)
    val body = Celestial.at(nowMillis, state.latitude, state.longitude, zone)
    SkyScenePainter(state, quality, SkyVariation.seedFor(nowMillis, zone)).draw(scope, sky, body, t, moon)
    if (scrim) ScrimPainter.draw(scope, sky)
  }
}

/** La copertura da mostrare quando i provider non la dicono: dedotta dal tipo di tempo. */
internal fun SkyState.cloudCoverPercentOrDefault(): Float =
  (cloudCoverPercent ?: SkyPalette.defaultCover(kind)).toFloat()

// ------------------------------------------------------------------------------------------------
// Primitive
// ------------------------------------------------------------------------------------------------

/**
 * L'unita' di misura della scena: quanto vale "un pixel di disegno" su questa tela.
 *
 * La scena si disegna a 360 px sul banco, a 1080 sul telefono, a 220 sul widget. Un tratto di
 * pioggia largo due pixel e' un filo sul telefono e un tronco sul widget: le larghezze e i raggi
 * dei dettagli si esprimono in unita', non in pixel. Il tetto e il pavimento evitano che su una
 * tela minuscola i dettagli spariscano e su una enorme diventino grossolani.
 */
private val DrawScope.unit: Float
  get() = (minOf(size.width, size.height) / 360f).coerceIn(0.6f, 2.2f)

/**
 * Quanto la tela e' piccola rispetto a uno schermo: 1 sul telefono, meno sul widget.
 *
 * Le luci grandi (il bagliore del sole, la fascia dell'orizzonte) sono proporzionate alla tela, e
 * su un widget di duecento pixel invaderebbero tutto lo spazio in cui poi si scrive: si smorzano.
 */
private val DrawScope.smallness: Float
  get() = (minOf(size.width, size.height) / 360f).toDouble().pow(1.6).toFloat().coerceIn(0.35f, 1f)

/** Un disco dal bordo morbido: pieno fino a [hardness] del raggio, poi sfuma a zero. */
private fun DrawScope.softDisc(
  center: Offset,
  radius: Float,
  color: Color,
  alpha: Float,
  hardness: Float = 0f,
  blendMode: BlendMode = BlendMode.SrcOver,
) {
  if (alpha <= 0.004f || radius <= 0.5f) return
  val stops = if (hardness > 0.01f) {
    arrayOf(0f to color.copy(alpha = alpha), hardness to color.copy(alpha = alpha), 1f to color.copy(alpha = 0f))
  } else {
    arrayOf(0f to color.copy(alpha = alpha), 1f to color.copy(alpha = 0f))
  }
  drawCircle(
    brush = Brush.radialGradient(colorStops = stops, center = center, radius = radius),
    radius = radius,
    center = center,
    blendMode = blendMode,
  )
}

/**
 * I pennelli dei dischi morbidi, creati una volta e riusati.
 *
 * Un `Brush.radialGradient` costruito a ogni chiamata diventa uno shader nuovo a ogni fotogramma:
 * con trecento batuffoli a trenta fotogrammi al secondo sono diecimila shader al secondo, e il
 * garbage collector si fa sentire sotto le dita. Il pennello si costruisce centrato nell'origine e
 * si disegna **traslando** la tela, cosi' lo stesso oggetto serve a ogni posizione e Compose riusa
 * lo shader che ha gia' creato. La chiave e' la geometria e il colore: quando i colori del cielo
 * cambiano (una dissolvenza di un secondo) la cache si svuota e si rifa', ed e' l'unico momento
 * in cui costa quanto costava prima.
 */
private class BrushCache {
  private data class Key(val radius: Float, val color: Color, val alpha: Float, val hardness: Float)

  private val brushes = HashMap<Key, Brush>()
  private var paletteKey: Any? = null

  /** Svuota la cache se la palette e' cambiata rispetto all'ultimo fotogramma. */
  fun sync(palette: Any) {
    if (palette != paletteKey) {
      brushes.clear()
      paletteKey = palette
    }
  }

  fun disc(radius: Float, color: Color, alpha: Float, hardness: Float): Brush =
    brushes.getOrPut(Key(radius, color, alpha, hardness)) {
      val stops = if (hardness > 0.01f) {
        arrayOf(0f to color.copy(alpha = alpha), hardness to color.copy(alpha = alpha), 1f to color.copy(alpha = 0f))
      } else {
        arrayOf(0f to color.copy(alpha = alpha), 1f to color.copy(alpha = 0f))
      }
      Brush.radialGradient(colorStops = stops, center = Offset.Zero, radius = radius)
    }
}

/** Come [softDisc], ma col pennello preso dalla cache e la tela traslata sotto. */
private fun DrawScope.cachedDisc(
  cache: BrushCache,
  center: Offset,
  radius: Float,
  color: Color,
  alpha: Float,
  hardness: Float = 0f,
) {
  if (alpha <= 0.004f || radius <= 0.5f) return
  val brush = cache.disc(radius, color, alpha, hardness)
  withTransform({ translate(center.x, center.y) }) {
    drawCircle(brush = brush, radius = radius, center = Offset.Zero)
  }
}

/** Come [softBlob], ma col pennello preso dalla cache. */
private fun DrawScope.cachedBlob(
  cache: BrushCache,
  center: Offset,
  radiusX: Float,
  radiusY: Float,
  color: Color,
  alpha: Float,
  hardness: Float = 0f,
  rotationDegrees: Float = 0f,
) {
  if (alpha <= 0.004f || radiusX <= 0.5f || radiusY <= 0.5f) return
  val r = maxOf(radiusX, radiusY)
  val brush = cache.disc(r, color, alpha, hardness)
  withTransform({
    translate(center.x, center.y)
    rotate(rotationDegrees, pivot = Offset.Zero)
    scale(radiusX / r, radiusY / r, pivot = Offset.Zero)
  }) {
    drawCircle(brush = brush, radius = r, center = Offset.Zero)
  }
}

/** Un disco morbido stirato e ruotato: e' il mattone di nuvole, cirri, foschia e Via Lattea. */
private fun DrawScope.softBlob(
  center: Offset,
  radiusX: Float,
  radiusY: Float,
  color: Color,
  alpha: Float,
  hardness: Float = 0f,
  rotationDegrees: Float = 0f,
  blendMode: BlendMode = BlendMode.SrcOver,
) {
  if (alpha <= 0.004f || radiusX <= 0.5f || radiusY <= 0.5f) return
  val r = maxOf(radiusX, radiusY)
  withTransform({
    translate(center.x, center.y)
    rotate(rotationDegrees, pivot = Offset.Zero)
    scale(radiusX / r, radiusY / r, pivot = Offset.Zero)
  }) {
    softDisc(Offset.Zero, r, color, alpha, hardness, blendMode)
  }
}

/** Il colore del cielo a quella altezza della tela, dalle fermate del gradiente. */
private fun skyColorAt(sky: SkyPalette.Sky, y: Float): Color {
  val stops = sky.stops
  if (stops.isEmpty()) return Color.Black
  if (y <= stops.first().first) return stops.first().second
  for (i in 1 until stops.size) {
    val (at, color) = stops[i]
    if (y <= at) {
      val (before, from) = stops[i - 1]
      val span = (at - before).coerceAtLeast(1e-4f)
      return lerp(from, color, ((y - before) / span).coerceIn(0f, 1f))
    }
  }
  return stops.last().second
}

private fun Random.between(from: Float, to: Float): Float = from + nextFloat() * (to - from)

// ------------------------------------------------------------------------------------------------
// Il cielo
// ------------------------------------------------------------------------------------------------

internal object SkyGradientPainter {
  fun draw(scope: DrawScope, sky: SkyPalette.Sky) {
    scope.drawRect(
      Brush.verticalGradient(
        colorStops = sky.stops.map { it.first to it.second }.toTypedArray(),
        startY = 0f,
        endY = scope.size.height,
      ),
    )
  }
}

// ------------------------------------------------------------------------------------------------
// Il sole e la luna
// ------------------------------------------------------------------------------------------------

/**
 * Il sole come luce, la luna come oggetto.
 *
 * Il sole vero non si guarda: cio' che si vede e' il cielo che si accende intorno. Quindi il disco
 * e' piccolo e quasi bianco, e il grosso del lavoro lo fa la luce che schiarisce il cielo per un
 * raggio enorme — con i modi di fusione che sommano la luce ai colori sotto invece di coprirli
 * (`Screen` per il bagliore largo, che non satura; `Plus` per la corona, che deve bruciare). Il
 * vecchio sole era un disco giallo pastello con un alone grigio a `SrcOver`: un adesivo.
 *
 * Basso sull'orizzonte la luce diventa arancio e rossa, il disco si allarga (rifrazione) e si fa
 * piu' netto perche' l'aria lo smorza abbastanza da poterlo guardare, e accende una fascia lungo
 * l'orizzonte: e' il tramonto, e non e' un colore del gradiente, e' il sole. Dietro una coltre
 * resta solo una macchia diffusa piu' chiara, senza disco.
 */
internal object CelestialPainter {

  fun draw(scope: DrawScope, body: CelestialBody, sky: SkyPalette.Sky, moon: ImageBitmap? = null) {
    when (body.kind) {
      CelestialBody.Kind.SUN -> scope.drawSun(body, sky)
      CelestialBody.Kind.MOON -> scope.drawMoon(body, sky, moon)
    }
  }

  private fun DrawScope.drawSun(body: CelestialBody, sky: SkyPalette.Sky) {
    val minSide = minOf(size.width, size.height)
    val center = Offset(body.x * size.width, body.y * size.height)
    // 0 quando e' alto, 1 quando tocca l'orizzonte: guida colore, grandezza e fascia.
    val warmth = (1.0 - (body.altitudeDegrees / 25.0).coerceIn(0.0, 1.0)).toFloat()
    val core = lerp(SkyPalette.SunCoreHigh, SkyPalette.SunCoreLow, warmth)
    val glow = lerp(SkyPalette.SunGlowHigh, SkyPalette.SunGlowLow, warmth)
    // Dietro una coltre il sole non ha alone e il disco e' appena un chiarore.
    val visibility = (1f - sky.gloom * 0.85f).coerceIn(0.1f, 1f)
    val discRadius = minSide * lerp(0.046f, 0.07f, warmth)
    val wide = smallness

    // 1) La luce nel cielo: grande, debole, in `Screen`. E' quello che rende il sole un sole.
    val bloom = minSide * lerp(0.95f, 1.3f, warmth)
    drawCircle(
      brush = Brush.radialGradient(
        colorStops = arrayOf(
          0f to glow.copy(alpha = 0.55f * visibility * wide),
          0.14f to glow.copy(alpha = 0.3f * visibility * wide),
          0.42f to glow.copy(alpha = 0.1f * visibility * wide),
          1f to glow.copy(alpha = 0f),
        ),
        center = center,
        radius = bloom,
      ),
      radius = bloom,
      center = center,
      blendMode = BlendMode.Screen,
    )

    // 2) La fascia dell'orizzonte quando e' basso: l'aria si accende lungo tutta la linea.
    if (warmth > 0.15f) {
      softBlob(
        center = Offset(body.x * size.width, size.height * 0.98f),
        radiusX = size.width * 0.95f,
        radiusY = size.height * 0.24f,
        color = glow,
        alpha = 0.4f * warmth * visibility * wide,
        hardness = 0.12f,
        blendMode = BlendMode.Screen,
      )
    }

    // 3) La corona: la luce che il disco proietta subito intorno a se'. Fermate fitte, che
    // decrescono senza gomiti: un gomito nel gradiente si vede come un anello.
    val coronaRadius = discRadius * 3.6f
    val coronaStrength = visibility * (1f + 0.25f * warmth)
    drawCircle(
      brush = Brush.radialGradient(
        colorStops = arrayOf(
          0f to core.copy(alpha = 0.8f * coronaStrength),
          0.2f to glow.copy(alpha = 0.42f * coronaStrength),
          0.42f to glow.copy(alpha = 0.16f * coronaStrength),
          0.7f to glow.copy(alpha = 0.05f * coronaStrength),
          1f to glow.copy(alpha = 0f),
        ),
        center = center,
        radius = coronaRadius,
      ),
      radius = coronaRadius,
      center = center,
      blendMode = BlendMode.Plus,
    )

    if (sky.gloom < 0.7f) {
      // 4) Il disco: alto e' quasi bianco col bordo appena morbido — e' troppo luminoso per avere
      // un contorno netto; basso e' arancio e piu' netto, perche' l'aria lo smorza.
      val edge = lerp(0.72f, 0.86f, warmth)
      drawCircle(
        brush = Brush.radialGradient(
          colorStops = arrayOf(
            0f to core.copy(alpha = visibility),
            edge to core.copy(alpha = visibility),
            1f to glow.copy(alpha = 0f),
          ),
          center = center,
          radius = discRadius * 1.1f,
        ),
        radius = discRadius * 1.1f,
        center = center,
      )
    } else {
      // Sotto una coltre: solo la macchia diffusa piu' chiara, dove il sole sta dietro.
      softDisc(center, discRadius * 3f, core, 0.32f * visibility + 0.08f, blendMode = BlendMode.Screen)
    }
  }

  private fun DrawScope.drawMoon(body: CelestialBody, sky: SkyPalette.Sky, moon: ImageBitmap?) {
    val minSide = minOf(size.width, size.height)
    val center = Offset(body.x * size.width, body.y * size.height)
    val radius = minSide * 0.046f
    val visibility = (1f - sky.gloom * 0.85f).coerceIn(0.12f, 1f)
    val lit = body.illuminated.coerceIn(0f, 1f)

    // L'alone: tenue, freddo. Piu' ampio dietro un velo di nuvole sottili, che e' l'unico caso
    // in cui la luna ha davvero un alone largo (e chi lo ha visto sa quanto e' bello).
    softDisc(center, radius * 2.4f, SkyPalette.MoonHalo, (0.10f + 0.10f * lit) * visibility, hardness = 0.12f)
    if (sky.gloom in 0.12f..0.6f) {
      softDisc(center, radius * 5.5f, SkyPalette.MoonHalo, 0.07f * visibility, hardness = 0.35f)
    }

    MoonPainter.disc(
      scope = this,
      image = moon,
      center = center,
      radius = radius,
      illuminated = lit,
      waxing = body.waxing,
      shadow = skyColorAt(sky, body.y),
      alpha = visibility,
    )
  }
}

/**
 * Il disco della luna: la fotografia vera del lato visibile, con l'ombra della fase sopra.
 *
 * E' cosi' che fa Apple, ed e' l'unico modo perche' la luna sembri la luna: i mari e i crateri
 * non si inventano con quattro macchie. L'immagine e' la mappa LROC della NASA proiettata a disco
 * (tools/skypreview la carica dal file, l'app dalle risorse). Sopra: il limbo che si scurisce
 * come su una sfera, l'ombra della fase col bordo morbido, la luce cinerea. Lo usano il cielo e
 * la pagina della luna, cosi' le due lune sono la stessa.
 */
internal object MoonPainter {

  fun disc(
    scope: DrawScope,
    image: ImageBitmap?,
    center: Offset,
    radius: Float,
    illuminated: Float,
    waxing: Boolean,
    /** Il colore del cielo dietro: la parte in ombra deve sparirci dentro. */
    shadow: Color,
    alpha: Float = 1f,
  ) = with(scope) {
    val lit = illuminated.coerceIn(0f, 1f)
    val disc = Path().apply { addOval(Rect(center = center, radius = radius)) }

    if (image != null) {
      val side = (radius * 2f).roundToInt().coerceAtLeast(2)
      drawImage(
        image = image,
        dstOffset = IntOffset((center.x - radius).roundToInt(), (center.y - radius).roundToInt()),
        dstSize = IntSize(side, side),
        alpha = alpha,
        filterQuality = FilterQuality.High,
      )
    } else {
      // Senza fotografia: un disco col limbo e quattro macchie, che e' meno luna ma e' una luna.
      drawCircle(
        brush = Brush.radialGradient(
          colorStops = arrayOf(
            0f to SkyPalette.MoonColor.copy(alpha = alpha),
            0.68f to SkyPalette.MoonColor.copy(alpha = alpha),
            1f to SkyPalette.MoonLimb.copy(alpha = alpha),
          ),
          center = center,
          radius = radius,
        ),
        radius = radius,
        center = center,
      )
      clipPath(disc) {
        for ((dx, dy, r) in listOf(Triple(-0.22f, -0.22f, 0.36f), Triple(0.22f, -0.02f, 0.26f), Triple(-0.06f, 0.30f, 0.22f))) {
          softDisc(Offset(center.x + dx * radius, center.y + dy * radius), r * radius * 1.4f, SkyPalette.MoonMaria, 0.34f * alpha, hardness = 0.35f)
        }
      }
    }

    clipPath(disc) {
      // Il limbo: una sfera si scurisce verso il bordo, una fotografia piatta no.
      drawCircle(
        brush = Brush.radialGradient(
          colorStops = arrayOf(
            0f to Color.Transparent,
            0.72f to Color.Transparent,
            1f to Color(0xFF1A1E2C).copy(alpha = 0.42f * alpha),
          ),
          center = center,
          radius = radius,
        ),
        radius = radius,
        center = center,
      )
      // La fase: un secondo disco del colore del cielo che morde il primo, col bordo morbido.
      // Il terminatore vero e' un'ellisse, ma a questi raggi un cerchio spostato e' identico.
      // L'ombra si allontana quanto piu' la luna e' piena: a meta' il suo centro sta sul bordo,
      // a un decimo copre nove decimi, a nove decimi ne morde uno. Nella 1.4.1 lo scostamento
      // era `2R * (1 - lit)`, cioe' la fase al contrario: chiara quando doveva essere in ombra.
      if (lit < 0.985f) {
        val offset = radius * 2f * lit
        val direction = if (waxing) -1f else 1f
        val shadowCenter = Offset(center.x + direction * offset, center.y)
        drawCircle(
          brush = Brush.radialGradient(
            colorStops = arrayOf(0f to shadow, 0.92f to shadow, 1f to shadow.copy(alpha = 0f)),
            center = shadowCenter,
            radius = radius * 1.02f,
          ),
          radius = radius * 1.02f,
          center = shadowCenter,
        )
        // La luce cinerea: la parte in ombra non e' nera, la Terra la rischiara appena, e la
        // fotografia si intravede.
        drawImageOrDisc(image, center, radius, 0.07f * alpha)
      }
    }
  }

  private fun DrawScope.drawImageOrDisc(image: ImageBitmap?, center: Offset, radius: Float, alpha: Float) {
    if (image != null) {
      val side = (radius * 2f).roundToInt().coerceAtLeast(2)
      drawImage(
        image = image,
        dstOffset = IntOffset((center.x - radius).roundToInt(), (center.y - radius).roundToInt()),
        dstSize = IntSize(side, side),
        alpha = alpha,
        filterQuality = FilterQuality.High,
      )
    } else {
      drawCircle(color = SkyPalette.MoonLimb.copy(alpha = alpha), radius = radius, center = center)
    }
  }
}

// ------------------------------------------------------------------------------------------------
// Le stelle
// ------------------------------------------------------------------------------------------------

/**
 * Stelle nelle notti pulite.
 *
 * Non sono tutte uguali, ed e' questo che le rende stelle: quasi tutte sono granelli, poche sono
 * evidenti, due o tre brillano con una croce di luce. Qualcuna e' azzurra, qualcuna calda. In
 * alto sono piu' fitte. E in una notte davvero pulita si intravede la Via Lattea: una fascia
 * appena piu' chiara, obliqua, larga e chiazzata, che nessuno nota e tutti sentono.
 */
internal class StarField(private val quality: SceneQuality, random: Random) {

  private val count = if (quality == SceneQuality.FULL) 170 else 90
  private val xs = FloatArray(count) { random.nextFloat() }
  private val ys = FloatArray(count) { random.nextFloat().toDouble().pow(1.5).toFloat() * 0.74f }
  /** 0 = granello, 1 = piccola, 2 = evidente. */
  private val classes = IntArray(count) {
    val r = random.nextFloat()
    when {
      r < 0.72f -> 0
      r < 0.95f -> 1
      else -> 2
    }
  }
  private val sizes = FloatArray(count) { i ->
    when (classes[i]) {
      0 -> random.between(0.5f, 0.85f)
      1 -> random.between(0.95f, 1.35f)
      else -> random.between(1.5f, 2.2f)
    }
  }
  private val brightness = FloatArray(count) { i ->
    when (classes[i]) {
      0 -> random.between(0.35f, 0.7f)
      1 -> random.between(0.65f, 0.95f)
      else -> 1f
    }
  }
  private val tints = Array(count) {
    when (random.nextInt(8)) {
      0 -> Color(0xFFCBDCFF)
      1 -> Color(0xFFFFE9C8)
      else -> Color.White
    }
  }
  private val phases = FloatArray(count) { random.nextFloat() * 6.28f }
  private val speeds = FloatArray(count) { random.between(0.6f, 2.0f) }
  private val milkyWayAngle = random.between(-38f, -18f)
  private val milkyWayX = random.between(0.35f, 0.7f)
  private val milkyWayY = random.between(0.22f, 0.4f)
  /** Le chiazze della Via Lattea: nubi di stelle lungo la fascia, dove e' piu' densa. */
  private val milkyWayKnots = List(4) { Offset(random.between(-0.35f, 0.35f), random.between(-0.05f, 0.05f)) }

  fun draw(scope: DrawScope, t: Float, sky: SkyPalette.Sky) {
    val width = scope.size.width
    val height = scope.size.height
    val u = scope.unit
    val base = sky.starAlpha

    if (quality == SceneQuality.FULL && base > 0.55f) {
      val strength = ((base - 0.55f) / 0.15f).coerceIn(0f, 1f)
      val tint = Color(0xFFD8E1FF)
      val center = Offset(width * milkyWayX, height * milkyWayY)
      // La fascia larga, quasi invisibile, e dentro le chiazze piu' dense: e' la chiazzatura che
      // la fa sembrare una nube di stelle e non una striscia.
      scope.softBlob(center, width * 1.2f, height * 0.24f, tint, 0.035f * strength, rotationDegrees = milkyWayAngle)
      scope.withTransform({
        translate(center.x, center.y)
        rotate(milkyWayAngle, pivot = Offset.Zero)
      }) {
        for (knot in milkyWayKnots) {
          softBlob(Offset(knot.x * width, knot.y * height), width * 0.2f, height * 0.07f, tint, 0.04f * strength)
        }
      }
    }

    for (i in xs.indices) {
      val twinkle = if (classes[i] == 0) 1f else 0.72f + 0.28f * sin(t * speeds[i] + phases[i])
      val alpha = (base * brightness[i] * twinkle).coerceIn(0f, 1f)
      val center = Offset(xs[i] * width, ys[i] * height)
      val radius = sizes[i] * u
      scope.drawCircle(color = tints[i].copy(alpha = alpha), radius = radius, center = center)
      if (classes[i] == 2 && quality == SceneQuality.FULL) {
        // La croce di luce delle piu' brillanti, e un alone minuscolo.
        scope.softDisc(center, radius * 3.2f, tints[i], alpha * 0.28f)
        val arm = radius * 4.2f
        val stroke = u * 0.55f
        scope.drawLine(tints[i].copy(alpha = alpha * 0.32f), Offset(center.x - arm, center.y), Offset(center.x + arm, center.y), stroke)
        scope.drawLine(tints[i].copy(alpha = alpha * 0.32f), Offset(center.x, center.y - arm), Offset(center.x, center.y + arm), stroke)
      }
    }
  }
}

// ------------------------------------------------------------------------------------------------
// Le nuvole
// ------------------------------------------------------------------------------------------------

/**
 * Un batuffolo di un cumulo, in unita' della larghezza della nuvola.
 *
 * [height] e' quanto sta in alto nella nuvola (0 alla base, 1 alla cima): decide quanta luce
 * prende. [tint] e' una variazione casuale della tinta, perche' una nuvola tutta dello stesso
 * bianco e' una piastra.
 */
private class Puff(val dx: Float, val dy: Float, val r: Float, val height: Float, val tint: Float)

/**
 * Un cumulo: un grappolo di batuffoli con la base piatta, la faccia al sole e la pancia in ombra.
 *
 * Si disegna in passate sopra una base scura: la massa in ombra, poi i corpi con un'opacita' che
 * cresce verso la cima (in basso resta l'ombra), poi le luci sui batuffoli alti spostate verso il
 * sole, poi i ciuffi: batuffoli larghi e quasi trasparenti ai bordi, che sfilacciano il contorno.
 * Sono tanti batuffoli piccoli con il bordo morbido, non pochi grandi col bordo netto: e' la
 * differenza fra un cumulo e un'emoji.
 */
private class Cumulus(
  val cx: Float,
  val cy: Float,
  /** La larghezza, in frazione della larghezza della tela. */
  val width: Float,
  val puffs: List<Puff>,
  val wisps: List<Puff>,
  /** La velocita' di deriva, in larghezze di tela al secondo. Le vicine (grandi) vanno piu' forte. */
  val speed: Float,
  val alpha: Float,
) {
  companion object {
    fun random(random: Random, cx: Float, cy: Float, width: Float, alpha: Float, quality: SceneQuality): Cumulus {
      val full = quality == SceneQuality.FULL
      val puffs = mutableListOf<Puff>()
      // Il profilo: una cupola, alta al centro e bassa ai lati, con la base piatta a dy = 0.
      val columns = if (full) random.nextInt(4) + 9 else random.nextInt(3) + 6
      val peak = random.between(0.3f, 0.42f)
      for (i in 0 until columns) {
        val along = (i + 0.5f) / columns
        val dx = -0.47f + along * 0.94f
        val dome = sqrt((1f - ((along - 0.5f) * 2f).let { it * it }).coerceAtLeast(0f))
        val top = peak * dome * random.between(0.75f, 1.05f)
        // Da uno a tre batuffoli per colonna, dalla base alla cima.
        val stack = if (full) 1 + random.nextInt(3) else 1 + random.nextInt(2)
        for (s in 0 until stack) {
          val r = random.between(0.09f, 0.15f) * (if (s == 0) 1.15f else 1f)
          val dy = if (stack == 1) -top * 0.5f else -top * (s.toFloat() / (stack - 1)) * 0.85f - r * 0.35f
          puffs += Puff(
            dx = dx + random.between(-0.035f, 0.035f),
            dy = dy.coerceAtMost(-r * 0.75f),
            r = r,
            height = ((-dy) / peak).coerceIn(0f, 1f),
            tint = random.between(-0.08f, 0.08f),
          )
        }
      }
      // I ciuffi: larghi, tenui, un po' fuori dalla sagoma.
      val wisps = List(if (full) 6 else 3) {
        val dx = random.between(-0.5f, 0.5f)
        val edge = sqrt((1f - (dx * 2f).let { v -> v * v }).coerceAtLeast(0f))
        Puff(
          dx = dx,
          dy = -peak * edge * random.between(0.3f, 0.9f),
          r = random.between(0.12f, 0.2f),
          height = 0.6f,
          tint = 0f,
        )
      }
      return Cumulus(cx, cy, width, puffs, wisps, speed = width * 0.006f, alpha = alpha)
    }
  }
}

/** Un banco di una coltre: un blob largo e basso, con la sua scurezza. */
private class DeckBlob(val x: Float, val y: Float, val rx: Float, val ry: Float, val darkness: Float, val drift: Float)

/**
 * Una coltre: cielo coperto, pioggia, neve, temporale.
 *
 * Non e' una fila di nuvole, e' un soffitto: banchi sparsi a caso dall'alto fin quasi a meta'
 * tela, ognuno piu' scuro quanto piu' sta in basso, perche' la luce arriva dall'alto e in basso
 * resta l'ombra — una scurezza continua, non a file, altrimenti si vedono le file. Sotto il
 * soffitto la foschia: la base che sfuma nel grigio, invece di un bordo netto sul cielo. E gli
 * stracci: brandelli scuri che pendono, che e' cio' che distingue un cielo di pioggia da un
 * cielo grigio.
 */
private class Deck(
  val blobs: List<DeckBlob>,
  val scud: List<DeckBlob>,
  /** 0 = coltre chiara e sottile, 1 = piombo. */
  val darkness: Float,
  /** Fin dove scende il soffitto, in frazione dell'altezza. */
  val bottom: Float,
) {
  companion object {
    fun random(random: Random, darkness: Float, coverage: Float, ragged: Boolean, quality: SceneQuality): Deck {
      val full = quality == SceneQuality.FULL
      val bottom = 0.4f + darkness * 0.14f
      val count = if (full) 22 else 12
      val blobs = List(count) {
        val y = random.nextFloat().let { v -> v * v * 0.6f + v * 0.4f } * bottom
        DeckBlob(
          x = random.between(-0.12f, 1.12f),
          y = y,
          rx = random.between(0.14f, 0.32f),
          ry = random.between(0.05f, 0.11f),
          darkness = ((y / bottom).toDouble().pow(0.8).toFloat() * 0.85f + random.between(-0.1f, 0.1f)).coerceIn(0f, 1f),
          drift = random.between(0.002f, 0.006f),
        )
      }.filter { random.nextFloat() <= coverage }.sortedBy { it.y }
      val scud = if (ragged) {
        List(if (full) 8 else 4) {
          DeckBlob(
            x = random.between(-0.05f, 1.05f),
            y = bottom + random.between(-0.02f, 0.1f),
            rx = random.between(0.07f, 0.15f),
            ry = random.between(0.03f, 0.055f),
            darkness = 1f,
            drift = random.between(0.006f, 0.011f),
          )
        }
      } else {
        emptyList()
      }
      return Deck(blobs, scud, darkness, bottom)
    }
  }
}

/** Un filamento di un cirro. */
private class Filament(val dx: Float, val dy: Float, val length: Float, val thickness: Float, val angle: Float, val alpha: Float)

/**
 * Un velo di cirri: due o tre chiazze larghe, basse e quasi trasparenti, appena inclinate — l'aria
 * alta che si sbianca, non un disegno.
 *
 * Prima erano filamenti sottili e obliqui, e da lontano sembravano un lens flare venuto male, o
 * scie di aerei: righe dritte nel cielo sereno. Le righe dritte nel cielo non esistono, e il
 * sereno adesso resta sereno.
 */
private class Cirrus(val cx: Float, val cy: Float, val filaments: List<Filament>, val alpha: Float) {
  companion object {
    fun random(random: Random, alpha: Float): Cirrus {
      val angle = random.between(-7f, -2f)
      val filaments = List(2 + random.nextInt(2)) {
        Filament(
          dx = random.between(-0.15f, 0.15f),
          dy = random.between(-0.03f, 0.03f),
          length = random.between(0.28f, 0.5f),
          thickness = random.between(0.02f, 0.04f),
          angle = angle + random.between(-2f, 2f),
          alpha = random.between(0.5f, 1f),
        )
      }
      return Cirrus(random.nextFloat(), random.between(0.06f, 0.26f), filaments, alpha)
    }
  }
}

/** Tutte le nuvole di una scena, decise una volta dal tipo di tempo e dalla copertura. */
internal class CloudLayer private constructor(
  private val cumuli: List<Cumulus>,
  private val deck: Deck?,
  private val cirri: List<Cirrus>,
  private val quality: SceneQuality,
) {
  private val cache = BrushCache()

  fun draw(scope: DrawScope, t: Float, sky: SkyPalette.Sky, body: CelestialBody?) {
    cache.sync(Triple(sky.cloudColor, sky.cloudShadow, scope.size))
    // Da che parte arriva la luce: dal sole (o dalla luna) se c'e', altrimenti da destra in alto.
    val lightX = body?.x ?: 0.7f
    cirri.forEach { scope.drawCirrus(it, t, sky) }
    deck?.let { scope.drawDeck(it, t, sky) }
    cumuli.forEach { scope.drawCumulus(it, t, sky, lightX) }
  }

  private fun DrawScope.drawCumulus(cloud: Cumulus, t: Float, sky: SkyPalette.Sky, lightX: Float) {
    val width = size.width
    val height = size.height
    val w = cloud.width * width
    val travel = width + w * 1.2f
    val x = ((cloud.cx * travel + t * cloud.speed * width) % travel) - w * 0.6f
    val center = Offset(x, cloud.cy * height)
    val sunSide = if (lightX * width >= x) 1f else -1f
    val lit = sky.cloudColor
    val shadow = sky.cloudShadow
    val body = lerp(lit, shadow, 0.22f)
    val a = cloud.alpha

    // La base: un'ellisse scura e piatta che appoggia il cumulo sull'aria.
    cachedBlob(cache, Offset(center.x, center.y + w * 0.03f), w * 0.5f, w * 0.09f, shadow, 0.5f * a, hardness = 0.25f)
    // La massa in ombra: ogni batuffolo, spostato in basso e via dal sole. Non piu' grande — un
    // orlo scuro uniforme intorno a tutto e' un disegno, non un'ombra.
    for (p in cloud.puffs) {
      cachedDisc(
        cache,
        center = Offset(center.x + p.dx * w - sunSide * w * 0.02f, center.y + p.dy * w + w * 0.03f),
        radius = p.r * w,
        color = shadow,
        alpha = 0.85f * a,
        hardness = 0.45f,
      )
    }
    // I corpi: in basso trasparenti (resta l'ombra), in alto pieni. Ogni batuffolo con la sua tinta.
    for (p in cloud.puffs) {
      val color = lerp(body, if (p.tint >= 0f) lit else shadow, abs(p.tint) * 2f)
      cachedDisc(
        cache,
        center = Offset(center.x + p.dx * w, center.y + p.dy * w),
        radius = p.r * w,
        color = color,
        alpha = lerp(0.45f, 0.97f, p.height) * a,
        hardness = 0.42f,
      )
    }
    // Le luci: sui batuffoli alti, verso il sole e verso l'alto, piu' piccole. E sulle cime un
    // secondo tocco piu' piccolo e piu' pieno: e' li' che il sole batte, ed e' bianco.
    if (quality == SceneQuality.FULL) {
      for (p in cloud.puffs) {
        if (p.height < 0.35f) continue
        cachedDisc(
          cache,
          center = Offset(center.x + p.dx * w + sunSide * w * 0.03f, center.y + p.dy * w - w * 0.045f),
          radius = p.r * w * 0.72f,
          color = lit,
          alpha = (0.9f * (p.height - 0.15f)).coerceIn(0f, 0.85f) * a,
          hardness = 0.3f,
        )
        if (p.height > 0.7f) {
          cachedDisc(
            cache,
            center = Offset(center.x + p.dx * w + sunSide * w * 0.045f, center.y + p.dy * w - w * 0.06f),
            radius = p.r * w * 0.5f,
            color = lit,
            alpha = 0.55f * a,
            hardness = 0.2f,
          )
        }
      }
    }
    // I ciuffi: quasi trasparenti, fuori dalla sagoma, a sfilacciare il bordo.
    for (p in cloud.wisps) {
      cachedDisc(cache, Offset(center.x + p.dx * w, center.y + p.dy * w), p.r * w, body, 0.22f * a)
    }
  }

  private fun DrawScope.drawDeck(deck: Deck, t: Float, sky: SkyPalette.Sky) {
    val width = size.width
    val height = size.height
    val lit = lerp(sky.cloudColor, sky.cloudShadow, 0.2f + deck.darkness * 0.25f)
    val dark = lerp(sky.cloudShadow, Color(0xFF22262F), deck.darkness * 0.55f)

    // Il soffitto: un riempimento continuo dall'alto fino alla base, chiaro in cima e scuro in
    // basso, sotto la texture dei banchi. Senza, fra un banco e l'altro spuntava il cielo azzurro,
    // e un cielo coperto con dei buchi azzurri non e' coperto.
    val ceilingBottom = (deck.bottom + 0.04f) * height
    drawRect(
      brush = Brush.verticalGradient(
        0f to lit.copy(alpha = 0.96f),
        0.45f to lerp(lit, dark, 0.5f).copy(alpha = 0.92f),
        0.85f to dark.copy(alpha = 0.85f),
        1f to dark.copy(alpha = 0f),
        startY = 0f,
        endY = ceilingBottom,
      ),
      topLeft = Offset.Zero,
      size = Size(width, ceilingBottom),
    )
    // La foschia sotto il soffitto: la base sfuma nel grigio, invece di finire con un bordo.
    val mistTop = (deck.bottom - 0.08f) * height
    val mistHeight = height * 0.5f
    drawRect(
      brush = Brush.verticalGradient(
        0f to dark.copy(alpha = 0f),
        0.25f to dark.copy(alpha = 0.55f),
        0.55f to dark.copy(alpha = 0.3f),
        1f to dark.copy(alpha = 0f),
        startY = mistTop,
        endY = mistTop + mistHeight,
      ),
      topLeft = Offset(0f, mistTop),
      size = Size(width, mistHeight),
    )
    for (blob in deck.blobs) {
      val x = ((blob.x + t * blob.drift) % 1.3f) - 0.15f
      val color = lerp(lit, dark, blob.darkness)
      cachedBlob(
        cache,
        center = Offset(x * width, blob.y * height),
        radiusX = blob.rx * width,
        radiusY = blob.ry * height,
        color = color,
        alpha = 0.78f,
        hardness = 0.4f,
      )
      // La pancia di ogni banco, piu' scura: e' quello che da' spessore alla coltre.
      cachedBlob(
        cache,
        center = Offset(x * width, (blob.y + blob.ry * 0.6f) * height),
        radiusX = blob.rx * width * 0.85f,
        radiusY = blob.ry * height * 0.5f,
        color = dark,
        alpha = 0.3f + 0.3f * blob.darkness,
        hardness = 0.3f,
      )
    }
    for (rag in deck.scud) {
      val x = ((rag.x + t * rag.drift) % 1.2f) - 0.1f
      cachedBlob(
        cache,
        center = Offset(x * width, rag.y * height),
        radiusX = rag.rx * width,
        radiusY = rag.ry * height,
        color = dark,
        alpha = 0.5f,
        hardness = 0.25f,
      )
    }
  }

  private fun DrawScope.drawCirrus(cirrus: Cirrus, t: Float, sky: SkyPalette.Sky) {
    val width = size.width
    val height = size.height
    val x = ((cirrus.cx + t * 0.0015f) % 1.3f) - 0.15f
    val center = Offset(x * width, cirrus.cy * height)
    val color = lerp(sky.cloudColor, Color.White, 0.3f)
    for (f in cirrus.filaments) {
      cachedBlob(
        cache,
        center = Offset(center.x + f.dx * width, center.y + f.dy * height),
        radiusX = f.length * width,
        radiusY = f.thickness * height,
        color = color,
        alpha = cirrus.alpha * f.alpha,
        rotationDegrees = f.angle,
      )
    }
  }

  companion object {
    fun forState(state: SkyState, quality: SceneQuality, random: Random): CloudLayer {
      val cover = (state.cloudCoverPercentOrDefault() / 100f).coerceIn(0f, 1f)
      val cumuli = mutableListOf<Cumulus>()
      val cirri = mutableListOf<Cirrus>()
      var deck: Deck? = null

      fun cirrus(alpha: Float) {
        cirri += Cirrus.random(random, alpha)
      }

      fun cumulus(width: Float, y: Float, alpha: Float = 1f) {
        cumuli += Cumulus.random(random, cx = random.nextFloat(), cy = y, width = width, alpha = alpha, quality = quality)
      }

      when (state.kind) {
        WeatherKind.FOG -> Unit
        WeatherKind.THUNDERSTORM, WeatherKind.HEAVY_RAIN ->
          deck = Deck.random(random, darkness = 1f, coverage = 1f, ragged = true, quality = quality)
        WeatherKind.RAIN, WeatherKind.DRIZZLE, WeatherKind.SLEET ->
          deck = Deck.random(random, darkness = 0.7f, coverage = 1f, ragged = state.kind == WeatherKind.RAIN, quality = quality)
        WeatherKind.SNOW, WeatherKind.HEAVY_SNOW ->
          deck = Deck.random(random, darkness = 0.25f, coverage = 1f, ragged = false, quality = quality)
        WeatherKind.CLOUDY -> {
          deck = Deck.random(random, darkness = 0.5f, coverage = 0.85f + cover * 0.15f, ragged = false, quality = quality)
        }
        WeatherKind.PARTLY_CLOUDY -> {
          val count = (2 + (cover * 4f).roundToInt()).coerceIn(2, 5)
          // Le nuvole vicine (grandi, basse) e quelle lontane (piccole, alte): profondita'.
          repeat(count) { i ->
            val near = i % 2 == 0
            cumulus(
              width = if (near) random.between(0.42f, 0.62f) else random.between(0.2f, 0.32f),
              y = if (near) random.between(0.16f, 0.34f) else random.between(0.06f, 0.16f),
            )
          }
        }
        WeatherKind.MOSTLY_CLEAR -> {
          // Uno o due cumuli piccoli e lontani, e a volte un velo alto appena percettibile.
          repeat(1 + random.nextInt(2)) {
            cumulus(width = random.between(0.16f, 0.26f), y = random.between(0.08f, 0.2f), alpha = 0.9f)
          }
          if (random.nextFloat() < 0.4f) cirrus(random.between(0.07f, 0.12f))
        }
        // Il sereno e' sereno: niente in cielo oltre al sole o alla luna e alle stelle.
        WeatherKind.CLEAR, WeatherKind.UNKNOWN, null -> Unit
      }
      // Le vicine si disegnano per ultime, sopra le lontane.
      cumuli.sortBy { it.width }
      return CloudLayer(cumuli, deck, cirri, quality)
    }
  }
}

// ------------------------------------------------------------------------------------------------
// La nebbia
// ------------------------------------------------------------------------------------------------

/**
 * La nebbia: bande orizzontali di foschia che scorrono piano, e un velo su tutto. Il sole, se
 * c'e', resta la macchia diffusa che disegna gia' [CelestialPainter] con la cupezza alta.
 */
internal class FogLayer(random: Random) {
  private class Band(val y: Float, val x: Float, val rx: Float, val ry: Float, val alpha: Float, val speed: Float)

  private val bands = List(8) { i ->
    Band(
      y = 0.22f + (i / 7f) * 0.84f + random.between(-0.03f, 0.03f),
      x = random.nextFloat(),
      rx = random.between(0.7f, 1.1f),
      ry = random.between(0.07f, 0.15f),
      alpha = random.between(0.32f, 0.58f),
      speed = random.between(0.004f, 0.01f) * (if (i % 2 == 0) 1f else -1f),
    )
  }

  fun draw(scope: DrawScope, t: Float, sky: SkyPalette.Sky) {
    val width = scope.size.width
    val height = scope.size.height
    val haze = sky.hazeColor
    // Il velo: la nebbia toglie contrasto a tutto, in basso piu' che in alto.
    scope.drawRect(
      Brush.verticalGradient(
        0f to haze.copy(alpha = 0.22f),
        0.5f to haze.copy(alpha = 0.42f),
        1f to haze.copy(alpha = 0.66f),
        startY = 0f,
        endY = height,
      ),
    )
    for (band in bands) {
      val x = ((band.x + t * band.speed) % 1.6f + 1.6f) % 1.6f - 0.3f
      scope.softBlob(Offset(x * width, band.y * height), band.rx * width, band.ry * height, haze, band.alpha, hardness = 0.25f)
    }
  }
}

// ------------------------------------------------------------------------------------------------
// I fulmini
// ------------------------------------------------------------------------------------------------

/**
 * Il temporale lampeggia. Ogni tanto: un lampo che schiarisce tutto il cielo per un decimo di
 * secondo, con un secondo guizzo subito dopo, e un fulmine che scende dalla coltre.
 *
 * Nessun caso: il ritmo e' una funzione del tempo, cosi' due telefoni con lo stesso seme vedono
 * lo stesso lampo nello stesso istante. Nel fotogramma fermo del widget (t = 0) non c'e' mai un
 * fulmine: un lampo congelato per mezz'ora sulla schermata Home sarebbe assurdo.
 */
internal class LightningField(random: Random) {
  private class Bolt(val points: List<Offset>, val branch: List<Offset>)

  private val period = 7.5f + random.nextFloat() * 4f
  private val offset = random.nextFloat() * period
  private val bolts = List(3) {
    val points = mutableListOf<Offset>()
    var x = random.between(0.2f, 0.8f)
    var y = random.between(0.05f, 0.2f)
    points += Offset(x, y)
    val segments = 7 + random.nextInt(4)
    for (s in 0 until segments) {
      x += random.between(-0.06f, 0.06f)
      y += random.between(0.05f, 0.09f)
      points += Offset(x, y)
    }
    // Un ramo laterale da meta' fulmine: un fulmine senza rami e' una linea a zig zag.
    val fork = points[segments / 2]
    val branch = mutableListOf(fork)
    var bx = fork.x
    var by = fork.y
    val side = if (random.nextBoolean()) 1f else -1f
    repeat(3 + random.nextInt(3)) {
      bx += side * random.between(0.02f, 0.06f)
      by += random.between(0.03f, 0.06f)
      branch += Offset(bx, by)
    }
    Bolt(points, branch)
  }

  fun flashing(t: Float): Boolean = t >= 0.5f && strengthAt(t) > 0f

  private fun strengthAt(t: Float): Float {
    val phase = (t + offset) % period
    return when {
      phase < 0.08f -> 1f
      phase < 0.15f -> 0.3f
      phase < 0.22f -> 0.75f
      phase < 0.32f -> 0.15f
      else -> 0f
    }
  }

  fun draw(scope: DrawScope, t: Float) {
    if (t < 0.5f) return
    val strength = strengthAt(t)
    if (strength <= 0f) return
    val width = scope.size.width
    val height = scope.size.height
    scope.drawRect(Color(0xFFE8F0FF).copy(alpha = 0.16f * strength), blendMode = BlendMode.Plus)
    val bolt = bolts[((t + offset) / period).toInt().mod(bolts.size)]
    val u = scope.unit
    for ((points, isBranch) in listOf(bolt.points to false, bolt.branch to true)) {
      val path = Path()
      points.forEachIndexed { i, p ->
        if (i == 0) path.moveTo(p.x * width, p.y * height) else path.lineTo(p.x * width, p.y * height)
      }
      val core = if (isBranch) 1.1f else 1.7f
      scope.drawPath(path, Color(0xFFCFE0FF).copy(alpha = 0.32f * strength), style = Stroke(width = 8f * u * core / 1.7f, cap = StrokeCap.Round))
      scope.drawPath(path, Color.White.copy(alpha = 0.95f * strength), style = Stroke(width = core * u, cap = StrokeCap.Round))
    }
  }
}

// ------------------------------------------------------------------------------------------------
// Pioggia e neve
// ------------------------------------------------------------------------------------------------

/**
 * Pioggia e neve, su piu' piani di profondita'.
 *
 * La pioggia vicina e' un tratto lungo e chiaro, quella lontana un filo corto e pallido, e vanno
 * a velocita' diverse: e' la parallasse che fa "pioggia" invece di "righe". La neve vicina e' un
 * fiocco grande e sfocato, quella lontana un granello; e ondeggia. Le forti aggiungono un velo che
 * toglie contrasto al cielo, che e' cio' che si vede davvero quando viene giu' sul serio.
 */
internal class PrecipitationField(private val kind: WeatherKind?, quality: SceneQuality, random: Random) {

  private enum class Mode { RAIN, SNOW, NONE }

  private val cache = BrushCache()

  private class Layer(val count: Int, val depth: Float, random: Random) {
    val xs = FloatArray(count) { random.nextFloat() }
    val phases = FloatArray(count) { random.nextFloat() }
    val speeds = FloatArray(count) { 0.8f + random.nextFloat() * 0.4f }
    val sizes = FloatArray(count) { 0.55f + random.nextFloat() * 0.9f }
    val sways = FloatArray(count) { random.nextFloat() * 6.28f }
  }

  private val mode = when (kind) {
    WeatherKind.DRIZZLE, WeatherKind.RAIN, WeatherKind.HEAVY_RAIN, WeatherKind.THUNDERSTORM, WeatherKind.SLEET -> Mode.RAIN
    WeatherKind.SNOW, WeatherKind.HEAVY_SNOW -> Mode.SNOW
    else -> Mode.NONE
  }

  private val scale = when (quality) {
    SceneQuality.FULL -> 1f
    SceneQuality.REDUCED -> 0.5f
    SceneQuality.STATIC -> 0f
  }

  private val layers: List<Layer> = run {
    val base = when (kind) {
      WeatherKind.DRIZZLE -> 90
      WeatherKind.RAIN -> 170
      WeatherKind.SLEET -> 140
      WeatherKind.HEAVY_RAIN -> 300
      WeatherKind.THUNDERSTORM -> 320
      WeatherKind.SNOW -> 130
      WeatherKind.HEAVY_SNOW -> 230
      else -> 0
    }
    val total = (base * scale).toInt()
    when {
      total == 0 -> emptyList()
      mode == Mode.SNOW -> listOf(
        Layer((total * 0.5f).toInt(), 0f, random),
        Layer((total * 0.32f).toInt(), 0.5f, random),
        Layer((total * 0.18f).toInt(), 1f, random),
      )
      else -> listOf(Layer((total * 0.6f).toInt(), 0f, random), Layer((total * 0.4f).toInt(), 1f, random))
    }
  }

  /** I fiocchi nella pioggia mista: il nevischio non e' pioggia sottile, e' pioggia con dentro la neve. */
  private val sleetFlakes = if (kind == WeatherKind.SLEET) Layer((40 * scale).toInt(), 0.6f, random) else null

  /**
   * L'inclinazione: quanto ogni goccia si sposta di lato per ogni pixel che scende. Un rapporto,
   * non una distanza — cosi' il tratto della goccia e la sua traiettoria hanno la STESSA pendenza.
   * Prima il tratto era inclinato dal vento ma la goccia scendeva quasi verticale, e l'occhio se
   * ne accorgeva senza saper dire cosa non andava.
   */
  private val slantRatio = when (kind) {
    WeatherKind.DRIZZLE -> 0.06f
    WeatherKind.RAIN, WeatherKind.SLEET -> 0.16f
    WeatherKind.HEAVY_RAIN -> 0.3f
    WeatherKind.THUNDERSTORM -> 0.38f
    else -> 0f
  }

  /** La neve va di traverso col vento, poco: la forte di piu'. */
  private val snowDrift = when (kind) {
    WeatherKind.SNOW -> 0.05f
    WeatherKind.HEAVY_SNOW -> 0.12f
    else -> 0f
  }

  fun draw(scope: DrawScope, t: Float, sky: SkyPalette.Sky) {
    if (layers.isEmpty() || mode == Mode.NONE) return
    cache.sync(scope.size)
    val width = scope.size.width
    val height = scope.size.height
    val u = scope.unit

    // Il velo delle forti: contrasto giu', cielo piu' lattiginoso.
    val veil = when (kind) {
      WeatherKind.HEAVY_RAIN -> 0.10f
      WeatherKind.THUNDERSTORM -> 0.12f
      WeatherKind.HEAVY_SNOW -> 0.16f
      WeatherKind.SNOW -> 0.07f
      else -> 0f
    }
    if (veil > 0f) scope.drawRect(sky.hazeColor.copy(alpha = veil))

    when (mode) {
      Mode.RAIN -> {
        // Le raffiche: la pendenza respira, non e' un binario.
        val slant = slantRatio * (1f + 0.3f * sin(t * 0.45f))
        val tint = lerp(Color(0xFFDCE7F3), sky.hazeColor, 0.3f)
        for (layer in layers) {
          val near = layer.depth
          val length = height * lerp(0.022f, 0.05f, near)
          val stroke = u * lerp(0.9f, 1.9f, near)
          val alpha = lerp(0.2f, 0.46f, near) * (if (kind == WeatherKind.DRIZZLE) 0.75f else 1f)
          val speed = lerp(0.55f, 1f, near)
          for (i in 0 until layer.count) {
            val progress = (layer.phases[i] + t * layer.speeds[i] * speed * 0.9f) % 1.1f
            val fallen = progress * height * 1.1f
            val y = fallen - height * 0.05f
            // La goccia scorre lungo la propria inclinazione, e rientra dall'altro lato quando esce.
            val x = ((layer.xs[i] * width - fallen * slant) % width + width) % width
            val drop = length * layer.sizes[i]
            val head = Offset(x - slant * drop, y + drop)
            val middle = Offset(x - slant * drop * 0.5f, y + drop * 0.5f)
            // La coda sfuma e la testa e' piena: e' la scia di una goccia che cade, non un
            // trattino. Due segmenti invece di un gradiente per goccia, che sarebbe uno shader
            // nuovo a ogni goccia a ogni fotogramma.
            scope.drawLine(
              color = tint.copy(alpha = alpha * 0.35f * layer.sizes[i]),
              start = Offset(x, y),
              end = middle,
              strokeWidth = stroke * 0.75f,
              cap = StrokeCap.Round,
            )
            scope.drawLine(
              color = tint.copy(alpha = alpha * layer.sizes[i]),
              start = middle,
              end = head,
              strokeWidth = stroke,
              cap = StrokeCap.Round,
            )
          }
        }
        sleetFlakes?.let { scope.drawFlakes(it, t, u, width, height, 0.6f) }
      }
      Mode.SNOW -> for (layer in layers) scope.drawFlakes(layer, t, u, width, height, layer.depth, snowDrift)
      Mode.NONE -> Unit
    }
  }

  private fun DrawScope.drawFlakes(layer: Layer, t: Float, u: Float, width: Float, height: Float, near: Float, drift: Float = 0f) {
    val radius = u * lerp(1.0f, 3.6f, near)
    val alpha = lerp(0.35f, 0.9f, near)
    val speed = lerp(0.055f, 0.14f, near)
    val swayAmplitude = width * lerp(0.006f, 0.018f, near)
    for (i in 0 until layer.count) {
      val progress = (layer.phases[i] + t * layer.speeds[i] * speed) % 1.1f
      val fallen = progress * height * 1.1f
      val y = fallen - height * 0.05f
      val sway = sin(t * layer.speeds[i] * 0.9f + layer.sways[i]) * swayAmplitude
      // Il vento porta i fiocchi di traverso, i vicini piu' dei lontani; e si rientra dall'altro lato.
      val x = ((layer.xs[i] * width + sway - fallen * drift * (0.5f + near * 0.5f)) % width + width) % width
      val center = Offset(x, y)
      val r = radius * layer.sizes[i]
      if (near > 0.4f) {
        // I fiocchi vicini sono fuori fuoco: un centro pieno e un bordo che sfuma.
        cachedDisc(cache, center, r, Color.White, alpha, hardness = 0.45f)
      } else {
        drawCircle(Color.White.copy(alpha = alpha), r, center)
      }
    }
  }
}

// ------------------------------------------------------------------------------------------------
// Il velo del widget
// ------------------------------------------------------------------------------------------------

/**
 * La velatura sotto il testo del widget.
 *
 * Il widget scrive in bianco fisso, ed e' la scelta giusta su un cielo notturno. Ma neve e nebbia
 * portano il gradiente verso il grigio chiaro, e li' il bianco su bianco sparisce. Un velo scuro
 * in fondo, dipinto **dentro la stessa bitmap**, lo tiene leggibile senza cambiare identita'.
 */
internal object ScrimPainter {

  fun draw(scope: DrawScope, sky: SkyPalette.Sky) {
    val strength = scrimStrength(sky)
    if (strength <= 0f) return
    val height = scope.size.height
    scope.drawRect(
      brush = Brush.verticalGradient(
        colors = listOf(Color.Transparent, Color.Black.copy(alpha = strength)),
        startY = height * 0.2f,
        endY = height,
      ),
    )
  }

  /**
   * Quanto velo serve, dalla luminanza del cielo in basso: nessuno su un cielo notturno, tanto su
   * una nevicata. Si guarda l'ultimo colore del gradiente perche' e' quello sotto il testo.
   */
  fun scrimStrength(sky: SkyPalette.Sky): Float {
    val ground = sky.stops.lastOrNull()?.second ?: return 0f
    val luminance = 0.2126f * ground.red + 0.7152f * ground.green + 0.0722f * ground.blue
    return ((luminance - 0.4f) / 0.5f).coerceIn(0f, 1f) * 0.55f
  }
}
