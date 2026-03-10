#ifndef TGCALLS_ZAPRET_PACKET_SOCKET_WRAPPER_H
#define TGCALLS_ZAPRET_PACKET_SOCKET_WRAPPER_H

#include "ZapretNativeConfig.h"

#include "api/async_dns_resolver.h"
#include "api/packet_socket_factory.h"
#include "p2p/base/basic_packet_socket_factory.h"
#include "rtc_base/ip_address.h"
#include "rtc_base/socket_address.h"

#include <algorithm>
#include <memory>

namespace tgcalls {
namespace zapret_internal {

class WrappedAsyncPacketSocket : public rtc::AsyncPacketSocket {
public:
    explicit WrappedAsyncPacketSocket(std::unique_ptr<rtc::AsyncPacketSocket> &&wrappedSocket)
        : wrappedSocket_(std::move(wrappedSocket)) {
        wrappedSocket_->RegisterReceivedPacketCallback([this](AsyncPacketSocket *socket, rtc::ReceivedPacket const &packet) {
            onReadPacket(packet);
        });
        wrappedSocket_->SignalSentPacket.connect(this, &WrappedAsyncPacketSocket::onSentPacket);
        wrappedSocket_->SignalReadyToSend.connect(this, &WrappedAsyncPacketSocket::onReadyToSend);
        wrappedSocket_->SignalAddressReady.connect(this, &WrappedAsyncPacketSocket::onAddressReady);
        wrappedSocket_->SignalConnect.connect(this, &WrappedAsyncPacketSocket::onConnect);
        wrappedSocket_->SubscribeCloseEvent(this, [this](AsyncPacketSocket *socket, int error) {
            onClose(socket, error);
        });
    }

    ~WrappedAsyncPacketSocket() override {
        wrappedSocket_->DeregisterReceivedPacketCallback();
        wrappedSocket_->SignalSentPacket.disconnect(this);
        wrappedSocket_->SignalReadyToSend.disconnect(this);
        wrappedSocket_->SignalAddressReady.disconnect(this);
        wrappedSocket_->SignalConnect.disconnect(this);
        wrappedSocket_->UnsubscribeCloseEvent(this);
        wrappedSocket_.reset();
    }

    rtc::SocketAddress GetLocalAddress() const override {
        return wrappedSocket_->GetLocalAddress();
    }

    rtc::SocketAddress GetRemoteAddress() const override {
        return wrappedSocket_->GetRemoteAddress();
    }

    int Send(const void *pv, size_t cb, const rtc::PacketOptions &options) override {
        if (!zapretTcpDesyncSent_) {
            rtc::SocketAddress remoteAddress = wrappedSocket_->GetRemoteAddress();
            if (!remoteAddress.IsNil()) {
                const auto *bytes = reinterpret_cast<const uint8_t *>(pv);
                zapret::TcpChunkPlan plan = zapret::BuildTcpChunkPlan(true, remoteAddress.port(), bytes, cb);
                if (plan.enabled && !plan.chunks.empty()) {
                    size_t offset = 0;
                    int total = 0;
                    for (size_t chunkLength : plan.chunks) {
                        if (chunkLength == 0 || offset >= cb) {
                            continue;
                        }
                        chunkLength = std::min(chunkLength, cb - offset);
                        int sent = wrappedSocket_->Send(bytes + offset, chunkLength, options);
                        if (sent < 0) {
                            return sent;
                        }
                        total += sent;
                        offset += chunkLength;
                    }
                    zapretTcpDesyncSent_ = offset > 0;
                    if (zapretTcpDesyncSent_) {
                        return total;
                    }
                }
            }
        }
        return wrappedSocket_->Send(pv, cb, options);
    }

    int SendTo(const void *pv, size_t cb, const rtc::SocketAddress &addr, const rtc::PacketOptions &options) override {
        if (!zapretUdpFakeSent_) {
            const int repeats = zapret::GetUdpFakeRepeats(true, addr.port());
            if (repeats > 0) {
                uint8_t fakePacket[32];
                for (int i = 0; i < repeats; i++) {
                    size_t fakeLength = zapret::BuildUdpFakePayload(reinterpret_cast<const uint8_t *>(pv), cb, i, fakePacket, sizeof(fakePacket));
                    if (fakeLength > 0) {
                        wrappedSocket_->SendTo(fakePacket, fakeLength, addr, options);
                    }
                }
                zapretUdpFakeSent_ = true;
            }
        }
        return wrappedSocket_->SendTo(pv, cb, addr, options);
    }

    int Close() override {
        return wrappedSocket_->Close();
    }

    State GetState() const override {
        return wrappedSocket_->GetState();
    }

    int GetOption(rtc::Socket::Option opt, int *value) override {
        return wrappedSocket_->GetOption(opt, value);
    }

    int SetOption(rtc::Socket::Option opt, int value) override {
        return wrappedSocket_->SetOption(opt, value);
    }

    int GetError() const override {
        return wrappedSocket_->GetError();
    }

    void SetError(int error) override {
        wrappedSocket_->SetError(error);
    }

private:
    void onReadPacket(const rtc::ReceivedPacket &packet) {
        NotifyPacketReceived(packet);
    }

    void onSentPacket(AsyncPacketSocket *socket, const rtc::SentPacket &packet) {
        SignalSentPacket.emit(this, packet);
    }

    void onReadyToSend(AsyncPacketSocket *socket) {
        SignalReadyToSend.emit(this);
    }

    void onAddressReady(AsyncPacketSocket *socket, const rtc::SocketAddress &address) {
        SignalAddressReady.emit(this, address);
    }

    void onConnect(AsyncPacketSocket *socket) {
        SignalConnect.emit(this);
    }

    void onClose(AsyncPacketSocket *socket, int error) {
        SignalClose(this, error);
    }

    std::unique_ptr<rtc::AsyncPacketSocket> wrappedSocket_;
    bool zapretTcpDesyncSent_ = false;
    bool zapretUdpFakeSent_ = false;
};

class WrappedPacketSocketFactory : public rtc::PacketSocketFactory {
public:
    explicit WrappedPacketSocketFactory(std::unique_ptr<rtc::BasicPacketSocketFactory> &&impl, bool standaloneReflectorMode = false)
        : impl_(std::move(impl))
        , standaloneReflectorMode_(standaloneReflectorMode) {
    }

    ~WrappedPacketSocketFactory() override = default;

    rtc::AsyncPacketSocket *CreateUdpSocket(const rtc::SocketAddress &address, uint16_t min_port, uint16_t max_port) override {
        if (standaloneReflectorMode_ && isBlockedReflectorAddress(address)) {
            return nullptr;
        }
        rtc::SocketAddress updatedAddress = address;
        if (standaloneReflectorMode_ && updatedAddress.port() == 12345) {
            updatedAddress.SetPort(0);
        }
        rtc::AsyncPacketSocket *socket = impl_->CreateUdpSocket(updatedAddress, min_port, max_port);
        if (socket == nullptr) {
            return nullptr;
        }
        return new WrappedAsyncPacketSocket(std::unique_ptr<rtc::AsyncPacketSocket>(socket));
    }

    rtc::AsyncListenSocket *CreateServerTcpSocket(const rtc::SocketAddress &local_address, uint16_t min_port, uint16_t max_port, int opts) override {
        if (standaloneReflectorMode_ && isBlockedReflectorAddress(local_address)) {
            return nullptr;
        }
        return impl_->CreateServerTcpSocket(local_address, min_port, max_port, opts);
    }

    rtc::AsyncPacketSocket *CreateClientTcpSocket(const rtc::SocketAddress &local_address, const rtc::SocketAddress &remote_address, const rtc::ProxyInfo &proxy_info, const std::string &user_agent, const rtc::PacketSocketTcpOptions &tcp_options) override {
        if (standaloneReflectorMode_ && isBlockedReflectorAddress(local_address)) {
            return nullptr;
        }
        rtc::AsyncPacketSocket *socket = impl_->CreateClientTcpSocket(local_address, remote_address, proxy_info, user_agent, tcp_options);
        if (socket == nullptr) {
            return nullptr;
        }
        return new WrappedAsyncPacketSocket(std::unique_ptr<rtc::AsyncPacketSocket>(socket));
    }

    std::unique_ptr<webrtc::AsyncDnsResolverInterface> CreateAsyncDnsResolver() override {
        return impl_->CreateAsyncDnsResolver();
    }

private:
    bool isBlockedReflectorAddress(const rtc::SocketAddress &address) const {
        rtc::IPAddress ipAddress;
        rtc::IPFromString("0.1.2.3", &ipAddress);
        return address.ipaddr() == ipAddress && address.port() != 12345;
    }

    std::unique_ptr<rtc::BasicPacketSocketFactory> impl_;
    bool standaloneReflectorMode_ = false;
};

} // namespace zapret_internal
} // namespace tgcalls

#endif
