package org.telegram.messenger.tgws

import org.json.JSONArray
import org.json.JSONObject

private val DEFAULT_DC_LIST = listOf(
    "1:149.154.175.54",
    "2:149.154.167.220",
    "4:149.154.167.220",
    "203:91.105.192.100"
)

private fun mergeDcList(entries: List<String>): List<String> {
    val ordered = LinkedHashSet<String>()
    for (entry in entries) {
        val trimmed = entry.trim()
        if (trimmed.isNotEmpty()) {
            ordered.add(trimmed)
        }
    }
    for (entry in DEFAULT_DC_LIST) {
        ordered.add(entry)
    }
    return ordered.toList()
}


data class ProxyConfig(
    val host: String = "127.0.0.1",
    val port: Int = 1080,
    val dcIp: List<String> = DEFAULT_DC_LIST,
    val loggingEnabled: Boolean = true,
    val verbose: Boolean = false,
    val allowIpv6: Boolean = true,
    val keepAlive: Boolean = true,
    val keepAliveIntervalSec: Int = 25
) {
    fun toJson(): String {
        val obj = JSONObject()
        obj.put("host", host)
        obj.put("port", port)
        obj.put("logging_enabled", loggingEnabled)
        obj.put("verbose", verbose)
        obj.put("allow_ipv6", allowIpv6)
        obj.put("keep_alive", keepAlive)
        obj.put("keep_alive_interval", keepAliveIntervalSec)
        obj.put("dc_ip", JSONArray(dcIp))
        return obj.toString()
    }

    fun withMergedDcList(): ProxyConfig {
        val merged = mergeDcList(dcIp)
        return if (merged == dcIp) this else copy(dcIp = merged)
    }

    companion object {
        fun defaultDcList(): List<String> = DEFAULT_DC_LIST

        fun fromJson(raw: String?): ProxyConfig? {
            if (raw.isNullOrBlank()) return null
            return try {
                val obj = JSONObject(raw)
                val dcArray = obj.optJSONArray("dc_ip")
                val dc = mutableListOf<String>()
                if (dcArray != null) {
                    for (i in 0 until dcArray.length()) {
                        dc.add(dcArray.optString(i))
                    }
                }
                ProxyConfig(
                    host = obj.optString("host", "127.0.0.1"),
                    port = obj.optInt("port", 1080),
                    dcIp = if (dc.isEmpty()) DEFAULT_DC_LIST else dc,
                    loggingEnabled = obj.optBoolean("logging_enabled", true),
                    verbose = obj.optBoolean("verbose", false),
                    allowIpv6 = obj.optBoolean("allow_ipv6", true),
                    keepAlive = obj.optBoolean("keep_alive", true),
                    keepAliveIntervalSec = obj.optInt("keep_alive_interval", 25)
                )
            } catch (_: Exception) {
                null
            }
        }
    }
}

