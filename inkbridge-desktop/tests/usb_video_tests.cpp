#include "usbvideochannel.h"
#include "videopipeline.h"
#include <QCoreApplication>
#include <QtEndian>
#include <gst/app/gstappsink.h>
#include <gst/video/video-event.h>
#include <iostream>
#include <cstdlib>

static void require(bool condition, const char *message) {
    if (!condition) {
        std::cerr << "FAIL: " << message << '\n';
        std::exit(1);
    }
}

static void ready(UsbVideoChannel &channel) {
    channel.setConnected(true);
    PenPacket hello{};
    hello.toolType = 127;
    hello.action = 1;
    hello.x = 1;
    require(channel.acceptControl(hello), "recognize capability packet");
    require(channel.tabletReady(), "updated tablet is ready");
}

static void testChannel() {
    UsbVideoChannel channel;
    require(!channel.tabletReady(), "disconnected tablet is not ready");
    channel.setConnected(true);
    channel.sendFrame("abc", 1280, 720, 12345, true);
    require(channel.takePacket(0).isEmpty(), "legacy tablet receives no unsolicited video");
    ready(channel);
    channel.sendFrame("abc", 1280, 720, 12345, false);
    require(channel.takePacket(0).isEmpty(), "wait for initial keyframe");
    channel.sendFrame("abc", 1280, 720, 12345, true);
    const QByteArray frame = channel.takePacket(0);
    require(frame.toHex() == "494256310300000000050000d002000001000000000000003930000000000000616263", "wire header matches Android fixture");
    channel.packetWritten();
    channel.sendFrame("abc", 1280, 720, 12346, false);
    channel.sendFrame("abc", 1280, 720, 12347, false);
    channel.sendFrame("abc", 1280, 720, 12348, false);
    int requests = 0;
    QObject::connect(&channel, &UsbVideoChannel::keyFrameRequested, [&requests] { ++requests; });
    channel.sendFrame("abc", 1280, 720, 12349, false);
    require(channel.takePacket(0).isEmpty(), "overflow discards stale GOP, not random dependent frames");
    require(requests == 1, "overflow requests a fresh keyframe");
    channel.sendFrame("abc", 1280, 720, 12350, false);
    require(channel.takePacket(0).isEmpty(), "discard dependent frames until recovery");
    channel.sendFrame("abc", 1280, 720, 12351, true);
    require(!channel.takePacket(0).isEmpty(), "keyframe recovers overflow");
    channel.packetWritten();
    channel.sendFrame("abc", 1280, 720, 12352, false);
    channel.stopVideo("Stopped");
    require(!channel.waitForDelivery(0), "shutdown waits for queued status delivery");
    const QByteArray stopped = channel.takePacket(0);
    require(qFromLittleEndian<quint32>(reinterpret_cast<const uchar *>(stopped.constData()) + 16) == UsbVideoChannel::Stopped, "stop supersedes queued frames");
    require(stopped.mid(32) == "Stopped", "stop carries status text");
    require(!channel.waitForDelivery(0), "shutdown also waits for an in-flight transfer");
    channel.packetWritten();
    require(channel.waitForDelivery(0), "writer acknowledgement permits orderly shutdown");
    channel.setConnected(false);
    require(!channel.tabletReady() && channel.takePacket(0).isEmpty(), "disconnect resets queue and capabilities");
    channel.setConnected(true);
    require(!channel.tabletReady(), "reconnect requires a new handshake");
    PenPacket pen{};
    pen.toolType = 2;
    pen.pressure = 3072;
    require(!channel.acceptControl(pen) && pen.pressure == 3072, "pen and pressure are not interpreted as control messages");
}

static void testEncoder(bool useNvenc = false) {
    require(mirrorVideoSize(QSize(3840, 2160), 0) == QSize(1280, 720), "720p bounds");
    require(mirrorVideoSize(QSize(2560, 1600), 1) == QSize(1728, 1080), "preserve monitor aspect ratio");
    require(mirrorVideoSize(QSize(1080, 1920), 0) == QSize(404, 720), "portrait bounds are even");
    require(mirrorVideoSize(QSize(), 0).isEmpty(), "reject invalid monitor size");
    require(mirrorVideoSize(QSize(3840, 2160), 3) == QSize(1920, 1080), "1080p/60 bounds");
    require(mirrorVideoSize(QSize(3840, 2160), 4) == QSize(2560, 1440), "1440p/60 bounds");
    require(mirrorVideoSize(QSize(2560, 1600), 4) == QSize(2304, 1440), "WQHD preserves aspect ratio");
    require(mirrorVideoSize(QSize(1920, 1080), 4) == QSize(1920, 1080), "WQHD does not upscale capture");
    require(mirrorVideoSize(QSize(3840, 2160), 5) == QSize(1280, 720), "720p/120 bounds");
    require(mirrorVideoSize(QSize(3840, 2160), 6) == QSize(1920, 1080), "1080p/120 bounds");
    require(mirrorVideoSize(QSize(3840, 2160), 7) == QSize(2560, 1440), "1440p/120 bounds");
    require(mirrorVideoSize(QSize(3840, 2160), -1).isEmpty(), "reject negative preset");
    require(mirrorVideoSize(QSize(3840, 2160), 8).isEmpty(), "reject unknown preset");
    require(mirrorPipelineDescription("videotestsrc", QSize(320, 180), 8).isEmpty(), "reject unknown pipeline preset");
    const int frameRates[] = {30, 30, 60, 60, 60, 120, 120, 120};
    gst_init(nullptr, nullptr);
    for (int preset = 0; preset < 8; ++preset) {
        GError *error = nullptr;
        const QString description = mirrorPipelineDescription("videotestsrc num-buffers=3", QSize(320, 180), preset, useNvenc);
        GstElement *pipeline = gst_parse_launch(description.toUtf8().constData(), &error);
        require(error == nullptr && pipeline != nullptr, "construct actual H.264 pipeline");
        GstElement *sink = gst_bin_get_by_name(GST_BIN(pipeline), "frames");
        require(gst_element_set_state(pipeline, GST_STATE_PLAYING) != GST_STATE_CHANGE_FAILURE, "start encoder");
        int frames = 0;
        qint64 previousTime = -1;
        while (GstSample *sample = gst_app_sink_try_pull_sample(GST_APP_SINK(sink), 5 * GST_SECOND)) {
            const qint64 time = mirrorPresentationTimeUs(sample);
            require(time > previousTime && time < 1000000, "wire PTS uses running time rather than the encoder timestamp offset");
            previousTime = time;
            GstBuffer *buffer = gst_sample_get_buffer(sample);
            GstMapInfo map{};
            require(gst_buffer_map(buffer, &map, GST_MAP_READ), "map encoded frame");
            const QByteArray bytes(reinterpret_cast<const char *>(map.data), int(map.size));
            if (frames == 0) {
                int numerator = 0, denominator = 0;
                const GstStructure *caps = gst_caps_get_structure(gst_sample_get_caps(sample), 0);
                require(gst_structure_get_fraction(caps, "framerate", &numerator, &denominator), "encoded framerate is negotiated");
                require(numerator == frameRates[preset] * denominator, "encoded framerate matches preset");
                require(!GST_BUFFER_FLAG_IS_SET(buffer, GST_BUFFER_FLAG_DELTA_UNIT), "stream starts with IDR");
                require(bytes.contains(QByteArray::fromHex("0000000167")), "keyframe includes SPS for Android decoder");
                require(bytes.contains(QByteArray::fromHex("0000000168")), "keyframe includes PPS for Android decoder");
            }
            gst_buffer_unmap(buffer, &map);
            gst_sample_unref(sample);
            ++frames;
        }
        require(frames > 0 && gst_app_sink_is_eos(GST_APP_SINK(sink)), "encoder produces frames and clean EOS");
        gst_element_set_state(pipeline, GST_STATE_NULL);
        gst_object_unref(sink);
        gst_object_unref(pipeline);
    }
}

static void testKeyFrameRequest(bool useNvenc) {
    const QString description = mirrorPipelineDescription(
        "videotestsrc is-live=true num-buffers=12 ! video/x-raw,framerate=60/1", QSize(320, 180), 2, useNvenc);
    GError *error = nullptr;
    GstElement *pipeline = gst_parse_launch(description.toUtf8().constData(), &error);
    require(error == nullptr && pipeline != nullptr, "construct keyframe recovery pipeline");
    GstElement *sink = gst_bin_get_by_name(GST_BIN(pipeline), "frames");
    require(gst_element_set_state(pipeline, GST_STATE_PLAYING) != GST_STATE_CHANGE_FAILURE, "start keyframe recovery pipeline");
    int frames = 0;
    bool recovered = false;
    while (GstSample *sample = gst_app_sink_try_pull_sample(GST_APP_SINK(sink), 5 * GST_SECOND)) {
        GstBuffer *buffer = gst_sample_get_buffer(sample);
        if (frames >= 3 && !GST_BUFFER_FLAG_IS_SET(buffer, GST_BUFFER_FLAG_DELTA_UNIT)) {
            GstMapInfo map{};
            require(gst_buffer_map(buffer, &map, GST_MAP_READ), "map requested keyframe");
            const QByteArray bytes(reinterpret_cast<const char *>(map.data), int(map.size));
            require(bytes.contains(QByteArray::fromHex("0000000167")) && bytes.contains(QByteArray::fromHex("0000000168")),
                "requested keyframe repeats decoder configuration");
            gst_buffer_unmap(buffer, &map);
            recovered = true;
        }
        gst_sample_unref(sample);
        if (++frames == 3) {
            require(gst_element_send_event(sink, gst_video_event_new_upstream_force_key_unit(GST_CLOCK_TIME_NONE, TRUE, 0)),
                "encoder accepts USB recovery keyframe request");
        }
        if (recovered) break;
    }
    require(recovered, "USB recovery produces a keyframe before the periodic GOP boundary");
    gst_element_set_state(pipeline, GST_STATE_NULL);
    gst_object_unref(sink);
    gst_object_unref(pipeline);
}

static void testConversionSize() {
    QString description = mirrorPipelineDescription(
        "videotestsrc num-buffers=1 ! video/x-raw,format=RGBx,width=1280,height=720", QSize(320, 180), 2);
    description.replace(" ! videoconvert", " ! videoconvert name=convert");
    GError *error = nullptr;
    GstElement *pipeline = gst_parse_launch(description.toUtf8().constData(), &error);
    require(error == nullptr && pipeline != nullptr, "construct RGB capture pipeline");
    GstElement *sink = gst_bin_get_by_name(GST_BIN(pipeline), "frames");
    require(gst_element_set_state(pipeline, GST_STATE_PLAYING) != GST_STATE_CHANGE_FAILURE, "start RGB capture pipeline");
    GstSample *sample = gst_app_sink_try_pull_sample(GST_APP_SINK(sink), 5 * GST_SECOND);
    require(sample != nullptr, "RGB capture produces AVC");
    GstElement *convert = gst_bin_get_by_name(GST_BIN(pipeline), "convert");
    GstPad *pad = gst_element_get_static_pad(convert, "sink");
    GstCaps *caps = gst_pad_get_current_caps(pad);
    int width = 0, height = 0;
    gst_structure_get_int(gst_caps_get_structure(caps, 0), "width", &width);
    gst_structure_get_int(gst_caps_get_structure(caps, 0), "height", &height);
    require(width == 320 && height == 180, "scale RGB before converting its color format");
    gst_caps_unref(caps);
    gst_object_unref(pad);
    gst_object_unref(convert);
    gst_sample_unref(sample);
    gst_element_set_state(pipeline, GST_STATE_NULL);
    gst_object_unref(sink);
    gst_object_unref(pipeline);
}

int main(int argc, char **argv) {
    QCoreApplication app(argc, argv);
    testChannel();
    testEncoder();
    testConversionSize();
    testKeyFrameRequest(false);
    if (mirrorNvencAvailable()) {
        testEncoder(true);
        testKeyFrameRequest(true);
    }
    std::cout << "PASS: USB capability negotiation, framing, pressure isolation, bounded queues and all H.264 presets.\n";
}
