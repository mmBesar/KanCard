package com.mmbesar.kancard.widget

import android.content.Context
import androidx.glance.appwidget.updateAll
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.mmbesar.kancard.KanCardApplication
import com.mmbesar.kancard.network.ApiException
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

/**
 * Background job: sends changes that are waiting for the server, downloads the
 * projects shown by widgets (and the selected one), then redraws the widgets.
 *
 * It runs in two situations:
 *  - about every 30 minutes while at least one widget exists;
 *  - once as soon as a connection is available after you changed something
 *    (see [syncSoon]), even if you closed the app in the meantime.
 */
class RefreshWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as KanCardApplication
        return try {
            val projects = WidgetPrefs.allProjectIds(applicationContext) + app.credentialStore.selectedProjectId
            val result = app.repository.sync(projects.filter { it > 0L })
            BoardWidget().updateAll(applicationContext)
            // No connection: Android tries again later with growing delays.
            // A wrong login or a certificate problem will not fix itself, so stop there.
            if (result.error?.kind == ApiException.Kind.NETWORK) Result.retry() else Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.success()
        }
    }

    companion object {
        private const val PERIODIC_NAME = "kancard-widget-refresh"
        private const val SOON_NAME = "kancard-sync-soon"

        private fun needsNetwork() =
            Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        /** Safe to call many times: an existing schedule is kept. */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<RefreshWorker>(30, TimeUnit.MINUTES)
                .setConstraints(needsNetwork())
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(PERIODIC_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(PERIODIC_NAME)
        }

        /**
         * Runs one sync as soon as there is a connection. If one is already waiting
         * it is kept (never cancelled while it may be sending something).
         */
        fun syncSoon(context: Context) {
            val request = OneTimeWorkRequestBuilder<RefreshWorker>()
                .setConstraints(needsNetwork())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(SOON_NAME, ExistingWorkPolicy.KEEP, request)
        }
    }
}
