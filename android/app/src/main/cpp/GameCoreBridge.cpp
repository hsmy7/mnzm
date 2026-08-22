#include "GameCoreBridge.h"

#include <android/log.h>
#include <string>

#include "gamecore/game_core.h"

// ============================================================
// GameCoreBridge — JNI 实现（Android 专用）
// Kotlin 端包名: com.xianxia.sect.core.nativebridge.GameCoreBridge
//
// 日志直接走 logcat（桥层是唯一允许依赖 android/log.h 的 C++ 文件；
// game-core 本体零 Android 依赖）。
// ============================================================

#define LOG_TAG "GameCoreBridge"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

/// logcat 日志适配器（注入 game-core）
class AndroidLogger final : public gamecore::Logger {
public:
    void log(gamecore::LogLevel level, const std::string& tag,
             const std::string& message) override {
        const android_LogPriority prio = [level]() {
            switch (level) {
                case gamecore::LogLevel::kDebug: return ANDROID_LOG_DEBUG;
                case gamecore::LogLevel::kInfo: return ANDROID_LOG_INFO;
                case gamecore::LogLevel::kWarn: return ANDROID_LOG_WARN;
                default: return ANDROID_LOG_ERROR;
            }
        }();
        __android_log_print(prio, tag.c_str(), "%s", message.c_str());
    }
};

/// 全局引擎实例（仿 NativeBridge 全局态模式：单消费者线程 + JNI 串行）
gamecore::GameCore* g_gameCore = nullptr;
AndroidLogger g_androidLogger;
gamecore::SystemClock g_systemClock;

/// JNI jbyteArray → std::string（copy；execute 参数/结果用）
std::string jbytesToString(JNIEnv* env, jbyteArray array) {
    if (!array) return {};
    const jsize len = env->GetArrayLength(array);
    if (len <= 0) return {};
    jbyte* bytes = env->GetByteArrayElements(array, nullptr);
    std::string out(reinterpret_cast<const char*>(bytes), static_cast<size_t>(len));
    env->ReleaseByteArrayElements(array, bytes, JNI_ABORT);
    return out;
}

/// std::string → jbyteArray
jbyteArray stringToJbytes(JNIEnv* env, const std::string& s) {
    jbyteArray out = env->NewByteArray(static_cast<jsize>(s.size()));
    if (!out) return nullptr;
    env->SetByteArrayRegion(out, 0, static_cast<jsize>(s.size()),
                            reinterpret_cast<const jbyte*>(s.data()));
    return out;
}

}  // namespace

// ============================================================
// 生命周期
// ============================================================

extern "C" JNIEXPORT jboolean JNICALL
Java_com_xianxia_sect_core_nativebridge_GameCoreBridge_nativeInit(
    JNIEnv* env, jobject /*thiz*/,
    jstring snapshotSchemaVersion,
    jlong systemSeed, jboolean seedInitialized) {

    if (g_gameCore) {
        LOGW("nativeInit: already initialized, ignored");
        return JNI_FALSE;
    }

    gamecore::GameCoreConfig config;
    if (snapshotSchemaVersion) {
        const char* v = env->GetStringUTFChars(snapshotSchemaVersion, nullptr);
        if (v) {
            config.snapshotSchemaVersion = v;
            env->ReleaseStringUTFChars(snapshotSchemaVersion, v);
        }
    }
    config.systemSeed = static_cast<int64_t>(systemSeed);
    config.seedInitialized = (seedInitialized == JNI_TRUE);

    g_gameCore = new gamecore::GameCore(&g_systemClock, &g_androidLogger);
    const bool ok = g_gameCore->initialize(config);
    if (!ok) {
        delete g_gameCore;
        g_gameCore = nullptr;
        return JNI_FALSE;
    }
    LOGI("nativeInit ok (schema=%s)", config.snapshotSchemaVersion.c_str());
    return JNI_TRUE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_xianxia_sect_core_nativebridge_GameCoreBridge_nativeDestroy(
    JNIEnv* /*env*/, jobject /*thiz*/) {
    if (g_gameCore) {
        g_gameCore->shutdown();
        delete g_gameCore;
        g_gameCore = nullptr;
    }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_xianxia_sect_core_nativebridge_GameCoreBridge_nativeIsInitialized(
    JNIEnv* /*env*/, jobject /*thiz*/) {
    return (g_gameCore && g_gameCore->isInitialized()) ? JNI_TRUE : JNI_FALSE;
}

// ============================================================
// 逻辑 tick
// ============================================================

extern "C" JNIEXPORT jboolean JNICALL
Java_com_xianxia_sect_core_nativebridge_GameCoreBridge_nativeAdvance(
    JNIEnv* /*env*/, jobject /*thiz*/,
    jlong deltaNs, jlong nowMs) {
    if (!g_gameCore) return JNI_FALSE;
    return g_gameCore->advance(static_cast<int64_t>(deltaNs),
                               static_cast<int64_t>(nowMs))
               ? JNI_TRUE
               : JNI_FALSE;
}

// ============================================================
// 业务操作（ActionId 协议）
// ============================================================

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_xianxia_sect_core_nativebridge_GameCoreBridge_nativeExecute(
    JNIEnv* env, jobject /*thiz*/,
    jint actionId, jbyteArray paramsJson, jlong nowMs) {
    if (!g_gameCore) return stringToJbytes(
        env, R"({"status":"failure","code":"kInternal","message":"GameCore not initialized"})");
    const std::string params = jbytesToString(env, paramsJson);
    const std::string result = g_gameCore->execute(
        static_cast<int32_t>(actionId), params, static_cast<int64_t>(nowMs));
    return stringToJbytes(env, result);
}

// ============================================================
// 状态快照（批次 1 填充实现；桥层只做字节搬运）
// ============================================================

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_xianxia_sect_core_nativebridge_GameCoreBridge_nativeExportState(
    JNIEnv* env, jobject /*thiz*/) {
    if (!g_gameCore) return stringToJbytes(env, "{}");
    return stringToJbytes(env, g_gameCore->exportStateJson());
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_xianxia_sect_core_nativebridge_GameCoreBridge_nativeImportState(
    JNIEnv* env, jobject /*thiz*/,
    jbyteArray stateJson) {
    if (!g_gameCore) return JNI_FALSE;
    return g_gameCore->importStateJson(jbytesToString(env, stateJson))
               ? JNI_TRUE
               : JNI_FALSE;
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_xianxia_sect_core_nativebridge_GameCoreBridge_nativeExportDirty(
    JNIEnv* env, jobject /*thiz*/) {
    if (!g_gameCore) return stringToJbytes(env, R"({"version":0,"changed":{},"removed":[]})");
    return stringToJbytes(env, g_gameCore->exportDirtyJson());
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_xianxia_sect_core_nativebridge_GameCoreBridge_nativePollEvents(
    JNIEnv* env, jobject /*thiz*/) {
    if (!g_gameCore) return stringToJbytes(env, "[]");
    return stringToJbytes(env, g_gameCore->pollEventsJson());
}
