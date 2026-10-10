#ifndef VIDEOPIPELINE_H
#define VIDEOPIPELINE_H

#include <QSize>
#include <QString>
#include <gst/gst.h>

struct MirrorVideoPreset {
    QSize bounds;
    int fps;
    int bitrate;
};

const MirrorVideoPreset *mirrorVideoPreset(int preset);
QSize mirrorVideoSize(QSize source, int preset);
bool mirrorNvencAvailable();
qint64 mirrorPresentationTimeUs(GstSample *sample);
QString mirrorPipelineDescription(const QString &source, QSize size, int preset, bool useNvenc = false);

#endif
