package dev.pampa.fluidweather.testbench.tiers

import dev.pampa.fluidweather.nowcast.cleaning.CleaningPipeline
import dev.pampa.fluidweather.nowcast.cleaning.CleaningResult
import dev.pampa.fluidweather.nowcast.features.FeatureExtractor
import dev.pampa.fluidweather.nowcast.truth.RainWindows
import dev.pampa.fluidweather.testbench.replay.SampleSynthesizer
import dev.pampa.fluidweather.testbench.replay.SamplingProfile
import dev.pampa.fluidweather.testbench.replay.TierReplayer
import dev.pampa.fluidweather.testbench.train.RecordContext
import dev.pampa.fluidweather.testbench.train.TemperatureTrack

/**
 * Cio' che il telefono sa, di suo, a un'emissione: il barometro (puliti i campioni delle ventiquattro
 * ore prima), la normale dei trenta giorni, e le venti feature del v2 senza contesto. Il contesto dei
 * provider e le tabelle di baseline non ci sono: dipendono dal livello, e ogni livello li aggiunge a
 * questo stesso nucleo.
 */
class SimulatedIssue(
  /** L'ancora delle finestre: l'emissione arrotondata per eccesso. */
  val t0Millis: Long,
  /** L'istante di emissione del telefono: `t0` meno il minuto casuale. */
  val issueMillis: Long,
  /** I campioni del telefono, puliti una volta: la parte barometrica e' la stessa per tutti i livelli. */
  val cleaning: CleaningResult,
  /** La normale dei trenta giorni del banco (null nei primi quindici giorni dell'archivio). */
  val normalHpa: Double?,
  /** Le venti feature del v2 col solo barometro. */
  val barometerOnly: DoubleArray,
  /** La tendenza a tre ore che misura il telefono ([TierReplayer.TREND_FEATURE]); null se manca. */
  val trendHpaPerHour: Double?,
  /** La tendenza del filtro di Kalman all'emissione: la legge la vecchia regola barometrica a soglie fisse. */
  val kalmanTrendHpaPerHour: Double?,
)

/**
 * Il telefono finto di una localita': da un `t0` dice cosa avrebbe all'emissione. E' il pezzo che
 * il replay (gate, baseline, classifica) e il costruttore delle righe di addestramento del v3 **devono**
 * condividere: lo stesso barometro sintetico ([SamplingProfile.TELEFONO]), lo stesso minuto di
 * emissione, le stesse regole di scarto (meno di [TierReplayer.MIN_HISTORY_SAMPLES] campioni o meno
 * di 13 ore di storia filtrata: niente verdetto barometrico). Se le due strade divergessero, il modello
 * si addestrerebbe su un telefono che il gate non giudica.
 *
 * Non e' thread-safe (la pipeline di pulizia e' per istanza): un'istanza per thread. E' deterministico:
 * la stessa localita' e lo stesso `t0` danno lo stesso risultato, in qualunque ordine.
 */
class IssueSimulator(
  private val input: LocationInputs,
  causalPressure: Boolean = false,
) {

  private val name = input.location.name
  private val dataset = input.dataset
  private val synthesizer = SampleSynthesizer(dataset, SamplingProfile.TELEFONO, causalPressure = causalPressure)
  private val recordContext = RecordContext(dataset)
  private val temperatures = TemperatureTrack(dataset)
  private val pipeline = CleaningPipeline()
  private val trendIndex = FeatureExtractor.names.indexOf(TierReplayer.TREND_FEATURE).also {
    check(it >= 0) { "la feature '${TierReplayer.TREND_FEATURE}' non e' piu' fra quelle del modello" }
  }

  /** L'istante di emissione di `t0`; l'ancora delle finestre resta `t0`. */
  fun issueMillis(t0Millis: Long): Long {
    val issue = TierScenarios.issueMillis(name, t0Millis)
    check(RainWindows.anchorOf(issue) == t0Millis) { "l'ancora di $name a $t0Millis e' scivolata: emissione $issue" }
    return issue
  }

  /**
   * Il telefono a `t0`; null se non ha un verdetto barometrico: meno di [TierReplayer.MIN_HISTORY_SAMPLES]
   * campioni nelle ventiquattro ore, o meno di 13 ore di storia pulita.
   */
  fun simulate(t0Millis: Long): SimulatedIssue? {
    val issue = issueMillis(t0Millis)
    val samples = TierReplayer.phoneHistory(synthesizer, issue)
    if (samples.size < TierReplayer.MIN_HISTORY_SAMPLES) return null
    val cleaning = pipeline.process(
      samples,
      temperatureCelsius = temperatures.at(issue),
      referenceAltitudeMeters = dataset.elevationMeters,
    )
    val normal = recordContext.normal(issue)
    val barometerOnly = FeatureExtractor.extract(cleaning, null, normal, issue) ?: return null
    return SimulatedIssue(
      t0Millis = t0Millis,
      issueMillis = issue,
      cleaning = cleaning,
      normalHpa = normal,
      barometerOnly = barometerOnly,
      trendHpaPerHour = barometerOnly[trendIndex].takeUnless { it.isNaN() },
      kalmanTrendHpaPerHour = cleaning.latest?.trendHpaPerHour,
    )
  }

  /**
   * Il contesto del livello com'era al fetch (`emissione - eta'`, l'eta' di [TierScenarios]); null nei
   * livelli senza contesto, o quando la riga "adesso" del contesto manca. Il replay scarta l'emissione
   * anche quando la pioggia dell'ultima ora manca: lo decide il chiamante.
   */
  fun context(kind: TierKind, t0Millis: Long, issueMillis: Long): AsOfContext? {
    if (!kind.hasContext) return null
    val age = TierScenarios.contextAgeMillis(kind, name, t0Millis)!!
    return ContextSources.contextAtAge(input.contextReader, issueMillis, age)
  }
}
