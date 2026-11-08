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
import androidx.glance.appwidget.lazy.LazyColumn
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
import androidx.glance.text.TextStyle
import androidx.glance.background
import androidx.compose.ui.unit.sp
import dev.pampa.fluidweather.strings.windowLabelRes
import kotlinx.coroutines.flow.first
import androidx.glance.unit.ColorProvider
import dev.pampa.fluidweather.core.model.*
import dev.pampa.fluidweather.strings.WidgetSharedRainFormatter
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
   * `Responsive` e non `Exact`: le tre taglie si calcolano una volta sola e viaggiano insieme, cosi'
   * il launcher sceglie al ridimensionamento senza risvegliare il processo dell'app. Su API 26-30,
   * dove `Exact` rifarebbe la composizione a ogni trascinamento, la differenza si vede.
   */
  override val sizeMode = SizeMode.Responsive(setOf(Small, Medium, Large))

  override suspend fun provideGlance(context: Context, id: GlanceId) {
    val runtime = context.appWidgetRuntime()
    val read = widgetLocalRead { readAppWidgetBrief(runtime.cachedWeatherBriefs) { resolvePlace(runtime) } }
    val readToken = runtime.cachedWeatherBriefs.cachedInputUpdates.value
    val place = read?.first
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
        zone = zone.zoneId,
      )
    }

    provideContent {
      val tier = tierFor(LocalSize.current)
      val (model, sky) = scenes.getValue(tier)
      Body(model, tier, UnitFormatter(LocalContext.current.resources, units), target, sky)
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
    tier: AppWidgetTier,
    units: UnitFormatter,
    target: Intent,
    sky: android.graphics.Bitmap,
  ) {
    val resources = LocalContext.current.resources
    val onSky = ColorProvider(ComposeColor.White)
    val dim = ColorProvider(ComposeColor.White.copy(alpha = 0.75f))

    // A collection keeps the mandatory source disclosures reachable on short launcher cells.
    // Sky raster dimensions and the three responsive tiers stay unchanged.
    LazyColumn(modifier = GlanceModifier.fillMaxSize()
      .background(ImageProvider(sky), contentScale = ContentScale.Crop)) {
      item {
        Column(
          modifier = GlanceModifier
            .fillMaxWidth()
            .padding(if (tier == AppWidgetTier.SMALL) 12.dp else 14.dp)
            .clickable(actionStartActivity(target)),
          verticalAlignment = Alignment.Vertical.Top,
        ) {
          if (model.isEmpty) {
            Text(
              text = resources.getString(R.string.appwidget_empty),
              style = TextStyle(color = onSky, fontSize = 13.sp, fontWeight = FontWeight.Medium),
            )
            return@Column
          }

          Row(verticalAlignment = Alignment.Vertical.CenterVertically, modifier = GlanceModifier.fillMaxWidth()) {
            Image(
              provider = ImageProvider(model.iconRes),
              contentDescription = null,
              colorFilter = androidx.glance.ColorFilter.tint(onSky),
              modifier = GlanceModifier.size(if (tier == AppWidgetTier.SMALL) 22.dp else 26.dp),
            )
            Spacer(GlanceModifier.width(8.dp))
            Text(
              text = model.temperatureC?.let { units.degrees(it) } ?: "—",
              style = TextStyle(
                color = onSky,
                fontSize = if (tier == AppWidgetTier.SMALL) 30.sp else 34.sp,
                fontWeight = FontWeight.Medium,
              ),
            )
          }
          Text(text = model.placeName ?: resources.getString(R.string.place_my_location),
            style = TextStyle(color = dim, fontSize = 13.sp))
          Text(text = WidgetSharedRainFormatter.headline(resources, model.headline),
            style = TextStyle(color = onSky, fontSize = 11.sp),
            modifier = GlanceModifier.clickable(actionStartActivity(widgetIntent(target, HomeWidget.NOWCAST))))
          Text(text = WidgetSharedRainFormatter.provenance(resources, model.brief, model.assessedAtMillis,
            model.zone, model.zoneFallback, resources.configuration.locales[0]),
            style = TextStyle(color = dim, fontSize = 11.sp))

          if (tier != AppWidgetTier.LARGE) TodayLine(model, units, onSky, dim, leadingSpace = true)

          if (tier != AppWidgetTier.SMALL && model.verdictLevel != null) {
            Spacer(GlanceModifier.height(6.dp))
            VerdictLine(model, resources, onSky, target)
          }

          if (tier == AppWidgetTier.LARGE && model.hours.isNotEmpty()) {
            Spacer(GlanceModifier.height(10.dp))
            HourStrip(model, units, onSky, dim, target)
          }

          model.dataAtMillis?.let {
            Text(text = resources.getString(R.string.widget_shared_bundle,
              WidgetSharedRainFormatter.absoluteTime(it, model.zone, resources.configuration.locales[0])),
              style = TextStyle(color = dim, fontSize = 11.sp))
          }
        }
      }
    }
  }

  /**
   * "29° / 22°   ○ 40%": la riga in fondo al widget compatto.
   *
   * La massima e la minima sono quelle del **giorno locale**, le stesse della testata dell'app —
   * stessa funzione, non una copia, perche' due superfici che dicono due massime diverse nello
   * stesso momento sono peggio di una che non la dice.
   *
   * La goccia non e' decorazione: senza, un "40%" accanto a due temperature si legge come
   * umidita' o come copertura, e sarebbe una percentuale che non vuol dire niente.
   */
  @Composable
  private fun TodayLine(
    model: AppWidgetModel,
    units: UnitFormatter,
    onSky: ColorProvider,
    dim: ColorProvider,
    /** Vero quando va a capo (taglia piccola): serve un respiro sopra, in riga no. */
    leadingSpace: Boolean,
  ) {
    val range = if (model.maxC != null && model.minC != null) {
      "${units.degrees(model.maxC)} / ${units.degrees(model.minC)}"
    } else {
      null
    }
    // Sotto il 5% non e' un'informazione, e' rumore: la tessera dell'app usa la stessa soglia.
    val rain = model.rainProbabilityPercent?.takeIf { it >= RAIN_WORTH_SAYING_PERCENT }
    if (range == null && rain == null) return

    if (leadingSpace) Spacer(GlanceModifier.height(4.dp))
    Text(text = LocalContext.current.getString(R.string.widget_shared_forecast), style = TextStyle(color = dim, fontSize = 11.sp))
    Row(verticalAlignment = Alignment.Vertical.CenterVertically) {
      if (range != null) {
        Text(text = range, style = TextStyle(color = onSky, fontSize = 12.sp), maxLines = 1)
      }
      if (rain != null) {
        if (range != null) Spacer(GlanceModifier.width(8.dp))
        Image(
          provider = ImageProvider(dev.pampa.fluidweather.feature.appwidget.R.drawable.ic_drop),
          contentDescription = null,
          colorFilter = androidx.glance.ColorFilter.tint(dim),
          modifier = GlanceModifier.size(11.dp),
        )
        Spacer(GlanceModifier.width(3.dp))
        Text(text = "$rain%", style = TextStyle(color = dim, fontSize = 12.sp), maxLines = 1)
      }
    }
  }

  @Composable
  private fun VerdictLine(
    model: AppWidgetModel,
    resources: android.content.res.Resources,
    onSky: ColorProvider,
    target: Intent,
  ) {
    val window = model.verdictWindow?.let { resources.getString(windowLabelRes(it)) }.orEmpty()
    Text(text = resources.getString(R.string.widget_shared_history,
      model.verdictAtMillis?.let { WidgetSharedRainFormatter.absoluteTime(it, model.zone, resources.configuration.locales[0]) }.orEmpty(),
      model.zone.id, model.verdictProbabilityPercent?.toString() ?: "?", window),
      style = TextStyle(color = onSky, fontSize = 11.sp),
      modifier = GlanceModifier.clickable(actionStartActivity(widgetIntent(target, HomeWidget.NOWCAST))))
    Text(text = resources.getString(R.string.widget_shared_history_location),
      style = TextStyle(color = onSky, fontSize = 11.sp))
  }

  @Composable
  private fun HourStrip(
    model: AppWidgetModel,
    units: UnitFormatter,
    onSky: ColorProvider,
    dim: ColorProvider,
    target: Intent,
  ) {
    // Una riga sola con quattro colonne, non quattro righe: alla taglia grande il budget
    // dell'engine lascia spazio a due righe in tutto, e la testata ne ha gia' presa una.
    Text(text = LocalContext.current.getString(R.string.widget_shared_forecast), style = TextStyle(color = dim, fontSize = 11.sp))
    Row(
      modifier = GlanceModifier.fillMaxWidth().clickable(actionStartActivity(widgetIntent(target, HomeWidget.HOURLY))),
      horizontalAlignment = Alignment.Horizontal.CenterHorizontally,
    ) {
      model.hours.forEach { hour ->
        Column(
          modifier = GlanceModifier.defaultWeight(),
          horizontalAlignment = Alignment.Horizontal.CenterHorizontally,
        ) {
          Text(text = TimeFormats.time(hour.timestampMillis, model.zone), style = TextStyle(color = dim, fontSize = 11.sp))
          Image(
            provider = ImageProvider(hour.iconRes),
            contentDescription = null,
            colorFilter = androidx.glance.ColorFilter.tint(onSky),
            modifier = GlanceModifier.size(18.dp),
          )
          Text(
            text = hour.temperatureC?.let { units.degrees(it) } ?: "—",
            style = TextStyle(color = onSky, fontSize = 12.sp),
          )
        }
      }
    }
  }

  private companion object {
    /** Sotto il 5% la probabilita' non e' un'informazione: la tessera dell'app usa la stessa. */
    const val RAIN_WORTH_SAYING_PERCENT = 5

    val Small = DpSize(110.dp, 110.dp)
    val Medium = DpSize(250.dp, 110.dp)
    val Large = DpSize(250.dp, 200.dp)

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
