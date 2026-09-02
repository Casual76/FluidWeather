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
import dev.pampa.fluidweather.core.ui.PageCharts
import dev.pampa.fluidweather.feature.home.HomeUiState
import dev.pampa.fluidweather.nowcast.tide.TideSource
import dev.pampa.fluidweather.nowcast.verdict.AlertLevel
import dev.pampa.fluidweather.nowcast.verdict.Factor
import dev.pampa.fluidweather.nowcast.verdict.WindowVerdict
import java.util.Locale
import kotlin.math.abs

/**
 * La pagina del nowcast: il livello, le tre finestre con la loro banda, i fattori finestra per
 * finestra, il segnale pulito da cui nasce tutto, e lo storico dei verdetti contro la pioggia
 * osservata — la pagella in miniatura, in attesa del Benchmark (fase 13).
 */
@Composable
internal fun NowcastPage(state: HomeUiState) {
  val verdict = state.verdict

  PageSection("Adesso")
  if (verdict == null) {
    PageNote(
      "Il verdetto compare dopo circa 13 ore di campionamento continuo: il modello vuole vedere " +
        "la tendenza a 12 ore prima di parlare, e un verdetto costruito sul vuoto sarebbe un'invenzione.",
    )
    val readiness = state.readiness
    if (readiness != null) {
      Spacer(Modifier.height(10.dp))
      Text(readiness.stageLabel, style = MaterialTheme.typography.titleSmall, color = White)
      Spacer(Modifier.height(6.dp))
      FluidProgressBar(progress = { readiness.overallFraction }, color = PageBlue)
      Spacer(Modifier.height(6.dp))
    }
    val points = state.cleaning?.filtered?.size ?: 0
    if (points > 0) StatRow("Punti puliti in archivio", "$points", "ultime 24 ore")
  } else {
    val (title, body, color) = when (verdict.level) {
      AlertLevel.QUIETE -> Triple(
        "Quiete",
        "Il barometro non vede cambiamenti in arrivo: la pressione fa il suo corso normale.",
        PageGreen,
      )
      AlertLevel.SORVEGLIANZA -> Triple(
        "Sorveglianza",
        "La pressione si muove: il sensore legge piu' spesso e il verdetto e' in osservazione.",
        PageAmber,
      )
      AlertLevel.ALLERTA -> Triple(
        "Allerta",
        "Il barometro vede arrivare la pioggia: probabilita' forte nelle prossime ore.",
        PageRed,
      )
    }
    Text(title, style = MaterialTheme.typography.headlineMedium, color = color)
    Text(body, style = MaterialTheme.typography.bodyMedium, color = Dim)

    PageSection("Probabilita' di precipitazione, 0-6 ore")
    verdict.windows.forEach { WindowRow(it) }
    PageNote(
      "La banda e' la dispersione dei cinque modelli addestrati sullo stesso banco (bagging): " +
        "stretta quando sono d'accordo, larga quando la situazione e' ambigua.",
    )

    PageSection("I fattori del verdetto")
    verdict.windows.forEach { window ->
      Text(
        windowLabel(window.window),
        style = MaterialTheme.typography.labelMedium,
        color = Faint,
        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
      )
      if (window.topFactors.isEmpty()) {
        Text("Niente fuori dal neutro: e' la climatologia a parlare.", style = MaterialTheme.typography.bodySmall, color = Faint)
      } else {
        val strongest = window.topFactors.maxOf { abs(it.contribution) }.coerceAtLeast(1e-9)
        window.topFactors.forEach { FactorRow(it, strongest) }
      }
    }
  }

  val cleaning = state.cleaning
  if (cleaning != null && cleaning.filtered.size >= 2) {
    PageSection("Il segnale pulito (12 ore, livello del mare)")
    CurveWithLabels(
      values = cleaning.filtered.map { it.levelHpa },
      labels = timeLabels(cleaning.filtered.map { it.timestampMillis }),
      color = PageBlue,
      unit = " hPa",
      decimals = 1,
    )
    val latest = cleaning.latest
    if (latest != null) {
      StatRow("Livello", "${fmt1(latest.levelHpa)} hPa", "±${fmt1(latest.levelSigmaHpa)}")
      StatRow(
        "Tendenza",
        String.format(Locale.ROOT, "%+.2f hPa/h", latest.trendHpaPerHour),
        "±${String.format(Locale.ROOT, "%.2f", latest.trendSigmaHpaPerHour)}",
      )
    }
    val tide = cleaning.tide
    StatRow(
      "Marea atmosferica",
      tideSourceLabel(tide.source),
      "S1 ${fmt1(tide.s1AmplitudeHpa)} · S2 ${fmt1(tide.s2AmplitudeHpa)} hPa",
    )
    if (tide.source != TideSource.NONE) {
      StatRow("Spinta della marea adesso", String.format(Locale.ROOT, "%+.2f hPa", tide.tideAtLatestHpa), "gia' sottratta")
    }
  }

  PageSection("Storico dei verdetti, 24 ore")
  val history = state.verdictHistory
  if (history.size >= 2) {
    CurveWithLabels(
      values = history.map { it.probability13 * 100 },
      labels = timeLabels(history.map { it.timestampMillis }),
      color = PageAmber,
      unit = "%",
    )
    Text(
      "Probabilita' di pioggia a 1-3 ore, com'era ogni volta che il ciclo ha giudicato.",
      style = MaterialTheme.typography.bodySmall,
      color = Faint,
    )
    val alerts = history.count { it.level == AlertLevel.ALLERTA.name }
    val watches = history.count { it.level == AlertLevel.SORVEGLIANZA.name }
    StatRow("Verdetti registrati", "${history.size}", "allerta $alerts · sorveglianza $watches")
  } else {
    PageNote("Il ciclo in background sta accumulando i verdetti: torna fra qualche ora.")
  }

  val observed = state.observedPrecipitation
  if (observed.isNotEmpty()) {
    PageSection("Cosa e' successo davvero")
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
    StatRow("Pioggia osservata", "${fmt1(observed.sumOf { it.second })} mm", "nelle ultime ${observed.size} ore")
    PageNote(
      "Le ore passate dell'opinione piu' completa (Open-Meteo) sono analisi, non previsioni: " +
        "e' il metro con cui la pagella giudichera' il barometro.",
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
        "banda ${(window.probabilityLow * 100).toInt()}-${(window.probabilityHigh * 100).toInt()}%",
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
    Text(factor.name, style = MaterialTheme.typography.bodyMedium, color = White.copy(alpha = 0.85f), modifier = Modifier.weight(1f))
    Spacer(Modifier.width(8.dp))
    Box(
      Modifier
        .width((110 * (abs(factor.contribution) / strongest)).coerceAtLeast(6.0).dp)
        .height(6.dp)
        .background(color, ContinuousCornerShape(3.dp)),
    )
  }
}

internal fun windowLabel(window: String): String = when (window) {
  "0-1h" -> "Entro un'ora"
  "1-3h" -> "Fra 1 e 3 ore"
  "3-6h" -> "Fra 3 e 6 ore"
  else -> window
}

internal fun tideSourceLabel(source: TideSource): String = when (source) {
  TideSource.NONE -> "non sottratta (posizione ignota)"
  TideSource.CLIMATOLOGICAL -> "climatologica"
  else -> "stimata sul posto"
}
