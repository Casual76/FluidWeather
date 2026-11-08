import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.inset
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import dev.pampa.fluidweather.core.model.DayPhase
import dev.pampa.fluidweather.core.model.WeatherKind
import dev.pampa.fluidweather.core.ui.*
import java.awt.Color
import java.awt.Font
import java.awt.image.BufferedImage
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId
import javax.imageio.ImageIO

/** Synthetic fixed seed/date. Runs only when explicitly requested with --cloud-volume. */
object CloudVolumePreview {
  fun run(out: File) {
    out.mkdirs()
    val cases = listOf(
      "broken" to SkyAtmosphere(0.4f, 0.42f, 0.25f, 0.25f),
      "filtered" to SkyAtmosphere(0.3f, 0.78f, 0.2f, 0.9f),
      "overcast" to SkyAtmosphere(0.7f, 1f, 0.5f, 0.8f),
      "dark dry" to SkyAtmosphere(0.95f, 0.94f, 0.9f, 0.7f, windSpeedFactor = 1.8f),
      "pale" to SkyAtmosphere(0.45f, 0.96f, 0.2f, 0.95f),
      "legacy cloudy" to null,
    )
    val zone = ZoneId.of("Europe/Rome")
    for (phase in listOf(DayPhase.DAY, DayPhase.DUSK, DayPhase.NIGHT)) {
      val at = LocalDateTime.of(2026, 6, 15, when (phase) {
        DayPhase.DAY -> 12
        DayPhase.DUSK -> 20
        else -> 23
      }, 30).atZone(zone).toInstant().toEpochMilli()
      for ((w, h) in listOf(360 to 640, 220 to 97)) {
        val sheet = BufferedImage(w * cases.size, (h + 24) * 3, BufferedImage.TYPE_INT_RGB)
        val g = sheet.createGraphics()
        g.font = Font(Font.SANS_SERIF, Font.PLAIN, 11)
        for ((row, quality) in SceneQuality.entries.withIndex()) for ((col, case) in cases.withIndex()) {
          val state = SkyState(phase, WeatherKind.CLOUDY, null, 43.77, 11.26, atmosphere = case.second)
          val sky = SkyPalette.sky(state)
          val body = Celestial.at(at, 43.77, 11.26, zone)
          val painter = SkyScenePainter(state, quality, 2026166)
          fun frame(t: Float): BufferedImage {
            val bitmap = ImageBitmap(w, h)
            CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(bitmap), Size(w.toFloat(), h.toFloat())) {
              painter.draw(this, sky, body, t)
            }
            return bitmap.toAwtImage()
          }
          val first = frame(0f)
          val later = frame(12f)
          val pixels = first.getRGB(0, 0, w, h, null, 0, w)
          val laterPixels = later.getRGB(0, 0, w, h, null, 0, w)
          if (quality == SceneQuality.STATIC) check(pixels.contentEquals(laterPixels))
          else check(!pixels.contentEquals(laterPixels)) { "Cloud motion absent: ${case.first}" }
          check(!painter.flashing(0f) && !painter.flashing(12f))
          // Isolate the cloud pass to assert that morphology never escapes the upper half.
          val cloudOnly = ImageBitmap(w, h)
          val layer = CloudVolumeLayer.forState(state, 2026166)
          CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(cloudOnly), Size(w.toFloat(), h.toFloat())) {
            inset(0f, 0f, 0f, size.height * 0.48f) { layer?.draw(this, 12f, sky, body, quality) }
          }
          val lower = cloudOnly.toAwtImage()
          for (y in (h * 0.53f).toInt() until h) for (x in 0 until w) {
            check(lower.getRGB(x, y) ushr 24 == 0) { "Cloud escaped ceiling" }
          }
          g.drawImage(first, col * w, row * (h + 24), null)
          g.color = Color(24, 24, 28)
          g.fillRect(col * w, row * (h + 24) + h, w, 24)
          g.color = Color.WHITE
          g.drawString("${case.first} $quality", col * w + 3, row * (h + 24) + h + 16)
          if (w == 360 && quality == SceneQuality.FULL) {
            ImageIO.write(first, "png", File(out, "${phase.name.lowercase()}-${case.first.replace(' ', '-')}-0.png"))
            ImageIO.write(later, "png", File(out, "${phase.name.lowercase()}-${case.first.replace(' ', '-')}-12.png"))
          }
        }
        g.dispose()
        ImageIO.write(sheet, "png", File(out, "cloud-volume-${phase.name.lowercase()}-${w}x$h.png"))
      }
    }
    println("Cloud volume synthetic sheets, motion, STATIC, lower-half and no-lightning probes completed: ${out.absolutePath}")
  }
}
