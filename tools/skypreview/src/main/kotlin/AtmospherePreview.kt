import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
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
import java.time.ZoneId
import java.time.LocalDateTime
import javax.imageio.ImageIO

/** Synthetic visual inputs, never a claim about the weather on the fixed fixture date. */
object AtmospherePreview {
  data class Case(val name: String, val kind: WeatherKind, val air: SkyAtmosphere?)

  val cases = listOf(
    Case("clear", WeatherKind.CLEAR, SkyAtmosphere()),
    Case("distant broken", WeatherKind.PARTLY_CLOUDY, SkyAtmosphere(0.28f, 0.42f, 0.25f, 0.2f, 0.12f, 0.5f)),
    Case("filtered sun", WeatherKind.CLOUDY, SkyAtmosphere(0.3f, 0.78f, 0.2f, 0.9f, 0.18f, 0.65f)),
    Case("pale drizzle", WeatherKind.DRIZZLE, SkyAtmosphere(0.38f, 0.96f, 0.24f, 0.95f, 0.48f, 0.6f, 0.14f)),
    Case("dry dark front", WeatherKind.CLOUDY, SkyAtmosphere(0.95f, 0.87f, 0.93f, 0.8f, 0.28f, 1.6f, 0f, 0.7f)),
    Case("dense rain", WeatherKind.HEAVY_RAIN, SkyAtmosphere(0.95f, 1f, 0.9f, 0.95f, 0.65f, 1.8f, 0.85f, 0.8f)),
    Case("snow", WeatherKind.SNOW, SkyAtmosphere(0.5f, 0.96f, 0.2f, 1f, 0.6f, 0.8f, 0.5f)),
    Case("fog", WeatherKind.FOG, SkyAtmosphere(0.1f, 0.9f, 0.05f, 1f, 1f, 0.25f)),
    Case("legacy null", WeatherKind.PARTLY_CLOUDY, null),
  )

  @JvmStatic fun main(args: Array<String>) {
    val out = File(args.firstOrNull() ?: "out/atmosphere").apply { mkdirs() }
    val zone = ZoneId.of("Europe/Rome")
    val w = 240
    val h = 400
    val label = 32
    for (phase in DayPhase.entries) {
      val (hour, minute) = when (phase) {
        DayPhase.DAWN -> 5 to 50
        DayPhase.DAY -> 12 to 0
        DayPhase.DUSK -> 20 to 50
        DayPhase.NIGHT -> 23 to 30
      }
      val at = LocalDateTime.of(2026, 6, 15, hour, minute).atZone(zone).toInstant().toEpochMilli()
      val sheet = BufferedImage(w * cases.size, (h + label) * SceneQuality.entries.size, BufferedImage.TYPE_INT_RGB)
      val g = sheet.createGraphics()
      g.font = Font(Font.SANS_SERIF, Font.PLAIN, 11)
      for ((row, quality) in SceneQuality.entries.withIndex()) {
        for ((col, case) in cases.withIndex()) {
          val state = SkyState(phase, case.kind, null, 43.77, 11.26, atmosphere = case.air)
          val sky = SkyPalette.sky(state)
          val painter = SkyScenePainter(state, quality, 2026166)
          val body = Celestial.at(at, 43.77, 11.26, zone)
          fun frame(t: Float): BufferedImage {
            val bitmap = ImageBitmap(w, h)
            CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(bitmap), Size(w.toFloat(), h.toFloat())) {
              painter.draw(this, sky, body, t)
            }
            return bitmap.toAwtImage()
          }
          val first = frame(0f)
          check(!painter.flashing(1f) && !painter.flashing(11f))
          if (quality == SceneQuality.STATIC) {
            val later = frame(12f)
            check(first.getRGB(0, 0, w, h, null, 0, w).contentEquals(later.getRGB(0, 0, w, h, null, 0, w))) {
              "STATIC changed: ${case.name} $phase"
            }
          }
          g.drawImage(first, col * w, row * (h + label), null)
          g.color = Color(24, 24, 28)
          g.fillRect(col * w, row * (h + label) + h, w, label)
          g.color = Color.WHITE
          g.drawString("${case.name} $quality", col * w + 4, row * (h + label) + h + 18)
          if (phase == DayPhase.DAY && quality == SceneQuality.FULL && case.name == "pale drizzle") {
            for (t in listOf(0f, 4f, 8f, 12f)) ImageIO.write(frame(t), "png", File(out, "drizzle-${t.toInt()}.png"))
          }
        }
      }
      g.dispose()
      ImageIO.write(sheet, "png", File(out, "atmosphere-${phase.name.lowercase()}.png"))
    }
    // Public palette boundary must remain finite even for invalid external values.
    val invalid = SkyPalette.sky(SkyState(DayPhase.DAY, WeatherKind.CLOUDY, null,
      atmosphere = SkyAtmosphere(Float.NaN, 3f, -2f, Float.POSITIVE_INFINITY, 0.5f, Float.NaN)))
    check(invalid.gloom.isFinite() && invalid.starAlpha.isFinite())
    println("Synthetic painter sheets and STATIC equality assertions completed: ${out.absolutePath}")
  }
}
