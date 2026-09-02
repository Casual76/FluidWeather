package dev.pampa.fluidweather.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import dev.pampa.fluidweather.core.model.UnitPreferences
import dev.pampa.fluidweather.strings.UnitFormatter

/**
 * Le unita' dell'utente, disponibili a ogni schermata senza passarle a mano. Le fornisce la
 * radice dell'app leggendo lo store; il default metrico esiste solo perche' una preview o un
 * test non abbiano bisogno dello store.
 */
val LocalUnits = compositionLocalOf { UnitPreferences.METRIC }

/** Il formattatore per le unita' correnti, ricostruito solo quando cambiano unita' o lingua. */
@Composable
fun rememberUnitFormatter(): UnitFormatter {
  val context = LocalContext.current
  val units = LocalUnits.current
  val configuration = LocalConfiguration.current
  return remember(units, configuration) { UnitFormatter(context.resources, units) }
}
