#ifndef VIDEOPIPELINE_H
#define VIDEOPIPELINE_H

#include <QSize>
#include <QString>

QSize mirrorVideoSize(QSize source, int preset);
QString mirrorPipelineDescription(const QString &source, QSize size, int preset);

#endif
