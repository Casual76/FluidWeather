package dev.pampa.fluidweather.core.cycle

import android.app.Application
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

class SummaryWorkerTestApplication : Application(), CycleRuntime {
  override val backgroundCycle: BackgroundCycle get() = error("Worker must use durable summary boundary")
  var requested: Long? = null
  var delivery: suspend () -> Boolean = { true }
  override suspend fun rescheduleDailySummary() = Unit
  override suspend fun runDailySummary(dueAtMillis: Long): Boolean {
    requested = dueAtMillis
    return delivery()
  }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = SummaryWorkerTestApplication::class)
class DailySummaryWorkerTest {
  private lateinit var app: SummaryWorkerTestApplication
  @Before fun prepare() {
    app = RuntimeEnvironment.getApplication() as SummaryWorkerTestApplication
    app.requested = null
    app.delivery = { true }
  }
  private fun worker(due: Long = 1234L) = TestListenableWorkerBuilder<DailySummaryWorker>(app)
    .setInputData(workDataOf(DailySummaryWork.DUE_AT to due)).build()

  @Test fun `worker passes scheduled occurrence to runtime and completes successful delivery`() = runBlocking {
    assertEquals(ListenableWorker.Result.success(), worker().doWork())
    assertEquals(1234L, app.requested)
  }
  @Test fun `missing data or refused delivery requests a durable retry`() = runBlocking {
    app.delivery = { false }
    assertEquals(ListenableWorker.Result.retry(), worker().doWork())
  }
  @Test fun `transient failure requests a retry without failing the receiver`() = runBlocking {
    app.delivery = { throw java.io.IOException("offline") }
    assertEquals(ListenableWorker.Result.retry(), worker().doWork())
  }
  @Test fun `WorkManager cancellation propagates`() = runBlocking {
    val cancellation = CancellationException("stopped")
    app.delivery = { throw cancellation }
    assertSame(cancellation, runCatching { worker().doWork() }.exceptionOrNull())
  }
  @Test fun `invalid work input does not invoke delivery`() = runBlocking {
    val worker = TestListenableWorkerBuilder<DailySummaryWorker>(app).build()
    assertEquals(ListenableWorker.Result.failure(), worker.doWork())
    assertNull(app.requested)
  }
}
