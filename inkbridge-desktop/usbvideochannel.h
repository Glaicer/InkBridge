#ifndef USBVIDEOCHANNEL_H
#define USBVIDEOCHANNEL_H

#include <QObject>
#include <QByteArray>
#include <QString>
#include <condition_variable>
#include <deque>
#include <mutex>
#include "protocol.h"

class UsbVideoChannel : public QObject
{
    Q_OBJECT
public:
    static constexpr int HeaderSize = 32;
    static constexpr int MaxPayload = 4 * 1024 * 1024;
    static constexpr quint32 KeyFrame = 1;
    static constexpr quint32 Stopped = 2;

    explicit UsbVideoChannel(QObject *parent = nullptr);
    void setConnected(bool connected);
    bool connected() const;
    bool tabletReady() const;
    bool acceptControl(const PenPacket &packet);
    void sendFrame(const QByteArray &frame, int width, int height, qint64 pts, bool keyFrame);
    void stopVideo(const QString &message);
    QByteArray takePacket(int timeoutMs);
    void packetWritten();
    bool waitForDelivery(int timeoutMs);

signals:
    void connectionChanged();
    void tabletReadyChanged();
    void keyFrameRequested();

private:
    QByteArray packet(const QByteArray &payload, int width, int height, qint64 pts, quint32 flags);
    mutable std::mutex m_mutex;
    std::condition_variable m_changed;
    std::deque<QByteArray> m_packets;
    bool m_connected = false;
    bool m_ready = false;
    bool m_waitingForKeyFrame = true;
    quint32 m_sequence = 0;
    bool m_writing = false;
};

#endif
