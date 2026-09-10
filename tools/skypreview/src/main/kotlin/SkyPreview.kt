import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import dev.pampa.fluidweather.core.model.DayPhase
import dev.pampa.fluidweather.core.model.SolarEphemeris
import dev.pampa.fluidweather.core.model.WeatherKind
import dev.pampa.fluidweather.core.ui.Celestial
import dev.pampa.fluidweather.core.ui.CelestialBody
import dev.pampa.fluidweather.core.ui.SceneQuality
import dev.pampa.fluidweather.core.ui.SkyFrame
import dev.pampa.fluidweather.core.ui.SkyPalette
import dev.pampa.fluidweather.core.ui.SkyScenePainter
import dev.pampa.fluidweather.core.ui.SkyVariation
import dev.pampa.fluidweather.core.ui.SkyState
import java.awt.Color
import java.awt.Font
import java.awt.image.BufferedImage
import java.io.File
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import javax.imageio.ImageIO

/*
 * Il banco di anteprima del cielo.
 *
 * Disegna i fotogrammi con gli stessi pittori dell'app e li mette in tavole di contatto, una per
 * fase del giorno, cosi' si vedono tutte le condizioni una accanto all'altra. Si lancia con
 * `./gradlew.bat -p tools/skypreview run` dalla radice; i PNG finiscono in tools/skypreview/out.
 *
 * Le coordinate sono Firenze e la data e' fissa (meta' giugno 2026), cosi' due rese si confrontano
 * pixel per pixel: un cielo che cambia ogni volta non si puo' giudicare.
 */

private val zone: ZoneId = ZoneId.of("Europe/Rome")
private const val LAT = 43.77
private const val LON = 11.26

/** La cella di una tavola: proporzioni di un telefono, a densita' 1. */
private const val CELL_W = 360
private const val CELL_H = 640

private val kinds = listOf(
  WeatherKind.CLEAR, WeatherKind.MOSTLY_CLEAR, WeatherKind.PARTLY_CLOUDY, WeatherKind.CLOUDY,
  WeatherKind.FOG, WeatherKind.DRIZZLE, WeatherKind.RAIN, WeatherKind.HEAVY_RAIN,
  WeatherKind.SLEET, WeatherKind.SNOW, WeatherKind.HEAVY_SNOW, WeatherKind.THUNDERSTORM,
)

fun main(args: Array<String>) {
  val out = File(System.getProperty("skypreview.out") ?: "out").apply { mkdirs() }
  val only = args.firstOrNull { it.startsWith("--only=") }?.removePrefix("--only=")?.split(',')?.toSet()

  val instants = instants()
  for ((phase, at) in instants) {
    if (only != null && phase.name.lowercase() !in only) continue
    val sheet = contactSheet(
      title = "${phase.name.lowercase()}  ${LocalDateTime.ofInstant(Instant.ofEpochMilli(at), zone)}",
      columns = 4,
      cellW = CELL_W,
      cellH = CELL_H,
      cells = kinds.map { kind ->
        kind.name.lowercase() to render(CELL_W, CELL_H, phase, kind, at, cover = null, t = 0f, scrim = false)
      },
    )
    save(sheet, File(out, "sheet-${phase.name.lowercase()}.png"))
  }

  if (only == null || "motion" in only) {
    val (phase, at) = instants.first { it.first == DayPhase.DAY }
    val motionKinds = listOf(WeatherKind.PARTLY_CLOUDY, WeatherKind.RAIN, WeatherKind.SNOW, WeatherKind.THUNDERSTORM)
    val frames = listOf(0f, 4f, 8f, 12f)
    val sheet = contactSheet(
      title = "motion  t = 0 / 4 / 8 / 12 s",
      columns = frames.size,
      cellW = CELL_W,
      cellH = CELL_H,
      cells = motionKinds.flatMap { kind ->
        frames.map { t -> "${kind.name.lowercase()} t=${t.toInt()}" to render(CELL_W, CELL_H, phase, kind, at, null, t, false) }
      },
    )
    save(sheet, File(out, "sheet-motion.png"))
  }

  if (only == null || "widget" in only) {
    // Le tre taglie del widget alle loro dimensioni vere, con la velatura, giorno e notte.
    val tiers = listOf(220 to 220, 220 to 97, 220 to 176)
    val widgetKinds = listOf(WeatherKind.CLEAR, WeatherKind.PARTLY_CLOUDY, WeatherKind.RAIN, WeatherKind.SNOW, WeatherKind.FOG)
    val cells = mutableListOf<Pair<String, BufferedImage>>()
    for (phase in listOf(DayPhase.DAY, DayPhase.NIGHT)) {
      val at = instants.first { it.first == phase }.second
      for (kind in widgetKinds) {
        for ((w, h) in tiers) {
          cells += "${phase.name.lowercase()} ${kind.name.lowercase()} ${w}x$h" to
            render(w, h, phase, kind, at, null, 0f, scrim = true)
        }
      }
    }
    save(contactSheet("widget", columns = 6, cellW = 220, cellH = 220, cells = cells), File(out, "sheet-widget.png"))
  }

  if (only == null || "phone" in only) {
    // Un fotogramma a densita' da telefono (2.75x): e' quello che si vede davvero.
    val (phase, at) = instants.first { it.first == DayPhase.DAY }
    save(render(1080, 2340, phase, WeatherKind.PARTLY_CLOUDY, at, null, 0f, false), File(out, "phone-day-partly.png"))
    val (nightPhase, nightAt) = instants.first { it.first == DayPhase.NIGHT }
    save(render(1080, 2340, nightPhase, WeatherKind.CLEAR, nightAt, null, 0f, false), File(out, "phone-night-clear.png"))
  }

  if (only == null || "variants" in only) {
    // Lo stesso tempo in quattro giorni diversi: il seme cambia, le nuvole devono cambiare.
    val (phase, at) = instants.first { it.first == DayPhase.DAY }
    val cells = mutableListOf<Pair<String, BufferedImage>>()
    for (kind in listOf(WeatherKind.PARTLY_CLOUDY, WeatherKind.CLOUDY, WeatherKind.MOSTLY_CLEAR)) {
      for (day in 0 until 4) {
        cells += "${kind.name.lowercase()} +${day}g" to render(CELL_W, CELL_H, phase, kind, at + day * 86_400_000L, null, 0f, false)
      }
    }
    save(contactSheet("variants", 4, CELL_W, CELL_H, cells), File(out, "sheet-variants.png"))
  }

  if (only == null || "lightning" in only) {
    // Il temporale nell'istante di un lampo: si cerca il primo t in cui lampeggia.
    val (phase, at) = instants.first { it.first == DayPhase.DAY }
    val painter = SkyScenePainter(SkyState(phase, WeatherKind.THUNDERSTORM, null, LAT, LON), SceneQuality.FULL, SkyVariation.seedFor(at, zone))
    val flashAt = generateSequence(0.5f) { it + 0.02f }.take(1200).firstOrNull { painter.flashing(it) }
    if (flashAt != null) {
      val sky = SkyPalette.sky(phase, WeatherKind.THUNDERSTORM, null)
      val body = Celestial.at(at, LAT, LON, zone)
      val bitmap = ImageBitmap(CELL_W, CELL_H)
      CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(bitmap), Size(CELL_W.toFloat(), CELL_H.toFloat())) {
        painter.draw(this, sky, body, flashAt)
      }
      save(bitmap.toAwtImage(), File(out, "lightning.png"))
      println("  lampo a t=$flashAt")
    } else {
      println("  nessun lampo trovato")
    }
  }

  println("fotogrammi in ${out.absolutePath}")
}

/**
 * Un istante per fase, scelto dalle effemeridi vere di Firenze e non dall'orologio: cosi' il sole
 * e la luna stanno dove starebbero davvero. La notte si cerca su qualche giorno finche' non se ne
 * trova una con la luna su e ben illuminata: una tavola notturna senza luna non dice niente.
 */
private fun instants(): List<Pair<DayPhase, Long>> {
  val base = LocalDateTime.of(2026, 6, 15, 12, 0).atZone(zone).toInstant().toEpochMilli()
  fun at(hour: Int, minute: Int) = LocalDateTime.of(2026, 6, 15, hour, minute).atZone(zone).toInstant().toEpochMilli()
  val night = (0 until 30 * 24).map { base + it * 3_600_000L }.firstOrNull { millis ->
    SolarEphemeris.phaseAt(millis, LAT, LON) == DayPhase.NIGHT &&
      Celestial.at(millis, LAT, LON, zone)?.let { it.kind == CelestialBody.Kind.MOON && it.illuminated in 0.55f..0.9f } == true
  } ?: at(23, 30)
  return listOf(
    DayPhase.DAWN to at(5, 50),
    DayPhase.DAY to at(12, 0),
    DayPhase.DUSK to at(20, 50),
    DayPhase.NIGHT to night,
  ).map { (phase, millis) ->
    // La fase dichiarata deve combaciare con quella calcolata, o la tavola mentirebbe.
    val computed = SolarEphemeris.phaseAt(millis, LAT, LON)
    computed to millis
  }
}

private fun render(
  width: Int,
  height: Int,
  phase: DayPhase,
  kind: WeatherKind?,
  nowMillis: Long,
  cover: Double?,
  t: Float,
  scrim: Boolean,
): BufferedImage {
  val bitmap = ImageBitmap(width, height)
  CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(bitmap), Size(width.toFloat(), height.toFloat())) {
    SkyFrame.draw(
      scope = this,
      state = SkyState(phase, kind, cover, LAT, LON),
      nowMillis = nowMillis,
      quality = SceneQuality.FULL,
      zone = zone,
      scrim = scrim,
      t = t,
    )
  }
  return bitmap.toAwtImage()
}

private fun contactSheet(
  title: String,
  columns: Int,
  cellW: Int,
  cellH: Int,
  cells: List<Pair<String, BufferedImage>>,
): BufferedImage {
  val gap = 8
  val label = 18
  val rows = (cells.size + columns - 1) / columns
  val sheet = BufferedImage(columns * (cellW + gap) + gap, 28 + rows * (cellH + label + gap) + gap, BufferedImage.TYPE_INT_RGB)
  val g = sheet.createGraphics()
  g.color = Color(24, 24, 28)
  g.fillRect(0, 0, sheet.width, sheet.height)
  g.color = Color.WHITE
  g.font = Font(Font.SANS_SERIF, Font.BOLD, 14)
  g.drawString(title, gap, 20)
  g.font = Font(Font.SANS_SERIF, Font.PLAIN, 12)
  cells.forEachIndexed { index, (name, image) ->
    val col = index % columns
    val row = index / columns
    val x = gap + col * (cellW + gap)
    val y = 28 + gap + row * (cellH + label + gap)
    g.drawImage(image, x, y, null)
    g.color = Color(200, 200, 205)
    g.drawString(name, x, y + cellH + 14)
  }
  g.dispose()
  return sheet
}

private fun save(image: BufferedImage, file: File) {
  ImageIO.write(image, "png", file)
  println("  ${file.name}  ${image.width}x${image.height}")
}
