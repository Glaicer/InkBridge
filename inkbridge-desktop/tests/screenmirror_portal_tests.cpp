#include "screenmirror.h"
#include "usbvideochannel.h"
#include <QCoreApplication>
#include <QDBusArgument>
#include <QDBusConnection>
#include <QDBusMetaType>
#include <QDBusObjectPath>
#include <QDBusVirtualObject>
#include <QElapsedTimer>
#include <QThread>
#include <iostream>
#include <cstdlib>

struct TestStream {
    uint node;
    QVariantMap properties;
};
Q_DECLARE_METATYPE(TestStream)
Q_DECLARE_METATYPE(QList<TestStream>)

QDBusArgument &operator<<(QDBusArgument &argument, const TestStream &stream) {
    argument.beginStructure();
    argument << stream.node << stream.properties;
    argument.endStructure();
    return argument;
}

const QDBusArgument &operator>>(const QDBusArgument &argument, TestStream &stream) {
    argument.beginStructure();
    argument >> stream.node >> stream.properties;
    argument.endStructure();
    return argument;
}

static void require(bool condition, const char *message) {
    if (!condition) {
        std::cerr << "FAIL: " << message << '\n';
        std::exit(1);
    }
}

static void spinUntil(const std::function<bool()> &done) {
    QElapsedTimer timer;
    timer.start();
    while (!done() && timer.elapsed() < 3000) {
        QCoreApplication::processEvents();
        QThread::msleep(1);
    }
    require(done(), "asynchronous portal operation completed");
}

static QVariant point(int x, int y) {
    QDBusArgument argument;
    argument.beginStructure();
    argument << x << y;
    argument.endStructure();
    return QVariant::fromValue(argument);
}

class Portal : public QDBusVirtualObject {
public:
    enum Mode { Deny, RemoteError, Pending } mode = Deny;
    int requestCloses = 0;
    int sessionCloses = 0;
    bool created = false;
    bool remoteOpened = false;

    QString introspect(const QString &) const override { return {}; }

    bool handleMessage(const QDBusMessage &message, const QDBusConnection &bus) override {
        if (message.member() == "Close") {
            if (message.interface() == "org.freedesktop.portal.Session") ++sessionCloses;
            else ++requestCloses;
            bus.send(message.createReply());
            return true;
        }
        if (message.member() == "OpenPipeWireRemote") {
            remoteOpened = true;
            bus.send(message.createErrorReply("org.freedesktop.portal.Error.Failed", "Test remote refused"));
            return true;
        }
        const QVariantMap options = qdbus_cast<QVariantMap>(message.arguments().last());
        const QString sender = QString(message.service()).mid(1).replace('.', '_');
        const QString root = "/org/freedesktop/portal/desktop";
        const QString path = root + "/request/" + sender + "/" + options.value("handle_token").toString();
        bus.send(message.createReply(QVariant::fromValue(QDBusObjectPath(path))));
        QVariantMap results;
        uint response = 0;
        if (message.member() == "CreateSession") {
            created = true;
            results["session_handle"] = root + "/session/" + sender + "/" + options.value("session_handle_token").toString();
            if (mode == Pending) return true;
        } else if (message.member() == "SelectSources") {
            require(options.value("types").toUInt() == 1 && !options.value("multiple").toBool(), "only one existing monitor can be shared");
        } else if (message.member() == "Start") {
            if (mode == Deny) response = 1;
            else {
                QVariantMap properties{{"position", point(0, 0)}, {"size", point(1920, 1080)}};
                results["streams"] = QVariant::fromValue(QList<TestStream>{{42, properties}});
            }
        } else return false;
        QTimer::singleShot(0, this, [bus, path, response, results] {
            auto signal = QDBusMessage::createSignal(path, "org.freedesktop.portal.Request", "Response");
            signal.setArguments({response, results});
            bus.send(signal);
        });
        return true;
    }
};

int main(int argc, char **argv) {
    QCoreApplication app(argc, argv);
    qDBusRegisterMetaType<TestStream>();
    qDBusRegisterMetaType<QList<TestStream>>();
    auto bus = QDBusConnection::sessionBus();
    Portal portal;
    require(bus.registerService("org.freedesktop.portal.Desktop"), "own isolated test portal service");
    require(bus.registerVirtualObject("/org/freedesktop/portal/desktop", &portal, QDBusConnection::SubPath), "register test portal");
    UsbVideoChannel channel;
    channel.setConnected(true);
    PenPacket hello{};
    hello.toolType = 127;
    hello.action = 1;
    hello.x = 1;
    channel.acceptControl(hello);
    ScreenMirror mirror(&channel);
    QString status;
    QRect selected;
    QObject::connect(&mirror, &ScreenMirror::statusChanged, [&status](const QString &message) { status = message; });
    QObject::connect(&mirror, &ScreenMirror::screenSelected, [&selected](QRect geometry) { selected = geometry; });

    mirror.start(QRect(0, 0, 1920, 1080), 0);
    spinUntil([&mirror] { return !mirror.active(); });
    require(status.contains("cancelled"), "denied permission produces a clear message");
    require(channel.tabletReady(), "denied sharing preserves USB pen session");
    spinUntil([&portal] { return portal.sessionCloses >= 1; });

    portal.mode = Portal::RemoteError;
    mirror.start(QRect(0, 0, 1280, 720), 0);
    spinUntil([&mirror] { return !mirror.active(); });
    require(portal.remoteOpened, "successful permission flow reaches PipeWire remote");
    require(selected == QRect(0, 0, 1920, 1080), "decode actual D-Bus monitor coordinate tuples");
    require(status == "Test remote refused", "capture failure is reported without dropping pen input");

    portal.mode = Portal::Pending;
    portal.created = false;
    mirror.start(QRect(0, 0, 1920, 1080), 0);
    spinUntil([&portal] { return portal.created; });
    mirror.stop();
    spinUntil([&portal] { return portal.requestCloses >= 1 && portal.sessionCloses >= 3; });
    require(!mirror.active() && channel.tabletReady(), "cancel pending permission and close both portal handles");
    channel.setConnected(false);
    mirror.stop("USB disconnected. Screen sharing stopped.");
    channel.setConnected(true);
    channel.acceptControl(hello);
    spinUntil([&status] { return status.startsWith("USB video ready"); });
    require(channel.tabletReady(), "reconnect restores ready status instead of a stale disconnect message");
    std::cout << "PASS: asynchronous portal permission, monitor geometry, failure and cancellation cleanup.\n";
}
