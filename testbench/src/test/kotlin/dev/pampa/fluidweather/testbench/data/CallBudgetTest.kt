package dev.pampa.fluidweather.testbench.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CallBudgetTest {

  @get:Rule
  val tmp = TemporaryFolder()

  /** Un orologio finto: dormire fa avanzare il tempo, e ogni sonno si registra. */
  private class FakeTime(var now: Long = 1_800_000_000_000L) {
    val sleeps = mutableListOf<Long>()
    fun sleep(millis: Long) {
      sleeps += millis
      now += millis
    }
  }

  private fun budget(
    time: FakeTime,
    file: java.io.File = java.io.File(tmp.root, "budget.log"),
    perMinute: Int = CallBudget.PER_MINUTE,
    perHour: Int = CallBudget.PER_HOUR,
    perDay: Int = CallBudget.PER_DAY,
  ) = CallBudget(file, { time.now }, time::sleep, {}, perMinute, perHour, perDay)

  @Test
  fun `il peso e' il prodotto di variabili, giorni, localita' e modelli arrotondati per eccesso`() {
    assertEquals(10, CallBudget.weightOf(2, 1, 10, 1))   // una corsa su dieci localita'
    assertEquals(1, CallBudget.weightOf(10, 14, 1, 1))   // il massimo che pesa uno
    assertEquals(2, CallBudget.weightOf(11, 14, 1, 1))   // undici variabili
    assertEquals(2, CallBudget.weightOf(10, 15, 1, 1))   // quindici giorni
    assertEquals(27, CallBudget.weightOf(10, 365, 1, 1)) // un anno orario
    assertEquals(81, CallBudget.weightOf(3, 365, 1, 3))  // tre modelli
    assertEquals(1, CallBudget.weightOf(0, 0, 1, 1))     // mai zero
  }

  @Test
  fun `sotto i tetti si parte subito e ogni richiesta lascia una riga`() {
    val time = FakeTime()
    val file = java.io.File(tmp.root, "budget.log")
    val budget = budget(time, file)

    repeat(3) { budget.acquire(10) }

    assertTrue(time.sleeps.isEmpty())
    val lines = file.readLines()
    assertEquals(3, lines.size)
    assertTrue(lines.all { it == "${time.now},10" })
  }

  @Test
  fun `al tetto del minuto si dorme fino all'uscita della riga piu' vecchia`() {
    val time = FakeTime()
    val start = time.now
    val budget = budget(time)

    budget.acquire(CallBudget.PER_MINUTE)
    assertTrue(time.sleeps.isEmpty())
    budget.acquire(1)

    assertTrue("deve aver dormito", time.sleeps.isNotEmpty())
    val waited = time.now - start
    assertTrue("aspettato $waited ms", waited in CallBudget.MINUTE..(CallBudget.MINUTE + CallBudget.SLACK_MILLIS))
  }

  @Test
  fun `si aspetta l'uscita dell'ultima riga che serve, non di tutte`() {
    val time = FakeTime()
    val start = time.now
    val budget = budget(time)

    budget.acquire(300)
    time.now += 10_000
    budget.acquire(200)
    // 500 nell'ultimo minuto: per 100 in piu' basta che esca la prima (300), non anche la seconda.
    budget.acquire(100)

    val waited = time.now - start
    assertTrue("aspettato $waited ms", waited in CallBudget.MINUTE..(CallBudget.MINUTE + CallBudget.SLACK_MILLIS))
  }

  @Test
  fun `al tetto dell'ora si aspetta un'ora anche se il minuto e' libero`() {
    val time = FakeTime()
    val start = time.now
    val budget = budget(time, perMinute = 100, perHour = 150, perDay = 10_000)

    budget.acquire(100)
    time.now += 2 * CallBudget.MINUTE
    budget.acquire(50)
    assertTrue(time.sleeps.isEmpty())
    budget.acquire(10)

    val waited = time.now - start
    assertTrue("aspettato $waited ms", waited in CallBudget.HOUR..(CallBudget.HOUR + CallBudget.MINUTE))
  }

  @Test
  fun `al tetto del giorno si aspetta un giorno`() {
    val time = FakeTime()
    val start = time.now
    val budget = budget(time, perMinute = 200, perHour = 200, perDay = 200)

    budget.acquire(200)
    budget.acquire(1)

    val waited = time.now - start
    assertTrue("aspettato $waited ms", waited in CallBudget.DAY..(CallBudget.DAY + CallBudget.MINUTE))
  }

  @Test
  fun `il conto sopravvive a un riavvio`() {
    val time = FakeTime()
    val file = java.io.File(tmp.root, "budget.log")
    budget(time, file).acquire(CallBudget.PER_MINUTE)

    // Un secondo processo (o lo stesso dopo un riavvio) parte dal file, non da zero.
    val second = budget(time, file)
    assertEquals(CallBudget.PER_MINUTE, second.usedLast(CallBudget.MINUTE))
    assertTrue(second.waitMillisFor(1) > 0)

    time.now += CallBudget.MINUTE + 1
    assertEquals(0L, second.waitMillisFor(1))
    assertEquals("la finestra piu' lunga ricorda ancora", CallBudget.PER_MINUTE, second.usedLast(CallBudget.HOUR))
  }

  @Test
  fun `piu' istanti che prenotano insieme sullo stesso file non sforano mai il tetto`() {
    // Due "processi" (due istanze sullo stesso file) con quattro fili ciascuno chiedono 120 in
    // tutto contro un tetto di 40 al minuto, con l'orologio fermo: chi dovrebbe aspettare si
    // arrende invece di dormire. Passano esattamente 40, e il file ne ha esattamente 40.
    val file = java.io.File(tmp.root, "budget.log")
    val now = 1_800_000_000_000L
    class GaveUp : RuntimeException()
    fun instance() = CallBudget(file, { now }, { throw GaveUp() }, {}, perMinute = 40, perHour = 1_000, perDay = 1_000)
    val budgets = listOf(instance(), instance())
    val granted = java.util.concurrent.atomic.AtomicInteger()
    val failures = java.util.concurrent.ConcurrentLinkedQueue<Throwable>()
    val threads = (0 until 8).map { k ->
      Thread {
        repeat(15) {
          try {
            budgets[k % 2].acquire(1)
            granted.incrementAndGet()
          } catch (gaveUp: GaveUp) {
            // Avrebbe dovuto aspettare: e' il comportamento giusto, non si conta.
          } catch (e: Throwable) {
            failures += e
          }
        }
      }
    }
    threads.forEach { it.start() }
    threads.forEach { it.join() }

    assertTrue("errori: $failures", failures.isEmpty())
    assertEquals(40, granted.get())
    assertEquals(40, file.readLines().sumOf { it.substringAfter(',').toInt() })
  }

  @Test
  fun `un peso che non entra in nessun tetto e' un errore, non un'attesa infinita`() {
    val time = FakeTime()
    val budget = budget(time, perMinute = 50)
    val failure = runCatching { budget.acquire(51) }.exceptionOrNull()
    assertTrue(failure is IllegalArgumentException)
    assertTrue(time.sleeps.isEmpty())
  }

  @Test
  fun `all'avvio si buttano le righe vecchie e si ignorano quelle rotte`() {
    val time = FakeTime()
    val file = java.io.File(tmp.root, "budget.log")
    file.writeText(
      "${time.now - 3 * CallBudget.DAY},500\n" +   // vecchia di tre giorni
        "rotta\n" +
        "${time.now - CallBudget.HOUR},7\n",        // viva
    )

    val budget = budget(time, file)

    assertEquals(listOf("${time.now - CallBudget.HOUR},7"), file.readLines())
    assertEquals(7, budget.usedLast(CallBudget.DAY))
    assertFalse(file.readText().contains("rotta"))
  }
}
