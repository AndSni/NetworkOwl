package com.netwatch.app.monitor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.netwatch.app.MainActivity
import com.netwatch.app.NetWatchApp
import com.netwatch.app.R
import com.netwatch.app.ui.Format
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Background monitoring session: keeps polling the router while the app is closed, with a
 * notification showing the live numbers. Runs until the owner stops it.
 */
class MonitorService : LifecycleService() {

    private val monitor get() = (application as NetWatchApp).monitor

    override fun onCreate() {
        super.onCreate()
        running.value = true
        ensureChannel(this)
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification("Starting…"),
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0,
        )
        monitor.acquire(TAG)
        lifecycleScope.launch {
            monitor.state.collect { s ->
                val text = when (val st = s.status) {
                    is Status.Error -> st.message
                    Status.Connecting -> "Connecting to router…"
                    Status.NeedsSetup -> "Open NetWatch to finish setup"
                    is Status.Restarting -> "Router is restarting…"
                    else -> s.frame?.let { f ->
                        val ping = f.probe?.avgMs?.let { " · ${it.toInt()} ms" } ?: " · no ping reply"
                        val sinr = f.lte.sinr?.let { " · SINR $it dB" } ?: ""
                        "↓ ${Format.rate(f.wanDownBps)}  ↑ ${Format.rate(f.wanUpBps)}$ping$sinr"
                    } ?: "Live"
                }
                getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text))
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) stopSelf()
        return START_STICKY
    }

    override fun onDestroy() {
        monitor.release(TAG)
        running.value = false
        super.onDestroy()
    }

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, MonitorService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_netwatch)
            .setContentTitle("NetWatch monitoring")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
            .build()
    }

    companion object {
        private const val TAG = "service"
        private const val CHANNEL = "monitoring"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_STOP = "com.netwatch.app.STOP"

        private val running = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = running

        fun start(context: Context) =
            ContextCompat.startForegroundService(context, Intent(context, MonitorService::class.java))

        fun stop(context: Context) {
            context.stopService(Intent(context, MonitorService::class.java))
        }

        private fun ensureChannel(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL, "Monitoring session", NotificationManager.IMPORTANCE_LOW)
                )
            }
        }
    }
}
