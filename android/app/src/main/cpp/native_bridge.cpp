#include <jni.h>
#include <android/log.h>
#include <string>
#include "input_processor.h"
#include "video_streamer.h"

#define LOG_TAG "TeamAccessNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

using namespace teamaccess;

static VideoStreamer* g_videoStreamer = nullptr;

extern "C" {

JNIEXPORT void JNICALL
Java_com_teamaccess_remote_service_ScreenCaptureService_nativeInit(
    JNIEnv* env,
    jobject /* this */
) {
    if (!g_videoStreamer) {
        g_videoStreamer = new VideoStreamer();
        LOGI("Native VideoStreamer initialized successfully.");
    }
}

JNIEXPORT void JNICALL
Java_com_teamaccess_remote_service_ScreenCaptureService_nativeRelease(
    JNIEnv* env,
    jobject /* this */
) {
    if (g_videoStreamer) {
        delete g_videoStreamer;
        g_videoStreamer = nullptr;
        LOGI("Native VideoStreamer released.");
    }
}

JNIEXPORT jbyteArray JNICALL
Java_com_teamaccess_remote_RemoteViewActivity_nativeSerializeTouch(
    JNIEnv* env,
    jobject /* this */,
    jint action,
    jint pointerId,
    jfloat x,
    jfloat y,
    jint extra,
    jint timestampMs
) {
    std::vector<uint8_t> buffer = InputProcessor::serializeTouch(
        static_cast<TouchAction>(action),
        static_cast<uint8_t>(pointerId),
        x,
        y,
        extra,
        static_cast<uint16_t>(timestampMs)
    );

    jbyteArray result = env->NewByteArray(buffer.size());
    env->SetByteArrayRegion(result, 0, buffer.size(), reinterpret_cast<const jbyte*>(buffer.data()));
    return result;
}

JNIEXPORT jfloatArray JNICALL
Java_com_teamaccess_remote_service_RemoteInputAccessibilityService_nativeDeserializeTouch(
    JNIEnv* env,
    jobject /* this */,
    jbyteArray data
) {
    jsize len = env->GetArrayLength(data);
    jbyte* bytes = env->GetByteArrayElements(data, nullptr);

    TouchPacket packet{};
    bool success = InputProcessor::deserializeTouch(
        reinterpret_cast<const uint8_t*>(bytes),
        static_cast<size_t>(len),
        packet
    );

    env->ReleaseByteArrayElements(data, bytes, JNI_ABORT);

    if (!success) {
        return nullptr;
    }

    // Return array: [action, pointerId, normalizedX, normalizedY, extraData]
    jfloatArray result = env->NewFloatArray(5);
    jfloat elements[5] = {
        static_cast<jfloat>(packet.action),
        static_cast<jfloat>(packet.pointerId),
        packet.normalizedX,
        packet.normalizedY,
        static_cast<jfloat>(packet.extraData)
    };
    env->SetFloatArrayRegion(result, 0, 5, elements);
    return result;
}

} // extern "C"
