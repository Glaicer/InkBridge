#include "videopipeline.h"
#include <algorithm>

QSize mirrorVideoSize(QSize source, int preset) {
    if (source.width() <= 0 || source.height() <= 0) return {};
    const QSize bounds = preset == 1 ? QSize(1920, 1080) : QSize(1280, 720);
    const double scale = std::min({1.0, double(bounds.width()) / source.width(), double(bounds.height()) / source.height()});
    return QSize(std::max(2, int(source.width() * scale) & ~1),
                 std::max(2, int(source.height() * scale) & ~1));
}

QString mirrorPipelineDescription(const QString &source, QSize size, int preset) {
    const int fps = preset == 2 ? 60 : 30;
    const int bitrate = preset == 1 ? 12000000 : (preset == 2 ? 8000000 : 6000000);
    return source + QString(
        " ! queue max-size-buffers=2 max-size-bytes=0 max-size-time=0 leaky=downstream"
        " ! videoconvert ! videoscale add-borders=false ! videorate drop-only=true"
        " ! video/x-raw,format=I420,width=%1,height=%2,pixel-aspect-ratio=1/1,framerate=%3/1"
        " ! openh264enc name=encoder usage-type=screen complexity=low rate-control=bitrate"
        " bitrate=%4 gop-size=%3 multi-thread=2"
        " ! h264parse config-interval=-1"
        " ! video/x-h264,stream-format=byte-stream,alignment=au,profile=constrained-baseline"
        " ! appsink name=frames sync=false max-buffers=2")
        .arg(size.width()).arg(size.height()).arg(fps).arg(bitrate);
}
