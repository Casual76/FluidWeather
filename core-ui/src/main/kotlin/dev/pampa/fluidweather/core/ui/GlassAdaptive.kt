package dev.pampa.fluidweather.core.ui

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import dev.pampa.fluidweather.core.model.GlassLevel

/**
 * La misura del dispositivo per la modalita' automatica del vetro. Euristica di primo avvio,
 * dichiarata e migliorabile (fase 17 la raffinera' col frame time): la lente vera esiste solo
 * dai RuntimeShader (API 33), il blur da RenderEffect (API 31), sotto e' opaco comunque.
 */
fun deviceGlassTier(context: Context): GlassLevel {
  val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
  return when {
    Build.VERSION.SDK_INT >= 33 && !activityManager.isLowRamDevice -> GlassLevel.FULL
    Build.VERSION.SDK_INT >= 31 -> GlassLevel.REDUCED
    else -> GlassLevel.OFF
  }
}

fun isPowerSaveActive(context: Context): Boolean =
  (context.getSystemService(Context.POWER_SERVICE) as PowerManager).isPowerSaveMode

/** Il livello del vetro deciso -> quanta scena animare. */
fun GlassLevel.toSceneQuality(): SceneQuality = when (this) {
  GlassLevel.FULL -> SceneQuality.FULL
  GlassLevel.REDUCED -> SceneQuality.REDUCED
  GlassLevel.OFF -> SceneQuality.STATIC
}

/** Il livello del vetro deciso -> il tetto di qualita' per FluidGlassQuality dell'engine. */
fun GlassLevel.toGlassCeiling(): Float = when (this) {
  GlassLevel.FULL -> 1f
  GlassLevel.REDUCED -> 0.5f
  GlassLevel.OFF -> 0f
}
