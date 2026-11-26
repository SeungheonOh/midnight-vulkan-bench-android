package network.midnight.vulkanbench.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import network.midnight.vulkanbench.MainActivity

/**
 * Foreground service that keeps the benchmark process alive and holds a partial
 * CPU WakeLock while multi-minute proving suites execute.
 */
class BenchService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                releaseWakeLock()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_UPDATE -> {
                val status = intent.getStringExtra(EXTRA_STATUS) ?: "Running benchmark…"
                val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.notify(NOTIF_ID, buildNotification(status))
                return START_NOT_STICKY
            }
            else -> {
                val status = intent?.getStringExtra(EXTRA_STATUS) ?: "Running Midnight Vulkan benchmark…"
                acquireWakeLock()
                val notif = buildNotification(status)
                if (Build.VERSION.SDK_INT >= 34) {
                    startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
                } else {
                    startForeground(NOTIF_ID, notif)
                }
                return START_NOT_STICKY
            }
        }
    }

    override fun onDestroy() {
        releaseWakeLock()
        super.onDestroy()
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MidnightBench::Runner").apply {
            setReferenceCounted(false)
            acquire(30 * 60 * 1000L) // 30-min safety timeout
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    private fun ensureChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val ch = NotificationChannel(
            CHANNEL_ID,
            "Benchmark Runner",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Shows progress while Midnight prover benchmarks are running"
        }
        nm.createNotificationChannel(ch)
    }

    private fun buildNotification(status: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pi = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Midnight Vulkan Bench")
            .setContentText(status)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(pi)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "midnight_bench_runner"
        private const val NOTIF_ID = 1001
        private const val ACTION_START = "start"
        private const val ACTION_UPDATE = "update"
        private const val ACTION_STOP = "stop"
        private const val EXTRA_STATUS = "status"

        fun start(context: Context, status: String) {
            val i = Intent(context, BenchService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_STATUS, status)
            }
            runCatching { context.startForegroundService(i) }
        }

        fun update(context: Context, status: String) {
            val i = Intent(context, BenchService::class.java).apply {
                action = ACTION_UPDATE
                putExtra(EXTRA_STATUS, status)
            }
            runCatching { context.startService(i) }
        }

        fun stop(context: Context) {
            val i = Intent(context, BenchService::class.java).apply {
                action = ACTION_STOP
            }
            runCatching { context.startService(i) }
        }
    }
}
