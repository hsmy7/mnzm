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

#include <string>

#include <nlohmann/json.hpp>

#include "gamecore/game_core.h"
#include "gamecore/map/road_system.h"
#include "gamecore/rng/pcg_xsh_rr.h"
#include "gamecore/rng/rng_manager.h"
#include "gamecore/system/battle.h"
#include "gamecore/system/breakthrough.h"
#include "gamecore/system/cultivation.h"
#include "gamecore/system/disciple.h"
#include "gamecore/system/economy.h"
#include "gamecore/system/exploration.h"
#include "gamecore/system/government.h"
#include "gamecore/system/inventory.h"
#include "gamecore/system/lifecycle.h"
#include "gamecore/system/spirit_field.h"

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

extern "C" JNIEXPORT jlong JNICALL
Java_com_xianxia_sect_core_nativebridge_DiffRngBridge_nativeSnapshot(
    JNIEnv* /*env*/, jobject /*thiz*/) {
    return g_rng ? g_rng->snapshot() : 0L;
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
    // 同步重置 GameCore 内部 RNG（批次 4c 灵田收获走 core->rng()，需与对拍 seed 对齐）
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

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_xianxia_sect_core_nativebridge_DiffRngBridge_nativeCoreExportState(
    JNIEnv* env, jobject /*thiz*/) {
    if (!g_core) return stringToJbytes(env, "{}");
    return stringToJbytes(env, g_core->exportStateJson());
}

// ── 时间推进通道（批次 3：对拍用）──────────────────────────────

extern "C" JNIEXPORT jint JNICALL
Java_com_xianxia_sect_core_nativebridge_DiffRngBridge_nativeCoreAdvancePhases(
    JNIEnv* /*env*/, jobject /*thiz*/, jint phaseCount) {
    if (!g_core) return 0;
    return static_cast<jint>(g_core->advancePhases(static_cast<int>(phaseCount)).phasesAdvanced);
}

// ============================================================
// 弟子属性计算通道（批次 5：对拍用）
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

/// 执行弟子属性计算操作（返回结果 JSON 片段）
nlohmann::json execDiscipleOp(const nlohmann::json& op) {
    const std::string opName = op.at("op").get<std::string>();
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
// 修炼推进计算通道（批次 5b：对拍用）
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
// 内政计算通道（批次 7：对拍用）
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
// 探索计算通道（批次 8a：对拍用）
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
// execute 分发表通道（批次 9：对拍用）
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
// 战斗计算通道（批次 6a：对拍用）
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
        // 灵田收获：设置当前年/月后执行（批次 4c）
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
// 道路系统对拍通道（批次 R：求解器双端一致性）
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
// Cleanup on unload
// ============================================================

extern "C" JNIEXPORT void JNICALL
Java_com_xianxia_sect_core_nativebridge_DiffRngBridge_nativeDestroy(
    JNIEnv* /*env*/, jobject /*thiz*/) {
    delete g_rng;
    g_rng = nullptr;
    delete g_rngManager;
    g_rngManager = nullptr;
    if (g_core) {
        g_core->shutdown();
        delete g_core;
        g_core = nullptr;
    }
}
