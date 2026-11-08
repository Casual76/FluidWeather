package dev.pampa.fluidweather.core.sensor

import dev.pampa.fluidweather.core.data.LatestActivityStore
import dev.pampa.fluidweather.core.data.PressureRepository
import dev.pampa.fluidweather.core.data.SamplingSettingsStore
import dev.pampa.fluidweather.core.model.ActivityKind
import dev.pampa.fluidweather.core.model.PressureSample
import dev.pampa.fluidweather.core.model.PressureTrend
import dev.pampa.fluidweather.core.model.SampleSource
import dev.pampa.fluidweather.nowcast.cleaning.CleaningPipeline
import java.util.UUID
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Un giro di campionamento completo: leggi, arricchisci, archivia. E' l'unico pezzo che worker,
 * allarmi, servizio di sorveglianza e raffica manuale condividono, cosi' un campione e' identico
 * qualunque strada l'abbia raccolto.
 */
class SamplingEngine(
  private val barometer: Barometer,
  private val locationProvider: LocationProvider,
  private val activityStore: LatestActivityStore,
  /**
   * Chi misura il movimento (senza Google Play Services): a ogni raffica, mentre il fix arriva, si
   * guarda se il telefono si muove. Null nei test che non hanno sensori: resta l'ultima attivita'.
   */
  private val activityRecognizer: ActivityRecognizer? = null,
  private val repository: PressureRepository,
  private val settingsStore: SamplingSettingsStore,
  private val surveillance: SurveillanceController,
  private val cleaningPipeline: CleaningPipeline,
  /**
   * Cosa succede DOPO ogni passata: il ciclo in background (giro meteo, verdetto, notifiche)
   * si aggancia qui, cosi' il meteo si aggiorna al ritmo del barometro. Un errore li' dentro
   * non tocca il campionamento, che e' gia' in archivio.
   */
  private val afterPass: suspend (SampleSource) -> Unit = {},
  /**
   * La tendenza pulita di chi la sa calcolare come si deve — con la taratura e la quota di casa.
   * Il default resta il calcolo locale, che pero' usa i valori di default della pipeline: era il
   * terzo dei tre segnali puliti diversi che l'app teneva in giro, e la soglia della sorveglianza
   * ci si accendeva sopra.
   */
  private val cleanTrend: (suspend () -> Double?)? = null,
) {

  private val collectionMutex = Mutex()

  /** Senza sensore niente raffiche: chi vuole tarare lo sa prima di partire. */
  val barometerAvailable: Boolean get() = barometer.isAvailable

  /** Il giro periodico: raffica o lettura secca secondo la modalita', poi il cambio di marcia. */
  suspend fun runScheduledPass() {
    val mode = settingsStore.current().mode
    scheduledSensorPass(
      available = barometer.isAvailable,
      collect = { collect(source = SampleSource.PERIODIC, durationSeconds = mode.burstSeconds) },
      completed = {
        if (mode.surveillanceCapable && PressureTrend.callsForSurveillance(currentTrend())) {
          surveillance.start()
        }
        notifyPassCompleted(SampleSource.PERIODIC)
      },
    )
  }

  /**
   * Il gancio per chi campiona per conto suo: errori isolati, cancellazione propagata.
   *
   * [source] dice **chi** ha campionato. Serve perche' il ciclo lo racconti per quello che e':
   * la sorveglianza passava di qui ogni minuto e arrivava travestita da passata periodica, quindi
   * la nota della Diagnostica diceva la cosa sbagliata. (La difesa dai giri troppo frequenti non
   * dipende piu' da questa etichetta — vedi `RefreshBudget` — ma raccontare il falso resta falso.)
   */
  suspend fun notifyPassCompleted(source: SampleSource = SampleSource.PERIODIC) {
    optionalContext { afterPass(source) }
  }

  /**
   * Registra letture per [durationSeconds] (0 = una sola) e restituisce quante ne ha archiviate.
   * Ogni lettura e' inserita appena arriva: una raffica interrotta a meta' lascia in archivio
   * la meta' che esiste.
   *
   * [burstId] si passa da fuori quando piu' chiamate devono comporre **una sola** raffica: e' il
   * caso della taratura, che puo' allungarsi a tratti finche' non ha abbastanza tempo fermo e poi
   * deve poter ritrovare tutti i propri campioni con una query sola.
   *
   * [contextRefreshSeconds] rilegge posizione e attivita' *dentro* la raffica. Vedi il commento
   * sul contesto qui sotto: e' l'eccezione, non la regola.
   */
  suspend fun collect(
    source: SampleSource,
    durationSeconds: Int,
    burstId: String? = null,
    contextRefreshSeconds: Int? = null,
    onProgress: ((completedSeconds: Int, totalSeconds: Int) -> Unit)? = null,
  ): Int = collectionMutex.withLock {
    coroutineScope {
      if (!barometer.isAvailable) return@coroutineScope 0
      val locationDeferred = async { optionalContext { withTimeoutOrNull(10_000L) { locationProvider.snapshot() } } }
      // Il movimento si misura mentre il fix arriva: i due tempi si sovrappongono, e poi la velocita'
      // che il fix porta con se' (o la media dal fix precedente) entra nel giudizio.
      val motionDeferred = async { measureMotion() }
      var context = BurstContext(location = null, activity = freshActivity())
      var stored = 0
      if (durationSeconds <= 0) {
        val reading = barometer.single() ?: run {
          locationDeferred.cancel()
          motionDeferred.cancel()
          return@coroutineScope 0
        }
        val location = locationDeferred.await()
        context = context.copy(activity = judgedActivity(motionDeferred.await(), location))
        repository.record(listOf(reading.toSample(source, burstId = null, location, context.activity)))
        stored = 1
      } else {
        val id = burstId ?: UUID.randomUUID().toString()
        // La posizione prima della raffica: costa qualche secondo di attesa, ma tutti i campioni
        // della raffica condividono lo stesso contesto invece di un contesto arrivato a meta'.
        //
        // **Vale finche' la raffica dura venti secondi.** Per i dieci minuti della taratura no: chi
        // cammina, o guida, in quei minuti cambia quota e paese, e ogni campione finirebbe in
        // archivio alla quota di partenza. Non un dato impreciso — un dato *falso*, e nessuna
        // matematica a valle puo' rimediare a un contesto che non e' mai stato registrato.
        val firstFix = locationDeferred.await()
        context = context.copy(location = firstFix, activity = judgedActivity(motionDeferred.await(), firstFix))
        val refresher = contextRefreshSeconds?.takeIf { it > 0 }?.let { seconds ->
          launch {
            while (true) {
              delay(seconds * 1_000L)
              val motion = async { measureMotion() }
              val fix = optionalContext { withTimeoutOrNull(10_000L) { locationProvider.snapshot() } } ?: context.location
              context = BurstContext(location = fix, activity = judgedActivity(motion.await(), fix))
            }
          }
        }
        try {
          withTimeoutOrNull((durationSeconds + BURST_GRACE_SECONDS) * 1_000L) {
            barometer.burst(durationSeconds).collect { reading ->
              val now = context
              repository.record(listOf(reading.toSample(source, id, now.location, now.activity)))
              stored++
              onProgress?.invoke(stored, durationSeconds)
            }
          }
        } finally {
          refresher?.cancel()
        }
      }
      stored
    }
  }

  /** Posizione e attivita' di questo momento della raffica. */
  private data class BurstContext(
    val location: LocationSnapshot?,
    val activity: Pair<ActivityKind, Int?>,
  )

  /**
   * La tendenza *pulita* delle ultime tre ore: gli stadi 1-2 mangiano ascensori, viaggi e
   * raffiche impazzite prima che diventino un falso cambio di marcia. Tre ore perche' e' la
   * finestra su cui parlano le soglie della letteratura (1,6 e 3-4 hPa/3h).
   */
  suspend fun currentTrend(): Double? {
    cleanTrend?.let { return optionalContext { it() } }
    val samples = repository.samplesSince(System.currentTimeMillis() - TREND_WINDOW_MILLIS)
    return cleaningPipeline.process(samples).latest?.trendHpaPerHour
  }

  /** Una misura del movimento; un errore dei sensori non ferma il campionamento. */
  private suspend fun measureMotion(): MotionFeatures? {
    val recognizer = activityRecognizer ?: return null
    return try {
      recognizer.measure()
    } catch (cancelled: kotlinx.coroutines.CancellationException) {
      throw cancelled
    } catch (_: Exception) {
      // Un sensore che tace non e' un campione perso: l'attivita' resta l'ultima nota (o sconosciuta).
      null
    }
  }

  /** Giudica la misura con la velocita' del fix, la scrive, e rilegge l'attivita' fresca com'e' in archivio. */
  private suspend fun judgedActivity(motion: MotionFeatures?, location: LocationSnapshot?): Pair<ActivityKind, Int?> {
    val recognizer = activityRecognizer
    if (recognizer != null && motion != null) {
      try {
        recognizer.record(motion, location?.speedMetersPerSecond)
      } catch (cancelled: kotlinx.coroutines.CancellationException) {
        throw cancelled
      } catch (_: Exception) {
        // L'archivio dell'attivita' non risponde: vale l'ultima nota.
      }
    }
    return freshActivity()
  }

  private suspend fun freshActivity(): Pair<ActivityKind, Int?> {
    val latest = activityStore.current() ?: return ActivityKind.UNKNOWN to null
    val stale = System.currentTimeMillis() - latest.observedAtMillis > ACTIVITY_FRESHNESS_MILLIS
    return if (stale) ActivityKind.UNKNOWN to null else latest.kind to latest.confidence
  }

  private fun TimedReading.toSample(
    source: SampleSource,
    burstId: String?,
    location: LocationSnapshot?,
    activity: Pair<ActivityKind, Int?>,
  ) = PressureSample(
    timestampMillis = timestampMillis,
    pressureHpa = pressureHpa,
    source = source,
    burstId = burstId,
    altitudeMeters = location?.altitudeMeters,
    latitude = location?.latitude,
    longitude = location?.longitude,
    activity = activity.first,
    activityConfidence = activity.second,
  )

  private companion object {
    /** Un riconoscimento piu' vecchio di cosi' non descrive piu' questo campione. */
    const val ACTIVITY_FRESHNESS_MILLIS = 10 * 60_000L

    /** La finestra della tendenza per il cambio di marcia. */
    const val TREND_WINDOW_MILLIS = 3 * 60 * 60_000L

    /** Margine oltre la durata nominale prima di considerare il sensore ammutolito. */
    const val BURST_GRACE_SECONDS = 10
  }
}
