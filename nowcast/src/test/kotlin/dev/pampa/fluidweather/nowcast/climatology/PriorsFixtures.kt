package dev.pampa.fluidweather.nowcast.climatology

import java.time.Instant

/**
 * Tabelle di baseline sintetiche per i test: un mondo a blocchi (ogni 48 ore piovono sei ore di
 * fila) e uno sfasato, cosi' due "localita'" hanno tabelle locali diverse e il riferimento di tutti
 * i posti e' una cosa terza.
 */
internal object PriorsFixtures {

  private const val HOUR = 3_600_000L

  val START: Long = Instant.parse("2023-03-01T00:00:00Z").toEpochMilli()

  /** Pioggia a blocchi di sei ore ogni 48, spostati di [phase] ore; pressione che scende prima del blocco. */
  fun truth(phase: Int = 0, days: Int = 40): Map<Long, Double> =
    (0..days * 24).associate { i -> START + i * HOUR to if (Math.floorMod(i + phase, 48) in 0..5) 1.0 else 0.0 }

  fun msl(phase: Int = 0, days: Int = 40): Map<Long, Double> =
    (0..days * 24).associate { i ->
      val k = Math.floorMod(i + phase, 48)
      START + i * HOUR to when {
        k in 42..47 -> 1013.0 - (k - 41) * 1.0
        k in 0..5 -> 1007.0
        else -> 1013.0
      }
    }

  class Tables(
    val climatology: WindowClimatology,
    val baselines: LocalBaselines,
  )

  fun tables(phase: Int = 0, days: Int = 40, longitude: Double = 0.0): Tables {
    val truth = truth(phase, days)
    val climatology = WindowClimatology.build(truth, longitude)!!
    return Tables(climatology, LocalBaselines.build(truth, climatology, rainNowMm = truth, mslHpa = msl(phase, days)))
  }

  /** Il riferimento di tutti i posti sulle due localita' sintetiche. */
  fun pooled(): PooledPriors {
    val a = tables(0)
    val b = tables(7)
    return PooledPriors.of(listOf(a.climatology to a.baselines, b.climatology to b.baselines))
  }

  fun local(phase: Int = 0): LocalPriors {
    val t = tables(phase)
    return LocalPriors.local(t.climatology, t.baselines, pooled())
  }

  fun pooledOnly(): LocalPriors = LocalPriors.pooledOnly(pooled())
}
