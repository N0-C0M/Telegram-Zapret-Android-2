package org.telegram.messenger.tgws.core

import android.os.SystemClock
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.SecureRandom
import java.util.Locale
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLSocket

class WsHandshakeError(
    val statusCode: Int,
    val statusLine: String,
    val headers: Map<String, String> = emptyMap(),
    val location: String? = null
) : Exception("HTTP $statusCode: $statusLine") {
    val isRedirect: Boolean
        get() = statusCode in listOf(301, 302, 303, 307, 308)
}

class RawWebSocket private constructor(
    private val socket: SSLSocket,
    private val input: InputStream,
    private val output: OutputStream
) {
    private var closed = false
    private val writeLock = Any()
    private var lastReadAtMs: Long = SystemClock.elapsedRealtime()
    private var awaitingPongSinceMs: Long = 0L
    val isClosed: Boolean
        get() = closed

    companion object {
        private val rng = SecureRandom()
        private const val OP_TEXT = 0x1
        private const val OP_BINARY = 0x2
        private const val OP_CLOSE = 0x8
        private const val OP_PING = 0x9
        private const val OP_PONG = 0xA

        suspend fun connect(ip: String, domain: String, timeoutMs: Int = 10_000): RawWebSocket {
            val tcp = Socket()
            try {
                val connectTimeout = maxOf(timeoutMs, ProxyConstants.SOCKET_CONNECT_TIMEOUT_MS)
                tcp.tcpNoDelay = ProxyConstants.TCP_NODELAY
                tcp.receiveBufferSize = ProxyConstants.RECV_BUF
                tcp.sendBufferSize = ProxyConstants.SEND_BUF
                tcp.connect(InetSocketAddress(ip, 443), connectTimeout)
                tcp.soTimeout = connectTimeout

                val sslSocket = SslUtil.trustAllFactory.createSocket(tcp, ip, 443, true) as SSLSocket
                val params = sslSocket.sslParameters
                params.serverNames = listOf(SNIHostName(domain))
                sslSocket.sslParameters = params
                sslSocket.soTimeout = connectTimeout
                sslSocket.startHandshake()
                sslSocket.soTimeout = ProxyConstants.WS_READ_TIMEOUT_MS

                val input = BufferedInputStream(sslSocket.inputStream, ProxyConstants.RECV_BUF)
                val output = BufferedOutputStream(sslSocket.outputStream, ProxyConstants.SEND_BUF)

                val wsKey = ByteArray(16)
                rng.nextBytes(wsKey)
                val wsKeyB64 = android.util.Base64.encodeToString(wsKey, android.util.Base64.NO_WRAP)

                val request = buildString {
                    append("GET /apiws HTTP/1.1\r\n")
                    append("Host: $domain\r\n")
                    append("Upgrade: websocket\r\n")
                    append("Connection: Upgrade\r\n")
                    append("Sec-WebSocket-Key: $wsKeyB64\r\n")
                    append("Sec-WebSocket-Version: 13\r\n")
                    append("Sec-WebSocket-Protocol: binary\r\n")
                    append("Origin: https://web.telegram.org\r\n")
                    append(
                        "User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                            "AppleWebKit/537.36 (KHTML, like Gecko) " +
                            "Chrome/131.0.0.0 Safari/537.36\r\n"
                    )
                    append("\r\n")
                }
                output.write(request.toByteArray())
                output.flush()

                val responseLines = mutableListOf<String>()
                while (true) {
                    val line = readHttpLine(input) ?: break
                    if (line.isBlank()) break
                    responseLines.add(line)
                }

                if (responseLines.isEmpty()) {
                    sslSocket.close()
                    throw WsHandshakeError(0, "empty response")
                }

                val statusLine = responseLines.first()
                val parts = statusLine.split(" ", limit = 3)
                val statusCode = parts.getOrNull(1)?.toIntOrNull() ?: 0

                if (statusCode == 101) {
                    return RawWebSocket(
                        socket = sslSocket,
                        input = input,
                        output = output
                    )
                }

                val headers = mutableMapOf<String, String>()
                for (line in responseLines.drop(1)) {
                    val idx = line.indexOf(':')
                    if (idx > 0) {
                        val key = line.substring(0, idx).trim().lowercase(Locale.US)
                        val value = line.substring(idx + 1).trim()
                        headers[key] = value
                    }
                }

                sslSocket.close()
                throw WsHandshakeError(statusCode, statusLine, headers, headers["location"])
            } catch (e: Exception) {
                try {
                    tcp.close()
                } catch (_: Exception) {
                }
                throw e
            }
        }

        private fun readHttpLine(input: InputStream): String? {
            val sb = StringBuilder()
            while (true) {
                val ch = input.read()
                if (ch == -1) return if (sb.isNotEmpty()) sb.toString() else null
                if (ch == '\n'.code) break
                if (ch != '\r'.code) sb.append(ch.toChar())
            }
            return sb.toString()
        }
    }

    suspend fun send(data: ByteArray) {
        if (closed) throw IllegalStateException("WebSocket closed")
        writeFrame(buildFrame(OP_BINARY, data, mask = true))
    }

    suspend fun sendBatch(parts: List<ByteArray>) {
        if (closed) throw IllegalStateException("WebSocket closed")
        synchronized(writeLock) {
            if (closed) throw IllegalStateException("WebSocket closed")
            for (part in parts) {
                output.write(buildFrame(OP_BINARY, part, mask = true))
            }
            output.flush()
        }
    }

    suspend fun recv(): ByteArray? {
        while (!closed) {
            try {
                val (opcode, payload) = readFrame()
                lastReadAtMs = SystemClock.elapsedRealtime()
                when (opcode) {
                    OP_CLOSE -> {
                        closed = true
                        try {
                            val closePayload = if (payload.size >= 2) payload.copyOfRange(0, 2) else payload
                            writeFrame(
                                frame = buildFrame(OP_CLOSE, closePayload, mask = true),
                                allowWhenClosed = true
                            )
                        } catch (_: Exception) {
                        }
                        return null
                    }
                    OP_PING -> {
                        try {
                            writeFrame(buildFrame(OP_PONG, payload, mask = true))
                        } catch (_: Exception) {
                        }
                    }
                    OP_PONG -> {
                        awaitingPongSinceMs = 0L
                    }
                    OP_TEXT, OP_BINARY -> return payload
                    else -> Unit
                }
            } catch (_: SocketTimeoutException) {
                onReadTimeout()
            }
        }
        return null
    }

    suspend fun close() {
        if (closed) return
        closed = true
        try {
            writeFrame(
                frame = buildFrame(OP_CLOSE, ByteArray(0), mask = true),
                allowWhenClosed = true
            )
        } catch (_: Exception) {
        }
        try {
            socket.close()
        } catch (_: Exception) {
        }
    }

    private fun readFrame(): Pair<Int, ByteArray> {
        val b1 = input.read()
        val b2 = input.read()
        if (b1 == -1 || b2 == -1) throw EOFException("WebSocket closed")
        val opcode = b1 and 0x0F
        val masked = (b2 and 0x80) != 0
        var length = b2 and 0x7F

        if (length == 126) {
            val ext = readExact(input, 2)
            length = ((ext[0].toInt() and 0xFF) shl 8) or (ext[1].toInt() and 0xFF)
        } else if (length == 127) {
            val ext = readExact(input, 8)
            val longLen = ext.fold(0L) { acc, b -> (acc shl 8) or (b.toInt() and 0xFF).toLong() }
            if (longLen > Int.MAX_VALUE) throw IllegalStateException("Frame too large")
            length = longLen.toInt()
        }

        val maskKey = if (masked) readExact(input, 4) else null
        val payload = readExact(input, length)
        if (masked && maskKey != null) {
            for (i in payload.indices) {
                payload[i] = (payload[i].toInt() xor maskKey[i % 4].toInt()).toByte()
            }
        }
        return Pair(opcode, payload)
    }

    private fun onReadTimeout() {
        if (closed) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastReadAtMs >= ProxyConstants.WS_IDLE_TIMEOUT_MS) {
            closed = true
            try {
                socket.close()
            } catch (_: Exception) {
            }
            throw EOFException("WebSocket idle timeout")
        }

        val waitingSince = awaitingPongSinceMs
        if (waitingSince != 0L && now - waitingSince >= ProxyConstants.WS_PONG_TIMEOUT_MS) {
            closed = true
            try {
                socket.close()
            } catch (_: Exception) {
            }
            throw EOFException("WebSocket ping timeout")
        }

        if (now - lastReadAtMs >= ProxyConstants.WS_PING_INTERVAL_MS && waitingSince == 0L) {
            try {
                writeFrame(buildFrame(OP_PING, ByteArray(0), mask = true))
                awaitingPongSinceMs = now
            } catch (_: Exception) {
                closed = true
                try {
                    socket.close()
                } catch (_: Exception) {
                }
                throw EOFException("WebSocket ping failed")
            }
        }
    }

    private fun writeFrame(frame: ByteArray, allowWhenClosed: Boolean = false) {
        synchronized(writeLock) {
            if (closed && !allowWhenClosed) throw IllegalStateException("WebSocket closed")
            output.write(frame)
            output.flush()
        }
    }

    private fun buildFrame(opcode: Int, data: ByteArray, mask: Boolean): ByteArray {
        val header = ArrayList<Byte>()
        header.add((0x80 or opcode).toByte())
        val length = data.size
        val maskBit = if (mask) 0x80 else 0x00

        when {
            length < 126 -> header.add((maskBit or length).toByte())
            length < 65536 -> {
                header.add((maskBit or 126).toByte())
                header.add(((length shr 8) and 0xFF).toByte())
                header.add((length and 0xFF).toByte())
            }
            else -> {
                header.add((maskBit or 127).toByte())
                val lenLong = length.toLong()
                for (i in 7 downTo 0) {
                    header.add(((lenLong shr (8 * i)) and 0xFF).toByte())
                }
            }
        }

        val maskKey = if (mask) ByteArray(4).also { rng.nextBytes(it) } else null
        val out = ByteArray(header.size + (maskKey?.size ?: 0) + data.size)
        var idx = 0
        for (b in header) out[idx++] = b
        if (maskKey != null) {
            for (b in maskKey) out[idx++] = b
            for (i in data.indices) {
                out[idx++] = (data[i].toInt() xor maskKey[i % 4].toInt()).toByte()
            }
        } else {
            System.arraycopy(data, 0, out, idx, data.size)
        }
        return out
    }

    private fun readExact(input: InputStream, length: Int): ByteArray {
        val buf = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(buf, read, length - read)
            if (n == -1) throw EOFException("Stream closed")
            read += n
        }
        return buf
    }

}

