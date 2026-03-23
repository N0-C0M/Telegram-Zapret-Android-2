package org.telegram.messenger.tgws

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow

object TgWsProxyController {
    val running = MutableStateFlow(false)

    fun start(context: Context, config: ProxyConfig) {
        val intent = Intent(context, TgWsProxyService::class.java).apply {
            action = TgWsProxyService.ACTION_START
            putExtra(TgWsProxyService.EXTRA_CONFIG, config.toJson())
        }
        ContextCompat.startForegroundService(context, intent)
    }

    fun stop(context: Context) {
        val intent = Intent(context, TgWsProxyService::class.java).apply {
            action = TgWsProxyService.ACTION_STOP
        }
        context.startService(intent)
    }

    internal fun updateRunning(isRunning: Boolean) {
        running.value = isRunning
    }
}

