#include "ZapretNativeConfig.h"

#include <algorithm>
#include <array>
#include <cctype>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <mutex>
#include <sstream>
#include <string>
#include <utility>
#include <vector>

namespace zapret {

namespace {

struct PortRange {
    uint16_t from = 0;
    uint16_t to = 0;

    bool contains(uint16_t value) const {
        return value >= from && value <= to;
    }
};

struct Rule {
    std::vector<PortRange> ports;
    std::vector<std::string> modes;
    std::vector<std::string> splitPositions;
    int repeats = 1;
    int cutoffBytes = 0;
};

struct NativeConfig {
    bool enabled = false;
    bool applyToMessages = false;
    bool applyToCalls = false;
    std::vector<Rule> tcpRules;
    std::vector<Rule> udpRules;
};

std::mutex configMutex;
NativeConfig currentConfig;

std::string trim(std::string value) {
    size_t start = 0;
    while (start < value.size() && std::isspace(static_cast<unsigned char>(value[start]))) {
        start++;
    }
    size_t end = value.size();
    while (end > start && std::isspace(static_cast<unsigned char>(value[end - 1]))) {
        end--;
    }
    return value.substr(start, end - start);
}

std::string toLower(std::string value) {
    std::transform(value.begin(), value.end(), value.begin(), [](unsigned char ch) {
        return static_cast<char>(std::tolower(ch));
    });
    return value;
}

bool parsePositiveInt(const std::string &value, int *result) {
    if (value.empty()) {
        return false;
    }
    char *end = nullptr;
    long parsed = std::strtol(value.c_str(), &end, 10);
    if (end == value.c_str() || *end != '\0' || parsed <= 0) {
        return false;
    }
    *result = static_cast<int>(parsed);
    return true;
}

std::vector<std::string> splitCommaList(const std::string &value) {
    std::vector<std::string> result;
    std::stringstream stream(value);
    std::string item;
    while (std::getline(stream, item, ',')) {
        item = trim(item);
        if (!item.empty()) {
            result.push_back(item);
        }
    }
    return result;
}

std::vector<std::string> splitCommaListLower(const std::string &value) {
    std::vector<std::string> result = splitCommaList(value);
    for (std::string &item : result) {
        item = toLower(item);
    }
    return result;
}

std::vector<PortRange> parsePorts(const std::string &value) {
    std::vector<PortRange> result;
    for (std::string item : splitCommaList(value)) {
        size_t dash = item.find('-');
        if (dash != std::string::npos) {
            int from = 0;
            int to = 0;
            if (parsePositiveInt(trim(item.substr(0, dash)), &from) && parsePositiveInt(trim(item.substr(dash + 1)), &to)) {
                from = std::max(1, std::min(from, 65535));
                to = std::max(1, std::min(to, 65535));
                if (from > to) {
                    std::swap(from, to);
                }
                result.push_back(PortRange{static_cast<uint16_t>(from), static_cast<uint16_t>(to)});
            }
        } else {
            int port = 0;
            if (parsePositiveInt(item, &port)) {
                port = std::max(1, std::min(port, 65535));
                result.push_back(PortRange{static_cast<uint16_t>(port), static_cast<uint16_t>(port)});
            }
        }
    }
    return result;
}

bool parseRuleLine(const std::string &line, bool tcp, Rule *rule) {
    std::stringstream stream(line);
    std::string token;
    bool hasFilter = false;
    while (stream >> token) {
        if (tcp && token.find("--filter-tcp=") == 0) {
            rule->ports = parsePorts(token.substr(strlen("--filter-tcp=")));
            hasFilter = !rule->ports.empty();
        } else if (!tcp && token.find("--filter-udp=") == 0) {
            rule->ports = parsePorts(token.substr(strlen("--filter-udp=")));
            hasFilter = !rule->ports.empty();
        } else if (token.find("--dpi-desync=") == 0) {
            rule->modes = splitCommaList(toLower(token.substr(strlen("--dpi-desync="))));
        } else if (token.find("--dpi-desync-repeats=") == 0) {
            int repeats = 0;
            if (parsePositiveInt(trim(token.substr(strlen("--dpi-desync-repeats="))), &repeats)) {
                rule->repeats = std::max(1, repeats);
            }
        } else if (token.find("--dpi-desync-split-pos=") == 0) {
            rule->splitPositions = splitCommaListLower(token.substr(strlen("--dpi-desync-split-pos=")));
        } else if (token.find("--dpi-desync-cutoff=") == 0) {
            std::string cutoffValue = toLower(trim(token.substr(strlen("--dpi-desync-cutoff="))));
            int cutoffBytes = 0;
            if (parsePositiveInt(cutoffValue, &cutoffBytes)) {
                rule->cutoffBytes = cutoffBytes;
            } else {
                size_t digitStart = 0;
                while (digitStart < cutoffValue.size() && !std::isdigit(static_cast<unsigned char>(cutoffValue[digitStart]))) {
                    digitStart++;
                }
                if (digitStart < cutoffValue.size() && parsePositiveInt(cutoffValue.substr(digitStart), &cutoffBytes)) {
                    rule->cutoffBytes = std::max(24, cutoffBytes * 16);
                }
            }
        }
    }
    return hasFilter && !rule->modes.empty();
}

NativeConfig parseConfig(bool enabled, bool applyToMessages, bool applyToCalls, const std::string &config) {
    NativeConfig result;
    result.enabled = enabled;
    result.applyToMessages = enabled && applyToMessages;
    result.applyToCalls = enabled && applyToCalls;
    if (!result.enabled) {
        return result;
    }

    std::stringstream stream(config);
    std::string line;
    while (std::getline(stream, line)) {
        Rule tcpRule;
        if (parseRuleLine(line, true, &tcpRule)) {
            result.tcpRules.push_back(std::move(tcpRule));
        }

        Rule udpRule;
        if (parseRuleLine(line, false, &udpRule)) {
            result.udpRules.push_back(std::move(udpRule));
        }
    }
    return result;
}

NativeConfig snapshot() {
    std::lock_guard<std::mutex> lock(configMutex);
    return currentConfig;
}

bool matchesPort(const Rule &rule, uint16_t port) {
    for (const PortRange &range : rule.ports) {
        if (range.contains(port)) {
            return true;
        }
    }
    return false;
}

const Rule *findRule(const std::vector<Rule> &rules, uint16_t port) {
    for (const Rule &rule : rules) {
        if (matchesPort(rule, port)) {
            return &rule;
        }
    }
    return nullptr;
}

bool hasMode(const Rule &rule, const char *mode) {
    return std::find(rule.modes.begin(), rule.modes.end(), mode) != rule.modes.end();
}

bool looksLikeHttpMethod(const uint8_t *data, size_t length, size_t *endOffset) {
    static const std::array<const char *, 5> methods = {{"GET", "POST", "HEAD", "PUT", "OPTIONS"}};
    size_t limit = std::min(length, static_cast<size_t>(16));
    for (const char *method : methods) {
        size_t methodLength = strlen(method);
        if (limit > methodLength && std::memcmp(data, method, methodLength) == 0 && data[methodLength] == ' ') {
            *endOffset = methodLength;
            return true;
        }
    }
    return false;
}

size_t findTlsSniExtensionOffset(const uint8_t *data, size_t length) {
    if (length < 43 || data[0] != 0x16 || data[1] != 0x03) {
        return 0;
    }
    size_t offset = 5;
    if (offset + 4 > length || data[offset] != 0x01) {
        return 0;
    }
    offset += 4;
    offset += 2 + 32;
    if (offset >= length) {
        return 0;
    }
    size_t sessionIdLength = data[offset];
    offset += 1 + sessionIdLength;
    if (offset + 2 > length) {
        return 0;
    }
    size_t cipherSuitesLength = (static_cast<size_t>(data[offset]) << 8) | data[offset + 1];
    offset += 2 + cipherSuitesLength;
    if (offset >= length) {
        return 0;
    }
    size_t compressionMethodsLength = data[offset];
    offset += 1 + compressionMethodsLength;
    if (offset + 2 > length) {
        return 0;
    }
    size_t extensionsLength = (static_cast<size_t>(data[offset]) << 8) | data[offset + 1];
    offset += 2;
    size_t extensionsEnd = std::min(length, offset + extensionsLength);
    while (offset + 4 <= extensionsEnd) {
        uint16_t type = static_cast<uint16_t>((data[offset] << 8) | data[offset + 1]);
        uint16_t extLength = static_cast<uint16_t>((data[offset + 2] << 8) | data[offset + 3]);
        if (offset + 4 + extLength > extensionsEnd) {
            break;
        }
        if (type == 0x0000) {
            return offset + 4;
        }
        offset += 4 + extLength;
    }
    return 0;
}

size_t defaultSplitPosition(bool forCalls, size_t length) {
    if (length <= 2) {
        return 0;
    }
    size_t preferred = forCalls ? 24 : 8;
    preferred = std::min(preferred, length - 1);
    return std::max<size_t>(1, preferred);
}

size_t resolveSingleSplitPosition(const std::string &token, bool forCalls, const uint8_t *data, size_t length) {
    if (length <= 1) {
        return 0;
    }
    if (token.empty()) {
        return defaultSplitPosition(forCalls, length);
    }

    int numeric = 0;
    if (parsePositiveInt(token, &numeric)) {
        return std::min(static_cast<size_t>(numeric), length - 1);
    }

    if (token.find("method+") == 0) {
        size_t methodEnd = 0;
        if (looksLikeHttpMethod(data, length, &methodEnd)) {
            int delta = 0;
            if (parsePositiveInt(token.substr(strlen("method+")), &delta)) {
                return std::min(methodEnd + static_cast<size_t>(delta), length - 1);
            }
        }
    } else if (token.find("sniext+") == 0) {
        size_t sniOffset = findTlsSniExtensionOffset(data, length);
        if (sniOffset != 0) {
            int delta = 0;
            if (parsePositiveInt(token.substr(strlen("sniext+")), &delta)) {
                return std::min(sniOffset + static_cast<size_t>(delta), length - 1);
            }
        }
    } else if (token == "midsld") {
        return std::max<size_t>(1, std::min(length - 1, length / 2));
    }

    return defaultSplitPosition(forCalls, length);
}

std::vector<size_t> resolveSplitPositions(const Rule &rule, bool forCalls, const uint8_t *data, size_t length) {
    std::vector<size_t> positions;
    if (rule.splitPositions.empty()) {
        size_t fallback = defaultSplitPosition(forCalls, length);
        if (fallback > 0 && fallback < length) {
            positions.push_back(fallback);
        }
        return positions;
    }

    for (const std::string &token : rule.splitPositions) {
        size_t position = resolveSingleSplitPosition(token, forCalls, data, length);
        if (position > 0 && position < length) {
            positions.push_back(position);
        }
    }

    if (positions.empty()) {
        size_t fallback = defaultSplitPosition(forCalls, length);
        if (fallback > 0 && fallback < length) {
            positions.push_back(fallback);
        }
    }

    std::sort(positions.begin(), positions.end());
    positions.erase(std::unique(positions.begin(), positions.end()), positions.end());
    return positions;
}

TcpChunkPlan buildChunkPlan(const Rule &rule, bool forCalls, const uint8_t *data, size_t length) {
    TcpChunkPlan plan;
    if (length <= 1) {
        return plan;
    }

    const bool wantsSplit = hasMode(rule, "split2") || hasMode(rule, "multisplit") || hasMode(rule, "multidisorder") ||
        hasMode(rule, "fakedsplit") || hasMode(rule, "hostfakesplit") || hasMode(rule, "fake");
    if (!wantsSplit) {
        return plan;
    }

    std::vector<size_t> splitPositions = resolveSplitPositions(rule, forCalls, data, length);
    if (splitPositions.empty()) {
        return plan;
    }

    const bool wantsMulti = hasMode(rule, "multisplit") || hasMode(rule, "multidisorder") ||
        hasMode(rule, "fakedsplit") || hasMode(rule, "hostfakesplit");
    if (!wantsMulti) {
        size_t split = splitPositions.front();
        plan.chunks.push_back(split);
        plan.chunks.push_back(length - split);
        plan.enabled = true;
        return plan;
    }

    size_t furthestSplit = splitPositions.back();
    size_t cutoff = rule.cutoffBytes > 0
        ? std::min(length, std::max<size_t>(furthestSplit + 1, rule.cutoffBytes))
        : std::min(length, std::max<size_t>(furthestSplit + 16, hasMode(rule, "multidisorder") ? 64 : 48));

    size_t previous = 0;
    for (size_t split : splitPositions) {
        if (split <= previous || split >= cutoff) {
            continue;
        }
        plan.chunks.push_back(split - previous);
        previous = split;
    }

    if (previous < cutoff) {
        plan.chunks.push_back(cutoff - previous);
    }
    if (cutoff < length && cutoff > 0) {
        plan.chunks.push_back(length - cutoff);
    }

    plan.chunks.erase(std::remove(plan.chunks.begin(), plan.chunks.end(), static_cast<size_t>(0)), plan.chunks.end());
    plan.enabled = plan.chunks.size() > 1;
    return plan;
}

int clampUdpRepeats(int repeats) {
    return std::max(1, std::min(repeats, 4));
}

} // namespace

void UpdateNativeConfig(bool enabled, bool applyToMessages, bool applyToCalls, const std::string &config) {
    NativeConfig parsed = parseConfig(enabled, applyToMessages, applyToCalls, config);
    std::lock_guard<std::mutex> lock(configMutex);
    currentConfig = std::move(parsed);
}

TcpChunkPlan BuildTcpChunkPlan(bool forCalls, uint16_t port, const uint8_t *data, size_t length) {
    NativeConfig config = snapshot();
    if (!config.enabled || (forCalls ? !config.applyToCalls : !config.applyToMessages)) {
        return TcpChunkPlan();
    }
    const Rule *rule = findRule(config.tcpRules, port);
    if (rule == nullptr) {
        return TcpChunkPlan();
    }
    return buildChunkPlan(*rule, forCalls, data, length);
}

int GetUdpFakeRepeats(bool forCalls, uint16_t port) {
    NativeConfig config = snapshot();
    if (!config.enabled || (forCalls ? !config.applyToCalls : !config.applyToMessages)) {
        return 0;
    }
    const Rule *rule = findRule(config.udpRules, port);
    if (rule == nullptr || !hasMode(*rule, "fake")) {
        return 0;
    }
    return clampUdpRepeats(rule->repeats);
}

size_t BuildUdpFakePayload(const uint8_t *data, size_t length, int repeatIndex, uint8_t *output, size_t outputCapacity) {
    if (output == nullptr || outputCapacity == 0) {
        return 0;
    }
    size_t fakeLength = length == 0 ? 16 : std::min(length, static_cast<size_t>(32));
    fakeLength = std::max<size_t>(12, std::min(fakeLength, outputCapacity));
    size_t copied = std::min(length, fakeLength);
    if (copied > 0) {
        std::memcpy(output, data, copied);
    }
    for (size_t i = copied; i < fakeLength; i++) {
        output[i] = static_cast<uint8_t>(0x20 + ((i + repeatIndex * 13) & 0x3f));
    }
    for (size_t i = 0; i < fakeLength; i++) {
        output[i] ^= static_cast<uint8_t>(0x5a + repeatIndex + (i * 17));
    }
    return fakeLength;
}

std::string GetDebugSummary() {
    NativeConfig config = snapshot();
    std::stringstream stream;
    stream << "enabled=" << (config.enabled ? 1 : 0)
           << " messages=" << (config.applyToMessages ? 1 : 0)
           << " calls=" << (config.applyToCalls ? 1 : 0)
           << " tcpRules=" << config.tcpRules.size()
           << " udpRules=" << config.udpRules.size();
    return stream.str();
}

} // namespace zapret
