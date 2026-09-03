package dev.pampa.fluidweather.core.ai.speech

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Process

/**
 * `AudioRecord` con la sorgente per il riconoscimento vocale (l'OEM la tara per il parlato), il
 * soppressore di rumore e il controllo di guadagno dove ci sono, e il thread di lettura alla
 * priorita' audio. Il permesso RECORD_AUDIO lo controlla chi chiama: qui si presume concesso.
 */
class AndroidPcmSource : PcmSource {

  private var record: AudioRecord? = null
  private var suppressor: NoiseSuppressor? = null
  private var gain: AutomaticGainControl? = null

  @SuppressLint("MissingPermission")
  override fun start(sampleRate: Int): Boolean {
    val minBuffer = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
    if (minBuffer <= 0) return false
    val buffer = maxOf(minBuffer, sampleRate * 2)
    val recorder = runCatching {
      AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, buffer)
    }.getOrNull() ?: return false
    if (recorder.state != AudioRecord.STATE_INITIALIZED) {
      recorder.release()
      return false
    }
    runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO) }
    if (NoiseSuppressor.isAvailable()) suppressor = runCatching { NoiseSuppressor.create(recorder.audioSessionId)?.apply { enabled = true } }.getOrNull()
    if (AutomaticGainControl.isAvailable()) gain = runCatching { AutomaticGainControl.create(recorder.audioSessionId)?.apply { enabled = true } }.getOrNull()
    recorder.startRecording()
    if (recorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
      recorder.release()
      return false
    }
    record = recorder
    return true
  }

  override fun read(frame: ShortArray): Int = record?.read(frame, 0, frame.size) ?: -1

  override fun stop() {
    runCatching { record?.stop() }
    runCatching { record?.release() }
    runCatching { suppressor?.release() }
    runCatching { gain?.release() }
    record = null
    suppressor = null
    gain = null
  }
}
