package dev.pampa.fluidweather.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import dev.antigravity.fluidengine.ui.fluid.FluidLabels
import dev.pampa.fluidweather.strings.R

/**
 * Le parole che l'engine dice da se' (il nome della X di una pagina, l'occhio di un campo segreto,
 * il tasto indietro della barra, l'indice delle sezioni...), nella lingua dell'app. Quelle di serie
 * sono italiane: senza questo, un telefono in inglese le leggerebbe in italiano a TalkBack.
 * Si forniscono una volta alla radice (`FluidWeatherTheme`) e valgono per tutto l'albero.
 */
@Composable
fun rememberFluidWeatherLabels(): FluidLabels {
  val context = LocalContext.current
  // La lingua puo' cambiare mentre l'app e' viva: le risorse si rileggono con la configurazione.
  val configuration = context.resources.configuration
  return remember(configuration) {
    val res = context.resources
    FluidLabels(
      close = res.getString(R.string.fl_close),
      showSecret = res.getString(R.string.fl_show_secret),
      hideSecret = res.getString(R.string.fl_hide_secret),
      actions = res.getString(R.string.fl_actions),
      back = res.getString(R.string.fl_back),
      scrollToTop = res.getString(R.string.fl_scroll_to_top),
      notification = res.getString(R.string.fl_notification),
      notificationExpand = res.getString(R.string.fl_notification_expand),
      notificationDismiss = res.getString(R.string.fl_notification_dismiss),
      notificationClose = res.getString(R.string.fl_notification_close),
      sectionIndex = res.getString(R.string.fl_section_index),
      previousSection = res.getString(R.string.fl_previous_section),
      nextSection = res.getString(R.string.fl_next_section),
      sectionPosition = { label, position, total -> res.getString(R.string.fl_section_position, label, position, total) },
      hide = res.getString(R.string.fl_hide),
    )
  }
}
