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
//   ENEMY_GEN=4 / MAIL=5 / AI_SECT=6 / SECRET_REALM=7 / MISSION=8 /
//   AI_SECT_MIRROR=9（保留）
//
// AI_SECT_MIRROR(9) 语义（**保留 id，永不改义**）：
//   C++ AI 域真实消费的流是 GameCore::aiRng_（种子 seed + 6×31337），而它
//   原先**不在 rngStates 协议面内**（syncRngStates 只导 rng_.exportStates()）
//   ⇒ AI 宗门演化读档后无法续接。本分区承载 aiRng_ 的**镜像态**，经既有三入口
//   （nextInt/snapshot/restore）与 Kotlin NativeBackedRng 委托贯通：
//     - snapshot(9) → aiRng_.snapshot()
//     - restore(9, s) → aiRng_.restore(s) **且** 镜像分区同步落 s（双向对称）
//   `kAiSect`(6) 分区本身仍供 Kotlin 委托通道消费，两者是**不同种子、不同序列**
//   的独立流，本项不做合并（合并会改变 AI 演化行为基线）。
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
    kMission = 8,   // 任务系统（任务刷新/奖励 RNG 收敛分区）
    kAiSectMirror = 9,  // AI 流镜像态（GameCore::aiRng_ 的归档通道；保留 id 永不改义）
    kChat = 10,     // 弟子交谈（W4-A·A5：DiscipleChatDialog 决策类抽取——用户时序独立流，不与结算分区共用）
};

class RngManager {
public:
    /// 分区 id 上界（**唯一权威**：JNI 入口的合法性守卫必须引用本常量，
    /// 不得写死枚举成员——新增分区时写死的守卫会静默拒绝新 id，
    /// MISSION(8) 曾因此在 AUTHORITATIVE 下恒返回 0）
    static constexpr int32_t kMaxPartitionId = static_cast<int32_t>(RngPartition::kAiSectMirror);

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
        partitions_[RngPartition::kChat] = DeterministicRng::fromSeed(seed + 10);
        // 镜像分区按同一公式播种（= aiRng_ 的播种式 seed + 6×31337 的等价初值；
        // GameCore::initialize 播种 aiRng_ 后经 mirrorAiRng 覆盖为权威态）
        partitions_[RngPartition::kAiSectMirror] = DeterministicRng::fromSeed(seed + 9);
    }

    /// 获取指定分区的 PRNG（Kotlin 语义：未初始化则 error；此处抛出异常由调用方保证初始化）
    DeterministicRng& getRng(RngPartition partition) {
        return partitions_.at(partition);
    }

    /// 导出所有分区状态（对应 Kotlin exportStates(): Map<Int, Long>）
    /// 返回分区 id → state；std::map 有序（存档语义与顺序无关）
    ///
    /// **`kAiSectMirror` 跳过**：该分区是 Kotlin 侧取用 `GameCore::aiRng_` 的
    /// 通道句柄（`inSnapshot=false`），其真实状态由宿主侧 `syncRngStates` 以同一
    /// 9 号键单独写入——若在此一并导出，会把镜像分区的陈旧值覆盖掉刚写好的 AI 态
    ///（Kotlin 与 C++ 两侧该键的序列不同，见 RngPartition KDoc）。
    std::map<int32_t, int64_t> exportStates() const {
        std::map<int32_t, int64_t> out;
        for (const auto& [p, rng] : partitions_) {
            if (p == RngPartition::kAiSectMirror) continue;
            out[static_cast<int32_t>(p)] = rng.snapshot();
        }
        return out;
    }

    /// 从存档恢复（对应 Kotlin restoreStates(states: Map<Int, Long>)）
    /// `kAiSectMirror` 跳过（通道型分区不参与存档面——见 exportStates 注释）；
    /// 未知 id 亦跳过（Kotlin: 未知分区跳过），保证前后版本互读无损
    void restoreStates(const std::map<int32_t, int64_t>& states) {
        for (const auto& [partitionId, savedState] : states) {
            if (partitionId == static_cast<int32_t>(RngPartition::kAiSectMirror)) continue;
            auto it = partitions_.find(static_cast<RngPartition>(partitionId));
            if (it == partitions_.end()) continue;  // Kotlin: 未知分区跳过
            it->second.restore(savedState);
        }
    }

    /// 是否参与存档序列化（Kotlin `RngPartition.inSnapshot` 的 C++ 同源口径）
    static constexpr bool inSnapshot(RngPartition partition) {
        return partition != RngPartition::kAiSectMirror;
    }

private:
    int64_t systemSeed_ = 0;
    std::map<RngPartition, DeterministicRng> partitions_;
};

}  // namespace gamecore::rng
