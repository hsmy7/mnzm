#pragma once

// ============================================================
// determinism_probe.h — FP 确定性对拍探针（R0.2 双锁的探针腿）
//
// 目标：把"浮点位一致"从测试巧合变成跨架构工程锁定。本探针在
// **自包含、零平台依赖**的确定性场景中驱动引擎的全部 FP 主路径：
//   1. createDisciple 固定消费序（SYSTEM 分区 RNG，含 gaussianInt 浮点方差）
//   2. 40 旬结算（跨月/跨年边界——修炼/突破/月变/年变浮点积累）
//   3. executeBattle 全链（伤害/暴击/修正浮点主路径，BATTLE 分区）
//   4. RNG 原始标量序列
// 并对**位规范型转录**（整数原值 + double 的 IEEE754 位模式，不依赖
// printf/JSON 序列化的跨平台舍入行为）计算 FNV-1a 64 位摘要。
//
// 消费方（同一实现，两腿对拍）：
//   - 桌面腿：test/determinism_probe_test.cpp（x86-64 CI，GTest 断言 golden）
//   - 真机腿：Android instrumentation（arm64，经 GameCoreBridge JNI 同摘要断言）
//
// golden 变更协议：任何**有意**改变转录的代码变更，须在桌面腿重录
// kGoldenDigest 并在提交说明中注明（对拍基线重定，同 Diff 测试惯例）。
// ============================================================

#include <cstdint>
#include <cstring>
#include <string>
#include <vector>

#include "gamecore/core/clock.h"
#include "gamecore/core/logger.h"
#include "gamecore/game_core.h"
#include "gamecore/system/battle_execution.h"
#include "gamecore/system/disciple_factory.h"

namespace gamecore::probe {

/// golden 摘要（x86-64 llvm-mingw 桌面腿录制；跨架构必须逐位一致）
inline constexpr uint64_t kGoldenDigest = 0x490e8dc522e12921ULL;

/// 探针转录版本戳（转录格式/场景变更时递增，强制 golden 重录）
inline constexpr uint64_t kProbeVersion = 1;

/// FNV-1a 64 位（字节序固定的小端逐字节哈希，跨架构稳定；
/// 非加密哈希——本用途只需位漂移检测，碰撞概率 ~2^-64 充分）
class Fnv1a64 {
public:
    void bytes(const void* data, size_t len) {
        const auto* p = static_cast<const uint8_t*>(data);
        for (size_t i = 0; i < len; ++i) {
            hash_ ^= p[i];
            hash_ *= 0x100000001b3ULL;
        }
    }
    void u8(uint8_t v) { bytes(&v, 1); }
    void i32(int32_t v) { bytes(&v, sizeof(v)); }
    void u64(uint64_t v) { bytes(&v, sizeof(v)); }
    /// double 按原始 IEEE754 位模式哈希（位规范型——不经过文本序列化）
    void f64(double v) {
        uint64_t bits = 0;
        static_assert(sizeof(double) == sizeof(bits), "double must be 64-bit IEEE754");
        std::memcpy(&bits, &v, sizeof(bits));
        bytes(&bits, sizeof(bits));
    }
    void str(const std::string& s) {
        u64(static_cast<uint64_t>(s.size()));
        bytes(s.data(), s.size());
    }
    uint64_t value() const { return hash_; }

private:
    uint64_t hash_ = 0xcbf29ce484222325ULL;
};

struct ProbeResult {
    uint64_t digest = 0;
    bool matchesGolden() const { return digest == kGoldenDigest; }
};

/// 逐弟子转录（SoA 列采样：身份/境界/年龄/存活 + double 位模式）
inline void hashDiscipleColumns(Fnv1a64& h, const state::DiscipleStore& store) {
    h.u64(store.size());
    for (std::size_t i = 0; i < store.size(); ++i) {
        h.str(store.ids[i]);
        h.i32(store.realms[i]);
        h.i32(store.realmLayers[i]);
        h.f64(store.cultivations[i]);
        h.f64(store.cultivationCheckpoints[i]);
        h.i32(store.ages[i]);
        h.i32(store.lifespans[i]);
        h.u8(static_cast<uint8_t>(store.isAlive[i]));
    }
}

/// 逐参战者战斗终态转录
inline void hashCombatants(Fnv1a64& h, const std::vector<battle::Combatant>& list) {
    h.u64(list.size());
    for (const auto& c : list) {
        h.str(c.id);
        h.i32(c.hp);
        h.i32(c.mp);
        h.u8(c.isDead() ? 1 : 0);
    }
}

/// 战斗回合动作转录（确定性字段：伤害/暴击/击杀/攻防双方名）
inline void hashBattleRounds(Fnv1a64& h, const std::vector<battle::BattleRound>& rounds) {
    h.u64(rounds.size());
    for (const auto& round : rounds) {
        h.i32(round.roundNumber);
        h.u64(round.actions.size());
        for (const auto& a : round.actions) {
            h.str(a.type);
            h.str(a.attacker);
            h.str(a.target);
            h.i32(a.damage);
            h.u8(a.isCrit ? 1 : 0);
            h.u8(a.isKill ? 1 : 0);
        }
    }
}

/// 基础战斗单位（与 battle_execution_test.baseCombatant 同规格；
/// 2×2 对称阵容让战斗打满 maxTurns 内多回合 FP/RNG 路径）
inline battle::Combatant probeCombatant(const char* id, const char* name,
                                        battle::CombatantSide side, int speed) {
    battle::Combatant c;
    c.id = id;
    c.name = name;
    c.side = side;
    c.hp = 1000;
    c.maxHp = 1000;
    c.mp = 100;
    c.maxMp = 100;
    c.physicalAttack = 120;
    c.magicAttack = 100;
    c.physicalDefense = 60;
    c.magicDefense = 50;
    c.speed = speed;
    c.critRate = 0.15;
    c.realm = 9;
    c.realmLayer = 1;

    battle::CombatSkill s;
    s.name = "probe_attack";
    s.skillType = battle::SkillType::kAttack;
    s.damageType = battle::DamageType::kPhysical;
    s.damageMultiplier = 1.5;
    s.mpCost = 0;
    s.cooldown = 0;
    c.skills.push_back(s);
    return c;
}

/// 运行探针（自包含——新建 GameCore 实例，不触碰任何全局/平台状态）
inline ProbeResult runDeterminismProbe() {
    ProbeResult result;

    probe::Fnv1a64 h;
    h.u64(kProbeVersion);

    FixedClock clock{1'700'000'000'000LL};
    NullLogger logger;
    GameCore coreObj(&clock, &logger);
    GameCoreConfig config;
    config.seedInitialized = true;
    config.systemSeed = 42;
    if (!coreObj.initialize(config)) {
        result.digest = h.value();
        return result;  // 初始化失败 → 摘要必然偏离 golden，测试显式红
    }

    // ── 1. 弟子创建（SYSTEM 分区固定消费序：六维方差 gaussianInt 等）──
    auto& systemRng = coreObj.rng().getRng(rng::RngPartition::kSystem);
    static const char* kGenders[5] = {"male", "female", "male", "female", "male"};
    static const char* kRoots[5] = {"metal", "wood", "water", "fire", "earth"};
    for (int i = 0; i < 5; ++i) {
        system::DiscipleCreationSeed seed;
        seed.id = "probe_" + std::to_string(i);
        seed.gender = kGenders[i];
        seed.fullName = "Probe" + std::to_string(i);
        seed.surname = "Probe";
        seed.spiritRootType = kRoots[i];
        seed.age = 16;
        seed.realm = 3;
        seed.realmLayer = 1;
        auto d = system::createDisciple(seed, systemRng);
        coreObj.state().disciples.upsertDisciple(d);
    }

    // ── 2. 40 旬推进（年 1 月 11 起：跨月×3 + 跨年×1，结算 FP 全链）──
    auto& gd = coreObj.state().gameData;
    gd.gameYear = 1;
    gd.gameMonth = 11;
    gd.gamePhase = 0;
    for (int p = 0; p < 40; ++p) {
        h.i32(coreObj.settleOnePhase());
    }
    h.i32(gd.gameYear);
    h.i32(gd.gameMonth);
    h.i32(gd.gamePhase);
    h.u64(static_cast<uint64_t>(gd.spiritStones));
    h.f64(gd.sectCultivation);
    hashDiscipleColumns(h, coreObj.state().disciples);

    // ── 3. 战斗全链（BATTLE 分区；伤害/暴击/修正 FP 主路径）──
    auto& battleRng = coreObj.rng().getRng(rng::RngPartition::kBattle);
    battle::BattleState state;
    state.team.push_back(probeCombatant("t1", "probeA", battle::CombatantSide::kDefender, 90));
    state.team.push_back(probeCombatant("t2", "probeB", battle::CombatantSide::kDefender, 70));
    state.beasts.push_back(probeCombatant("b1", "probeX", battle::CombatantSide::kAttacker, 80));
    state.beasts.push_back(probeCombatant("b2", "probeY", battle::CombatantSide::kAttacker, 60));
    const auto battleResult = battle::executeBattle(state, 1.0, battleRng);
    h.i32(battleResult.turn);
    h.i32(static_cast<int32_t>(battleResult.winner));
    hashCombatants(h, battleResult.team);
    hashCombatants(h, battleResult.beasts);
    hashBattleRounds(h, battleResult.rounds);
    const auto rewardsIt = battleResult.rewards.find("spiritStones");
    h.i32(rewardsIt != battleResult.rewards.end() ? rewardsIt->second : 0);

    // ── 4. RNG 原始标量序列（分区状态一致性哨兵）──
    for (int i = 0; i < 16; ++i) {
        h.i32(coreObj.rngNextInt(static_cast<int>(rng::RngPartition::kBattle)));
    }

    result.digest = h.value();
    return result;
}

/// 摘要十六进制表示（测试失败信息/重录用）
inline std::string digestHex(uint64_t digest) {
    static const char* kHex = "0123456789abcdef";
    std::string out(16, '0');
    for (int i = 15; i >= 0; --i) {
        out[static_cast<size_t>(i)] = kHex[digest & 0xF];
        digest >>= 4;
    }
    return out;
}

}  // namespace gamecore::probe
