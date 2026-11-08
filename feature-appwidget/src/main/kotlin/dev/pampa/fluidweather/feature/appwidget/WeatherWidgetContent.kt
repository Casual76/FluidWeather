package dev.pampa.fluidweather.feature.appwidget

import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.background
import androidx.compose.ui.unit.sp
import dev.pampa.fluidweather.strings.labelRes
import dev.antigravity.fluidengine.widget.engineWidgetTextStyle
import dev.antigravity.fluidengine.widget.EngineWidgetShape
import androidx.glance.appwidget.cornerRadius
import androidx.glance.unit.ColorProvider
import dev.pampa.fluidweather.core.ui.AppDeepLink
import dev.pampa.fluidweather.core.ui.AppDestination
import dev.pampa.fluidweather.core.ui.HomeWidget
import dev.pampa.fluidweather.strings.R
import dev.pampa.fluidweather.strings.TimeFormats
import dev.pampa.fluidweather.strings.UnitFormatter
import androidx.compose.ui.graphics.Color as ComposeColor

/** Production Glance content shared by the launcher and the debug RemoteViews host. */
@Composable
fun WeatherWidgetContent(
  model: AppWidgetModel,
  units: UnitFormatter,
  nowMillis: Long,
  target: Intent,
  sky: android.graphics.Bitmap,
) {
  val resources = LocalContext.current.resources
  val density = WidgetDensity.of(LocalSize.current.width.value, LocalSize.current.height.value)
  val ink = ColorProvider(ComposeColor.White)
  val secondary = ColorProvider(ComposeColor.White.copy(alpha = 0.85f))
  val place = model.placeName ?: resources.getString(R.string.place_my_location)
  val condition = model.kind?.labelRes()?.let(resources::getString) ?: "\u2014"
  val age = widgetAgeMinutes(model.dataAtMillis, nowMillis)
  val ageText = when {
    age == null -> resources.getString(R.string.widget_quality_age_unknown)
    age < 60 -> resources.getString(R.string.widget_quality_age_minutes, age)
    age < 1440 -> resources.getString(R.string.widget_quality_age_hours, age / 60)
    else -> resources.getString(R.string.widget_quality_age_days, age / 1440)
  }
  Column(
    modifier = GlanceModifier.fillMaxSize()
      .background(ImageProvider(sky), contentScale = ContentScale.Crop)
      .cornerRadius(EngineWidgetShape.Container)
      .padding(density.padding.dp)
      .clickable(actionStartActivity(target)),
  ) {
    if (model.isEmpty) {
      Label(place, ink, 13, FontWeight.Medium)
      Label("\u2014", ink, 32)
      Label(resources.getString(R.string.widget_quality_empty), secondary, 11)
      return@Column
    }
    if (density.compact) {
      Label(place, ink, 13, FontWeight.Medium)
      Label(model.temperatureC?.takeIf { it.isFinite() }?.let { units.degrees(it) } ?: "\u2014", ink, 32)
      // At the minimum size stale data matters more than repeating the condition icon.
      Label(if (age == null || age >= 90) ageText else condition, secondary, 11,
        modifier = GlanceModifier.clickable(actionStartActivity(widgetIntent(target, HomeWidget.NOWCAST))))
      RangeLine(model, units, ink, 11)
    } else {
      Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.Vertical.CenterVertically) {
        Column(GlanceModifier.defaultWeight()) {
          Label(place, ink, 15, FontWeight.Medium)
          Label(model.temperatureC?.takeIf { it.isFinite() }?.let { units.degrees(it) } ?: "\u2014", ink, 44)
        }
        Column(GlanceModifier.defaultWeight(), horizontalAlignment = Alignment.Horizontal.End) {
          WeatherIcon(model.iconRes, ink, 22)
          Label(condition, ink, 12,
            modifier = GlanceModifier.clickable(actionStartActivity(widgetIntent(target, HomeWidget.NOWCAST))))
          RangeLine(model, units, secondary, 11)
        }
      }
      if (density.hourCount > 0 && model.hours.isNotEmpty()) {
        Spacer(GlanceModifier.height(8.dp))
        HourStrip(model, units, ink, secondary, target, density.hourCount)
      }
      if (density.dayCount > 0 && model.days.isNotEmpty()) {
        Spacer(GlanceModifier.height(10.dp))
        Label(resources.getString(R.string.widget_quality_days), secondary, 11)
        model.days.take(density.dayCount).forEach { day ->
          Row(
            GlanceModifier.fillMaxWidth().height(30.dp)
              .clickable(actionStartActivity(widgetIntent(target, HomeWidget.DAILY))),
            verticalAlignment = Alignment.Vertical.CenterVertically,
          ) {
            Label(day.date.format(java.time.format.DateTimeFormatter.ofPattern("EEE d", resources.configuration.locales[0])),
              ink, 12, modifier = GlanceModifier.defaultWeight())
            WeatherIcon(day.iconRes, ink, 18)
            Spacer(GlanceModifier.width(12.dp))
            Label(rangeText(day.minC, day.maxC, units), ink, 12)
          }
        }
      }
      // A short age only: full source/timestamps stay in app details.
      if (density.showFooter) {
        Spacer(GlanceModifier.defaultWeight())
        Label(ageText, secondary, 11)
      }
    }
  }
}

@Composable
private fun Label(
  text: String, color: ColorProvider, size: Int,
  weight: FontWeight = FontWeight.Normal, modifier: GlanceModifier = GlanceModifier,
) = Text(text, modifier = modifier,
  style = engineWidgetTextStyle(color = color, size = size.sp, weight = weight), maxLines = 1)

@Composable
private fun WeatherIcon(icon: Int, color: ColorProvider, size: Int) = Image(
  provider = ImageProvider(icon), contentDescription = null,
  colorFilter = androidx.glance.ColorFilter.tint(color), modifier = GlanceModifier.size(size.dp),
)

@Composable
private fun RangeLine(model: AppWidgetModel, units: UnitFormatter, ink: ColorProvider, size: Int) {
  Label(rangeText(model.todayMinC, model.todayMaxC, units), ink, size)
}

private fun rangeText(min: Double?, max: Double?, units: UnitFormatter): String =
  "\u2191${max?.takeIf { it.isFinite() }?.let { units.degrees(it) } ?: "\u2014"}  \u2193${min?.takeIf { it.isFinite() }?.let { units.degrees(it) } ?: "\u2014"}"

@Composable
private fun HourStrip(
  model: AppWidgetModel, units: UnitFormatter, ink: ColorProvider, secondary: ColorProvider,
  target: Intent, count: Int,
) {
  Row(
    GlanceModifier.fillMaxWidth().clickable(actionStartActivity(widgetIntent(target, HomeWidget.HOURLY))),
  ) {
    model.hours.take(count).forEach { hour ->
      Column(GlanceModifier.defaultWeight(), horizontalAlignment = Alignment.Horizontal.CenterHorizontally) {
        Label(TimeFormats.time(hour.timestampMillis, model.zone), secondary, 11)
        Spacer(GlanceModifier.height(4.dp))
        WeatherIcon(hour.iconRes, ink, 20)
        Spacer(GlanceModifier.height(4.dp))
        Label(hour.temperatureC?.takeIf { it.isFinite() }?.let { units.degrees(it) } ?: "\u2014", ink, 13, FontWeight.Medium)
      }
    }
  }
}

private fun widgetIntent(base: Intent, widget: HomeWidget) =
  Intent(base).setData(AppDeepLink.uri(AppDestination.Widget(widget)))
