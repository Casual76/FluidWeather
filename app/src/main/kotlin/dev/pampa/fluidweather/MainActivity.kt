package dev.pampa.fluidweather

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import dev.pampa.fluidweather.navigation.FluidWeatherNavHost
import dev.pampa.fluidweather.theme.FluidWeatherTheme

class MainActivity : ComponentActivity() {

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    val graph = (application as FluidWeatherApp).graph
    setContent {
      FluidWeatherTheme {
        ContinuousSamplingEffect(graph)
        FluidWeatherNavHost(graph)
      }
    }
  }
}

/**
 * Il toggle "continuo mentre l'app e' aperta", legato allo stato STARTED: in background il
 * monitor si sospende da solo, riaperta l'app riparte. Il monitor decide poi se fare qualcosa
 * guardando l'impostazione.
 */
@Composable
private fun ContinuousSamplingEffect(graph: AppGraph) {
  val lifecycle = LocalLifecycleOwner.current.lifecycle
  LaunchedEffect(lifecycle) {
    lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
      graph.continuousMonitor.run()
    }
  }
}
