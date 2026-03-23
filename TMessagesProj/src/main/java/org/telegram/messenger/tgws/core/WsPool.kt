package org.telegram.messenger.tgws.core

import org.telegram.messenger.tgws.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import android.os.SystemClock

class WsPool(
    private val scope: CoroutineScope,
    private val stats: Stats
) {
    private val idle = mutableMapOf<DcKey, MutableList<Pair<RawWebSocket, Long>>>()
    private val refilling = mutableSetOf<DcKey>()
    private val mutex = Mutex()

    suspend fun get(dc: Int, isMedia: Boolean, targetIp: String, domains: List<String>): RawWebSocket? {
        val key = DcKey(dc, isMedia)
        val now = SystemClock.elapsedRealtime()
        val ws = mutex.withLock {
            val bucket = idle[key]
            if (bucket.isNullOrEmpty()) {
                null
            } else {
                var candidate: RawWebSocket? = null
                while (bucket.isNotEmpty()) {
                    val (item, created) = bucket.removeAt(0)
                    val age = now - created
                    if (age > ProxyConstants.WS_POOL_MAX_AGE_MS || item.isClosed) {
                        scope.launch { item.close() }
                        continue
                    }
                    candidate = item
                    break
                }
                candidate
            }
        }

        if (ws != null) {
            stats.incPoolHit()
            scheduleRefill(key, targetIp, domains)
            return ws
        }

        stats.incPoolMiss()
        scheduleRefill(key, targetIp, domains)
        return null
    }

    fun scheduleRefill(key: DcKey, targetIp: String, domains: List<String>) {
        scope.launch {
            val shouldRefill = mutex.withLock {
                if (refilling.contains(key)) {
                    false
                } else {
                    refilling.add(key)
                    true
                }
            }
            if (!shouldRefill) return@launch
            try {
                refill(key, targetIp, domains)
            } finally {
                mutex.withLock { refilling.remove(key) }
            }
        }
    }

    fun warmup(dcOpt: Map<Int, String>) {
        for ((dc, ip) in dcOpt) {
            for (isMedia in listOf(false, true)) {
                val key = DcKey(dc, isMedia)
                val domains = wsDomains(dc, isMedia)
                scheduleRefill(key, ip, domains)
            }
        }
    }

    private suspend fun refill(key: DcKey, targetIp: String, domains: List<String>) {
        val bucket = mutex.withLock {
            idle.getOrPut(key) { mutableListOf() }
        }
        val needed = ProxyConstants.WS_POOL_SIZE - bucket.size
        if (needed <= 0) return

        val created = mutableListOf<Pair<RawWebSocket, Long>>()
        var consecutiveErrors = 0
        repeat(needed) {
            try {
                val ws = connectOne(targetIp, domains)
                if (ws != null) {
                    created.add(ws to SystemClock.elapsedRealtime())
                    consecutiveErrors = 0
                } else {
                    consecutiveErrors++
                    if (consecutiveErrors > 2) {
                        val delayMs = minOf(
                            ProxyConstants.WS_POOL_ERROR_BACKOFF_BASE_MS * consecutiveErrors,
                            ProxyConstants.WS_POOL_ERROR_BACKOFF_MAX_MS
                        )
                        delay(delayMs)
                    }
                }
            } catch (e: Exception) {
                consecutiveErrors++
                AppLogger.d("WsPool", "Refill connect failed: ${e.message}")
                if (consecutiveErrors > 2) {
                    val delayMs = minOf(
                        ProxyConstants.WS_POOL_ERROR_BACKOFF_BASE_MS * consecutiveErrors,
                        ProxyConstants.WS_POOL_ERROR_BACKOFF_MAX_MS
                    )
                    delay(delayMs)
                }
            }
        }

        if (created.isNotEmpty()) {
            mutex.withLock {
                bucket.addAll(created)
            }
            AppLogger.d("WsPool", "Refilled ${created.size} sockets for DC${key.dc}${if (key.isMedia) "m" else ""}")
        }
    }

    private suspend fun connectOne(targetIp: String, domains: List<String>): RawWebSocket? {
        for (domain in domains) {
            try {
                return RawWebSocket.connect(
                    ip = targetIp,
                    domain = domain,
                    timeoutMs = ProxyConstants.WS_CONNECT_TIMEOUT_MS
                )
            } catch (e: WsHandshakeError) {
                if (e.isRedirect) continue
                return null
            } catch (_: Exception) {
                return null
            }
        }
        return null
    }

    private fun wsDomains(dc: Int, isMedia: Boolean): List<String> {
        val base = if (dc > 5) "telegram.org" else "web.telegram.org"
        return if (isMedia) {
            listOf("kws$dc-1.$base", "kws$dc.$base")
        } else {
            listOf("kws$dc.$base", "kws$dc-1.$base")
        }
    }
}

