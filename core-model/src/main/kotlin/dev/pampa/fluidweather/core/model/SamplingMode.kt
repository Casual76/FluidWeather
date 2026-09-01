package dev.pampa.fluidweather.core.model

/**
 * Le quattro ancore dello slider di accuratezza, come da piano.
 *
 * MINIMA campiona piu' spesso di RISPARMIO ma senza raffica: una lettura secca ogni 20 minuti
 * costa ~nulla, mentre i 15 secondi di raffica di RISPARMIO comprano una mediana vera al prezzo
 * di accendere il sensore piu' a lungo. Sono due compromessi diversi, non una scala monotona.
 *
 * MASSIMA sta sotto il minimo periodico di WorkManager (15 min): la implementa una catena di
 * allarmi esatti, e in Doze profondo il ritmo cala — l'app lo dichiara invece di fingere.
 */
enum class SamplingMode(
  val cadenceMinutes: Int,
  /** 0 = lettura singola, niente raffica. */
  val burstSeconds: Int,
  /** Se la marcia di sorveglianza puo' accendersi quando la tendenza supera la soglia. */
  val surveillanceCapable: Boolean,
) {
  MASSIMA(cadenceMinutes = 5, burstSeconds = 30, surveillanceCapable = true),
  BILANCIATA(cadenceMinutes = 15, burstSeconds = 30, surveillanceCapable = true),
  RISPARMIO(cadenceMinutes = 30, burstSeconds = 15, surveillanceCapable = false),
  MINIMA(cadenceMinutes = 20, burstSeconds = 0, surveillanceCapable = false),
}

/** La raffica manuale: ~300 campioni a 1 Hz separano 0,08 hPa di caduta vera dal rumore. */
object ManualBurst {
  const val DURATION_SECONDS: Int = 5 * 60
  const val HZ: Int = 1
}
