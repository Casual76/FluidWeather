package dev.pampa.fluidweather

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.ImageView
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.pampa.fluidweather.widgetqa.*
import dev.pampa.fluidweather.strings.R
import dev.pampa.fluidweather.strings.UnitFormatter
import dev.pampa.fluidweather.strings.labelRes
import dev.pampa.fluidweather.core.model.UnitPreferences
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.io.File
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/** Real Glance -> RemoteViews -> framework Views, at the device's actual density/font renderer. */
@RunWith(Parameterized::class)
class WeatherWidgetRemoteViewsHostTest(private val case: WidgetHostCase) {
  companion object {
    @JvmStatic @Parameterized.Parameters(name = "{0}")
    fun cases(): List<Array<Any>> = WidgetHostCase.sizes.flatMap { (w, h) ->
      listOf("en", "it").flatMap { language -> listOf(1f, 1.5f).flatMap { scale ->
        WidgetHostCase.variants.map { arrayOf<Any>(WidgetHostCase(w, h, language, scale, it)) }
      } }
    }
  }

  @Test(timeout = 60_000)
  fun productionRemoteViewsHaveVisibleBoundedTextAndExpectedHierarchy() {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val intent = Intent(instrumentation.targetContext, WeatherWidgetHostActivity::class.java)
      .putExtra("width", case.width).putExtra("height", case.height)
      .putExtra("language", case.language).putExtra("fontScale", case.fontScale)
      .putExtra("variant", case.variant).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    ActivityScenario.launch<WeatherWidgetHostActivity>(intent).use { scenario ->
      lateinit var activity: WeatherWidgetHostActivity
      scenario.onActivity { activity = it }
      assertTrue("Host never completed ${case.key}", activity.ready.await(40, TimeUnit.SECONDS))
      scenario.onActivity { host ->
        host.failure?.let { throw AssertionError("Glance composition/inflation failed ${case.key}", it) }
        val root = requireNotNull(host.widgetView)
        // Persist every case, including failures; instrumentation -e class selects this suite.
        val directory = File(host.getExternalFilesDir(null), "widget-host-qa").apply { mkdirs() }
        val capture = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(capture))
        File(directory, "${case.key}.png").outputStream().use { capture.compress(Bitmap.CompressFormat.PNG, 100, it) }
        // Bars are framework-painted content, not merely extrema strings in the model.
        val barRows = (0 until capture.height).filter { y ->
          (0 until capture.width).count { x -> capture.getPixel(x, y) == 0xFFF2D76C.toInt() } >= 2
        }
        val bands = barRows.filterIndexed { index, y -> index == 0 || y > barRows[index - 1] + 1 }
        assertEquals("Expected visible complete-day range bars", if (case.height >= 330 && case.variant in listOf("current", "stale")) 5 else 0, bands.size)
        capture.recycle()
        val density = host.widgetContext.resources.displayMetrics.density
        assertEquals((case.width * density).roundToInt(), root.width)
        assertEquals((case.height * density).roundToInt(), root.height)
        assertEquals(case.fontScale, host.widgetContext.resources.configuration.fontScale, 0f)
        assertEquals(case.language, host.widgetContext.resources.configuration.locales[0].language)
        val texts = descendants(root).filterIsInstance<TextView>().filter { it.text.isNotEmpty() }.toList()
        assertTrue("No framework TextViews: is this still a RemoteViews host?", texts.isNotEmpty())
        texts.filter { it.text.toString() != host.model.placeName }.forEach { text ->
          for (line in 0 until text.layout.lineCount) {
            assertEquals("Essential text was ellipsized on line $line: ${text.text}", 0, text.layout.getEllipsisCount(line))
          }
        }
        val condition = host.model.kind?.labelRes()?.let { host.widgetContext.getString(it) }
        val painted = texts.map { it to assertPaintedTextFits(root, it,
          case.width >= 180 && it.text.toString() == condition) }
        painted.forEachIndexed { index, (a, rectA) ->
          painted.drop(index + 1).forEach { (b, rectB) ->
            assertFalse("Painted text overlaps: ${a.text} / ${b.text}", Rect.intersects(rectA, rectB))
          }
        }
        assertHierarchy(host, root, texts)
      }
    }
  }

  private fun assertHierarchy(host: WeatherWidgetHostActivity, root: View, texts: List<TextView>) {
    val model = host.model
    if (case.variant == "current") assertFalse("Current fixture fell back to hourly forecast", model.usesHourlyForecast)
    if (case.variant == "stale") assertTrue("Stale fixture must use qualified forecast fallback", model.usesHourlyForecast)
    val place = texts.single { it.text.toString() == model.placeName }
    val units = UnitFormatter(host.widgetContext.resources, UnitPreferences.METRIC, Locale.forLanguageTag(case.language))
    val temperature = model.temperatureC?.let { units.degrees(it) } ?: "\u2014"
    val hero = requireNotNull(texts.filter { it.text.toString() == temperature }.maxByOrNull { it.textSize }) { "Missing hero temperature" }
    assertNotNull("Missing hero temperature", hero)
    assertTrue("Place must precede hero", bounds(root, place).top <= bounds(root, hero).top)
    assertTrue("Temperature must dominate place", hero.textSize > place.textSize)
    assertTrue("Hero must be largest text", texts.all { it.textSize <= hero.textSize })
    val emptyLabel = host.getString(dev.pampa.fluidweather.feature.appwidget.R.string.widget_reference_empty)
    if (case.variant == "empty") {
      assertEquals(3, texts.size)
      assertTrue(texts.any { it.text.toString() == host.widgetContext.getString(dev.pampa.fluidweather.feature.appwidget.R.string.widget_reference_empty) })
    } else {
      assertFalse(texts.any { it.text.toString() == emptyLabel })
      assertEquals("Unexpected extra/missing text rows", when {
        case.width == 110 -> if (case.variant == "stale") 4 else 3
        case.height == 110 -> 4
        case.height < 330 -> if (case.width >= 320) 17 else if (case.width < 220 && !model.usesHourlyForecast) 12 else 13
        else -> if (case.width >= 320) 32 else 28
      }, texts.size)
      if (model.usesHourlyForecast) {
        val valid = requireNotNull(model.forecastAtMillis)
        val label = host.widgetContext.getString(R.string.widget_quality_valid_time,
          dev.pampa.fluidweather.strings.TimeFormats.time(valid, model.zone, Locale.forLanguageTag(case.language)))
        assertEquals("Hourly fallback validity must remain visible", 1, texts.count { it.text.toString() == label })
      }
      // Each hourly column contributes one time and one temperature.
      val hourLabels = model.hours.map { dev.pampa.fluidweather.strings.TimeFormats.hour(it.timestampMillis, model.zone,
        Locale.forLanguageTag(case.language)) }
      val hours = texts.filter { it.text.toString() in hourLabels }
      val expectedHours = if (case.height < 170) 0 else if (case.width >= 320) 6 else 4
      assertEquals(expectedHours, hours.size)
      hours.forEach { assertTrue("Hours must follow hero", bounds(root, it).top >= bounds(root, hero).bottom) }
      val dayCount = if (case.height >= 330) 5 else 0
      if (dayCount > 0) {
        assertEquals("Fixture must populate all five days", 5, model.days.size)
        var previousBottom = hours.maxOf { bounds(root, it).bottom }
        model.days.take(dayCount).forEach { day ->
          val label = day.date.format(java.time.format.DateTimeFormatter.ofPattern("EEE", Locale.forLanguageTag(case.language)))
          val row = texts.single { it.text.toString() == label }
          assertTrue("Daily rows must follow hours in order", bounds(root, row).top >= previousBottom)
          previousBottom = bounds(root, row).bottom
        }
        if (case.variant != "missing") {
          assertTrue("Complete fixture needs actual daily ranges", model.days.all { it.minC != null && it.maxC != null })
        }
      }
      // Every hourly label has a real drawable centered below it, and columns share a baseline.
      if (hours.isNotEmpty()) {
        assertEquals("Hourly labels must align", 1, hours.map { bounds(root, it).top }.distinct().size)
        val icons = descendants(root).filterIsInstance<ImageView>().toList()
        hours.forEach { hour ->
          val cell = bounds(root, hour)
          assertTrue("Missing aligned hourly weather icon", icons.any {
            val icon = bounds(root, it)
            kotlin.math.abs(icon.centerX() - cell.centerX()) <= 2 &&
              icon.top >= cell.bottom && icon.bottom <= cell.bottom + (48 * host.widgetContext.resources.displayMetrics.density).roundToInt()
          })
        }
      }
      if (case.variant == "stale") {
        val age = host.widgetContext.getString(R.string.widget_quality_age_hours, 9L)
        assertTrue("Stale age absent", texts.any { it.text.toString() == age })
      }
    }
  }

  /** Rasterize Layout with spare space: scan actual glyph pixels, including ascent/descenders.
   * This admits the framework's intentional end ellipsis; it never ignores an overflowing view.
   */
  private fun assertPaintedTextFits(root: View, text: TextView, conditionCanWrap: Boolean): Rect {
    assertTrue("Text is hidden ${text.text}", text.isShown)
    val layout = requireNotNull(text.layout) { "No text layout for ${text.text}" }
    assertTrue("Unexpected wrapped row ${text.text}", layout.lineCount in 1..if (conditionCanWrap) 2 else 1)
    assertTrue("Zero-sized text ${text.text}", text.width > 0 && text.height > 0)
    val margin = (text.textSize * 3).roundToInt().coerceAtLeast(64)
    val bitmap = Bitmap.createBitmap(layout.width.coerceAtLeast(1) + margin * 2,
      layout.height.coerceAtLeast(1) + margin * 2, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    canvas.translate(margin.toFloat(), margin.toFloat())
    layout.draw(canvas)
    var left = bitmap.width; var top = bitmap.height; var right = -1; var bottom = -1
    for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
      if ((bitmap.getPixel(x, y) ushr 24) != 0) {
        left = minOf(left, x); right = maxOf(right, x); top = minOf(top, y); bottom = maxOf(bottom, y)
      }
    }
    bitmap.recycle()
    assertTrue("Text has no painted pixels ${text.text}", right >= left && bottom >= top)
    val originY = text.baseline - layout.getLineBaseline(0)
    val painted = Rect(left - margin + text.compoundPaddingLeft,
      top - margin + originY, right + 1 - margin + text.compoundPaddingLeft,
      bottom + 1 - margin + originY)
    assertTrue("Paint clipped by TextView ${text.text}: $painted in ${text.width}x${text.height}",
      Rect(0, 0, text.width, text.height).contains(painted))
    var child: View = text
    var ancestor = text.parent
    while (ancestor is ViewGroup) {
      painted.offset(child.left - child.scrollX, child.top - child.scrollY)
      if (ancestor.clipChildren || ancestor === root) {
        val clip = if (ancestor.clipToPadding) Rect(ancestor.paddingLeft, ancestor.paddingTop,
          ancestor.width - ancestor.paddingRight, ancestor.height - ancestor.paddingBottom)
        else Rect(0, 0, ancestor.width, ancestor.height)
        assertTrue("Ancestor clips painted text ${text.text}: $painted outside $clip", clip.contains(painted))
      }
      if (ancestor === root) break
      child = ancestor
      ancestor = ancestor.parent
    }
    return painted
  }

  private fun bounds(root: View, view: View): Rect = Rect(0, 0, view.width, view.height).also {
    (root as ViewGroup).offsetDescendantRectToMyCoords(view, it)
  }
  private fun descendants(view: View): Sequence<View> = sequence {
    yield(view)
    if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(descendants(view.getChildAt(i)))
  }
}
