#include "videopipeline.h"
#include <algorithm>
#include <iterator>

const MirrorVideoPreset *mirrorVideoPreset(int preset) {
    static const MirrorVideoPreset presets[] = {
        {QSize(1280, 720), 30, 6000000},
        {QSize(1920, 1080), 30, 12000000},
        {QSize(1280, 720), 60, 8000000},
        {QSize(1920, 1080), 60, 16000000},
        {QSize(2560, 1440), 60, 24000000},
        {QSize(1280, 720), 120, 12000000},
        {QSize(1920, 1080), 120, 24000000},
        {QSize(2560, 1440), 120, 40000000}
    };
    return preset >= 0 && preset < int(std::size(presets)) ? &presets[preset] : nullptr;
}

QSize mirrorVideoSize(QSize source, int preset) {
    const auto *profile = mirrorVideoPreset(preset);
    if (!profile || source.width() <= 0 || source.height() <= 0) return {};
    const QSize bounds = profile->bounds;
    const double scale = std::min({1.0, double(bounds.width()) / source.width(), double(bounds.height()) / source.height()});
    return QSize(std::max(2, int(source.width() * scale) & ~1),
                 std::max(2, int(source.height() * scale) & ~1));
}

bool mirrorNvencAvailable() {
    for (const char *name : {"nvh264enc", "cudaupload", "cudaconvertscale"}) {
        GstElementFactory *factory = gst_element_factory_find(name);
        if (!factory) return false;
        gst_object_unref(factory);
    }
    GstElement *encoder = gst_element_factory_make("nvh264enc", nullptr);
    if (!encoder) return false;
    const bool available = g_object_class_find_property(G_OBJECT_GET_CLASS(encoder), "tune") &&
        g_object_class_find_property(G_OBJECT_GET_CLASS(encoder), "zerolatency") &&
        g_object_class_find_property(G_OBJECT_GET_CLASS(encoder), "repeat-sequence-header");
    gst_object_unref(encoder);
    return available;
}

qint64 mirrorPresentationTimeUs(GstSample *sample) {
    GstBuffer *buffer = gst_sample_get_buffer(sample);
    if (!GST_BUFFER_PTS_IS_VALID(buffer)) return 0;
    const GstClockTime time = gst_segment_to_running_time(gst_sample_get_segment(sample), GST_FORMAT_TIME, GST_BUFFER_PTS(buffer));
    return GST_CLOCK_TIME_IS_VALID(time) ? qint64(time / GST_USECOND) : 0;
}

QString mirrorPipelineDescription(const QString &source, QSize size, int preset, bool useNvenc) {
    const auto *profile = mirrorVideoPreset(preset);
    if (!profile) return {};
    if (useNvenc) {
        return source + QString(
            " ! queue max-size-buffers=2 max-size-bytes=0 max-size-time=0 leaky=downstream"
            " ! videorate drop-only=true ! video/x-raw,framerate=%3/1"
            " ! cudaupload ! cudaconvertscale"
            " ! video/x-raw(memory:CUDAMemory),format=NV12,width=%1,height=%2,pixel-aspect-ratio=1/1"
            " ! nvh264enc name=encoder preset=p1 tune=ultra-low-latency zerolatency=true"
            " bframes=0 rc-lookahead=0 rc-mode=cbr bitrate=%4 gop-size=%3 repeat-sequence-header=true"
            " ! h264parse config-interval=-1"
            " ! video/x-h264,stream-format=byte-stream,alignment=au,profile=constrained-baseline"
            " ! appsink name=frames sync=false max-buffers=2")
            .arg(size.width()).arg(size.height()).arg(profile->fps).arg(profile->bitrate / 1000);
    }
    return source + QString(
        " ! queue max-size-buffers=2 max-size-bytes=0 max-size-time=0 leaky=downstream"
        " ! videoscale add-borders=false"
        " ! video/x-raw,width=%1,height=%2,pixel-aspect-ratio=1/1"
        " ! videoconvert ! videorate drop-only=true"
        " ! video/x-raw,format=I420,framerate=%3/1"
        " ! openh264enc name=encoder usage-type=screen complexity=low rate-control=bitrate"
        " bitrate=%4 gop-size=%3 multi-thread=2"
        " ! h264parse config-interval=-1"
        " ! video/x-h264,stream-format=byte-stream,alignment=au,profile=constrained-baseline"
        " ! appsink name=frames sync=false max-buffers=2")
        .arg(size.width()).arg(size.height()).arg(profile->fps).arg(profile->bitrate);
}
