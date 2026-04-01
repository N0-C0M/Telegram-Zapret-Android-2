package org.telegram.messenger.tgws

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.net.wifi.WifiManager
import androidx.core.app.NotificationCompat
import org.telegram.messenger.R
import org.telegram.messenger.tgws.core.ProxyEngine
import org.telegram.ui.LaunchActivity

class TgWsProxyService : Service() {
    private var engine: ProxyEngine? = null
    private var currentConfig: ProxyConfig? = null
    private lateinit var repo: ConfigRepository
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onCreate() {
        super.onCreate()
        repo = ConfigRepository(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val config = ProxyConfig.fromJson(intent.getStringExtra(EXTRA_CONFIG)) ?: repo.load()
                val merged = config.withMergedDcList()
                repo.save(merged)
                repo.setShouldRun(true)
                applyForegroundState(merged)
                acquireLocks()
                startOrReloadEngine(merged)
                TgWsProxyController.updateRunning(true)
            }
            ACTION_STOP -> {
                repo.setShouldRun(false)
                stopEngine()
                releaseLocks()
                TgWsProxyController.updateRunning(false)
                stopForegroundCompat()
                stopSelf()
            }
            else -> {
                if (repo.shouldRun()) {
                    val config = repo.load()
                    val merged = config.withMergedDcList()
                    if (merged != config) {
                        repo.save(merged)
                    }
                    applyForegroundState(merged)
                    acquireLocks()
                    startOrReloadEngine(merged)
                    TgWsProxyController.updateRunning(true)
                } else {
                    stopSelf()
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopEngine()
        releaseLocks()
        TgWsProxyController.updateRunning(false)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startEngine(config: ProxyConfig) {
        AppLogger.init(
            context = applicationContext,
            verbose = config.verbose,
            enabled = config.loggingEnabled
        )
        engine = ProxyEngine()
        engine?.start(config)
        currentConfig = config
    }

    private fun startOrReloadEngine(config: ProxyConfig) {
        val previousConfig = currentConfig
        if (engine?.isRunning == true && previousConfig != null && normalizeForEngine(previousConfig) == normalizeForEngine(config)) {
            currentConfig = config
            return
        }
        stopEngine()
        startEngine(config)
    }

    private fun stopEngine() {
        engine?.stop()
        engine = null
        currentConfig = null
    }

    private fun acquireLocks() {
        if (wakeLock == null) {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TgWsProxy::ProxyWake").apply {
                setReferenceCounted(false)
                acquire()
            }
        }
        if (wifiLock == null) {
            val wm = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "TgWsProxy::WifiLock").apply {
                setReferenceCounted(false)
                acquire()
            }
        }
    }

    private fun releaseLocks() {
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
        } catch (_: Exception) {
        }
        try {
            wifiLock?.let { if (it.isHeld) it.release() }
        } catch (_: Exception) {
        }
        wakeLock = null
        wifiLock = null
    }

    private fun buildNotification(config: ProxyConfig): Notification {
        val openIntent = Intent(this, LaunchActivity::class.java).apply {
            action = "org.telegram.messenger.OPEN_ZAPRET"
        }
        val openPending = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopIntent = Intent(this, TgWsProxyService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPending = PendingIntent.getService(
            this,
            0,
            stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val contentText = "Proxy on ${config.host}:${config.port}"

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.notification)
            .setContentTitle("TG WS Proxy")
            .setContentText(contentText)
            .setContentIntent(openPending)
            .setOngoing(true)
            .addAction(0, "Stop", stopPending)
            .build()
    }

    private fun applyForegroundState(config: ProxyConfig) {
        if (config.showNotification) {
            startForeground(NOTIF_ID, buildNotification(config))
            return
        }
        startForeground(NOTIF_ID, buildNotification(config))
        stopForegroundCompat()
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.cancel(NOTIF_ID)
    }

    private fun normalizeForEngine(config: ProxyConfig): ProxyConfig {
        return config.copy(showNotification = true)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "TG WS Proxy",
                NotificationManager.IMPORTANCE_LOW
            )
            channel.description = "Local Telegram WebSocket proxy"
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    companion object {
        const val ACTION_START = "org.telegram.messenger.action.START_TG_WS_PROXY"
        const val ACTION_STOP = "org.telegram.messenger.action.STOP_TG_WS_PROXY"
        const val EXTRA_CONFIG = "extra_config"
        private const val CHANNEL_ID = "tg_ws_proxy_service"
        private const val NOTIF_ID = 1001
    }
}

