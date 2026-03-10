package org.telegram.messenger;

import android.net.VpnService;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.text.TextUtils;

import java.io.Closeable;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.Arrays;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class ZapretTunnelRuntime {

    private static final int PROTOCOL_TCP = 6;
    private static final int PROTOCOL_UDP = 17;

    private static final int FLAG_FIN = 0x01;
    private static final int FLAG_SYN = 0x02;
    private static final int FLAG_RST = 0x04;
    private static final int FLAG_PSH = 0x08;
    private static final int FLAG_ACK = 0x10;

    private static final int IP_HEADER_LENGTH = 20;
    private static final int TCP_HEADER_LENGTH = 20;
    private static final int UDP_HEADER_LENGTH = 8;
    private static final int MAX_PACKET_SIZE = 32 * 1024;
    private static final int MAX_TCP_SEGMENT_SIZE = 1360;
    private static final long TCP_SESSION_TIMEOUT_MS = 2 * 60 * 1000L;
    private static final long TCP_CONNECT_TIMEOUT_MS = 15 * 1000L;
    private static final long UDP_SESSION_TIMEOUT_MS = 45 * 1000L;

    private final VpnService service;
    private final ZapretVpnService owner;
    private final ParcelFileDescriptor tunnelInterface;
    private final ZapretDesyncProfile desyncProfile;
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicInteger packetId = new AtomicInteger(Utilities.fastRandom.nextInt() & 0xFFFF);
    private final Object tunWriteLock = new Object();
    private final Map<FlowKey, TcpSession> tcpSessions = new ConcurrentHashMap<>();
    private final Map<FlowKey, UdpSession> udpSessions = new ConcurrentHashMap<>();

    private FileInputStream tunInput;
    private FileOutputStream tunOutput;
    private Selector selector;
    private Thread readerThread;
    private Thread networkThread;

    public ZapretTunnelRuntime(ZapretVpnService owner, ParcelFileDescriptor tunnelInterface) {
        this.owner = owner;
        this.service = owner;
        this.tunnelInterface = tunnelInterface;
        this.desyncProfile = ZapretDesyncProfile.parse(ZapretConfig.getActiveConfig());
    }

    public void start() throws IOException {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        boolean success = false;
        try {
            selector = Selector.open();
            tunInput = new FileInputStream(tunnelInterface.getFileDescriptor());
            tunOutput = new FileOutputStream(tunnelInterface.getFileDescriptor());
            readerThread = new Thread(this::runReaderLoop, "ZapretTunReader");
            networkThread = new Thread(this::runNetworkLoop, "ZapretTunNetwork");
            readerThread.start();
            networkThread.start();
            if (!desyncProfile.isEmpty()) {
                ZapretDiagnosticsController.getInstance().logRuntimeEvent("desync profile loaded from active zapret config");
            }
            success = true;
        } finally {
            if (!success) {
                stop();
            }
        }
    }

    public void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        closeSessions();
        closeQuietly(selector);
        selector = null;
        closeQuietly(tunInput);
        tunInput = null;
        synchronized (tunWriteLock) {
            closeQuietly(tunOutput);
            tunOutput = null;
        }
    }

    private void runReaderLoop() {
        byte[] buffer = new byte[MAX_PACKET_SIZE];
        try {
            while (running.get()) {
                int length = tunInput.read(buffer);
                if (length <= 0) {
                    if (length < 0) {
                        break;
                    }
                    continue;
                }
                dispatchPacket(buffer, length);
            }
        } catch (Throwable e) {
            if (running.get()) {
                FileLog.e(e);
                notifyRuntimeFailure(e);
            }
        }
    }

    private void runNetworkLoop() {
        try {
            while (running.get()) {
                int selected = selector.select(500);
                if (!running.get()) {
                    break;
                }
                if (selected > 0) {
                    Iterator<SelectionKey> iterator = selector.selectedKeys().iterator();
                    while (iterator.hasNext()) {
                        SelectionKey key = iterator.next();
                        iterator.remove();
                        if (!key.isValid()) {
                            continue;
                        }
                        Object attachment = key.attachment();
                        if (attachment instanceof SocketSession) {
                            ((SocketSession) attachment).processSelectionKey(key);
                        }
                    }
                }
                cleanupExpiredSessions();
            }
        } catch (Throwable e) {
            if (running.get()) {
                FileLog.e(e);
                notifyRuntimeFailure(e);
            }
        }
    }

    private void cleanupExpiredSessions() {
        long now = SystemClock.elapsedRealtime();
        for (TcpSession session : tcpSessions.values()) {
            if (session.isExpired(now)) {
                session.close("tcp session timeout");
            }
        }
        for (UdpSession session : udpSessions.values()) {
            if (session.isExpired(now)) {
                session.close("udp session timeout");
            }
        }
    }

    private void dispatchPacket(byte[] buffer, int length) {
        Packet packet = Packet.parse(buffer, length);
        if (packet == null || packet.fragmented) {
            return;
        }
        if (packet.protocol == PROTOCOL_TCP) {
            handleTcpPacket(packet);
        } else if (packet.protocol == PROTOCOL_UDP) {
            handleUdpPacket(packet);
        }
    }

    private void handleTcpPacket(Packet packet) {
        FlowKey key = packet.getFlowKey();
        TcpSession session = tcpSessions.get(key);
        if (session == null) {
            if (!packet.isFlagSet(FLAG_SYN) || packet.isFlagSet(FLAG_ACK)) {
                return;
            }
            session = new TcpSession(key, packet.sourceAddress, packet.destinationAddress, packet.sourcePort, packet.destinationPort, packet.sequenceNumber);
            TcpSession existing = tcpSessions.putIfAbsent(key, session);
            if (existing != null) {
                session = existing;
            }
        }
        session.onClientPacket(packet);
    }

    private void handleUdpPacket(Packet packet) {
        FlowKey key = packet.getFlowKey();
        UdpSession session = udpSessions.get(key);
        if (session == null) {
            session = new UdpSession(key, packet.sourceAddress, packet.destinationAddress, packet.sourcePort, packet.destinationPort);
            UdpSession existing = udpSessions.putIfAbsent(key, session);
            if (existing != null) {
                session = existing;
            }
        }
        session.onClientPacket(packet);
    }

    private void notifyRuntimeFailure(Throwable e) {
        stop();
        owner.onRuntimeError(e.getMessage());
    }

    private void logDesyncApplied(String protocol, int port, String label) {
        if (TextUtils.isEmpty(label)) {
            return;
        }
        ZapretDiagnosticsController.getInstance().logRuntimeEvent("desync " + protocol + "/" + port + ": " + label);
    }

    private void closeSessions() {
        for (TcpSession session : tcpSessions.values()) {
            session.close("runtime stopping");
        }
        for (UdpSession session : udpSessions.values()) {
            session.close("runtime stopping");
        }
        tcpSessions.clear();
        udpSessions.clear();
    }

    private void writeTunPacket(byte[] packet) throws IOException {
        synchronized (tunWriteLock) {
            if (!running.get() || tunOutput == null) {
                return;
            }
            tunOutput.write(packet);
        }
    }

    private int nextPacketId() {
        return packetId.updateAndGet(value -> (value + 1) & 0xFFFF);
    }

    private void removeTcpSession(FlowKey key, TcpSession session) {
        tcpSessions.remove(key, session);
    }

    private void removeUdpSession(FlowKey key, UdpSession session) {
        udpSessions.remove(key, session);
    }

    private void registerChannel(SelectableChannel channel, int ops, SocketSession session) throws IOException {
        if (!running.get() || selector == null) {
            throw new IOException("selector stopped");
        }
        selector.wakeup();
        session.selectionKey = channel.register(selector, ops, session);
    }

    private void closeQuietly(Closeable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (Throwable ignored) {
        }
    }

    private void closeQuietly(Selector closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (Throwable ignored) {
        }
    }

    private static InetSocketAddress toSocketAddress(int address, int port) throws IOException {
        byte[] bytes = new byte[4];
        bytes[0] = (byte) ((address >> 24) & 0xFF);
        bytes[1] = (byte) ((address >> 16) & 0xFF);
        bytes[2] = (byte) ((address >> 8) & 0xFF);
        bytes[3] = (byte) (address & 0xFF);
        return new InetSocketAddress(InetAddress.getByAddress(bytes), port);
    }

    private static int readUnsignedShort(byte[] data, int offset) {
        return ((data[offset] & 0xFF) << 8) | (data[offset + 1] & 0xFF);
    }

    private static int readInt(byte[] data, int offset) {
        return ((data[offset] & 0xFF) << 24)
            | ((data[offset + 1] & 0xFF) << 16)
            | ((data[offset + 2] & 0xFF) << 8)
            | (data[offset + 3] & 0xFF);
    }

    private static long readUnsignedInt(byte[] data, int offset) {
        return readInt(data, offset) & 0xFFFFFFFFL;
    }

    private static void writeShort(byte[] data, int offset, int value) {
        data[offset] = (byte) ((value >> 8) & 0xFF);
        data[offset + 1] = (byte) (value & 0xFF);
    }

    private static void writeInt(byte[] data, int offset, long value) {
        data[offset] = (byte) ((value >> 24) & 0xFF);
        data[offset + 1] = (byte) ((value >> 16) & 0xFF);
        data[offset + 2] = (byte) ((value >> 8) & 0xFF);
        data[offset + 3] = (byte) (value & 0xFF);
    }

    private static int computeChecksum(byte[] data, int offset, int length, int seed) {
        long sum = seed & 0xFFFFFFFFL;
        int index = offset;
        while (length > 1) {
            sum += readUnsignedShort(data, index);
            index += 2;
            length -= 2;
        }
        if (length > 0) {
            sum += (data[index] & 0xFF) << 8;
        }
        while ((sum >> 16) != 0) {
            sum = (sum & 0xFFFF) + (sum >> 16);
        }
        return (int) (~sum) & 0xFFFF;
    }

    private static int computeIpv4HeaderChecksum(byte[] packet, int headerLength) {
        writeShort(packet, 10, 0);
        return computeChecksum(packet, 0, headerLength, 0);
    }

    private static int computePseudoHeaderSum(int sourceAddress, int destinationAddress, int protocol, int length) {
        long sum = 0;
        sum += (sourceAddress >> 16) & 0xFFFF;
        sum += sourceAddress & 0xFFFF;
        sum += (destinationAddress >> 16) & 0xFFFF;
        sum += destinationAddress & 0xFFFF;
        sum += protocol & 0xFF;
        sum += length & 0xFFFF;
        while ((sum >> 16) != 0) {
            sum = (sum & 0xFFFF) + (sum >> 16);
        }
        return (int) sum;
    }

    private byte[] buildTcpPacket(int sourceAddress, int destinationAddress, int sourcePort, int destinationPort, long sequenceNumber, long acknowledgementNumber, int flags, byte[] payload) {
        int payloadLength = payload != null ? payload.length : 0;
        int totalLength = IP_HEADER_LENGTH + TCP_HEADER_LENGTH + payloadLength;
        byte[] packet = new byte[totalLength];

        packet[0] = 0x45;
        packet[1] = 0;
        writeShort(packet, 2, totalLength);
        writeShort(packet, 4, nextPacketId());
        writeShort(packet, 6, 0);
        packet[8] = 64;
        packet[9] = (byte) PROTOCOL_TCP;
        writeInt(packet, 12, sourceAddress & 0xFFFFFFFFL);
        writeInt(packet, 16, destinationAddress & 0xFFFFFFFFL);
        writeShort(packet, 10, computeIpv4HeaderChecksum(packet, IP_HEADER_LENGTH));

        int tcpOffset = IP_HEADER_LENGTH;
        writeShort(packet, tcpOffset, sourcePort);
        writeShort(packet, tcpOffset + 2, destinationPort);
        writeInt(packet, tcpOffset + 4, sequenceNumber);
        writeInt(packet, tcpOffset + 8, acknowledgementNumber);
        packet[tcpOffset + 12] = (byte) (TCP_HEADER_LENGTH << 2);
        packet[tcpOffset + 13] = (byte) (flags & 0x3F);
        writeShort(packet, tcpOffset + 14, 65535);
        writeShort(packet, tcpOffset + 16, 0);
        writeShort(packet, tcpOffset + 18, 0);

        if (payloadLength > 0) {
            System.arraycopy(payload, 0, packet, tcpOffset + TCP_HEADER_LENGTH, payloadLength);
        }

        int transportLength = TCP_HEADER_LENGTH + payloadLength;
        int checksumSeed = computePseudoHeaderSum(sourceAddress, destinationAddress, PROTOCOL_TCP, transportLength);
        writeShort(packet, tcpOffset + 16, computeChecksum(packet, tcpOffset, transportLength, checksumSeed));
        return packet;
    }

    private byte[] buildUdpPacket(int sourceAddress, int destinationAddress, int sourcePort, int destinationPort, byte[] payload, int payloadLength) {
        int totalLength = IP_HEADER_LENGTH + UDP_HEADER_LENGTH + payloadLength;
        byte[] packet = new byte[totalLength];

        packet[0] = 0x45;
        packet[1] = 0;
        writeShort(packet, 2, totalLength);
        writeShort(packet, 4, nextPacketId());
        writeShort(packet, 6, 0);
        packet[8] = 64;
        packet[9] = (byte) PROTOCOL_UDP;
        writeInt(packet, 12, sourceAddress & 0xFFFFFFFFL);
        writeInt(packet, 16, destinationAddress & 0xFFFFFFFFL);
        writeShort(packet, 10, computeIpv4HeaderChecksum(packet, IP_HEADER_LENGTH));

        int udpOffset = IP_HEADER_LENGTH;
        writeShort(packet, udpOffset, sourcePort);
        writeShort(packet, udpOffset + 2, destinationPort);
        writeShort(packet, udpOffset + 4, UDP_HEADER_LENGTH + payloadLength);
        writeShort(packet, udpOffset + 6, 0);

        if (payloadLength > 0) {
            System.arraycopy(payload, 0, packet, udpOffset + UDP_HEADER_LENGTH, payloadLength);
        }
        return packet;
    }

    private abstract class SocketSession {

        protected final FlowKey flowKey;
        protected final int clientAddress;
        protected final int remoteAddress;
        protected final int clientPort;
        protected final int remotePort;
        protected volatile long lastActivityTime = SystemClock.elapsedRealtime();
        protected volatile SelectionKey selectionKey;
        protected volatile boolean closed;

        protected SocketSession(FlowKey flowKey, int clientAddress, int remoteAddress, int clientPort, int remotePort) {
            this.flowKey = flowKey;
            this.clientAddress = clientAddress;
            this.remoteAddress = remoteAddress;
            this.clientPort = clientPort;
            this.remotePort = remotePort;
        }

        protected void touch() {
            lastActivityTime = SystemClock.elapsedRealtime();
        }

        protected void updateInterestOps(int ops) {
            SelectionKey key = selectionKey;
            if (key == null || !key.isValid() || selector == null) {
                return;
            }
            selector.wakeup();
            key.interestOps(ops);
        }

        protected abstract void processSelectionKey(SelectionKey key) throws IOException;

        protected abstract boolean isExpired(long now);

        protected abstract void close(String reason);
    }

    private final class UdpSession extends SocketSession {

        private DatagramChannel channel;
        private final java.util.ArrayDeque<java.nio.ByteBuffer> pendingOutbound = new java.util.ArrayDeque<>();
        private int outboundPacketIndex;
        private boolean desyncLogged;

        private UdpSession(FlowKey flowKey, int clientAddress, int remoteAddress, int clientPort, int remotePort) {
            super(flowKey, clientAddress, remoteAddress, clientPort, remotePort);
        }

        public synchronized void onClientPacket(Packet packet) {
            if (closed || packet.payloadLength <= 0) {
                return;
            }
            touch();
            byte[] payload = packet.getPayloadCopy();
            ZapretDesyncProfile.UdpPlan plan = desyncProfile.buildUdpPlan(remotePort, payload, outboundPacketIndex++);
            if (plan != null) {
                for (byte[] datagram : plan.datagrams) {
                    pendingOutbound.add(java.nio.ByteBuffer.wrap(datagram));
                }
                if (!desyncLogged) {
                    desyncLogged = true;
                    logDesyncApplied("udp", remotePort, plan.label);
                }
            } else {
                pendingOutbound.add(java.nio.ByteBuffer.wrap(payload));
            }
            try {
                ensureChannel();
                updateInterestOps(SelectionKey.OP_READ | SelectionKey.OP_WRITE);
            } catch (Throwable e) {
                FileLog.e(e);
                close(e.getMessage());
            }
        }

        private void ensureChannel() throws IOException {
            if (channel != null) {
                return;
            }
            channel = DatagramChannel.open();
            channel.configureBlocking(false);
            if (!service.protect(channel.socket())) {
                throw new SocketException("failed to protect udp socket");
            }
            channel.connect(toSocketAddress(remoteAddress, remotePort));
            registerChannel(channel, SelectionKey.OP_READ | (pendingOutbound.isEmpty() ? 0 : SelectionKey.OP_WRITE), this);
        }

        @Override
        protected synchronized void processSelectionKey(SelectionKey key) throws IOException {
            if (closed) {
                return;
            }
            if (key.isReadable()) {
                java.nio.ByteBuffer buffer = java.nio.ByteBuffer.allocate(MAX_PACKET_SIZE);
                int read = channel.read(buffer);
                if (read > 0) {
                    byte[] payload = Arrays.copyOf(buffer.array(), read);
                    writeTunPacket(buildUdpPacket(remoteAddress, clientAddress, remotePort, clientPort, payload, read));
                    touch();
                }
            }
            if (key.isWritable()) {
                while (!pendingOutbound.isEmpty()) {
                    java.nio.ByteBuffer packet = pendingOutbound.peek();
                    channel.write(packet);
                    if (packet.hasRemaining()) {
                        break;
                    }
                    pendingOutbound.removeFirst();
                    touch();
                }
                updateInterestOps(SelectionKey.OP_READ | (pendingOutbound.isEmpty() ? 0 : SelectionKey.OP_WRITE));
            }
        }

        @Override
        protected boolean isExpired(long now) {
            return now - lastActivityTime > UDP_SESSION_TIMEOUT_MS;
        }

        @Override
        protected synchronized void close(String reason) {
            if (closed) {
                return;
            }
            closed = true;
            SelectionKey key = selectionKey;
            if (key != null) {
                key.cancel();
                selectionKey = null;
            }
            closeQuietly(channel);
            channel = null;
            pendingOutbound.clear();
            removeUdpSession(flowKey, this);
        }
    }

    private final class TcpSession extends SocketSession {

        private final long serverInitialSequence = Utilities.fastRandom.nextInt() & 0xFFFFFFFFL;
        private long nextServerSequence = serverInitialSequence + 1;
        private long nextClientSequence;
        private boolean handshakeAcknowledged;
        private boolean remoteConnected;
        private boolean remoteClosed;
        private boolean remoteFinSent;
        private boolean clientFinReceived;
        private boolean shutdownOutputPending;
        private boolean connectAttempted;

        private SocketChannel channel;
        private final java.util.ArrayDeque<java.nio.ByteBuffer> pendingOutbound = new java.util.ArrayDeque<>();
        private final java.util.ArrayDeque<byte[]> pendingInbound = new java.util.ArrayDeque<>();
        private int outboundPayloadIndex;
        private boolean desyncLogged;

        private TcpSession(FlowKey flowKey, int clientAddress, int remoteAddress, int clientPort, int remotePort, long clientInitialSequence) {
            super(flowKey, clientAddress, remoteAddress, clientPort, remotePort);
            nextClientSequence = (clientInitialSequence + 1) & 0xFFFFFFFFL;
        }

        public synchronized void onClientPacket(Packet packet) {
            if (closed) {
                return;
            }
            touch();

            if (packet.isFlagSet(FLAG_RST)) {
                close("client rst");
                return;
            }

            if (packet.isFlagSet(FLAG_SYN) && !packet.isFlagSet(FLAG_ACK) && packet.payloadLength == 0) {
                if (!connectAttempted) {
                    try {
                        ensureChannel();
                    } catch (Throwable e) {
                        FileLog.e(e);
                        sendResetSafely();
                        close(e.getMessage());
                        return;
                    }
                }
                sendSynAckSafely();
                return;
            }

            if (packet.sequenceNumber != nextClientSequence) {
                sendAckSafely();
                return;
            }

            if (!handshakeAcknowledged && packet.isFlagSet(FLAG_ACK)) {
                handshakeAcknowledged = true;
                flushPendingInbound();
            }

            if (packet.payloadLength > 0) {
                byte[] payload = packet.getPayloadCopy();
                ZapretDesyncProfile.TcpPlan plan = desyncProfile.buildTcpPlan(remotePort, payload, outboundPayloadIndex++);
                if (plan != null) {
                    for (byte[] chunk : plan.chunks) {
                        pendingOutbound.add(java.nio.ByteBuffer.wrap(chunk));
                    }
                    if (!desyncLogged) {
                        desyncLogged = true;
                        logDesyncApplied("tcp", remotePort, plan.label);
                    }
                } else {
                    pendingOutbound.add(java.nio.ByteBuffer.wrap(payload));
                }
                nextClientSequence = (nextClientSequence + packet.payloadLength) & 0xFFFFFFFFL;
                sendAckSafely();
                if (remoteConnected) {
                    updateInterestOps(SelectionKey.OP_READ | SelectionKey.OP_WRITE);
                }
            }

            if (packet.isFlagSet(FLAG_FIN)) {
                clientFinReceived = true;
                nextClientSequence = (nextClientSequence + 1) & 0xFFFFFFFFL;
                sendAckSafely();
                if (remoteConnected && channel != null && channel.isOpen()) {
                    try {
                        channel.socket().shutdownOutput();
                    } catch (Throwable e) {
                        FileLog.e(e);
                    }
                } else {
                    shutdownOutputPending = true;
                }
                if (remoteClosed && !remoteFinSent) {
                    sendFinSafely();
                }
            }

            if (remoteFinSent && packet.isFlagSet(FLAG_ACK) && packet.acknowledgementNumber == nextServerSequence) {
                close("tcp closed");
            }
        }

        private void ensureChannel() throws IOException {
            if (channel != null) {
                return;
            }
            connectAttempted = true;
            channel = SocketChannel.open();
            channel.configureBlocking(false);
            channel.socket().setTcpNoDelay(true);
            if (!service.protect(channel.socket())) {
                throw new SocketException("failed to protect tcp socket");
            }
            boolean connected = channel.connect(toSocketAddress(remoteAddress, remotePort));
            remoteConnected = connected;
            int ops = connected ? SelectionKey.OP_READ : SelectionKey.OP_CONNECT;
            if (connected && !pendingOutbound.isEmpty()) {
                ops |= SelectionKey.OP_WRITE;
            }
            registerChannel(channel, ops, this);
            if (connected && shutdownOutputPending && pendingOutbound.isEmpty()) {
                channel.socket().shutdownOutput();
                shutdownOutputPending = false;
            }
        }

        @Override
        protected synchronized void processSelectionKey(SelectionKey key) throws IOException {
            if (closed) {
                return;
            }
            if (key.isConnectable()) {
                handleConnect();
            }
            if (key.isReadable()) {
                handleReadable();
            }
            if (key.isWritable()) {
                handleWritable();
            }
        }

        private void handleConnect() {
            try {
                if (channel == null || !channel.finishConnect()) {
                    return;
                }
                remoteConnected = true;
                touch();
                if (shutdownOutputPending && pendingOutbound.isEmpty()) {
                    channel.socket().shutdownOutput();
                    shutdownOutputPending = false;
                }
                updateInterestOps(SelectionKey.OP_READ | (pendingOutbound.isEmpty() ? 0 : SelectionKey.OP_WRITE));
            } catch (Throwable e) {
                FileLog.e(e);
                sendResetSafely();
                close(e.getMessage());
            }
        }

        private void handleReadable() {
            try {
                java.nio.ByteBuffer buffer = java.nio.ByteBuffer.allocate(MAX_PACKET_SIZE);
                int read = channel.read(buffer);
                if (read < 0) {
                    remoteClosed = true;
                    if (!remoteFinSent) {
                        sendFinSafely();
                    }
                    updateInterestOps(pendingOutbound.isEmpty() ? 0 : SelectionKey.OP_WRITE);
                    return;
                }
                if (read == 0) {
                    return;
                }
                byte[] payload = Arrays.copyOf(buffer.array(), read);
                if (!handshakeAcknowledged) {
                    pendingInbound.add(payload);
                } else {
                    sendInboundPayload(payload);
                }
                touch();
            } catch (Throwable e) {
                FileLog.e(e);
                sendResetSafely();
                close(e.getMessage());
            }
        }

        private void handleWritable() {
            try {
                while (!pendingOutbound.isEmpty()) {
                    java.nio.ByteBuffer data = pendingOutbound.peek();
                    channel.write(data);
                    if (data.hasRemaining()) {
                        break;
                    }
                    pendingOutbound.removeFirst();
                    touch();
                }
                if (pendingOutbound.isEmpty()) {
                    if (shutdownOutputPending && remoteConnected && channel != null && channel.isOpen()) {
                        channel.socket().shutdownOutput();
                        shutdownOutputPending = false;
                    }
                    updateInterestOps(remoteClosed ? 0 : SelectionKey.OP_READ);
                } else {
                    updateInterestOps(SelectionKey.OP_READ | SelectionKey.OP_WRITE);
                }
            } catch (Throwable e) {
                FileLog.e(e);
                sendResetSafely();
                close(e.getMessage());
            }
        }

        private void flushPendingInbound() {
            while (!pendingInbound.isEmpty()) {
                sendInboundPayload(pendingInbound.removeFirst());
            }
        }

        private void sendInboundPayload(byte[] payload) {
            int offset = 0;
            while (offset < payload.length) {
                int chunkSize = Math.min(MAX_TCP_SEGMENT_SIZE, payload.length - offset);
                byte[] chunk = new byte[chunkSize];
                System.arraycopy(payload, offset, chunk, 0, chunkSize);
                offset += chunkSize;
                try {
                    writeTunPacket(buildTcpPacket(remoteAddress, clientAddress, remotePort, clientPort, nextServerSequence, nextClientSequence, FLAG_ACK | FLAG_PSH, chunk));
                    nextServerSequence = (nextServerSequence + chunkSize) & 0xFFFFFFFFL;
                } catch (Throwable e) {
                    FileLog.e(e);
                    close(e.getMessage());
                    return;
                }
            }
        }

        private void sendSynAckSafely() {
            try {
                writeTunPacket(buildTcpPacket(remoteAddress, clientAddress, remotePort, clientPort, serverInitialSequence, nextClientSequence, FLAG_SYN | FLAG_ACK, null));
            } catch (Throwable e) {
                FileLog.e(e);
                close(e.getMessage());
            }
        }

        private void sendAckSafely() {
            try {
                writeTunPacket(buildTcpPacket(remoteAddress, clientAddress, remotePort, clientPort, nextServerSequence, nextClientSequence, FLAG_ACK, null));
            } catch (Throwable e) {
                FileLog.e(e);
                close(e.getMessage());
            }
        }

        private void sendFinSafely() {
            if (remoteFinSent) {
                return;
            }
            try {
                writeTunPacket(buildTcpPacket(remoteAddress, clientAddress, remotePort, clientPort, nextServerSequence, nextClientSequence, FLAG_FIN | FLAG_ACK, null));
                nextServerSequence = (nextServerSequence + 1) & 0xFFFFFFFFL;
                remoteFinSent = true;
            } catch (Throwable e) {
                FileLog.e(e);
                close(e.getMessage());
            }
        }

        private void sendResetSafely() {
            try {
                writeTunPacket(buildTcpPacket(remoteAddress, clientAddress, remotePort, clientPort, nextServerSequence, nextClientSequence, FLAG_RST | FLAG_ACK, null));
            } catch (Throwable e) {
                FileLog.e(e);
            }
        }

        @Override
        protected boolean isExpired(long now) {
            long timeout = remoteConnected ? TCP_SESSION_TIMEOUT_MS : TCP_CONNECT_TIMEOUT_MS;
            return now - lastActivityTime > timeout;
        }

        @Override
        protected synchronized void close(String reason) {
            if (closed) {
                return;
            }
            closed = true;
            SelectionKey key = selectionKey;
            if (key != null) {
                key.cancel();
                selectionKey = null;
            }
            closeQuietly(channel);
            channel = null;
            pendingOutbound.clear();
            pendingInbound.clear();
            removeTcpSession(flowKey, this);
        }
    }

    private static final class FlowKey {

        private final int sourceAddress;
        private final int sourcePort;
        private final int destinationAddress;
        private final int destinationPort;
        private final int protocol;

        private FlowKey(int sourceAddress, int sourcePort, int destinationAddress, int destinationPort, int protocol) {
            this.sourceAddress = sourceAddress;
            this.sourcePort = sourcePort;
            this.destinationAddress = destinationAddress;
            this.destinationPort = destinationPort;
            this.protocol = protocol;
        }

        @Override
        public boolean equals(Object object) {
            if (this == object) {
                return true;
            }
            if (!(object instanceof FlowKey)) {
                return false;
            }
            FlowKey other = (FlowKey) object;
            return sourceAddress == other.sourceAddress
                && sourcePort == other.sourcePort
                && destinationAddress == other.destinationAddress
                && destinationPort == other.destinationPort
                && protocol == other.protocol;
        }

        @Override
        public int hashCode() {
            int result = sourceAddress;
            result = 31 * result + sourcePort;
            result = 31 * result + destinationAddress;
            result = 31 * result + destinationPort;
            result = 31 * result + protocol;
            return result;
        }
    }

    private static final class Packet {

        private final int protocol;
        private final int sourceAddress;
        private final int destinationAddress;
        private final int sourcePort;
        private final int destinationPort;
        private final int flags;
        private final long sequenceNumber;
        private final long acknowledgementNumber;
        private final int payloadOffset;
        private final int payloadLength;
        private final byte[] data;
        private final boolean fragmented;

        private Packet(int protocol, int sourceAddress, int destinationAddress, int sourcePort, int destinationPort, int flags, long sequenceNumber, long acknowledgementNumber, int payloadOffset, int payloadLength, byte[] data, boolean fragmented) {
            this.protocol = protocol;
            this.sourceAddress = sourceAddress;
            this.destinationAddress = destinationAddress;
            this.sourcePort = sourcePort;
            this.destinationPort = destinationPort;
            this.flags = flags;
            this.sequenceNumber = sequenceNumber;
            this.acknowledgementNumber = acknowledgementNumber;
            this.payloadOffset = payloadOffset;
            this.payloadLength = payloadLength;
            this.data = data;
            this.fragmented = fragmented;
        }

        public static Packet parse(byte[] data, int length) {
            if (length < IP_HEADER_LENGTH) {
                return null;
            }
            int version = (data[0] >> 4) & 0xF;
            int ipHeaderLength = (data[0] & 0xF) * 4;
            if (version != 4 || ipHeaderLength < IP_HEADER_LENGTH || length < ipHeaderLength) {
                return null;
            }

            int totalLength = readUnsignedShort(data, 2);
            if (totalLength <= 0 || totalLength > length) {
                totalLength = length;
            }

            int fragmentInfo = readUnsignedShort(data, 6);
            boolean fragmented = (fragmentInfo & 0x3FFF) != 0;
            int protocol = data[9] & 0xFF;
            int sourceAddress = readInt(data, 12);
            int destinationAddress = readInt(data, 16);

            if (protocol == PROTOCOL_TCP) {
                if (totalLength < ipHeaderLength + TCP_HEADER_LENGTH) {
                    return null;
                }
                int sourcePort = readUnsignedShort(data, ipHeaderLength);
                int destinationPort = readUnsignedShort(data, ipHeaderLength + 2);
                long sequenceNumber = readUnsignedInt(data, ipHeaderLength + 4);
                long acknowledgementNumber = readUnsignedInt(data, ipHeaderLength + 8);
                int tcpHeaderLength = ((data[ipHeaderLength + 12] >> 4) & 0xF) * 4;
                int payloadOffset = ipHeaderLength + tcpHeaderLength;
                if (tcpHeaderLength < TCP_HEADER_LENGTH || payloadOffset > totalLength) {
                    return null;
                }
                return new Packet(protocol, sourceAddress, destinationAddress, sourcePort, destinationPort, data[ipHeaderLength + 13] & 0xFF, sequenceNumber, acknowledgementNumber, payloadOffset, totalLength - payloadOffset, data, fragmented);
            } else if (protocol == PROTOCOL_UDP) {
                if (totalLength < ipHeaderLength + UDP_HEADER_LENGTH) {
                    return null;
                }
                int sourcePort = readUnsignedShort(data, ipHeaderLength);
                int destinationPort = readUnsignedShort(data, ipHeaderLength + 2);
                int payloadOffset = ipHeaderLength + UDP_HEADER_LENGTH;
                if (payloadOffset > totalLength) {
                    return null;
                }
                return new Packet(protocol, sourceAddress, destinationAddress, sourcePort, destinationPort, 0, 0, 0, payloadOffset, totalLength - payloadOffset, data, fragmented);
            }
            return null;
        }

        public boolean isFlagSet(int value) {
            return (flags & value) != 0;
        }

        public byte[] getPayloadCopy() {
            if (payloadLength <= 0) {
                return new byte[0];
            }
            return Arrays.copyOfRange(data, payloadOffset, payloadOffset + payloadLength);
        }

        public FlowKey getFlowKey() {
            return new FlowKey(sourceAddress, sourcePort, destinationAddress, destinationPort, protocol);
        }
    }
}
