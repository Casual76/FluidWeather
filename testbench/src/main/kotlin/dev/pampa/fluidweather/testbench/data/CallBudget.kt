package dev.pampa.fluidweather.testbench.data

import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

/**
 * Il budget di chiamate verso Open-Meteo, che sopravvive ai riavvii.
 *
 * Il piano gratuito dice 600 al minuto, 5.000 all'ora, 10.000 al giorno (e le richieste pesanti
 * contano multiple). Scaricare anni di corse passate richiede giorni: il conto non puo' vivere in
 * memoria, o un riavvio (o un secondo processo del banco) lo azzererebbe e ci si ritroverebbe
 * bloccati a meta' con un 429 quotidiano. Ogni richiesta e' quindi una riga "epochMillis,peso"
 * in un file accanto ai dati; prima di partire si sommano i pesi dell'ultimo minuto, ora e giorno
 * e, se un tetto sarebbe superato, si dorme finche' le righe piu' vecchie escono dalla finestra.
 *
 * I tetti sono volutamente sotto i limiti veri (500/4.500/9.000): il margine copre le stime di
 * peso sbagliate per difetto e un altro uso dello stesso IP.
 *
 * Il file si rilegge a ogni richiesta, cosi' due processi che scaricano insieme si vedono. La
 * lettura, il conto e la riga nuova stanno sotto un lucchetto di file (`<log>.lock`): senza, due
 * processi possono leggere lo stesso spazio libero e partire entrambi, sforando il tetto di una
 * richiesta — o la compattazione di uno puo' riscrivere il file cancellando la riga appena
 * aggiunta dall'altro, e quel peso sparisce dal conto.
 */
class CallBudget(
  private val logFile: File = File("data/.callbudget.log"),
  private val clock: () -> Long = System::currentTimeMillis,
  private val sleeper: (Long) -> Unit = { Thread.sleep(it) },
  private val announce: (String) -> Unit = ::println,
  private val perMinute: Int = PER_MINUTE,
  private val perHour: Int = PER_HOUR,
  private val perDay: Int = PER_DAY,
) {

  private class Entry(val atMillis: Long, val weight: Int)

  private val windows = listOf(
    Triple("minuto", MINUTE, perMinute),
    Triple("ora", HOUR, perHour),
    Triple("giorno", DAY, perDay),
  )

  private var nextAnnounceAt = 0L

  init {
    compact()
  }

  /**
   * Prenota [weight] chiamate: ritorna quando la richiesta puo' partire senza sforare nessun
   * tetto, e la registra nel file. Bloccante per costruzione — e' il punto in cui il banco
   * rallenta per essere un buon cittadino.
   */
  @Synchronized
  fun acquire(weight: Int) {
    val smallestCap = windows.minOf { it.third }
    require(weight in 1..smallestCap) { "peso $weight fuori dai tetti (massimo $smallestCap)" }
    while (true) {
      val now = clock()
      val wait = locked {
        val wait = waitMillis(read(now), now, weight)
        if (wait <= 0L) append(now, weight)
        wait
      }
      if (wait <= 0L) return
      if (now >= nextAnnounceAt) {
        announce("  budget Open-Meteo: aspetto ${formatWait(wait)} (minuto ${usedLast(MINUTE)}/$perMinute, ora ${usedLast(HOUR)}/$perHour, giorno ${usedLast(DAY)}/$perDay)")
        nextAnnounceAt = now + ANNOUNCE_EVERY_MILLIS
      }
      // Dorme a fette: lascia vedere che il processo e' vivo e ricontrolla il file, dove un
      // altro processo potrebbe aver consumato (o liberato) budget nel frattempo.
      sleeper(minOf(wait, MAX_SLICE_MILLIS))
    }
  }

  /** Peso speso nell'ultima finestra (per i rapporti di avanzamento e per i test). */
  fun usedLast(windowMillis: Long): Int {
    val now = clock()
    return read(now).filter { it.atMillis > now - windowMillis }.sumOf { it.weight }
  }

  /** Quanto bisognerebbe aspettare adesso per prenotare [weight] (0 se si puo' partire). */
  fun waitMillisFor(weight: Int): Long {
    val now = clock()
    return waitMillis(read(now), now, weight).coerceAtLeast(0L)
  }

  private fun waitMillis(entries: List<Entry>, now: Long, weight: Int): Long {
    var wait = 0L
    for ((_, window, cap) in windows) {
      val inside = entries.filter { it.atMillis > now - window }
      val used = inside.sumOf { it.weight }
      if (used + weight <= cap) continue
      // Quante righe devono uscire dalla finestra perche' il peso nuovo ci stia: si aspetta
      // l'uscita dell'ultima di quelle, non di tutte.
      var excess = used + weight - cap
      for (entry in inside) {
        excess -= entry.weight
        if (excess <= 0) {
          wait = maxOf(wait, entry.atMillis + window - now + SLACK_MILLIS)
          break
        }
      }
    }
    return wait
  }

  private fun read(now: Long): List<Entry> {
    if (!logFile.exists()) return emptyList()
    val cutoff = now - DAY
    return logFile.readLines().mapNotNull { line ->
      val parts = line.split(",")
      val at = parts.getOrNull(0)?.trim()?.toLongOrNull() ?: return@mapNotNull null
      val weight = parts.getOrNull(1)?.trim()?.toIntOrNull() ?: return@mapNotNull null
      if (at <= cutoff) null else Entry(at, weight)
    }.sortedBy { it.atMillis }
  }

  private fun append(now: Long, weight: Int) {
    logFile.absoluteFile.parentFile?.mkdirs()
    Files.write(
      logFile.toPath(), "$now,$weight\n".toByteArray(),
      StandardOpenOption.CREATE, StandardOpenOption.APPEND,
    )
  }

  /**
   * Il file non deve crescere per sempre: all'avvio si buttano le righe piu' vecchie di due
   * giorni (quelle che nessuna finestra guarda piu'). Riscrittura solo se serve.
   */
  private fun compact() = locked {
    if (!logFile.exists()) return@locked
    val lines = logFile.readLines()
    val cutoff = clock() - 2 * DAY
    val kept = lines.filter { line ->
      val at = line.substringBefore(',').trim().toLongOrNull()
      at != null && at > cutoff
    }
    if (kept.size == lines.size) return@locked
    val temp = File(logFile.path + ".tmp")
    temp.writeText(kept.joinToString("") { it + "\n" })
    Files.move(temp.toPath(), logFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
  }

  /**
   * [block] con il file del conto tutto per se': un lucchetto di sistema su `<log>.lock` (fra
   * processi) e un monitor della JVM (fra istanze dello stesso processo, a cui il lucchetto di
   * sistema non basta: lo stesso file bloccato due volte da una JVM e' un errore, non un'attesa).
   * Il lucchetto e' un file a parte perche' il conto si riscrive con una rinomina.
   */
  private fun <T> locked(block: () -> T): T = synchronized(JVM_LOCK) {
    logFile.absoluteFile.parentFile?.mkdirs()
    RandomAccessFile(File(logFile.path + ".lock"), "rw").use { file ->
      val lock = file.channel.lock()
      try {
        block()
      } finally {
        lock.release()
      }
    }
  }

  private fun formatWait(millis: Long): String = when {
    millis < 120_000L -> "${(millis + 999) / 1_000} s"
    millis < 2 * HOUR -> "${(millis + 59_999) / 60_000} min"
    else -> "%.1f h".format(java.util.Locale.ROOT, millis / HOUR.toDouble())
  }

  companion object {
    const val PER_MINUTE = 500
    const val PER_HOUR = 4_500
    const val PER_DAY = 9_000

    const val MINUTE = 60_000L
    const val HOUR = 3_600_000L
    const val DAY = 86_400_000L

    /** Margine oltre l'istante esatto di uscita: le finestre del server non sono le nostre. */
    const val SLACK_MILLIS = 250L

    private const val MAX_SLICE_MILLIS = 60_000L

    /** Un solo monitor per tutte le istanze: vedi [locked]. */
    private val JVM_LOCK = Any()
    private const val ANNOUNCE_EVERY_MILLIS = 10 * MINUTE

    /**
     * Il peso, stimato per difetto di fiducia: Open-Meteo conta multipla una richiesta con piu' di
     * 10 variabili o piu' lunga di 2 settimane, e ogni localita' e ogni modello contano a parte.
     * Meglio sovrastimare (si scarica un po' piu' piano) che sottostimare (un 429 quotidiano).
     */
    fun weightOf(nVariables: Int, nDays: Int, nLocations: Int = 1, nModels: Int = 1): Int {
      val weight = Math.ceil(nVariables / 10.0).toInt() *
        Math.ceil(nDays / 14.0).toInt() *
        nLocations * nModels
      return weight.coerceAtLeast(1)
    }
  }
}
