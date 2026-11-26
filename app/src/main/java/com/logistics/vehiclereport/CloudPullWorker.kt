package com.logistics.vehiclereport

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Refreshes the vehicle data from the cloud (what the office Excel sends) every 20 minutes, in the background,
 * whether the app is open or not. Android may delay a run a little when the phone is idle (Doze) or when the
 * phone's battery saver restricts the app.
 */
class CloudPullWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
    override fun doWork(): Result {
        val c = applicationContext
        if (!Account.isSignedIn(c) || !FirebaseLink.available(c)) return Result.success()
        val latch = CountDownLatch(1)
        var ok = false
        try {
            FirebaseLink.pullOnce(c) { done, _ -> ok = done; latch.countDown() }
            latch.await(90, TimeUnit.SECONDS)
        } catch (e: Throwable) {
            return Result.retry()
        }
        return if (ok) Result.success() else Result.retry()
    }

    companion object {
        private const val NAME = "cloud_pull_20min"

        /** Safe to call often: keeps the one existing schedule. */
        fun schedule(c: Context) {
            try {
                val req = PeriodicWorkRequestBuilder<CloudPullWorker>(20, TimeUnit.MINUTES)
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .build()
                WorkManager.getInstance(c.applicationContext)
                    .enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, req)
            } catch (e: Throwable) { }
        }

        fun cancel(c: Context) {
            try { WorkManager.getInstance(c.applicationContext).cancelUniqueWork(NAME) } catch (e: Throwable) { }
        }
    }
}
