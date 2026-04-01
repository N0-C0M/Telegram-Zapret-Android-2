package org.telegram.messenger.tgws.core

import android.os.SystemClock
import org.telegram.messenger.tgws.AppLogger
import org.telegram.messenger.tgws.ProxyConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.selects.select
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketAddress
import java.net.SocketTimeoutException
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

class ProxyEngine {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val stats = Stats()
    private val wsPool = WsPool(scope, stats)
    private var keepAliveJob: kotlinx.coroutines.Job? = null

    private val wsBlacklist = Collections.synchronizedSet(mutableSetOf<DcKey>())
    private val dcFailUntil = ConcurrentHashMap<DcKey, Long>()
    private val ipv6BlockDurationMs = 10 * 60 * 1000L
    @Volatile
    private var ipv6BlockedUntilMs: Long = 0L
    @Volatile
    private var allowIpv6: Boolean = true

    @Volatile
    var isRunning: Boolean = false
        private set

    private var serverSocket: ServerSocket? = null
    private var dcOpt: Map<Int, String> = emptyMap()

    fun start(config: ProxyConfig) {
        if (isRunning) return
        AppLogger.setEnabled(config.loggingEnabled)
        AppLogger.setVerbose(config.verbose)
        allowIpv6 = config.allowIpv6
        ipv6BlockedUntilMs = 0L
        dcOpt = parseDcIpList(config.dcIp)

        scope.launch { runServer(config.host, config.port) }
        scope.launch { logStatsLoop() }
        if (config.keepAlive) {
            startKeepAlive(config.host, config.port, config.keepAliveIntervalSec)
        }
        wsPool.warmup(dcOpt)
    }

    fun stop() {
        isRunning = false
        keepAliveJob?.cancel()
        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
        scope.cancel()
    }

    private suspend fun runServer(host: String, port: Int) = withContext(Dispatchers.IO) {
        try {
            val server = ServerSocket()
            server.reuseAddress = true
            server.bind(InetSocketAddress(host, port))
            serverSocket = server
            isRunning = true

            AppLogger.i("Proxy", "Listening on $host:$port")
            for ((dc, ip) in dcOpt) {
                AppLogger.i("Proxy", "DC$dc: $ip")
            }

            while (isRunning) {
                try {
                    val client = server.accept()
                    scope.launch { handleClient(client) }
                } catch (e: Exception) {
                    if (isRunning) {
                        AppLogger.w("Proxy", "Accept failed: ${e.message}")
                    }
                    break
                }
            }
        } catch (e: Exception) {
            AppLogger.e("Proxy", "Server failed: ${e.message}", e)
        } finally {
            isRunning = false
            try {
                serverSocket?.close()
            } catch (_: Exception) {
            }
        }
    }

    private suspend fun handleClient(socket: Socket) = withContext(Dispatchers.IO) {
        stats.incTotal()
        val peer = socket.inetAddress?.hostAddress ?: "?"
        val label = "$peer:${socket.port}"

        try {
            socket.tcpNoDelay = ProxyConstants.TCP_NODELAY
            socket.receiveBufferSize = ProxyConstants.RECV_BUF
            socket.sendBufferSize = ProxyConstants.SEND_BUF

            val input = BufferedInputStream(socket.getInputStream(), ProxyConstants.RECV_BUF)
            val output = BufferedOutputStream(socket.getOutputStream(), ProxyConstants.SEND_BUF)

            socket.soTimeout = 10_000

            val hdr = readExact(input, 2)
            if (hdr[0].toInt() != 0x05) {
                AppLogger.d("Proxy", "[$label] not SOCKS5 (ver=${hdr[0]})")
                socket.close()
                return@withContext
            }
            val nMethods = hdr[1].toInt() and 0xFF
            if (nMethods > 0) readExact(input, nMethods)
            output.write(byteArrayOf(0x05, 0x00))
            output.flush()

            val req = readExact(input, 4)
            val cmd = req[1].toInt() and 0xFF
            val atyp = req[3].toInt() and 0xFF
            if (cmd != 0x01 && cmd != 0x03) {
                output.write(socks5Reply(0x07))
                output.flush()
                socket.close()
                return@withContext
            }

            val dst = when (atyp) {
                0x01 -> {
                    val raw = readExact(input, 4)
                    InetAddress.getByAddress(raw).hostAddress
                }
                0x03 -> {
                    val len = readExact(input, 1)[0].toInt() and 0xFF
                    String(readExact(input, len))
                }
                0x04 -> {
                    val raw = readExact(input, 16)
                    InetAddress.getByAddress(raw).hostAddress
                }
                else -> {
                    output.write(socks5Reply(0x08))
                    output.flush()
                    socket.close()
                    return@withContext
                }
            }

            val portBytes = readExact(input, 2)
            val port = ((portBytes[0].toInt() and 0xFF) shl 8) or (portBytes[1].toInt() and 0xFF)

            if (cmd == 0x03) {
                socket.soTimeout = 0
                handleUdpAssociate(socket, output, label)
                return@withContext
            }

            if (dst.contains(":")) {
                if (!allowIpv6) {
                    AppLogger.d("Proxy", "[$label] IPv6 disabled by config -> reject")
                    output.write(socks5Reply(0x04))
                    output.flush()
                    socket.close()
                    return@withContext
                }
                val now = SystemClock.elapsedRealtime()
                if (now < ipv6BlockedUntilMs) {
                    val remaining = (ipv6BlockedUntilMs - now) / 1000
                    AppLogger.d("Proxy", "[$label] IPv6 disabled ${remaining}s -> reject")
                    output.write(socks5Reply(0x04))
                    output.flush()
                    socket.close()
                    return@withContext
                }
                stats.incPassthrough()
                AppLogger.w("Proxy", "[$label] IPv6 passthrough -> $dst:$port")
                val remote = try {
                    openSocket(dst, port, 2_000)
                } catch (e: Exception) {
                    val message = e.message ?: ""
                    if (message.contains("ENETUNREACH", ignoreCase = true)) {
                        ipv6BlockedUntilMs = now + ipv6BlockDurationMs
                        AppLogger.w("Proxy", "IPv6 unreachable, disabling for 10 minutes")
                    }
                    AppLogger.w("Proxy", "[$label] IPv6 passthrough failed: ${e.message}")
                    output.write(socks5Reply(0x05))
                    output.flush()
                    socket.close()
                    return@withContext
                }

                output.write(socks5Reply(0x00))
                output.flush()
                socket.soTimeout = 0
                bridgeTcp(input, output, remote, label)
                return@withContext
            }

            if (dst == "0.0.0.0" && port == 0) {
                output.write(socks5Reply(0x00))
                output.flush()
                socket.close()
                return@withContext
            }

            val isTelegram = ProxyConstants.isTelegramIp(dst)
            if (!isTelegram) {
                stats.incPassthrough()
                AppLogger.d("Proxy", "[$label] passthrough -> $dst:$port")
                val remote = try {
                    openSocket(dst, port, ProxyConstants.SOCKET_CONNECT_TIMEOUT_MS)
                } catch (e: Exception) {
                    AppLogger.w("Proxy", "[$label] passthrough failed: ${e.message}")
                    output.write(socks5Reply(0x05))
                    output.flush()
                    socket.close()
                    return@withContext
                }

                output.write(socks5Reply(0x00))
                output.flush()
                socket.soTimeout = 0
                bridgeTcp(input, output, remote, label)
                return@withContext
            }

            output.write(socks5Reply(0x00))
            output.flush()

            socket.soTimeout = 15_000
            val init = try {
                readExact(input, 64)
            } catch (_: EOFException) {
                AppLogger.d("Proxy", "[$label] client disconnected before init")
                return@withContext
            }
            socket.soTimeout = 0

            if (isHttpTransport(init)) {
                stats.incHttpRejected()
                AppLogger.d("Proxy", "[$label] HTTP transport rejected $dst:$port")
                socket.close()
                return@withContext
            }

            var (dc, isMedia) = Mtproto.dcFromInit(init)
            var initBytes = init
            var initPatched = false

            val info = ProxyConstants.ipToDc[dst]
            if (info != null && dcOpt.containsKey(info.dc) && (dc == null || !dcOpt.containsKey(dc))) {
                dc = info.dc
                isMedia = info.isMedia
                initBytes = Mtproto.patchInitDc(initBytes, if (isMedia) dc else -dc)
                initPatched = true
            }

            if (dc == null || !dcOpt.containsKey(dc)) {
                AppLogger.w("Proxy", "[$label] unknown DC for $dst:$port -> TCP fallback")
                tcpFallback(input, output, dst, port, initBytes, label)
                return@withContext
            }

            val dcKey = DcKey(dc, isMedia)
            val now = SystemClock.elapsedRealtime()

            if (wsBlacklist.contains(dcKey)) {
                AppLogger.d("Proxy", "[$label] DC${dc}${if (isMedia) "m" else ""} WS blacklisted -> TCP")
                tcpFallback(input, output, dst, port, initBytes, label)
                return@withContext
            }

            val failUntil = dcFailUntil[dcKey] ?: 0L
            if (now < failUntil) {
                val remaining = (failUntil - now) / 1000
                AppLogger.d("Proxy", "[$label] DC${dc}${if (isMedia) "m" else ""} cooldown ${remaining}s -> TCP")
                tcpFallback(input, output, dst, port, initBytes, label)
                return@withContext
            }

            val domains = wsDomains(dc, isMedia)
            val target = dcOpt[dc] ?: run {
                tcpFallback(input, output, dst, port, initBytes, label)
                return@withContext
            }

            var ws: RawWebSocket? = wsPool.get(dc, isMedia, target, domains)
            var wsFailedRedirect = false
            var allRedirects = true

            if (ws != null) {
                AppLogger.i("Proxy", "[$label] DC${dc}${if (isMedia) "m" else ""} -> pool hit via $target")
            } else {
                var retryCount = 0
                while (retryCount < ProxyConstants.NETWORK_MAX_RETRIES && ws == null) {
                    val attempt = retryCount + 1
                    for (domain in domains) {
                        val url = "wss://$domain/apiws"
                        AppLogger.i(
                            "Proxy",
                            "[$label] DC${dc}${if (isMedia) "m" else ""} -> $url via $target (attempt $attempt)"
                        )
                        try {
                            ws = RawWebSocket.connect(
                                ip = target,
                                domain = domain,
                                timeoutMs = ProxyConstants.WS_CONNECT_TIMEOUT_MS
                            )
                            allRedirects = false
                            break
                        } catch (e: WsHandshakeError) {
                            stats.incWsErrors()
                            if (e.isRedirect) {
                                wsFailedRedirect = true
                                AppLogger.w(
                                    "Proxy",
                                    "[$label] DC${dc}${if (isMedia) "m" else ""} got ${e.statusCode} from $domain"
                                )
                                continue
                            } else {
                                allRedirects = false
                                AppLogger.w(
                                    "Proxy",
                                    "[$label] DC${dc}${if (isMedia) "m" else ""} WS handshake: ${e.statusLine}"
                                )
                            }
                        } catch (e: Exception) {
                            stats.incWsErrors()
                            allRedirects = false
                            AppLogger.w(
                                "Proxy",
                                "[$label] DC${dc}${if (isMedia) "m" else ""} WS connect failed: ${e.message}"
                            )
                        }
                    }

                    if (ws != null) {
                        break
                    }

                    if (retryCount < ProxyConstants.NETWORK_MAX_RETRIES - 1) {
                        val backoffMs = minOf(
                            ProxyConstants.NETWORK_RETRY_BACKOFF_MS * (1L shl retryCount),
                            ProxyConstants.NETWORK_RETRY_MAX_DELAY_MS
                        )
                        AppLogger.d("Proxy", "[$label] WS retry in ${backoffMs}ms")
                        delay(backoffMs)
                    }
                    retryCount++
                }
            }

            if (ws == null) {
                if (wsFailedRedirect && allRedirects) {
                    wsBlacklist.add(dcKey)
                    AppLogger.w("Proxy", "[$label] DC${dc}${if (isMedia) "m" else ""} blacklisted for WS")
                }
                dcFailUntil[dcKey] = now + ProxyConstants.DC_FAIL_COOLDOWN_MS
                AppLogger.i("Proxy", "[$label] DC${dc}${if (isMedia) "m" else ""} -> TCP fallback")
                tcpFallback(input, output, dst, port, initBytes, label)
                return@withContext
            }

            dcFailUntil.remove(dcKey)
            stats.incWs()

            val splitter = if (initPatched) {
                try {
                    Mtproto.MsgSplitter(initBytes)
                } catch (_: Exception) {
                    null
                }
            } else {
                null
            }

            ws.send(initBytes)
            bridgeWs(input, output, ws, label, splitter)
        } catch (e: SocketTimeoutException) {
            AppLogger.w("Proxy", "[$label] timeout during SOCKS5 handshake")
        } catch (_: CancellationException) {
            AppLogger.d("Proxy", "[$label] cancelled")
        } catch (e: EOFException) {
            AppLogger.d("Proxy", "[$label] client disconnected")
        } catch (e: Exception) {
            AppLogger.e("Proxy", "[$label] unexpected error: ${e.message}", e)
        } finally {
            try {
                socket.close()
            } catch (_: Exception) {
            }
        }
    }

    private suspend fun handleUdpAssociate(
        controlSocket: Socket,
        output: OutputStream,
        label: String
    ) = withContext(Dispatchers.IO) {
        val udp = DatagramSocket(0, InetAddress.getByName("0.0.0.0"))
        udp.soTimeout = 1_000

        val bindAddr = InetAddress.getByName("127.0.0.1")
        output.write(socks5ReplyAddr(0x00, bindAddr, udp.localPort))
        output.flush()

        AppLogger.i("Proxy", "[$label] UDP associate -> ${bindAddr.hostAddress}:${udp.localPort}")

        val controlIp = controlSocket.inetAddress
        var clientAddr: SocketAddress? = null
        val buffer = ByteArray(65536)

        try {
            while (isRunning && !controlSocket.isClosed) {
                try {
                    val packet = DatagramPacket(buffer, buffer.size)
                    udp.receive(packet)
                    val from = packet.socketAddress
                    val data = packet.data.copyOfRange(0, packet.length)

                    val fromClient = (from as? InetSocketAddress)?.address == controlIp ||
                        (clientAddr != null && from == clientAddr)

                    if (fromClient) {
                        if (clientAddr == null) clientAddr = from
                        val parsed = parseUdpRequest(data) ?: continue
                        val dest = parsed.first
                        val payload = parsed.second
                        val outPacket = DatagramPacket(payload, payload.size, dest.address, dest.port)
                        udp.send(outPacket)
                    } else {
                        val client = clientAddr as? InetSocketAddress ?: continue
                        val wrapped = buildUdpResponse(packet.address, packet.port, data)
                        if (wrapped.isEmpty()) continue
                        val outPacket = DatagramPacket(wrapped, wrapped.size, client.address, client.port)
                        udp.send(outPacket)
                    }
                } catch (_: SocketTimeoutException) {
                    // idle tick
                }
            }
        } catch (_: Exception) {
        } finally {
            try { udp.close() } catch (_: Exception) { }
        }
    }

    private fun parseUdpRequest(data: ByteArray): Pair<InetSocketAddress, ByteArray>? {
        if (data.size < 4) return null
        if (data[0].toInt() != 0 || data[1].toInt() != 0) return null
        val frag = data[2].toInt() and 0xFF
        if (frag != 0) return null
        val atyp = data[3].toInt() and 0xFF
        var idx = 4

        val address: InetAddress = when (atyp) {
            0x01 -> {
                if (data.size < idx + 4 + 2) return null
                val raw = data.copyOfRange(idx, idx + 4)
                idx += 4
                InetAddress.getByAddress(raw)
            }
            0x03 -> {
                if (data.size < idx + 1) return null
                val len = data[idx].toInt() and 0xFF
                idx += 1
                if (data.size < idx + len + 2) return null
                val host = String(data.copyOfRange(idx, idx + len))
                idx += len
                InetAddress.getByName(host)
            }
            0x04 -> {
                return null
            }
            else -> return null
        }

        if (data.size < idx + 2) return null
        val port = ((data[idx].toInt() and 0xFF) shl 8) or (data[idx + 1].toInt() and 0xFF)
        idx += 2
        if (data.size < idx) return null
        if (!allowIpv6 && address.address.size == 16) return null
        val payload = data.copyOfRange(idx, data.size)
        return InetSocketAddress(address, port) to payload
    }

    private fun buildUdpResponse(address: InetAddress, port: Int, payload: ByteArray): ByteArray {
        val addrBytes = address.address
        if (addrBytes.size != 4) return ByteArray(0)
        val out = ByteArray(4 + 4 + 2 + payload.size)
        out[0] = 0x00
        out[1] = 0x00
        out[2] = 0x00
        out[3] = 0x01
        System.arraycopy(addrBytes, 0, out, 4, 4)
        out[8] = ((port shr 8) and 0xFF).toByte()
        out[9] = (port and 0xFF).toByte()
        System.arraycopy(payload, 0, out, 10, payload.size)
        return out
    }

    private suspend fun bridgeWs(
        input: InputStream,
        output: OutputStream,
        ws: RawWebSocket,
        label: String,
        splitter: Mtproto.MsgSplitter?
    ) = withContext(Dispatchers.IO) {

        val upBuffer = ByteArray(ProxyConstants.RECV_BUF)
        val jobUp = scope.launch {
            try {
                while (true) {
                    val n = input.read(upBuffer)
                    if (n <= 0) break
                    stats.addUp(n.toLong())
                    val chunk = upBuffer.copyOfRange(0, n)
                    if (splitter != null) {
                        val parts = splitter.split(chunk)
                        if (parts.size > 1) {
                            ws.sendBatch(parts)
                        } else {
                            ws.send(parts[0])
                        }
                    } else {
                        ws.send(chunk)
                    }
                }
            } catch (_: Exception) {
            }
        }

        val jobDown = scope.launch {
            try {
                while (true) {
                    val data = ws.recv() ?: break
                    stats.addDown(data.size.toLong())
                    output.write(data)
                    output.flush()
                }
            } catch (_: Exception) {
            }
        }

        select<Unit> {
            jobUp.onJoin { }
            jobDown.onJoin { }
        }
        jobUp.cancel()
        jobDown.cancel()
        try { jobUp.join() } catch (_: Exception) { }
        try { jobDown.join() } catch (_: Exception) { }

        AppLogger.i("Proxy", "[$label] WS session closed")
        try {
            ws.close()
        } catch (_: Exception) {
        }
    }

    private suspend fun bridgeTcp(
        clientIn: InputStream,
        clientOut: OutputStream,
        remote: Socket,
        label: String
    ) = withContext(Dispatchers.IO) {
        val remoteIn = BufferedInputStream(remote.getInputStream(), ProxyConstants.RECV_BUF)
        val remoteOut = BufferedOutputStream(remote.getOutputStream(), ProxyConstants.SEND_BUF)

        val bufferUp = ByteArray(65536)
        val bufferDown = ByteArray(65536)

        val upJob = scope.launch {
            try {
                while (true) {
                    val n = clientIn.read(bufferUp)
                    if (n <= 0) break
                    stats.addUp(n.toLong())
                    remoteOut.write(bufferUp, 0, n)
                    remoteOut.flush()
                }
            } catch (_: Exception) {
            }
        }

        val downJob = scope.launch {
            try {
                while (true) {
                    val n = remoteIn.read(bufferDown)
                    if (n <= 0) break
                    stats.addDown(n.toLong())
                    clientOut.write(bufferDown, 0, n)
                    clientOut.flush()
                }
            } catch (_: Exception) {
            }
        }

        select<Unit> {
            upJob.onJoin { }
            downJob.onJoin { }
        }
        upJob.cancel()
        downJob.cancel()
        try { upJob.join() } catch (_: Exception) { }
        try { downJob.join() } catch (_: Exception) { }

        AppLogger.i("Proxy", "[$label] TCP session closed")
        try {
            remote.close()
        } catch (_: Exception) {
        }
    }

    private suspend fun tcpFallback(
        clientIn: InputStream,
        clientOut: OutputStream,
        dst: String,
        port: Int,
        init: ByteArray,
        label: String
    ) = withContext(Dispatchers.IO) {
        val remote = try {
            openSocket(dst, port, ProxyConstants.SOCKET_CONNECT_TIMEOUT_MS)
        } catch (e: Exception) {
            AppLogger.w("Proxy", "[$label] TCP fallback connect failed: ${e.message}")
            return@withContext
        }

        stats.incTcpFallback()
        val remoteOut = BufferedOutputStream(remote.getOutputStream(), ProxyConstants.SEND_BUF)
        remoteOut.write(init)
        remoteOut.flush()
        bridgeTcp(clientIn, clientOut, remote, label)
    }

    private fun openSocket(host: String, port: Int, timeoutMs: Int): Socket {
        val socket = Socket()
        socket.tcpNoDelay = ProxyConstants.TCP_NODELAY
        socket.receiveBufferSize = ProxyConstants.RECV_BUF
        socket.sendBufferSize = ProxyConstants.SEND_BUF
        socket.connect(InetSocketAddress(host, port), timeoutMs)
        socket.soTimeout = ProxyConstants.SOCKET_READ_TIMEOUT_MS
        return socket
    }

    private fun isHttpTransport(data: ByteArray): Boolean {
        if (data.size < 4) return false
        return startsWith(data, "POST ".toByteArray()) ||
            startsWith(data, "GET ".toByteArray()) ||
            startsWith(data, "HEAD ".toByteArray()) ||
            startsWith(data, "OPTIONS ".toByteArray())
    }

    private fun startsWith(data: ByteArray, prefix: ByteArray): Boolean {
        if (data.size < prefix.size) return false
        for (i in prefix.indices) {
            if (data[i] != prefix[i]) return false
        }
        return true
    }

    private fun socks5Reply(status: Int): ByteArray {
        return byteArrayOf(0x05, status.toByte(), 0x00, 0x01, 0, 0, 0, 0, 0, 0)
    }

    private fun socks5ReplyAddr(status: Int, addr: InetAddress, port: Int): ByteArray {
        val addrBytes = addr.address
        if (addrBytes.size != 4) return socks5Reply(status)
        return byteArrayOf(
            0x05,
            status.toByte(),
            0x00,
            0x01,
            addrBytes[0],
            addrBytes[1],
            addrBytes[2],
            addrBytes[3],
            ((port shr 8) and 0xFF).toByte(),
            (port and 0xFF).toByte()
        )
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

    private fun startKeepAlive(host: String, port: Int, intervalSec: Int) {
        keepAliveJob?.cancel()
        val safeInterval = intervalSec.coerceIn(10, 600)
        keepAliveJob = scope.launch {
            while (true) {
                delay(safeInterval * 1000L)
                try {
                    runKeepAlive(host, port)
                } catch (_: Exception) {
                }
            }
        }
    }

    private suspend fun runKeepAlive(host: String, port: Int) = withContext(Dispatchers.IO) {
        val socket = Socket()
        try {
            socket.tcpNoDelay = true
            socket.connect(InetSocketAddress(host, port), 2_000)
            socket.soTimeout = 2_000
            val out = socket.getOutputStream()
            val input = socket.getInputStream()

            // SOCKS5 greeting
            out.write(byteArrayOf(0x05, 0x01, 0x00))
            out.flush()
            readExact(input, 2)

            // CONNECT to 0.0.0.0:0 (special keep-alive no-op)
            val req = byteArrayOf(
                0x05, 0x01, 0x00, 0x01,
                0x00, 0x00, 0x00, 0x00,
                0x00, 0x00
            )
            out.write(req)
            out.flush()
            readExact(input, 10)
        } catch (_: Exception) {
        } finally {
            try { socket.close() } catch (_: Exception) { }
        }
    }

    private suspend fun logStatsLoop() {
        while (true) {
            delay(60_000)
            val snapshot = synchronized(wsBlacklist) { wsBlacklist.toList() }
            val bl = if (snapshot.isEmpty()) "none" else snapshot.joinToString(",") {
                "DC${it.dc}${if (it.isMedia) "m" else ""}"
            }
            AppLogger.i("Proxy", "stats: ${stats.summary()} | ws_bl: $bl")
        }
    }

    private fun parseDcIpList(dcIpList: List<String>): Map<Int, String> {
        val dcOpt = mutableMapOf<Int, String>()
        for (entry in dcIpList) {
            val idx = entry.indexOf(':')
            if (idx <= 0) continue
            val dcStr = entry.substring(0, idx)
            val ip = entry.substring(idx + 1)
            val dc = dcStr.toIntOrNull() ?: continue
            try {
                ProxyConstants.ipToLong(ip)
                dcOpt[dc] = ip
            } catch (_: Exception) {
            }
        }
        return dcOpt
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

