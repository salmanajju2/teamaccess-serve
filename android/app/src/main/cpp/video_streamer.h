#ifndef TEAMACCESS_VIDEO_STREAMER_H
#define TEAMACCESS_VIDEO_STREAMER_H

#include <cstdint>
#include <vector>

namespace teamaccess {

class VideoStreamer {
public:
    VideoStreamer();
    ~VideoStreamer();

    // Fast SIMD/optimized color conversion from RGBA to I420 (YUV420P)
    void convertRgbaToI420(
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
    );

    // Frame throttling and bitrate estimation
    bool shouldDropFrame(uint64_t currentTimestampMs, int targetFps);

private:
    uint64_t lastFrameTimestampMs_;
};

} // namespace teamaccess

#endif // TEAMACCESS_VIDEO_STREAMER_H
