package dev.pampa.fluidweather.core.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.util.lerp
import dev.pampa.fluidweather.core.model.Moon
import dev.pampa.fluidweather.core.model.MoonEphemeris
import dev.pampa.fluidweather.core.model.MoonPhase
import dev.pampa.fluidweather.core.model.SolarEphemeris
import dev.pampa.fluidweather.core.model.SunTimes
import dev.pampa.fluidweather.core.model.WeatherKind
import java.time.Instant
import java.time.ZoneId
import java.util.Random
import kotlin.math.PI
import kotlin.math.sin

/*
 * I pennelli del cielo, staccati dalla scena che li usa.
 *
 * Stavano dentro `WeatherScene` come classi private, ed era giusto finche' il cielo lo disegnava
 * solo la home. Il widget di sistema pero' deve mostrare **la stessa** cosa, e Glance non ha una
 * tela: le sue composable diventano RemoteViews. L'unico modo di non avere due cieli che divergono
 * e' che il widget rasterizzi in una bitmap chiamando queste stesse funzioni — cosa che si puo'
 * fare solo se sono funzioni di un `DrawScope` e non pezzi privati di una composable.
 */

/**
 * Il sole o la luna: dove sta in cielo adesso, e quanto e' illuminato.
 *
 * Mancava del tutto — non solo nel widget: la scena della home aveva nuvole, stelle, pioggia e
 * neve, e nessun astro. Un cielo sereno di mezzogiorno e uno di mezzanotte si distinguevano solo
 * dal colore.
 */
data class CelestialBody(
  val kind: Kind,
  /** 0 = bordo sinistro, 1 = bordo destro. */
  val x: Float,
  /** 0 = in cima, 1 = in fondo. Sale con l'altezza vera sull'orizzonte. */
  val y: Float,
  /** 0..1: la frazione illuminata. Per il sole e' sempre 1. */
  val illuminated: Float,
  /** Crescente: l'ombra sta a sinistra. Calante: a destra. */
  val waxing: Boolean,
) {
  enum class Kind { SUN, MOON }
}

/**
 * Dove sta l'astro adesso. Aritmetica pura sulle effemeridi che l'app ha gia': zero rete, zero
 * permessi, e le stesse funzioni con cui la pagina Sole e la pagina Luna dicono i loro orari.
 */
object Celestial {

  /**
   * Sotto questa altezza il sole e' tramontato per davvero, non "quasi".
   *
   * Non zero: il disco resta visibile per rifrazione fin sotto l'orizzonte geometrico, e a -2 la
   * scena continua a mostrarlo mentre il gradiente e' gia' quello del crepuscolo — che e'
   * esattamente cio' che si vede fuori dalla finestra.
   */
  private const val SUN_VISIBLE_ABOVE_DEG = -2.0

  /** La luna si disegna solo se e' davvero sopra l'orizzonte: "c'e' la luna" e' un fatto, non un'ora. */
  private const val MOON_VISIBLE_ABOVE_DEG = 0.0

  /** Sotto questa frazione illuminata non c'e' niente da disegnare: e' la luna nuova. */
  private const val MOON_INVISIBLE_BELOW = 0.04

  fun at(
    nowMillis: Long,
    latitude: Double?,
    longitude: Double?,
    zone: ZoneId = ZoneId.systemDefault(),
  ): CelestialBody? {
    if (latitude == null || longitude == null) return fromClock(nowMillis, zone)
    val sunElevation = SolarEphemeris.elevationDegrees(nowMillis, latitude, longitude)
    if (sunElevation > SUN_VISIBLE_ABOVE_DEG) {
      return CelestialBody(
        kind = CelestialBody.Kind.SUN,
        x = arcX(nowMillis, SunTimes.forDay(dayStartUtc(nowMillis, zone), latitude, longitude).let {
          it.sunriseMillis to it.sunsetMillis
        }, zone),
        y = arcY(sunElevation),
        illuminated = 1f,
        waxing = true,
      )
    }
    val moonAltitude = MoonEphemeris.altitudeDegrees(nowMillis, latitude, longitude)
    if (moonAltitude <= MOON_VISIBLE_ABOVE_DEG) return null
    val illuminated = Moon.illuminatedFraction(nowMillis).toFloat()
    if (illuminated < MOON_INVISIBLE_BELOW) return null
    val times = MoonEphemeris.riseSet(dayStartUtc(nowMillis, zone), latitude, longitude)
    return CelestialBody(
      kind = CelestialBody.Kind.MOON,
      x = arcX(nowMillis, times.riseMillis to times.setMillis, zone),
      y = arcY(moonAltitude),
      illuminated = illuminated,
      waxing = Moon.phase(nowMillis).isWaxing(),
    )
  }

  /**
   * Senza coordinate: l'orologio.
   *
   * Grossolano — la luna finta e' sempre mezza e sempre crescente — ma la stessa scelta che fanno
   * gia' il cielo della home e quello del widget quando non c'e' ancora una posizione. Un cielo
   * senza niente dentro sarebbe piu' sbagliato di un cielo approssimato.
   */
  private fun fromClock(nowMillis: Long, zone: ZoneId): CelestialBody {
    val hour = Instant.ofEpochMilli(nowMillis).atZone(zone).hour
    val day = hour in 7..18
    val span = if (day) (hour - 7) / 11f else ((hour + 5) % 24) / 12f
    return CelestialBody(
      kind = if (day) CelestialBody.Kind.SUN else CelestialBody.Kind.MOON,
      x = 0.12f + span.coerceIn(0f, 1f) * 0.76f,
      y = arcY(60.0 * sin(span.coerceIn(0f, 1f) * PI).toFloat().toDouble()),
      illuminated = if (day) 1f else 0.5f,
      waxing = true,
    )
  }

  /** Da est a ovest lungo l'arco: 0,12 all'alba, 0,88 al tramonto. */
  private fun arcX(nowMillis: Long, riseSet: Pair<Long?, Long?>, zone: ZoneId): Float {
    val (rise, set) = riseSet
    if (rise == null || set == null || set <= rise) {
      // Niente alba o niente tramonto (le estati polari, o una luna che non tramonta): resta
      // l'orologio, che almeno fa muovere l'astro nella direzione giusta.
      val minutes = Instant.ofEpochMilli(nowMillis).atZone(zone).let { it.hour * 60 + it.minute }
      return 0.12f + (minutes % (12 * 60)) / (12f * 60f) * 0.76f
    }
    val progress = ((nowMillis - rise).toDouble() / (set - rise)).coerceIn(0.0, 1.0)
    return (0.12 + progress * 0.76).toFloat()
  }

  /**
   * L'altezza sull'orizzonte in altezza sulla tela: allo zenit sta in cima, all'orizzonte
   * appoggiato al bordo basso dell'area di cielo.
   */
  private fun arcY(altitudeDegrees: Double): Float {
    val normalized = (altitudeDegrees / 60.0).coerceIn(0.0, 1.0)
    return (SKY_BOTTOM - normalized * (SKY_BOTTOM - SKY_TOP)).toFloat()
  }

  private fun dayStartUtc(nowMillis: Long, zone: ZoneId): Long =
    Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
      .atStartOfDay(ZoneId.of("UTC")).toInstant().toEpochMilli()

  private fun MoonPhase.isWaxing(): Boolean = when (this) {
    MoonPhase.WAXING_CRESCENT, MoonPhase.FIRST_QUARTER, MoonPhase.WAXING_GIBBOUS -> true
    else -> false
  }

  /** Il disco non sale mai oltre un decimo dall'alto ne' scende sotto la meta' della tela. */
  private const val SKY_TOP = 0.10
  private const val SKY_BOTTOM = 0.46
}

/**
 * Il disco del sole o della luna, con il suo alone.
 *
 * Si disegna **prima** delle nuvole e dopo le stelle: e' l'unica ragione per cui un cielo coperto
 * all'ottanta per cento si legge come coperto invece che come "sereno con delle macchie bianche".
 */
internal object CelestialPainter {

  fun draw(scope: DrawScope, body: CelestialBody, sky: SkyPalette.Sky) {
    val width = scope.size.width
    val height = scope.size.height
    // Il raggio segue il lato corto: su un widget largo e basso un disco proporzionato alla
    // larghezza diventerebbe un sole che occupa mezza schermata.
    val radius = minOf(width, height) * if (body.kind == CelestialBody.Kind.SUN) 0.085f else 0.070f
    val center = Offset(body.x * width, body.y * height)
    // Dietro una coltre il sole non ha alone e il disco e' appena un chiarore: la cupezza del
    // cielo, che SkyPalette calcola gia' per il gradiente, decide quanto resta visibile.
    val visibility = (1f - sky.gloom * 0.85f).coerceIn(0.12f, 1f)
    val color = if (body.kind == CelestialBody.Kind.SUN) SkyPalette.SunColor else SkyPalette.MoonColor

    scope.drawCircle(
      brush = Brush.radialGradient(
        colors = listOf(color.copy(alpha = 0.34f * visibility), color.copy(alpha = 0f)),
        center = center,
        radius = radius * HALO_SCALE,
      ),
      radius = radius * HALO_SCALE,
      center = center,
    )
    scope.drawCircle(color = color.copy(alpha = visibility), radius = radius, center = center)

    if (body.kind == CelestialBody.Kind.MOON && body.illuminated < 0.98f) {
      scope.drawMoonShadow(center, radius, body, sky)
    }
  }

  /**
   * La fase: un secondo disco, del colore del cielo dietro, che morde il primo.
   *
   * Il terminatore vero e' un'ellisse, non un cerchio — ma a questi raggi (poche decine di pixel,
   * una quindicina sul widget) la differenza e' meno di un pixel, e un cerchio spostato si disegna
   * con una primitiva sola invece che con un path. Il verso lo decide se la luna e' crescente:
   * l'ombra a sinistra quando cresce, a destra quando cala.
   *
   * Due dettagli che sembrano pignoleria e non lo sono:
   *
   * - **si ritaglia al disco, non al suo quadrato.** I due cerchi hanno lo stesso raggio, quindi
   *   verso il bordo opposto l'ombra sporge oltre la luna: dentro un `clipRect` resterebbe una
   *   falce scura appiccicata di fianco alla luna, sul cielo.
   * - **il colore e' quello del cielo a quell'altezza**, non il primo del gradiente. Il gradiente
   *   e' verticale, e una luna a meta' schermo avrebbe dietro una tinta diversa da quella in cima:
   *   con il colore sbagliato la parte non illuminata si vede come una macchia invece che come
   *   niente.
   */
  private fun DrawScope.drawMoonShadow(
    center: Offset,
    radius: Float,
    body: CelestialBody,
    sky: SkyPalette.Sky,
  ) {
    // Da 0 (piena) a 2r (nuova): quanto il disco d'ombra e' spostato rispetto a quello di luce.
    val offset = radius * 2f * (1f - body.illuminated)
    val direction = if (body.waxing) -1f else 1f
    val disc = Path().apply {
      addOval(Rect(center = center, radius = radius))
    }
    clipPath(disc) {
      drawCircle(
        color = skyColorAt(sky, body.y),
        radius = radius,
        center = Offset(center.x + direction * offset, center.y),
      )
    }
  }

  /**
   * Il colore del cielo a quella altezza, dal gradiente a tre fermate (0, 0,55, 1) che dipinge la
   * scena. Serve all'ombra della luna, che deve sparire nel cielo invece di somigliargli.
   */
  private fun skyColorAt(sky: SkyPalette.Sky, y: Float): Color {
    val top = sky.gradient.getOrNull(0) ?: return Color.Black
    val mid = sky.gradient.getOrNull(1) ?: top
    val bottom = sky.gradient.getOrNull(2) ?: mid
    return if (y <= 0.55f) {
      androidx.compose.ui.graphics.lerp(top, mid, (y / 0.55f).coerceIn(0f, 1f))
    } else {
      androidx.compose.ui.graphics.lerp(mid, bottom, ((y - 0.55f) / 0.45f).coerceIn(0f, 1f))
    }
  }

  private const val HALO_SCALE = 3.2f
}

/**
 * Nuvole procedurali: otto banchi, ciascuno tre lobi radiali sovrapposti, che scorrono a
 * velocita' diverse (parallasse). La copertura decide quanti banchi esistono e quanto pieni.
 */
internal class CloudField {
  private val random = Random(1913L)
  private val banks = List(8) { index ->
    Bank(
      y = 0.06f + random.nextFloat() * 0.30f,
      scale = 0.6f + random.nextFloat() * 0.8f,
      speed = (0.004f + random.nextFloat() * 0.010f) * (if (index % 2 == 0) 1f else 1.4f),
      seed = random.nextFloat(),
    )
  }

  private class Bank(val y: Float, val scale: Float, val speed: Float, val seed: Float)

  fun draw(scope: DrawScope, t: Float, sky: SkyPalette.Sky, coverPercent: Float) {
    val visible = ((coverPercent / 100f) * banks.size).toInt().coerceIn(0, banks.size)
    if (visible == 0) return
    val width = scope.size.width
    val height = scope.size.height
    val alpha = lerp(0.35f, 0.75f, coverPercent / 100f)

    for (index in 0 until visible) {
      val bank = banks[index]
      val cloudWidth = width * 0.9f * bank.scale
      val travel = width + cloudWidth
      val x = ((bank.seed * travel + t * bank.speed * width) % travel) - cloudWidth / 2
      val y = height * bank.y
      val radius = cloudWidth / 3.4f
      // Tre lobi: il centro pieno, i fianchi piu' morbidi. Radiali, quindi senza bordi duri.
      lobo(scope, x, y, radius * 1.15f, sky.cloudColor, alpha)
      lobo(scope, x - radius, y + radius * 0.25f, radius * 0.9f, sky.cloudColor, alpha * 0.85f)
      lobo(scope, x + radius, y + radius * 0.2f, radius * 0.95f, sky.cloudColor, alpha * 0.85f)
    }
  }

  private fun lobo(scope: DrawScope, x: Float, y: Float, r: Float, color: Color, alpha: Float) {
    scope.drawCircle(
      brush = Brush.radialGradient(
        colors = listOf(color.copy(alpha = alpha), color.copy(alpha = 0f)),
        center = Offset(x, y),
        radius = r,
      ),
      radius = r,
      center = Offset(x, y),
    )
  }
}

/** Stelle nelle notti pulite: punti fissi, tremolio in aritmetica pura. */
internal class StarField {
  private val random = Random(417L)
  private val xs = FloatArray(70) { random.nextFloat() }
  private val ys = FloatArray(70) { random.nextFloat() * 0.55f }
  private val sizes = FloatArray(70) { 0.8f + random.nextFloat() * 1.6f }
  private val phases = FloatArray(70) { random.nextFloat() * 6.28f }
  private val speeds = FloatArray(70) { 0.5f + random.nextFloat() * 1.5f }

  fun draw(scope: DrawScope, t: Float, baseAlpha: Float) {
    val width = scope.size.width
    val height = scope.size.height
    for (i in xs.indices) {
      val twinkle = 0.65f + 0.35f * sin(t * speeds[i] + phases[i])
      scope.drawCircle(
        color = Color.White.copy(alpha = (baseAlpha * twinkle).coerceIn(0f, 1f)),
        radius = sizes[i],
        center = Offset(xs[i] * width, ys[i] * height),
      )
    }
  }
}

/** Pioggia e neve. Conteggi per qualita': FULL paga il realismo, REDUCED la meta', STATIC zero. */
internal class ParticleField(kind: WeatherKind?, quality: SceneQuality) {

  private enum class Mode { RAIN, SNOW, NONE }

  private val mode = when (kind) {
    WeatherKind.DRIZZLE, WeatherKind.RAIN, WeatherKind.HEAVY_RAIN,
    WeatherKind.THUNDERSTORM, WeatherKind.SLEET,
    -> Mode.RAIN
    WeatherKind.SNOW, WeatherKind.HEAVY_SNOW -> Mode.SNOW
    else -> Mode.NONE
  }

  private val count: Int = run {
    val base = when (kind) {
      WeatherKind.DRIZZLE -> 60
      WeatherKind.RAIN, WeatherKind.SLEET -> 130
      WeatherKind.HEAVY_RAIN, WeatherKind.THUNDERSTORM -> 220
      WeatherKind.SNOW -> 90
      WeatherKind.HEAVY_SNOW -> 160
      else -> 0
    }
    when (quality) {
      SceneQuality.FULL -> base
      SceneQuality.REDUCED -> base / 2
      SceneQuality.STATIC -> 0
    }
  }

  private val random = Random(7331L)
  private val xs = FloatArray(count) { random.nextFloat() }
  private val phases = FloatArray(count) { random.nextFloat() }
  private val speeds = FloatArray(count) { 0.75f + random.nextFloat() * 0.5f }
  private val lengths = FloatArray(count) { 0.7f + random.nextFloat() * 0.6f }

  fun draw(scope: DrawScope, t: Float) {
    if (count == 0 || mode == Mode.NONE) return
    val width = scope.size.width
    val height = scope.size.height
    when (mode) {
      Mode.RAIN -> {
        val slant = width * 0.02f
        val streak = height * 0.035f
        for (i in xs.indices) {
          val progress = (phases[i] + t * speeds[i] * 0.9f) % 1.1f
          val y = progress * height * 1.1f - height * 0.05f
          val x = xs[i] * width - progress * slant
          scope.drawLine(
            color = Color(0xFFBFD4EA).copy(alpha = 0.38f),
            start = Offset(x, y),
            end = Offset(x - slant * 0.3f, y + streak * lengths[i]),
            strokeWidth = 2f,
            cap = StrokeCap.Round,
          )
        }
      }
      Mode.SNOW -> {
        for (i in xs.indices) {
          val progress = (phases[i] + t * speeds[i] * 0.12f) % 1.1f
          val y = progress * height * 1.1f - height * 0.05f
          val sway = sin(t * speeds[i] + phases[i] * 6.28f) * width * 0.015f
          scope.drawCircle(
            color = Color.White.copy(alpha = 0.65f),
            radius = 2.2f + lengths[i] * 1.8f,
            center = Offset(xs[i] * width + sway, y),
          )
        }
      }
      Mode.NONE -> Unit
    }
  }
}

/**
 * La velatura sotto il testo del widget.
 *
 * Il widget scrive in bianco fisso, ed e' la scelta giusta su un cielo notturno. Ma neve e nebbia
 * portano il gradiente verso il grigio chiaro ([SkyPalette] lo chiama `whiteout`), e li' il bianco
 * su bianco sparisce. Cambiare colore al testo lo staccherebbe dall'app; un velo scuro in fondo,
 * dipinto **dentro la stessa bitmap**, lo tiene leggibile senza cambiare identita'.
 *
 * Vive qui e non nel widget perche' il velo e' una decisione sul cielo, e il cielo sta qui.
 */
internal object ScrimPainter {

  fun draw(scope: DrawScope, sky: SkyPalette.Sky) {
    val strength = scrimStrength(sky)
    if (strength <= 0f) return
    val height = scope.size.height
    scope.drawRect(
      brush = Brush.verticalGradient(
        colors = listOf(Color.Transparent, Color.Black.copy(alpha = strength)),
        startY = height * 0.25f,
        endY = height,
      ),
    )
  }

  /**
   * Quanto velo serve, dalla luminanza del cielo in basso: nessuno su un cielo notturno, tanto su
   * una nevicata. Si guarda l'ultimo colore del gradiente perche' e' quello sotto il testo.
   */
  fun scrimStrength(sky: SkyPalette.Sky): Float {
    val ground = sky.gradient.lastOrNull() ?: return 0f
    val luminance = 0.2126f * ground.red + 0.7152f * ground.green + 0.0722f * ground.blue
    return ((luminance - 0.45f) / 0.45f).coerceIn(0f, 1f) * 0.45f
  }
}

/**
 * Un fotogramma fermo della scena, per chi non ha una tela viva.
 *
 * E' la porta pubblica dei pennelli: il widget di sistema disegna il cielo chiamando questa, che
 * e' la stessa sequenza che compone [WeatherScene]. Non una copia con gli stessi colori — proprio
 * le stesse funzioni, nello stesso ordine, cosi' app e schermata Home non possono divergere.
 */
object SkyFrame {

  fun draw(
    scope: DrawScope,
    state: SkyState,
    nowMillis: Long,
    quality: SceneQuality = SceneQuality.FULL,
    zone: java.time.ZoneId = java.time.ZoneId.systemDefault(),
    /** Il velo sotto il testo: lo vuole il widget, che ci scrive sopra in bianco. */
    scrim: Boolean = false,
  ) {
    val sky = SkyPalette.sky(state.phase, state.kind, state.cloudCoverPercent)
    scope.drawRect(
      Brush.verticalGradient(
        0f to sky.gradient[0],
        0.55f to sky.gradient[1],
        1f to sky.gradient[2],
      ),
    )
    if (sky.starAlpha > 0f) StarField().draw(scope, 0f, sky.starAlpha)
    Celestial.at(nowMillis, state.latitude, state.longitude, zone)
      ?.let { CelestialPainter.draw(scope, it, sky) }
    CloudField().draw(scope, 0f, sky, state.cloudCoverPercentOrDefault())
    ParticleField(state.kind, quality).draw(scope, 0f)
    if (scrim) ScrimPainter.draw(scope, sky)
  }
}

/**
 * La copertura da mostrare quando i provider non la dicono: dedotta dal tipo di tempo.
 *
 * Vive qui perche' la usano in due — la scena viva e il fotogramma fermo — e una copertura
 * dedotta in due modi diversi sarebbe un cielo diverso nell'app e sulla schermata Home.
 */
internal fun SkyState.cloudCoverPercentOrDefault(): Float =
  (cloudCoverPercent ?: when (kind) {
    WeatherKind.CLEAR -> 5.0
    WeatherKind.MOSTLY_CLEAR -> 20.0
    WeatherKind.PARTLY_CLOUDY -> 45.0
    null -> 30.0
    else -> 85.0
  }).toFloat()
