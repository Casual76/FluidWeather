package dev.pampa.fluidweather.core.ai.speech

import dev.pampa.fluidweather.core.ai.net.AiError
import dev.pampa.fluidweather.core.ai.provider.ProviderFactory
import dev.pampa.fluidweather.core.ai.provider.ProviderId
import dev.pampa.fluidweather.core.ai.provider.ReadyProvider
import dev.pampa.fluidweather.core.ai.provider.TranscribeOptions
import java.io.File
import kotlinx.coroutines.CancellationException

/** Chi ha trascritto e cosa. */
data class Transcription(val text: String, val provider: ProviderId, val model: String)

/**
 * La trascrizione segue l'ordine dei provider scelto per la voce: il primo con chiave prova, i
 * successivi sono riserva su 429, 5xx e rete. La lingua e' quella dell'app (it/en), forzata:
 * piu' precisa dell'auto-rilevamento sulle frasi corte.
 */
class Transcriber(private val providers: suspend () -> List<ReadyProvider>) {

  suspend fun transcribe(audio: File, language: String, hint: String?): Transcription {
    val ordered = providers()
    if (ordered.isEmpty()) throw AiError.Unauthorized("nessun provider per la trascrizione")
    var last: Throwable? = null
    for (ready in ordered) {
      try {
        val transcript = ready.provider.transcribe(audio, "audio/wav", TranscribeOptions(ready.sttModel, language, hint))
        return Transcription(transcript.text.trim(), ready.provider.id, ready.sttModel)
      } catch (e: CancellationException) {
        throw e
      } catch (e: AiError.Unauthorized) {
        throw e
      } catch (e: Throwable) {
        last = e
      }
    }
    throw last ?: AiError.Network("trascrizione fallita")
  }

  companion object {
    /** Il suggerimento di vocabolario per Whisper: il dominio e i posti dell'utente. */
    fun hint(language: String, savedPlaces: List<String>): String {
      val base = if (language == "it") {
        "Domande sul meteo: pioggia, neve, radar, previsioni, barometro, pressione, allerte, temperatura, vento, umidita', localita' italiane."
      } else {
        "Questions about the weather: rain, snow, radar, forecast, barometer, pressure, alerts, temperature, wind, humidity, place names."
      }
      val places = savedPlaces.take(12).joinToString(", ")
      return (if (places.isEmpty()) base else "$base $places.").take(800)
    }
  }
}
