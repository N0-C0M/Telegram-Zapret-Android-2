package org.telegram.messenger.tgws.core

data class DcInfo(val dc: Int, val isMedia: Boolean)

data class DcKey(val dc: Int, val isMedia: Boolean)

object ProxyConstants {
    const val DEFAULT_PORT = 1080
    const val TCP_NODELAY = true
    const val RECV_BUF = 131072
    const val SEND_BUF = 131072
    const val WS_POOL_SIZE = 8
    const val WS_POOL_MAX_AGE_MS = 120_000L
    const val DC_FAIL_COOLDOWN_MS = 20_000L

    // Mobile-network oriented timeouts/retry settings.
    const val WS_CONNECT_TIMEOUT_MS = 15_000
    const val WS_READ_TIMEOUT_MS = 30_000
    const val WS_IDLE_TIMEOUT_MS = 90_000L
    const val WS_PING_INTERVAL_MS = 25_000L
    const val WS_PONG_TIMEOUT_MS = 10_000L
    const val NETWORK_RETRY_BACKOFF_MS = 500L
    const val NETWORK_RETRY_MAX_DELAY_MS = 10_000L
    const val NETWORK_MAX_RETRIES = 5
    const val SOCKET_CONNECT_TIMEOUT_MS = 12_000
    const val SOCKET_READ_TIMEOUT_MS = 20_000
    const val WS_POOL_ERROR_BACKOFF_BASE_MS = 1_000L
    const val WS_POOL_ERROR_BACKOFF_MAX_MS = 5_000L

    val tgRanges: List<LongRange> = listOf(
        ipRange("185.76.151.0", "185.76.151.255"),
        ipRange("149.154.160.0", "149.154.175.255"),
        ipRange("91.105.192.0", "91.105.193.255"),
        ipRange("91.108.0.0", "91.108.255.255")
    )

    val ipToDc: Map<String, DcInfo> = mapOf(
        // DC1
        "149.154.175.50" to DcInfo(1, false),
        "149.154.175.51" to DcInfo(1, false),
        "149.154.175.53" to DcInfo(1, false),
        "149.154.175.54" to DcInfo(1, false),
        "149.154.175.55" to DcInfo(1, false),
        "149.154.175.52" to DcInfo(1, true),
        // DC2
        "149.154.167.41" to DcInfo(2, false),
        "149.154.167.50" to DcInfo(2, false),
        "149.154.167.51" to DcInfo(2, false),
        "149.154.167.220" to DcInfo(2, false),
        "95.161.76.100" to DcInfo(2, false),
        "149.154.167.151" to DcInfo(2, true),
        "149.154.167.222" to DcInfo(2, true),
        "149.154.167.223" to DcInfo(2, true),
        "149.154.162.123" to DcInfo(2, true),
        // DC3
        "149.154.175.100" to DcInfo(3, false),
        "149.154.175.101" to DcInfo(3, false),
        "149.154.175.102" to DcInfo(3, true),
        // DC4
        "149.154.167.91" to DcInfo(4, false),
        "149.154.167.92" to DcInfo(4, false),
        "149.154.164.250" to DcInfo(4, true),
        "149.154.166.120" to DcInfo(4, true),
        "149.154.166.121" to DcInfo(4, true),
        "149.154.167.118" to DcInfo(4, true),
        "149.154.165.111" to DcInfo(4, true),
        // DC5
        "91.108.56.100" to DcInfo(5, false),
        "91.108.56.101" to DcInfo(5, false),
        "91.108.56.116" to DcInfo(5, false),
        "91.108.56.126" to DcInfo(5, false),
        "149.154.171.5" to DcInfo(5, false),
        "91.108.56.102" to DcInfo(5, true),
        "91.108.56.128" to DcInfo(5, true),
        "91.108.56.151" to DcInfo(5, true),
        // DC203
        "91.105.192.100" to DcInfo(203, false)
    )

    private fun ipRange(start: String, end: String): LongRange {
        return ipToLong(start)..ipToLong(end)
    }

    fun isTelegramIp(ip: String): Boolean {
        val value = ipToLongOrNull(ip) ?: return false
        return tgRanges.any { value in it }
    }

    fun ipToLongOrNull(ip: String): Long? {
        return try {
            ipToLong(ip)
        } catch (_: Exception) {
            null
        }
    }

    fun ipToLong(ip: String): Long {
        val parts = ip.split(".")
        require(parts.size == 4) { "Invalid IPv4" }
        var result = 0L
        for (part in parts) {
            val n = part.toInt()
            require(n in 0..255)
            result = (result shl 8) or n.toLong()
        }
        return result
    }
}

