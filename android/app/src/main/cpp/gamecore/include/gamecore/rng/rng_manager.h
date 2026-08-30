#pragma once

#include <cstdint>
#include <map>

#include "gamecore/rng/pcg_xsh_rr.h"

// ============================================================
// RNG 分区管理器 — 对应 Kotlin `GameRngManager`
//
// 不同游戏子系统使用独立 PRNG（分区策略参考 DCSS），
// 存档时导出各分区 state（Map<Int, Long> → GameData.rngStates），
// 读档时恢复——保证读档后推演结果与存档前完全一致（确定性）。
//
// 分区枚举（Kotlin RngPartition.kt，id 即 @ProtoNumber 语义的持久化值，
// 不得改动——存档 rngStates 的键）：
//   BATTLE=0 / BREAKTHROUGH=1 / EXPLORATION=2 / SYSTEM=3 /
//   ENEMY_GEN=4 / MAIL=5 / AI_SECT=6 / SECRET_REALM=7 / MISSION=8
// ============================================================
namespace gamecore::rng {

enum class RngPartition : int32_t {
    kBattle = 0,
    kBreakthrough = 1,
    kExploration = 2,
    kSystem = 3,
    kEnemyGen = 4,
    kMail = 5,
    kAiSect = 6,
    kSecretRealm = 7,
    kMission = 8,   // 任务系统（批 11-4 S-19：任务刷新/奖励 RNG 收敛分区）
};

class RngManager {
public:
    RngManager() = default;

    /// 初始化系统种子（创建新世界时调用；对应 Kotlin initSystemSeed）
    /// 各分区以 seed + partitionId 播种（Kotlin: fromSeed(seed + partition.id)）
    void initSystemSeed(int64_t seed) {
        systemSeed_ = seed;
        partitions_.clear();
        partitions_[RngPartition::kBattle] = DeterministicRng::fromSeed(seed + 0);
        partitions_[RngPartition::kBreakthrough] = DeterministicRng::fromSeed(seed + 1);
        partitions_[RngPartition::kExploration] = DeterministicRng::fromSeed(seed + 2);
        partitions_[RngPartition::kSystem] = DeterministicRng::fromSeed(seed + 3);
        partitions_[RngPartition::kEnemyGen] = DeterministicRng::fromSeed(seed + 4);
        partitions_[RngPartition::kMail] = DeterministicRng::fromSeed(seed + 5);
        partitions_[RngPartition::kAiSect] = DeterministicRng::fromSeed(seed + 6);
        partitions_[RngPartition::kSecretRealm] = DeterministicRng::fromSeed(seed + 7);
        partitions_[RngPartition::kMission] = DeterministicRng::fromSeed(seed + 8);
    }

    /// 获取指定分区的 PRNG（Kotlin 语义：未初始化则 error；此处抛出异常由调用方保证初始化）
    DeterministicRng& getRng(RngPartition partition) {
        return partitions_.at(partition);
    }

    /// 导出所有分区状态（对应 Kotlin exportStates(): Map<Int, Long>）
    /// 返回分区 id → state；std::map 有序（存档语义与顺序无关）
    std::map<int32_t, int64_t> exportStates() const {
        std::map<int32_t, int64_t> out;
        for (const auto& [p, rng] : partitions_) {
            out[static_cast<int32_t>(p)] = rng.snapshot();
        }
        return out;
    }

    /// 从存档恢复（对应 Kotlin restoreStates(states: Map<Int, Long>)）
    void restoreStates(const std::map<int32_t, int64_t>& states) {
        for (const auto& [partitionId, savedState] : states) {
            auto it = partitions_.find(static_cast<RngPartition>(partitionId));
            if (it == partitions_.end()) continue;  // Kotlin: 未知分区跳过
            it->second.restore(savedState);
        }
    }

private:
    int64_t systemSeed_ = 0;
    std::map<RngPartition, DeterministicRng> partitions_;
};

}  // namespace gamecore::rng
