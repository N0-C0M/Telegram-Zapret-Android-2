#ifndef ZAPRET_NATIVE_CONFIG_H
#define ZAPRET_NATIVE_CONFIG_H

#include <stddef.h>
#include <stdint.h>

#include <string>
#include <vector>

namespace zapret {

struct TcpChunkPlan {
    bool enabled = false;
    std::vector<size_t> chunks;
};

void UpdateNativeConfig(bool enabled, bool applyToMessages, bool applyToCalls, const std::string &config);
TcpChunkPlan BuildTcpChunkPlan(bool forCalls, uint16_t port, const uint8_t *data, size_t length);
int GetTcpDesyncCutoffPackets(bool forCalls, uint16_t port);
int GetUdpFakeRepeats(bool forCalls, uint16_t port);
int GetUdpFakeCutoffPackets(bool forCalls, uint16_t port);
size_t BuildUdpFakePayload(const uint8_t *data, size_t length, int repeatIndex, uint8_t *output, size_t outputCapacity);
std::string GetDebugSummary();

} // namespace zapret

#endif
