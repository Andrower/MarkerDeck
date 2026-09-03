package com.andrower.markerdeck

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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class MarkerDeckHostService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var restartJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        promoteToForeground(MarkerDeckHostRuntime.currentController()?.currentSession())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopPersistently()
            return START_NOT_STICKY
        }

        val session = MarkerDeckHostRuntime.currentController()?.currentSession()
        if (session != null) {
            promoteToForeground(session)
        } else {
            restartDesiredHostOrStop()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        serviceScope.cancel()
        MarkerDeckHostRuntime.stopController()
        super.onDestroy()
    }

    private fun restartDesiredHostOrStop() {
        if (restartJob?.isActive == true) return
        val desired = desiredHost(this) ?: run {
            stopSelf()
            return
        }
        restartJob = serviceScope.launch {
            runCatching {
                MarkerDeckHostRuntime.controller(applicationContext).start(
                    desired.mode,
                    desired.hostName
                )
            }.onSuccess(::promoteToForeground)
                .onFailure {
                    clearDesiredHost(this@MarkerDeckHostService)
                    stopSelf()
                }
            restartJob = null
        }
    }

    private fun stopPersistently() {
        clearDesiredHost(this)
        MarkerDeckHostRuntime.stopController()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun promoteToForeground(session: EmbeddedHostSession?) {
        val notification = buildNotification(session)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(session: EmbeddedHostSession?): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, MarkerDeckHostService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val address = session?.let(::hostDisplayAddress)
        val text = if (address == null) {
            getString(R.string.host_notification_starting)
        } else {
            getString(R.string.host_notification_running, address)
        }
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.host_notification_title))
            .setContentText(text)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .addAction(
                Notification.Action.Builder(
                    null,
                    getString(R.string.stop_embedded_host_short),
                    stopIntent
                ).build()
            )
            .build()
    }

    private fun hostDisplayAddress(session: EmbeddedHostSession): String =
        if (session.mode == EmbeddedHostMode.LAN_HOST && session.lanAddress.isNotBlank()) {
            "http://${session.lanAddress}:${session.port}"
        } else {
            session.origin
        }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.host_notification_channel),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.host_notification_channel_description)
                setShowBadge(false)
            }
        )
    }

    companion object {
        private const val ACTION_START = "com.andrower.markerdeck.action.START_HOST"
        private const val ACTION_STOP = "com.andrower.markerdeck.action.STOP_HOST"
        private const val CHANNEL_ID = "markerdeck_host"
        private const val NOTIFICATION_ID = 8765
        private const val PREFERENCES = "markerdeck_host_service"
        private const val KEY_DESIRED = "desired"
        private const val KEY_MODE = "mode"
        private const val KEY_HOST_NAME = "host_name"

        fun keepRunning(
            context: Context,
            session: EmbeddedHostSession,
            hostName: String
        ) {
            context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_DESIRED, true)
                .putString(KEY_MODE, session.mode.name)
                .putString(KEY_HOST_NAME, hostName)
                .apply()
            val intent = Intent(context, MarkerDeckHostService::class.java).setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            clearDesiredHost(context)
            MarkerDeckHostRuntime.stopController()
            context.stopService(Intent(context, MarkerDeckHostService::class.java))
        }

        private fun desiredHost(context: Context): DesiredHost? {
            val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            if (!preferences.getBoolean(KEY_DESIRED, false)) return null
            val mode = preferences.getString(KEY_MODE, null)
                ?.let { runCatching { EmbeddedHostMode.valueOf(it) }.getOrNull() }
                ?: return null
            val hostName = preferences.getString(KEY_HOST_NAME, null).orEmpty()
            return DesiredHost(mode, hostName)
        }

        private fun clearDesiredHost(context: Context) {
            context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                .edit()
                .clear()
                .apply()
        }
    }

    private data class DesiredHost(
        val mode: EmbeddedHostMode,
        val hostName: String
    )
}
