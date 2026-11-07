package io.github.pradigunara.staydee.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import io.github.pradigunara.staydee.MainActivity
import io.github.pradigunara.staydee.R
import io.github.pradigunara.staydee.gallery.GallerySaver
import io.github.pradigunara.staydee.pipeline.JobRepository
import io.github.pradigunara.staydee.pipeline.JobState
import io.github.pradigunara.staydee.pipeline.StabilizeRequest
import io.github.pradigunara.staydee.pipeline.jobDir
import io.github.pradigunara.staydee.pipeline.runPipeline
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Runs the one stabilisation job as a foreground service so it survives the app leaving the screen.
 * [JobRepository] is the only state it publishes; the service never touches the UI.
 */
class StabilizeService : Service() {
    /** Per-job bookkeeping. Written from the main thread and the job coroutine. */
    private class Active(val request: StabilizeRequest, val startId: Int, val startedAtMillis: Long) {
        @Volatile var job: Job? = null
        @Volatile var stage: JobState.Stage = JobState.Stage.PROBING
        @Volatile var estimateSeconds: Double? = null
        @Volatile var timedOut = false
        @Volatile var lastNotifyMillis = 0L
        @Volatile var notifiedStage: JobState.Stage? = null
        var wakeLock: PowerManager.WakeLock? = null
    }

    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var active: Active? = null

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_JOBS, getString(R.string.notif_channel_jobs), NotificationManager.IMPORTANCE_LOW),
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_DONE, getString(R.string.notif_channel_done), NotificationManager.IMPORTANCE_DEFAULT),
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            // the job reports Cancelled and cleans up through its own teardown
            active?.job?.cancel()
            return START_NOT_STICKY
        }
        val started = synchronized(lock) {
            if (active != null) return START_NOT_STICKY
            val request = JobRepository.takePending()
            if (request == null) {
                stopSelf(startId)
                return START_NOT_STICKY
            }
            Active(request, startId, SystemClock.elapsedRealtime()).also { active = it }
        }
        startForegroundCompat(buildRunning(started, JobState.Stage.PROBING, null))
        started.wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "staydee:stabilize")
            .apply { acquire() }
        JobRepository.update(
            JobState.Running(started.request, JobState.Stage.PROBING, null, null, started.startedAtMillis),
        )
        started.job = scope.launch { execute(started) }
        return START_NOT_STICKY
    }

    @RequiresApi(35)
    override fun onTimeout(startId: Int, fgsType: Int) {
        // the system is ending the foreground time limit: cancel through the normal teardown
        active?.let { it.timedOut = true; it.job?.cancel() }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun execute(act: Active) {
        val request = act.request
        val outcome: JobState = try {
            val result = runPipeline(
                context = this,
                request = request,
                onStage = { stage, progress -> publish(act, stage, progress) },
                onPlan = { estimate ->
                    act.estimateSeconds = estimate
                    publish(act, act.stage, null)
                },
            )
            publish(act, JobState.Stage.SAVING, null)
            val uri = GallerySaver.save(
                context = applicationContext,
                file = result.file,
                displayName = result.displayName,
                mime = result.mime,
                creationTime = result.creationTime,
            )
            JobState.Done(
                request = request,
                result = uri,
                displayName = result.displayName,
                elapsedSeconds = (SystemClock.elapsedRealtime() - act.startedAtMillis) / 1000.0,
                warnings = result.warnings,
            )
        } catch (e: CancellationException) {
            if (act.timedOut) JobState.Failed(request, getString(R.string.job_timed_out)) else JobState.Cancelled(request)
        } catch (t: Throwable) {
            // "ffmpeg failed (code):" plus ffmpeg's last log line, which names the actual error
            val lines = t.message?.lines()?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
            JobState.Failed(request, lines.firstOrNull()?.let { first -> listOfNotNull(first, lines.drop(1).lastOrNull()).joinToString("\n") } ?: t.javaClass.simpleName)
        }
        // Tear down before the terminal state: while the state is Running, JobRepository.start
        // ignores new requests, so a new job cannot start into this teardown.
        teardown(act)
        JobRepository.update(outcome)
        postOutcome(outcome)
    }

    private fun teardown(act: Active) {
        synchronized(lock) {
            if (active === act) active = null
        }
        act.wakeLock?.takeIf { it.isHeld }?.release()
        jobDir(applicationContext).deleteRecursively()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf(act.startId)
    }

    /** Publish progress: state on every call, notification at most about once a second or on a stage change. */
    private fun publish(act: Active, stage: JobState.Stage, progress: Float?) {
        act.stage = stage
        JobRepository.update(JobState.Running(act.request, stage, progress, act.estimateSeconds, act.startedAtMillis))
        val now = SystemClock.elapsedRealtime()
        val stageChanged = act.notifiedStage != stage
        if (!stageChanged && now - act.lastNotifyMillis < NOTIFY_INTERVAL_MS) return
        act.lastNotifyMillis = now
        act.notifiedStage = stage
        notifySafely(NOTIFICATION_RUNNING, buildRunning(act, stage, progress))
    }

    private fun buildRunning(act: Active, stage: JobState.Stage, progress: Float?): Notification =
        NotificationCompat.Builder(this, CHANNEL_JOBS)
            .setSmallIcon(R.drawable.ic_stat_staydee)
            .setContentTitle(getString(R.string.notif_running_title))
            .setContentText(getString(stageText(stage)))
            .setContentIntent(openAppIntent())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setProgress(PROGRESS_MAX, ((progress ?: 0f) * PROGRESS_MAX).roundToInt(), progress == null)
            .addAction(0, getString(R.string.notif_cancel), cancelIntent())
            .build()

    private fun postOutcome(outcome: JobState) {
        val notification = when (outcome) {
            is JobState.Done -> NotificationCompat.Builder(this, CHANNEL_DONE)
                .setContentTitle(getString(R.string.notif_done_title))
                .setContentText(getString(R.string.notif_done_text, outcome.displayName))
                .setContentIntent(viewIntent(outcome))
                .setAutoCancel(true)
            is JobState.Failed -> NotificationCompat.Builder(this, CHANNEL_DONE)
                .setContentTitle(getString(R.string.notif_failed_title))
                .setContentText(outcome.message)
                .setContentIntent(openAppIntent())
                .setAutoCancel(true)
            is JobState.Idle, is JobState.Running, is JobState.Cancelled -> return
        }
        notifySafely(NOTIFICATION_OUTCOME, notification.setSmallIcon(R.drawable.ic_stat_staydee).build())
    }

    private fun viewIntent(done: JobState.Done): PendingIntent {
        val view = Intent(Intent.ACTION_VIEW)
            .setDataAndType(done.result, contentResolver.getType(done.result))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        return PendingIntent.getActivity(this, REQUEST_VIEW, view, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun openAppIntent(): PendingIntent {
        val open = Intent(this, MainActivity::class.java).setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
        return PendingIntent.getActivity(this, REQUEST_OPEN, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun cancelIntent(): PendingIntent {
        val cancel = Intent(this, StabilizeService::class.java).setAction(ACTION_CANCEL)
        return PendingIntent.getService(this, REQUEST_CANCEL, cancel, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun startForegroundCompat(notification: Notification) {
        // mediaProcessing exists from API 35; dataSync (API 29+) covers 29-34.
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
        } else {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        }
        // Not ServiceCompat: androidx.core 1.18 masks the type to the API 34 set (0x40000fff),
        // which turns MEDIA_PROCESSING (0x2000) into "none" and Android 15+ throws
        // InvalidForegroundServiceTypeException. The 3-arg platform call exists since API 29 = minSdk.
        startForeground(NOTIFICATION_RUNNING, notification, type)
    }

    /** Posts unless the user has denied notifications (API 33+ requires the runtime permission). */
    private fun notifySafely(id: Int, notification: Notification) {
        val allowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (allowed) NotificationManagerCompat.from(this).notify(id, notification)
    }

    companion object {
        const val ACTION_CANCEL = "io.github.pradigunara.staydee.action.CANCEL"

        private const val CHANNEL_JOBS = "jobs"
        private const val CHANNEL_DONE = "done"
        private const val NOTIFICATION_RUNNING = 1
        private const val NOTIFICATION_OUTCOME = 2
        private const val REQUEST_OPEN = 0
        private const val REQUEST_CANCEL = 1
        private const val REQUEST_VIEW = 2
        private const val PROGRESS_MAX = 1000
        private const val NOTIFY_INTERVAL_MS = 1000L

        private fun stageText(stage: JobState.Stage): Int = when (stage) {
            JobState.Stage.PROBING -> R.string.notif_stage_probing
            JobState.Stage.DETECTING -> R.string.notif_stage_detecting
            JobState.Stage.TRANSFORMING -> R.string.notif_stage_transforming
            JobState.Stage.SAVING -> R.string.notif_stage_saving
        }
    }
}
