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

  /** Bloccante: quanti campioni ha scritto in [frame]; < 0 = errore. */
  fun read(frame: ShortArray): Int

  fun stop()
}

/**
 * La cattura vocale con rilevamento del silenzio, su `AudioRecord` (non `MediaRecorder`, che da'
 * solo un picco senza campioni e non si puo' rifilare). PCM 16 kHz mono in frame da 20 ms: dagli
 * stessi frame escono il livello per l'aureola e la decisione di fermarsi. Il WAV si scrive una
 * volta alla fine, gia' tagliato all'ultima parola piu' un margine.
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
    val floorMultiplier: Double = 3.0,
    /** ~ -34 dBFS in unita' int16: sotto, non e' parlato anche in una stanza silenziosa. */
    val absoluteThreshold: Double = 655.0,
    /** ~ -48 dBFS: un pavimento piu' basso scambierebbe il respiro per parlato. */
    val minFloor: Double = 130.0,
    val startFrames: Int = 3,
    val minSpeechMillis: Long = 500,
    val endSilenceMillis: Long = 1_200,
    val maxDurationMillis: Long = 30_000,
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
      emit(Event.Failed(IllegalStateException("microfono non disponibile")))
      return@flow
    }
    var floor = Double.MAX_VALUE
    var calibrationMin = Double.MAX_VALUE
    var speechStarted = false
    var speechStartMillis = 0L
    var lastSpeechMillis = 0L
    var run = 0
    var frames = 0
    var reason: EndReason? = null
    try {
      while (true) {
        val n = source.read(frame)
        if (n <= 0) {
          emit(Event.Failed(IllegalStateException("lettura del microfono fallita")))
          return@flow
        }
        val copy = min(n, capacity - written)
        System.arraycopy(frame, 0, pcm, written, copy)
        written += copy
        frames++
        val elapsed = frames.toLong() * config.frameMillis
        val rms = rms(frame, n)
        if (elapsed <= config.calibrationMillis) {
          calibrationMin = min(calibrationMin, rms)
          floor = (calibrationMin * 1.5).coerceIn(config.minFloor, config.absoluteThreshold)
        } else if (!speechStarted) {
          floor = (0.95 * floor + 0.05 * rms).coerceIn(config.minFloor, config.absoluteThreshold)
        }
        val threshold = max(floor * config.floorMultiplier, config.absoluteThreshold)
        val isSpeech = rms > threshold
        emit(Event.Level(level(rms), isSpeech, elapsed))
        if (!speechStarted) {
          run = if (isSpeech) run + 1 else 0
          if (run >= config.startFrames) {
            speechStarted = true
            speechStartMillis = elapsed - config.startFrames * config.frameMillis
            lastSpeechMillis = elapsed
            emit(Event.SpeechStarted)
          }
        } else {
          if (isSpeech) lastSpeechMillis = elapsed
          else if (elapsed - lastSpeechMillis >= config.endSilenceMillis && lastSpeechMillis - speechStartMillis >= config.minSpeechMillis) reason = EndReason.SILENCE
        }
        if (reason == null && elapsed >= config.maxDurationMillis) reason = EndReason.MAX_DURATION
        if (reason == null && stopRequested.get()) reason = EndReason.MANUAL
        if (reason != null) break
      }
    } finally {
      source.stop()
    }
    val elapsed = frames.toLong() * config.frameMillis
    if (!speechStarted) {
      emit(Event.Empty(EmptyReason.NOTHING_HEARD))
      return@flow
    }
    if (lastSpeechMillis - speechStartMillis < config.minSpeechMillis || elapsed < config.minTotalMillis) {
      emit(Event.Empty(EmptyReason.TOO_SHORT))
      return@flow
    }
    val keepMillis = min(elapsed, lastSpeechMillis + config.tailKeepMillis)
    val keepSamples = min(written, (keepMillis / 1000.0 * config.sampleRate).toInt())
    WavWriter.write(target, pcm, keepSamples, config.sampleRate)
    emit(Event.Finished(target, keepMillis, reason ?: EndReason.MANUAL))
  }.flowOn(Dispatchers.IO)

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
