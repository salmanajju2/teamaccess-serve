#ifndef TEAMACCESS_INPUT_PROCESSOR_H
#define TEAMACCESS_INPUT_PROCESSOR_H

#include <cstdint>
#include <vector>

namespace teamaccess {

enum class TouchAction : uint8_t {
    ACTION_DOWN = 0,
    ACTION_UP = 1,
    ACTION_MOVE = 2,
    ACTION_CANCEL = 3,
    KEY_EVENT = 4,
    SCROLL = 5
};

// Compact 16-byte binary packet for ultra-low latency (<2ms)
#pragma pack(push, 1)
struct TouchPacket {
    uint8_t action;      // TouchAction
    uint8_t pointerId;   // Finger index (0-9)
    float normalizedX;   // 0.0f to 1.0f (Device independent)
    float normalizedY;   // 0.0f to 1.0f (Device independent)
    int32_t extraData;   // KeyCode or Scroll Delta
    uint16_t timestamp;  // Delta timestamp in ms
};
#pragma pack(pop)

class InputProcessor {
public:
    static std::vector<uint8_t> serializeTouch(TouchAction action, uint8_t pointerId, float x, float y, int32_t extra, uint16_t ts);
    static bool deserializeTouch(const uint8_t* buffer, size_t length, TouchPacket& outPacket);
};

} // namespace teamaccess

#endif // TEAMACCESS_INPUT_PROCESSOR_H
