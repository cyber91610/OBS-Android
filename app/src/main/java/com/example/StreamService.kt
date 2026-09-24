package com.example

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class StreamService : Service() {

    companion object {
        const val ACTION_START = "com.example.action.START_STREAM"
        const val ACTION_STOP = "com.example.action.STOP_STREAM"
        private const val CHANNEL_ID = "youtube_stream_channel"
        private const val NOTIFICATION_ID = 2001

        fun start(context: Context) {
            val intent = Intent(context, StreamService::class.java).apply {
                action = ACTION_START
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, StreamService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var stateObserverJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        AppLogManager.i("StreamService", "Foreground service created")
        createNotificationChannel()

        acquireLocks()

        StreamManager.onServiceStopRequested = {
            AppLogManager.i("StreamService", "Stop requested, removing notification and stopping service")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }

        observeStreamState()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                StreamManager.requestStopStream()
                releaseLocks()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                acquireLocks()
                val notification = buildNotification("Initializing stream...")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val foregroundType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                    } else {
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                    }
                    ServiceCompat.startForeground(
                        this,
                        NOTIFICATION_ID,
                        notification,
                        foregroundType
                    )
                } else {
                    startForeground(NOTIFICATION_ID, notification)
                }

                StreamManager.startInternal(applicationContext)
            }
        }
        return START_NOT_STICKY
    }

    private fun observeStreamState() {
        stateObserverJob?.cancel()
        stateObserverJob = serviceScope.launch {
            StreamManager.uiState.collectLatest { state ->
                if (state.isStreaming || state.status == StreamStatus.LIVE || state.status == StreamStatus.CONNECTING || state.status == StreamStatus.RECONNECTING) {
                    val statusText = when (state.status) {
                        StreamStatus.LIVE -> {
                            val elapsed = FilePicker.formatTime(state.elapsedTimeSeconds)
                            val remaining = FilePicker.formatTime(state.remainingTimeSeconds)
                            if (state.isLoopEnabled) {
                                val loopCycle = state.loopCount + 1
                                "Live (Loop #$loopCycle): $elapsed / -$remaining"
                            } else {
                                "Live: $elapsed / -$remaining"
                            }
                        }
                        StreamStatus.CONNECTING -> "Connecting to YouTube..."
                        StreamStatus.RECONNECTING -> "Reconnecting to YouTube..."
                        StreamStatus.PREPARING -> "Preparing video encoder..."
                        else -> state.status.displayText
                    }
                    val notification = buildNotification(statusText)
                    val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    manager.notify(NOTIFICATION_ID, notification)
                }
            }
        }
    }

    private fun buildNotification(contentText: String): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, StreamService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("YouTube Video Streamer")
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(openPendingIntent)
            .addAction(android.R.drawable.ic_media_pause, "Stop Stream", stopPendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "YouTube Live Streaming",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows live streaming status for YouTube broadcast"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun acquireLocks() {
        try {
            if (wakeLock == null) {
                val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
                wakeLock = powerManager?.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "YouTubeStreamer::StreamWakeLock"
                )?.apply {
                    setReferenceCounted(false)
                }
            }
            if (wakeLock?.isHeld == false) {
                wakeLock?.acquire()
                AppLogManager.s("StreamService", "PowerManager.WakeLock (PARTIAL_WAKE_LOCK) acquired successfully to prevent CPU sleep")
            }
        } catch (e: Exception) {
            AppLogManager.e("StreamService", "Failed to acquire WakeLock: ${e.message}", e)
        }

        try {
            if (wifiLock == null) {
                val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                wifiLock = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    wifiManager?.createWifiLock(
                        WifiManager.WIFI_MODE_FULL_LOW_LATENCY,
                        "YouTubeStreamer::StreamWifiLock"
                    )
                } else {
                    @Suppress("DEPRECATION")
                    wifiManager?.createWifiLock(
                        WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                        "YouTubeStreamer::StreamWifiLock"
                    )
                }?.apply {
                    setReferenceCounted(false)
                }
            }
            if (wifiLock?.isHeld == false) {
                wifiLock?.acquire()
                AppLogManager.s("StreamService", "WifiManager.WifiLock acquired successfully to keep Wi-Fi active during stream")
            }
        } catch (e: Exception) {
            AppLogManager.e("StreamService", "Failed to acquire WifiLock: ${e.message}", e)
        }
    }

    private fun releaseLocks() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
                AppLogManager.d("StreamService", "PowerManager.WakeLock released")
            }
        } catch (e: Exception) {
            AppLogManager.w("StreamService", "Exception releasing WakeLock: ${e.message}")
        }
        wakeLock = null

        try {
            if (wifiLock?.isHeld == true) {
                wifiLock?.release()
                AppLogManager.d("StreamService", "WifiManager.WifiLock released")
            }
        } catch (e: Exception) {
            AppLogManager.w("StreamService", "Exception releasing WifiLock: ${e.message}")
        }
        wifiLock = null
    }

    override fun onDestroy() {
        stateObserverJob?.cancel()
        serviceScope.cancel()
        releaseLocks()
        StreamManager.onServiceStopRequested = null
        super.onDestroy()
    }
}
