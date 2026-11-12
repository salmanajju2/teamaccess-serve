#include "video_streamer.h"
#include <algorithm>

namespace teamaccess {

VideoStreamer::VideoStreamer() : lastFrameTimestampMs_(0) {}

VideoStreamer::~VideoStreamer() = default;

void VideoStreamer::convertRgbaToI420(
    const uint8_t* rgbaSrc,
    int width,
    int height,
    int stride,
    uint8_t* dstY,
    int strideY,
    uint8_t* dstU,
    int strideU,
    uint8_t* dstV,
    int strideV
) {
    // Highly efficient conversion loop
    for (int j = 0; j < height; ++j) {
        const uint8_t* srcRow = rgbaSrc + j * stride;
        uint8_t* yRow = dstY + j * strideY;

        for (int i = 0; i < width; ++i) {
            int r = srcRow[i * 4 + 0];
            int g = srcRow[i * 4 + 1];
            int b = srcRow[i * 4 + 2];

            // Standard ITU-R BT.601 conversion formulas
            int y = ((66 * r + 129 * g + 25 * b + 128) >> 8) + 16;
            yRow[i] = static_cast<uint8_t>(std::clamp(y, 16, 235));

            if ((j % 2 == 0) && (i % 2 == 0)) {
                int u = ((-38 * r - 74 * g + 112 * b + 128) >> 8) + 128;
                int v = ((112 * r - 94 * g - 18 * b + 128) >> 8) + 128;

                dstU[(j / 2) * strideU + (i / 2)] = static_cast<uint8_t>(std::clamp(u, 16, 240));
                dstV[(j / 2) * strideV + (i / 2)] = static_cast<uint8_t>(std::clamp(v, 16, 240));
            }
        }
    }
}

bool VideoStreamer::shouldDropFrame(uint64_t currentTimestampMs, int targetFps) {
    if (targetFps <= 0) return false;
    uint64_t minIntervalMs = 1000 / targetFps;
    if (currentTimestampMs - lastFrameTimestampMs_ < minIntervalMs) {
        return true; // Throttle frame to maintain steady frame rate
    }
    lastFrameTimestampMs_ = currentTimestampMs;
    return false;
}

} // namespace teamaccess
