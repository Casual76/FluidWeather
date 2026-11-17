package dev.pampa.fluidweather.core.data

import java.io.IOException
import kotlinx.coroutines.CancellationException

/** Attempts every independent store, then reports incomplete deletion to the caller. */
suspend fun clearUserData(vararg stores: suspend () -> Unit) {
  var failure: IOException? = null
  for (clear in stores) {
    try {
      clear()
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (error: Exception) {
      val incomplete = failure ?: IOException("Could not delete all local data").also { failure = it }
      incomplete.addSuppressed(error)
    }
  }
  failure?.let { throw it }
}
