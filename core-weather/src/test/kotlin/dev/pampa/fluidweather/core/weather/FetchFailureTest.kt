package dev.pampa.fluidweather.core.weather

import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "Non ci siamo arrivati" e "ha risposto male" sono due cose diverse.
 *
 * Finivano nell'istantanea come lo stesso `error.message` grezzo, quindi la Diagnostica mostrava
 * un DNS che non risolve accanto a un HTTP 500 senza distinguerli — mentre per chi guarda sono
 * opposti: nel primo caso c'e' da aspettare la rete, nel secondo il provider e' giu' e la
 * costellazione deve coprirlo.
 */
class FetchFailureTest {

  @Test
  fun `dns, connessione, tls e timeout sono la rete`() {
    listOf(
      UnknownHostException("api.open-meteo.com"),
      ConnectException("failed to connect"),
      SocketTimeoutException("timeout"),
      SSLHandshakeException("handshake"),
    ).forEach { assertEquals(it.toString(), FetchFailureKind.NETWORK, failureKindOf(it)) }
  }

  @Test
  fun `un IOException generico e' comunque rete`() {
    // Le quattro sopra ne sono sottoclassi: l'ordine dei rami conta, e questo test lo fissa.
    assertEquals(FetchFailureKind.NETWORK, failureKindOf(IOException("stream chiuso")))
  }

  @Test
  fun `un errore del provider resta del provider`() {
    // `EngineHttp` alza `IllegalStateException` sui non-2xx, e i client alzano di tutto sui JSON
    // storti: sono risposte arrivate, non rete mancante.
    assertEquals(FetchFailureKind.PROVIDER, failureKindOf(IllegalStateException("Richiesta non riuscita (500).")))
    assertEquals(FetchFailureKind.PROVIDER, failureKindOf(NumberFormatException("For input string: null")))
  }

  @Test
  fun `il testo della rete si riconosce a colpo d'occhio`() {
    assertTrue(failureTextOf(UnknownHostException("api.open-meteo.com")).startsWith("rete: "))
    assertEquals("Richiesta non riuscita (500).", failureTextOf(IllegalStateException("Richiesta non riuscita (500).")))
  }

  @Test
  fun `un errore senza messaggio non diventa una riga vuota`() {
    // Capita: `NullPointerException()` nudo. Meglio il nome della classe che niente.
    assertEquals("NullPointerException", failureTextOf(NullPointerException()))
    assertTrue(failureTextOf(UnknownHostException()).contains("UnknownHostException"))
  }
}
