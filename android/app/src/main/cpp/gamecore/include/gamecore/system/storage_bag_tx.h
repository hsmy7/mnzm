// ============================================================
// storage_bag_tx.h — 储物袋开启**抽签**事务（ADR 随机源治理 阶段 1①）
//
// 来源：[ui-read-surface.md](../ui-read-surface.md) §4.3 残余域「库存开袋」；
// [docs/adr/rng-determinism-remediation.md] 阶段 1①。
//
// ## 为何只下沉"抽签"而不是整条开袋链
// Kotlin `InventoryFacadeImpl.openStorageBag` 的链路是两段：
//   ① **抽签**：`count = 5 + rng.nextInt(16)`，逐件 `kind = rng.nextInt(7)`
//      —— 已走 `EXPLORATION` 分区（可复现），但"抽到哪一件"走
//      `EquipmentDatabase.generateRandom(...)` / `templates.random()` /
//      `ItemDatabase.generateRandomPill(...)` 的**默认实参**
//      （`kotlin.random.Random.Default`——不入存档、不随档走 ⇒ 同一存档两次
//      开袋结果不同，不可复现）；
//   ② **物化 + 入仓**：按 kind 从模板库取模板、构造物品实例、经
//      `InventorySystem.addXxx` 入仓（`withTrackingSource("storage_bag")`
//      年度报告 + `StackableItemStore` 自动合并 + 溢出转邮件）。
//
// ②**必须留 Kotlin**，两条硬约束：
//   - CLAUDE.md 13.3 🔴：物品发放必须经 `InventorySystem.addXxx` 统一入口
//     （守卫测试 `InventoryAddPathGuardTest` 拦截手写 find+追加/截断）；
//   - 模板库（EquipmentDatabase / ManualDatabase / ItemDatabase / HerbDatabase）
//     在 Kotlin 注册表，C++ 不可复刻（batch-16 商人物品池「路线 B」同先例）。
//
// ⇒ 本事务只承接①：**消费 `EXPLORATION` 分区、产出确定性抽取描述符序列**。
// 抽签序（RNG 消费序）归 C++ 真相源后，同一存档的开袋产出**可复现**；
// Kotlin 据描述符物化并存库，双臂（native / 回退）消费**同一序列**。
//
// ## RNG 契约（对拍命门）
// - 分区 = `kExploration`（与 Kotlin 原实现的取源一致，保证抽取序不跨分区迁移）
// - 消费序**逐位对齐 Kotlin**：先 `nextInt(16)`（件数偏移）→ 再每件
//   `nextInt(7)`（kind）
// - 失败零抽取：`rarity` 越界/`bagId` 空 → 校验先行、**零 RNG 消费**、
//   零状态写入（failure 信封 → Kotlin 回退臂重执行原路径）
// - 本事务**不写任何游戏状态**（纯抽签），故"失败零写入"平凡满足；
//   状态推进仅体现为 EXPLORATION 分区 state 前移（RNG 抽取本身即副作用）
//
// ## 与 Kotlin 的数值一致性
// `nextInt(bound)` 为 Lemire 无偏回绝（`pcg_xsh_rr.h` 与 Kotlin
// `DeterministicRng.nextInt(bound)` 逐位一致，见该头注释）；故 C++ 产出的
// `count`/`kind` 序列与 Kotlin 同分区同起点抽取完全一致。
// ============================================================
#pragma once

#include <cstdint>
#include <string>
#include <vector>

#include "gamecore/rng/rng_manager.h"

namespace gamecore::system::storage_bag_tx {

/// 抽签件数下界（Kotlin `5 + rng.nextInt(16)` ⇒ [5, 20]）
inline constexpr int32_t kMinRewardCount = 5;
/// 抽签件数上界（含）；`nextInt` 的上界实参 = kRewardCountSpan
inline constexpr int32_t kMaxRewardCount = 20;
/// 件数抽取上界（`rng.nextInt(kRewardCountSpan)` 范围 [0,16) ⇒ 件数 [5,20]）
inline constexpr int32_t kRewardCountSpan = kMaxRewardCount - kMinRewardCount + 1;
/// 奖励种类数（Kotlin `rng.nextInt(7)`）——与 Kotlin `when(rng.nextInt(7))`
/// 的 0..6 分支一一对应，顺序即语义，不可改动
inline constexpr int32_t kRewardKindCount = 7;

/// 品阶合法区间（Kotlin `BattleRewardItem.rarity.coerceIn(1, 6)` 的同域）
inline constexpr int32_t kMinRarity = 1;
inline constexpr int32_t kMaxRarity = 6;

/// 单件抽取描述符：kind 为 [0, kRewardKindCount) 的种类下标。
/// Kotlin 侧按下标分派到模板库（0=装备 / 1=功法 / 2=丹药 / 3=草药 /
/// 4=种子 / 5=材料 / 6=灵石）——与 Kotlin `generateStorageBagRewards` 逐字同序。
struct RewardDraw {
    int32_t kind = 0;
};

struct OpenOutcome {
    bool ok = false;
    std::string errorType;
    std::string message;
    /// 抽取描述符序列（长度 ∈ [kMinRewardCount, kMaxRewardCount]）
    std::vector<RewardDraw> draws;
};

namespace detail {

/// 品阶是否在合法区间（Kotlin `coerceIn(1, 6)` 前的输入校验）
inline bool isValidRarity(int32_t rarity) {
    return rarity >= kMinRarity && rarity <= kMaxRarity;
}

}  // namespace detail

/// 开袋抽签：校验 → 逐件抽取描述符序列。
///
/// 校验链（**先于任何 RNG 消费**，失败零抽取）：
///   ① bagId 非空（空 id 在 Kotlin 侧即"袋不存在"早退，native 臂不承接查库）
///   ② rarity ∈ [1, 6]
///
/// 消费序（与 Kotlin `generateStorageBagRewards` 逐位一致）：
///   count = kMinRewardCount + rng.nextInt(kRewardCountSpan)
///   repeat(count) { kind = rng.nextInt(kRewardKindCount) }
///
/// @param rng EXPLORATION 分区 PRNG（调用方从 `RngManager` 取，本函数不自取，
///            保证"谁消费谁声明"、签名级可审）
/// @param bagId 储物袋实例 id（仅做非空校验，不查库——查库归 Kotlin）
/// @param rarity 袋品阶（决定奖励品阶，与 Kotlin 同域）
inline OpenOutcome openStorageBagTx(rng::DeterministicRng& rng,
                                    const std::string& bagId,
                                    int32_t rarity) {
    OpenOutcome out;
    if (bagId.empty()) {
        out.errorType = "BagNotFound";
        out.message = "储物袋 id 为空";
        return out;
    }
    if (!detail::isValidRarity(rarity)) {
        out.errorType = "InvalidRarity";
        out.message = "储物袋品阶越界 " + std::to_string(rarity) +
                      "（合法区间 [" + std::to_string(kMinRarity) + ", " +
                      std::to_string(kMaxRarity) + "]）";
        return out;
    }

    // ── 抽取段（校验已全通过）──────────────────────────────────────
    const int32_t count = kMinRewardCount + rng.nextInt(kRewardCountSpan);
    out.draws.reserve(static_cast<size_t>(count));
    for (int32_t i = 0; i < count; ++i) {
        RewardDraw draw;
        draw.kind = rng.nextInt(kRewardKindCount);
        out.draws.push_back(draw);
    }
    out.ok = true;
    return out;
}

}  // namespace gamecore::system::storage_bag_tx
