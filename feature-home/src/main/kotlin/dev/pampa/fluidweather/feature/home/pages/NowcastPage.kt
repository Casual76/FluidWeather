package dev.pampa.fluidweather.feature.home.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.antigravity.fluidengine.ui.fluid.ContinuousCornerShape
import dev.antigravity.fluidengine.ui.fluid.FluidProgressBar
import dev.antigravity.fluidengine.ui.tutorial.fluidTutorialAnchor
import dev.pampa.fluidweather.core.ui.PageCharts
import dev.pampa.fluidweather.core.ui.TutorialScreen
import dev.pampa.fluidweather.core.ui.TutorialSlot
import dev.pampa.fluidweather.feature.home.HomeUiState
import dev.pampa.fluidweather.nowcast.tide.TideSource
import dev.pampa.fluidweather.nowcast.verdict.AlertLevel
import dev.pampa.fluidweather.nowcast.verdict.Factor
import dev.pampa.fluidweather.nowcast.verdict.WindowVerdict
import kotlin.math.abs
import dev.pampa.fluidweather.strings.R
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import dev.pampa.fluidweather.core.ui.rememberUnitFormatter
import dev.pampa.fluidweather.core.ui.stageText
import dev.pampa.fluidweather.strings.featureLabelRes
import dev.pampa.fluidweather.strings.windowLabelRes

/**
 * La pagina del nowcast: il livello, le tre finestre con la loro banda, i fattori finestra per
 * finestra, il segnale pulito da cui nasce tutto, e lo storico dei verdetti contro la pioggia
 * osservata — la pagella in miniatura, in attesa del Benchmark (fase 13).
 */
@Composable
internal fun NowcastPage(state: HomeUiState) {
  val verdict = state.verdict
  val units = rememberUnitFormatter()

  TutorialSlot(screen = TutorialScreen.WIDGET_NOWCAST)
  Box(Modifier.fluidTutorialAnchor("nowcast_readiness_card")) {
    PageSection(stringResource(R.string.common_now))
  }
  if (verdict == null) {
    PageNote(
      stringResource(R.string.nowcast_waiting_note),
    )
    val readiness = state.readiness
    if (readiness != null) {
      Spacer(Modifier.height(10.dp))
      Text(readiness.stageText(), style = MaterialTheme.typography.titleSmall, color = White)
      Spacer(Modifier.height(6.dp))
      FluidProgressBar(progress = { readiness.overallFraction }, color = PageBlue)
      Spacer(Modifier.height(6.dp))
    }
    val points = state.cleaning?.filtered?.size ?: 0
    if (points > 0) StatRow(stringResource(R.string.nowcast_clean_points), "$points", stringResource(R.string.nowcast_last_24h))
  } else {
    val (title, body, color) = when (verdict.level) {
      AlertLevel.QUIETE -> Triple(
        stringResource(R.string.level_quiet),
        stringResource(R.string.nowcast_quiet_desc),
        PageGreen,
      )
      AlertLevel.SORVEGLIANZA -> Triple(
        stringResource(R.string.level_watch),
        stringResource(R.string.nowcast_watch_desc),
        PageAmber,
      )
      AlertLevel.ALLERTA -> Triple(
        stringResource(R.string.level_alert),
        stringResource(R.string.nowcast_alert_desc),
        PageRed,
      )
    }
    Text(title, style = MaterialTheme.typography.headlineMedium, color = color)
    Text(body, style = MaterialTheme.typography.bodyMedium, color = Dim)

    PageSection(stringResource(R.string.nowcast_prob_title))
    verdict.windows.forEach { WindowRow(it) }
    PageNote(
      stringResource(R.string.nowcast_band_note),
    )

    Box(Modifier.fluidTutorialAnchor("nowcast_factors_card")) {
      PageSection(stringResource(R.string.nowcast_factors_title))
    }
    verdict.windows.forEach { window ->
      Text(
        windowLabel(window.window),
        style = MaterialTheme.typography.labelMedium,
        color = Faint,
        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
      )
      if (window.topFactors.isEmpty()) {
        Text(stringResource(R.string.nowcast_no_factors), style = MaterialTheme.typography.bodySmall, color = Faint)
      } else {
        val strongest = window.topFactors.maxOf { abs(it.contribution) }.coerceAtLeast(1e-9)
        window.topFactors.forEach { FactorRow(it, strongest) }
      }
    }
  }

  val explanation = state.nowcastExplanation
  if (explanation != null) {
    PageSection(stringResource(R.string.learning_title))
    explanation.verdict.windows.forEach { window ->
      val raw = explanation.rawVerdict.forWindow(window.window)
      val analogs = explanation.analogs[window.window]
      val recalibrated = window.window in explanation.recalibrated
      StatRow(
        windowLabel(window.window),
        "${(window.probability * 100).toInt()}%",
        buildString {
          append(stringResource(R.string.nowcast_model_prefix) + ((raw?.probability ?: 0.0) * 100).toInt() + "%")
          if (recalibrated) append(stringResource(R.string.nowcast_recalibrated))
          if (analogs != null) append(stringResource(R.string.nowcast_analogs, analogs.rained, analogs.neighbours))
          // Il quarto passaggio: cio' che si vede fuori. La pagina mostra tutti i passaggi, e
          // questo e' l'unico che non e' una statistica — quindi va detto per quello che e'.
          if (window.window in explanation.observed) append(stringResource(R.string.nowcast_observed))
        },
      )
    }
    explanation.observation?.takeIf { it.rainingNow }?.let { observation ->
      PageNote(stringResource(R.string.nowcast_seen_by, observation.source))
    }
    PageNote(
      if (explanation.recalibrated.isEmpty() && explanation.analogs.isEmpty()) {
        stringResource(R.string.learning_nothing_yet)
      } else {
        stringResource(R.string.learning_explain)
      },
    )
  }

  val cleaning = state.cleaning
  if (cleaning != null && cleaning.filtered.size >= 2) {
    PageSection(stringResource(R.string.nowcast_clean_signal))
    CurveWithLabels(
      values = units.pressureSeries(cleaning.filtered.map { it.levelHpa }),
      labels = timeLabels(cleaning.filtered.map { it.timestampMillis }),
      color = PageBlue,
      unit = " " + units.pressureSymbol(),
      decimals = units.pressureChartDecimals() + 1,
    )
    val latest = cleaning.latest
    if (latest != null) {
      StatRow(stringResource(R.string.pressure_level), units.pressure(latest.levelHpa, 1), units.pressureSigma(latest.levelSigmaHpa))
      StatRow(
        stringResource(R.string.pressure_trend),
        units.pressureRate(latest.trendHpaPerHour, 2),
        units.pressureSigma(latest.trendSigmaHpaPerHour, 2),
      )
    }
    val tide = cleaning.tide
    StatRow(
      stringResource(R.string.pressure_tide),
      tideSourceLabel(tide.source),
      "S1 ${units.pressure(tide.s1AmplitudeHpa, 1)} · S2 ${units.pressure(tide.s2AmplitudeHpa, 1)}",
    )
    if (tide.source != TideSource.NONE) {
      StatRow(stringResource(R.string.nowcast_tide_now), units.pressureDelta(tide.tideAtLatestHpa, 2), stringResource(R.string.nowcast_tide_subtracted))
    }
  }

  PageSection(stringResource(R.string.nowcast_history_title))
  val history = state.verdictHistory
  if (history.size >= 2) {
    CurveWithLabels(
      values = history.map { it.probability13 * 100 },
      labels = timeLabels(history.map { it.timestampMillis }),
      color = PageAmber,
      unit = "%",
    )
    Text(
      stringResource(R.string.nowcast_history_note),
      style = MaterialTheme.typography.bodySmall,
      color = Faint,
    )
    val alerts = history.count { it.level == AlertLevel.ALLERTA.name }
    val watches = history.count { it.level == AlertLevel.SORVEGLIANZA.name }
    StatRow(stringResource(R.string.nowcast_verdicts_recorded), "${history.size}", stringResource(R.string.nowcast_verdict_counts, alerts, watches))
  } else {
    PageNote(stringResource(R.string.nowcast_history_empty))
  }

  val observed = state.observedPrecipitation
  if (observed.isNotEmpty()) {
    PageSection(stringResource(R.string.nowcast_truth_title))
    PageCharts.Bars(
      values = observed.map { it.second },
      color = PageBlue,
      modifier = Modifier
        .fillMaxWidth()
        .height(60.dp),
    )
    Row(Modifier.fillMaxWidth(), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween) {
      Text(fmtTime(observed.first().first), style = MaterialTheme.typography.labelSmall, color = Faint)
      Text(fmtTime(observed.last().first), style = MaterialTheme.typography.labelSmall, color = Faint)
    }
    StatRow(stringResource(R.string.nowcast_rain_observed), units.precipitation(observed.sumOf { it.second }), pluralStringResource(R.plurals.nowcast_last_hours, observed.size, observed.size))
    PageNote(
      stringResource(R.string.nowcast_truth_note),
    )
  }
}

@Composable
private fun WindowRow(window: WindowVerdict) {
  Row(
    verticalAlignment = Alignment.CenterVertically,
    modifier = Modifier
      .fillMaxWidth()
      .padding(vertical = 8.dp),
  ) {
    Text(windowLabel(window.window), style = MaterialTheme.typography.bodyMedium, color = Dim, modifier = Modifier.width(96.dp))
    Text(
      "${(window.probability * 100).toInt()}%",
      style = MaterialTheme.typography.titleLarge,
      color = White,
      modifier = Modifier.width(64.dp),
    )
    Column(Modifier.weight(1f)) {
      PageCharts.ProbabilityBand(
        low = window.probabilityLow,
        high = window.probabilityHigh,
        value = window.probability,
        trackColor = White.copy(alpha = 0.12f),
        bandColor = PageBlue.copy(alpha = 0.45f),
        markerColor = PageBlue,
        modifier = Modifier
          .fillMaxWidth()
          .height(10.dp),
      )
      Text(
        stringResource(R.string.nowcast_band, (window.probabilityLow * 100).toInt(), (window.probabilityHigh * 100).toInt()),
        style = MaterialTheme.typography.labelSmall,
        color = Faint,
      )
    }
  }
}

@Composable
private fun FactorRow(factor: Factor, strongest: Double) {
  val positive = factor.contribution > 0
  val color = if (positive) PageBlue else PageAmber
  Row(
    verticalAlignment = Alignment.CenterVertically,
    modifier = Modifier
      .fillMaxWidth()
      .padding(vertical = 3.dp),
  ) {
    Text(if (positive) "▲" else "▼", color = color, modifier = Modifier.width(22.dp))
    Text(stringResource(featureLabelRes(factor.name)), style = MaterialTheme.typography.bodyMedium, color = White.copy(alpha = 0.85f), modifier = Modifier.weight(1f))
    Spacer(Modifier.width(8.dp))
    Box(
      Modifier
        .width((110 * (abs(factor.contribution) / strongest)).coerceAtLeast(6.0).dp)
        .height(6.dp)
        .background(color, ContinuousCornerShape(3.dp)),
    )
  }
}

@Composable
internal fun windowLabel(window: String): String = stringResource(windowLabelRes(window))

@Composable
internal fun tideSourceLabel(source: TideSource): String = when (source) {
  TideSource.NONE -> stringResource(R.string.nowcast_tide_not_subtracted)
  TideSource.CLIMATOLOGICAL -> stringResource(R.string.nowcast_climatological)
  else -> stringResource(R.string.nowcast_tide_local)
}
