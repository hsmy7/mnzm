#include "GameCoreBridge.h"

#include <android/log.h>
#include <cstring>
#include <string>
#include <time.h>

#include "gamecore/game_core.h"
#include "gamecore/core/game_config.h"
#include "gamecore/system/engine_loop.h"
#include "gamecore/map/road_compositor.h"

// ============================================================
// GameCoreBridge — JNI 实现（Android 专用）
// Kotlin 端包名: com.xianxia.sect.core.nativebridge.GameCoreBridge
//
// 日志直接走 logcat（桥层是唯一允许依赖 android/log.h 的 C++ 文件；
// game-core 本体零 Android 依赖）。
//
// 计划 v2 阶段 5：平台能力注入——MonotonicClock（CLOCK_BOOTTIME，与
// SystemClock.elapsedRealtime 一致含深度睡眠）/ Telemetry（logcat）/
// 热控+电量（Settable 端口，Kotlin 平台层轮询推送）。
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

/// 单调时钟适配器——CLOCK_BOOTTIME 与 SystemClock.elapsedRealtime 一致
/// （单调递增 + 含深度睡眠；steady_clock 不含休眠会低估挂机时长）
class AndroidMonotonicClock final : public gamecore::MonotonicClock {
public:
    int64_t nowMs() override {
#if defined(CLOCK_BOOTTIME)
        struct timespec ts {};
        clock_gettime(CLOCK_BOOTTIME, &ts);
        return static_cast<int64_t>(ts.tv_sec) * 1000 + ts.tv_nsec / 1'000'000;
#else
        struct timespec ts {};
        clock_gettime(CLOCK_MONOTONIC, &ts);
        return static_cast<int64_t>(ts.tv_sec) * 1000 + ts.tv_nsec / 1'000'000;
#endif
    }
};

/// 遥测适配器（阶段 5 落 logcat；接 Bugly/TapDB 上报由平台层后续消费）
class AndroidTelemetrySink final : public gamecore::TelemetrySink {
public:
    void event(const std::string& name, const std::string& propsJson) override {
        __android_log_print(ANDROID_LOG_INFO, "GameCoreTelemetry", "%s %s",
                            name.c_str(), propsJson.c_str());
    }
};

/// 全局引擎实例（仿 NativeBridge 全局态模式：单消费者线程 + JNI 串行）
gamecore::GameCore* g_gameCore = nullptr;
AndroidLogger g_androidLogger;
gamecore::SystemClock g_systemClock;
AndroidMonotonicClock g_androidMonoClock;
AndroidTelemetrySink g_androidTelemetry;
gamecore::SettableThermalStatusProvider g_thermalProvider;
gamecore::SettableBatteryStatusProvider g_batteryProvider;

/// LoopFramePlan → jlongArray（17 槽标量协议；见 engine_loop.h 注释）
jlongArray packLoopFramePlan(JNIEnv* env, const gamecore::system::LoopFramePlan& p) {
    constexpr int kLen = 17;
    jlong buf[kLen] = {0};
    buf[0] = p.paused ? 1 : 0;
    buf[1] = p.tickCount;
    for (int i = 0; i < 5; ++i) buf[2 + i] = p.tickKind[i];
    for (int i = 0; i < 5; ++i) buf[7 + i] = p.tickPhases[i];
    int32_t alphaBits = 0;
    static_assert(sizeof(alphaBits) == sizeof(float), "float bits");
    std::memcpy(&alphaBits, &p.alpha, sizeof(float));
    buf[12] = alphaBits;
    buf[13] = p.frameDeltaNs;
    buf[14] = p.idleNs;
    buf[15] = p.tickTotal;
    buf[16] = p.accumulatedGameMs;
    jlongArray out = env->NewLongArray(kLen);
    if (out) env->SetLongArrayRegion(out, 0, kLen, buf);
    return out;
}

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
    jlong systemSeed, jboolean seedInitialized,
    jboolean authoritativeTickMode) {

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
    config.authoritativeTickMode = (authoritativeTickMode == JNI_TRUE);

    g_gameCore = new gamecore::GameCore(&g_systemClock, &g_androidLogger);
    // 计划 v2 阶段 5：平台能力注入（引擎循环时间源/遥测/热控/电量端口）
    gamecore::PlatformProviders providers;
    providers.monotonicClock = &g_androidMonoClock;
    providers.telemetry = &g_androidTelemetry;
    providers.thermal = &g_thermalProvider;
    providers.battery = &g_batteryProvider;
    g_gameCore->setPlatformProviders(providers);
    const bool ok = g_gameCore->initialize(config);
    if (!ok) {
        delete g_gameCore;
        g_gameCore = nullptr;
        return JNI_FALSE;
    }
    LOGI("nativeInit ok (schema=%s, authoritativeTick=%d)",
         config.snapshotSchemaVersion.c_str(),
         config.authoritativeTickMode ? 1 : 0);
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
// AUTHORITATIVE tick 标量通道（计划 v2 阶段 2d）
// ============================================================

extern "C" JNIEXPORT jint JNICALL
Java_com_xianxia_sect_core_nativebridge_GameCoreBridge_nativeSettlePhase(
    JNIEnv* /*env*/, jobject /*thiz*/) {
    if (!g_gameCore) return 0;
    return static_cast<jint>(g_gameCore->settleOnePhase());
}

extern "C" JNIEXPORT jint JNICALL
Java_com_xianxia_sect_core_nativebridge_GameCoreBridge_nativeRngNextInt(
    JNIEnv* /*env*/, jobject /*thiz*/, jint partitionId) {
    if (!g_gameCore) return 0;
    return g_gameCore->rngNextInt(static_cast<int>(partitionId));
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_xianxia_sect_core_nativebridge_GameCoreBridge_nativeRngSnapshotPartition(
    JNIEnv* /*env*/, jobject /*thiz*/, jint partitionId) {
    if (!g_gameCore) return 0;
    return static_cast<jlong>(g_gameCore->rngSnapshotPartition(static_cast<int>(partitionId)));
}

extern "C" JNIEXPORT void JNICALL
Java_com_xianxia_sect_core_nativebridge_GameCoreBridge_nativeRngRestorePartition(
    JNIEnv* /*env*/, jobject /*thiz*/, jint partitionId, jlong state) {
    if (!g_gameCore) return;
    g_gameCore->rngRestorePartition(static_cast<int>(partitionId),
                                    static_cast<int64_t>(state));
}

extern "C" JNIEXPORT void JNICALL
Java_com_xianxia_sect_core_nativebridge_GameCoreBridge_nativeRngInitSeed(
    JNIEnv* /*env*/, jobject /*thiz*/, jlong seed) {
    if (!g_gameCore) return;
    g_gameCore->rngInitSystemSeed(static_cast<int64_t>(seed));
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

extern "C" JNIEXPORT jboolean JNICALL
Java_com_xianxia_sect_core_nativebridge_GameCoreBridge_nativeImportStateNoRng(
    JNIEnv* env, jobject /*thiz*/,
    jbyteArray stateJson) {
    if (!g_gameCore) return JNI_FALSE;
    return g_gameCore->importStateJsonNoRng(jbytesToString(env, stateJson))
               ? JNI_TRUE
               : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_xianxia_sect_core_nativebridge_GameCoreBridge_nativeApplyReverseDirty(
    JNIEnv* env, jobject /*thiz*/,
    jbyteArray dirtyJson) {
    if (!g_gameCore) return JNI_FALSE;
    return g_gameCore->applyReverseDirty(jbytesToString(env, dirtyJson))
               ? JNI_TRUE
               : JNI_FALSE;
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_xianxia_sect_core_nativebridge_GameCoreBridge_nativeExportDirty(
    JNIEnv* env, jobject /*thiz*/) {
    if (!g_gameCore) {
        return stringToJbytes(env, R"({"version":0,"changed":{},"removed":{}})");
    }
    return stringToJbytes(env, g_gameCore->exportDirtyJson());
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_xianxia_sect_core_nativebridge_GameCoreBridge_nativePollEvents(
    JNIEnv* env, jobject /*thiz*/) {
    if (!g_gameCore) return stringToJbytes(env, "[]");
    return stringToJbytes(env, g_gameCore->pollEventsJson());
}

// ============================================================
// 引擎循环 + 看门狗（计划 v2 阶段 5：游戏循环入 C++）
// ============================================================

extern "C" JNIEXPORT void JNICALL
Java_com_xianxia_sect_core_nativebridge_GameCoreBridge_nativeLoopStart(
    JNIEnv* /*env*/, jobject /*thiz*/) {
    if (g_gameCore) g_gameCore->loop().start();
}

extern "C" JNIEXPORT void JNICALL
Java_com_xianxia_sect_core_nativebridge_GameCoreBridge_nativeLoopSetSpeed(
    JNIEnv* /*env*/, jobject /*thiz*/, jint speed) {
    if (g_gameCore) g_gameCore->loop().time().setSpeed(static_cast<int>(speed));
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_com_xianxia_sect_core_nativebridge_GameCoreBridge_nativeLoopFrame(
    JNIEnv* env, jobject /*thiz*/,
    jboolean pausedOrLoading, jboolean isSaving) {
    if (!g_gameCore) return env->NewLongArray(0);
    const auto plan = g_gameCore->loop().iterate(
        pausedOrLoading == JNI_TRUE, isSaving == JNI_TRUE);
    return packLoopFramePlan(env, plan);
}

extern "C" JNIEXPORT void JNICALL
Java_com_xianxia_sect_core_nativebridge_GameCoreBridge_nativeLoopConsumeDeadTime(
    JNIEnv* /*env*/, jobject /*thiz*/) {
    if (g_gameCore) g_gameCore->loop().time().consumeDeadTime();
}

extern "C" JNIEXPORT void JNICALL
Java_com_xianxia_sect_core_nativebridge_GameCoreBridge_nativeLoopRefundPhases(
    JNIEnv* /*env*/, jobject /*thiz*/, jint count) {
    if (g_gameCore) g_gameCore->loop().time().refundPhases(static_cast<int>(count));
}

extern "C" JNIEXPORT void JNICALL
Java_com_xianxia_sect_core_nativebridge_GameCoreBridge_nativeLoopNotifyUserActivity(
    JNIEnv* /*env*/, jobject /*thiz*/) {
    if (g_gameCore) g_gameCore->loop().notifyUserActivity();
}

extern "C" JNIEXPORT void JNICALL
Java_com_xianxia_sect_core_nativebridge_GameCoreBridge_nativeLoopOnRestart(
    JNIEnv* /*env*/, jobject /*thiz*/) {
    if (g_gameCore) g_gameCore->loop().onLoopRestart();
}

extern "C" JNIEXPORT jint JNICALL
Java_com_xianxia_sect_core_nativebridge_GameCoreBridge_nativeWatchdogVerdict(
    JNIEnv* /*env*/, jobject /*thiz*/,
    jboolean loopActive, jboolean isPaused, jboolean isSaving, jboolean isLoading,
    jboolean secretRealmPauseLock, jlong secretRealmPauseRenewedAtMs) {
    if (!g_gameCore) return -1;
    gamecore::WatchdogFlags flags;
    flags.loopActive = (loopActive == JNI_TRUE);
    flags.isPaused = (isPaused == JNI_TRUE);
    flags.isSaving = (isSaving == JNI_TRUE);
    flags.isLoading = (isLoading == JNI_TRUE);
    flags.secretRealmPauseLock = (secretRealmPauseLock == JNI_TRUE);
    flags.secretRealmPauseRenewedAtMs = static_cast<int64_t>(secretRealmPauseRenewedAtMs);
    return g_gameCore->watchdogVerdict(flags);
}

extern "C" JNIEXPORT void JNICALL
Java_com_xianxia_sect_core_nativebridge_GameCoreBridge_nativeLoopSetThermalStatus(
    JNIEnv* /*env*/, jobject /*thiz*/, jint severity) {
    g_thermalProvider.set(static_cast<gamecore::ThermalState>(severity));
}

extern "C" JNIEXPORT void JNICALL
Java_com_xianxia_sect_core_nativebridge_GameCoreBridge_nativeLoopSetBatteryStatus(
    JNIEnv* /*env*/, jobject /*thiz*/,
    jboolean isLowBattery, jboolean isPowerSaveMode, jint fpsCap, jfloat offsetC) {
    g_batteryProvider.set(isLowBattery == JNI_TRUE, isPowerSaveMode == JNI_TRUE,
                          static_cast<int>(fpsCap), static_cast<float>(offsetC));
}

// ============================================================
// 运行时配置注入（S-10/S-13 清偿：消除 C++ 硬编码默认值与 Kotlin
// GameConfigProvider 的双端漂移；引擎初始化后调用，引擎线程串行）
// ============================================================
extern "C" JNIEXPORT void JNICALL
Java_com_xianxia_sect_core_nativebridge_GameCoreBridge_nativeSetGameConfig(
    JNIEnv* env, jobject /*thiz*/,
    jint warehouseBaseCapacity, jint warehouseCapacityPerBuilding,
    jint lawLoyaltyThreshold, jint lawMoralityThreshold,
    jint lawHerdLoyaltyThreshold, jdouble lawProbPerPoint, jdouble lawMaxProb,
    jdouble lawBaseCaptureRate, jint lawIntelligenceBase,
    jdouble lawElderBonusPerPoint, jint lawDiscipleIntelligenceStep,
    jdouble lawDiscipleBonusPerStep, jint lawReflectionYears,
    jint lawNewDiscipleProtectionMonths, jint lawMaxTheftPerYear,
    jint lawMaxTheftJudgementsPerMonth) {
    gamecore::GameConfig cfg;
    cfg.warehouseBaseCapacity = static_cast<int32_t>(warehouseBaseCapacity);
    cfg.warehouseCapacityPerBuilding =
        static_cast<int32_t>(warehouseCapacityPerBuilding);
    cfg.lawLoyaltyThreshold = static_cast<int32_t>(lawLoyaltyThreshold);
    cfg.lawMoralityThreshold = static_cast<int32_t>(lawMoralityThreshold);
    cfg.lawHerdLoyaltyThreshold = static_cast<int32_t>(lawHerdLoyaltyThreshold);
    cfg.lawProbPerPoint = static_cast<double>(lawProbPerPoint);
    cfg.lawMaxProb = static_cast<double>(lawMaxProb);
    cfg.lawBaseCaptureRate = static_cast<double>(lawBaseCaptureRate);
    cfg.lawIntelligenceBase = static_cast<int32_t>(lawIntelligenceBase);
    cfg.lawElderBonusPerPoint = static_cast<double>(lawElderBonusPerPoint);
    cfg.lawDiscipleIntelligenceStep =
        static_cast<int32_t>(lawDiscipleIntelligenceStep);
    cfg.lawDiscipleBonusPerStep = static_cast<double>(lawDiscipleBonusPerStep);
    cfg.lawReflectionYears = static_cast<int32_t>(lawReflectionYears);
    cfg.lawNewDiscipleProtectionMonths =
        static_cast<int32_t>(lawNewDiscipleProtectionMonths);
    cfg.lawMaxTheftPerYear = static_cast<int32_t>(lawMaxTheftPerYear);
    cfg.lawMaxTheftJudgementsPerMonth =
        static_cast<int32_t>(lawMaxTheftJudgementsPerMonth);
    gamecore::setGameConfig(cfg);
    (void)env;
}

// ============================================================
// 渲染合成器通道（计划 v2 阶段 6：道路逐格合成单一权威）
// 无状态纯函数——不依赖引擎实例，可在引擎初始化前调用（仅需库已加载）。
// 返回扁平 IntArray：[sprite, x, y, w, h] × N（sprite 序 = RoadSprite
// 枚举序 = SpriteAtlasDef.ROAD_RECTS 声明序，Kotlin RoadCompositorBridge
// .SPRITE_KEYS 同序映射图集名）。
// ============================================================
extern "C" JNIEXPORT jintArray JNICALL
Java_com_xianxia_sect_core_nativebridge_GameCoreBridge_nativeRoadCompose(
    JNIEnv* env, jobject /*thiz*/, jint mask, jint tileSize) {
    gamecore::map::RoadDrawOp ops[gamecore::map::kMaxRoadDrawOpsPerTile];
    const int n = gamecore::map::emitRoadDrawOps(mask, tileSize, ops);
    jintArray arr = env->NewIntArray(n * 5);
    if (arr == nullptr) return nullptr;
    jint flat[gamecore::map::kMaxRoadDrawOpsPerTile * 5];
    for (int i = 0; i < n; i++) {
        const int base = i * 5;
        flat[base]     = static_cast<jint>(ops[i].sprite);
        flat[base + 1] = ops[i].x;
        flat[base + 2] = ops[i].y;
        flat[base + 3] = ops[i].w;
        flat[base + 4] = ops[i].h;
    }
    env->SetIntArrayRegion(arr, 0, n * 5, flat);
    return arr;
}
