package dev.pampa.fluidweather.nowcast.features

import dev.pampa.fluidweather.nowcast.truth.RainWindows
import java.util.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextSlotsTest {

  private val hour = 3_600_000L
  private val origin = 1_704_067_200_000L // 2024-01-01T00:00Z

  /**
   * Un lettore dove ogni valore dice da quale slot viene: lo slot che si chiude a `origin + k h` vale
   * `k` (la pioggia `k / 100`, il punto di rugiada `k - 2`). Un contesto che leggesse lo slot sbagliato
   * si tradisce nel numero. [skip] toglie righe (buchi dell'archivio), [asked] registra ogni lettura.
   */
  private class Labelled(
    private val origin: Long,
    private val hours: Int = 400,
    private val skip: Set<Int> = emptySet(),
    private val skipVariable: Map<ContextVariable, Set<Int>> = emptyMap(),
  ) : ContextSlotLookup {
    val asked = ArrayList<Long>()

    override fun value(slotEndMillis: Long, v: ContextVariable): Double? {
      asked += slotEndMillis
      val k = ((slotEndMillis - origin) / 3_600_000L).toInt()
      if (k < 0 || k >= hours || k in skip || k in (skipVariable[v] ?: emptySet())) return null
      return when (v) {
        ContextVariable.PRECIPITATION -> k * 0.01
        ContextVariable.DEW_POINT -> k - 2.0
        else -> k.toDouble()
      }
    }
  }

  @Test
  fun `S e' l'ultimo slot chiuso e allo scoccare dell'ora e' quello che si chiude adesso`() {
    val lookup = Labelled(origin)
    val ref = origin + 50 * hour + 20 * 60_000L
    val context = ContextSlots.contextAt(ref, lookup)!!
    assertEquals(origin + 50 * hour, context.slotEndMillis)

    assertEquals(ref - ref % hour, RainWindows.lastClosedSlotEnd(ref))
    val onTheHour = origin + 60 * hour
    assertEquals(onTheHour, ContextSlots.contextAt(onTheHour, Labelled(origin))!!.slotEndMillis)
    assertEquals(onTheHour - hour, ContextSlots.contextAt(onTheHour - 1, Labelled(origin))!!.slotEndMillis)
  }

  @Test
  fun `non legge mai oltre S`() {
    val random = Random(11)
    repeat(300) {
      val ref = origin + 8 * hour + (random.nextDouble() * 380 * hour).toLong()
      val lookup = Labelled(origin)
      val context = ContextSlots.contextAt(ref, lookup)!!
      val slot = context.slotEndMillis!!
      assertTrue(lookup.asked.isNotEmpty())
      assertTrue("letto ${lookup.asked.max()} oltre S=$slot (ref $ref)", lookup.asked.all { it <= slot })
      assertTrue(slot <= ref && ref - slot < hour)
    }
  }

  @Test
  fun `un valore futuro non cambia il contesto`() {
    val ref = origin + 50 * hour + 20 * 60_000L
    val base = Labelled(origin)
    val poisoned = ContextSlotLookup { t, v -> if (t > origin + 50 * hour) 99_999.0 else base.value(t, v) }
    assertEquals(ContextSlots.contextAt(ref, Labelled(origin)), ContextSlots.contextAt(ref, poisoned))
  }

  @Test
  fun `i campi del v2 sono quelli di sempre`() {
    val ref = origin + 50 * hour + 20 * 60_000L
    val c = ContextSlots.contextAt(ref, Labelled(origin))!!
    assertEquals(50.0, c.relativeHumidityPercent!!, 1e-9)
    assertEquals(50.0, c.cloudCoverPercent!!, 1e-9)
    assertEquals(50.0, c.windSpeedKmh!!, 1e-9)
    assertEquals(50.0, c.windDirectionDeg!!, 1e-9)
    assertEquals(47.0, c.windDirectionDeg3hAgo!!, 1e-9)
    assertEquals(2.0, c.dewPointSpreadC!!, 1e-9) // temperatura 50, rugiada 48
    assertEquals(50.0, c.pressureMslHpa!!, 1e-9)
    assertEquals(47.0, c.pressureMsl3hAgoHpa!!, 1e-9)
    assertEquals(0.50, c.rainLastHourMm!!, 1e-9)
    assertEquals(0.50 + 0.49 + 0.48, c.rainLast3hMm!!, 1e-9)
  }

  @Test
  fun `i campi del v3 vengono dagli slot S e S meno tre ore`() {
    val ref = origin + 50 * hour + 20 * 60_000L
    val c = ContextSlots.contextAt(ref, Labelled(origin))!!
    assertEquals(50.0, c.temperatureC!!, 1e-9)
    assertEquals(47.0, c.temperature3hAgoC!!, 1e-9)
    assertEquals(48.0, c.dewPointC!!, 1e-9)
    assertEquals(45.0, c.dewPoint3hAgoC!!, 1e-9)
    assertEquals(47.0, c.cloudCover3hAgoPercent!!, 1e-9)
    val slots = c.rainSlotsMm!!
    assertEquals(ContextSlots.RAIN_SLOTS, slots.size)
    for (k in 0..6) assertEquals("slot S-$k", (50 - k) * 0.01, slots[k]!!, 1e-9)
    assertEquals(slots[0], c.rainLastHourMm)
  }

  @Test
  fun `un buco resta un buco, senza slot vicini e senza zeri`() {
    // Tolgo lo slot 48: rainSlots[2] e' null, la somma delle tre ore somma i due che ci sono.
    val ref = origin + 50 * hour + 20 * 60_000L
    val c = ContextSlots.contextAt(ref, Labelled(origin, skip = setOf(48)))!!
    assertNull(c.rainSlotsMm!![2])
    assertEquals(0.50 + 0.49, c.rainLast3hMm!!, 1e-9)
    // Tolgo la pioggia di tutti e tre gli slot: la somma non c'e', non vale zero.
    val noRain = ContextSlots.contextAt(
      ref,
      Labelled(origin, skipVariable = mapOf(ContextVariable.PRECIPITATION to setOf(50, 49, 48))),
    )!!
    assertNull(noRain.rainLast3hMm)
    assertNull(noRain.rainLastHourMm)
    // Senza lo slot di tre ore fa, i campi "3 h fa" sono null.
    val noThreeAgo = ContextSlots.contextAt(ref, Labelled(origin, skip = setOf(47)))!!
    assertNull(noThreeAgo.temperature3hAgoC)
    assertNull(noThreeAgo.cloudCover3hAgoPercent)
    assertNull(noThreeAgo.pressureMsl3hAgoHpa)
    assertEquals(50.0, noThreeAgo.cloudCoverPercent!!, 1e-9)
  }

  @Test
  fun `senza la riga adesso il contesto non c'e'`() {
    val ref = origin + 50 * hour + 20 * 60_000L
    assertNull(ContextSlots.contextAt(ref, Labelled(origin, skip = setOf(50))))
    // Col solo la pioggia nello slot S il contesto c'e' (come per un modello che ha solo quella).
    val onlyRain = ContextSlotLookup { t, v -> if (t == origin + 50 * hour && v == ContextVariable.PRECIPITATION) 0.4 else null }
    val c = ContextSlots.contextAt(ref, onlyRain)
    assertNotNull(c)
    assertEquals(0.4, c!!.rainLastHourMm!!, 1e-9)
    assertNull(c.relativeHumidityPercent)
  }

  @Test
  fun `il telefono, che legge un pacchetto per timestamp esatto, da' lo stesso contesto del banco`() {
    // Il pacchetto best_match con past_hours=6: righe S-6h..S (e oltre, le previsioni), indicizzate per timestamp.
    val ref = origin + 120 * hour + 37 * 60_000L
    val s = RainWindows.lastClosedSlotEnd(ref)
    val bundle: Map<Long, Map<ContextVariable, Double>> = (-6..30).associate { k ->
      (s + k * hour) to ContextVariable.entries.associateWith { v -> (((s + k * hour) - origin) / hour) * (v.ordinal + 1) + 0.5 }
    }
    val phone = ContextSlotLookup { t, v -> bundle[t]?.get(v) }
    val fromBundle = ContextSlots.contextAt(ref, phone)!!
    assertEquals(s, fromBundle.slotEndMillis)
    // Le letture future del pacchetto (k > 0) non entrano: il contesto e' identico a quello di un pacchetto troncato a S.
    val truncated = ContextSlotLookup { t, v -> if (t > s) null else bundle[t]?.get(v) }
    assertEquals(fromBundle, ContextSlots.contextAt(ref, truncated))
    // Il pacchetto ha esattamente sette righe di pioggia utili.
    assertEquals(7, fromBundle.rainSlotsMm!!.count { it != null })
  }
}
