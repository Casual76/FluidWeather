package dev.pampa.fluidweather.feature.settings

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckPolicyTest {

  private val hour = 3_600_000L

  @Test
  fun `il primo controllo parte sempre, poi si aspetta l'intervallo`() {
    val policy = UpdateCheckPolicy(recheckAfterMillis = 6 * hour)
    assertTrue(policy.shouldCheck(nowMillis = 0L))
    policy.markAttempt(0L)
    assertFalse(policy.shouldCheck(nowMillis = 5 * hour))
    assertTrue(policy.shouldCheck(nowMillis = 6 * hour))
  }

  @Test
  fun `piu tardi vale solo per quella versione`() {
    val policy = UpdateCheckPolicy()
    policy.defer("1.4.2")
    assertTrue(policy.isDeferred("1.4.2"))
    assertFalse(policy.isDeferred("1.4.3"))
  }

  @Test
  fun `un controllo fallito si ripete e il primo esito buono vince`() = runBlocking {
    val policy = UpdateCheckPolicy(retryDelaysMillis = longArrayOf(0L, 0L))
    var attempts = 0
    val found = policy.retrying {
      attempts++
      if (attempts < 3) Result.failure(IllegalStateException("rete non pronta")) else Result.success("1.4.3")
    }
    assertEquals("1.4.3", found)
    assertEquals(3, attempts)
  }

  @Test
  fun `dopo l'ultimo tentativo fallito resta il silenzio`() = runBlocking {
    val policy = UpdateCheckPolicy(retryDelaysMillis = longArrayOf(0L))
    var attempts = 0
    val found = policy.retrying<String> {
      attempts++
      Result.failure(IllegalStateException("niente rete"))
    }
    assertNull(found)
    assertEquals(2, attempts)
  }

  @Test
  fun `nessun aggiornamento e un esito, non un fallimento`() = runBlocking {
    val policy = UpdateCheckPolicy(retryDelaysMillis = longArrayOf(0L, 0L))
    var attempts = 0
    val found = policy.retrying<String> {
      attempts++
      Result.success(null)
    }
    assertNull(found)
    assertEquals(1, attempts)
  }
}
