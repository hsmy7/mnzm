#include "GameCoreBridge.h"

#include <android/log.h>
#include <cstring>
#include <string>
#include <time.h>
#include <unistd.h>

#include "gamecore/game_core.h"
#include "gamecore/determinism_probe.h"
#include "gamecore/core/game_config.h"
// R6.2/B16 数值外置：数据文件 → DB 容器注入（含 nlohmann 适配）
#include "gamecore/data/data_inject.h"
#include "gamecore/state/json_codec.h"
#include "gamecore/system/engine_loop.h"
#include "gamecore/map/road_compositor.h"
#include "gamecore/map/island_cliff.h"
#include "gamecore/map/terrain.h"
#include "gamecore/system/battle_execution.h"
#include "gamecore/system/battle_json.h"
#include "gamecore/system/sect_battle.h"
#include "gamecore/system/sect_attack_decision.h"

#include <atomic>
#include <stdlib.h>

// ============================================================
// GameCoreBridge — JNI 实现（Android 专用）
// Kotlin 端包名: com.xianxia.sect.core.nativebridge.GameCoreBridge
//
// 日志直接走 logcat（桥层是唯一允许依赖 android/log.h 的 C++ 文件；
// game-core 本体零 Android 依赖）。
//
// 平台能力注入——MonotonicClock（CLOCK_BOOTTIME，与
// SystemClock.elapsedRealtime 一致含深度睡眠）/ Telemetry（logcat）/
// 热控+电量（Settable 端口，Kotlin 平台层轮询推送）。
// ============================================================

#define LOG_TAG "GameCoreBridge"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

// ============================================================
// JNI 线程亲和性守卫
//
// 背景：C++ 侧 g_gameCore 是**无锁单线程模型**——游戏状态、
// RNG 分区状态、引擎循环热字段全是非原子的普通成员。安全性只依赖一条
// 口头约定："所有状态入口都在 GameEngine 单线程调度器上串行"。但事实上
// 至少三条线程会进入本桥：
//   · 引擎线程（GameDispatcher 单线程）——正常入口
//   · 看门狗线程 / 主线程（GameEngineCore.progressVerdict ← GameLoopDelegate
//     健康检查 + AlarmWatchdogReceiver）→ nativeWatchdogVerdict
//   · 主线程（onUserActivity / gameClock.onSpeedChanged）→ nativeLoopNotifyUserActivity
//     / nativeLoopSetSpeed
// 第三类入口是**设计上的跨线程端口**（C++ 侧对应字段为 atomic / 输入端口），
// 前两类之外的一切调用都属"静默数据竞争"——既不崩也不报错，只会在若干旬后
// 表现为状态错乱，无法归因。
//
// 本守卫的做法：
//   1. nativeInit 记录 owner 线程（= 引擎线程）；紧急重启换线程后由新
//      驱动线程的首个 nativeLoopFrame 经 jniMaybeRebaseOwner 重锚 owner
//      （标志由 EngineLoop::onLoopRestart 置位——生命周期端口专属）；
//   2. 状态入口在 **debug 构建**（NDEBUG 未定义）校验当前线程 == owner，
//      非法进入即 LOGE + abort——把"静默竞争"变成"首犯即崩、可归因"；
//   3. release 构建（NDEBUG）全部擦除为空函数，零开销；
//
// ★ 为什么 release 不 abort：debug 断言的目的是在开发期抓出越界调用，
//   而不是在生产上把可恢复的异常变成崩溃（生产误 abort 风险高）。
//   被标注为 kAnyThread 的跨线程端口不参与校验（见各入口注释）——其中
//   loopStart/loopOnRestart 为**生命周期输入端口**（Kotlin 状态机保证进入
//   时循环非运行态），必须标注 kAnyThread——标注 kEngineOnly 会在
//   debug 构建"后台→前台"循环重启时误 abort。
// ============================================================

/// 全局引擎实例（仿 NativeBridge 全局态模式：单消费者线程 + JNI 串行）。
/// 声明前置：守卫函数（jniMaybeRebaseOwner）早于使用处需要可见。
gamecore::GameCore* g_gameCore = nullptr;

namespace {

/// 引擎 owner 线程 tid（nativeInit 所在线程）；0 = 尚未初始化
std::atomic<pid_t> g_ownerTid{0};

#ifndef NDEBUG
/// 记录 owner 线程（nativeInit 调用；重复初始化不覆盖首次记录）
void jniNoteOwnerThread() {
    const pid_t tid = gettid();
    pid_t expected = 0;
    g_ownerTid.compare_exchange_strong(expected, tid, std::memory_order_relaxed);
}

/// 强制重录 owner 线程（紧急重启换线程后，新驱动线程首个 nativeLoopFrame
/// 进入时调用——owner 旧值不为 0，jniNoteOwnerThread 的 first-wins 不生效）
void jniForceNoteOwnerThread() {
    g_ownerTid.store(gettid(), std::memory_order_relaxed);
}

/// 清除 owner 线程（nativeDestroy）
void jniForgetOwnerThread() {
    g_ownerTid.store(0, std::memory_order_relaxed);
}

/**
 * owner 重锚通道：Kotlin 紧急重启（emergencyRestartGameLoop）会用全新
 * GameDispatcher 线程驱动循环（recreateGameDispatcher），而 owner 只在
 * nativeInit 记录一次——若重锚缺失，新引擎线程的受守卫入口会被误判为
 * 违规进入而 abort（debug 构建），守卫自毁其"换线程自愈"设计意图。
 *
 * 消费点：nativeLoopFrame（新循环的必然首站，且只在驱动线程运行）。标志
 * 由 EngineLoop::onLoopRestart 置位（紧急重启路径专属）；消费后把 owner
 * 重锚到当前线程再进入常规校验——后续受守卫入口照常严格。
 *
 * 剩余窗口（文档化）：重锚仅由"标志置位后的首个 nativeLoopFrame"消费；
 * 若旧（被 OEM 挂起的）驱动线程在标志置位后、新驱动首帧前复活并进入
 * 受守卫入口，仍会 abort——这正是守卫要暴露的异常形态，属预期诊断。
 */
void jniMaybeRebaseOwner(const char* entry) {
    if (!g_gameCore) return;
    if (g_gameCore->loop().consumeOwnerRebasePending()) {
        jniForceNoteOwnerThread();
        __android_log_print(
            ANDROID_LOG_INFO, LOG_TAG,
            "P1-4 owner rebased to tid=%d (entry=%s, 紧急重启换线程后重锚)",
            static_cast<int>(gettid()), entry);
    }
}

/**
 * 校验当前线程 == owner 线程（debug 构建）。
 *
 * @param entry JNI 入口名（用于崩溃日志归因）
 */
void jniRequireEngineThread(const char* entry) {
    const pid_t owner = g_ownerTid.load(std::memory_order_relaxed);
    // 未初始化 → 无 owner 可比，跳过（nativeIsInitialized 等入口可在初始化前进入）
    if (owner == 0) return;
    const pid_t self = gettid();
    if (self == owner) return;
    __android_log_print(
        ANDROID_LOG_ERROR, LOG_TAG,
        "P1-4 线程契约违规：%s 在非引擎线程进入（owner tid=%d, 当前 tid=%d）。"
        "g_gameCore 为无锁单线程模型——跨线程进入即数据竞争。若本入口确为跨线程端口，"
        "请在 GameCoreBridge.cpp 标注并改走 kAnyThread 分类。",
        entry, static_cast<int>(owner), static_cast<int>(self));
    // debug 构建下直接 abort：让违规在开发期暴露，而不是留到生产慢慢错乱
    __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "abort: %s", entry);
    abort();
}

#else
inline void jniNoteOwnerThread() {}
inline void jniForceNoteOwnerThread() {}
inline void jniForgetOwnerThread() {}
inline void jniMaybeRebaseOwner(const char*) {}
inline void jniRequireEngineThread(const char*) {}
#endif

}  // namespace

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
///
/// 桌面（Windows）腿：Bionic 的 `CLOCK_BOOTTIME` 与 32 位 time ABI
/// （`clock_gettime64`）在 llvm-mingw/UCRT 下不存在（链接期 undefined symbol），
/// 故桌面替身为不含深度睡眠的 `CLOCK_MONOTONIC`。**仅影响桌面对拍测试**
/// （Android 真机/模拟器走 Bionic 分支，语义零变化）。
class AndroidMonotonicClock final : public gamecore::MonotonicClock {
public:
    int64_t nowMs() override {
#if defined(_WIN32)
        struct timespec ts {};
        clock_gettime(CLOCK_MONOTONIC, &ts);
        return static_cast<int64_t>(ts.tv_sec) * 1000 + ts.tv_nsec / 1'000'000;
#elif defined(CLOCK_BOOTTIME)
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

/// 遥测适配器（落 logcat；Bugly/TapDB 上报由平台层消费）
class AndroidTelemetrySink final : public gamecore::TelemetrySink {
public:
    void event(const std::string& name, const std::string& propsJson) override {
        __android_log_print(ANDROID_LOG_INFO, "GameCoreTelemetry", "%s %s",
                            name.c_str(), propsJson.c_str());
    }
};

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

/// JNI jstring → std::string（copy；攻击决策 id 参数用）
std::string jstringToStd(JNIEnv* env, jstring s) {
    if (!s) return {};
    const char* chars = env->GetStringUTFChars(s, nullptr);
    if (!chars) return {};
    std::string out(chars);
    env->ReleaseStringUTFChars(s, chars);
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
    jboolean authoritativeTickMode,
    jint terrainWidthCells, jint terrainHeightCells,
    jfloat terrainDecorationDensity, jint terrainBorderTreeRing,
    jint terrainGateX, jint terrainGateY,
    jint terrainGateWidth, jint terrainGateHeight,
    jint terrainMapGenVersion) {

    if (g_gameCore) {
        LOGW("nativeInit: already initialized, ignored");
        return JNI_FALSE;
    }

    // 记录 owner 线程（= 引擎线程）。后续状态入口经
    // jniRequireEngineThread 以此为基准做 debug 断言。
    jniNoteOwnerThread();

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
    // 地图冻结（WS-5b）：地形生成参数（单一数据源 = Kotlin GameConfig.SectMap）
    config.terrainWidthCells = static_cast<int32_t>(terrainWidthCells);
    config.terrainHeightCells = static_cast<int32_t>(terrainHeightCells);
    config.terrainDecorationDensity = static_cast<float>(terrainDecorationDensity);
    config.terrainBorderTreeRing = static_cast<int32_t>(terrainBorderTreeRing);
    config.terrainGateX = static_cast<int32_t>(terrainGateX);
    config.terrainGateY = static_cast<int32_t>(terrainGateY);
    config.terrainGateWidth = static_cast<int32_t>(terrainGateWidth);
    config.terrainGateHeight = static_cast<int32_t>(terrainGateHeight);
    config.terrainMapGenVersion = static_cast<int32_t>(terrainMapGenVersion);

    g_gameCore = new gamecore::GameCore(&g_systemClock, &g_androidLogger);
    // 平台能力注入（引擎循环时间源/遥测/热控/电量端口）
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
    jniRequireEngineThread("nativeDestroy");
    jniForgetOwnerThread();
    if (g_gameCore) {
        g_gameCore->shutdown();
        delete g_gameCore;
        g_gameCore = nullptr;
    }
}

// kAnyThread——只读探针（读 g_gameCore 指针 + isInitialized），
// 看门狗/主线程健康检查合法进入。
extern "C" JNIEXPORT jboolean JNICALL
Java_com_xianxia_sect_core_nativebridge_GameCoreBridge_nativeIsInitialized(
    JNIEnv* /*env*/, jobject /*thiz*/) {
    return (g_gameCore && g_gameCore->isInitialized()) ? JNI_TRUE : JNI_FALSE;
}

// ============================================================
// AUTHORITATIVE tick 标量通道
// ============================================================

extern "C" JNIEXPORT jint JNICALL
Java_com_xianxia_sect_core_nativebridge_GameCoreBridge_nativeSettlePhase(
    JNIEnv* /*env*/, jobject /*thiz*/) {
    jniRequireEngineThread("nativeSettlePhase");
    if (!g_gameCore) return 0;
    return static_cast<jint>(g_gameCore->settleOnePhase());
}

// AI 热控批量上界推送（Kotlin ThermalMonitor 平台决策——12/6/3；
// kEngineOnly——月结前引擎线程调用）
extern "C" JNIEXPORT void JNICALL
Java_com_xianxia_sect_core_nativebridge_GameCoreBridge_nativeSetAiThermalBatchSize(
    JNIEnv* /*env*/, jobject /*thiz*/, jint batchSize) {
    jniRequireEngineThread("nativeSetAiThermalBatchSize");
    if (!g_gameCore) return;
    g_gameCore->setAiThermalBatchSize(static_cast<int32_t>(batchSize));
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_xianxia_sect_core_nativebridge_GameCoreBridge_nativeSettleMonth(
    JNIEnv* env, jobject /*thiz*/) {
    jniRequireEngineThread("nativeSettleMonth");
    if (!g_gameCore) return stringToJbytes(env, "{}");
    return stringToJbytes(env, g_gameCore->settleMonth());
}

extern "C" JNIEXPORT void JNICALL
Java_com_xianxia_sect_core_nativebridge_GameCoreBridge_nativeResetAutoRecruitIdle(
    JNIEnv* /*env*/, jobject /*thiz*/) {
    jniRequireEngineThread("nativeResetAutoRecruitIdle");
    if (!g_gameCore) return;
    g_gameCore->resetAutoRecruitIdle();
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_xianxia_sect_core_nativebridge_GameCoreBridge_nativeSettleYear(
    JNIEnv* env, jobject /*thiz*/) {
    jniRequireEngineThread("nativeSettleYear");
    if (!g_gameCore) return stringToJbytes(env, "{}");
    return stringToJbytes(env, g_gameCore->settleYear());
}

// RNG 委托通道——kEngineOnly 断言（P1-4 正式收口，见 handover §2.39）。
// 全部跨线程调用面已收敛到引擎线程（UI 抽取派生化/引擎化、重播种并入
// restartGameInternal、存档快照 withEngineContext 采样、读档恢复）；
// 2026-09-10 真机/开发期 ≥1h debug 混合操作观察窗零警告后由过渡 WARN
// 守卫升级为断言（batch-10，观察记录 docs/parallel-batches/
// batch-10-verification-record.md）。
extern "C" JNIEXPORT jint JNICALL
Java_com_xianxia_sect_core_nativebridge_GameCoreBridge_nativeRngNextInt(
    JNIEnv* /*env*/, jobject /*thiz*/, jint partitionId) {
    jniRequireEngineThread("nativeRngNextInt");
    if (!g_gameCore) return 0;
    return g_gameCore->rngNextInt(static_cast<int>(partitionId));
}

// RNG 快照读——存档快照导出已经
// GameEngineSaveOps.getStateSnapshot 收敛到引擎线程采样。
extern "C" JNIEXPORT jlong JNICALL
Java_com_xianxia_sect_core_nativebridge_GameCoreBridge_nativeRngSnapshotPartition(
    JNIEnv* /*env*/, jobject /*thiz*/, jint partitionId) {
    jniRequireEngineThread("nativeRngSnapshotPartition");
    if (!g_gameCore) return 0;
    return static_cast<jlong>(g_gameCore->rngSnapshotPartition(static_cast<int>(partitionId)));
}

// RNG 恢复——读档导入经 loadData
// （withEngineContext）→ loadFromSnapshot 锁内原子切换，已在引擎线程。
extern "C" JNIEXPORT void JNICALL
Java_com_xianxia_sect_core_nativebridge_GameCoreBridge_nativeRngRestorePartition(
    JNIEnv* /*env*/, jobject /*thiz*/, jint partitionId, jlong state) {
    jniRequireEngineThread("nativeRngRestorePartition");
    if (!g_gameCore) return;
    g_gameCore->rngRestorePartition(static_cast<int>(partitionId),
                                    static_cast<int64_t>(state));
}

// RNG 重播种——重启播种在 restartGameInternal 引擎上下文；
// 新档播种在 createNewGame 引擎侧（既有）。
extern "C" JNIEXPORT void JNICALL
Java_com_xianxia_sect_core_nativebridge_GameCoreBridge_nativeRngInitSeed(
    JNIEnv* /*env*/, jobject /*thiz*/, jlong seed) {
    jniRequireEngineThread("nativeRngInitSeed");
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
    jniRequireEngineThread("nativeExecute");
    if (!g_gameCore) return stringToJbytes(
        env, R"({"status":"failure","code":"kInternal","message":"GameCore not initialized"})");
    const std::string params = jbytesToString(env, paramsJson);
    const std::string result = g_gameCore->execute(
        static_cast<int32_t>(actionId), params, static_cast<int64_t>(nowMs));
    return stringToJbytes(env, result);
}

// ============================================================
// 状态快照（桥层只做字节搬运）
// ============================================================

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_xianxia_sect_core_nativebridge_GameCoreBridge_nativeExportState(
    JNIEnv* env, jobject /*thiz*/) {
    jniRequireEngineThread("nativeExportState");
    if (!g_gameCore) return stringToJbytes(env, "{}");
    return stringToJbytes(env, g_gameCore->exportStateJson());
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_xianxia_sect_core_nativebridge_GameCoreBridge_nativeImportState(
    JNIEnv* env, jobject /*thiz*/,
    jbyteArray stateJson) {
    jniRequireEngineThread("nativeImportState");
    if (!g_gameCore) return JNI_FALSE;
    return g_gameCore->importStateJson(jbytesToString(env, stateJson))
               ? JNI_TRUE
               : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_xianxia_sect_core_nativebridge_GameCoreBridge_nativeImportStateNoRng(
    JNIEnv* env, jobject /*thiz*/,
    jbyteArray stateJson) {
    jniRequireEngineThread("nativeImportStateNoRng");
    if (!g_gameCore) return JNI_FALSE;
    return g_gameCore->importStateJsonNoRng(jbytesToString(env, stateJson))
               ? JNI_TRUE
               : JNI_FALSE;
}

// 手动招募单招（Kotlin DiscipleFacadeImpl.recruitDiscipleFromList 等价下沉：
// AUTHORITATIVE 单真相源——C++ 直接招募入宗，状态变化经下一 tick 前向 diff
// 推送镜像。信封见 GameCore::manualRecruitFromList KDoc）。
extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_xianxia_sect_core_nativebridge_GameCoreBridge_nativeManualRecruitFromList(
    JNIEnv* env, jobject /*thiz*/, jstring discipleId) {
    jniRequireEngineThread("nativeManualRecruitFromList");
    if (!g_gameCore) {
        return stringToJbytes(env,
            R"({"ok":false,"newId":"","age":0,"name":"","reason":"UNKNOWN"})");
    }
    return stringToJbytes(env, g_gameCore->manualRecruitFromList(
        jstringToStd(env, discipleId)));
}

// 一键招募全部（Kotlin GameEngine.recruitAllFromList 等价下沉；信封见
// GameCore::manualRecruitAll KDoc）。
extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_xianxia_sect_core_nativebridge_GameCoreBridge_nativeRecruitAllFromList(
    JNIEnv* env, jobject /*thiz*/) {
    jniRequireEngineThread("nativeRecruitAllFromList");
    if (!g_gameCore) {
        return stringToJbytes(env, R"({"ok":false,"count":0,"reason":"UNKNOWN"})");
    }
    return stringToJbytes(env, g_gameCore->manualRecruitAll());
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_xianxia_sect_core_nativebridge_GameCoreBridge_nativeExportDirty(
    JNIEnv* env, jobject /*thiz*/) {
    jniRequireEngineThread("nativeExportDirty");
    if (!g_gameCore) {
        return stringToJbytes(env, R"({"version":0,"changed":{},"removed":{}})");
    }
    // 传输编码分发（R2.2）：dirtyExportProtobuf 关 = 旧 JSON 文本，开 = GameView
    // protobuf 信封（exportDirty 内部按模式选择，未初始化/异常返回合法空信封）。
    // JNI 签名不变，仅字节载荷编码换轨。
    return stringToJbytes(env, g_gameCore->exportDirty());
}

// 镜像通道传输编码开关（R2.2 灰度：Kotlin NativeEngineFlag.mirrorProtobufTransport
// 驱动）——与 nativeSetAiThermalBatchSize 同族引擎线程控制端口，仅切换
// nativeExportDirty 的输出编码，不改导出内容/版本号/基线消费语义。
extern "C" JNIEXPORT void JNICALL
Java_com_xianxia_sect_core_nativebridge_GameCoreBridge_nativeSetDirtyExportProtobuf(
    JNIEnv* /*env*/, jobject /*thiz*/, jboolean on) {
    jniRequireEngineThread("nativeSetDirtyExportProtobuf");
    if (!g_gameCore) return;
    g_gameCore->setDirtyExportProtobuf(on == JNI_TRUE);
}

// 列级增量导出开关（R2.4/B09：Kotlin NativeEngineFlag.dirtyColumnExport 驱动）
// ——与 nativeSetDirtyExportProtobuf 同族引擎线程控制端口，仅切换 exportDirty
// 的增量来源（列级写屏障 vs 全量树 diff），不改导出面协议/版本号语义；
// 【JNI 面豁免登记】新增引擎控制端口（非玩法操作，不塞业务操作码表），
// 沿 R0.2 nativeFpDeterminismProbe / R2.2 nativeSetDirtyExportProtobuf 先例。
extern "C" JNIEXPORT void JNICALL
Java_com_xianxia_sect_core_nativebridge_GameCoreBridge_nativeSetDirtyExportColumn(
    JNIEnv* /*env*/, jobject /*thiz*/, jboolean on) {
    jniRequireEngineThread("nativeSetDirtyExportColumn");
    if (!g_gameCore) return;
    g_gameCore->setDirtyExportColumn(on == JNI_TRUE);
}

// ============================================================
// 战斗执行通道（AI 兽战/任务完成生产接线）
//
// 输入 op JSON：{"team":[Combatant...], "beasts":[Combatant...],
//               "playerDamageModifier":1.0, "maxTurns":25, "timeoutMs":-1}
// 输出：{"turn":N, "timedOut":bool, "winner":"TEAM|BEASTS|DRAW",
//        "rewards":{...}, "team":[Combatant...], "beasts":[Combatant...]}
//
// RNG：消费 GameCore 的 BATTLE 分区（kBattle）——AUTHORITATIVE 下委托式
// RNG 单一真相源（Kotlin NativeBackedRng 委托同一分区，序列天然一致）。
// 超时：timeoutMs<0 不检查（生产默认传 -1 由调用方保证轻量战斗）；>0 时
// 用 Android 单调时钟检查（对齐 Kotlin MAX_BATTLE_DURATION_MS 语义）。
// ============================================================

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_xianxia_sect_core_nativebridge_GameCoreBridge_nativeBattleExecute(
    JNIEnv* env, jobject /*thiz*/, jbyteArray opJson) {
    jniRequireEngineThread("nativeBattleExecute");
    if (g_gameCore) g_gameCore->noteNonSettlementMutation();  // R2/B09 列级锁存
    if (!g_gameCore) {
        return stringToJbytes(env, R"({"error":"GameCore not initialized"})");
    }
    try {
        const std::string input = jbytesToString(env, opJson);
        const auto op = nlohmann::json::parse(input);
        std::vector<gamecore::battle::Combatant> team;
        if (op.contains("team") && op["team"].is_array()) {
            for (const auto& t : op.at("team")) {
                team.push_back(gamecore::battle::combatantFromJson(t));
            }
        }
        std::vector<gamecore::battle::Combatant> beasts;
        if (op.contains("beasts") && op["beasts"].is_array()) {
            for (const auto& b : op.at("beasts")) {
                beasts.push_back(gamecore::battle::combatantFromJson(b));
            }
        }
        gamecore::battle::BattleState state;
        state.team = std::move(team);
        state.beasts = std::move(beasts);
        state.maxTurns = op.value("maxTurns", gamecore::battle::kMaxTurns);
        auto& battleRng = g_gameCore->rng().getRng(gamecore::rng::RngPartition::kBattle);
        const auto out = gamecore::battle::executeBattle(
            state, op.value("playerDamageModifier", 1.0), battleRng,
            op.value("timeoutMs", -1LL), &g_androidMonoClock);
        nlohmann::json result;
        result["turn"] = out.turn;
        result["timedOut"] = out.timedOut;
        result["winner"] = out.winner == gamecore::battle::BattleWinner::kTeam
            ? "TEAM"
            : (out.winner == gamecore::battle::BattleWinner::kBeasts ? "BEASTS" : "DRAW");
        result["rewards"] = out.rewards;
        result["rounds"] = gamecore::battle::roundsToJson(out.rounds);
        result["team"] = nlohmann::json::array();
        for (const auto& c : out.team) {
            result["team"].push_back(gamecore::battle::combatantToJson(c));
        }
        result["beasts"] = nlohmann::json::array();
        for (const auto& c : out.beasts) {
            result["beasts"].push_back(gamecore::battle::combatantToJson(c));
        }
        return stringToJbytes(env, result.dump());
    } catch (const std::exception& e) {
        nlohmann::json err = {{"error", e.what()}};
        return stringToJbytes(env, err.dump());
    }
}

// ============================================================
// AI 宗门战执行通道（洞天 AI 操作生产接线）
//
// 输入 op JSON：{"attackers":[Combatant...], "defenders":[Combatant...]}
// 输出：{"turns":N, "winner":"ATTACKER|DEFENDER|DRAW",
//        "rounds":[...], "attackers":[Combatant...], "defenders":[Combatant...]}
//
// 第三战斗引擎 executeUnifiedAIBattle 等价（sect_battle.h）——AI vs AI
// 宗门战/洞天 AI 操作 100% 共用；RNG 消费 BATTLE 分区（与主战斗同一分区，
// 委托式真相源）。失败返回 {"error":"..."}（调用方回退 Kotlin）。
// ============================================================

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_xianxia_sect_core_nativebridge_GameCoreBridge_nativeAiBattleExecute(
    JNIEnv* env, jobject /*thiz*/, jbyteArray opJson) {
    jniRequireEngineThread("nativeAiBattleExecute");
    if (g_gameCore) g_gameCore->noteNonSettlementMutation();  // R2/B09 列级锁存
    if (!g_gameCore) {
        return stringToJbytes(env, R"({"error":"GameCore not initialized"})");
    }
    try {
        const std::string input = jbytesToString(env, opJson);
        const auto op = nlohmann::json::parse(input);
        std::vector<gamecore::battle::Combatant> attackers;
        if (op.contains("attackers") && op["attackers"].is_array()) {
            for (const auto& a : op.at("attackers")) {
                attackers.push_back(gamecore::battle::combatantFromJson(a));
            }
        }
        std::vector<gamecore::battle::Combatant> defenders;
        if (op.contains("defenders") && op["defenders"].is_array()) {
            for (const auto& d : op.at("defenders")) {
                defenders.push_back(gamecore::battle::combatantFromJson(d));
            }
        }
        auto& battleRng = g_gameCore->rng().getRng(gamecore::rng::RngPartition::kBattle);
        const auto out = gamecore::battle::executeAiBattle(
            std::move(attackers), std::move(defenders), battleRng,
            -1, &g_androidMonoClock);
        nlohmann::json result;
        result["turns"] = out.turns;
        result["winner"] = out.winner == gamecore::battle::AiBattleWinner::kAttacker
            ? "ATTACKER"
            : (out.winner == gamecore::battle::AiBattleWinner::kDefender ? "DEFENDER" : "DRAW");
        result["rounds"] = gamecore::battle::roundsToJson(out.rounds);
        result["attackers"] = nlohmann::json::array();
        for (const auto& c : out.attackers) {
            result["attackers"].push_back(gamecore::battle::combatantToJson(c));
        }
        result["defenders"] = nlohmann::json::array();
        for (const auto& c : out.defenders) {
            result["defenders"].push_back(gamecore::battle::combatantToJson(c));
        }
        return stringToJbytes(env, result.dump());
    } catch (const std::exception& e) {
        nlohmann::json err = {{"error", e.what()}};
        return stringToJbytes(env, err.dump());
    }
}

// ============================================================
// AI 攻击决策通道（生产路由；对拍桥见 GameCoreJni.cpp）
//   nativeDecidePlayerAttack：AI 攻玩家预警决策（自包含，无参）
//   nativeCheckAttackConditions：AI vs AI 逐目标判定（id + playerGarrison JSON）
// ============================================================

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_xianxia_sect_core_nativebridge_GameCoreBridge_nativeDecidePlayerAttack(
    JNIEnv* env, jobject /*thiz*/) {
    jniRequireEngineThread("nativeDecidePlayerAttack");
    if (g_gameCore) g_gameCore->noteNonSettlementMutation();  // R2/B09 列级锁存
    if (!g_gameCore) return stringToJbytes(env, R"({"error":"GameCore not initialized"})");
    try {
        const auto decision = gamecore::system::detail::decidePlayerAttack(
            g_gameCore->state(), g_gameCore->rng());
        nlohmann::json j;
        j["type"] = decision.type == gamecore::system::detail::PlayerAttackDecisionType::kGenerateWarning
            ? "GENERATE_WARNING" : "SKIP";
        if (decision.type == gamecore::system::detail::PlayerAttackDecisionType::kGenerateWarning) {
            j["attackerSectId"] = decision.attackerSectId;
            j["attackerSectName"] = decision.attackerSectName;
        }
        return stringToJbytes(env, j.dump());
    } catch (const std::exception& e) {
        nlohmann::json err = {{"error", e.what()}};
        return stringToJbytes(env, err.dump());
    }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_xianxia_sect_core_nativebridge_GameCoreBridge_nativeCheckAttackConditions(
    JNIEnv* env, jobject /*thiz*/, jstring attackerId, jstring defenderId,
    jbyteArray playerGarrisonJson) {
    jniRequireEngineThread("nativeCheckAttackConditions");
    if (g_gameCore) g_gameCore->noteNonSettlementMutation();  // R2/B09 列级锁存
    if (!g_gameCore) return JNI_FALSE;
    try {
        const std::string aiStr = jstringToStd(env, attackerId);
        const std::string diStr = jstringToStd(env, defenderId);
        auto& state = g_gameCore->state();
        const auto& worldSects = state.gameData.worldMapSects;
        const gamecore::state::WorldSect* attacker = nullptr;
        const gamecore::state::WorldSect* defender = nullptr;
        for (const auto& s : worldSects) {
            if (s.id == aiStr) attacker = &s;
            else if (s.id == diStr) defender = &s;
        }
        if (attacker == nullptr || defender == nullptr) return JNI_FALSE;  // 未找到 → 不消费

        std::map<std::string, std::vector<gamecore::state::Disciple>> playerGarrison;
        if (playerGarrisonJson != nullptr) {
            const auto j = nlohmann::json::parse(jbytesToString(env, playerGarrisonJson));
            if (j.is_object()) {
                for (auto it = j.begin(); it != j.end(); ++it) {
                    if (it.value().is_array()) {
                        std::vector<gamecore::state::Disciple> vec;
                        for (const auto& d : it.value()) {
                            gamecore::state::Disciple disc;
                            gamecore::state::from_json(d, disc);
                            vec.push_back(std::move(disc));
                        }
                        playerGarrison[it.key()] = std::move(vec);
                    }
                }
            }
        }
        const bool result = gamecore::system::detail::checkAttackConditions(
            state, *attacker, *defender, playerGarrison, g_gameCore->rng());
        return result ? JNI_TRUE : JNI_FALSE;
    } catch (const std::exception&) {
        return JNI_FALSE;
    }
}

// G7 战斗残余：AI vs AI 战胜后占领判定（sect_attack_decision.h computeCanOccupy，
// 纯确定性零 RNG——Kotlin executeSectBattleCore 战斗胜利后调用，替代内联判定）
extern "C" JNIEXPORT jboolean JNICALL
Java_com_xianxia_sect_core_nativebridge_GameCoreBridge_nativeComputeCanOccupy(
    JNIEnv* env, jobject /*thiz*/, jbyteArray payloadJson) {
    jniRequireEngineThread("nativeComputeCanOccupy");
    if (payloadJson == nullptr) return JNI_FALSE;
    try {
        const auto j = nlohmann::json::parse(jbytesToString(env, payloadJson));
        const bool winnerIsAttacker = j.value("winnerIsAttacker", false);

        std::vector<gamecore::state::Disciple> defenders;
        if (j.contains("defenders") && j["defenders"].is_array()) {
            for (const auto& d : j["defenders"]) {
                gamecore::state::Disciple disc;
                gamecore::state::from_json(d, disc);
                defenders.push_back(std::move(disc));
            }
        }
        std::vector<std::string> deadDefenderIds;
        if (j.contains("deadDefenderIds") && j["deadDefenderIds"].is_array()) {
            for (const auto& id : j["deadDefenderIds"]) {
                deadDefenderIds.push_back(id.get<std::string>());
            }
        }
        const bool result = gamecore::system::detail::computeCanOccupy(
            winnerIsAttacker, defenders, deadDefenderIds);
        return result ? JNI_TRUE : JNI_FALSE;
    } catch (const std::exception&) {
        return JNI_FALSE;
    }
}


// kAnyThread——跨线程**生命周期输入端口**（不可加 jniRequireEngineThread）：
// 循环启动/重启按 Kotlin 设计可从主线程调用（GameForegroundService.onStartCommand
// 前台服务启动、GameActivity.onResume → resumeFromBackground 后台恢复，
// 见 GameEngineCore.prepareLoopStart 注释"startGameLoop 可能在主线程被调"），
// 且 start/stop/emergency 经 Kotlin loopOpLock + phase 状态机与 iterate 串行化
// （进入时循环必非运行态，EngineLoop::start 仅做帧状态清零与时钟基准重置）。
// 必须保持 kAnyThread 标注：本入口的调用线程是主线程而非引擎 owner 线程，
// 加引擎线程断言会使"后台→前台"循环重启在 debug 构建即 abort。
extern "C" JNIEXPORT void JNICALL
Java_com_xianxia_sect_core_nativebridge_GameCoreBridge_nativeLoopStart(
    JNIEnv* /*env*/, jobject /*thiz*/) {
    if (g_gameCore) g_gameCore->loop().start();
}

// kAnyThread——跨线程输入端口（UI 速度按钮经 gameClock.onSpeedChanged
// 钩子推送，主线程进入；EngineLoop.speed_/accumulatedGameMs_ 为 atomic）
extern "C" JNIEXPORT void JNICALL
Java_com_xianxia_sect_core_nativebridge_GameCoreBridge_nativeLoopSetSpeed(
    JNIEnv* /*env*/, jobject /*thiz*/, jint speed) {
    if (g_gameCore) g_gameCore->loop().time().setSpeed(static_cast<int>(speed));
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_com_xianxia_sect_core_nativebridge_GameCoreBridge_nativeLoopFrame(
    JNIEnv* env, jobject /*thiz*/,
    jboolean pausedOrLoading, jboolean isSaving) {
    // 重锚消费点：紧急重启换线程后，新驱动线程的首帧把 owner 重锚到
    // 自身（标志由 nativeLoopOnRestart → EngineLoop::onLoopRestart 置位；
    // 正常启动路径无标志，零行为变化）。必须先于守卫校验执行。
    jniMaybeRebaseOwner("nativeLoopFrame");
    jniRequireEngineThread("nativeLoopFrame");
    if (!g_gameCore) return env->NewLongArray(0);
    const auto plan = g_gameCore->loop().iterate(
        pausedOrLoading == JNI_TRUE, isSaving == JNI_TRUE);
    return packLoopFramePlan(env, plan);
}

extern "C" JNIEXPORT void JNICALL
Java_com_xianxia_sect_core_nativebridge_GameCoreBridge_nativeLoopConsumeDeadTime(
    JNIEnv* /*env*/, jobject /*thiz*/) {
    jniRequireEngineThread("nativeLoopConsumeDeadTime");
    if (g_gameCore) g_gameCore->loop().time().consumeDeadTime();
}

extern "C" JNIEXPORT void JNICALL
Java_com_xianxia_sect_core_nativebridge_GameCoreBridge_nativeLoopRefundPhases(
    JNIEnv* /*env*/, jobject /*thiz*/, jint count) {
    jniRequireEngineThread("nativeLoopRefundPhases");
    if (g_gameCore) g_gameCore->loop().time().refundPhases(static_cast<int>(count));
}

// kAnyThread——跨线程输入端口（onUserActivity 由触控/UI 事件在主线程调用）
extern "C" JNIEXPORT void JNICALL
Java_com_xianxia_sect_core_nativebridge_GameCoreBridge_nativeLoopNotifyUserActivity(
    JNIEnv* /*env*/, jobject /*thiz*/) {
    if (g_gameCore) g_gameCore->loop().notifyUserActivity();
}

// kAnyThread——跨线程**生命周期输入端口**（不可加 jniRequireEngineThread）：
// performEmergencyRestart 由看门狗线程 / 主线程 HealthCheck / Alarm 兜底统一
// 调用（Kotlin 注释"全恢复路径统一入口，可从任意线程调用"）。其置位的
// owner 重锚标志由新驱动线程的首个 nativeLoopFrame 消费（见
// jniMaybeRebaseOwner）；剩余帧状态清零语义不变。
extern "C" JNIEXPORT void JNICALL
Java_com_xianxia_sect_core_nativebridge_GameCoreBridge_nativeLoopOnRestart(
    JNIEnv* /*env*/, jobject /*thiz*/) {
    if (g_gameCore) g_gameCore->loop().onLoopRestart();
}

// kAnyThread——看门狗监控端口（progressVerdict 由看门狗协程、
// GameLoopDelegate 健康检查与 AlarmWatchdogReceiver 跨线程调用，属设计内进入）
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

// kAnyThread——平台状态推送端口（热控/电量由 Kotlin 平台层轮询推送，
// SettableXxxProvider 内部自带同步，线程无关）
extern "C" JNIEXPORT void JNICALL
Java_com_xianxia_sect_core_nativebridge_GameCoreBridge_nativeLoopSetThermalStatus(
    JNIEnv* /*env*/, jobject /*thiz*/, jint severity) {
    g_thermalProvider.set(static_cast<gamecore::ThermalState>(severity));
}

// kAnyThread——平台状态推送端口（同上）
extern "C" JNIEXPORT void JNICALL
Java_com_xianxia_sect_core_nativebridge_GameCoreBridge_nativeLoopSetBatteryStatus(
    JNIEnv* /*env*/, jobject /*thiz*/,
    jboolean isLowBattery, jboolean isPowerSaveMode, jint fpsCap, jfloat offsetC) {
    g_batteryProvider.set(isLowBattery == JNI_TRUE, isPowerSaveMode == JNI_TRUE,
                          static_cast<int>(fpsCap), static_cast<float>(offsetC));
}

// ============================================================
// 运行时配置注入（消除 C++ 硬编码默认值与 Kotlin
// GameConfigProvider 的双端漂移）
// ============================================================

// kAnyThread——注入点之一在 Dagger 单例构造期（CultivationEventProcessor
// init，触发线程取决于 DI 图实例化顺序，非恒引擎线程）；幂等 + 引擎结算前
// 完成，不改判为契约违规。收敛为"仅引擎线程注入"属后续专项。
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
// 数值外置数据注入（R6.2 / B16）
//
// 豁免登记与端口总数对照见 Kotlin 侧
// GameCoreBridge.nativeSetGameData 的 KDoc——**+1 端口**，注入仅初始化期一次，
// 零每帧/每事务跨线。
//
// 语义：把 assets/data/game-data.json 全文交给 C++
// `gamecore::data::inject::injectFromJson`，由 data_store 状态机保证
// 「仅初始化期一次」；重复注入被拒（幂等），解析失败时**保留头文件内联默认
// 兜底**（禁止静默空表），不抛异常、不阻断引擎启动。
// ============================================================
extern "C" JNIEXPORT jboolean JNICALL
Java_com_xianxia_sect_core_nativebridge_GameCoreBridge_nativeSetGameData(
    JNIEnv* env, jobject /*thiz*/, jstring json) {
    if (json == nullptr) return JNI_FALSE;
    const char* chars = env->GetStringUTFChars(json, nullptr);
    if (chars == nullptr) return JNI_FALSE;
    const std::string payload(chars);
    env->ReleaseStringUTFChars(json, chars);
    return gamecore::data::inject::injectFromJson(payload) ? JNI_TRUE : JNI_FALSE;
}

// ============================================================
// 渲染合成器通道（道路逐格合成单一权威）
// 无状态纯函数——不依赖引擎实例，可在引擎初始化前调用（仅需库已加载）。
// kAnyThread（无 g_gameCore 状态，天然线程安全）。
// 返回扁平 IntArray：[sprite, x, y, w, h, flip] × N（sprite 序 = RoadSprite
// 枚举序 = SpriteAtlasDef.ROAD_RECTS 声明序，Kotlin RoadCompositorBridge
// .SPRITE_KEYS 同序映射图集名；flip = flipU 水平镜像标志，2.4 边缘条方向修正——
// 0/1，左/上缘 1、右/下缘与交汇块 0）。
// ============================================================
extern "C" JNIEXPORT jintArray JNICALL
Java_com_xianxia_sect_core_nativebridge_GameCoreBridge_nativeRoadCompose(
    JNIEnv* env, jobject /*thiz*/, jint mask, jint tileSize) {
    gamecore::map::RoadDrawOp ops[gamecore::map::kMaxRoadDrawOpsPerTile];
    const int n = gamecore::map::emitRoadDrawOps(mask, tileSize, ops);
    jintArray arr = env->NewIntArray(n * 6);
    if (arr == nullptr) return nullptr;
    jint flat[gamecore::map::kMaxRoadDrawOpsPerTile * 6];
    for (int i = 0; i < n; i++) {
        const int base = i * 6;
        flat[base]     = static_cast<jint>(ops[i].sprite);
        flat[base + 1] = ops[i].x;
        flat[base + 2] = ops[i].y;
        flat[base + 3] = ops[i].w;
        flat[base + 4] = ops[i].h;
        flat[base + 5] = ops[i].flipU ? 1 : 0;
    }
    env->SetIntArrayRegion(arr, 0, n * 6, flat);
    return arr;
}

// ============================================================
// 浮空岛崖壁布局合成通道（地图边缘系统）
// 无状态纯函数——不依赖引擎实例（同 nativeRoadCompose：kAnyThread）。
// 布局合成单一权威 = gamecore/map/island_cliff.h；本函数仅做 JNI 装配：
// 池平铺表/纹理尺寸表 → IslandCliffConfig → 输出
// [texIdx, x, y, w, h, u0, v0, u1, v1, flags] × N（步长 kIslandCliffStride）。
// 崖壁走独立纹理，故输出携带 texIdx + 逐条目 UV + 镜像位，
// 不依赖图集精灵索引与全局 UV 表。
// ============================================================
extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_xianxia_sect_core_nativebridge_GameCoreBridge_nativeIslandCliffCompose(
    JNIEnv* env, jobject /*thiz*/,
    jint cols, jint rows, jint tileSize, jint seed,
    jfloatArray textureSizes, jintArray poolBase, jintArray poolCount,
    jintArray poolFlat, jfloat topInset, jfloat bottomStartRatio, jfloat bottomEndRatio,
    jint textureMask) {

    if (textureSizes == nullptr || poolBase == nullptr || poolCount == nullptr ||
        poolFlat == nullptr) {
        return nullptr;
    }
    const jsize sizesLen = env->GetArrayLength(textureSizes);
    const jsize baseLen = env->GetArrayLength(poolBase);
    const jsize countLen = env->GetArrayLength(poolCount);
    const jsize flatLen = env->GetArrayLength(poolFlat);
    if (sizesLen < 2 || baseLen < gamecore::map::kIslandCliffPoolCount ||
        countLen != baseLen) {
        return nullptr;
    }

    const jint textureCount = sizesLen / 2;

    // 配置装配（指针生命周期 = 本函数作用域；sizes 交错 [w,h] 拆分为独立 W/H 表）
    const std::vector<float> sizesF = [&]() {
        std::vector<float> v(static_cast<size_t>(sizesLen));
        env->GetFloatArrayRegion(textureSizes, 0, sizesLen, v.data());
        return v;
    }();
    const std::vector<float> sizesW = [&]() {
        std::vector<float> v(static_cast<size_t>(textureCount));
        for (jsize i = 0; i < textureCount; i++) {
            v[static_cast<size_t>(i)] = sizesF[static_cast<size_t>(i) * 2];
        }
        return v;
    }();
    const std::vector<float> sizesH = [&]() {
        std::vector<float> v(static_cast<size_t>(textureCount));
        for (jsize i = 0; i < textureCount; i++) {
            v[static_cast<size_t>(i)] = sizesF[static_cast<size_t>(i) * 2 + 1];
        }
        return v;
    }();
    const std::vector<jint> baseV = [&]() {
        std::vector<jint> v(static_cast<size_t>(baseLen));
        env->GetIntArrayRegion(poolBase, 0, baseLen, v.data());
        return v;
    }();
    const std::vector<jint> countV = [&]() {
        std::vector<jint> v(static_cast<size_t>(countLen));
        env->GetIntArrayRegion(poolCount, 0, countLen, v.data());
        return v;
    }();
    const std::vector<jint> flatV = [&]() {
        std::vector<jint> v(static_cast<size_t>(flatLen));
        env->GetIntArrayRegion(poolFlat, 0, flatLen, v.data());
        return v;
    }();

    // 防御：池平铺表容量须容纳全部池条目 + 下标须在纹理范围内（数据篡改兜底）
    {
        jsize required = 0;
        for (jsize i = 0; i < countLen; i++) required += countV[static_cast<size_t>(i)];
        if (flatLen < required) return nullptr;
        for (jsize i = 0; i < flatLen; i++) {
            const jint t = flatV[static_cast<size_t>(i)];
            if (t < 0 || t >= textureCount) return nullptr;
        }
    }

    gamecore::map::IslandCliffConfig cfg;
    cfg.cols = static_cast<int32_t>(cols);
    cfg.rows = static_cast<int32_t>(rows);
    cfg.tileSize = static_cast<int32_t>(tileSize);
    cfg.seed = static_cast<int32_t>(seed);
    cfg.textureCount = static_cast<int32_t>(textureCount);
    cfg.textureW = sizesW.data();
    cfg.textureH = sizesH.data();
    static_assert(sizeof(jfloat) == sizeof(float), "JNI float 宽度假设");
    // poolBase/count/flat 指针对齐（jint = int32_t）
    for (int32_t i = 0; i < gamecore::map::kIslandCliffPoolCount; i++) {
        cfg.poolBase[i] = static_cast<int32_t>(baseV[static_cast<size_t>(i)]);
        cfg.poolCountArr[i] = static_cast<int32_t>(countV[static_cast<size_t>(i)]);
    }
    cfg.poolFlat = reinterpret_cast<const int32_t*>(flatV.data());
    cfg.topInset = static_cast<float>(topInset);
    cfg.bottomStartRatio = static_cast<float>(bottomStartRatio);
    cfg.bottomEndRatio = static_cast<float>(bottomEndRatio);
    cfg.textureMask = static_cast<uint32_t>(textureMask);

    const int32_t maxPieces = gamecore::map::islandCliffMaxPieces(cfg) + 8;
    std::vector<gamecore::map::IslandCliffPiece> pieces(
        static_cast<size_t>(maxPieces < 0 ? 0 : maxPieces));
    const int32_t n = gamecore::map::computeIslandCliffLayout(
        cfg, pieces.data(), static_cast<int32_t>(pieces.size()));

    constexpr int32_t kStride = gamecore::map::kIslandCliffStride;
    jfloatArray out = env->NewFloatArray(static_cast<jsize>(n) * kStride);
    if (out == nullptr) return nullptr;
    std::vector<float> flatOut(static_cast<size_t>(n) * kStride);
    for (int32_t i = 0; i < n; i++) {
        const int32_t base = i * kStride;
        const auto& p = pieces[static_cast<size_t>(i)];
        flatOut[static_cast<size_t>(base)] = static_cast<float>(p.texIdx);
        flatOut[static_cast<size_t>(base + 1)] = p.x;
        flatOut[static_cast<size_t>(base + 2)] = p.y;
        flatOut[static_cast<size_t>(base + 3)] = p.w;
        flatOut[static_cast<size_t>(base + 4)] = p.h;
        flatOut[static_cast<size_t>(base + 5)] = p.u0;
        flatOut[static_cast<size_t>(base + 6)] = p.v0;
        flatOut[static_cast<size_t>(base + 7)] = p.u1;
        flatOut[static_cast<size_t>(base + 8)] = p.v1;
        flatOut[static_cast<size_t>(base + 9)] = static_cast<float>(p.flags);
    }
    env->SetFloatArrayRegion(out, 0, static_cast<jsize>(n) * kStride, flatOut.data());
    return out;
}

// ============================================================
// 宗门地图地形生成通道
// 无状态纯函数——不依赖引擎实例（同 nativeRoadCompose：kAnyThread）。
// 地形生成单一权威 = gamecore/map/terrain.h（Kotlin SectMapTileGenerator
// 的位级等价移植）；本函数仅做 JNI 装配：门楼盒传参 → 展平行主序瓦片数组。
// 门楼常量由 Kotlin 侧按 GameConfig.SectMap 传入（单一数据源不落 C++）。
// 对拍守护：DiffSectTerrainTest（桌面 JNI 同签名导出，全数组逐位一致）。
// ============================================================
extern "C" JNIEXPORT jintArray JNICALL
Java_com_xianxia_sect_core_nativebridge_GameCoreBridge_nativeGenerateSectTerrain(
    JNIEnv* env, jobject /*thiz*/,
    jint seed, jint width, jint height, jfloat density, jint borderTreeRing,
    jint gateX, jint gateY, jint gateWidth, jint gateHeight, jint gateSpriteY) {

    if (width <= 0 || height <= 0) return nullptr;

    gamecore::map::terrain::GateBox gate;
    gate.x = static_cast<int32_t>(gateX);
    gate.y = static_cast<int32_t>(gateY);
    gate.width = static_cast<int32_t>(gateWidth);
    gate.height = static_cast<int32_t>(gateHeight);
    gate.spriteY = static_cast<int32_t>(gateSpriteY);

    const std::vector<int32_t> tiles = gamecore::map::terrain::generateTileData(
        static_cast<int32_t>(width), static_cast<int32_t>(height),
        static_cast<float>(density), static_cast<int32_t>(seed),
        static_cast<int32_t>(borderTreeRing), gate);

    const jsize n = static_cast<jsize>(tiles.size());
    jintArray out = env->NewIntArray(n);
    if (out == nullptr) return nullptr;
    env->SetIntArrayRegion(out, 0, n,
                           reinterpret_cast<const jint*>(tiles.data()));
    return out;
}

// ============================================================
// FP 确定性对拍探针（R0.2 真机腿）
// ============================================================

/**
 * 运行 FP 确定性对拍探针（gamecore/determinism_probe.h 单一实现），
 * 返回 FNV-1a 64 位摘要的十六进制字符串——Android instrumentation
 * 测试断言其与桌面腿录制的 kGoldenDigest 一致（跨架构浮点位锁定）。
 * 自包含：新建 GameCore 实例，不触碰 g_gameCore 生产状态。kAnyThread。
 */
extern "C" JNIEXPORT jstring JNICALL
Java_com_xianxia_sect_core_nativebridge_GameCoreBridge_nativeFpDeterminismProbe(
    JNIEnv* env, jobject /*thiz*/) {
    const auto probeResult = gamecore::probe::runDeterminismProbe();
    const std::string hex = gamecore::probe::digestHex(probeResult.digest);
    return env->NewStringUTF(hex.c_str());
}
