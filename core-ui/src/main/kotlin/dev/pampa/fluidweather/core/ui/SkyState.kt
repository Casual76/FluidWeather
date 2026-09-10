package dev.pampa.fluidweather.core.ui

import dev.pampa.fluidweather.core.model.DayPhase
import dev.pampa.fluidweather.core.model.WeatherKind

/*
 * Lo stato del cielo, in un file senza una riga di Android.
 *
 * Stava dentro WeatherScene.kt, accanto alla composable: ma i pittori (SkyPainters.kt) e questo
 * stato devono poter compilare anche sul computer, dentro il banco di anteprima in tools/skypreview,
 * che disegna gli stessi fotogrammi con Compose Desktop per poterli GUARDARE prima di spedirli.
 */

/** Quanta scena ci si puo' permettere: deciso dal vetro adattivo, non dalla scena stessa. */
enum class SceneQuality { FULL, REDUCED, STATIC }

data class SkyState(
  val phase: DayPhase,
  val kind: WeatherKind?,
  val cloudCoverPercent: Double?,
  /**
   * Dove sei, per sapere dove sta il sole e se c'e' la luna. Null = si ripiega sull'orologio,
   * come gia' fa la fase del giorno quando la posizione non e' ancora arrivata.
   */
  val latitude: Double? = null,
  val longitude: Double? = null,
)
