/*
 *  Copyright 2004 The WebRTC Project Authors. All rights reserved.
 *
 *  Use of this source code is governed by a BSD-style license
 *  that can be found in the LICENSE file in the root of the source
 *  tree. An additional intellectual property rights grant can be found
 *  in the file PATENTS.  All contributing project authors may
 *  be found in the AUTHORS file in the root of the source tree.
 */

#include "RawTcpSocket.h"

#include <stdint.h>
#include <string.h>

#include <algorithm>
#include <cstddef>
#include <cstdint>
#include <memory>

#include "api/array_view.h"
#include "rtc_base/byte_order.h"
#include "rtc_base/checks.h"
#include "rtc_base/logging.h"
#include "rtc_base/network/sent_packet.h"
#include "rtc_base/time_utils.h"  // for TimeMillis
#include "ZapretNativeConfig.h"

#if defined(WEBRTC_POSIX)
#include <errno.h>
#endif  // WEBRTC_POSIX

namespace rtc {

static const size_t kMaxPacketSize = 64 * 1024;

static const size_t kBufSize = kMaxPacketSize + 4;

// RawTcpSocket
// Binds and connects `socket` and creates RawTcpSocket for
// it. Takes ownership of `socket`. Returns null if bind() or
// connect() fail (`socket` is destroyed in that case).
RawTcpSocket* RawTcpSocket::Create(Socket* socket,
                                       const SocketAddress& bind_address,
                                       const SocketAddress& remote_address) {
  return new RawTcpSocket(
      AsyncTCPSocketBase::ConnectSocket(socket, bind_address, remote_address));
}

RawTcpSocket::RawTcpSocket(Socket* socket)
    : AsyncTCPSocketBase(socket, kBufSize) {}

int RawTcpSocket::Send(const void* pv,
                         size_t cb,
                         const rtc::PacketOptions& options) {
  if (cb > kBufSize) {
    SetError(EMSGSIZE);
    return -1;
  }

  // If we are blocking on send, then silently drop this packet
  if (!IsOutBufferEmpty())
    return static_cast<int>(cb);

  std::vector<uint8_t> frame;
  frame.reserve(cb + 8);
  if (!did_send_mtproto_prologue_) {
    did_send_mtproto_prologue_ = true;
    uint32_t prologue = 0xeeeeeeee;
    const uint8_t* prologueBytes = reinterpret_cast<const uint8_t*>(&prologue);
    frame.insert(frame.end(), prologueBytes, prologueBytes + sizeof(prologue));
  }

  uint32_t pkt_len = (uint32_t)cb;
  const uint8_t* lengthBytes = reinterpret_cast<const uint8_t*>(&pkt_len);
  frame.insert(frame.end(), lengthBytes, lengthBytes + sizeof(pkt_len));
  const uint8_t* payloadBytes = reinterpret_cast<const uint8_t*>(pv);
  frame.insert(frame.end(), payloadBytes, payloadBytes + cb);

  int res;
  if (!did_send_zapret_desync_) {
    zapret::TcpChunkPlan plan = zapret::BuildTcpChunkPlan(true, GetRemoteAddress().port(), frame.data(), frame.size());
    if (plan.enabled) {
      did_send_zapret_desync_ = true;
      size_t offset = 0;
      res = static_cast<int>(frame.size());
      for (size_t chunkSize : plan.chunks) {
        if (offset >= frame.size()) {
          break;
        }
        chunkSize = std::min(chunkSize, frame.size() - offset);
        if (chunkSize == 0) {
          continue;
        }
        AppendToOutBuffer(frame.data() + offset, chunkSize);
        int flushRes = FlushOutBuffer();
        if (flushRes <= 0) {
          ClearOutBuffer();
          res = flushRes;
          break;
        }
        if (static_cast<size_t>(flushRes) < chunkSize) {
          offset += static_cast<size_t>(flushRes);
          if (offset < frame.size()) {
            AppendToOutBuffer(frame.data() + offset, frame.size() - offset);
          }
          break;
        }
        offset += chunkSize;
      }
      if (res > 0 && offset < frame.size() && IsOutBufferEmpty()) {
        AppendToOutBuffer(frame.data() + offset, frame.size() - offset);
        int flushRes = FlushOutBuffer();
        if (flushRes <= 0) {
          ClearOutBuffer();
          res = flushRes;
        }
      }
    } else {
      AppendToOutBuffer(frame.data(), frame.size());
      res = FlushOutBuffer();
    }
  } else {
    AppendToOutBuffer(frame.data(), frame.size());
    res = FlushOutBuffer();
  }
  if (res <= 0) {
    // drop packet if we made no progress
    ClearOutBuffer();
    return res;
  }

  rtc::SentPacket sent_packet(options.packet_id, rtc::TimeMillis(),
                              options.info_signaled_after_sent);
  CopySocketInformationToPacketInfo(cb, *this, false, &sent_packet.info);
  SignalSentPacket(this, sent_packet);

  // We claim to have sent the whole thing, even if we only sent partial
  return static_cast<int>(cb);
}

size_t RawTcpSocket::ProcessInput(rtc::ArrayView<const uint8_t> data) {
  SocketAddress remote_addr(GetRemoteAddress());

  size_t processed_bytes = 0;
  while (true) {
    size_t bytes_left = data.size() - processed_bytes;
    if (bytes_left < 4)
      return processed_bytes;

    uint32_t pkt_len = rtc::GetLE32(data.data() + processed_bytes);
    if (bytes_left < 4 + pkt_len)
      return processed_bytes;

    rtc::ReceivedPacket received_packet(
        data.subview(processed_bytes + 4, pkt_len), remote_addr,
        webrtc::Timestamp::Micros(rtc::TimeMicros()));
    NotifyPacketReceived(received_packet);
    processed_bytes += 4 + pkt_len;
  }
}

}  // namespace rtc
