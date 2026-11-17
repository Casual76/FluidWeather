package dev.pampa.fluidweather.core.cycle

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeoutOrNull

/** Unique per scheduled occurrence: cold starts and duplicate broadcasts cannot multiply work. */
object DailySummaryWork {
  internal const val DUE_AT = "daily-summary-due-at"
  internal const val TAG = "daily-summary-delivery"

  suspend fun enqueue(context: Context, dueAtMillis: Long) {
    val request = OneTimeWorkRequestBuilder<DailySummaryWorker>()
      .setInputData(workDataOf(DUE_AT to dueAtMillis))
      .setBackoffCriteria(BackoffPolicy.LINEAR, 15, TimeUnit.MINUTES)
      .addTag(TAG)
      .build()
    // Wait for the database commit before finishing the receiver. No provider calls here.
    runInterruptible(Dispatchers.IO) {
      WorkManager.getInstance(context)
        .enqueueUniqueWork("$TAG-$dueAtMillis", ExistingWorkPolicy.KEEP, request)
        .result.get(5, TimeUnit.SECONDS)
    }
  }

  suspend fun cancel(context: Context) {
    runInterruptible(Dispatchers.IO) {
      WorkManager.getInstance(context).cancelAllWorkByTag(TAG).result.get(5, TimeUnit.SECONDS)
    }
  }
}

class DailySummaryWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
  override suspend fun doWork(): Result {
    val dueAtMillis = inputData.getLong(DailySummaryWork.DUE_AT, Long.MIN_VALUE)
    if (dueAtMillis == Long.MIN_VALUE) return Result.failure()
    return try {
      val complete = withTimeoutOrNull(7 * 60_000L) {
        applicationContext.cycleRuntime().runDailySummary(dueAtMillis)
      } ?: false
      if (complete) Result.success() else Result.retry()
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (failure: Exception) {
      android.util.Log.w("DailySummary", "Daily summary deferred", failure)
      Result.retry()
    }
  }
}
