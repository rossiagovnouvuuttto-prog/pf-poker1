package com.hexadecinull.vineos.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.hexadecinull.vineos.MainActivity
import com.hexadecinull.vineos.VineApplication.Companion.CHANNEL_VM_RUNNING
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Android 16 foreground-service timeout workaround.
 *
 * The previous implementation called startForeground() only from
 * onStartCommand(). Xiaomi HyperOS could kill the app with
 * ForegroundServiceDidNotStartInTimeException while the guest image was
 * being provisioned/started. Promote immediately in onCreate(), before
 * any VM runtime work. Expose a readiness signal so the VM manager waits
 * before calling native code.
 */
@AndroidEntryPoint
class VineService : Service() {

    override fun onCreate() {
        super.onCreate()
        // Must happen immediately after Service creation, before VM boot.
        startForeground(NOTIF_ID, buildNotification(runningCount = 0))
        _foregroundReady.value = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int =
        when (intent?.action ?: ACTION_START) {
            ACTION_START -> START_STICKY
            ACTION_STOP -> {
                _foregroundReady.value = false
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                START_NOT_STICKY
            }
            else -> START_NOT_STICKY
        }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        _foregroundReady.value = false
        super.onDestroy()
    }

    private fun buildNotification(runningCount: Int): Notification {
        val contentText = when (runningCount) {
            0 -> "Starting virtual Android instance"
            1 -> "1 instance running"
            else -> "$runningCount instances running"
        }
        val openAppIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_VM_RUNNING)
            .setContentTitle("VineOS")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentIntent(openAppIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    companion object {
        const val ACTION_START = "com.hexadecinull.vineos.START_SERVICE"
        const val ACTION_STOP = "com.hexadecinull.vineos.STOP_SERVICE"
        private const val NOTIF_ID = 1001

        private val _foregroundReady = MutableStateFlow(false)
        val foregroundReady = _foregroundReady.asStateFlow()
    }
}
