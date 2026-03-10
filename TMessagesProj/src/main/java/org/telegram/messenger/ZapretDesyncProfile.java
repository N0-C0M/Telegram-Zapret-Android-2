package org.telegram.messenger;

import android.text.TextUtils;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;

public final class ZapretDesyncProfile {

    private static final int DEFAULT_TCP_CUTOFF = 1;
    private static final int DEFAULT_UDP_CUTOFF = 2;
    private static final int MAX_TCP_SPLITS = 6;
    private static final int MAX_UDP_FAKES = 6;

    private final ArrayList<Rule> tcpRules;
    private final ArrayList<Rule> udpRules;

    private ZapretDesyncProfile(ArrayList<Rule> tcpRules, ArrayList<Rule> udpRules) {
        this.tcpRules = tcpRules;
        this.udpRules = udpRules;
    }

    public static ZapretDesyncProfile parse(String config) {
        ArrayList<Rule> tcpRules = new ArrayList<>();
        ArrayList<Rule> udpRules = new ArrayList<>();
        if (TextUtils.isEmpty(config)) {
            return new ZapretDesyncProfile(tcpRules, udpRules);
        }
        String[] lines = config.split("\n");
        for (String line : lines) {
            Rule rule = Rule.parse(line);
            if (rule == null) {
                continue;
            }
            if (rule.tcp) {
                tcpRules.add(rule);
            } else {
                udpRules.add(rule);
            }
        }
        return new ZapretDesyncProfile(tcpRules, udpRules);
    }

    public boolean isEmpty() {
        return tcpRules.isEmpty() && udpRules.isEmpty();
    }

    public TcpPlan buildTcpPlan(int port, byte[] payload, int outboundPayloadIndex) {
        if (payload == null || payload.length < 2) {
            return null;
        }
        for (Rule rule : tcpRules) {
            if (!rule.matchesPort(port) || !rule.shouldApply(outboundPayloadIndex, DEFAULT_TCP_CUTOFF)) {
                continue;
            }
            ArrayList<Integer> splitPositions = rule.resolveSplitPositions(payload);
            if (splitPositions.isEmpty() && rule.hasSafeTcpDesync()) {
                int fallback = findDefaultSplitPosition(payload);
                if (fallback > 0 && fallback < payload.length) {
                    splitPositions.add(fallback);
                }
            }
            if (rule.multiSplit && payload.length > 3) {
                splitPositions = expandMultiSplitPositions(splitPositions, payload.length, rule.repeats);
            }
            if (splitPositions.isEmpty()) {
                continue;
            }
            return new TcpPlan(splitPayload(payload, splitPositions), rule.describeSafeTcpMode());
        }
        return null;
    }

    public UdpPlan buildUdpPlan(int port, byte[] payload, int outboundPacketIndex) {
        if (payload == null || payload.length == 0) {
            return null;
        }
        for (Rule rule : udpRules) {
            if (!rule.matchesPort(port) || !rule.shouldApply(outboundPacketIndex, DEFAULT_UDP_CUTOFF) || !rule.fakeUdp) {
                continue;
            }
            ArrayList<byte[]> datagrams = new ArrayList<>();
            int count = Math.max(1, Math.min(MAX_UDP_FAKES, rule.repeats));
            for (int i = 0; i < count; i++) {
                datagrams.add(buildFakeUdpPayload(payload, port, i));
            }
            datagrams.add(Arrays.copyOf(payload, payload.length));
            return new UdpPlan(datagrams, rule.describeSafeUdpMode());
        }
        return null;
    }

    private static ArrayList<byte[]> splitPayload(byte[] payload, ArrayList<Integer> splitPositions) {
        ArrayList<byte[]> chunks = new ArrayList<>();
        int start = 0;
        for (int position : splitPositions) {
            if (position <= start || position >= payload.length) {
                continue;
            }
            chunks.add(Arrays.copyOfRange(payload, start, position));
            start = position;
        }
        if (start < payload.length) {
            chunks.add(Arrays.copyOfRange(payload, start, payload.length));
        }
        return chunks;
    }

    private static ArrayList<Integer> expandMultiSplitPositions(ArrayList<Integer> positions, int payloadLength, int repeats) {
        HashSet<Integer> unique = new HashSet<>(positions);
        int targetSplits = Math.max(2, Math.min(MAX_TCP_SPLITS, Math.max(2, repeats)));
        if (unique.isEmpty()) {
            unique.add(Math.max(1, Math.min(payloadLength - 1, payloadLength / 2)));
        }
        int seed = Collections.min(unique);
        int step = Math.max(1, Math.min(4, seed));
        while (unique.size() < targetSplits) {
            int candidate = seed + unique.size() * step;
            if (candidate >= payloadLength) {
                candidate = Math.max(1, payloadLength - (unique.size() + 1));
            }
            if (candidate > 0 && candidate < payloadLength) {
                unique.add(candidate);
            } else {
                break;
            }
        }
        ArrayList<Integer> sorted = new ArrayList<>(unique);
        Collections.sort(sorted);
        return sorted;
    }

    private static byte[] buildFakeUdpPayload(byte[] payload, int port, int index) {
        if (port == 443 || looksLikeQuic(payload)) {
            return buildFakeQuicPacket(index);
        }
        if (looksLikeStun(payload)) {
            return buildFakeStunPacket(index);
        }
        byte[] fake = new byte[Math.min(Math.max(24, payload.length), 96)];
        for (int i = 0; i < fake.length; i++) {
            fake[i] = (byte) (Utilities.fastRandom.nextInt(256) & 0xFF);
        }
        fake[0] = (byte) 0xEE;
        fake[1] = (byte) (index & 0xFF);
        return fake;
    }

    private static byte[] buildFakeQuicPacket(int index) {
        byte[] packet = new byte[64];
        packet[0] = (byte) 0xC3;
        packet[1] = 0x00;
        packet[2] = 0x00;
        packet[3] = 0x01;
        packet[4] = 0x08;
        packet[5] = (byte) index;
        for (int i = 6; i < packet.length; i++) {
            packet[i] = (byte) (Utilities.fastRandom.nextInt(256) & 0xFF);
        }
        return packet;
    }

    private static byte[] buildFakeStunPacket(int index) {
        byte[] packet = new byte[20];
        packet[0] = 0x00;
        packet[1] = 0x01;
        packet[2] = 0x00;
        packet[3] = 0x00;
        packet[4] = 0x21;
        packet[5] = 0x12;
        packet[6] = (byte) 0xA4;
        packet[7] = 0x42;
        for (int i = 8; i < packet.length; i++) {
            packet[i] = (byte) (Utilities.fastRandom.nextInt(256) & 0xFF);
        }
        packet[8] = (byte) index;
        return packet;
    }

    private static boolean looksLikeQuic(byte[] payload) {
        return payload.length > 8 && (payload[0] & 0xC0) == 0xC0;
    }

    private static boolean looksLikeStun(byte[] payload) {
        return payload.length >= 20
            && payload[0] == 0x00
            && payload[1] == 0x01
            && payload[4] == 0x21
            && payload[5] == 0x12
            && payload[6] == (byte) 0xA4
            && payload[7] == 0x42;
    }

    private static int findDefaultSplitPosition(byte[] payload) {
        int http = resolveHttpMethodPlus(payload, 2);
        if (http > 0) {
            return http;
        }
        int sniext = resolveTlsSniExtensionPlus(payload, 1);
        if (sniext > 0) {
            return sniext;
        }
        return Math.min(payload.length - 1, 2);
    }

    private static int resolveSplitToken(byte[] payload, String token) {
        if (TextUtils.isEmpty(token)) {
            return -1;
        }
        token = token.trim().toLowerCase(Locale.US);
        if (TextUtils.isDigitsOnly(token)) {
            int value = parsePositiveInt(token, -1);
            return normalizeSplitPosition(value, payload.length);
        }
        if (token.startsWith("method+")) {
            return resolveHttpMethodPlus(payload, parsePositiveInt(token.substring(7), 2));
        }
        if (token.startsWith("sniext+")) {
            return resolveTlsSniExtensionPlus(payload, parsePositiveInt(token.substring(7), 1));
        }
        if ("midsld".equals(token)) {
            return resolveMidSldPosition(payload);
        }
        if ("1".equals(token)) {
            return normalizeSplitPosition(1, payload.length);
        }
        return -1;
    }

    private static int resolveHttpMethodPlus(byte[] payload, int offset) {
        String head = new String(payload, 0, Math.min(payload.length, 16), StandardCharsets.US_ASCII);
        if (head.startsWith("GET ") || head.startsWith("POST ") || head.startsWith("HEAD ") || head.startsWith("PUT ") || head.startsWith("OPTIONS ") || head.startsWith("PATCH ") || head.startsWith("DELETE ")) {
            return normalizeSplitPosition(offset, payload.length);
        }
        return -1;
    }

    private static int resolveTlsSniExtensionPlus(byte[] payload, int offset) {
        int extOffset = findTlsSniExtensionOffset(payload);
        if (extOffset < 0) {
            return -1;
        }
        return normalizeSplitPosition(extOffset + offset, payload.length);
    }

    private static int resolveMidSldPosition(byte[] payload) {
        String host = extractHostName(payload);
        if (TextUtils.isEmpty(host)) {
            return -1;
        }
        String[] parts = host.split("\\.");
        if (parts.length < 2) {
            return -1;
        }
        String sld = parts[parts.length - 2];
        int half = sld.length() / 2;
        if (half <= 0) {
            return -1;
        }
        int hostOffset = indexOfAscii(payload, host);
        if (hostOffset >= 0) {
            int sldOffsetInHost = host.indexOf(sld);
            if (sldOffsetInHost >= 0) {
                return normalizeSplitPosition(hostOffset + sldOffsetInHost + half, payload.length);
            }
        }
        return -1;
    }

    private static int findTlsSniExtensionOffset(byte[] payload) {
        if (payload.length < 48 || payload[0] != 0x16) {
            return -1;
        }
        int recordLength = readUnsignedShort(payload, 3);
        if (recordLength + 5 > payload.length) {
            return -1;
        }
        int handshakeOffset = 5;
        if (payload[handshakeOffset] != 0x01) {
            return -1;
        }
        int sessionIdOffset = handshakeOffset + 38;
        if (sessionIdOffset >= payload.length) {
            return -1;
        }
        int sessionIdLength = payload[sessionIdOffset] & 0xFF;
        int cipherSuitesOffset = sessionIdOffset + 1 + sessionIdLength;
        if (cipherSuitesOffset + 2 > payload.length) {
            return -1;
        }
        int cipherSuitesLength = readUnsignedShort(payload, cipherSuitesOffset);
        int compressionOffset = cipherSuitesOffset + 2 + cipherSuitesLength;
        if (compressionOffset >= payload.length) {
            return -1;
        }
        int compressionLength = payload[compressionOffset] & 0xFF;
        int extensionsOffset = compressionOffset + 1 + compressionLength;
        if (extensionsOffset + 2 > payload.length) {
            return -1;
        }
        int extensionsLength = readUnsignedShort(payload, extensionsOffset);
        int cursor = extensionsOffset + 2;
        int end = Math.min(payload.length, cursor + extensionsLength);
        while (cursor + 4 <= end) {
            int type = readUnsignedShort(payload, cursor);
            int length = readUnsignedShort(payload, cursor + 2);
            if (type == 0x0000) {
                return cursor;
            }
            cursor += 4 + length;
        }
        return -1;
    }

    private static String extractHostName(byte[] payload) {
        int hostHeader = indexOfAscii(payload, "Host:");
        if (hostHeader >= 0) {
            int start = hostHeader + 5;
            while (start < payload.length && (payload[start] == ' ' || payload[start] == '\t')) {
                start++;
            }
            int end = start;
            while (end < payload.length && payload[end] != '\r' && payload[end] != '\n' && payload[end] != ' ') {
                end++;
            }
            if (end > start) {
                return new String(payload, start, end - start, StandardCharsets.US_ASCII).trim();
            }
        }
        int extOffset = findTlsSniExtensionOffset(payload);
        if (extOffset < 0 || extOffset + 9 >= payload.length) {
            return null;
        }
        int listLength = readUnsignedShort(payload, extOffset + 4);
        int cursor = extOffset + 6;
        int end = Math.min(payload.length, cursor + listLength);
        while (cursor + 3 <= end) {
            int nameType = payload[cursor] & 0xFF;
            int nameLength = readUnsignedShort(payload, cursor + 1);
            cursor += 3;
            if (nameType == 0 && cursor + nameLength <= payload.length) {
                return new String(payload, cursor, nameLength, StandardCharsets.US_ASCII);
            }
            cursor += nameLength;
        }
        return null;
    }

    private static int indexOfAscii(byte[] payload, String value) {
        byte[] pattern = value.getBytes(StandardCharsets.US_ASCII);
        outer:
        for (int i = 0; i <= payload.length - pattern.length; i++) {
            for (int j = 0; j < pattern.length; j++) {
                if (payload[i + j] != pattern[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    private static int normalizeSplitPosition(int position, int length) {
        if (position <= 0 || position >= length) {
            return -1;
        }
        return position;
    }

    private static int parsePositiveInt(String value, int fallback) {
        try {
            int parsed = Integer.parseInt(value.trim());
            return parsed >= 0 ? parsed : fallback;
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private static int readUnsignedShort(byte[] data, int offset) {
        if (offset + 1 >= data.length) {
            return 0;
        }
        return ((data[offset] & 0xFF) << 8) | (data[offset + 1] & 0xFF);
    }

    public static final class TcpPlan {

        public final ArrayList<byte[]> chunks;
        public final String label;

        private TcpPlan(ArrayList<byte[]> chunks, String label) {
            this.chunks = chunks;
            this.label = label;
        }
    }

    public static final class UdpPlan {

        public final ArrayList<byte[]> datagrams;
        public final String label;

        private UdpPlan(ArrayList<byte[]> datagrams, String label) {
            this.datagrams = datagrams;
            this.label = label;
        }
    }

    private static final class Rule {

        private Boolean tcp;
        private final ArrayList<PortRange> portRanges = new ArrayList<>();
        private final ArrayList<String> splitTokens = new ArrayList<>();
        private final HashSet<String> modes = new HashSet<>();
        private boolean fakeUdp;
        private boolean safeTcpFallbackSplit;
        private boolean multiSplit;
        private int repeats = 1;
        private int cutoffPackets = -1;

        private static Rule parse(String line) {
            if (TextUtils.isEmpty(line)) {
                return null;
            }
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                return null;
            }
            Rule rule = new Rule();
            String[] tokens = line.split("\\s+");
            for (String token : tokens) {
                if (token.startsWith("--filter-tcp=")) {
                    rule.tcp = true;
                    rule.parsePorts(token.substring(13));
                } else if (token.startsWith("--filter-udp=")) {
                    rule.tcp = false;
                    rule.parsePorts(token.substring(13));
                } else if (token.startsWith("--dpi-desync=")) {
                    rule.parseModes(token.substring(14));
                } else if (token.startsWith("--dpi-desync-repeats=")) {
                    rule.repeats = Math.max(1, parsePositiveInt(token.substring(22), 1));
                } else if (token.startsWith("--dpi-desync-split-pos=")) {
                    rule.parseSplitTokens(token.substring(24));
                } else if (token.startsWith("--dpi-desync-cutoff=")) {
                    rule.cutoffPackets = parseCutoff(token.substring(21));
                }
            }
            if (rule.tcp == null || rule.portRanges.isEmpty() || rule.modes.isEmpty()) {
                return null;
            }
            rule.fakeUdp = !rule.tcp && rule.modes.contains("fake");
            rule.multiSplit = rule.tcp && (rule.modes.contains("multisplit") || rule.modes.contains("multidisorder"));
            rule.safeTcpFallbackSplit = rule.tcp && (rule.modes.contains("split2")
                || rule.modes.contains("multisplit")
                || rule.modes.contains("multidisorder")
                || rule.modes.contains("fakedsplit")
                || rule.modes.contains("hostfakesplit")
                || rule.modes.contains("fake"));
            return rule.fakeUdp || rule.safeTcpFallbackSplit ? rule : null;
        }

        private void parsePorts(String value) {
            String[] items = value.split(",");
            for (String item : items) {
                PortRange range = PortRange.parse(item);
                if (range != null) {
                    portRanges.add(range);
                }
            }
        }

        private void parseModes(String value) {
            String[] items = value.toLowerCase(Locale.US).split(",");
            for (String item : items) {
                item = item.trim();
                if (!item.isEmpty()) {
                    modes.add(item);
                }
            }
        }

        private void parseSplitTokens(String value) {
            String[] items = value.split(",");
            for (String item : items) {
                item = item.trim();
                if (!item.isEmpty()) {
                    splitTokens.add(item);
                }
            }
        }

        private boolean matchesPort(int port) {
            for (PortRange range : portRanges) {
                if (range.matches(port)) {
                    return true;
                }
            }
            return false;
        }

        private boolean shouldApply(int outboundIndex, int defaultCutoff) {
            int cutoff = cutoffPackets > 0 ? cutoffPackets : defaultCutoff;
            return outboundIndex < cutoff;
        }

        private boolean hasSafeTcpDesync() {
            return safeTcpFallbackSplit;
        }

        private ArrayList<Integer> resolveSplitPositions(byte[] payload) {
            HashSet<Integer> unique = new HashSet<>();
            for (String splitToken : splitTokens) {
                int position = resolveSplitToken(payload, splitToken);
                if (position > 0 && position < payload.length) {
                    unique.add(position);
                }
            }
            ArrayList<Integer> sorted = new ArrayList<>(unique);
            Collections.sort(sorted);
            return sorted;
        }

        private String describeSafeTcpMode() {
            if (modes.contains("multidisorder")) {
                return "multidisorder->fragment";
            }
            if (modes.contains("multisplit")) {
                return "multisplit";
            }
            if (modes.contains("split2")) {
                return "split2";
            }
            if (modes.contains("fakedsplit") || modes.contains("hostfakesplit")) {
                return "fakesplit->fragment";
            }
            return "fake->fragment";
        }

        private String describeSafeUdpMode() {
            return "fake x" + Math.max(1, Math.min(MAX_UDP_FAKES, repeats));
        }
    }

    private static final class PortRange {

        private final int start;
        private final int end;

        private PortRange(int start, int end) {
            this.start = start;
            this.end = end;
        }

        private static PortRange parse(String value) {
            if (TextUtils.isEmpty(value)) {
                return null;
            }
            value = value.trim();
            if (value.contains("-")) {
                String[] parts = value.split("-", 2);
                int start = parsePositiveInt(parts[0], -1);
                int end = parsePositiveInt(parts[1], -1);
                if (start > 0 && end >= start) {
                    return new PortRange(start, end);
                }
                return null;
            }
            int port = parsePositiveInt(value, -1);
            return port > 0 ? new PortRange(port, port) : null;
        }

        private boolean matches(int port) {
            return port >= start && port <= end;
        }
    }

    private static int parseCutoff(String value) {
        if (TextUtils.isEmpty(value)) {
            return -1;
        }
        String digits = value.replaceAll("[^0-9]", "");
        return parsePositiveInt(digits, -1);
    }
}
