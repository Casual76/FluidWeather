package dev.pampa.fluidweather.testbench.metrics

import dev.pampa.fluidweather.nowcast.scoring.ForecastCase
import dev.pampa.fluidweather.nowcast.scoring.ProperScores
import java.util.Locale

/**
 * La curva di affidabilita' stampata: "quando dico X%, succede davvero Y% delle volte".
 *
 * Un gradino per riga, con quanti casi lo compongono: un gradino con tre casi non dice niente,
 * e chi legge la tabella deve vederlo. Un modello calibrato ha la frequenza osservata vicina
 * alla previsione media di ogni gradino; sotto la diagonale e' troppo bagnato, sopra troppo asciutto.
 */
object ReliabilityPrinter {

  /** Le righe della tabella (senza intestazione) per [cases] su [bins] gradini; vuota se non ci sono casi. */
  fun lines(cases: List<ForecastCase>, bins: Int = 10): List<String> =
    ProperScores.reliability(cases, bins).map { bin ->
      String.format(
        Locale.ROOT,
        "%4.2f-%4.2f  %7d  %8.3f  %8.3f  %+8.3f",
        bin.lower, bin.upper, bin.count, bin.meanForecast, bin.observedFrequency,
        bin.observedFrequency - bin.meanForecast,
      )
    }

  /** L'intestazione che accompagna [lines]. */
  fun header(): String =
    String.format(Locale.ROOT, "%-10s  %7s  %8s  %8s  %8s", "gradino", "n", "p media", "osserv.", "scarto")

  /** La tabella intera, con [indent] davanti a ogni riga. */
  fun format(cases: List<ForecastCase>, bins: Int = 10, indent: String = ""): String =
    (listOf(header()) + lines(cases, bins)).joinToString("\n") { indent + it }

  /**
   * Piu' predittori sugli stessi gradini, affiancati: `gradino | n p media osserv. | n p media osserv. | ...`.
   * Serve a leggere un prima/dopo (grezzo contro ricalibrato) senza saltare fra due tabelle.
   * Un gradino vuoto per un predittore si stampa con i trattini.
   */
  fun sideBySide(labels: List<String>, series: List<List<ForecastCase>>, bins: Int = 10, indent: String = ""): String {
    require(labels.size == series.size) { "un'etichetta per serie" }
    val tables = series.map { cases -> ProperScores.reliability(cases, bins).associateBy { Math.round(it.lower * bins).toInt() } }
    val used = tables.flatMap { it.keys }.toSortedSet()
    val head = StringBuilder(String.format(Locale.ROOT, "%-10s", "gradino"))
    for (label in labels) head.append(String.format(Locale.ROOT, " | %-26s", label))
    val sub = StringBuilder(String.format(Locale.ROOT, "%-10s", ""))
    repeat(labels.size) { sub.append(String.format(Locale.ROOT, " | %7s %8s %9s", "n", "p media", "osserv.")) }
    val lines = mutableListOf(head.toString(), sub.toString())
    for (bin in used) {
      val line = StringBuilder(String.format(Locale.ROOT, "%4.2f-%4.2f", bin.toDouble() / bins, (bin + 1).toDouble() / bins))
      for (table in tables) {
        val cell = table[bin]
        if (cell == null) {
          line.append(String.format(Locale.ROOT, " | %7s %8s %9s", "-", "-", "-"))
        } else {
          line.append(String.format(Locale.ROOT, " | %7d %8.3f %9.3f", cell.count, cell.meanForecast, cell.observedFrequency))
        }
      }
      lines += line.toString()
    }
    return lines.joinToString("\n") { indent + it }
  }
}
