package dev.pampa.fluidweather.widgetqa

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.LocaleList
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.ExperimentalGlanceRemoteViewsApi
import androidx.glance.appwidget.GlanceRemoteViews
import dev.pampa.fluidweather.MainActivity
import dev.pampa.fluidweather.core.model.*
import dev.pampa.fluidweather.core.weather.WeatherSnapshot
import dev.pampa.fluidweather.feature.appwidget.*
import dev.pampa.fluidweather.strings.UnitFormatter
import java.time.ZoneId
import java.util.Locale

/** All input is synthetic and in memory; never reads AppGraph, preferences or cached weather. */
data class WidgetHostCase(
  val width: Int = 250,
  val height: Int = 200,
  val language: String = "en",
  val fontScale: Float = 1f,
  val variant: String = "current",
) {
  init {
    require((width to height) in sizes)
    require(language in listOf("en", "it"))
    require(fontScale in listOf(1f, 1.5f))
    require(variant in variants)
  }
  val size get() = DpSize(width.dp, height.dp)
  val key get() = "${width}x${height}_${language}_${fontScale}_$variant"
  companion object {
    val sizes = listOf(110 to 110, 250 to 110, 250 to 200, 250 to 340, 350 to 180, 350 to 350)
    val variants = listOf("empty", "stale", "current", "missing")
    const val NOW = 1_783_080_000_000L
    val zone: ZoneId = ZoneId.of("Europe/Rome")
  }
}

object WeatherWidgetHostRenderer {
  fun context(base: Context, case: WidgetHostCase): Context =
    base.createConfigurationContext(Configuration(base.resources.configuration).apply {
      setLocales(LocaleList(Locale.forLanguageTag(case.language)))
      fontScale = case.fontScale
    })

  fun model(case: WidgetHostCase): AppWidgetModel {
    val hours = (0 until 168).map { h ->
      FusedHour(WidgetHostCase.NOW + h * 3_600_000L,
        if (case.variant == "missing") emptyMap() else mapOf(
          FusionVariables.TEMPERATURE to FusedValue(21.0 + h % 5, emptyList()),
          FusionVariables.CLOUD_COVER to FusedValue(75.0, emptyList())), WeatherKind.CLOUDY)
    }
    val fetched = WidgetHostCase.NOW - if (case.variant == "stale") 9 * 3_600_000L else 5 * 60_000L
    val snapshot = if (case.variant == "empty") null else WeatherSnapshot(
      placeKey = "widget-host-fixture", latitude = 43.8, longitude = 11.2,
      fetchedAtMillis = fetched, fetches = emptyList(), fused = FusedForecast(hours, emptyMap()),
      context = null, zoneId = WidgetHostCase.zone.id)
    val evidence = WeatherEvidence("widget-host-model", WeatherEvidenceType.MODEL,
      WeatherInterval(WidgetHostCase.NOW - 600_000L, WidgetHostCase.NOW + 600_000L), fetched, sourceTimestampMillis = fetched)
    val brief = if (case.variant in listOf("empty", "missing")) null else WeatherBrief(
      LocalPrecipitationAssessment(PrecipitationState.UNAVAILABLE, WidgetHostCase.NOW),
      CurrentWeatherSummary(if (case.variant == "current") WeatherKind.PARTLY_CLOUDY else WeatherKind.CLOUDY,
        21.0, listOf(evidence)))
    return AppWidgetModelBuilder.of(snapshot,
      "San Casciano in Val di Pesa — Montefiridolfi / Sant'Andrea in Percussina",
      null, WidgetHostCase.NOW, tierFor(case.size), WidgetHostCase.zone, brief, false)
  }

  @OptIn(ExperimentalGlanceRemoteViewsApi::class)
  suspend fun render(context: Context, case: WidgetHostCase, model: AppWidgetModel): android.widget.RemoteViews {
    val moon = ImageBitmap.imageResource(context.resources, dev.pampa.fluidweather.core.ui.R.drawable.moon_nearside)
    val bitmap = SkyScene.of(tierFor(case.size), model.skyKind, model.cloudCover,
      model.latitude, model.longitude, WidgetHostCase.NOW, WidgetHostCase.zone, moon, model.atmosphere)
    return GlanceRemoteViews().compose(context = context, size = case.size) {
      WeatherWidgetContent(model, UnitFormatter(context.resources, UnitPreferences.METRIC,
        Locale.forLanguageTag(case.language)), WidgetHostCase.NOW,
        Intent(context, MainActivity::class.java), bitmap)
    }.remoteViews
  }
}
