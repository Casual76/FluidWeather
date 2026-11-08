package dev.pampa.fluidweather.feature.appwidget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.provideContent
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
import kotlinx.coroutines.flow.first
import androidx.glance.unit.ColorProvider
import dev.pampa.fluidweather.core.model.*
import dev.pampa.fluidweather.core.ui.AppDeepLink
import dev.pampa.fluidweather.core.ui.AppDestination
import dev.pampa.fluidweather.core.ui.HomeWidget
import dev.pampa.fluidweather.core.weather.WeatherSnapshot
import dev.pampa.fluidweather.strings.R
import dev.pampa.fluidweather.strings.TimeFormats
import dev.pampa.fluidweather.strings.UnitFormatter
import androidx.compose.ui.graphics.Color as ComposeColor

/**
 * Il widget sulla schermata Home di Android.
 *
 * Non va in rete e non chiede il GPS: disegna quello che il ciclo ha gia' scritto su disco. E' cio'
 * che limita l'aggiornamento agli input locali condivisi con l'app.
 *
 * Lo sfondo e' la **scena dipinta della home**, con il meteo di adesso: il sole o la luna (con la
 * sua fase vera) dove stanno davvero, le nuvole secondo la copertura, la pioggia e la neve. Glance
 * non ha una tela, quindi la scena si rasterizza in una bitmap piccola (vedi [SkyScene]) — ma e'
 * la stessa funzione che dipinge il cielo dell'app, non una copia. Sopra si scrive in bianco: sul
 * cielo notturno i colori del tema chiaro sarebbero illeggibili, e questo widget deve somigliare
 * all'app, non al launcher.
 */
class FluidWeatherAppWidget : GlanceAppWidget() {

  /**
   * `Responsive` e non `Exact`: i layout si calcolano una volta sola e viaggiano insieme, cosi'
   * il launcher sceglie al ridimensionamento senza risvegliare il processo dell'app. Su API 26-30,
   * dove `Exact` rifarebbe la composizione a ogni trascinamento, la differenza si vede.
   */
  override val sizeMode = SizeMode.Responsive(setOf(Small, Medium, Large, Expanded))

  override suspend fun provideGlance(context: Context, id: GlanceId) {
    val runtime = context.appWidgetRuntime()
    val fallback = resolvePlace(runtime)
    val read = widgetLocalRead { readAppWidgetBrief(runtime.cachedWeatherBriefs) { resolvePlace(runtime) } }
    val readToken = runtime.cachedWeatherBriefs.cachedInputUpdates.value
    val place = read?.first ?: fallback?.takeIf { resolvePlace(runtime) == it }
    val brief = read?.second
    val verdict = widgetLocalRead { runtime.nowcastHistory.since(0L).lastOrNull() }
      ?.takeIf { place?.place?.isGps == true && it.variant == NowcastVerdictRecord.VARIANT_INDEPENDENT }
    val units = runtime.unitPreferences.value
    // FLAG_ACTIVITY_SINGLE_TOP e' la cintura di sicurezza: la cinghia e' il launchMode
    // `singleTask` dichiarato nel manifest, ma un intent che dice esplicitamente "non impilarti"
    // costa niente e resta giusto se un domani quella riga del manifest cambia.
    val target = Intent(Intent.ACTION_VIEW)
      .setComponent(runtime.mainActivity)
      .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)

    // Recheck after history IO too; no mixed snapshot/location on a suspended read.
    val stable = place != null && resolvePlace(runtime) == place &&
      readToken == runtime.cachedWeatherBriefs.cachedInputUpdates.value
    val now = System.currentTimeMillis()
    val snapshot = place?.snapshot.takeIf { stable }
    val sharedBrief = brief.takeIf { stable }
    val zone = WeatherZones.resolve(place?.place?.zoneId, snapshot?.zoneId ?: snapshot?.context?.zoneId,
      java.time.ZoneId.systemDefault())
    // La fotografia della luna, una volta per aggiornamento: senza, il cielo disegna quella
    // procedurale, che e' meno luna ma non lascia il widget vuoto.
    val moon = runCatching {
      ImageBitmap.imageResource(context.resources, dev.pampa.fluidweather.core.ui.R.drawable.moon_nearside)
    }.getOrNull()
    // I modelli e i cieli si costruiscono QUI, una volta per taglia, non dentro `provideContent`.
    //
    // Quattro layout responsive condividono tre bitmap (Large ed Expanded usano la stessa scena).
    // Con `SizeMode.Responsive` la composable gira una volta per ogni taglia dichiarata: quello
    // che nasce li' dentro nasce tre volte. Con il vecchio gradiente da 8 KB non si notava; con
    // una scena vera sarebbero tre bitmap invece di tre riferimenti alla stessa, e il budget di
    // `RemoteViews` (circa un megabyte e mezzo per aggiornamento) non lo regge.
    val scenes = AppWidgetTier.entries.associateWith { tier ->
      val model = AppWidgetModelBuilder.of(
        snapshot = snapshot,
        placeName = place?.place?.name?.takeIf { stable && it.isNotBlank() },
        verdict = verdict.takeIf { stable },
        nowMillis = now,
        tier = tier,
        zone = zone.zoneId,
        brief = sharedBrief,
        zoneFallback = zone.source == WeatherZoneSource.DEVICE_FALLBACK,
      )
      model to SkyScene.of(
        tier = tier,
        kind = model.skyKind,
        cloudCoverPercent = model.cloudCover,
        latitude = model.latitude,
        longitude = model.longitude,
        nowMillis = now,
        moon = moon,
        atmosphere = model.atmosphere,
        zone = zone.zoneId,
      )
    }

    provideContent {
      val tier = tierFor(LocalSize.current)
      val (model, sky) = scenes.getValue(tier)
      Body(model, UnitFormatter(LocalContext.current.resources, units), now, target, sky)
    }
  }

  /**
   * Dove sei, e cosa si sa di li'.
   *
   * Si parte dall'**istantanea**, non dalla posizione: il ciclo l'ha gia' scritta con dentro le
   * proprie coordinate, e un fix GPS costerebbe fino a quindici secondi mentre il launcher aspetta.
   * Senza permesso o senza istantanea del GPS si ripiega sulla prima localita' salvata, che e' la
   * stessa regola del ciclo in background.
   */
  private suspend fun resolvePlace(runtime: AppWidgetRuntime): AppWidgetPlace? {
    widgetLocalRead { runtime.snapshotStore.read(WeatherSnapshot.GPS_KEY) }
      ?.takeIf { widgetSnapshotMatches(Place.gps(), it) }?.let { snapshot ->
        return AppWidgetPlace(Place.gps().copy(latitude = snapshot.latitude, longitude = snapshot.longitude,
          zoneId = snapshot.zoneId ?: snapshot.context?.zoneId), snapshot)
      }
    val saved = widgetLocalRead { runtime.savedLocations.places.first().firstOrNull { !it.isGps } } ?: return null
    val snapshot = widgetLocalRead { runtime.snapshotStore.read(WeatherSnapshot.keyFor(saved.id)) }
      ?.takeIf { widgetSnapshotMatches(saved, it) }
    return AppWidgetPlace(saved, snapshot)
  }

  @Composable
  private fun Body(
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

  private companion object {
    val Small = DpSize(110.dp, 110.dp)
    val Medium = DpSize(250.dp, 110.dp)
    val Large = DpSize(250.dp, 200.dp)
    val Expanded = DpSize(250.dp, 340.dp)

    /**
     * Bersagli distinti per **Uri**, non per extra.
     *
     * L'uguaglianza dei `PendingIntent` ignora gli extra: due destinazioni che differiscono solo
     * per un extra collassano in una e il sistema consegna sempre la prima registrata. Con dati
     * diversi invece sono due intent diversi davvero.
     *
     * L'Uri lo scrive [AppDeepLink], che e' anche chi lo rilegge in `MainActivity`: prima il
     * widget si inventava il proprio dialetto e l'app ne conosceva due parole.
     */
    fun widgetIntent(base: Intent, widget: HomeWidget) =
      Intent(base).setData(AppDeepLink.uri(AppDestination.Widget(widget)))

  }
}

/** Il ricevitore che il sistema istanzia: l'unico pezzo dichiarato nel manifest. */
class FluidWeatherAppWidgetReceiver : GlanceAppWidgetReceiver() {
  override val glanceAppWidget: GlanceAppWidget = FluidWeatherAppWidget()
}
