package dev.pampa.fluidweather.core.ai.net

/** Cosa gli header di un provider dicono sui limiti: tutto opzionale, Gemini non ne manda nessuno. */
data class RateLimitInfo(
  val limitRequests: Int? = null,
  val remainingRequests: Int? = null,
  val limitTokens: Int? = null,
  val remainingTokens: Int? = null,
  val resetRequestsSec: Double? = null,
  val resetTokensSec: Double? = null,
) {
  companion object {
    val EMPTY = RateLimitInfo()
  }
}

/**
 * Gli errori di un provider IA, ridotti a quelli su cui l'orchestratore decide qualcosa: aspettare,
 * passare a un altro provider, riprovare, o dirlo all'utente. Il messaggio grezzo resta per la
 * diagnostica; la frase per l'utente la sceglie la UI in base al tipo.
 */
sealed class AiError(message: String, cause: Throwable? = null) : Exception(message, cause) {
  /** 401/403, o il 400 di Gemini "API key not valid": una chiave sbagliata non si maschera con un ripiego. */
  class Unauthorized(message: String) : AiError(message)

  class RateLimited(
    val retryAfterSec: Double?,
    val rateLimit: RateLimitInfo,
    /** Vero per il tetto giornaliero dei modelli gratuiti di OpenRouter: merita una frase sua. */
    val freeModelCap: Boolean = false,
    message: String,
  ) : AiError(message)

  class Server(val code: Int, message: String) : AiError(message)
  class BadRequest(val code: Int, message: String) : AiError(message)
  class Network(message: String, cause: Throwable? = null) : AiError(message, cause)
  class Timeout(message: String, cause: Throwable? = null) : AiError(message, cause)

  /** Un 2xx che non si capisce: JSON rotto, struttura inattesa. */
  class Parse(message: String, cause: Throwable? = null) : AiError(message, cause)
}
