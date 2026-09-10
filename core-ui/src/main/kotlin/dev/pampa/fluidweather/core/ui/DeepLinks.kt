package dev.pampa.fluidweather.core.ui

import android.net.Uri

/**
 * Le schermate dell'app raggiungibili da fuori. I widget della home stanno in [HomeWidget]: qui
 * ci sono solo le rotte vere, quelle che aprono una pagina invece di una tessera.
 *
 * Gli id sono stringhe stabili come quelli di [HomeWidget], per la stessa ragione: finiscono
 * dentro `PendingIntent` gia' consegnati al launcher e dentro le richieste di un'altra app.
 */
enum class AppScreen(val id: String) {
  RADAR("radar"),
  BENCHMARK("benchmark"),
  REPORT("report"),
  SETTINGS("settings"),
  AI_SETTINGS("settings-ai"),
  ;

  companion object {
    fun fromId(id: String?): AppScreen? = entries.firstOrNull { it.id == id }
  }
}

/** Dove porta una richiesta arrivata da fuori: una tessera della home o una pagina. */
sealed interface AppDestination {
  data class Widget(val widget: HomeWidget) : AppDestination
  data class Screen(val screen: AppScreen) : AppDestination
}

/**
 * Una destinazione chiesta, con il suo numero di serie.
 *
 * Il numero non e' burocrazia: chi la consuma la osserva con un `LaunchedEffect`, che riparte solo
 * quando il valore **cambia**. Senza, toccare due volte di fila lo stesso bersaglio del widget
 * scriverebbe lo stesso valore e la seconda volta non succederebbe niente — un widget che sembra
 * rotto.
 */
data class AppRequest(val destination: AppDestination, val nonce: Long)

/**
 * L'unico vocabolario dei collegamenti che entrano nell'app: il widget di sistema, l'assistente
 * federato (PampAI/Aria), e domani chiunque altro.
 *
 * Prima ce n'erano due che non si conoscevano — il widget scriveva `.../widget/nowcast`, il ponte
 * dell'assistente `.../open/orario` — e [dev.pampa.fluidweather.core.ui.HomeWidget] ne capiva
 * due parole in tutto: ogni "apri" dell'assistente tranne il nowcast finiva sulla home nuda.
 * Un vocabolario solo, scritto e letto dalla stessa funzione, non puo' divergere.
 *
 * **I bersagli si distinguono per Uri, non per extra**: l'uguaglianza dei `PendingIntent` ignora
 * gli extra, quindi due destinazioni che differissero solo per quelli collasserebbero in una e il
 * sistema consegnerebbe sempre la prima registrata.
 */
object AppDeepLink {

  const val SCHEME: String = "fluidweather"

  const val HOST: String = "open"

  fun uri(destination: AppDestination): Uri = Uri.parse("$SCHEME://$HOST/${idOf(destination)}")

  fun idOf(destination: AppDestination): String = when (destination) {
    is AppDestination.Widget -> destination.widget.id
    is AppDestination.Screen -> destination.screen.id
  }

  /**
   * Cosa chiede questo Uri, se chiede qualcosa.
   *
   * Si guarda l'ultimo segmento e non l'intero percorso perche' il vecchio widget scriveva
   * `fluidweather://widget/<id>`: le sue destinazioni sono gia' in mano al launcher, e continuano
   * a funzionare finche' il sistema non le rigenera.
   */
  fun parse(uri: Uri?): AppDestination? {
    if (uri == null) return null
    if (uri.scheme != null && uri.scheme != SCHEME) return null
    val id = uri.lastPathSegment?.trim()?.lowercase() ?: return null
    HomeWidget.entries.firstOrNull { it.id == id }?.let { return AppDestination.Widget(it) }
    AppScreen.fromId(id)?.let { return AppDestination.Screen(it) }
    return null
  }
}
