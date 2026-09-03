package dev.pampa.fluidweather.core.ai.speech

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Process

/**
 * `AudioRecord` con la sorgente per il riconoscimento vocale (l'OEM la tara per il parlato), il
 * soppressore di rumore e il controllo di guadagno dove ci sono, e il thread di lettura alla
 * priorita' audio. Il permesso RECORD_AUDIO lo controlla chi chiama: qui si presume concesso.
 *
 * Chiede anche il **fuoco audio**: senza, una musica in riproduzione continua a suonare dentro al
 * microfono, e la domanda finisce nel mezzo di una canzone. Con il fuoco, il sistema abbassa o
 * mette in pausa l'altra app per la durata dell'ascolto e poi la restituisce com'era.
 */
class AndroidPcmSource(private val context: Context? = null) : PcmSource {

  private var record: AudioRecord? = null
  private var suppressor: NoiseSuppressor? = null
  private var gain: AutomaticGainControl? = null
  private var focus: AudioFocusRequest? = null
  private var previousPriority: Int? = null

  override var failure: MicrophoneFailure = MicrophoneFailure.UNKNOWN
    private set

  @SuppressLint("MissingPermission")
  override fun start(sampleRate: Int): Boolean {
    failure = MicrophoneFailure.UNKNOWN
    val minBuffer = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
    if (minBuffer <= 0) {
      failure = MicrophoneFailure.UNAVAILABLE
      return false
    }
    val buffer = maxOf(minBuffer, sampleRate * 2)
    val recorder = runCatching {
      AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, buffer)
    }.getOrNull()
    if (recorder == null) {
      failure = MicrophoneFailure.UNAVAILABLE
      return false
    }
    if (recorder.state != AudioRecord.STATE_INITIALIZED) {
      recorder.release()
      failure = MicrophoneFailure.UNAVAILABLE
      return false
    }
    requestFocus()
    // La priorita' si alza sul thread che legge, e si rimette com'era in `stop`: e' un thread del
    // pool IO, e lasciarlo a priorita' audio urgente per sempre condiziona tutto quello che ci passa.
    previousPriority = runCatching { Process.getThreadPriority(Process.myTid()) }.getOrNull()
    runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO) }
    if (NoiseSuppressor.isAvailable()) suppressor = runCatching { NoiseSuppressor.create(recorder.audioSessionId)?.apply { enabled = true } }.getOrNull()
    if (AutomaticGainControl.isAvailable()) gain = runCatching { AutomaticGainControl.create(recorder.audioSessionId)?.apply { enabled = true } }.getOrNull()
    runCatching { recorder.startRecording() }
    if (recorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
      // Il microfono c'e' ma non parte: quasi sempre e' un'altra app che lo tiene (una chiamata,
      // un registratore, un assistente di sistema).
      recorder.release()
      abandonFocus()
      restorePriority()
      failure = MicrophoneFailure.BUSY
      return false
    }
    record = recorder
    return true
  }

  override fun read(frame: ShortArray): Int {
    val n = record?.read(frame, 0, frame.size) ?: return -1
    if (n >= 0) return n
    failure = when (n) {
      AudioRecord.ERROR_DEAD_OBJECT -> MicrophoneFailure.LOST
      AudioRecord.ERROR_INVALID_OPERATION -> MicrophoneFailure.BUSY
      else -> MicrophoneFailure.UNKNOWN
    }
    return -1
  }

  override fun stop() {
    runCatching { record?.stop() }
    runCatching { record?.release() }
    runCatching { suppressor?.release() }
    runCatching { gain?.release() }
    record = null
    suppressor = null
    gain = null
    abandonFocus()
    restorePriority()
  }

  private fun requestFocus() {
    val manager = context?.getSystemService(AudioManager::class.java) ?: return
    val attributes = AudioAttributes.Builder()
      .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
      .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
      .build()
    val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
      .setAudioAttributes(attributes)
      .setOnAudioFocusChangeListener { }
      .build()
    runCatching { manager.requestAudioFocus(request) }
    focus = request
  }

  private fun abandonFocus() {
    val manager = context?.getSystemService(AudioManager::class.java)
    val request = focus ?: return
    runCatching { manager?.abandonAudioFocusRequest(request) }
    focus = null
  }

  private fun restorePriority() {
    val previous = previousPriority ?: return
    runCatching { Process.setThreadPriority(previous) }
    previousPriority = null
  }
}
