#pragma once

/**
 * guide_reward_tx.h — 引导领奖事务（W4-D/D2 · w3-11 月年编排残差，ActionId
 * `GUIDE_REWARD_CLAIM_TX=1830`）。
 *
 * ## 承接的 Kotlin 写者（下沉前真源）
 * `GameEngineGuideOps.claimGuideReward`（GameEngine.kt 扩展，batch-18a 审计登记面，
 * ui-read-surface §4.4 BOUNDARY 域在册保留字段 `guideClaimedRewardIds`）：
 *   ① 幂等校验：taskId ∈ guideClaimedRewardIds ⇒ 零写入；
 *   ② 条件校验：GuideTaskRegistry.getTask(taskId).conditions 全部 isMet；
 *   ③ 凭据发放：`withOverflowMailSuppressed` 内 addStorageBag(凡品储物袋, rarity=1,
 *      quantity=task.rewardItemQuantity)——溢出抑制（失败不转邮件、不标记已领取，
 *      玩家清理仓库后可重试）；
 *   ④ id 生成：`java.util.UUID(systemRng.nextLong(), systemRng.nextLong())`——
 *      SYSTEM 分区 2×nextLong（UUID 构造器为原始位型，无 version/variant 位操作）；
 *   ⑤ 成功才追加 guideClaimedRewardIds。
 * UI 奖励卡片（RewardCardHost 飞出动画）为 Kotlin 平台效应，两臂同形，不入事务。
 *
 * ## 判定序与抽取序（红线对齐，B3 通道预检同款）
 * 校验链先行（①②⇒失败零抽取零写入）；随后**可行性预检**（干跑一次真实
 * StackableItemStore 合并/建槽决策——"仓库满"路径 0 抽取即失败信封，Kotlin 回退臂
 * 重跑时同样只消耗 2×nextLong 后失败 ⇒ 两臂抽取位终点逐位一致）；预检通过才
 * 2×nextLong 造 id 并提交写入。四象限（成功/失败 × flag ON/OFF）抽取增量恒 +2。
 *
 * ## 注册表双维护纪律
 * `registry()` 为 Kotlin `GuideTaskRegistry.ALL_TASKS`（core/domain/model/guide/
 * GuideTask.kt）的逐条复刻（25 任务；条件仅登记面实际使用的 9 类——
 * `DiscipleReachRealm` 未被任何任务引用，不复刻）。**Kotlin 注册表增删任务/条件
 * 时必须同批改本表**；跨语言一致性由 GTest `guide_reward_tx_test.cpp` 的
 * "注册表形状锚点"用例与 Kotlin `GuideRewardNativeTxGateTest` 共同守护。
 *
 * ## 零 Android 依赖 / 失败信封契约
 * 纯 C++20；业务失败以失败信封表达（UNKNOWN_TASK / ALREADY_CLAIMED /
 * CONDITIONS_NOT_MET / STORAGE_FULL），端口不抛异常。
 */

#include <cstdint>
#include <string>
#include <vector>

#include "gamecore/rng/rng_manager.h"
#include "gamecore/state/models.h"
#include "gamecore/system/inventory.h"

namespace gamecore::system::guide_tx {

/// 奖励常量（Kotlin StorageBag.TIER_NAMES[0] / claimGuideReward 硬编码 rarity=1）
constexpr int32_t kRewardRarity = 1;
constexpr const char* kRewardBagName = "凡品储物袋";
/// 建造累计计数键前缀（Kotlin GuideCounterKeys.buildingBuiltKey）
constexpr const char* kBuildingBuiltKeyPrefix = "buildingBuilt:";

// ── 引导条件（Kotlin GuideCondition 结构化复刻）─────────────────────

enum class ConditionKind {
    kBuildingCount,            /// 建造数量：max(累计建造计数, 当前存量)（升级/拆除不回退）
    kElderAppointed,           /// 长老单值槽占用（field = ElderSlots 字段名）
    kDirectDiscipleActive,     /// 亲传/执事列表占用数（field = 列表字段键）
    kSlotFilledCount,          /// 集合槽位填充数（field = 槽位列表字段名）
    kCumulativeCounter,        /// guideCounters 累计计数（field = 计数器键）
    kPlantCropOnce,            /// 种植过灵植（spiritFieldPlants 非空）
    kBloodRefinementCompleted, /// 血炼完成（bloodRefinements 键数）
    kPatrolBeastDefeated,      /// 巡逻击败妖兽累计计数
    kMissionCompleted,         /// 宗门任务完成累计计数
};

struct Condition {
    ConditionKind kind;
    std::string field;     /// 槽位字段名 / 计数器键 / 建筑显示名（按 kind 解释）
    int64_t target = 0;    /// 目标值（Kotlin targetValue）
};

struct Task {
    int32_t id;
    std::vector<Condition> conditions;
    int32_t rewardQuantity;  /// Kotlin GuideTask.rewardItemQuantity（现有 25 任务全为默认 2）
};

/// 任务注册表（Kotlin GuideTaskRegistry.ALL_TASKS 逐条复刻——25 任务）。
inline const std::vector<Task>& registry() {
    static const std::vector<Task> kRegistry = {
        {1, {{ConditionKind::kBuildingCount, "灵矿场", 10},
             {ConditionKind::kCumulativeCounter, "miningOutput", 100000}}, 2},
        {2, {{ConditionKind::kDirectDiscipleActive, "spiritMineDeacon", 1},
             {ConditionKind::kCumulativeCounter, "miningOutput", 300000}}, 2},
        {3, {{ConditionKind::kBuildingCount, "灵田", 5},
             {ConditionKind::kPlantCropOnce, "", 1}}, 2},
        {4, {{ConditionKind::kBuildingCount, "灵植阁", 3},
             {ConditionKind::kCumulativeCounter, "herbsHarvested", 5}}, 2},
        {5, {{ConditionKind::kBuildingCount, "炼丹炉", 3},
             {ConditionKind::kCumulativeCounter, "alchemyCompleted", 3}}, 2},
        {6, {{ConditionKind::kBuildingCount, "锻造坊", 3},
             {ConditionKind::kCumulativeCounter, "forgeCompleted", 3}}, 2},
        {7, {{ConditionKind::kBuildingCount, "天枢殿", 1},
             {ConditionKind::kElderAppointed, "viceSectMaster", 1}}, 2},
        {8, {{ConditionKind::kBuildingCount, "天枢殿", 1},
             {ConditionKind::kCumulativeCounter, "autoMineActivated", 1}}, 2},
        {9, {{ConditionKind::kBuildingCount, "天枢殿", 1},
             {ConditionKind::kCumulativeCounter, "autoPlantActivated", 1}}, 2},
        {10, {{ConditionKind::kBuildingCount, "天枢殿", 1},
              {ConditionKind::kCumulativeCounter, "autoProductionActivated", 1}}, 2},
        {11, {{ConditionKind::kBuildingCount, "天枢殿", 1},
              {ConditionKind::kCumulativeCounter, "policyActivated", 1}}, 2},
        {12, {{ConditionKind::kBuildingCount, "藏经阁", 1},
              {ConditionKind::kSlotFilledCount, "librarySlots", 3}}, 2},
        {13, {{ConditionKind::kBuildingCount, "执法堂", 1},
              {ConditionKind::kElderAppointed, "lawEnforcementElder", 1}}, 2},
        {14, {{ConditionKind::kBuildingCount, "执法堂", 1},
              {ConditionKind::kDirectDiscipleActive, "lawEnforcementDisciples", 1}}, 2},
        {15, {{ConditionKind::kBuildingCount, "任务阁", 1},
              {ConditionKind::kMissionCompleted, "", 3}}, 2},
        {16, {{ConditionKind::kBuildingCount, "问道塔", 1},
              {ConditionKind::kElderAppointed, "outerElder", 1}}, 2},
        {17, {{ConditionKind::kBuildingCount, "问道塔", 1},
              {ConditionKind::kDirectDiscipleActive, "preachingMasters", 1}}, 2},
        {18, {{ConditionKind::kBuildingCount, "青云塔", 1},
              {ConditionKind::kElderAppointed, "innerElder", 1}}, 2},
        {19, {{ConditionKind::kBuildingCount, "青云塔", 1},
              {ConditionKind::kDirectDiscipleActive, "qingyunPreachingMasters", 1}}, 2},
        {20, {{ConditionKind::kBuildingCount, "巡视楼", 1},
              {ConditionKind::kPatrolBeastDefeated, "", 1}}, 2},
        {21, {{ConditionKind::kBuildingCount, "初级单人住所", 5},
              {ConditionKind::kSlotFilledCount, "residenceSlots", 5}}, 2},
        {22, {{ConditionKind::kBuildingCount, "初级多人住所", 3},
              {ConditionKind::kSlotFilledCount, "residenceSlots", 12}}, 2},
        {23, {{ConditionKind::kBuildingCount, "仓库", 3},
              {ConditionKind::kSlotFilledCount, "warehouseGarrisons", 1}}, 2},
        {24, {{ConditionKind::kBuildingCount, "血炼池", 1},
              {ConditionKind::kBloodRefinementCompleted, "", 1}}, 2},
        {25, {{ConditionKind::kBuildingCount, "监牢", 1},
              {ConditionKind::kCumulativeCounter, "discipleImprisoned", 1}}, 2},
    };
    return kRegistry;
}

/// 按 id 查任务（Kotlin GuideTaskRegistry.getTask；未注册 ⇒ nullptr）。
inline const Task* findTask(int32_t id) {
    for (const auto& t : registry()) {
        if (t.id == id) return &t;
    }
    return nullptr;
}

/// 长老单值槽占用人（Kotlin GuideCondition.ElderAppointed.elderSlotOccupant：
/// 按字段名取对应槽位，未知字段为空串）。
inline const std::string& elderSlotOccupant(const state::ElderSlots& slots,
                                            const std::string& field) {
    static const std::string kEmpty;
    if (field == "viceSectMaster") return slots.viceSectMaster;
    if (field == "outerElder") return slots.outerElder;
    if (field == "innerElder") return slots.innerElder;
    if (field == "preachingElder") return slots.preachingElder;
    if (field == "lawEnforcementElder") return slots.lawEnforcementElder;
    if (field == "recruitingElder") return slots.recruitingElder;
    if (field == "qingyunPreachingElder") return slots.qingyunPreachingElder;
    return kEmpty;
}

/// 亲传/执事列表占用数（Kotlin DirectDiscipleActive.currentValue：字段键 → 列表，
/// 数 discipleId 非空项；未知键为 0）。
inline int64_t directDiscipleActiveCount(const state::ElderSlots& slots,
                                         const std::string& field) {
    const std::vector<state::DirectDiscipleSlot>* list = nullptr;
    if (field == "spiritMineDeacon") list = &slots.spiritMineDeaconDisciples;
    else if (field == "preachingMasters") list = &slots.preachingMasters;
    else if (field == "lawEnforcementDisciples") list = &slots.lawEnforcementDisciples;
    else if (field == "qingyunPreachingMasters") list = &slots.qingyunPreachingMasters;
    if (list == nullptr) return 0;
    int64_t n = 0;
    for (const auto& s : *list) {
        if (!s.discipleId.empty()) ++n;
    }
    return n;
}

/// 集合槽位填充数（Kotlin SlotFilledCount.currentValue：五列表同判定）。
inline int64_t slotFilledCount(const state::GameState& state,
                               const std::string& field) {
    const auto& gd = state.gameData;
    if (field == "librarySlots") {
        int64_t n = 0;
        for (const auto& s : gd.librarySlots) if (!s.discipleId.empty()) ++n;
        return n;
    }
    if (field == "residenceSlots") {
        int64_t n = 0;
        for (const auto& s : gd.residenceSlots) if (!s.discipleId.empty()) ++n;
        return n;
    }
    if (field == "patrolSlots") {
        int64_t n = 0;
        for (const auto& s : gd.patrolSlots) if (!s.discipleId.empty()) ++n;
        return n;
    }
    if (field == "warehouseGarrisons") {
        int64_t n = 0;
        for (const auto& s : gd.warehouseGarrisons) if (!s.discipleId.empty()) ++n;
        return n;
    }
    if (field == "spiritMineSlots") {
        int64_t n = 0;
        for (const auto& s : gd.spiritMineSlots) if (!s.discipleId.empty()) ++n;
        return n;
    }
    return 0;
}

/// 条件当前值（Kotlin GuideCondition.currentValue 逐字对齐）。
inline int64_t currentValue(const state::GameState& state, const Condition& c) {
    const auto& gd = state.gameData;
    switch (c.kind) {
        case ConditionKind::kBuildingCount: {
            // 累计语义：max(累计建造计数, 当前存量)——升级/拆除不回退引导进度
            const auto it = gd.guideCounters.find(
                std::string(kBuildingBuiltKeyPrefix) + c.field);
            const int64_t cumulative = (it != gd.guideCounters.end()) ? it->second : 0;
            int64_t current = 0;
            for (const auto& b : gd.placedBuildings) {
                if (b.displayName == c.field) ++current;
            }
            return cumulative > current ? cumulative : current;
        }
        case ConditionKind::kElderAppointed: {
            const auto& occupant = elderSlotOccupant(gd.elderSlots, c.field);
            return occupant.empty() ? 0 : 1;
        }
        case ConditionKind::kDirectDiscipleActive:
            return directDiscipleActiveCount(gd.elderSlots, c.field);
        case ConditionKind::kSlotFilledCount:
            return slotFilledCount(state, c.field);
        case ConditionKind::kCumulativeCounter: {
            const auto it = gd.guideCounters.find(c.field);
            return (it != gd.guideCounters.end()) ? it->second : 0;
        }
        case ConditionKind::kPlantCropOnce:
            return gd.spiritFieldPlants.empty() ? 0 : 1;
        case ConditionKind::kBloodRefinementCompleted:
            return static_cast<int64_t>(gd.bloodRefinements.size());
        case ConditionKind::kPatrolBeastDefeated: {
            const auto it = gd.guideCounters.find("patrolBeastDefeated");
            return (it != gd.guideCounters.end()) ? it->second : 0;
        }
        case ConditionKind::kMissionCompleted: {
            const auto it = gd.guideCounters.find("missionsCompleted");
            return (it != gd.guideCounters.end()) ? it->second : 0;
        }
    }
    return 0;
}

/// 条件是否满足（Kotlin isMet：currentValue >= targetValue）。
inline bool isMet(const state::GameState& state, const Condition& c) {
    return currentValue(state, c) >= c.target;
}

/// 无符号 64 位整数的零填充十六进制（Java UUID.digits 等价）。
inline std::string uuidDigits(uint64_t val, int digits) {
    static const char* kHex = "0123456789abcdef";
    std::string out(static_cast<std::size_t>(digits), '0');
    for (int i = digits - 1; i >= 0; --i) {
        out[static_cast<std::size_t>(i)] = kHex[val & 0xFULL];
        val >>= 4;
    }
    return out;
}

/// UUID 字符串（Kotlin `java.util.UUID(msb, lsb).toString()` 复刻——
/// 构造器为原始位型（无 version/variant 位操作）；
/// 注意两侧 nextLong() 均为非负（nextInt() & Long.MAX_VALUE）⇒ 符号位恒 0。
inline std::string formatUuid(int64_t msb, int64_t lsb) {
    const uint64_t m = static_cast<uint64_t>(msb);
    const uint64_t l = static_cast<uint64_t>(lsb);
    return uuidDigits(m >> 32, 8) + "-" + uuidDigits(m >> 16, 4) + "-" +
           uuidDigits(m, 4) + "-" + uuidDigits(l >> 48, 4) + "-" +
           uuidDigits(l, 12);
}

/// 领奖事务结果（claimed=false 时 errorCode 非 null）。
struct ClaimOutcome {
    bool claimed = false;
    const char* errorCode = nullptr;  /// UNKNOWN_TASK/ALREADY_CLAIMED/CONDITIONS_NOT_MET/STORAGE_FULL
    std::string itemId;               /// 成功时的奖励堆叠 id（首个分块保留传入 id——Kotlin 同语义）
};

/**
 * 引导领奖事务（Kotlin claimGuideReward 事务内段等价移植）。
 *
 * 判定序：任务存在 → 未领取 → 条件全满足 → 可行性预检（干跑）→
 * SYSTEM 2×nextLong 造 UUID → addStorageBag（溢出抑制）→ 标记已领取。
 * 任一校验失败 ⇒ 零状态变更 + 零抽取 + 失败码（见文件头"判定序与抽取序"）。
 *
 * @param state     完整游戏状态（就地修改）
 * @param systemRng SYSTEM 分区流（rng.getRng(RngPartition::kSystem)）
 * @param taskId    任务 id（Kotlin GuideTaskRegistry 注册面）
 */
inline ClaimOutcome claimGuideRewardTx(state::GameState& state,
                                       rng::DeterministicRng& systemRng,
                                       int32_t taskId) {
    ClaimOutcome out;
    const Task* task = findTask(taskId);
    if (task == nullptr) {
        out.errorCode = "UNKNOWN_TASK";
        return out;
    }
    auto& gd = state.gameData;
    const auto& claimed = gd.guideClaimedRewardIds;
    if (std::find(claimed.begin(), claimed.end(), taskId) != claimed.end()) {
        out.errorCode = "ALREADY_CLAIMED";
        return out;
    }
    for (const auto& c : task->conditions) {
        if (!isMet(state, c)) {
            out.errorCode = "CONDITIONS_NOT_MET";
            return out;
        }
    }
    // 可行性预检（干跑，零抽取零写入）：以当前 storageBags 副本跑一次真实
    // add——溢出抑制语义下失败即整体失败信封；预检先行使"仓库满"路径两臂
    // 抽取位终点一致（flag-OFF 臂该路径同样"抽取后发放失败、不标记"）。
    // 注：storageBags 为 GameState 顶层集合（与 equipmentStacks 等仓库面同层）。
    state::StorageBag probeItem;
    probeItem.name = kRewardBagName;
    probeItem.rarity = kRewardRarity;
    probeItem.quantity = task->rewardQuantity;
    {
        auto probeBags = state.storageBags;
        StackableItemStore<state::StorageBag> probeStore(
            probeBags, storageBagKey, getMaxStackSize("storageBag"),
            []() { return 64; });  // addStorageBag 同款独立槽位预算
        const auto probe = probeStore.add(probeItem);
        if (probe.status == InventoryStatus::kFailure ||
            probe.status == InventoryStatus::kPartial) {
            out.errorCode = "STORAGE_FULL";
            return out;
        }
    }
    // SYSTEM 分区 2×nextLong 造 id（与 Kotlin UUID(msb, lsb) 抽取位逐位一致）
    const std::string id = formatUuid(systemRng.nextLong(), systemRng.nextLong());
    probeItem.id = id;
    OverflowMailCollector overflowMail;  // 溢出抑制——凭据类失败不转邮件
    const auto result = addStorageBag(state, probeItem, overflowMail,
                                      "guide_reward", true);
    if (result.status == InventoryStatus::kFailure ||
        result.status == InventoryStatus::kPartial) {
        // 预检后仍失败 ⇒ 防御性失败信封（正常流不可达）
        out.errorCode = "STORAGE_FULL";
        return out;
    }
    gd.guideClaimedRewardIds.push_back(taskId);
    out.claimed = true;
    out.itemId = id;
    return out;
}

}  // namespace gamecore::system::guide_tx
