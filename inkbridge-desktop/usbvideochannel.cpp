#include "usbvideochannel.h"
#include <QtEndian>
#include <chrono>

UsbVideoChannel::UsbVideoChannel(QObject *parent) : QObject(parent) {}

void UsbVideoChannel::setConnected(bool connected) {
    {
        std::lock_guard<std::mutex> lock(m_mutex);
        m_connected = connected;
        m_ready = false;
        m_packets.clear();
        m_waitingForKeyFrame = true;
        m_sequence = 0;
        m_writing = false;
    }
    m_changed.notify_all();
    emit connectionChanged();
    emit tabletReadyChanged();
}

bool UsbVideoChannel::connected() const {
    std::lock_guard<std::mutex> lock(m_mutex);
    return m_connected;
}

bool UsbVideoChannel::tabletReady() const {
    std::lock_guard<std::mutex> lock(m_mutex);
    return m_connected && m_ready;
}

bool UsbVideoChannel::acceptControl(const PenPacket &packet) {
    if (packet.toolType != 127) return false;
    if (packet.action == 1 && packet.x == 1) {
        {
            std::lock_guard<std::mutex> lock(m_mutex);
            if (!m_connected) return true;
            m_ready = true;
            m_waitingForKeyFrame = true;
            m_packets.clear();
        }
        emit tabletReadyChanged();
        emit keyFrameRequested();
    } else if (packet.action == 2) {
        {
            std::lock_guard<std::mutex> lock(m_mutex);
            m_waitingForKeyFrame = true;
            m_packets.clear();
        }
        emit keyFrameRequested();
    } else if (packet.action == Undo && packet.x == 0 && packet.y == 0 && packet.pressure == 0 &&
               packet.tiltX == 0 && packet.tiltY == 0 && tabletReady()) {
        emit undoRequested();
    }
    return true;
}

QByteArray UsbVideoChannel::packet(const QByteArray &payload, int width, int height, qint64 pts, quint32 flags) {
    QByteArray result(HeaderSize, '\0');
    auto *header = reinterpret_cast<uchar *>(result.data());
    qToLittleEndian<quint32>(0x31564249, header);
    qToLittleEndian<quint32>(payload.size(), header + 4);
    qToLittleEndian<quint32>(width, header + 8);
    qToLittleEndian<quint32>(height, header + 12);
    qToLittleEndian<quint32>(flags, header + 16);
    qToLittleEndian<quint32>(m_sequence++, header + 20);
    qToLittleEndian<qint64>(pts, header + 24);
    result.append(payload);
    return result;
}

void UsbVideoChannel::sendFrame(const QByteArray &frame, int width, int height, qint64 pts, bool keyFrame) {
    if (frame.isEmpty() || frame.size() > MaxPayload || width < 2 || height < 2 || width > 4096 || height > 4096) return;
    bool requestKeyFrame = false;
    {
        std::lock_guard<std::mutex> lock(m_mutex);
        if (!m_connected || !m_ready) return;
        if (m_packets.size() >= 3) {
            m_packets.clear();
            m_waitingForKeyFrame = true;
            requestKeyFrame = !keyFrame;
        }
        if (keyFrame) m_waitingForKeyFrame = false;
        if (!m_waitingForKeyFrame) {
            m_packets.push_back(packet(frame, width, height, pts, keyFrame ? KeyFrame : 0));
        }
    }
    m_changed.notify_one();
    if (requestKeyFrame) emit keyFrameRequested();
}

void UsbVideoChannel::stopVideo(const QString &message) {
    {
        std::lock_guard<std::mutex> lock(m_mutex);
        m_packets.clear();
        m_waitingForKeyFrame = true;
        if (m_connected && m_ready) {
            m_packets.push_back(packet(message.toUtf8().left(4096), 0, 0, 0, Stopped));
        }
    }
    m_changed.notify_one();
}

QByteArray UsbVideoChannel::takePacket(int timeoutMs) {
    std::unique_lock<std::mutex> lock(m_mutex);
    m_changed.wait_for(lock, std::chrono::milliseconds(timeoutMs), [this] {
        return !m_connected || !m_packets.empty();
    });
    if (m_packets.empty()) return {};
    QByteArray result = std::move(m_packets.front());
    m_packets.pop_front();
    m_writing = true;
    return result;
}

void UsbVideoChannel::packetWritten() {
    {
        std::lock_guard<std::mutex> lock(m_mutex);
        m_writing = false;
    }
    m_changed.notify_all();
}

bool UsbVideoChannel::waitForDelivery(int timeoutMs) {
    std::unique_lock<std::mutex> lock(m_mutex);
    return m_changed.wait_for(lock, std::chrono::milliseconds(timeoutMs), [this] {
        return !m_connected || (m_packets.empty() && !m_writing);
    });
}
