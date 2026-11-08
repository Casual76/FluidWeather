package dev.pampa.fluidweather.widgetqa

import android.app.Activity
import android.os.Bundle
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.*
import java.util.concurrent.CountDownLatch
import kotlin.math.roundToInt

/** Debug-only explicit Activity. The framed child is an inflated production RemoteViews tree. */
class WeatherWidgetHostActivity : Activity() {
  val ready = CountDownLatch(1)
  var failure: Throwable? = null
    private set
  var widgetView: View? = null
    private set
  lateinit var case: WidgetHostCase
    private set
  lateinit var widgetContext: android.content.Context
    private set
  lateinit var model: dev.pampa.fluidweather.feature.appwidget.AppWidgetModel
    private set
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

  override fun onCreate(state: Bundle?) {
    super.onCreate(state)
    case = WidgetHostCase(intent.getIntExtra("width", 250), intent.getIntExtra("height", 200),
      intent.getStringExtra("language") ?: "en", intent.getFloatExtra("fontScale", 1f),
      intent.getStringExtra("variant") ?: "current")
    widgetContext = WeatherWidgetHostRenderer.context(this, case)
    model = WeatherWidgetHostRenderer.model(case)
    val root = LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL
      gravity = Gravity.CENTER
      setBackgroundColor(Color.DKGRAY)
    }
    val status = TextView(this).apply { text = "Rendering ${case.key}"; setTextColor(Color.WHITE) }
    root.addView(status)
    val responsive = intent.getBooleanExtra("responsive", false)
    require(!responsive || android.os.Build.VERSION.SDK_INT >= 31)
    val native = if (responsive) android.appwidget.AppWidgetHostView(widgetContext).apply {
      val provider = android.appwidget.AppWidgetManager.getInstance(widgetContext).installedProviders.first {
        it.provider.packageName == this@WeatherWidgetHostActivity.packageName &&
          it.provider.className.endsWith("FluidWeatherAppWidgetReceiver")
      }
      // View-only host: no app-widget IDs allocated/bound, no existing widget changed.
      setAppWidget(0, provider)
    } else null
    val frame: FrameLayout = native ?: FrameLayout(widgetContext)
    val density = widgetContext.resources.displayMetrics.density
    val contentWidth = if (responsive) intent.getFloatExtra("hostWidth", 198.58824f) else case.width.toFloat()
    val contentHeight = if (responsive) intent.getFloatExtra("hostHeight", 198.58824f) else case.height.toFloat()
    root.addView(frame, LinearLayout.LayoutParams((contentWidth * density).roundToInt() + frame.paddingLeft + frame.paddingRight,
      (contentHeight * density).roundToInt() + frame.paddingTop + frame.paddingBottom))
    setContentView(root)
    scope.launch {
      try {
        val views = if (responsive) WeatherWidgetHostRenderer.responsive(widgetContext, case)
          else WeatherWidgetHostRenderer.render(widgetContext, case, model)
        val child = if (native != null) {
          native.updateAppWidget(views)
          native
        } else views.apply(widgetContext, frame).also { frame.addView(it, FrameLayout.LayoutParams(-1, -1)) }
        widgetView = child
        status.text = case.key
        frame.viewTreeObserver.addOnGlobalLayoutListener(object : android.view.ViewTreeObserver.OnGlobalLayoutListener {
          override fun onGlobalLayout() {
            if (child.width > 0 && child.height > 0) {
              frame.viewTreeObserver.removeOnGlobalLayoutListener(this)
              ready.countDown()
            }
          }
        })
      } catch (error: Throwable) {
        failure = error
        status.text = "FAILED ${case.key}: $error"
        ready.countDown()
      }
    }
  }
  override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
