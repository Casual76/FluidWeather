package dev.pampa.fluidweather.core.cycle

import android.app.Application
import android.content.Context
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class DailySummaryWorkQueueTest {
  private lateinit var app: Application
  @Before fun prepare() {
    app = RuntimeEnvironment.getApplication()
    val factory = object : WorkerFactory() {
      override fun createWorker(context: Context, name: String, params: WorkerParameters): ListenableWorker? =
        if (name != DailySummaryWorker::class.java.name) null else object : Worker(context, params) {
          override fun doWork(): Result = Result.retry()
        }
    }
    WorkManagerTestInitHelper.initializeTestWorkManager(app, Configuration.Builder()
      .setExecutor(SynchronousExecutor()).setWorkerFactory(factory).build())
  }
  @After fun finish() { WorkManagerTestInitHelper.closeWorkDatabase() }

  @Test fun `duplicate broadcasts and cold starts persist one delivery for the occurrence`() = runBlocking {
    DailySummaryWork.enqueue(app, 1234L)
    DailySummaryWork.enqueue(app, 1234L)
    val work = WorkManager.getInstance(app).getWorkInfosByTag(DailySummaryWork.TAG).get()
    assertEquals(1, work.size)
    assertFalse(work.single().state.isFinished)
  }

  @Test fun `disabling the summary cancels every queued occurrence`() = runBlocking {
    DailySummaryWork.enqueue(app, 1234L)
    DailySummaryWork.enqueue(app, 5678L)
    DailySummaryWork.cancel(app)
    val work = WorkManager.getInstance(app).getWorkInfosByTag(DailySummaryWork.TAG).get()
    assertEquals(2, work.size)
    assertTrue(work.all { it.state.isFinished })
  }
}
