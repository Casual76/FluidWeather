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
    val frame = FrameLayout(widgetContext)
    val density = widgetContext.resources.displayMetrics.density
    root.addView(frame, LinearLayout.LayoutParams((case.width * density).roundToInt(),
      (case.height * density).roundToInt()))
    setContentView(root)
    scope.launch {
      try {
        val views = WeatherWidgetHostRenderer.render(widgetContext, case, model)
        val child = views.apply(widgetContext, frame)
        frame.addView(child, FrameLayout.LayoutParams(-1, -1))
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
