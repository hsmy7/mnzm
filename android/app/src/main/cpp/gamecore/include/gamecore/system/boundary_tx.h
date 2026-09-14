// ============================================================
// boundary_tx.h — 月年边界编排族事务（batch-18a）
//
// ui-read-surface §4.1「月年编排 —— 边界效果、政策开关、guide」中 **guide 计数面**
// UI/读档操作面写者下沉（语义权威 = 各 Kotlin 源文件，判定序逐字对齐）。
//
// 写者审计结论（§2.49 登记表）——本头只承三处**真正 Kotlin 独占且活路径**的
// 计数写者（ActionId 1670–1672，零 RNG 纯确定性变换）：
//  - GameEngine.incrementGuideCounter（任务完成累计——GameEngineMissionOps 调用）
//  - GameEngine.batchUpdateAutoAssignAndGuide（自动分配策略 + 三激活计数合并写）
//  - GameEngine.backfillBuildingGuideCounters（读档 Step 3.6 建造计数回填，幂等）
//
// 同批审计判定的**不下沉项**（登记，见 handover §2.49）：
//  - advanceMonth / advanceYear（CultivationEventProcessor + CultivationService
//    薄委托）：**零调用者死代码**（changelog_entries 已记载"未被游戏主循环调用
//    （死代码）"）——下沉等于给死代码造事务，删除属"待拍板·死代码删除"项。
//  - updateDiscipleHpMpAfterBattle：零调用者死代码（战斗伤亡写回已由
//    exploration_tx.h `writeBackExplorationCasualties` / S5 结算链承担）。
//  - checkGameOverCondition(state)：月变残留链**事务内步骤**（外层已持
//    stateStore.update），C++ 权威已存在（month_settlement.h 步骤 8e）；
//    无参重载零调用者——包成独立事务会与月变单事务边界冲突。
//  - updateGameData / updateDisciple / renameDisciple / changeDiscipleTypeAtomic /
//    toggleWatchItem（GameEngineCoordination）：**通用写入口**，按批文 §2.2 保守
//    路线只审计登记，不为它们造事务（随消费方下沉自然收敛）。
//  - claimGuideReward：条件判定 `GuideTask.conditions.isMet(gd)` 在 Kotlin 引导
//    注册表（GuideTaskRegistry / 各 GuideCondition 子类，C++ 无对应物）且出生随机
//    走 SYSTEM 分区双 nextLong（UUID）——整段留 Kotlin 回退臂。
//  - drainYearlyOpsQueue / flushYearlyOpsQueue / clearYearlyOpsQueue：
//    `YearlyOpsQueue` 为 Kotlin 纯内存结构（不进 JSON 协议，无 C++ 对应物），
//    队列语义 = Kotlin 侧残差，只审计登记。
//
// RNG 契约（对拍命门）：
//  - 本头三事务**全部零抽取**（签名级：不接受 rng 参数）——与 Kotlin 臂
//    （纯 map 写 / 纯计数）逐位同源；零 RNG 族给全分区快照差分。
//
// 键名契约：引导计数键为 Kotlin [GuideCounterKeys] 镜像常量——键名漂移 =
// 引导进度丢失（玩家可见），故集中声明 + GTest 用真实键断言。
// ============================================================
#pragma once

#include <algorithm>
#include <cstdint>
#include <map>
#include <string>
#include <unordered_set>
#include <vector>

#include "gamecore/state/models.h"

namespace gamecore::system::boundary_tx {

using gamecore::state::GameState;

// ── 结果信封（失败零写入；failure → Kotlin 回退原路径重执行校验链）────────

struct TxResult {
    bool ok = false;
    std::string errorType;
    std::string message;
};

// ── 引导计数键（Kotlin GuideCounterKeys 镜像——漂移即引导进度丢失）────────

inline constexpr const char* kAutoMineActivatedKey = "autoMineActivated";
inline constexpr const char* kAutoPlantActivatedKey = "autoPlantActivated";
inline constexpr const char* kAutoProductionActivatedKey = "autoProductionActivated";
/// 建筑建造累计计数键前缀（Kotlin GuideCounterKeys.buildingBuiltKey = "buildingBuilt:$name"）
inline constexpr const char* kBuildingBuiltKeyPrefix = "buildingBuilt:";

/// 计数器读（缺省 0 —— Kotlin `map[key] ?: 0L`）
inline int64_t counterOrZero(const state::GameData& gd, const std::string& key) {
    const auto it = gd.guideCounters.find(key);
    return it == gd.guideCounters.end() ? 0 : it->second;
}

// ── 事务 1：引导计数递增（GameEngine.incrementGuideCounter）───────────────
//
// Kotlin 判定序：`currentCount = guideCounters[key] ?: 0L` → 写回
// `counters + (key to currentCount + amount)`。无键校验（空键同样落库）、
// 无符号守卫（amount 为调用方常量 1，本事务按签名透传）——逐字对齐。
// 幂等性：非幂等（每次 +amount），与 Kotlin 同。
struct GuideCounterIncrementOutcome {
    TxResult base;
    int64_t newValue = 0;
};

inline GuideCounterIncrementOutcome incrementGuideCounterTx(GameState& state,
                                                            const std::string& key,
                                                            int64_t amount) {
    GuideCounterIncrementOutcome out;
    auto& counters = state.gameData.guideCounters;
    out.newValue = counterOrZero(state.gameData, key) + amount;
    counters[key] = out.newValue;
    out.base.ok = true;
    return out;
}

// ── 事务 2：自动分配策略 + 引导计数合并写 ────────────────────────────────
//
// GameEngine.batchUpdateAutoAssignAndGuide：`sectPolicies` 整包替换为调用方
// 组装的 newPolicies（UI 侧 `gd.sectPolicies.copy(...16 字段...)`），并在同一
// 事务内按三个激活标志增量三计数。
//
// 口径：`oldPolicies` 形参在 Kotlin 侧为语义形参（@Suppress UnusedParameter），
// C++ 侧同样不消费——激活判定（mineActivated 等）由调用方在事务外先行计算后
// 传入（AutoAssignDelegate 判定序：`spec.focused && !gd.sectPolicies.<sameField>`）。
struct AutoAssignGuideOutcome {
    TxResult base;
    int64_t autoMineActivated = 0;
    int64_t autoPlantActivated = 0;
    int64_t autoProductionActivated = 0;
    /// 仅计数面是否变化（策略整包替换的相等性判定不参与——SectPolicies 无
    /// operator==，且 Kotlin 侧同样不比较，仅整体覆盖写）
    bool countersChanged = false;
};

inline AutoAssignGuideOutcome autoAssignGuideBatchTx(GameState& state,
                                                     const state::SectPolicies& newPolicies,
                                                     bool mineActivated,
                                                     bool plantActivated,
                                                     bool productionActivated) {
    AutoAssignGuideOutcome out;
    auto& gd = state.gameData;
    std::map<std::string, int64_t> counters = gd.guideCounters;
    if (mineActivated) {
        const int64_t cur = counterOrZero(gd, kAutoMineActivatedKey);
        counters[kAutoMineActivatedKey] = cur + 1;
    }
    if (plantActivated) {
        const int64_t cur = counterOrZero(gd, kAutoPlantActivatedKey);
        counters[kAutoPlantActivatedKey] = cur + 1;
    }
    if (productionActivated) {
        const int64_t cur = counterOrZero(gd, kAutoProductionActivatedKey);
        counters[kAutoProductionActivatedKey] = cur + 1;
    }
    out.countersChanged = (counters != gd.guideCounters);
    gd.sectPolicies = newPolicies;
    gd.guideCounters = std::move(counters);
    out.autoMineActivated = counterOrZero(gd, kAutoMineActivatedKey);
    out.autoPlantActivated = counterOrZero(gd, kAutoPlantActivatedKey);
    out.autoProductionActivated = counterOrZero(gd, kAutoProductionActivatedKey);
    out.base.ok = true;
    return out;
}

// ── 事务 3：建筑建造累计计数回填（GameEngine.backfillBuildingGuideCounters）──
//
// Kotlin computeBuildingCounterBackfill 等价（纯函数，读档 Step 3.6）：
//   `placedBuildings.map{displayName}.distinct()` 逐个判据——
//   键 = "buildingBuilt:" + displayName；值 = max(现有计数, 该 displayName 存量数)；
//   **max 语义**：存量不大于现有计数时不动（升级/拆除不回退引导进度）。
// 幂等：二次执行零变更（changed=false）——GTest 断言。
// 仅当回填结果与现有计数不同才写回（Kotlin `if (backfilled != guideCounters)`）。
struct BuildingGuideBackfillOutcome {
    TxResult base;
    bool changed = false;
    int32_t backfilledKeys = 0;
};

inline BuildingGuideBackfillOutcome backfillBuildingGuideCountersTx(GameState& state) {
    BuildingGuideBackfillOutcome out;
    auto& gd = state.gameData;
    std::map<std::string, int64_t> backfilled = gd.guideCounters;

    // 首次出现序去重（Kotlin .distinct() 保序；map 结果与序无关，此处仅为
    // 避免 O(n²) 重扫同一显示名）
    std::unordered_set<std::string> seen;
    for (const auto& building : gd.placedBuildings) {
        const std::string& name = building.displayName;
        if (!seen.insert(name).second) continue;
        const std::string key = std::string(kBuildingBuiltKeyPrefix) + name;
        int64_t current = 0;
        for (const auto& b : gd.placedBuildings) {
            if (b.displayName == name) ++current;
        }
        int64_t& slot = backfilled[key];
        if (current > slot) {
            slot = current;
            ++out.backfilledKeys;
        }
    }

    if (backfilled != gd.guideCounters) {
        gd.guideCounters = std::move(backfilled);
        out.changed = true;
    } else {
        out.changed = false;
    }
    out.base.ok = true;
    return out;
}

}  // namespace gamecore::system::boundary_tx
