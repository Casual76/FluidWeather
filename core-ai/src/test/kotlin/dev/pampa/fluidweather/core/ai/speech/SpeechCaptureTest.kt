package dev.pampa.fluidweather.core.ai.speech

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * I livelli veri, in unita' int16 (quelle su cui lavora il rilevatore). Il test precedente faceva
 * "parlare" a 3.000, cioe' -21 dBFS: un urlo col microfono in bocca. E' per quello che non si era
 * accorto che sul telefono nessuno veniva mai sentito.
 */
private object Rms {
  const val SILENZIO = 33.0 // -60 dBFS
  const val FRUSCIO = 120.0 // -49 dBFS: ventola, respiro
  const val VOCE_BASSA = 190.0 // -45 dBFS: parlare piano a mezzo metro
  const val VOCE_NORMALE = 330.0 // -40 dBFS
  const val VOCE_ALTA = 1_000.0 // -30 dBFS
  const val STANZA_RUMOROSA = 400.0 // -38 dBFS di fondo (bar, auto)
}

/**
 * Un microfono finto che suona una partitura: tratti di silenzio e di "parlato" (seno a un dato RMS).
 * Porta con se' anche l'orologio, perche' la cattura misura il tempo vero e non i frame contati: qui
 * il tempo avanza con i campioni consegnati.
 */
private class ScriptedPcm(private val segments: List<Pair<Long, Double>>, private val sampleRate: Int = 16_000) : PcmSource {
  private var position = 0L
  private val total = segments.sumOf { it.first } * sampleRate / 1000
  var stopped = false

  /** L'orologio della cattura: millisecondi di audio gia' consegnati. */
  val clock: () -> Long = { position * 1000 / sampleRate }

  override fun start(sampleRate: Int): Boolean = true

  override fun read(frame: ShortArray): Int {
    for (i in frame.indices) {
      val t = position + i
      frame[i] = if (t >= total) 0 else sampleAt(t)
    }
    position += frame.size
    return frame.size
  }

  private fun sampleAt(t: Long): Short {
    var offset = 0L
    for ((millis, rms) in segments) {
      val samples = millis * sampleRate / 1000
      if (t < offset + samples) {
        val amplitude = rms * Math.sqrt(2.0)
        return (amplitude * sin(2 * PI * 440.0 * t / sampleRate)).toInt().toShort()
      }
      offset += samples
    }
    return 0
  }

  override fun stop() {
    stopped = true
  }
}

private fun capture(source: ScriptedPcm, config: SpeechCapture.VadConfig = SpeechCapture.VadConfig()) =
  SpeechCapture(source, config, source.clock)

/**
 * Parlato, non un tono continuo: la voce e' fatta di sillabe e di pause, ed e' proprio la modulazione
 * a distinguerla da un rumore di fondo dello stesso livello. Un seno costante non e' parlato per
 * nessun rilevatore basato sull'energia, e chiedere di riconoscerlo sarebbe un test disonesto.
 */
private fun voce(millis: Long, rms: Double, fondo: Double = Rms.SILENZIO): List<Pair<Long, Double>> {
  val battuta = 250L // 180 ms di sillaba, 70 di respiro
  val segmenti = mutableListOf<Pair<Long, Double>>()
  var resto = millis
  while (resto > 0) {
    val suono = minOf(180L, resto)
    segmenti += suono to rms
    resto -= suono
    if (resto <= 0) break
    val pausa = minOf(battuta - 180L, resto)
    segmenti += pausa to fondo
    resto -= pausa
  }
  return segmenti
}

class SpeechCaptureTest {

  private fun target() = File.createTempFile("ask", ".wav").apply { deleteOnExit() }

  @Test
  fun `silenzio e basta - non ho sentito nulla`() = runBlocking<Unit> {
    val source = ScriptedPcm(listOf(5_000L to Rms.SILENZIO))
    val events = capture(source, SpeechCapture.VadConfig(maxDurationMillis = 4_000)).record(target()).toList()
    assertEquals(SpeechCapture.EmptyReason.NOTHING_HEARD, (events.last() as SpeechCapture.Event.Empty).reason)
    assertTrue(source.stopped)
  }

  @Test
  fun `il fruscio della stanza non e' parlato`() = runBlocking<Unit> {
    val source = ScriptedPcm(listOf(5_000L to Rms.FRUSCIO))
    val events = capture(source, SpeechCapture.VadConfig(maxDurationMillis = 4_000)).record(target()).toList()
    assertTrue("il fruscio non deve far partire l'ascolto", events.none { it is SpeechCapture.Event.SpeechStarted })
  }

  @Test
  fun `una voce normale a mezzo metro viene sentita, e l'ascolto finisce da solo`() = runBlocking<Unit> {
    val file = target()
    val source = ScriptedPcm(listOf(400L to Rms.SILENZIO) + voce(2_000L, Rms.VOCE_NORMALE) + listOf(3_000L to Rms.SILENZIO))
    val events = capture(source).record(file).toList()

    val started = events.indexOfFirst { it is SpeechCapture.Event.SpeechStarted }
    assertTrue("parlato non rilevato", started > 0)
    val finished = events.last() as SpeechCapture.Event.Finished
    assertEquals("si deve fermare da solo col silenzio", SpeechCapture.EndReason.SILENCE, finished.reason)
    assertTrue("durata ${finished.durationMillis}", finished.durationMillis in 2_200L..2_900L)

    val bytes = file.readBytes()
    val header = ByteBuffer.wrap(bytes, 0, 44).order(ByteOrder.LITTLE_ENDIAN)
    assertEquals("RIFF", String(bytes, 0, 4, Charsets.US_ASCII))
    assertEquals("WAVE", String(bytes, 8, 4, Charsets.US_ASCII))
    assertEquals(16, header.getInt(16))
    assertEquals(1, header.getShort(20).toInt())
    assertEquals(1, header.getShort(22).toInt())
    assertEquals(16_000, header.getInt(24))
    assertEquals(32_000, header.getInt(28))
    assertEquals(2, header.getShort(32).toInt())
    assertEquals(16, header.getShort(34).toInt())
    assertEquals(bytes.size - 44, header.getInt(40))
    assertEquals(bytes.size - 8, header.getInt(4))
  }

  @Test
  fun `anche chi parla piano viene sentito`() = runBlocking<Unit> {
    // -45 dBFS: il confine dichiarato. Sotto questo livello si e' deciso di non chiamarlo parlato.
    val source = ScriptedPcm(listOf(400L to Rms.SILENZIO) + voce(1_500L, Rms.VOCE_BASSA) + listOf(3_000L to Rms.SILENZIO))
    val events = capture(source).record(target()).toList()
    assertTrue("la voce bassa non e' stata sentita", events.any { it is SpeechCapture.Event.SpeechStarted })
    assertTrue(events.last() is SpeechCapture.Event.Finished)
  }

  @Test
  fun `in una stanza rumorosa serve alzare la voce`() = runBlocking<Unit> {
    val coperta = ScriptedPcm(
      listOf(400L to Rms.STANZA_RUMOROSA) + voce(2_000L, 700.0, Rms.STANZA_RUMOROSA) + listOf(2_000L to Rms.STANZA_RUMOROSA),
    )
    val persa = capture(coperta, SpeechCapture.VadConfig(maxDurationMillis = 4_500)).record(target()).toList()
    assertTrue("sotto il fondo della stanza non e' parlato", persa.none { it is SpeechCapture.Event.SpeechStarted })

    val alzata = ScriptedPcm(
      listOf(400L to Rms.STANZA_RUMOROSA) + voce(2_000L, 1_400.0, Rms.STANZA_RUMOROSA) + listOf(2_000L to Rms.STANZA_RUMOROSA),
    )
    val sentita = capture(alzata, SpeechCapture.VadConfig(maxDurationMillis = 4_500)).record(target()).toList()
    assertTrue(sentita.any { it is SpeechCapture.Event.SpeechStarted })
  }

  @Test
  fun `chi parla subito viene sentito lo stesso`() = runBlocking<Unit> {
    // La taratura misura la voce invece del fondo: il tetto del pavimento tarato esiste per questo.
    val source = ScriptedPcm(voce(1_500L, Rms.VOCE_ALTA) + listOf(2_000L to Rms.SILENZIO))
    val events = capture(source).record(target()).toList()
    assertTrue(events.any { it is SpeechCapture.Event.SpeechStarted })
    assertTrue(events.last() is SpeechCapture.Event.Finished)
  }

  @Test
  fun `fermando a mano si tiene quello che si e' detto`() = runBlocking<Unit> {
    // Il caso visto sul telefono: la voce non supera la soglia, l'utente aspetta, poi tocca per
    // fermare. La versione precedente buttava l'audio e rispondeva "non ho sentito nulla".
    val source = ScriptedPcm(listOf(400L to Rms.SILENZIO, 4_000L to 150.0))
    val capture = capture(source)
    val events = mutableListOf<SpeechCapture.Event>()
    capture.record(target()).collect { event ->
      events += event
      if (event is SpeechCapture.Event.Level && event.elapsedMillis >= 2_000) capture.stopNow()
    }
    val last = events.last()
    assertTrue("atteso l'audio tenuto, avuto $last", last is SpeechCapture.Event.Finished)
    assertEquals(SpeechCapture.EndReason.MANUAL, (last as SpeechCapture.Event.Finished).reason)
  }

  @Test
  fun `una frase brevissima non diventa una domanda`() = runBlocking<Unit> {
    val source = ScriptedPcm(listOf(400L to Rms.SILENZIO, 150L to Rms.VOCE_ALTA, 3_000L to Rms.SILENZIO))
    val events = capture(source, SpeechCapture.VadConfig(maxDurationMillis = 4_000)).record(target()).toList()
    val last = events.last()
    assertTrue("atteso Empty, avuto $last", last is SpeechCapture.Event.Empty)
    assertEquals(SpeechCapture.EmptyReason.TOO_SHORT, (last as SpeechCapture.Event.Empty).reason)
  }

  @Test
  fun `il tetto della durata e lo stop manuale`() = runBlocking<Unit> {
    val lungo = ScriptedPcm(voce(40_000L, Rms.VOCE_NORMALE))
    val capped = capture(lungo, SpeechCapture.VadConfig(maxDurationMillis = 3_000))
      .record(target()).toList().last() as SpeechCapture.Event.Finished
    assertEquals(SpeechCapture.EndReason.MAX_DURATION, capped.reason)
    assertTrue(capped.durationMillis <= 3_000)

    val source = ScriptedPcm(voce(40_000L, Rms.VOCE_NORMALE))
    val capture = capture(source)
    val events = mutableListOf<SpeechCapture.Event>()
    capture.record(target()).collect { event ->
      events += event
      if (event is SpeechCapture.Event.Level && event.elapsedMillis >= 2_000) capture.stopNow()
    }
    assertEquals(SpeechCapture.EndReason.MANUAL, (events.last() as SpeechCapture.Event.Finished).reason)
  }

  @Test
  fun `il microfono che non parte e quello che si perde hanno due motivi diversi`() = runBlocking<Unit> {
    val muto = object : PcmSource {
      override fun start(sampleRate: Int) = false
      override fun read(frame: ShortArray) = -1
      override fun stop() = Unit
      override val failure = MicrophoneFailure.BUSY
    }
    val fallito = SpeechCapture(muto).record(target()).toList().last() as SpeechCapture.Event.Failed
    assertEquals(MicrophoneFailure.BUSY, (fallito.cause as MicrophoneException).failure)

    val perso = object : PcmSource {
      override fun start(sampleRate: Int) = true
      override fun read(frame: ShortArray) = 0
      override fun stop() = Unit
    }
    val evento = SpeechCapture(perso).record(target()).toList().last() as SpeechCapture.Event.Failed
    assertEquals(MicrophoneFailure.LOST, (evento.cause as MicrophoneException).failure)
  }
}
