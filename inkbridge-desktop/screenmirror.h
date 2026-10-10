#ifndef SCREENMIRROR_H
#define SCREENMIRROR_H

#include <QObject>
#include <QRect>
#include <QTimer>
#include <QVariantMap>
#include <QDBusMessage>
#include <QDBusUnixFileDescriptor>
#include <gst/app/gstappsink.h>

class UsbVideoChannel;

class ScreenMirror : public QObject
{
    Q_OBJECT
public:
    explicit ScreenMirror(UsbVideoChannel *channel, QObject *parent = nullptr);
    ~ScreenMirror();
    bool active() const;
    void start(QRect screen, int preset);
    void stop(const QString &message = "USB connected. Start screen sharing on the PC.");
    void requestKeyFrame();

signals:
    void activeChanged();
    void statusChanged(QString status);
    void screenSelected(QRect geometry);

private slots:
    void portalResponse(uint response, const QVariantMap &results, const QDBusMessage &message);
    void sessionClosed();
    void checkPipeline();

private:
    enum class Request { Create, Select, Start };
    void request(Request kind);
    void openStream(uint node, const QVariantMap &properties);
    void startPipeline(uint node, QSize size);
    void fail(const QString &message);
    static GstFlowReturn newSample(GstAppSink *sink, gpointer data);

    UsbVideoChannel *m_channel;
    bool m_active = false;
    int m_preset = 0;
    QRect m_screen;
    Request m_request = Request::Create;
    QString m_requestPath;
    QString m_sessionPath;
    QDBusUnixFileDescriptor m_pipeWireFd;
    GstElement *m_pipeline = nullptr;
    QTimer m_pipelineTimer;
};

#endif
