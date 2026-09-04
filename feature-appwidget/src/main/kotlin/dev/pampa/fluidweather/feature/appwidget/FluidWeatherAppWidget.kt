package dev.pampa.fluidweather.feature.appwidget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
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
import androidx.glance.text.TextStyle
import androidx.glance.background
import androidx.compose.ui.unit.sp
import dev.pampa.fluidweather.nowcast.verdict.AlertLevel
import dev.pampa.fluidweather.strings.windowLabelRes
import kotlinx.coroutines.flow.first
import androidx.glance.unit.ColorProvider
import dev.antigravity.fluidengine.foundation.EngineSettings
import dev.pampa.fluidweather.core.model.DataFreshness
import dev.pampa.fluidweather.core.weather.WeatherSnapshot
import dev.pampa.fluidweather.strings.R
import dev.pampa.fluidweather.strings.TimeFormats
import dev.pampa.fluidweather.strings.UnitFormatter
import dev.pampa.fluidweather.strings.dataAgeLabel
import androidx.compose.ui.graphics.Color as ComposeColor

/**
 * Il widget sulla schermata Home di Android.
 *
 * Non va in rete e non chiede il GPS: disegna quello che il ciclo ha gia' scritto su disco. E' cio'
 * che lo rende gratis da aggiornare, e garantisce che non possa mai contraddire l'app.
 *
 * Lo sfondo e' il **cielo dipinto della home**, con il meteo di adesso: Glance non ha una tela,
 * quindi il gradiente diventa una bitmap piccola stirata (vedi [SkyBitmap]). Sopra si scrive in
 * bianco con la struttura del kit dell'engine — sul cielo notturno i colori del tema chiaro
 * sarebbero illeggibili, e questo widget deve somigliare all'app, non al launcher.
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
    val place = resolvePlace(runtime)
    val verdict = runCatching { runtime.nowcastHistory.since(0L).lastOrNull() }.getOrNull()
    val units = runtime.unitPreferences.value
    val target = Intent(Intent.ACTION_VIEW).setComponent(runtime.mainActivity)

    provideContent {
      val now = System.currentTimeMillis()
      val model = AppWidgetModelBuilder.of(
        snapshot = place?.second,
        placeName = place?.first,
        verdict = verdict,
        nowMillis = now,
        tier = tierFor(LocalSize.current),
      )
      Body(model, tierFor(LocalSize.current), UnitFormatter(LocalContext.current.resources, units), now, target)
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
  private suspend fun resolvePlace(runtime: AppWidgetRuntime): Pair<String?, WeatherSnapshot>? {
    runCatching { runtime.snapshotStore.read(WeatherSnapshot.GPS_KEY) }.getOrNull()
      ?.let { return null to it }
    val saved = runCatching { runtime.savedLocations.places.first().firstOrNull { !it.isGps } }.getOrNull() ?: return null
    val snapshot = runCatching { runtime.snapshotStore.read(WeatherSnapshot.keyFor(saved.id)) }.getOrNull() ?: return null
    return saved.name to snapshot
  }

  @Composable
  private fun Body(
    model: AppWidgetModel,
    tier: AppWidgetTier,
    units: UnitFormatter,
    nowMillis: Long,
    target: Intent,
  ) {
    val resources = LocalContext.current.resources
    val sky = ImageProvider(
      SkyBitmap.of(model.kind, model.cloudCover, model.latitude, model.longitude, nowMillis),
    )
    val onSky = ColorProvider(ComposeColor.White)
    val dim = ColorProvider(ComposeColor.White.copy(alpha = 0.75f))

    Column(
      modifier = GlanceModifier
        .fillMaxSize()
        .background(sky)
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
      // Il nome del posto, e accanto o sotto massima/minima e pioggia.
      //
      // La taglia MEDIA e' larga e BASSA (sotto i 150 dp): li' una riga in piu' verrebbe tagliata
      // dal launcher a meta', senza avvisare. Ma di larghezza ne ha, quindi i tre numeri stanno
      // sulla stessa riga del posto e non costano un pixel di altezza. La PICCOLA e' l'opposto —
      // stretta ma con spazio sotto — e li' vanno a capo.
      if (tier == AppWidgetTier.MEDIUM) {
        Row(verticalAlignment = Alignment.Vertical.CenterVertically, modifier = GlanceModifier.fillMaxWidth()) {
          Text(
            text = model.placeName ?: resources.getString(R.string.place_my_location),
            style = TextStyle(color = dim, fontSize = 13.sp),
            maxLines = 1,
            modifier = GlanceModifier.defaultWeight(),
          )
          TodayLine(model, units, onSky, dim, leadingSpace = false)
        }
      } else {
        Text(
          text = model.placeName ?: resources.getString(R.string.place_my_location),
          style = TextStyle(color = dim, fontSize = 13.sp),
          maxLines = 1,
        )
        if (tier == AppWidgetTier.SMALL) TodayLine(model, units, onSky, dim, leadingSpace = true)
      }

      if (tier != AppWidgetTier.SMALL && model.verdictLevel != null) {
        Spacer(GlanceModifier.height(6.dp))
        VerdictLine(model, resources, onSky, target)
      }

      if (tier == AppWidgetTier.LARGE && model.hours.isNotEmpty()) {
        Spacer(GlanceModifier.height(10.dp))
        HourStrip(model, units, onSky, dim, target)
      }

      // L'eta' dei dati, con la stessa frase della testata della home: un widget che mostra una
      // temperatura di nove ore fa senza dirlo e' peggio di un widget vuoto.
      dataAgeLabel(resources, model.dataAtMillis, nowMillis)?.let {
        Spacer(GlanceModifier.height(6.dp))
        Text(text = it, style = TextStyle(color = dim, fontSize = 11.sp), maxLines = 1)
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
    // Titolo e valore, non sottotitolo: alle taglie piccola e media il budget dell'engine mette
    // `compact`, e in compatto le righe del kit buttano via il sottotitolo. Scriverlo li' avrebbe
    // voluto dire non vederlo mai.
    val window = model.verdictWindow?.let { resources.getString(windowLabelRes(it)) }.orEmpty()
    val percent = model.verdictProbabilityPercent
    Row(
      modifier = GlanceModifier.fillMaxWidth().clickable(actionStartActivity(nowcastIntent(target))),
      verticalAlignment = Alignment.Vertical.CenterVertically,
    ) {
      Text(
        text = resources.getString(levelLabel(model.verdictLevel)),
        style = TextStyle(color = onSky, fontSize = 13.sp, fontWeight = FontWeight.Medium),
        maxLines = 1,
        modifier = GlanceModifier.defaultWeight(),
      )
      if (percent != null) {
        Text(
          text = "$percent%  $window",
          style = TextStyle(color = onSky, fontSize = 13.sp),
          maxLines = 1,
        )
      }
    }
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
    Row(
      modifier = GlanceModifier.fillMaxWidth().clickable(actionStartActivity(hourlyIntent(target))),
      horizontalAlignment = Alignment.Horizontal.CenterHorizontally,
    ) {
      model.hours.forEach { hour ->
        Column(
          modifier = GlanceModifier.defaultWeight(),
          horizontalAlignment = Alignment.Horizontal.CenterHorizontally,
        ) {
          Text(text = TimeFormats.time(hour.timestampMillis), style = TextStyle(color = dim, fontSize = 11.sp))
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
     */
    fun nowcastIntent(base: Intent) = Intent(base).setData("fluidweather://widget/nowcast".toUri())

    fun hourlyIntent(base: Intent) = Intent(base).setData("fluidweather://widget/hourly".toUri())

    fun levelLabel(level: AlertLevel?) = when (level) {
      AlertLevel.ALLERTA -> R.string.level_alert
      AlertLevel.SORVEGLIANZA -> R.string.level_watch
      else -> R.string.level_quiet
    }
  }
}

/** Il ricevitore che il sistema istanzia: l'unico pezzo dichiarato nel manifest. */
class FluidWeatherAppWidgetReceiver : GlanceAppWidgetReceiver() {
  override val glanceAppWidget: GlanceAppWidget = FluidWeatherAppWidget()
}
