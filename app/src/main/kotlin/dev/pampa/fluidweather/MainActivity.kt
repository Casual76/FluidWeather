package dev.pampa.fluidweather

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dev.pampa.fluidweather.navigation.FluidWeatherNavHost
import dev.pampa.fluidweather.theme.FluidWeatherTheme

class MainActivity : ComponentActivity() {

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    setContent {
      FluidWeatherTheme {
        FluidWeatherNavHost()
      }
    }
  }
}
