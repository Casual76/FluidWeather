package dev.pampa.fluidweather.testbench.tiers

import dev.pampa.fluidweather.nowcast.truth.TruthPanel
import dev.pampa.fluidweather.testbench.data.BenchLocation
import dev.pampa.fluidweather.testbench.data.ForecastArchive
import dev.pampa.fluidweather.testbench.data.HourlySeries
import dev.pampa.fluidweather.testbench.data.StationDataset
import dev.pampa.fluidweather.testbench.replay.TruthSeries
import java.io.File

/**
 * Tutto cio' che il banco onesto sa di una localita', letto una volta:
 *
 * - il dataset ERA5 (`data/<loc>.csv`): da qui si sintetizza il barometro del telefono, e da qui
 *   viene la verita' secondaria;
 * - le serie dei giudici del [TruthPanel] (`data/hf/<loc>/<modello>.csv`): la verita' primaria e,
 *   dal primo giudice, la pressione al mare con cui si tarano le classi di tendenza;
 * - la serie stitched di best_match: il contesto "come lo vede il telefono";
 * - la PoP oraria stitched dei provider in classifica (`icon_seamless`, `gfs_seamless`,
 *   `ecmwf_ifs025`), se c'e': righe di riferimento **con fuga** (vedi [providerPop]), mai nel gate.
 *
 * Il costruttore e' aperto perche' i test montano localita' sintetiche; [load] e' la strada vera.
 */
class LocationInputs(
  val location: BenchLocation,
  val dataset: StationDataset,
  panelMembers: Map<String, HourlySeries>,
  context: HourlySeries,
  /**
   * provider -> (fine dello slot -> PoP in [0, 1]). La PoP oraria di Open-Meteo e' la probabilita'
   * di piu' di 0,1 mm nell'ora **precedente**: la stessa convenzione degli slot di
   * [dev.pampa.fluidweather.nowcast.truth.RainWindows]. Stitched = ogni ora viene dalla corsa piu'
   * recente che la copriva, cioe' da una previsione emessa dopo l'emissione del telefono: fuga.
   */
  val providerPop: Map<String, Map<Long, Double>> = emptyMap(),
) {

  /** La verita' primaria: mediana del pannello, quorum = tutti. */
  val panelTruth: TruthSeries = TruthSeries.panel(panelMembers)

  /** La verita' secondaria: la pioggia della rianalisi. */
  val era5Truth: TruthSeries = TruthSeries.era5(dataset)

  /** Da dove si legge il contesto "adesso" e "tre ore fa" di un fetch. */
  val contextReader: SlotReader = HourlySeriesReader(context)

  /** La pioggia di best_match, slot per slot: e' il "piove adesso" del telefono, e la serie da cui la persistenza impara. */
  val contextRain: Map<Long, Double> = TruthSeries.precipitationOf(context)

  /**
   * La pressione al mare di un giudice del pannello (il primo: Meteo-France), istante -> hPa: e'
   * la serie da cui si tarano le classi di tendenza della regola barometrica, come nell'audit
   * dell'etichetta. Vuota se il giudice non l'ha.
   */
  val panelMsl: Map<Long, Double> =
    panelMembers[MSL_MODEL]?.let { TruthSeries.columnMap(it, "pressure_msl") } ?: emptyMap()

  companion object {

    /** Il giudice la cui pressione al mare tara la regola barometrica. */
    val MSL_MODEL: String = TruthPanel.MODELS.first()

    private const val CONTEXT_MODEL = "best_match"

    /** I provider in classifica di cui si legge la PoP stitched: riferimenti con fuga, facoltativi. */
    val PROVIDER_MODELS: List<String> = listOf("icon_seamless", "gfs_seamless", "ecmwf_ifs025")

    private const val POP_VARIABLE = "precipitation_probability"

    /** Cosa manca per una localita' (vuoto = c'e' tutto): file ERA5, giudici del pannello, contesto. */
    fun missingFor(location: BenchLocation, root: File): List<String> {
      val missing = ArrayList<String>()
      if (!File(root, "${location.name}.csv").exists()) missing += "${location.name}.csv (ERA5)"
      for (model in TruthPanel.MODELS + CONTEXT_MODEL) {
        if (!File(root, "hf/${location.name}/$model.csv").exists()) missing += "hf/${location.name}/$model.csv"
      }
      return missing
    }

    /** Legge la localita' da `<root>/`; null se manca qualcosa ([missingFor] dice cosa). */
    fun load(location: BenchLocation, root: File = File("data")): LocationInputs? {
      if (missingFor(location, root).isNotEmpty()) return null
      val archive = ForecastArchive(root)
      val dataset = StationDataset.parse(File(root, "${location.name}.csv").readLines(), location)
      val members = TruthPanel.MODELS.associateWith { archive.hourly(location, it)!! }
      val context = archive.hourly(location, CONTEXT_MODEL)!!
      // Facoltativi: un provider mancante toglie una riga di riferimento, non la localita'.
      val pop = PROVIDER_MODELS.mapNotNull { model ->
        val series = archive.hourly(location, model) ?: return@mapNotNull null
        val percent = TruthSeries.columnMap(series, POP_VARIABLE)
        if (percent.isEmpty()) null else model to percent.mapValues { (_, value) -> (value / 100.0).coerceIn(0.0, 1.0) }
      }.toMap()
      return LocationInputs(location, dataset, members, context, pop)
    }
  }
}
