package dev.pampa.fluidweather.testbench.data

import java.io.File
import java.io.IOException

/**
 * Il banco di prova dei fetcher: un orologio che avanza solo dormendo, un budget su file temporaneo
 * e un trasporto che risponde con una funzione. Nessuna rete, nessuna attesa vera.
 */
internal class FetcherHarness(directory: File, private val handler: (String) -> HttpReply) {

  var now = 1_800_000_000_000L
  val sleeps = mutableListOf<Long>()
  val urls = mutableListOf<String>()
  val logLines = mutableListOf<String>()
  val budgetFile = File(directory, "budget.log")

  private val budget = CallBudget(budgetFile, { now }, ::sleep, {})

  val http = OpenMeteoHttp(
    budget,
    HttpTransport { url ->
      urls += url
      handler(url)
    },
    ::sleep,
    { logLines += it },
  )

  private fun sleep(millis: Long) {
    sleeps += millis
    now += millis
  }

  companion object {
    fun ok(body: String) = HttpReply(200, body)
    fun notAvailable() = HttpReply(
      400,
      """{"reason":"The requested model run is not available. Model: m, run: 2026-04-02T06:00Z","error":true}""",
    )
    fun io(): Nothing = throw IOException("connessione caduta")
  }
}
