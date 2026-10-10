#include "screenmirror.h"
#include "usbvideochannel.h"
#include "videopipeline.h"
#include <QDBusArgument>
#include <QDBusConnection>
#include <QDBusObjectPath>
#include <QDBusPendingCallWatcher>
#include <QDBusPendingReply>
#include <QUuid>
#include <gst/video/video-event.h>

namespace {
const QString Portal = "org.freedesktop.portal.Desktop";
const QString PortalPath = "/org/freedesktop/portal/desktop";
const QString ScreenCast = "org.freedesktop.portal.ScreenCast";
const QString RequestInterface = "org.freedesktop.portal.Request";
const QString SessionInterface = "org.freedesktop.portal.Session";

QString token() {
    return "inkbridge_" + QUuid::createUuid().toString(QUuid::Id128);
}

QString senderPath() {
    QString sender = QDBusConnection::sessionBus().baseService();
    return sender.mid(1).replace('.', '_');
}

QPoint pair(const QVariant &value) {
    const auto argument = value.value<QDBusArgument>();
    int x = 0, y = 0;
    argument.beginStructure();
    argument >> x >> y;
    argument.endStructure();
    return QPoint(x, y);
}

void closePortal(const QString &path, const QString &interface) {
    if (path.isEmpty()) return;
    QDBusConnection::sessionBus().asyncCall(QDBusMessage::createMethodCall(Portal, path, interface, "Close"));
}
}

ScreenMirror::ScreenMirror(UsbVideoChannel *channel, QObject *parent)
    : QObject(parent), m_channel(channel) {
    m_pipelineTimer.setInterval(100);
    connect(&m_pipelineTimer, &QTimer::timeout, this, &ScreenMirror::checkPipeline);
    connect(channel, &UsbVideoChannel::keyFrameRequested, this, &ScreenMirror::requestKeyFrame, Qt::QueuedConnection);
    connect(channel, &UsbVideoChannel::tabletReadyChanged, this, [this] {
        if (!m_active && m_channel->tabletReady()) {
            emit statusChanged("USB video ready. Click Mirror via USB to share a monitor.");
        }
    }, Qt::QueuedConnection);
}

ScreenMirror::~ScreenMirror() {
    stop();
}

bool ScreenMirror::active() const {
    return m_active;
}

void ScreenMirror::start(QRect screen, int preset) {
    if (m_active || !m_channel->tabletReady()) return;
    if (!QDBusConnection::sessionBus().isConnected()) {
        fail("Screen sharing requires a desktop D-Bus session.");
        return;
    }
    GError *error = nullptr;
    if (!gst_init_check(nullptr, nullptr, &error)) {
        const QString message = error ? QString::fromUtf8(error->message) : "GStreamer initialization failed.";
        if (error) g_error_free(error);
        fail(message);
        return;
    }
    for (const char *plugin : {"pipewiresrc", "openh264enc", "h264parse", "appsink"}) {
        GstElementFactory *factory = gst_element_factory_find(plugin);
        if (!factory) {
            fail(QString("Missing GStreamer plugin: %1. See README.md.").arg(plugin));
            return;
        }
        gst_object_unref(factory);
    }
    m_screen = screen;
    m_preset = preset;
    m_active = true;
    m_sessionPath = PortalPath + "/session/" + senderPath() + "/" + token();
    QDBusConnection::sessionBus().connect(Portal, m_sessionPath, SessionInterface, "Closed", this, SLOT(sessionClosed()));
    emit activeChanged();
    emit statusChanged("Choose the monitor to mirror in the system screen-sharing dialog.");
    request(Request::Create);
}

void ScreenMirror::request(Request kind) {
    m_request = kind;
    const QString handleToken = token();
    m_requestPath = PortalPath + "/request/" + senderPath() + "/" + handleToken;
    if (!QDBusConnection::sessionBus().connect(Portal, m_requestPath, RequestInterface, "Response", this,
            SLOT(portalResponse(uint,QVariantMap,QDBusMessage)))) {
        fail("Could not listen for the screen-sharing permission response.");
        return;
    }
    QVariantMap options{{"handle_token", handleToken}};
    QString method;
    QVariantList arguments;
    if (kind == Request::Create) {
        method = "CreateSession";
        options["session_handle_token"] = m_sessionPath.section('/', -1);
        arguments = {options};
    } else if (kind == Request::Select) {
        method = "SelectSources";
        options["types"] = uint(1);
        options["multiple"] = false;
        options["cursor_mode"] = uint(2);
        arguments = {QVariant::fromValue(QDBusObjectPath(m_sessionPath)), options};
    } else {
        method = "Start";
        arguments = {QVariant::fromValue(QDBusObjectPath(m_sessionPath)), QString(), options};
    }
    QDBusMessage message = QDBusMessage::createMethodCall(Portal, PortalPath, ScreenCast, method);
    message.setArguments(arguments);
    const QString path = m_requestPath;
    auto *watcher = new QDBusPendingCallWatcher(QDBusConnection::sessionBus().asyncCall(message), this);
    connect(watcher, &QDBusPendingCallWatcher::finished, this, [this, path](QDBusPendingCallWatcher *call) {
        QDBusPendingReply<QDBusObjectPath> reply = *call;
        if (m_requestPath == path && reply.isError()) fail(reply.error().message());
        call->deleteLater();
    });
}

void ScreenMirror::portalResponse(uint response, const QVariantMap &results, const QDBusMessage &message) {
    if (!m_active || message.path() != m_requestPath) return;
    QDBusConnection::sessionBus().disconnect(Portal, m_requestPath, RequestInterface, "Response", this,
        SLOT(portalResponse(uint,QVariantMap,QDBusMessage)));
    m_requestPath.clear();
    if (response != 0) {
        fail(response == 1 ? "Screen sharing cancelled. Pen input remains available." : "Screen-sharing permission failed.");
        return;
    }
    if (m_request == Request::Create) {
        if (results.value("session_handle").toString() != m_sessionPath) {
            fail("The portal returned an unexpected screen-sharing session.");
            return;
        }
        request(Request::Select);
    } else if (m_request == Request::Select) {
        request(Request::Start);
    } else {
        const auto streams = results.value("streams").value<QDBusArgument>();
        streams.beginArray();
        if (streams.atEnd()) {
            streams.endArray();
            fail("No monitor was shared.");
            return;
        }
        uint node = 0;
        QVariantMap properties;
        streams.beginStructure();
        streams >> node >> properties;
        streams.endStructure();
        const bool extraStreams = !streams.atEnd();
        streams.endArray();
        if (extraStreams || node == 0) {
            fail("Select exactly one existing monitor to mirror.");
            return;
        }
        openStream(node, properties);
    }
}

void ScreenMirror::openStream(uint node, const QVariantMap &properties) {
    QSize size = m_screen.size();
    if (properties.contains("size")) {
        const QPoint dimensions = pair(properties.value("size"));
        size = QSize(dimensions.x(), dimensions.y());
    }
    if (properties.contains("position") && properties.contains("size")) {
        const QRect geometry(pair(properties.value("position")), size);
        m_screen = geometry;
        emit screenSelected(geometry);
        if (!m_active) return;
    }
    if (mirrorVideoSize(size, m_preset).isEmpty()) {
        fail("The shared monitor has an invalid size.");
        return;
    }
    QDBusMessage message = QDBusMessage::createMethodCall(Portal, PortalPath, ScreenCast, "OpenPipeWireRemote");
    message.setArguments({QVariant::fromValue(QDBusObjectPath(m_sessionPath)), QVariantMap()});
    const QString session = m_sessionPath;
    auto *watcher = new QDBusPendingCallWatcher(QDBusConnection::sessionBus().asyncCall(message), this);
    connect(watcher, &QDBusPendingCallWatcher::finished, this, [this, session, node, size](QDBusPendingCallWatcher *call) {
        QDBusPendingReply<QDBusUnixFileDescriptor> reply = *call;
        if (m_active && m_sessionPath == session) {
            if (reply.isError()) fail(reply.error().message());
            else {
                m_pipeWireFd = reply.value();
                startPipeline(node, size);
            }
        }
        call->deleteLater();
    });
}

void ScreenMirror::startPipeline(uint node, QSize size) {
    const int fps = m_preset == 2 ? 60 : 30;
    const QString source = QString("pipewiresrc fd=%1 path=%2 do-timestamp=true keepalive-time=%3")
        .arg(m_pipeWireFd.fileDescriptor()).arg(node).arg(1000 / fps);
    const QSize videoSize = mirrorVideoSize(size, m_preset);
    GError *error = nullptr;
    m_pipeline = gst_parse_launch(mirrorPipelineDescription(source, videoSize, m_preset).toUtf8().constData(), &error);
    if (error || !m_pipeline) {
        const QString message = error ? QString::fromUtf8(error->message) : "Could not create the video encoder.";
        if (error) g_error_free(error);
        fail(message);
        return;
    }
    GstElement *sink = gst_bin_get_by_name(GST_BIN(m_pipeline), "frames");
    GstAppSinkCallbacks callbacks{};
    callbacks.new_sample = &ScreenMirror::newSample;
    gst_app_sink_set_callbacks(GST_APP_SINK(sink), &callbacks, this, nullptr);
    gst_object_unref(sink);
    if (gst_element_set_state(m_pipeline, GST_STATE_PLAYING) == GST_STATE_CHANGE_FAILURE) {
        fail("Could not start monitor capture and H.264 encoding.");
        return;
    }
    m_pipelineTimer.start();
    emit statusChanged(QString("Mirroring via USB: %1 x %2, up to %3 fps. Input and video use separate USB directions.")
        .arg(videoSize.width()).arg(videoSize.height()).arg(fps));
}

GstFlowReturn ScreenMirror::newSample(GstAppSink *sink, gpointer data) {
    auto *mirror = static_cast<ScreenMirror *>(data);
    GstSample *sample = gst_app_sink_pull_sample(sink);
    if (!sample) return GST_FLOW_FLUSHING;
    GstBuffer *buffer = gst_sample_get_buffer(sample);
    GstCaps *caps = gst_sample_get_caps(sample);
    int width = 0, height = 0;
    if (caps) {
        const GstStructure *structure = gst_caps_get_structure(caps, 0);
        gst_structure_get_int(structure, "width", &width);
        gst_structure_get_int(structure, "height", &height);
    }
    GstMapInfo map{};
    if (gst_buffer_map(buffer, &map, GST_MAP_READ)) {
        const qint64 pts = GST_BUFFER_PTS_IS_VALID(buffer) ? qint64(GST_BUFFER_PTS(buffer) / GST_USECOND) : 0;
        mirror->m_channel->sendFrame(QByteArray(reinterpret_cast<const char *>(map.data), int(map.size)),
            width, height, pts, !GST_BUFFER_FLAG_IS_SET(buffer, GST_BUFFER_FLAG_DELTA_UNIT));
        gst_buffer_unmap(buffer, &map);
    }
    gst_sample_unref(sample);
    return GST_FLOW_OK;
}

void ScreenMirror::requestKeyFrame() {
    if (!m_pipeline) return;
    GstElement *sink = gst_bin_get_by_name(GST_BIN(m_pipeline), "frames");
    gst_element_send_event(sink, gst_video_event_new_upstream_force_key_unit(GST_CLOCK_TIME_NONE, TRUE, 0));
    gst_object_unref(sink);
}

void ScreenMirror::checkPipeline() {
    GstBus *bus = gst_element_get_bus(m_pipeline);
    GstMessage *message = gst_bus_pop_filtered(bus, GstMessageType(GST_MESSAGE_ERROR | GST_MESSAGE_EOS));
    gst_object_unref(bus);
    if (!message) return;
    QString text = "Screen sharing ended.";
    if (GST_MESSAGE_TYPE(message) == GST_MESSAGE_ERROR) {
        GError *error = nullptr;
        gchar *debug = nullptr;
        gst_message_parse_error(message, &error, &debug);
        text = QString::fromUtf8(error->message);
        g_error_free(error);
        g_free(debug);
    }
    gst_message_unref(message);
    fail(text);
}

void ScreenMirror::sessionClosed() {
    if (m_active) stop("Screen sharing was stopped by the desktop. Pen input remains available.");
}

void ScreenMirror::fail(const QString &message) {
    stop(message);
}

void ScreenMirror::stop(const QString &message) {
    m_pipelineTimer.stop();
    if (m_pipeline) {
        gst_element_set_state(m_pipeline, GST_STATE_NULL);
        gst_object_unref(m_pipeline);
        m_pipeline = nullptr;
    }
    m_pipeWireFd = QDBusUnixFileDescriptor();
    if (!m_requestPath.isEmpty()) {
        QDBusConnection::sessionBus().disconnect(Portal, m_requestPath, RequestInterface, "Response", this,
            SLOT(portalResponse(uint,QVariantMap,QDBusMessage)));
        closePortal(m_requestPath, RequestInterface);
        m_requestPath.clear();
    }
    if (!m_sessionPath.isEmpty()) {
        QDBusConnection::sessionBus().disconnect(Portal, m_sessionPath, SessionInterface, "Closed", this, SLOT(sessionClosed()));
        closePortal(m_sessionPath, SessionInterface);
        m_sessionPath.clear();
    }
    m_channel->stopVideo(message);
    const bool wasActive = m_active;
    m_active = false;
    emit statusChanged(message);
    if (wasActive) emit activeChanged();
}
