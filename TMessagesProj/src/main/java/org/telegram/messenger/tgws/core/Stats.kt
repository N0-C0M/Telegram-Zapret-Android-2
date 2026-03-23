package org.telegram.messenger.tgws.core

import java.util.concurrent.atomic.AtomicLong

class Stats {
    private val connectionsTotal = AtomicLong(0)
    private val connectionsWs = AtomicLong(0)
    private val connectionsTcpFallback = AtomicLong(0)
    private val connectionsHttpRejected = AtomicLong(0)
    private val connectionsPassthrough = AtomicLong(0)
    private val wsErrors = AtomicLong(0)
    private val bytesUp = AtomicLong(0)
    private val bytesDown = AtomicLong(0)
    private val poolHits = AtomicLong(0)
    private val poolMisses = AtomicLong(0)

    fun incTotal() = connectionsTotal.incrementAndGet()
    fun incWs() = connectionsWs.incrementAndGet()
    fun incTcpFallback() = connectionsTcpFallback.incrementAndGet()
    fun incHttpRejected() = connectionsHttpRejected.incrementAndGet()
    fun incPassthrough() = connectionsPassthrough.incrementAndGet()
    fun incWsErrors() = wsErrors.incrementAndGet()
    fun addUp(bytes: Long) = bytesUp.addAndGet(bytes)
    fun addDown(bytes: Long) = bytesDown.addAndGet(bytes)
    fun incPoolHit() = poolHits.incrementAndGet()
    fun incPoolMiss() = poolMisses.incrementAndGet()

    fun summary(): String {
        val hit = poolHits.get()
        val miss = poolMisses.get()
        return "total=${connectionsTotal.get()} ws=${connectionsWs.get()} " +
            "tcp_fb=${connectionsTcpFallback.get()} " +
            "http_skip=${connectionsHttpRejected.get()} " +
            "pass=${connectionsPassthrough.get()} " +
            "err=${wsErrors.get()} " +
            "pool=$hit/${hit + miss} " +
            "up=${humanBytes(bytesUp.get())} " +
            "down=${humanBytes(bytesDown.get())}"
    }

    private fun humanBytes(n: Long): String {
        var value = n.toDouble()
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        var idx = 0
        while (value >= 1024 && idx < units.lastIndex) {
            value /= 1024
            idx++
        }
        return String.format("%.1f%s", value, units[idx])
    }
}

