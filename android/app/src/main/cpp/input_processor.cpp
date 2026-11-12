#include "input_processor.h"
#include <cstring>

namespace teamaccess {

std::vector<uint8_t> InputProcessor::serializeTouch(
    TouchAction action,
    uint8_t pointerId,
    float x,
    float y,
    int32_t extra,
    uint16_t ts
) {
    TouchPacket packet{};
    packet.action = static_cast<uint8_t>(action);
    packet.pointerId = pointerId;
    packet.normalizedX = x;
    packet.normalizedY = y;
    packet.extraData = extra;
    packet.timestamp = ts;

    std::vector<uint8_t> buffer(sizeof(TouchPacket));
    std::memcpy(buffer.data(), &packet, sizeof(TouchPacket));
    return buffer;
}

bool InputProcessor::deserializeTouch(
    const uint8_t* buffer,
    size_t length,
    TouchPacket& outPacket
) {
    if (buffer == nullptr || length < sizeof(TouchPacket)) {
        return false;
    }
    std::memcpy(&outPacket, buffer, sizeof(TouchPacket));
    return true;
}

} // namespace teamaccess
