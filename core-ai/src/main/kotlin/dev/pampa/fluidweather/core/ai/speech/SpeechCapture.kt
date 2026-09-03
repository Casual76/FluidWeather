package dev.pampa.fluidweather.core.ai.speech

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

/** L'unico punto Android della cattura: un microfono che da' campioni PCM 16 bit. */
interface PcmSource {
  /** Vero se il microfono e' partito. */
  fun start(sampleRate: Int): Boolean

  /** Bloccante: quanti campioni ha scritto in [frame]; < 0 = errore, 0 = niente per ora. */
  fun read(frame: ShortArray): Int

  fun stop()

  /** Perche' non si e' potuto registrare, quando [start] o [read] falliscono. */
  val failure: MicrophoneFailure get() = MicrophoneFailure.UNKNOWN
}

/** Cosa dire all'utente quando il microfono non collabora: sono casi diversi con rimedi diversi. */
enum class MicrophoneFailure { UNAVAILABLE, BUSY, LOST, UNKNOWN }

/** Il microfono non ha registrato, e questo e' il motivo. */
class MicrophoneException(val failure: MicrophoneFailure, message: String) : IllegalStateException(message)

/**
 * La cattura vocale con rilevamento del silenzio, su `AudioRecord` (non `MediaRecorder`, che da'
 * solo un picco senza campioni e non si puo' rifilare). PCM 16 kHz mono in frame da 20 ms: dagli
 * stessi frame escono il livello per l'aureola e la decisione di fermarsi. Il WAV si scrive una
 * volta alla fine, gia' tagliato all'ultima parola piu' un margine.
 *
 * Le soglie sono state rifatte il 2026-09-03 dopo la prima prova su un telefono vero, dove la voce
 * non veniva mai riconosciuta. La versione precedente chiedeva **-34 dBFS** (RMS 655) per dichiarare
 * "parlato": e' il livello di una voce alzata a pochi centimetri dal microfono. Una persona che parla
 * normalmente a mezzo metro sta fra 100 e 250 di RMS, cioe' non superava mai la soglia — e siccome
 * la fine per silenzio si valutava solo *dopo* aver sentito parlare, l'ascolto non finiva neanche.
 */
class SpeechCapture(
  private val source: PcmSource,
  private val config: VadConfig = VadConfig(),
  private val clock: () -> Long = System::currentTimeMillis,
) {

  data class VadConfig(
    val sampleRate: Int = 16_000,
    val frameMillis: Int = 20,
    val calibrationMillis: Int = 300,
    /** Quanto sopra il rumore misurato deve stare un frame per contare come parlato. */
    val floorMultiplier: Double = 2.5,
    /**
     * ~ -45 dBFS. Sotto questo nessun frame conta come parlato, per quanto silenziosa sia la stanza:
     * e' il confine sotto cui restano respiro, ventole e fruscio del microfono.
     */
    val speechFloorRms: Double = 180.0,
    /** Il rumore misurato vive fra questi due: sotto e' un microfono muto, sopra non si dialoga. */
    val minFloor: Double = 60.0,
    val maxFloor: Double = 2_000.0,
    /**
     * Tetto del pavimento **misurato in taratura**: -41 dBFS e' gia' un fondo rumoroso, oltre non e'
     * piu' un fondo ma qualcuno che sta parlando. Senza questo tetto, chi comincia a parlare prima
     * che il microfono si sia tarato si alzerebbe la soglia sopra la propria voce e non verrebbe mai
     * sentito. Chi parla piano dentro la taratura viene recuperato dall'inseguitore, che scende in
     * fretta alla prima pausa fra le parole.
     */
    val maxCalibratedFloor: Double = 300.0,
    /**
     * Isteresi. Chi ha gia' cominciato a parlare resta "parlato" fino a questa frazione della soglia
     * d'ingresso: senza, le vocali deboli e le pause fra le parole spezzano il parlato in coriandoli.
     */
    val releaseRatio: Double = 0.6,
    val startFrames: Int = 3,
    val endSilenceMillis: Long = 1_200,
    val maxDurationMillis: Long = 30_000,
    /** Validita' dell'esito, **non** condizione per fermarsi: quella e' solo il silenzio. */
    val minSpeechMillis: Long = 300,
    val minTotalMillis: Long = 600,
    val tailKeepMillis: Long = 300,
  ) {
    val frameSamples: Int get() = sampleRate * frameMillis / 1000
  }

  enum class EndReason { SILENCE, MAX_DURATION, MANUAL }
  enum class EmptyReason { NOTHING_HEARD, TOO_SHORT }

  sealed interface Event {
    data class Level(val level: Float, val speaking: Boolean, val elapsedMillis: Long) : Event
    data object SpeechStarted : Event
    data class Finished(val file: File, val durationMillis: Long, val reason: EndReason) : Event
    data class Empty(val reason: EmptyReason) : Event
    data class Failed(val cause: Throwable) : Event
  }

  private val stopRequested = AtomicBoolean(false)

  /** Tocco: chiude la cattura al prossimo frame. */
  fun stopNow() {
    stopRequested.set(true)
  }

  /** Freddo: parte all'iscrizione, e la cancellazione ferma il microfono e cancella il file. */
  fun record(target: File): Flow<Event> = flow {
    stopRequested.set(false)
    val frame = ShortArray(config.frameSamples)
    val capacity = (config.maxDurationMillis / 1000.0 * config.sampleRate).toInt() + config.frameSamples
    val pcm = ShortArray(capacity)
    var written = 0
    if (!source.start(config.sampleRate)) {
      emit(Event.Failed(MicrophoneException(source.failure, "il microfono non e' partito")))
      return@flow
    }

    val calibration = ArrayList<Double>(config.calibrationMillis / config.frameMillis + 1)
    var floor = config.speechFloorRms / config.floorMultiplier
    var voiced = false
    var speechStarted = false
    var speechStartSample = 0
    var lastSpeechSample = 0
    var lastSpeechAt = 0L
    var peakRms = 0.0
    var run = 0
    var silentReads = 0
    var reason: EndReason? = null
    val startedAt = clock()

    try {
      while (true) {
        val n = source.read(frame)
        if (n < 0) {
          emit(Event.Failed(MicrophoneException(source.failure, "la lettura del microfono e' fallita")))
          return@flow
        }
        if (n == 0) {
          // Un frame vuoto ogni tanto capita; una raffica di frame vuoti vuol dire che il microfono
          // non c'e' piu' (o che qualcun altro se l'e' preso), e restare in ascolto e' inutile.
          if (++silentReads >= EMPTY_READS_LIMIT) {
            emit(Event.Failed(MicrophoneException(MicrophoneFailure.LOST, "il microfono non manda piu' niente")))
            return@flow
          }
          continue
        }
        silentReads = 0

        val copy = min(n, capacity - written)
        System.arraycopy(frame, 0, pcm, written, copy)
        written += copy

        val now = clock()
        val elapsed = now - startedAt
        val rms = rms(frame, n)
        peakRms = max(peakRms, rms)

        if (elapsed <= config.calibrationMillis) {
          // La MEDIANA, non il minimo: i primi frame dopo `startRecording` sono quasi vuoti, e col
          // minimo il pavimento del rumore crollava sempre al valore piu' basso possibile.
          calibration += rms
          floor = median(calibration).coerceIn(config.minFloor, config.maxCalibratedFloor)
        } else if (!speechStarted && rms < floor * config.floorMultiplier * config.releaseRatio) {
          // Insegue il rumore, non la voce: scende in fretta verso il silenzio, sale piano. E si
          // muove solo sui frame chiaramente quieti, altrimenti una voce bassa si alzerebbe la
          // soglia da sola fino a non essere piu' sentita.
          floor = (if (rms < floor) 0.7 * floor + 0.3 * rms else 0.98 * floor + 0.02 * rms)
            .coerceIn(config.minFloor, config.maxFloor)
        }
        val entry = max(floor.coerceIn(config.minFloor, config.maxFloor) * config.floorMultiplier, config.speechFloorRms)
        voiced = if (voiced) rms > entry * config.releaseRatio else rms > entry

        emit(Event.Level(level(rms), voiced, elapsed))

        if (!speechStarted) {
          run = if (voiced) run + 1 else 0
          if (run >= config.startFrames) {
            speechStarted = true
            speechStartSample = max(0, written - config.frameSamples * config.startFrames)
            lastSpeechSample = written
            lastSpeechAt = now
            emit(Event.SpeechStarted)
          }
        } else if (voiced) {
          lastSpeechSample = written
          lastSpeechAt = now
        }

        // Il silenzio ferma l'ascolto e basta: quanto e' durato il parlato lo si giudica dopo. Con la
        // durata minima qui dentro, una frase breve non fermava mai la cattura.
        if (speechStarted && now - lastSpeechAt >= config.endSilenceMillis) reason = EndReason.SILENCE
        if (reason == null && (elapsed >= config.maxDurationMillis || written >= capacity)) reason = EndReason.MAX_DURATION
        if (reason == null && stopRequested.get()) reason = EndReason.MANUAL
        if (reason != null) break
      }
    } finally {
      source.stop()
    }

    val elapsed = clock() - startedAt
    if (!speechStarted) {
      // Fermato a mano dopo aver parlato: il rilevatore puo' non essersene accorto (voce bassa,
      // stanza rumorosa), ma l'audio c'e'. Buttarlo e rispondere "non ho sentito nulla" e' il modo
      // peggiore di sbagliare, ed e' esattamente quello che faceva la versione precedente.
      val audible = max(floor * 1.5, config.speechFloorRms * 0.6)
      val enough = written >= millisToSamples(config.minTotalMillis)
      if (reason == EndReason.MANUAL && enough && peakRms >= audible) {
        speechStartSample = 0
        lastSpeechSample = written
      } else {
        emit(Event.Empty(EmptyReason.NOTHING_HEARD))
        return@flow
      }
    }

    val speechMillis = samplesToMillis(lastSpeechSample - speechStartSample)
    if (speechMillis < config.minSpeechMillis || elapsed < config.minTotalMillis) {
      emit(Event.Empty(EmptyReason.TOO_SHORT))
      return@flow
    }
    val keepSamples = min(written, lastSpeechSample + millisToSamples(config.tailKeepMillis))
    WavWriter.write(target, pcm, keepSamples, config.sampleRate)
    emit(Event.Finished(target, samplesToMillis(keepSamples), reason ?: EndReason.MANUAL))
  }.flowOn(Dispatchers.IO)

  private fun millisToSamples(millis: Long): Int = (millis * config.sampleRate / 1000).toInt()

  private fun samplesToMillis(samples: Int): Long = samples.toLong() * 1000 / config.sampleRate

  private fun median(values: List<Double>): Double {
    if (values.isEmpty()) return config.minFloor
    val sorted = values.sorted()
    val middle = sorted.size / 2
    return if (sorted.size % 2 == 1) sorted[middle] else (sorted[middle - 1] + sorted[middle]) / 2
  }

  private fun rms(frame: ShortArray, n: Int): Double {
    if (n == 0) return 0.0
    var sum = 0.0
    for (i in 0 until n) {
      val s = frame[i].toDouble()
      sum += s * s
    }
    return sqrt(sum / n)
  }

  /** -60 dBFS -> 0, 0 dBFS -> 1: la scala che l'aureola disegna. */
  private fun level(rms: Double): Float = ((20 * log10(max(rms, 1.0) / 32768.0) + 60.0) / 60.0).coerceIn(0.0, 1.0).toFloat()

  private companion object {
    /** Frame vuoti di fila prima di dichiarare perso il microfono: mezzo secondo a 20 ms. */
    const val EMPTY_READS_LIMIT = 25
  }
}

/** WAV PCM 16 bit mono, intestazione di 44 byte little-endian (mai `DataOutputStream`, che e' big-endian). */
object WavWriter {

  fun header(dataSize: Int, sampleRate: Int, channels: Int = 1, bitsPerSample: Int = 16): ByteArray {
    val byteRate = sampleRate * channels * bitsPerSample / 8
    val blockAlign = channels * bitsPerSample / 8
    return ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
      .put("RIFF".toByteArray(Charsets.US_ASCII))
      .putInt(36 + dataSize)
      .put("WAVE".toByteArray(Charsets.US_ASCII))
      .put("fmt ".toByteArray(Charsets.US_ASCII))
      .putInt(16)
      .putShort(1)
      .putShort(channels.toShort())
      .putInt(sampleRate)
      .putInt(byteRate)
      .putShort(blockAlign.toShort())
      .putShort(bitsPerSample.toShort())
      .put("data".toByteArray(Charsets.US_ASCII))
      .putInt(dataSize)
      .array()
  }

  fun write(target: File, pcm: ShortArray, samples: Int, sampleRate: Int) {
    val dataSize = samples * 2
    val buffer = ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN)
    buffer.put(header(dataSize, sampleRate))
    for (i in 0 until samples) buffer.putShort(pcm[i])
    target.parentFile?.mkdirs()
    target.writeBytes(buffer.array())
  }
}
