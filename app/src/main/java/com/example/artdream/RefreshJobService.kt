package com.example.artdream

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import java.util.concurrent.TimeUnit

/** Daily, network-gated, persisted job that tops up the [ArtCache] while the screensaver is idle. */
class RefreshJobService : JobService() {

    @Volatile private var worker: Thread? = null

    override fun onStartJob(params: JobParameters): Boolean {
        val t = Thread({
            var retry = false
            try {
                val n = ArtCache(applicationContext).refresh()
                Log.i(TAG, "background refresh: $n")
                // ALREADY_RUNNING: someone else is filling the cache; the next period will do.
            } catch (e: Exception) {
                Log.w(TAG, "background refresh failed: $e")
                retry = true
            } finally {
                if (worker === Thread.currentThread()) worker = null
                jobFinished(params, retry)
            }
        }, "artdream-bg-refresh")
        worker = t
        t.start()
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        worker?.interrupt()   // refresh() checks the interrupt flag between downloads
        return true           // reschedule per the job's backoff
    }

    companion object {
        private const val TAG = "ArtDream"
        private const val JOB_ID = 1001

        /**
         * Idempotent. Schedules the daily job if [Prefs.backgroundRefresh] is on (keeping an
         * already-pending one so its period isn't reset), otherwise cancels it.
         */
        fun schedule(ctx: Context) {
            val js = ctx.getSystemService(JobScheduler::class.java) ?: return
            if (!Prefs(ctx).backgroundRefresh) {
                js.cancel(JOB_ID)
                return
            }
            if (js.getPendingJob(JOB_ID) != null) return
            val job = JobInfo.Builder(JOB_ID, ComponentName(ctx, RefreshJobService::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPersisted(true)
                .setPeriodic(TimeUnit.DAYS.toMillis(1))
                .build()
            try {
                js.schedule(job)
            } catch (e: Exception) {
                Log.w(TAG, "could not schedule background refresh: $e")
            }
        }
    }
}

/** Re-arms the job after a reboot. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) RefreshJobService.schedule(context)
    }
}
