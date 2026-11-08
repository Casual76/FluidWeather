package dev.pampa.fluidweather

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.pampa.fluidweather.strings.TimeFormats
import dev.pampa.fluidweather.widgetqa.WeatherWidgetHostActivity
import dev.pampa.fluidweather.widgetqa.WidgetHostCase
import java.io.File
import java.util.Locale
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

/** Native RemoteViews map selection at the tablet's observed198.588dp CONTENT size. */
class WeatherWidgetResponsiveSelectionTest {
  @Test(timeout = 60_000) fun androidChoosesForecastSquareForActualTabletSize_inEnglishAndItalianLargeType() {
    org.junit.Assume.assumeTrue(android.os.Build.VERSION.SDK_INT >= 31)
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val context = instrumentation.targetContext
    val cases = listOf(NativeCase("en", 1f, 198.58824f, 198.58824f, 4), NativeCase("it", 1.5f, 198.58824f, 198.58824f, 4),
      NativeCase("en", 1f, 336f, 180f, 6), NativeCase("it", 1.5f, 336f, 180f, 6))
    for ((language, scale, hostWidth, hostHeight, hourCount) in cases) {
      val intent = Intent(context, WeatherWidgetHostActivity::class.java)
        .putExtra("width", 180).putExtra("height", 180)
        .putExtra("language", language).putExtra("fontScale", scale)
        .putExtra("variant", "stale").putExtra("responsive", true)
        .putExtra("hostWidth", hostWidth).putExtra("hostHeight", hostHeight)
      val scenario = ActivityScenario.launch<WeatherWidgetHostActivity>(intent)
      try {
        lateinit var activity: WeatherWidgetHostActivity
        scenario.onActivity { activity = it }
        assertTrue("Native host layout deadline", activity.ready.await(15, TimeUnit.SECONDS))
        instrumentation.waitForIdleSync()
        scenario.onActivity { host ->
          host.failure?.let { throw AssertionError("Native responsive host failed", it) }
          val root = requireNotNull(host.widgetView)
          assertTrue(root is android.appwidget.AppWidgetHostView)
          val labels = texts(root).map { it.text.toString() }
          val expectedHours = host.model.hours.take(hourCount).map {
            TimeFormats.hour(it.timestampMillis, host.model.zone, Locale.forLanguageTag(language))
          }
          assertEquals(hourCount, expectedHours.size)
          expectedHours.forEach { hour ->
            assertEquals("Native map chose tiny fallback instead of forecast square: $labels", 1, labels.count { it == hour })
          }
          val valid = host.widgetContext.getString(dev.pampa.fluidweather.strings.R.string.widget_quality_valid_time,
            TimeFormats.time(requireNotNull(host.model.forecastAtMillis), host.model.zone, Locale.forLanguageTag(language)))
          assertTrue("Original forecast validity missing", valid in labels)
          assertTrue("Original download age missing", host.widgetContext.getString(
            dev.pampa.fluidweather.strings.R.string.widget_quality_age_hours, 9L) in labels)
          val density = host.widgetContext.resources.displayMetrics.density
          assertEquals(hostWidth, (root.width - root.paddingLeft - root.paddingRight) / density, 1f)
          assertEquals(hostHeight, (root.height - root.paddingTop - root.paddingBottom) / density, 1f)
          val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
          root.draw(Canvas(bitmap))
          val directory = File(context.getExternalFilesDir(null), "widget-native-qa").apply { mkdirs() }
          File(directory, "${hostWidth.toInt()}x${hostHeight.toInt()}_${language}_${scale}_stale.png").outputStream().use {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
          }
          bitmap.recycle()
        }
      } finally { scenario.close() }
    }
  }
  private data class NativeCase(val language: String, val scale: Float, val width: Float, val height: Float, val hours: Int)
  private fun texts(view: View): List<TextView> = when (view) {
    is TextView -> listOf(view)
    is ViewGroup -> (0 until view.childCount).flatMap { texts(view.getChildAt(it)) }
    else -> emptyList()
  }
}
