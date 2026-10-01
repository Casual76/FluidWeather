package dev.pampa.fluidweather.testbench.replay

import dev.pampa.fluidweather.nowcast.truth.RainWindow
import dev.pampa.fluidweather.nowcast.truth.RainWindows
import dev.pampa.fluidweather.testbench.tiers.LocationInputs
import dev.pampa.fluidweather.testbench.tiers.TierKind

/**
 * La probabilita' di pioggia dei provider in classifica, come riga di riferimento: **con fuga**.
 *
 * La serie e' stitched (historical-forecast-api): ogni ora viene dalla corsa piu' recente che la
 * copriva, cioe' da una previsione emessa *dopo* l'emissione del telefono — per la finestra 3-6h anche
 * sei ore dopo. Nessun telefono ha mai avuto quel numero all'ora dell'emissione: e' un tetto di cio'
 * che un provider potrebbe dire, non un concorrente. Per questo le colonne portano "con fuga
 * (stitched)" nel nome, compaiono solo nelle tabelle FRESH (e' la PoP di un fetch appena fatto) e non
 * entrano mai ne' nella migliore baseline ne' nel gate.
 *
 * La probabilita' di finestra e' la PoP oraria massima sugli slot **esatti** della finestra
 * ([RainWindows.slotEnds] dall'ancora): la PoP di Open-Meteo e' la probabilita' di piu' di 0,1 mm
 * nell'ora precedente, la stessa convenzione degli slot. Uno slot senza PoP rende la finestra muta
 * (NaN), come un buco rende ingiudicabile la verita'.
 *
 * Il massimo delle PoP orarie non e' la probabilita' della finestra (che sta fra il massimo e la
 * somma): e' il numero che la vecchia app mostrava, e la sua MAE e' il numero della vecchia classifica.
 */
class LeakyProviderPop(private val providers: List<String> = LocationInputs.PROVIDER_MODELS) : CasePredictor {

  override val columns: List<String> = providers.map { columnName(it) }

  override fun session(input: LocationInputs, kind: TierKind): CaseSession {
    val series = providers.map { input.providerPop[it] }
    return CaseSession { view ->
      Array(providers.size) { k ->
        val pop = series[k]
        DoubleArray(RainWindows.ALL.size) { w ->
          if (kind != TierKind.FRESH || pop == null) Double.NaN else windowPop(pop, view.anchorMillis, RainWindows.ALL[w])
        }
      }
    }
  }

  companion object {
    /** L'etichetta che accompagna ogni riga dei provider, ovunque compaia. */
    const val LEAK_LABEL: String = "con fuga (stitched)"

    /** Il nome della colonna di un provider. */
    fun columnName(provider: String): String = "$provider PoP $LEAK_LABEL"

    /** Una colonna e' di un provider con fuga? */
    fun isLeaky(column: String): Boolean = column.endsWith(LEAK_LABEL)

    /** La PoP massima sugli slot della finestra dall'ancora; NaN se uno slot manca. */
    fun windowPop(pop: Map<Long, Double>, anchorMillis: Long, window: RainWindow): Double {
      var max = Double.NEGATIVE_INFINITY
      for (end in RainWindows.slotEnds(anchorMillis, window)) {
        val value = pop[end] ?: return Double.NaN
        if (value.isNaN()) return Double.NaN
        if (value > max) max = value
      }
      return max
    }
  }
}
