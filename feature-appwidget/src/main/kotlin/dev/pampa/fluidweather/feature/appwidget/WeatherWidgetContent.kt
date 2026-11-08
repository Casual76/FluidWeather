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
import androidx.glance.layout.Box
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
import dev.pampa.fluidweather.feature.appwidget.R as WidgetR
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
  val enlarged = resources.configuration.fontScale > 1.15f
  val short = LocalSize.current.height.value < 150
  val placeSize = if (enlarged) 11 else if (density.compact) 13 else 17
  val heroSize = if (enlarged) { if (density.compact || short) 24 else 32 } else if (density.compact) 32 else if (LocalSize.current.height.value < 180) 36 else 44
  val detailSize = if (enlarged) 10 else 11
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
  val stale = age == null || age >= 90
  val validTime = if (model.usesHourlyForecast) model.forecastAtMillis?.let {
    resources.getString(R.string.widget_quality_valid_time,
      TimeFormats.time(it, model.zone, resources.configuration.locales[0]))
  } ?: resources.getString(R.string.widget_quality_valid_time_unknown) else null
  val compactFallback = density.compact && enlarged && stale && validTime != null
  val displayedHeroSize = if (compactFallback) 20 else heroSize
  Column(
    modifier = GlanceModifier.fillMaxSize()
      .background(ImageProvider(sky), contentScale = ContentScale.Crop)
      .cornerRadius(EngineWidgetShape.Container)
      .padding(horizontal = density.padding.dp, vertical = when {
        compactFallback -> (density.padding - 3).dp
        density.compact -> density.padding.dp
        LocalSize.current.height.value < 200 -> 6.dp
        LocalSize.current.height.value >= 330 -> 10.dp
        else -> density.padding.dp
      })
      .clickable(actionStartActivity(target)),
  ) {
    if (model.isEmpty) {
      Label(place, ink, placeSize, FontWeight.Medium)
      Label("\u2014", ink, if (enlarged) 24 else 32)
      Label(resources.getString(WidgetR.string.widget_reference_empty), secondary, detailSize)
      return@Column
    }
    if (density.compact) {
      Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.Vertical.CenterVertically) {
        Label(place, ink, placeSize, FontWeight.Medium, GlanceModifier.defaultWeight())
        WeatherIcon(model.iconRes, 14)
      }
      Label(model.temperatureC?.takeIf { it.isFinite() }?.let { units.degrees(it) } ?: "\u2014", ink, displayedHeroSize)
      // The icon carries conditions in the square cell; stale age takes precedence over range
      // when large type leaves room for only three lines.
      if (validTime != null) Label(validTime, secondary, detailSize)
      else if (!enlarged || !stale) RangeLine(model, units, ink, detailSize)
      if (stale) Label(ageText, secondary, detailSize)
    } else {
      Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.Vertical.CenterVertically) {
        Column(GlanceModifier.defaultWeight()) {
          Label(place, ink, placeSize, FontWeight.Medium)
          Label(model.temperatureC?.takeIf { it.isFinite() }?.let { units.degrees(it) } ?: "\u2014", ink, heroSize)
        }
        Column(GlanceModifier.defaultWeight(), horizontalAlignment = Alignment.Horizontal.End) {
          WeatherIcon(model.iconRes, if (enlarged) 18 else 22)
          if (LocalSize.current.width.value >= 220 || validTime != null)
          Label(validTime ?: if (short && stale) ageText else condition, ink, if (enlarged) 10 else 12,
            modifier = GlanceModifier.clickable(actionStartActivity(widgetIntent(target, HomeWidget.NOWCAST))), maxLines = 2)
          if (validTime != null && short && stale) Label(ageText, secondary, detailSize)
          else RangeLine(model, units, secondary, detailSize)
        }
      }
      if (density.hourCount > 0 && model.hours.isNotEmpty()) {
        Spacer(GlanceModifier.height(4.dp))
        Separator()
        Spacer(GlanceModifier.height(4.dp))
        HourStrip(model, units, ink, secondary, target, density.hourCount, enlarged)
      }
      if (density.dayCount > 0 && model.days.isNotEmpty()) {
        Column(GlanceModifier.fillMaxWidth()) {
          Spacer(GlanceModifier.height(4.dp))
          Separator()
          val days = model.days.take(density.dayCount)
          val domain = widgetRangeDomain(days)
          days.forEach { day ->
            Row(
              GlanceModifier.fillMaxWidth().height(28.dp)
                .clickable(actionStartActivity(widgetIntent(target, HomeWidget.DAILY))),
              verticalAlignment = Alignment.Vertical.CenterVertically,
            ) {
              Label(day.date.format(java.time.format.DateTimeFormatter.ofPattern("EEE", resources.configuration.locales[0])),
                ink, 11, FontWeight.Medium, GlanceModifier.width(if (enlarged) 38.dp else 32.dp))
              WeatherIcon(day.iconRes, 18)
              Spacer(GlanceModifier.width(4.dp))
              Label(day.minC?.takeIf(Double::isFinite)?.let(units::degrees) ?: "\u2014",
                secondary, 11, modifier = GlanceModifier.width(if (enlarged) 38.dp else 30.dp))
              RangeBar(day, domain, enlarged, GlanceModifier.defaultWeight())
              Spacer(GlanceModifier.width(6.dp))
              Label(day.maxC?.takeIf(Double::isFinite)?.let(units::degrees) ?: "\u2014",
                ink, 11, modifier = GlanceModifier.width(if (enlarged) 38.dp else 30.dp))
            }
          }
        }
      }
      // A short age only: full source/timestamps stay in app details.
      if (density.showFooter) {
        Spacer(GlanceModifier.defaultWeight())
        Label(ageText, if (stale) ink else secondary, detailSize, if (stale) FontWeight.Medium else FontWeight.Normal)
      }
    }
  }
}

@Composable
private fun Label(
  text: String, color: ColorProvider, size: Int,
  weight: FontWeight = FontWeight.Normal, modifier: GlanceModifier = GlanceModifier,
  maxLines: Int = 1,
) = Text(text, modifier = modifier,
  style = engineWidgetTextStyle(color = color, size = size.sp, weight = weight), maxLines = maxLines)

@Composable
private fun WeatherIcon(icon: Int, size: Int) = Image(
  provider = ImageProvider(icon), contentDescription = null,
  modifier = GlanceModifier.size(size.dp),
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
  target: Intent, count: Int, enlarged: Boolean,
) {
  val locale = LocalContext.current.resources.configuration.locales[0]
  Row(
    GlanceModifier.fillMaxWidth().clickable(actionStartActivity(widgetIntent(target, HomeWidget.HOURLY))),
  ) {
    model.hours.take(count).forEach { hour ->
      Column(GlanceModifier.defaultWeight(), horizontalAlignment = Alignment.Horizontal.CenterHorizontally) {
        Label(TimeFormats.hour(hour.timestampMillis, model.zone, locale), secondary, if (enlarged) 10 else 11)
        Spacer(GlanceModifier.height(4.dp))
        WeatherIcon(hour.iconRes, if (enlarged) 18 else 20)
        Spacer(GlanceModifier.height(4.dp))
        Label(hour.temperatureC?.takeIf { it.isFinite() }?.let { units.degrees(it) } ?: "\u2014", ink, if (enlarged) 11 else 13, FontWeight.Medium)
      }
    }
  }
}

private fun widgetIntent(base: Intent, widget: HomeWidget): Intent {
  val placeId = when (val destination = AppDeepLink.parse(base.data)) {
    is AppDestination.Home -> destination.placeId
    is AppDestination.Widget -> destination.placeId
    else -> null
  }
  return Intent(base).setData(AppDeepLink.uri(AppDestination.Widget(widget, placeId)))
}

@Composable
private fun Separator() = Box(GlanceModifier.fillMaxWidth().height(1.dp)
  .background(ComposeColor.White.copy(alpha = 0.22f))) {}

/** Vector-like Glance boxes: no per-size or per-day bitmap allocation. */
@Composable
private fun RangeBar(day: AppWidgetDay, domain: ClosedFloatingPointRange<Double>?, enlarged: Boolean, modifier: GlanceModifier) {
  val fractions = widgetRangeFractions(day, domain)
  val width = (LocalSize.current.width.value - 24 - (if (enlarged) 38 + 38 + 38 else 32 + 30 + 30) - 18 - 4 - 6).coerceAtLeast(2f)
  val start = fractions?.first ?: 0f
  val end = fractions?.second ?: 0f
  Box(modifier.height(5.dp).background(ImageProvider(WidgetR.drawable.widget_temperature_track))) {
    if (fractions != null) {
      Row(GlanceModifier.fillMaxWidth()) {
        val barWidth = (width * (end - start)).coerceAtLeast(2f).coerceAtMost(width)
        val offset = (width * start).coerceAtMost(width - barWidth)
        if (offset > 0f) Spacer(GlanceModifier.width(offset.dp))
        Box(GlanceModifier.width(barWidth.dp).height(5.dp)
          .background(ImageProvider(WidgetR.drawable.widget_temperature_range))) {}
      }
    }
  }
}
