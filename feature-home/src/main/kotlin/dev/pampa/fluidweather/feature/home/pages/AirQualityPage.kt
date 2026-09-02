package dev.pampa.fluidweather.feature.home.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import dev.pampa.fluidweather.core.model.AqiBand
import dev.pampa.fluidweather.feature.home.HomeUiState
import dev.pampa.fluidweather.feature.home.aqiColor

/**
 * La pagina della qualita' dell'aria: l'indice europeo con la sua scala, gli inquinanti uno a
 * uno col sotto-indice sulla LORO scala (e' cosi' che si capisce chi domina), la previsione
 * dell'indice ora per ora, e i pollini per specie.
 */
@Composable
internal fun AirQualityPage(state: HomeUiState) {
  val air = state.airQuality
  if (air == null) {
    PageNote("Qualita' dell'aria non disponibile per questo punto.")
    return
  }

  PageSection("Indice europeo (EAQI)")
  BigStat("${air.europeanAqi}", null, air.band.label, color = aqiColor(air.band))
  Spacer(Modifier.height(10.dp))
  Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
    AqiBand.entries.forEach { band ->
      Box(
        Modifier
          .weight(1f)
          .height(if (band == air.band) 12.dp else 8.dp)
          .background(aqiColor(band).copy(alpha = if (band == air.band) 1f else 0.45f), ContinuousCornerShape(4.dp)),
      )
    }
  }
  Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
    Text("Buona", style = MaterialTheme.typography.labelSmall, color = Faint)
    Text("Moderata", style = MaterialTheme.typography.labelSmall, color = Faint)
    Text("Pessima", style = MaterialTheme.typography.labelSmall, color = Faint)
  }
  PageNote(
    "L'EAQI (Agenzia europea dell'ambiente) e' il peggiore dei sotto-indici: ogni inquinante ha " +
      "le sue soglie, e domina quello piu' vicino al proprio limite, non quello col numero piu' alto.",
  )

  PageSection("Inquinanti adesso")
  if (air.pollutants.isEmpty()) {
    PageNote("Il servizio non ha riportato le concentrazioni.")
  } else {
    Row(Modifier.fillMaxWidth().padding(bottom = 2.dp)) {
      Text("inquinante", style = MaterialTheme.typography.labelSmall, color = Faint, modifier = Modifier.weight(1f))
      Text("µg/m³", style = MaterialTheme.typography.labelSmall, color = Faint, modifier = Modifier.width(64.dp))
      Text("sotto-indice", style = MaterialTheme.typography.labelSmall, color = Faint, modifier = Modifier.width(84.dp))
      Text("banda", style = MaterialTheme.typography.labelSmall, color = Faint, modifier = Modifier.width(88.dp))
    }
    air.pollutants.sortedByDescending { it.subIndex }.forEach { pollutant ->
      Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Text(
          pollutant.name + if (pollutant.name == air.dominantPollutant) " · domina" else "",
          style = MaterialTheme.typography.bodyMedium,
          color = White,
          modifier = Modifier.weight(1f),
        )
        Text(fmt1(pollutant.valueUgm3), style = MaterialTheme.typography.bodyMedium, color = Dim, modifier = Modifier.width(64.dp))
        Text(fmt0(pollutant.subIndex), style = MaterialTheme.typography.bodyMedium, color = White, modifier = Modifier.width(84.dp))
        Text(
          pollutant.band.label,
          style = MaterialTheme.typography.bodySmall,
          color = aqiColor(pollutant.band),
          modifier = Modifier.width(88.dp),
        )
      }
    }
    PageNote("PM su media giornaliera, gas su media oraria: e' cosi' che la scala EEA li definisce.")
  }

  PageSection("Previsione dell'indice")
  val forecast = air.forecast
  if (forecast.size >= 2) {
    CurveWithLabels(
      values = forecast.map { it.europeanAqi.toDouble() },
      labels = timeLabels(forecast.map { it.timestampMillis }),
      color = PageAmber,
      height = 80.dp,
    )
    val worst = forecast.maxByOrNull { it.europeanAqi }
    if (worst != null) {
      StatRow("Peggiore in arrivo", "${worst.europeanAqi} · ${AqiBand.of(worst.europeanAqi).label}", fmtDay(worst.timestampMillis) + " " + fmtTime(worst.timestampMillis))
    }
  } else {
    PageNote("Nessuna previsione dell'indice per questo punto.")
  }

  PageSection("Pollini")
  val pollen = air.pollen
  if (pollen == null) {
    PageNote("I pollini sono serviti solo in Europa dal modello CAMS.")
  } else if (!pollen.any) {
    PageNote("Nessun polline rilevante in questo momento.")
  } else {
    listOf(
      "Ontano" to pollen.alder,
      "Betulla" to pollen.birch,
      "Graminacee" to pollen.grass,
      "Olivo" to pollen.olive,
      "Ambrosia" to pollen.ragweed,
    ).forEach { (name, value) ->
      if (value != null) {
        StatRow(name, "${value.toInt()} grani/m³", pollenLevel(value))
      }
    }
    PageNote("Soglie generiche: basso sotto 10 grani/m³, moderato fino a 50, alto fino a 100, oltre molto alto. La sensibilita' e' personale.")
  }
}

private fun pollenLevel(value: Double): String = when {
  value <= 0.0 -> "assente"
  value < 10 -> "basso"
  value < 50 -> "moderato"
  value < 100 -> "alto"
  else -> "molto alto"
}
