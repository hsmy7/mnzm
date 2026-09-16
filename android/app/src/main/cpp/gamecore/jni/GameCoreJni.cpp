// ============================================================
// GameCoreJni - Desktop JNI bridge for diff testing (no Android deps)
//
// Purpose: loaded by JVM (JUnit) to cross-language diff-test against the
// real Kotlin engine. Unlike the Android GameCoreBridge.cpp, this does not
// depend on android/log.h and only exposes the minimal diff surface
// (batch 0: DeterministicRng/RngManager; later batches add
// execute/advance/snapshot channels).
//
// Kotlin side (test): core/engine/src/test/.../DiffRngBridge.kt
//
// Build: see scripts/build-desktop-jni.ps1 / CI
//   clang++ -shared -fPIC -std=c++20 -I../.. GameCoreJni.cpp ... -o libgamecorejni.so
// ============================================================
#include <jni.h>

#include <cstring>
#include <string>
#include <vector>

#include <nlohmann/json.hpp>

#include "gamecore/game_core.h"
#include "gamecore/state/json_codec.h"
#include "gamecore/map/road_system.h"
#include "gamecore/map/road_compositor.h"
#include "gamecore/map/terrain.h"
#include "gamecore/rng/fdlibm.h"
#include "gamecore/rng/pcg_xsh_rr.h"
#include "gamecore/rng/rng_manager.h"
#include "gamecore/system/battle.h"
#include "gamecore/system/battle_ai.h"
#include "gamecore/system/battle_calculator.h"
#include "gamecore/system/battle_execution.h"
#include "gamecore/system/battle_json.h"
#include "gamecore/system/sect_battle.h"
#include "gamecore/system/breakthrough.h"
#include "gamecore/system/cultivation.h"
#include "gamecore/system/disciple.h"
#include "gamecore/system/disciple_factory.h"
#include "gamecore/system/disciple_stats.h"
#include "gamecore/system/economy.h"
#include "gamecore/system/engine_loop.h"
#include "gamecore/system/exploration.h"
#include "gamecore/system/government.h"
#include "gamecore/system/inventory.h"
#include "gamecore/system/lifecycle.h"
#include "gamecore/system/month_settlement.h"
#include "gamecore/system/sect_attack_decision.h"
#include "gamecore/system/name_service.h"
#include "gamecore/system/spirit_field.h"
#include "gamecore/system/watchdog.h"

namespace {

using gamecore::rng::DeterministicRng;
using gamecore::rng::RngManager;
using gamecore::rng::RngPartition;

/// Global RNG instance for diff testing (single-threaded tests only;
/// 1:1 with Kotlin DiffRngBridge)
DeterministicRng* g_rng = nullptr;
RngManager* g_rngManager = nullptr;

/// Global GameCore instance for state snapshot diff testing
gamecore::GameCore* g_core = nullptr;
gamecore::FixedClock g_coreClock;
gamecore::FixedMonotonicClock g_coreMono;  // 引擎循环单调时钟（对拍脚本驱动）
gamecore::ConsoleLogger g_coreLogger;   // 对拍调试期输出异常到 stderr

/// jbyteArray → std::string
std::string jbytesToString(JNIEnv* env, jbyteArray array) {
    if (!array) return {};
    const jsize len = env->GetArrayLength(array);
    if (len <= 0) return {};
    jbyte* bytes = env->GetByteArrayElements(array, nullptr);
    std::string out(reinterpret_cast<const char*>(bytes), static_cast<size_t>(len));
    env->ReleaseByteArrayElements(array, bytes, JNI_ABORT);
    return out;
}

/// jstring → std::string（UTF-8；对拍通道）
std::string jstringToString(JNIEnv* env, jstring s) {
    if (!s) return {};
    const jsize len = env->GetStringUTFLength(s);
    if (len <= 0) return {};
    const char* chars = env->GetStringUTFChars(s, nullptr);
    if (!chars) return {};
    std::string out(chars, static_cast<size_t>(len));
    env->ReleaseStringUTFChars(s, chars);
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

/// JNI jstring → std::string（copy；AI 攻击决策 id 参数用）
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
// DeterministicRng diff channel
// ============================================================

extern "C" JNIEXPORT void JNICALL
Java_com_xianxia_sect_core_nativebridge_DiffRngBridge_nativeFromSeed(
    JNIEnv* /*env*/, jobject /*thiz*/, jlong seed) {
    delete g_rng;
    g_rng = new DeterministicRng(DeterministicRng::fromSeed(static_cast<int64_t>(seed)));
}

extern "C" JNIEXPORT jint JNICALL
Java_com_xianxia_sect_core_nativebridge_DiffRngBridge_nativeNextInt(
    JNIEnv* /*env*/, jobject /*thiz*/) {
    return g_rng ? g_rng->nextInt() : 0;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_xianxia_sect_core_nativebridge_DiffRngBridge_nativeNextIntBound(
    JNIEnv* /*env*/, jobject /*thiz*/, jint bound) {
    return g_rng ? g_rng->nextInt(static_cast<int32_t>(bound)) : 0;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_xianxia_sect_core_nativebridge_DiffRngBridge_nativeNextLongBound(
    JNIEnv* /*env*/, jobject /*thiz*/, jlong bound) {
    return g_rng ? g_rng->nextLong(static_cast<int64_t>(bound)) : 0L;
}

extern "C" JNIEXPORT jdouble JNICALL
Java_com_xianxia_sect_core_nativebridge_DiffRngBridge_nativeNextDouble(
    JNIEnv* /*env*/, jobject /*thiz*/) {
    return g_rng ? g_rng->nextDouble() : 0.0;
}

// 正态分布跨语言精度对拍（StrictMath fdlibm vs 内嵌 fdlibm——
// log/cos 经 fdlibm.h 内嵌保证位级一致，sqrt 沿用 std:: 对拍验证）
extern "C" JNIEXPORT jdouble JNICALL
Java_com_xianxia_sect_core_nativebridge_DiffRngBridge_nativeNextGaussian(
    JNIEnv* /*env*/, jobject /*thiz*/, jdouble mean, jdouble stddev) {
    return g_rng ? g_rng->nextGaussian(mean, stddev) : 0.0;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_xianxia_sect_core_nativebridge_DiffRngBridge_nativeSnapshot(
    JNIEnv* /*env*/, jobject /*thiz*/) {
    return g_rng ? g_rng->snapshot() : 0L;
}

// 中文名继承对拍（Kotlin NameService.inheritName 分区 rng 版 vs
// C++ name_service.h——数据表逐项一致 + RNG 序列逐位一致，名字逐字符对拍）
extern "C" JNIEXPORT jstring JNICALL
Java_com_xianxia_sect_core_nativebridge_DiffRngBridge_nativeNameInherit(
    JNIEnv* env, jobject /*thiz*/, jstring surname, jstring gender,
    jstring existingJson) {
    if (!g_rng) return env->NewStringUTF("");
    const std::string surnameStr = jstringToString(env, surname);
    const std::string genderStr = jstringToString(env, gender);
    std::set<std::string> existing;
    if (existingJson) {
        const std::string json = jstringToString(env, existingJson);
        if (!json.empty()) {
            try {
                const auto arr = nlohmann::json::parse(json);
                if (arr.is_array()) {
                    for (const auto& e : arr) {
                        if (e.is_string()) existing.insert(e.get<std::string>());
                    }
                }
            } catch (const std::exception&) {
                // 损坏输入：空集合（对拍健壮性）
            }
        }
    }
    const auto result =
        gamecore::system::inheritName(surnameStr, genderStr, existing, *g_rng);
    return env->NewStringUTF(result.fullName.c_str());
}

// 弟子创建对拍（Kotlin DiscipleFactory.create vs C++ disciple_factory.h
// createDisciple——同种子同消费序，逐字段位级一致）。输入 seed JSON，返回
// 弟子生成结果 JSON（g_rng 为随机源，与 Kotlin 侧同一底层 PRNG 独立同序列消费）。
extern "C" JNIEXPORT jstring JNICALL
Java_com_xianxia_sect_core_nativebridge_DiffRngBridge_nativeCreateDisciple(
    JNIEnv* env, jobject /*thiz*/, jstring seedJson) {
    if (!g_rng) return env->NewStringUTF("");
    const std::string json = jstringToString(env, seedJson);
    gamecore::system::DiscipleCreationSeed seed;
    try {
        const auto obj = nlohmann::json::parse(json);
        seed.id = obj.value("id", "");
        seed.gender = obj.value("gender", "male");
        seed.fullName = obj.value("fullName", "");
        seed.surname = obj.value("surname", "");
        seed.spiritRootType = obj.value("spiritRootType", "metal");
        seed.age = obj.value("age", 16);
        seed.realm = obj.value("realm", 9);
        seed.realmLayer = obj.value("realmLayer", 1);
    } catch (const std::exception&) {
        return env->NewStringUTF("");
    }
    const auto d = gamecore::system::createDisciple(seed, *g_rng);
    nlohmann::json out;
    out["portraitRes"] = d.portraitRes;
    out["hpVariance"] = d.hpVariance;
    out["mpVariance"] = d.mpVariance;
    out["physicalAttackVariance"] = d.physicalAttackVariance;
    out["magicAttackVariance"] = d.magicAttackVariance;
    out["physicalDefenseVariance"] = d.physicalDefenseVariance;
    out["magicDefenseVariance"] = d.magicDefenseVariance;
    out["speedVariance"] = d.speedVariance;
    out["comprehension"] = d.comprehension;
    out["aptitude"] = d.aptitude;
    out["intelligence"] = d.intelligence;
    out["charm"] = d.charm;
    out["loyalty"] = d.loyalty;
    out["morality"] = d.morality;
    out["artifactRefining"] = d.artifactRefining;
    out["pillRefining"] = d.pillRefining;
    out["spiritPlanting"] = d.spiritPlanting;
    out["mining"] = d.mining;
    out["teaching"] = d.teaching;
    out["baseHp"] = d.baseHp;
    out["baseMp"] = d.baseMp;
    out["basePhysicalAttack"] = d.basePhysicalAttack;
    out["baseMagicAttack"] = d.baseMagicAttack;
    out["basePhysicalDefense"] = d.basePhysicalDefense;
    out["baseMagicDefense"] = d.baseMagicDefense;
    out["baseSpeed"] = d.baseSpeed;
    out["lifespan"] = d.lifespan;
    out["talentIds"] = d.talentIds;
    out["physiqueIds"] = d.physiqueIds;
    out["affixIds"] = d.affixIds;
    return env->NewStringUTF(out.dump().c_str());
}

// ============================================================
// RngManager (partition) diff channel
// ============================================================

extern "C" JNIEXPORT void JNICALL
Java_com_xianxia_sect_core_nativebridge_DiffRngBridge_nativeManagerInit(
    JNIEnv* /*env*/, jobject /*thiz*/, jlong seed) {
    delete g_rngManager;
    g_rngManager = new RngManager();
    g_rngManager->initSystemSeed(static_cast<int64_t>(seed));
    // 同步重置 GameCore 内部 RNG（灵田收获走 core->rng()，需与对拍 seed 对齐）
    if (g_core) {
        g_core->rng().initSystemSeed(static_cast<int64_t>(seed));
    }
}

extern "C" JNIEXPORT jint JNICALL
Java_com_xianxia_sect_core_nativebridge_DiffRngBridge_nativeManagerNextInt(
    JNIEnv* /*env*/, jobject /*thiz*/, jint partitionId, jint bound) {
    if (!g_rngManager) return 0;
    return g_rngManager->getRng(static_cast<RngPartition>(partitionId))
        .nextInt(static_cast<int32_t>(bound));
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_xianxia_sect_core_nativebridge_DiffRngBridge_nativeManagerSnapshot(
    JNIEnv* /*env*/, jobject /*thiz*/, jint partitionId) {
    if (!g_rngManager) return 0L;
    return g_rngManager->getRng(static_cast<RngPartition>(partitionId)).snapshot();
}

// ============================================================
// GameCore state snapshot channel (batch 1: JSON full snapshot)
// ============================================================

extern "C" JNIEXPORT void JNICALL
Java_com_xianxia_sect_core_nativebridge_DiffRngBridge_nativeCoreInit(
    JNIEnv* /*env*/, jobject /*thiz*/) {
    if (!g_core) {
        g_core = new gamecore::GameCore(&g_coreClock, &g_coreLogger);
        g_core->setPlatformProviders(
            {.monotonicClock = &g_coreMono, .telemetry = nullptr,
             .thermal = nullptr, .battery = nullptr});
        gamecore::GameCoreConfig config;
        config.seedInitialized = true;
        config.systemSeed = 42;
        g_core->initialize(config);
    }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_xianxia_sect_core_nativebridge_DiffRngBridge_nativeCoreImportState(
    JNIEnv* env, jobject /*thiz*/, jbyteArray stateJson) {
    if (!g_core) return JNI_FALSE;
    return g_core->importStateJson(jbytesToString(env, stateJson)) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_xianxia_sect_core_nativebridge_DiffRngBridge_nativeCoreImportStateNoRng(
    JNIEnv* env, jobject /*thiz*/, jbyteArray stateJson) {
    if (!g_core) return JNI_FALSE;
    return g_core->importStateJsonNoRng(jbytesToString(env, stateJson)) ? JNI_TRUE : JNI_FALSE;
}

// 手动招募单招（Kotlin DiscipleFacadeImpl.recruitDiscipleFromList 等价下沉
// 对拍用：协议与生产 GameCoreBridge.nativeManualRecruitFromList 一致）
extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_xianxia_sect_core_nativebridge_DiffRngBridge_nativeCoreManualRecruitFromList(
    JNIEnv* env, jobject /*thiz*/, jstring discipleId) {
    if (!g_core) {
        return stringToJbytes(env,
            R"({"ok":false,"newId":"","age":0,"name":"","reason":"UNKNOWN"})");
    }
    return stringToJbytes(
        env, g_core->manualRecruitFromList(jstringToString(env, discipleId)));
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_xianxia_sect_core_nativebridge_DiffRngBridge_nativeCoreExportState(
    JNIEnv* env, jobject /*thiz*/) {
    if (!g_core) return stringToJbytes(env, "{}");
    return stringToJbytes(env, g_core->exportStateJson());
}

// ── 变更集通道（exportDirty 对拍用）────────────

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_xianxia_sect_core_nativebridge_DiffRngBridge_nativeCoreExportDirty(
    JNIEnv* env, jobject /*thiz*/) {
    if (!g_core) return stringToJbytes(env, R"({"version":0,"changed":{},"removed":{}})");
    return stringToJbytes(env, g_core->exportDirtyJson());
}

// ── 时间推进通道（对拍用）────────────────────────────────────────

extern "C" JNIEXPORT jint JNICALL
Java_com_xianxia_sect_core_nativebridge_DiffRngBridge_nativeCoreAdvancePhases(
    JNIEnv* /*env*/, jobject /*thiz*/, jint phaseCount) {
    if (!g_core) return 0;
    return static_cast<jint>(g_core->advancePhases(static_cast<int>(phaseCount)).phasesAdvanced);
}

// ── AUTHORITATIVE tick 标量通道（对拍用）────────

extern "C" JNIEXPORT jint JNICALL
Java_com_xianxia_sect_core_nativebridge_DiffRngBridge_nativeCoreSettlePhase(
    JNIEnv* /*env*/, jobject /*thiz*/) {
    if (!g_core) return 0;
    return static_cast<jint>(g_core->settleOnePhase());
}

// ── 月/年边界结算通道（W4-D/D3 harness 对齐生产：与生产 GameCoreBridge
//    nativeSettleMonth/nativeSettleYear 同协议——结算 + 信封 JSON 原样回传）────

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_xianxia_sect_core_nativebridge_DiffRngBridge_nativeCoreSettleMonth(
    JNIEnv* env, jobject /*thiz*/) {
    if (!g_core) return stringToJbytes(env, "{}");
    return stringToJbytes(env, g_core->settleMonth());
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_xianxia_sect_core_nativebridge_DiffRngBridge_nativeCoreSettleYear(
    JNIEnv* env, jobject /*thiz*/) {
    if (!g_core) return stringToJbytes(env, "{}");
    return stringToJbytes(env, g_core->settleYear());
}

extern "C" JNIEXPORT jint JNICALL
Java_com_xianxia_sect_core_nativebridge_DiffRngBridge_nativeCoreRngNextInt(
    JNIEnv* /*env*/, jobject /*thiz*/, jint partitionId) {
    if (!g_core) return 0;
    return g_core->rngNextInt(static_cast<int>(partitionId));
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_xianxia_sect_core_nativebridge_DiffRngBridge_nativeCoreRngSnapshotPartition(
    JNIEnv* /*env*/, jobject /*thiz*/, jint partitionId) {
    if (!g_core) return 0L;
    return static_cast<jlong>(g_core->rngSnapshotPartition(static_cast<int>(partitionId)));
}

extern "C" JNIEXPORT void JNICALL
Java_com_xianxia_sect_core_nativebridge_DiffRngBridge_nativeCoreRngRestorePartition(
    JNIEnv* /*env*/, jobject /*thiz*/, jint partitionId, jlong state) {
    if (!g_core) return;
    g_core->rngRestorePartition(static_cast<int>(partitionId),
                                static_cast<int64_t>(state));
}

extern "C" JNIEXPORT void JNICALL
Java_com_xianxia_sect_core_nativebridge_DiffRngBridge_nativeCoreRngInitSeed(
    JNIEnv* /*env*/, jobject /*thiz*/, jlong seed) {
    if (!g_core) return;
    g_core->rngInitSystemSeed(static_cast<int64_t>(seed));
}

extern "C" JNIEXPORT void JNICALL
Java_com_xianxia_sect_core_nativebridge_DiffRngBridge_nativeCoreInitMode(
    JNIEnv* /*env*/, jobject /*thiz*/, jboolean authoritativeTickMode) {
    // 按 tick 模式（重）创建引擎：模式一致时复用既有单例；
    // AUTHORITATIVE 对拍需要 core 模式引擎，常规对拍不受影响
    const bool wantMode = (authoritativeTickMode == JNI_TRUE);
    if (g_core && g_core->settlement().coreMode() == wantMode) return;
    if (g_core) {
        g_core->shutdown();
        delete g_core;
        g_core = nullptr;
    }
    g_core = new gamecore::GameCore(&g_coreClock, &g_coreLogger);
    g_core->setPlatformProviders(
        {.monotonicClock = &g_coreMono, .telemetry = nullptr,
         .thermal = nullptr, .battery = nullptr});
    gamecore::GameCoreConfig config;
    config.seedInitialized = true;
    config.systemSeed = 42;
    config.authoritativeTickMode = wantMode;
    g_core->initialize(config);
}

// ── 引擎循环 + 看门狗通道（对拍用）────────────────

extern "C" JNIEXPORT void JNICALL
Java_com_xianxia_sect_core_nativebridge_DiffRngBridge_nativeCoreLoopStart(
    JNIEnv* /*env*/, jobject /*thiz*/) {
    if (g_core) g_core->loop().start();
}

extern "C" JNIEXPORT void JNICALL
Java_com_xianxia_sect_core_nativebridge_DiffRngBridge_nativeCoreLoopReset(
    JNIEnv* /*env*/, jobject /*thiz*/) {
    // 测试隔离：JUnit 用例间重建循环基准（tick 计数/速度/累积/帧状态清零）。
    // nativeCoreInit 幂等复用单例（既有设计），EngineLoop 生命周期
    // 跨用例残留——Kotlin 侧每用例 new GameTimeClock 是干净的，对拍须对齐。
    if (g_core) g_core->loop().resetForTest();
}

extern "C" JNIEXPORT void JNICALL
Java_com_xianxia_sect_core_nativebridge_DiffRngBridge_nativeCoreLoopSetSpeed(
    JNIEnv* /*env*/, jobject /*thiz*/, jint speed) {
    if (g_core) g_core->loop().time().setSpeed(static_cast<int>(speed));
}

extern "C" JNIEXPORT void JNICALL
Java_com_xianxia_sect_core_nativebridge_DiffRngBridge_nativeCoreLoopSetMonoMs(
    JNIEnv* /*env*/, jobject /*thiz*/, jlong nowMs) {
    g_coreMono.setNowMs(static_cast<int64_t>(nowMs));
}

extern "C" JNIEXPORT void JNICALL
Java_com_xianxia_sect_core_nativebridge_DiffRngBridge_nativeCoreLoopConsumeDeadTime(
    JNIEnv* /*env*/, jobject /*thiz*/) {
    if (g_core) g_core->loop().time().consumeDeadTime();
}

extern "C" JNIEXPORT void JNICALL
Java_com_xianxia_sect_core_nativebridge_DiffRngBridge_nativeCoreLoopRefundPhases(
    JNIEnv* /*env*/, jobject /*thiz*/, jint count) {
    if (g_core) g_core->loop().time().refundPhases(static_cast<int>(count));
}

extern "C" JNIEXPORT void JNICALL
Java_com_xianxia_sect_core_nativebridge_DiffRngBridge_nativeCoreLoopNotifyUserActivity(
    JNIEnv* /*env*/, jobject /*thiz*/) {
    if (g_core) g_core->loop().notifyUserActivity();
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_xianxia_sect_core_nativebridge_DiffRngBridge_nativeCoreLoopAccumulatedGameMs(
    JNIEnv* /*env*/, jobject /*thiz*/) {
    return g_core ? g_core->loop().time().accumulatedGameMs() : 0L;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_xianxia_sect_core_nativebridge_DiffRngBridge_nativeCoreLoopTickTotal(
    JNIEnv* /*env*/, jobject /*thiz*/) {
    return g_core ? g_core->loop().tickCount() : 0L;
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_com_xianxia_sect_core_nativebridge_DiffRngBridge_nativeCoreLoopFrame(
    JNIEnv* env, jobject /*thiz*/,
    jboolean pausedOrLoading, jboolean isSaving) {
    constexpr int kLen = 17;
    jlong buf[kLen] = {0};
    if (g_core) {
        const auto plan = g_core->loop().iterate(
            pausedOrLoading == JNI_TRUE, isSaving == JNI_TRUE);
        buf[0] = plan.paused ? 1 : 0;
        buf[1] = plan.tickCount;
        for (int i = 0; i < 5; ++i) buf[2 + i] = plan.tickKind[i];
        for (int i = 0; i < 5; ++i) buf[7 + i] = plan.tickPhases[i];
        int32_t alphaBits = 0;
        static_assert(sizeof(alphaBits) == sizeof(float), "float bits");
        std::memcpy(&alphaBits, &plan.alpha, sizeof(float));
        buf[12] = alphaBits;
        buf[13] = plan.frameDeltaNs;
        buf[14] = plan.idleNs;
        buf[15] = plan.tickTotal;
        buf[16] = plan.accumulatedGameMs;
    }
    jlongArray out = env->NewLongArray(kLen);
    if (out) env->SetLongArrayRegion(out, 0, kLen, buf);
    return out;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_xianxia_sect_core_nativebridge_DiffRngBridge_nativeCoreWatchdogVerdict(
    JNIEnv* /*env*/, jobject /*thiz*/,
    jboolean loopActive, jboolean isPaused, jboolean isSaving, jboolean isLoading,
    jboolean secretRealmPauseLock, jlong secretRealmPauseRenewedAtMs) {
    if (!g_core) return -1;
    gamecore::WatchdogFlags flags;
    flags.loopActive = (loopActive == JNI_TRUE);
    flags.isPaused = (isPaused == JNI_TRUE);
    flags.isSaving = (isSaving == JNI_TRUE);
    flags.isLoading = (isLoading == JNI_TRUE);
    flags.secretRealmPauseLock = (secretRealmPauseLock == JNI_TRUE);
    flags.secretRealmPauseRenewedAtMs = static_cast<int64_t>(secretRealmPauseRenewedAtMs);
    return g_core->watchdogVerdict(flags);
}

// 独立判据通道：不依赖 GameCore 状态，直接对拍 ProgressMonitor 纯函数
//（Kotlin GameTimeProgressMonitor 同序列快照 → 判定逐位一致守护）
namespace {
gamecore::system::ProgressMonitor* g_monitor = nullptr;
}  // namespace

extern "C" JNIEXPORT void JNICALL
Java_com_xianxia_sect_core_nativebridge_DiffRngBridge_nativeCoreMonitorReset(
    JNIEnv* /*env*/, jobject /*thiz*/) {
    delete g_monitor;
    g_monitor = new gamecore::system::ProgressMonitor();
}

extern "C" JNIEXPORT jint JNICALL
Java_com_xianxia_sect_core_nativebridge_DiffRngBridge_nativeCoreMonitorEvaluate(
    JNIEnv* /*env*/, jobject /*thiz*/,
    jlong tickCount, jlong totalPhases, jlong accumulatedGameMs,
    jboolean loopActive, jboolean isPaused, jboolean isSaving, jboolean isLoading,
    jint speed, jboolean secretRealmPauseLock, jlong secretRealmPauseRenewedAtMs,
    jlong loopActiveAtMs, jlong recordedAtMs) {
    if (!g_monitor) g_monitor = new gamecore::system::ProgressMonitor();
    gamecore::system::ProgressSnapshot s;
    s.tickCount = static_cast<int64_t>(tickCount);
    s.totalPhases = static_cast<int64_t>(totalPhases);
    s.accumulatedGameMs = static_cast<int64_t>(accumulatedGameMs);
    s.loopActive = (loopActive == JNI_TRUE);
    s.isPaused = (isPaused == JNI_TRUE);
    s.isSaving = (isSaving == JNI_TRUE);
    s.isLoading = (isLoading == JNI_TRUE);
    s.speed = static_cast<int>(speed);
    s.secretRealmPauseLock = (secretRealmPauseLock == JNI_TRUE);
    s.secretRealmPauseRenewedAtMs = static_cast<int64_t>(secretRealmPauseRenewedAtMs);
    s.loopActiveAtMs = static_cast<int64_t>(loopActiveAtMs);
    s.recordedAtMs = static_cast<int64_t>(recordedAtMs);
    return static_cast<jint>(g_monitor->evaluate(s));
}

// ============================================================
// 弟子属性计算通道（对拍用）
//
// 协议：Kotlin 侧 DiffDiscipleBridge 传入参数 JSON（纯计算，不落状态），
// C++ 侧直接计算并返回结果 JSON。Kotlin 侧用真实 DiscipleStatCalculator
// 计算同参数，逐字段对拍。
//
// 操作 JSON 格式：
//   {"op":"baseStats", "realm":9, "realmLayer":1, "hpVariance":0, ...,
//    "effects":{"maxHp":0.5,...}, "bloodHpBonusPct":0.3, ...}
//   {"op":"cultivationPerPhase", "realm":9, "rootCount":2,
//    "aptitudeBonus":0.2, "resourceBonus":0.5, "socialBonus":0.1,
//    "statusBonus":-0.1, "temporaryBonus":0.3}
//   {"op":"breakthroughChance", "realm":9, "rootCount":1, "realmLayer":1}
//   {"op":"breakthroughChanceZones", "baseZone":0.5, "elderGuidance":0.1,
//    "selfBonus":0.05, "statusPenalty":0.1, "adFlatBonus":0.0}
//   {"op":"lifespanRemainingPercent", "age":40, "lifespan":80}
//   {"op":"lifespanCultivationPenalty", "age":72, "lifespan":80}
//   {"op":"lifespanBreakthroughPenalty", "age":72, "lifespan":80}
//   {"op":"masterDiscipleBonus", "discipleRealm":9, "masterRealm":7}
//   {"op":"parentSpiritRootBonus", "rootCount":1}
//   {"op":"soulPowerBreakthroughBonus", "soulPower":40}
//   {"op":"aptitudeCultivationBonus", "aptitude":90}
//
// 天赋/词条/体质注册表通道（trait_db → 聚合函数，对拍用）：
//   {"op":"talentEffects", "talentIds":["r1_bat_hp",...]}
//       → {"effects":{"maxHp":0.1,...}}（TalentDatabase.calculateTalentEffects 对拍）
//   {"op":"affixEffects", "affixIds":["r1_aff_bat_hp",...]}
//       → {"effects":{...}}（AffixDatabase.calculateAffixEffects 对拍）
//   {"op":"physiqueEffects", "physiqueIds":["r1_phys_cult_speed",...]}
//       → 五分量聚合（PhysiqueDatabase.aggregatePhysiqueEffects 对拍）
//   {"op":"mergedTraitEffects", "talentIds":[...], "affixIds":[...]}
//       → {"effects":{...}}（getMergedEffects 合并语义对拍）
//   {"op":"baseComprehension", "comprehension":50, "talentIds":[...], "affixIds":[...]}
//       → {"value":N}（baseComprehension：合并 flat 截断，含词条分叉修复对拍）
//   {"op":"breakthroughLifespanGain", "newRealm":8, "talentIds":[...], "affixIds":[...]}
//       → {"value":N}（calculateBreakthroughLifespanGain 对拍）
//   {"op":"baseHpMpFromTraits", "realm":9, "realmLayer":1, "hpVariance":0,
//    "mpVariance":0, "talentIds":[...], "affixIds":[...]}
//       → {"maxHp":N,"maxMp":N}（getMaxHpMpColumn 无装备/功法/丹药段对拍）
//   {"op":"cultivationRateFromTraits", "realm":9, "rootCount":1, "aptitude":50,
//    "talentIds":[...], "affixIds":[...], "physiqueIds":[...]}
//       → {"value":D}（calculateCultivationPerPhaseColumn 无外部加成段对拍）
// ============================================================

namespace {

using gamecore::disciple::BaseStatsInput;
using gamecore::disciple::BreakthroughZones;
using gamecore::disciple::CultivationSpeedZones;

/// 效果 map 解析（{"key": value} → std::map）
std::map<std::string, double> effectsFromJson(const nlohmann::json& j) {
    std::map<std::string, double> out;
    if (j.is_object()) {
        for (auto it = j.begin(); it != j.end(); ++it) {
            out[it.key()] = it.value().get<double>();
        }
    }
    return out;
}

/// 字符串数组解析（["id1","id2",...] → std::vector；缺省为空）
std::vector<std::string> stringListFromJson(const nlohmann::json& op,
                                            const char* key) {
    std::vector<std::string> out;
    if (op.contains(key) && op.at(key).is_array()) {
        for (const auto& id : op.at(key)) out.push_back(id.get<std::string>());
    }
    return out;
}

/// 天赋/词条/体质注册表通道：id 列表 → 效果聚合对拍
nlohmann::json execTraitEffectsOp(const nlohmann::json& op,
                                  const std::string& opName) {
    namespace stats = gamecore::stats;
    nlohmann::json result;
    if (opName == "talentEffects") {
        result["effects"] = stats::talentEffectsFor(
            stringListFromJson(op, "talentIds"));
    } else if (opName == "affixEffects") {
        result["effects"] = stats::affixEffectsFor(
            stringListFromJson(op, "affixIds"));
    } else if (opName == "physiqueEffects") {
        const auto p = stats::physiqueEffectsFor(
            stringListFromJson(op, "physiqueIds"));
        result = {
            {"cultivationSpeedBonus", p.cultivationSpeedBonus},
            {"damageAmplification", p.damageAmplification},
            {"damageReduction", p.damageReduction},
            {"critDamageBonus", p.critDamageBonus},
            {"defenseBonus", p.defenseBonus},
        };
    } else if (opName == "mergedTraitEffects") {
        result["effects"] = stats::mergeEffects(
            stats::talentEffectsFor(stringListFromJson(op, "talentIds")),
            stats::affixEffectsFor(stringListFromJson(op, "affixIds")));
    } else if (opName == "baseComprehension") {
        gamecore::state::Disciple d;
        d.comprehension = op.value("comprehension", 0);
        d.talentIds = stringListFromJson(op, "talentIds");
        d.affixIds = stringListFromJson(op, "affixIds");
        result["value"] = stats::baseComprehension(d);
    } else if (opName == "breakthroughLifespanGain") {
        result["value"] = stats::calculateBreakthroughLifespanGain(
            op.value("newRealm", 8),
            stringListFromJson(op, "talentIds"),
            stringListFromJson(op, "affixIds"));
    } else if (opName == "baseHpMpFromTraits") {
        const auto effects = stats::mergeEffects(
            stats::talentEffectsFor(stringListFromJson(op, "talentIds")),
            stats::affixEffectsFor(stringListFromJson(op, "affixIds")));
        int32_t maxHp = 0, maxMp = 0;
        stats::computeBaseHpMp(
            op.value("realm", 9), op.value("realmLayer", 1),
            op.value("hpVariance", 0), op.value("mpVariance", 0),
            effects, nullptr, maxHp, maxMp);
        result = {{"maxHp", maxHp}, {"maxMp", maxMp}};
    } else if (opName == "cultivationRateFromTraits") {
        // 无外部加成段（建筑/社交/政策/丹药恒零/默认）的纯特质修炼速率，
        // 对拍 Kotlin calculateCultivationPerPhaseColumn 默认参数路径
        gamecore::state::GameData gd;          // 政策全关 → 状态乘区政策分量 0
        gamecore::state::Disciple d;
        d.realm = op.value("realm", 9);
        const int32_t rootCount = op.value("rootCount", 1);
        for (int32_t i = 0; i < rootCount; ++i) {
            if (i > 0) d.spiritRootType += ",";
            d.spiritRootType += "metal";
        }
        d.age = op.value("age", 16);
        d.lifespan = op.value("lifespan", 80);
        d.aptitude = op.value("aptitude", 50);
        d.talentIds = stringListFromJson(op, "talentIds");
        d.physiqueIds = stringListFromJson(op, "physiqueIds");
        d.affixIds = stringListFromJson(op, "affixIds");
        result["value"] = stats::calculateCultivationPerPhaseColumn(
            d, gd, {}, {}, stats::CultivationRateInput{});
    }
    return result;
}

/// 执行弟子属性计算操作（返回结果 JSON 片段）
nlohmann::json execDiscipleOp(const nlohmann::json& op) {
    const std::string opName = op.at("op").get<std::string>();
    // 注册表通道先行分派（未命中返回空 → 落入既有公式通道）
    nlohmann::json traitResult = execTraitEffectsOp(op, opName);
    if (!traitResult.empty()) return traitResult;
    nlohmann::json result;
    if (opName == "baseStats") {
        BaseStatsInput in;
        in.realm = op.value("realm", 9);
        in.realmLayer = op.value("realmLayer", 1);
        in.hpVariance = op.value("hpVariance", 0);
        in.mpVariance = op.value("mpVariance", 0);
        in.physicalAttackVariance = op.value("physicalAttackVariance", 0);
        in.magicAttackVariance = op.value("magicAttackVariance", 0);
        in.physicalDefenseVariance = op.value("physicalDefenseVariance", 0);
        in.magicDefenseVariance = op.value("magicDefenseVariance", 0);
        in.speedVariance = op.value("speedVariance", 0);
        in.intelligence = op.value("intelligence", 0);
        in.charm = op.value("charm", 0);
        in.loyalty = op.value("loyalty", 0);
        in.comprehension = op.value("comprehension", 0);
        in.aptitude = op.value("aptitude", 50);
        in.teaching = op.value("teaching", 0);
        in.morality = op.value("morality", 0);
        in.mining = op.value("mining", 0);
        in.spiritPlanting = op.value("spiritPlanting", 0);
        in.artifactRefining = op.value("artifactRefining", 0);
        in.pillRefining = op.value("pillRefining", 0);
        if (op.contains("effects")) in.talentEffects = effectsFromJson(op.at("effects"));
        in.bloodHpBonusPct = op.value("bloodHpBonusPct", 0.0);
        in.bloodPhysicalAttackBonusPct = op.value("bloodPhysicalAttackBonusPct", 0.0);
        in.bloodMagicAttackBonusPct = op.value("bloodMagicAttackBonusPct", 0.0);
        in.bloodPhysicalDefenseBonusPct = op.value("bloodPhysicalDefenseBonusPct", 0.0);
        in.bloodMagicDefenseBonusPct = op.value("bloodMagicDefenseBonusPct", 0.0);
        in.bloodSpeedBonusPct = op.value("bloodSpeedBonusPct", 0.0);
        const auto s = gamecore::disciple::computeBaseStats(in);
        result = {
            {"maxHp", s.maxHp}, {"maxMp", s.maxMp},
            {"physicalAttack", s.physicalAttack}, {"magicAttack", s.magicAttack},
            {"physicalDefense", s.physicalDefense}, {"magicDefense", s.magicDefense},
            {"speed", s.speed}, {"critRate", s.critRate},
            {"intelligence", s.intelligence}, {"charm", s.charm},
            {"loyalty", s.loyalty}, {"comprehension", s.comprehension},
            {"aptitude", s.aptitude}, {"teaching", s.teaching},
            {"morality", s.morality}, {"mining", s.mining},
            {"spiritPlanting", s.spiritPlanting}, {"artifactRefining", s.artifactRefining},
            {"pillRefining", s.pillRefining},
        };
    } else if (opName == "cultivationPerPhase") {
        CultivationSpeedZones zones;
        zones.aptitudeBonus = op.value("aptitudeBonus", 0.0);
        zones.resourceBonus = op.value("resourceBonus", 0.0);
        zones.socialBonus = op.value("socialBonus", 0.0);
        zones.statusBonus = op.value("statusBonus", 0.0);
        zones.temporaryBonus = op.value("temporaryBonus", 0.0);
        const double v = gamecore::disciple::calculateCultivationPerPhase(
            op.value("realm", 9), op.value("rootCount", 1), zones);
        result["value"] = v;
    } else if (opName == "breakthroughChance") {
        const double v = gamecore::disciple::getBreakthroughChance(
            op.value("realm", 9), op.value("rootCount", 1),
            op.value("realmLayer", 1));
        result["value"] = v;
    } else if (opName == "breakthroughChanceZones") {
        BreakthroughZones zones;
        zones.baseZone = op.value("baseZone", 0.0);
        zones.elderGuidance = op.value("elderGuidance", 0.0);
        zones.selfBonus = op.value("selfBonus", 0.0);
        zones.statusPenalty = op.value("statusPenalty", 0.0);
        zones.adFlatBonus = op.value("adFlatBonus", 0.0);
        result["value"] = gamecore::disciple::calculateBreakthroughChance(zones);
    } else if (opName == "lifespanRemainingPercent") {
        result["value"] = gamecore::disciple::calculateLifespanRemainingPercent(
            op.value("age", 0), op.value("lifespan", 80));
    } else if (opName == "lifespanCultivationPenalty") {
        result["value"] = gamecore::disciple::calculateLifespanCultivationPenalty(
            op.value("age", 0), op.value("lifespan", 80));
    } else if (opName == "lifespanBreakthroughPenalty") {
        result["value"] = gamecore::disciple::calculateLifespanBreakthroughPenalty(
            op.value("age", 0), op.value("lifespan", 80));
    } else if (opName == "masterDiscipleBonus") {
        result["gap"] = gamecore::disciple::getMasterDiscipleRealmGap(
            op.value("discipleRealm", 9), op.value("masterRealm", 9));
        result["cultivationBonus"] = gamecore::disciple::getMasterDiscipleCultivationBonus(
            op.value("discipleRealm", 9), op.value("masterRealm", 9));
        result["breakthroughBonus"] = gamecore::disciple::getMasterDiscipleBreakthroughBonus(
            op.value("discipleRealm", 9), op.value("masterRealm", 9));
    } else if (opName == "parentSpiritRootBonus") {
        result["value"] = gamecore::disciple::getParentSpiritRootBonus(
            op.value("rootCount", 3));
    } else if (opName == "soulPowerBreakthroughBonus") {
        result["value"] = gamecore::disciple::soulPowerBreakthroughBonus(
            op.value("soulPower", 0));
    } else if (opName == "aptitudeCultivationBonus") {
        result["value"] = gamecore::disciple::aptitudeCultivationBonus(
            op.value("aptitude", 80));
    } else {
        result["error"] = "unknown op: " + opName;
    }
    return result;
}

}  // namespace

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_xianxia_sect_core_nativebridge_DiffRngBridge_nativeCoreDiscipleOp(
    JNIEnv* env, jobject /*thiz*/, jbyteArray opJson) {
    try {
        const std::string input = jbytesToString(env, opJson);
        const auto op = nlohmann::json::parse(input);
        return stringToJbytes(env, execDiscipleOp(op).dump());
    } catch (const std::exception& e) {
        nlohmann::json err = {{"error", e.what()}};
        return stringToJbytes(env, err.dump());
    }
}

// ============================================================
// 修炼推进计算通道（对拍用）
//
// 操作 JSON 格式（纯计算，不落状态）：
//   {"op":"maxCultivation", "realm":9, "realmLayer":1, "cultivation":0.0}
//   {"op":"accumulate", "realm":9, "realmLayer":1, "cultivation":90.0, "rate":19.0, "alive":true}
//   {"op":"projection", "cultivation":500.0, "checkpoint":500.0, "checkpointMonth":100,
//    "currentMonth":102, "rate":10.0, "hasCheckpoint":true}
//   {"op":"absoluteMonth", "year":2, "month":1}
// ============================================================

namespace {

nlohmann::json execCultivationOp(const nlohmann::json& op) {
    const std::string opName = op.at("op").get<std::string>();
    nlohmann::json result;
    if (opName == "maxCultivation") {
        result["value"] = gamecore::system::computeMaxCultivation(
            op.value("realm", 9), op.value("realmLayer", 1),
            op.value("cultivation", 0.0));
    } else if (opName == "accumulate") {
        gamecore::state::Disciple d;
        d.realm = op.value("realm", 9);
        d.realmLayer = op.value("realmLayer", 1);
        d.cultivation = op.value("cultivation", 0.0);
        d.isAlive = op.value("alive", true);
        const double updated = gamecore::system::accumulateCultivationPerPhase(
            d, op.value("rate", 0.0));
        result["value"] = updated;
    } else if (opName == "projection") {
        gamecore::state::Disciple d;
        d.cultivation = op.value("cultivation", 0.0);
        d.cultivationCheckpoint = op.value("checkpoint", 0.0);
        d.cultivationCheckpointGameMonth = op.value("hasCheckpoint", false)
            ? op.value("checkpointMonth", 0) : 0;
        result["value"] = gamecore::system::getEffectiveCultivation(
            d, op.value("currentMonth", 0), op.value("rate", 0.0));
    } else if (opName == "absoluteMonth") {
        result["value"] = gamecore::system::toAbsoluteMonth(
            op.value("year", 1), op.value("month", 1));
    } else if (opName == "estimateMonths") {
        result["value"] = gamecore::system::estimateMonthsToNextBreakthrough(
            op.value("remaining", 0.0), op.value("rate", 0.0));
    } else if (opName == "breakthroughSuccess") {
        gamecore::state::Disciple d;
        d.realm = op.value("realm", 9);
        d.realmLayer = op.value("realmLayer", 1);
        d.cultivation = op.value("cultivation", 0.0);
        d.lifespan = op.value("lifespan", 80);
        const auto out = gamecore::system::applyBreakthroughSuccess(
            d, op.value("lifespanGain", 0));
        result = {
            {"realm", out.realm}, {"realmLayer", out.realmLayer},
            {"cultivation", out.cultivation}, {"lifespan", out.lifespan},
        };
    } else if (opName == "breakthroughFailure") {
        gamecore::state::Disciple d;
        d.realm = op.value("realm", 9);
        d.realmLayer = op.value("realmLayer", 1);
        d.cultivation = op.value("cultivation", 0.0);
        d.lifespan = op.value("lifespan", 80);
        const auto out = gamecore::system::applyBreakthroughFailure(d);
        result = {
            {"realm", out.realm}, {"realmLayer", out.realmLayer},
            {"cultivation", out.cultivation}, {"lifespan", out.lifespan},
        };
    } else if (opName == "computeMaxAge") {
        result["value"] = gamecore::system::computeMaxAge(
            op.value("lifespan", 0), op.value("realmMaxAge", 0),
            op.value("lifespanBonus", 0.0));
    } else if (opName == "ageDisciple") {
        const auto out = gamecore::system::ageDisciple(
            op.value("age", 0), op.value("realmLayer", 1),
            op.value("maxAge", 80));
        result = {
            {"age", out.age}, {"realmLayer", out.realmLayer}, {"dead", out.dead},
        };
    } else if (opName == "ageAlive") {
        int32_t layer = op.value("realmLayer", 1);
        const int32_t age = gamecore::system::ageAliveDisciple(
            op.value("age", 0), layer, layer);
        result = {{"age", age}, {"realmLayer", layer}};
    } else {
        result["error"] = "unknown op: " + opName;
    }
    return result;
}

}  // namespace

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_xianxia_sect_core_nativebridge_DiffRngBridge_nativeCoreCultivationOp(
    JNIEnv* env, jobject /*thiz*/, jbyteArray opJson) {
    try {
        const std::string input = jbytesToString(env, opJson);
        const auto op = nlohmann::json::parse(input);
        return stringToJbytes(env, execCultivationOp(op).dump());
    } catch (const std::exception& e) {
        nlohmann::json err = {{"error", e.what()}};
        return stringToJbytes(env, err.dump());
    }
}

// ============================================================
// 内政计算通道（对拍用）
//
// 操作 JSON 格式（纯计算，可操作状态）：
//   {"op":"zoneCalculate", "base":100.0, "zones":[0.2,0.5,-0.1]}
//   {"op":"calculateProbability", "baseProb":0.5, "positiveSum":0.1, "penaltySum":0.2}
//   {"op":"policyMonthlyDeltas", "benevolentGovernance":true, ...}
//   {"op":"spiritMineMonthly", "minerCount":3, "basePerMiner":170.0, "zones":{...}}
// ============================================================

namespace {

nlohmann::json execGovernmentOp(const nlohmann::json& op) {
    const std::string opName = op.at("op").get<std::string>();
    nlohmann::json result;
    if (opName == "zoneCalculate") {
        std::vector<double> zones;
        if (op.contains("zones")) {
            for (const auto& z : op.at("zones")) zones.push_back(z.get<double>());
        }
        result["value"] = gamecore::system::zoneCalculate(
            op.value("base", 0.0), zones);
    } else if (opName == "calculateProbability") {
        result["value"] = gamecore::system::calculateProbability(
            op.value("baseProb", 0.0), op.value("positiveSum", 0.0),
            op.value("penaltySum", 0.0));
    } else if (opName == "calculateReducedDuration") {
        std::vector<double> reductions;
        if (op.contains("reductions")) {
            for (const auto& r : op.at("reductions")) reductions.push_back(r.get<double>());
        }
        result["value"] = gamecore::system::calculateReducedDuration(
            op.value("baseDuration", 0), reductions);
    } else if (opName == "calculateAcceleratedTime") {
        std::vector<double> bonuses;
        if (op.contains("bonuses")) {
            for (const auto& b : op.at("bonuses")) bonuses.push_back(b.get<double>());
        }
        result["value"] = gamecore::system::calculateAcceleratedTime(
            op.value("baseTime", 0), bonuses);
    } else if (opName == "policyMonthlyDeltas") {
        gamecore::state::SectPolicies p;
        p.benevolentGovernance = op.value("benevolentGovernance", false);
        p.relaxedMgmt = op.value("relaxedMgmt", false);
        p.strictTraining = op.value("strictTraining", false);
        p.enhancedSecurity = op.value("enhancedSecurity", false);
        p.curfew = op.value("curfew", false);
        p.moralEducation = op.value("moralEducation", false);
        const auto d = gamecore::system::policyMonthlyDeltas(p);
        result = {{"loyaltyDelta", d.first}, {"moralityDelta", d.second}};
    } else if (opName == "spiritMineMonthly") {
        gamecore::system::SpiritMineZones zones;
        zones.minerCount = op.value("minerCount", 0);
        zones.avgMiningSkillBonus = op.value("avgMiningSkillBonus", 0.0);
        zones.deaconMoralityBonus = op.value("deaconMoralityBonus", 0.0);
        zones.policyBoost = op.value("policyBoost", 0.0);
        result["value"] = gamecore::system::calculateSpiritMineMonthly(
            zones, op.value("basePerMiner", 170.0));
    } else {
        result["error"] = "unknown op: " + opName;
    }
    return result;
}

}  // namespace

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_xianxia_sect_core_nativebridge_DiffRngBridge_nativeCoreGovernmentOp(
    JNIEnv* env, jobject /*thiz*/, jbyteArray opJson) {
    try {
        const std::string input = jbytesToString(env, opJson);
        const auto op = nlohmann::json::parse(input);
        return stringToJbytes(env, execGovernmentOp(op).dump());
    } catch (const std::exception& e) {
        nlohmann::json err = {{"error", e.what()}};
        return stringToJbytes(env, err.dump());
    }
}

// ============================================================
// 探索计算通道（对拍用）
//
// 操作 JSON 格式（纯计算）：
//   {"op":"checkExpired", "year":3, "month":3, "level":{...}}
//   {"op":"shouldRefresh", "lastRefreshMonth":13, "year":1, "month":4}
// ============================================================

namespace {

nlohmann::json execExplorationOp(const nlohmann::json& op) {
    const std::string opName = op.at("op").get<std::string>();
    nlohmann::json result;
    if (opName == "checkExpired") {
        const auto& l = op.at("level");
        gamecore::state::WorldLevel level;
        level.id = l.value("id", "");
        level.type = l.value("type", "BEAST");
        level.defeated = l.value("defeated", false);
        level.expiryYear = l.value("expiryYear", 0);
        level.expiryMonth = l.value("expiryMonth", 0);
        result["value"] = gamecore::system::checkLevelExpired(
            level, op.value("year", 1), op.value("month", 1));
    } else if (opName == "shouldRefresh") {
        result["value"] = gamecore::system::shouldRefreshLevels(
            op.value("lastRefreshMonth", 0), op.value("year", 1),
            op.value("month", 1));
    } else {
        result["error"] = "unknown op: " + opName;
    }
    return result;
}

}  // namespace

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_xianxia_sect_core_nativebridge_DiffRngBridge_nativeCoreExplorationOp(
    JNIEnv* env, jobject /*thiz*/, jbyteArray opJson) {
    try {
        const std::string input = jbytesToString(env, opJson);
        const auto op = nlohmann::json::parse(input);
        return stringToJbytes(env, execExplorationOp(op).dump());
    } catch (const std::exception& e) {
        nlohmann::json err = {{"error", e.what()}};
        return stringToJbytes(env, err.dump());
    }
}

// ============================================================
// AI 兽袭目标预计算直调通道（对拍用）
//
// 直接作用于 g_core 当前状态（导入/导出经 nativeCoreImportState/
// nativeCoreExportState 通道），与 Kotlin
// AISectBeastAttackProcessor.precomputeTargets 逐位对拍——不经过完整月变
// 管线（规避步骤 4e moveBeasts 的 EXPLORATION 干扰，抽取序仅含本函数）。
// 生产路径经 runMonthSettlement 步骤 3 接线（month_settlement.h）。
// ============================================================

extern "C" JNIEXPORT void JNICALL
Java_com_xianxia_sect_core_nativebridge_DiffRngBridge_nativeCorePrecomputeTargets(
    JNIEnv* /*env*/, jobject /*thiz*/) {
    if (!g_core) return;
    gamecore::system::detail::precomputeTargets(g_core->state(), g_core->rng());
}

// AI 攻击决策直调（对拍用，作用于 g_core 当前状态——导入经
// nativeCoreImportState，与 Kotlin AISectAttackManager 决策逐位对拍 BATTLE 分区）
extern "C" JNIEXPORT jboolean JNICALL
Java_com_xianxia_sect_core_nativebridge_DiffRngBridge_nativeCoreCheckAttackConditions(
    JNIEnv* env, jobject /*thiz*/, jstring attackerId, jstring defenderId,
    jstring playerGarrisonJson) {
    if (!g_core) return JNI_FALSE;
    const std::string ai = jstringToStd(env, attackerId);
    const std::string di = jstringToStd(env, defenderId);
    auto& state = g_core->state();
    const auto& worldSects = state.gameData.worldMapSects;
    const gamecore::state::WorldSect* attacker = nullptr;
    const gamecore::state::WorldSect* defender = nullptr;
    for (const auto& s : worldSects) {
        if (s.id == ai) attacker = &s;
        else if (s.id == di) defender = &s;
    }
    if (attacker == nullptr || defender == nullptr) return JNI_FALSE;
    std::map<std::string, std::vector<gamecore::state::Disciple>> playerGarrison;
    if (playerGarrisonJson != nullptr) {
        const auto j = nlohmann::json::parse(jstringToStd(env, playerGarrisonJson));
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
    return gamecore::system::detail::checkAttackConditions(
        state, *attacker, *defender, playerGarrison, g_core->rng())
        ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_xianxia_sect_core_nativebridge_DiffRngBridge_nativeCoreDecidePlayerAttack(
    JNIEnv* env, jobject /*thiz*/) {
    if (!g_core) return env->NewStringUTF(R"({"type":"SKIP"})");
    const auto decision = gamecore::system::detail::decidePlayerAttack(
        g_core->state(), g_core->rng());
    nlohmann::json j;
    j["type"] = decision.type == gamecore::system::detail::PlayerAttackDecisionType::kGenerateWarning
        ? "GENERATE_WARNING" : "SKIP";
    if (decision.type == gamecore::system::detail::PlayerAttackDecisionType::kGenerateWarning) {
        j["attackerSectId"] = decision.attackerSectId;
        j["attackerSectName"] = decision.attackerSectName;
    }
    return env->NewStringUTF(j.dump().c_str());
}

// ============================================================
// execute 分发表通道（对拍用）
// Kotlin 侧经 ActionIds 协议调用 C++ GameCore.execute（与 Android 桥同入口）
// ============================================================

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_xianxia_sect_core_nativebridge_DiffRngBridge_nativeCoreExecute(
    JNIEnv* env, jobject /*thiz*/, jint actionId, jbyteArray paramsJson) {
    if (!g_core) return stringToJbytes(env, R"({"status":"failure","code":"kInternal"})");
    const std::string params = jbytesToString(env, paramsJson);
    return stringToJbytes(env, g_core->execute(
        static_cast<int32_t>(actionId), params, 0));
}


// ============================================================
// 战斗计算通道（对拍用）
//
// 操作 JSON 格式（纯计算，不落状态）：
//   {"op":"finalDamage", "rawAttack":100, "defense":500, "skillMultiplier":1.0,
//    "zones":{...}, "isCrit":false, "variance":1.0}
//   {"op":"realmGapFactors", "attackerRealm":9, "attackerLayer":5,
//    "defenderRealm":9, "defenderLayer":1}
//   {"op":"checkInstantKill", "attackerRealm":0, "defenderRealm":9, ...}
//   {"op":"dodgeChance", "attackerSpeed":200, "defenderSpeed":100, "modifier":0.5}
//   {"op":"shieldAbsorption", "maxHp":1000, "shieldValue":0.3, "shieldActive":true, "damage":500}
// ============================================================

namespace {

using gamecore::battle::AffixCombatEffects;
using gamecore::battle::buffFromJson;
using gamecore::battle::buffToJson;
using gamecore::battle::combatantFromJson;
using gamecore::battle::combatantToJson;
using gamecore::battle::CombatBuff;
using gamecore::battle::CombatSkill;
using gamecore::battle::Combatant;
using gamecore::battle::PhysiqueCombatFactors;
using gamecore::battle::skillFromJson;
using gamecore::battle::skillToJson;

/// DamageResult → JSON（对拍输出键与 Kotlin DamageResult 字段对应）
nlohmann::json damageResultToJson(const gamecore::battle::DamageResult& r) {
    return {
        {"damage", r.damage}, {"isCrit", r.isCrit}, {"isPhysical", r.isPhysical},
        {"isDodged", r.isDodged}, {"isInstantKill", r.isInstantKill}, {"hits", r.hits},
    };
}

nlohmann::json execBattleOp(const nlohmann::json& op) {
    using gamecore::battle::DamageZones;
    const std::string opName = op.at("op").get<std::string>();
    nlohmann::json result;
    if (opName == "finalDamage") {
        DamageZones zones;
        if (op.contains("zones")) {
            const auto& z = op.at("zones");
            zones.attackBuffs = z.value("attackBuffs", 0.0);
            zones.physicalAttackBuffs = z.value("physicalAttackBuffs", 0.0);
            zones.magicAttackBuffs = z.value("magicAttackBuffs", 0.0);
            zones.damageAmplification = z.value("damageAmplification", 0.0);
            zones.damageReduction = z.value("damageReduction", 0.0);
            zones.physiqueDamageAmplification = z.value("physiqueDamageAmplification", 0.0);
            zones.physiqueCritDamageBonus = z.value("physiqueCritDamageBonus", 0.0);
            zones.physiqueDamageReduction = z.value("physiqueDamageReduction", 0.0);
            zones.physiqueDefenseBonus = z.value("physiqueDefenseBonus", 0.0);
            zones.affixDamageAmplification = z.value("affixDamageAmplification", 0.0);
            zones.affixCritDamageBonus = z.value("affixCritDamageBonus", 0.0);
            zones.affixDamageReduction = z.value("affixDamageReduction", 0.0);
            zones.affixDefenseBonus = z.value("affixDefenseBonus", 0.0);
            zones.realmGapDamageAmplification = z.value("realmGapDamageAmplification", 0.0);
            zones.realmGapDamageReduction = z.value("realmGapDamageReduction", 0.0);
            zones.majorRealmDamageAmplification = z.value("majorRealmDamageAmplification", 0.0);
        }
        result["value"] = gamecore::battle::calculateFinalDamage(
            op.value("rawAttack", 0), op.value("defense", 0),
            op.value("skillMultiplier", 1.0), zones,
            op.value("isCrit", false), op.value("variance", 1.0));
    } else if (opName == "realmGapFactors") {
        const auto f = gamecore::battle::calculateRealmGapFactors(
            op.value("attackerRealm", 9), op.value("attackerLayer", 1),
            op.value("defenderRealm", 9), op.value("defenderLayer", 1));
        result = {
            {"damageAmplification", f.damageAmplification},
            {"damageReduction", f.damageReduction},
            {"majorRealmDamageAmplification", f.majorRealmDamageAmplification},
        };
    } else if (opName == "checkInstantKill") {
        result["value"] = gamecore::battle::checkInstantKill(
            op.value("attackerRealm", 9), op.value("defenderRealm", 9),
            op.value("attackerLayer", 1), op.value("defenderLayer", 1));
    } else if (opName == "dodgeChance") {
        result["value"] = gamecore::battle::calculateDodgeChance(
            op.value("attackerSpeed", 0), op.value("defenderSpeed", 0),
            op.value("modifier", 0.5));
    } else if (opName == "shieldAbsorption") {
        const auto r = gamecore::battle::calculateShieldAbsorption(
            op.value("maxHp", 0), op.value("shieldValue", 0.0),
            op.value("shieldActive", false), op.value("damage", 0));
        result = {
            {"absorbed", r.absorbed}, {"remainingDamage", r.remainingDamage},
            {"remainingShield", r.remainingShield},
        };
    } else if (opName == "combatantDamage") {
        // 完整伤害管线（斩杀→闪避→暴击→波动→分桶注入→段数钳制）
        const auto attacker = combatantFromJson(op.at("attacker"));
        const auto defender = combatantFromJson(op.at("defender"));
        std::optional<CombatSkill> skill;
        if (op.contains("skill") && !op["skill"].is_null()) {
            skill = skillFromJson(op.at("skill"));
        }
        std::optional<gamecore::battle::DamageZones> zones;
        if (op.contains("zones") && !op["zones"].is_null()) {
            gamecore::battle::DamageZones z;
            const auto& zj = op.at("zones");
            z.attackBuffs = zj.value("attackBuffs", 0.0);
            z.physicalAttackBuffs = zj.value("physicalAttackBuffs", 0.0);
            z.magicAttackBuffs = zj.value("magicAttackBuffs", 0.0);
            z.damageAmplification = zj.value("damageAmplification", 0.0);
            z.damageReduction = zj.value("damageReduction", 0.0);
            z.physiqueDamageAmplification = zj.value("physiqueDamageAmplification", 0.0);
            z.physiqueCritDamageBonus = zj.value("physiqueCritDamageBonus", 0.0);
            z.physiqueDamageReduction = zj.value("physiqueDamageReduction", 0.0);
            z.physiqueDefenseBonus = zj.value("physiqueDefenseBonus", 0.0);
            z.affixDamageAmplification = zj.value("affixDamageAmplification", 0.0);
            z.affixCritDamageBonus = zj.value("affixCritDamageBonus", 0.0);
            z.affixDamageReduction = zj.value("affixDamageReduction", 0.0);
            z.affixDefenseBonus = zj.value("affixDefenseBonus", 0.0);
            z.realmGapDamageAmplification = zj.value("realmGapDamageAmplification", 0.0);
            z.realmGapDamageReduction = zj.value("realmGapDamageReduction", 0.0);
            z.majorRealmDamageAmplification = zj.value("majorRealmDamageAmplification", 0.0);
            zones = z;
        }
        if (!g_rng) result["error"] = "rng not initialized";
        else {
            const auto r = gamecore::battle::calculateCombatantDamage(
                *g_rng, attacker, defender, skill ? &*skill : nullptr,
                op.value("damageModifier", 1.0), zones ? &*zones : nullptr,
                op.value("enableInstantKill", false));
            result = damageResultToJson(r);
        }
    } else if (opName == "estimateDamage") {
        // 确定性伤害估算（无 RNG——AI 决策用）
        const auto attacker = combatantFromJson(op.at("attacker"));
        const auto defender = combatantFromJson(op.at("defender"));
        const auto skill = skillFromJson(op.at("skill"));
        result["value"] = gamecore::battle::estimateDamage(
            attacker, defender, skill, nullptr, op.value("damageModifier", 1.0));
    } else if (opName == "decideAction") {
        // 统一战斗 AI 决策（decideAction 8 层级联，g_rng 随机源）
        const auto unit = combatantFromJson(op.at("unit"));
        std::vector<Combatant> allies;
        if (op.contains("allies") && op["allies"].is_array()) {
            for (const auto& a : op.at("allies")) allies.push_back(combatantFromJson(a));
        }
        std::vector<Combatant> enemies;
        if (op.contains("enemies") && op["enemies"].is_array()) {
            for (const auto& e : op.at("enemies")) enemies.push_back(combatantFromJson(e));
        }
        if (!g_rng) result["error"] = "rng not initialized";
        else {
            const auto action = gamecore::battle::decideAction(
                unit, allies, enemies, *g_rng, op.value("playerDamageModifier", 1.0));
            result["actionType"] = gamecore::battle::aiActionTypeName(action.actionType);
            if (action.skill.has_value()) result["skillName"] = action.skill->name;
            if (action.targetId.has_value()) result["targetId"] = *action.targetId;
        }
    } else if (opName == "executeBattle") {
        // 回合编排全链（executeBattle 状态段；日志保持 Kotlin diff 排除）
        std::vector<Combatant> team;
        if (op.contains("team") && op["team"].is_array()) {
            for (const auto& t : op.at("team")) team.push_back(combatantFromJson(t));
        }
        std::vector<Combatant> beasts;
        if (op.contains("beasts") && op["beasts"].is_array()) {
            for (const auto& b : op.at("beasts")) beasts.push_back(combatantFromJson(b));
        }
        if (!g_rng) result["error"] = "rng not initialized";
        else {
            gamecore::battle::BattleState state;
            state.team = std::move(team);
            state.beasts = std::move(beasts);
            state.maxTurns = op.value("maxTurns", gamecore::battle::kMaxTurns);
            const auto out = gamecore::battle::executeBattle(
                state, op.value("playerDamageModifier", 1.0), *g_rng,
                op.value("timeoutMs", -1LL), nullptr);
            result["turn"] = out.turn;
            result["timedOut"] = out.timedOut;
            result["winner"] = out.winner == gamecore::battle::BattleWinner::kTeam
                ? "TEAM"
                : (out.winner == gamecore::battle::BattleWinner::kBeasts ? "BEASTS" : "DRAW");
            result["rewards"] = out.rewards;
            result["rounds"] = gamecore::battle::roundsToJson(out.rounds);
            result["team"] = nlohmann::json::array();
            for (const auto& c : out.team) result["team"].push_back(combatantToJson(c));
            result["beasts"] = nlohmann::json::array();
            for (const auto& c : out.beasts) result["beasts"].push_back(combatantToJson(c));
        }
    } else if (opName == "executeAiBattle") {
        // AI 宗门战第三引擎（executeUnifiedAIBattle 对拍）
        std::vector<Combatant> attackers;
        if (op.contains("attackers") && op["attackers"].is_array()) {
            for (const auto& a : op.at("attackers")) attackers.push_back(combatantFromJson(a));
        }
        std::vector<Combatant> defenders;
        if (op.contains("defenders") && op["defenders"].is_array()) {
            for (const auto& d : op.at("defenders")) defenders.push_back(combatantFromJson(d));
        }
        if (!g_rng) result["error"] = "rng not initialized";
        else {
            const auto out = gamecore::battle::executeAiBattle(
                std::move(attackers), std::move(defenders), *g_rng);
            result["turns"] = out.turns;
            result["winner"] = out.winner == gamecore::battle::AiBattleWinner::kAttacker
                ? "ATTACKER"
                : (out.winner == gamecore::battle::AiBattleWinner::kDefender ? "DEFENDER" : "DRAW");
            result["rounds"] = gamecore::battle::roundsToJson(out.rounds);
            result["attackers"] = nlohmann::json::array();
            for (const auto& c : out.attackers) result["attackers"].push_back(combatantToJson(c));
            result["defenders"] = nlohmann::json::array();
            for (const auto& c : out.defenders) result["defenders"].push_back(combatantToJson(c));
        }
    } else {
        result["error"] = "unknown op: " + opName;
    }
    return result;
}

}  // namespace

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_xianxia_sect_core_nativebridge_DiffRngBridge_nativeCoreBattleOp(
    JNIEnv* env, jobject /*thiz*/, jbyteArray opJson) {
    try {
        const std::string input = jbytesToString(env, opJson);
        const auto op = nlohmann::json::parse(input);
        return stringToJbytes(env, execBattleOp(op).dump());
    } catch (const std::exception& e) {
        nlohmann::json err = {{"error", e.what()}};
        return stringToJbytes(env, err.dump());
    }
}


// 协议：Kotlin 侧 DiffEconomyBridge 传入 JSON 操作序列（统一入口），
// C++ 侧对 g_core 状态依次执行，Kotlin 侧以真实 Kotlin 钱包/库存逻辑
// 对同一操作序列复刻，随后 export/import 逐字段对拍。
//
// 操作 JSON 格式（数组）：
//   {"op":"walletAdd","grade":"LOW","amount":100,"source":"Battle"}
//   {"op":"walletDeduct","grade":"LOW","amount":50,"reason":"Purchase","source":"Internal","autoConvert":true}
//   {"op":"walletBatch","autoConvert":false,"ops":[{...delta/grade/reason/source}]}
//   {"op":"invAddEquipment","id":"eq-1","name":"木剑","rarity":1,"slot":"WEAPON","quantity":5,
//     "source":"battle","suppressed":false}
//   {"op":"invAddPill",...,"category":"CULTIVATION","grade":"MEDIUM"}
//   {"op":"invAddMaterial",...,"category":"BEAST_HIDE"}
//   {"op":"invAddHerb",...,"category":"灵草"}
//   {"op":"invAddSeed",...,"growTime":3,"yield":1}
//   {"op":"invAddManual",...,"type":"MIND"}
//   {"op":"invAddStorageBag",...}
//   {"op":"invRemove","type":"equipment","id":"eq-1","quantity":2,"bypassLock":false}
// ============================================================

namespace {

using gamecore::system::SpiritStoneGrade;
using gamecore::system::spiritStoneGradeFromName;
using gamecore::state::EquipmentStack;
using gamecore::state::ManualStack;
using gamecore::state::Material;
using gamecore::state::Pill;
using gamecore::state::Seed;
using gamecore::state::StorageBag;

/// 解析品阶名（默认 LOW）
SpiritStoneGrade gradeFromJson(const nlohmann::json& j) {
    if (j.contains("grade")) return spiritStoneGradeFromName(j.at("grade").get<std::string>());
    return SpiritStoneGrade::LOW;
}

/// 构造装备堆叠（invAddEquipment / invRemove 共用字段）
EquipmentStack equipmentFromJson(const nlohmann::json& op) {
    EquipmentStack e;
    e.id = op.value("id", "");
    e.name = op.value("name", "");
    e.rarity = op.value("rarity", 1);
    e.slot = op.value("slot", "WEAPON");
    e.quantity = op.value("quantity", 1);
    e.isLocked = op.value("isLocked", false);
    return e;
}

ManualStack manualFromJson(const nlohmann::json& op) {
    ManualStack m;
    m.id = op.value("id", "");
    m.name = op.value("name", "");
    m.rarity = op.value("rarity", 1);
    m.type = op.value("type", "MIND");
    m.quantity = op.value("quantity", 1);
    m.isLocked = op.value("isLocked", false);
    return m;
}

Pill pillFromJson(const nlohmann::json& op) {
    Pill p;
    p.id = op.value("id", "");
    p.name = op.value("name", "");
    p.rarity = op.value("rarity", 1);
    p.category = op.value("category", "CULTIVATION");
    p.grade = op.value("grade", "MEDIUM");
    p.quantity = op.value("quantity", 1);
    p.isLocked = op.value("isLocked", false);
    return p;
}

Material materialFromJson(const nlohmann::json& op) {
    Material m;
    m.id = op.value("id", "");
    m.name = op.value("name", "");
    m.rarity = op.value("rarity", 1);
    m.category = op.value("category", "BEAST_HIDE");
    m.quantity = op.value("quantity", 1);
    m.isLocked = op.value("isLocked", false);
    return m;
}

Seed seedFromJson(const nlohmann::json& op) {
    Seed s;
    s.id = op.value("id", "");
    s.name = op.value("name", "");
    s.rarity = op.value("rarity", 1);
    s.growTime = op.value("growTime", 3);
    s.yield = op.value("yield", 1);
    s.quantity = op.value("quantity", 1);
    s.isLocked = op.value("isLocked", false);
    return s;
}

StorageBag bagFromJson(const nlohmann::json& op) {
    StorageBag b;
    b.id = op.value("id", "");
    b.name = op.value("name", "");
    b.rarity = op.value("rarity", 1);
    b.quantity = op.value("quantity", 1);
    b.isLocked = op.value("isLocked", false);
    return b;
}

/// 库存操作结果 → JSON（含状态/溢出/错误码）
nlohmann::json inventoryResultToJson(const auto& r) {
    using gamecore::system::InventoryStatus;
    nlohmann::json j;
    switch (r.status) {
        case InventoryStatus::kSuccess: j["status"] = "success"; break;
        case InventoryStatus::kPartial: j["status"] = "partial"; break;
        case InventoryStatus::kFailure: j["status"] = "failure"; break;
    }
    j["overflow"] = r.overflow;
    j["errorType"] = static_cast<int>(r.error.type);
    j["errorItemId"] = r.error.itemId;
    return j;
}

/// 执行单条操作（钱包/库存统一分派；返回结果 JSON 片段）
nlohmann::json execOp(gamecore::GameCore* core, nlohmann::json& result,
                      const nlohmann::json& op) {
    using gamecore::system::InventoryStatus;
    auto& state = core->state();
    auto& gd = state.gameData;
    const std::string opName = op.at("op").get<std::string>();
    if (opName == "walletAdd") {
        const int64_t amount = op.at("amount").get<int64_t>();
        const std::string source = op.value("source", "Internal");
        const int64_t balance = gamecore::system::SpiritStoneWallet::add(
            gd, amount, gradeFromJson(op), source);
        result["lastBalance"] = balance;
    } else if (opName == "walletDeduct") {
        const int64_t amount = op.at("amount").get<int64_t>();
        const std::string reason = op.value("reason", "Internal");
        const std::string source = op.value("source", "Internal");
        const bool autoConvert = op.value("autoConvert", true);
        const auto r = gamecore::system::SpiritStoneWallet::deduct(
            gd, amount, gradeFromJson(op), reason, source, autoConvert);
        nlohmann::json rj;
        switch (r.status) {
            case gamecore::system::DeductStatus::kSuccess:
                rj = {{"status", "success"}, {"balanceAfter", r.balanceAfter}};
                break;
            case gamecore::system::DeductStatus::kInsufficient:
                rj = {{"status", "insufficient"},
                      {"balance", r.balance}, {"required", r.required}};
                break;
            case gamecore::system::DeductStatus::kInvalid:
                rj = {{"status", "invalid"}};
                break;
        }
        result["lastDeduct"] = rj;
    } else if (opName == "walletBatch") {
        const bool autoConvert = op.value("autoConvert", false);
        std::vector<gamecore::system::SpiritStoneOperation> ops;
        for (const auto& o : op.at("ops")) {
            gamecore::system::SpiritStoneOperation so;
            so.delta = o.at("delta").get<int64_t>();
            so.grade = gradeFromJson(o);
            so.reason = o.value("reason", "Internal");
            so.source = o.value("source", "Internal");
            ops.push_back(so);
        }
        const auto r = gamecore::system::SpiritStoneWallet::batch(gd, ops, autoConvert);
        nlohmann::json rj = {{"successCount", r.successCount}, {"failedCount", r.failedCount}};
        nlohmann::json results = nlohmann::json::array();
        for (const auto& d : r.results) {
            switch (d.status) {
                case gamecore::system::DeductStatus::kSuccess:
                    results.push_back({{"status", "success"}, {"balanceAfter", d.balanceAfter}});
                    break;
                case gamecore::system::DeductStatus::kInsufficient:
                    results.push_back({{"status", "insufficient"},
                                       {"balance", d.balance}, {"required", d.required}});
                    break;
                case gamecore::system::DeductStatus::kInvalid:
                    results.push_back({{"status", "invalid"}});
                    break;
            }
        }
        rj["results"] = results;
        result["lastBatch"] = rj;
    } else if (opName == "invAddEquipment") {
        gamecore::system::OverflowMailCollector mail;
        const auto r = gamecore::system::addEquipmentStack(
            state, equipmentFromJson(op), mail, op.value("source", "unknown"),
            op.value("suppressed", false));
        result["lastInventory"] = inventoryResultToJson(r);
        result["overflowMails"] = mail.all().size();
    } else if (opName == "invAddManual") {
        gamecore::system::OverflowMailCollector mail;
        const auto r = gamecore::system::addManualStack(
            state, manualFromJson(op), mail, op.value("source", "unknown"),
            op.value("suppressed", false), op.value("merge", true));
        result["lastInventory"] = inventoryResultToJson(r);
        result["overflowMails"] = mail.all().size();
    } else if (opName == "invAddPill") {
        gamecore::system::OverflowMailCollector mail;
        const auto r = gamecore::system::addPill(
            state, pillFromJson(op), mail, op.value("source", "unknown"),
            op.value("suppressed", false), op.value("merge", true));
        result["lastInventory"] = inventoryResultToJson(r);
        result["overflowMails"] = mail.all().size();
    } else if (opName == "invAddMaterial") {
        gamecore::system::OverflowMailCollector mail;
        const auto r = gamecore::system::addMaterial(
            state, materialFromJson(op), mail, op.value("source", "unknown"),
            op.value("suppressed", false), op.value("merge", true));
        result["lastInventory"] = inventoryResultToJson(r);
        result["overflowMails"] = mail.all().size();
    } else if (opName == "invAddSeed") {
        gamecore::system::OverflowMailCollector mail;
        const auto r = gamecore::system::addSeed(
            state, seedFromJson(op), mail, op.value("source", "unknown"),
            op.value("suppressed", false), op.value("merge", true));
        result["lastInventory"] = inventoryResultToJson(r);
        result["overflowMails"] = mail.all().size();
    } else if (opName == "invAddStorageBag") {
        gamecore::system::OverflowMailCollector mail;
        const auto r = gamecore::system::addStorageBag(
            state, bagFromJson(op), mail, op.value("source", "unknown"),
            op.value("suppressed", false));
        result["lastInventory"] = inventoryResultToJson(r);
        result["overflowMails"] = mail.all().size();
    } else if (opName == "invRemove") {
        const std::string type = op.value("type", "equipment");
        const std::string id = op.at("id").get<std::string>();
        const int32_t qty = op.value("quantity", 1);
        const bool bypass = op.value("bypassLock", false);
        bool ok = false;
        if (type == "equipment") ok = gamecore::system::removeEquipment(state, id, qty, bypass);
        else if (type == "manual") ok = gamecore::system::removeManual(state, id, qty, bypass);
        else if (type == "pill") ok = gamecore::system::removePill(state, id, qty, bypass);
        else if (type == "material") ok = gamecore::system::removeMaterial(state, id, qty, bypass);
        else if (type == "seed") ok = gamecore::system::removeSeed(state, id, qty, bypass);
        result["lastRemove"] = ok;
    } else if (opName == "spiritFieldHarvest") {
        // 灵田收获：设置当前年/月后执行
        if (op.contains("year")) gd.gameYear = op.at("year").get<int32_t>();
        if (op.contains("month")) gd.gameMonth = op.at("month").get<int32_t>();
        gamecore::system::OverflowMailCollector mail;
        const auto r = gamecore::system::processSpiritFieldHarvest(state, core->rng(), mail);
        result["harvest"] = {
            {"herbsHarvested", r.herbsHarvested},
            {"seedsHarvested", r.seedsHarvested},
            {"plantsCompleted", r.plantsCompleted},
        };
        result["overflowMails"] = mail.all().size();
    } else {
        result["error"] = "unknown op: " + opName;
    }
    return result;
}

}  // namespace

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_xianxia_sect_core_nativebridge_DiffRngBridge_nativeCoreExecOps(
    JNIEnv* env, jobject /*thiz*/, jbyteArray opsJson) {
    if (!g_core) return stringToJbytes(env, R"({"error":"not initialized"})");
    try {
        const std::string input = jbytesToString(env, opsJson);
        const auto ops = nlohmann::json::parse(input);
        nlohmann::json result;
        if (ops.is_array()) {
            for (const auto& op : ops) {
                execOp(g_core, result, op);
            }
        } else {
            execOp(g_core, result, ops);
        }
        return stringToJbytes(env, result.dump());
    } catch (const std::exception& e) {
        nlohmann::json err = {{"error", e.what()}};
        return stringToJbytes(env, err.dump());
    }
}

// ============================================================
// 道路系统对拍通道（求解器双端一致性）
//
// 操作 JSON 格式（纯计算，不落状态）：
//   {"op":"tileType", "mask": 11}            → 形态枚举 int
//   {"op":"borderMask", "mask": 11}          → 描边掩码 int
//   {"op":"bitmaskAt", "roads":[[x,y],...], "x":3, "y":4, "width":10, "height":10} → 位掩码
// ============================================================

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_xianxia_sect_core_nativebridge_DiffRngBridge_nativeRoadOp(
    JNIEnv* env, jobject /*thiz*/, jbyteArray opJson) {
    try {
        const std::string input = jbytesToString(env, opJson);
        const auto op = nlohmann::json::parse(input);
        const std::string opName = op.at("op").get<std::string>();
        nlohmann::json result;
        if (opName == "tileType") {
            const int mask = op.at("mask").get<int>();
            result["value"] = static_cast<int>(
                gamecore::map::tileTypeForBitmask(mask));
        } else if (opName == "borderMask") {
            const int mask = op.at("mask").get<int>();
            result["value"] = gamecore::map::roadBorderMask(mask);
        } else if (opName == "bitmaskAt") {
            const int x = op.at("x").get<int>();
            const int y = op.at("y").get<int>();
            const int width = op.at("width").get<int>();
            const int height = op.at("height").get<int>();
            // 构建道路集合（与 Kotlin RoadTiling.bitmaskAt 同语义）
            std::vector<std::pair<int, int>> cells;
            for (const auto& r : op.at("roads")) {
                cells.emplace_back(r.at(0).get<int>(), r.at(1).get<int>());
            }
            // 用 RoadGrid 重建后查询
            gamecore::map::RoadGrid grid(width, height);
            grid.rebuild(cells);
            // Kotlin bitmaskAt 语义：对任意坐标计算"四邻中有道路的方向"，
            // 不要求该格本身是道路——显式计算四邻（与 Kotlin 逐位一致）
            int mask = 0;
            const bool inBounds = x >= 0 && y >= 0 && x < width && y < height;
            if (inBounds) {
                if (y - 1 >= 0 && grid.isRoad(x, y - 1)) mask |= gamecore::map::kDirUp;
                if (x + 1 < width && grid.isRoad(x + 1, y)) mask |= gamecore::map::kDirRight;
                if (y + 1 < height && grid.isRoad(x, y + 1)) mask |= gamecore::map::kDirDown;
                if (x - 1 >= 0 && grid.isRoad(x - 1, y)) mask |= gamecore::map::kDirLeft;
            }
            result["value"] = mask;
        } else if (opName == "compose") {
            // 道路渲染合成器：单格绘制操作序列
            // [[sprite, x, y, w, h, flip], ...]——sprite 序 = RoadSprite 枚举序，
            // flip = flipU 水平镜像标志（2.4 边缘条方向修正，0/1）
            const int mask = op.at("mask").get<int>();
            const int tileSize = op.at("tileSize").get<int>();
            gamecore::map::RoadDrawOp ops[gamecore::map::kMaxRoadDrawOpsPerTile];
            const int n = gamecore::map::emitRoadDrawOps(mask, tileSize, ops);
            nlohmann::json arr = nlohmann::json::array();
            for (int i = 0; i < n; i++) {
                arr.push_back({static_cast<int>(ops[i].sprite),
                               ops[i].x, ops[i].y, ops[i].w, ops[i].h,
                               ops[i].flipU ? 1 : 0});
            }
            result["ops"] = arr;
        } else {
            result["error"] = "unknown op: " + opName;
        }
        return stringToJbytes(env, result.dump());
    } catch (const std::exception& e) {
        nlohmann::json err = {{"error", e.what()}};
        return stringToJbytes(env, err.dump());
    }
}

// ============================================================
// 宗门地图地形生成（与 Android GameCoreBridge.nativeGenerateSectTerrain
// 同签名同语义；DiffSectTerrainTest 双端全数组逐位对拍用）
// ============================================================

extern "C" JNIEXPORT jintArray JNICALL
Java_com_xianxia_sect_core_nativebridge_DiffRngBridge_nativeGenerateSectTerrain(
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

// 位级对拍探针：terrain cellHash（jfloat 直传保 IEEE binary32 位型）
extern "C" JNIEXPORT jfloat JNICALL
Java_com_xianxia_sect_core_nativebridge_DiffRngBridge_nativeSectCellHash(
    JNIEnv* /*env*/, jobject /*thiz*/, jint x, jint y, jint seed) {
    return gamecore::map::terrain::cellHash(
        static_cast<int32_t>(x), static_cast<int32_t>(y), static_cast<int32_t>(seed));
}

// 位级对拍探针：terrain smoothNoise
extern "C" JNIEXPORT jfloat JNICALL
Java_com_xianxia_sect_core_nativebridge_DiffRngBridge_nativeSectSmoothNoise(
    JNIEnv* /*env*/, jobject /*thiz*/, jint x, jint y, jint scale, jint seed) {
    return gamecore::map::terrain::smoothNoise(
        static_cast<int32_t>(x), static_cast<int32_t>(y),
        static_cast<int32_t>(scale), static_cast<int32_t>(seed));
}

// ============================================================
// Cleanup on unload
// ============================================================

extern "C" JNIEXPORT void JNICALL
Java_com_xianxia_sect_core_nativebridge_DiffRngBridge_nativeDestroy(
    JNIEnv* /*env*/, jobject /*thiz*/) {
    delete g_rng;
    g_rng = nullptr;
    delete g_rngManager;
    g_rngManager = nullptr;
    delete g_monitor;
    g_monitor = nullptr;
    if (g_core) {
        g_core->shutdown();
        delete g_core;
        g_core = nullptr;
    }
}
