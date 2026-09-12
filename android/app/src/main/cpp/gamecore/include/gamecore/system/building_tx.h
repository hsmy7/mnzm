#pragma once

#include <algorithm>
#include <cstdint>
#include <string>
#include <unordered_set>
#include <utility>
#include <vector>

#include "gamecore/state/models.h"
#include "gamecore/system/economy.h"  // SpiritStoneWallet（拆除返还）

// ============================================================
// 建筑放置/迁移/升级/拆除事务（BuildingFacade AUTHORITATIVE 转发——
// C++ 唯一真相；batch-06 下沉，road_tx.h 同构）
//
// 承接 Kotlin 建筑编辑面四操作写者语义（判定序与 Kotlin 原实现逐字对齐）：
//   place  ：宗门等级 → 环界+门楼 → 限建数量 → 同宗占位重叠 → 灵石充足
//            → placedBuildings 增 + 灵石直扣 + guideCounters++
//            （Kotlin BuildingDelegate.doPlaceBuilding isPlacementAllowed 链）
//   move   ：存在性 → 环界+门楼 → gridX/gridY 改写（无重叠检查——与
//            moveBuildingDirect 逐字一致：移动语义允许与其他建筑重叠）
//   upgrade：存在性 → 宗门等级(≥中型) → 灵石差价 → 升级后占地 canFit
//            （环界+门楼+同宗除己重叠）→ 原地变换 + 差价直扣
//            （Kotlin BuildingUpgradeCalculator.checkUpgrade 链）
//   upgradeBatch：宗门等级整批 → 候选（同宗同 key，gridX/gridY/instanceId
//            稳定序）→ 可负担上限 → 逐座「升级中间态」增量校验（防相邻
//            扩地互斥）→ 计数返回（Kotlin upgradeBuildings 链）
//   remove ：逐实例存在性（未知跳过）→ 灵石返还（wallet.add——记年度账，
//            与 Kotlin spiritStoneWallet.add(refund, LOW, Refund) 同原语）
//            → placedBuildings 删（Kotlin removeBuildingsInternal 写面；
//            槽位/弟子派生清理由 Kotlin 门面残差承担——BuildingFeatureRegistry
//            槽组语义留 Kotlin）
// 任意校验失败零写入（信封 failure → Kotlin 回退原路径重执行校验链，
// 用户可见文案由 Kotlin 臂产出；本头 message 仅诊断用）。
//
// 零 RNG 论证：五事务均为纯确定性状态变换——校验链只读，变更段仅
// placedBuildings 增删改 + spiritStones 线性加减 + guideCounters 计数。
// 全链无 rng() 调用点（签名级证据：本头 API 不接受 RngManager/种子参数）；
// Kotlin 原路径（BuildingDelegate/BuildingFacadeImpl/BuildingUpgradeCalculator）
// 同样零 Random 消费（instanceId 由调用方 java.util.UUID 生成后传入——非
// 游戏 RNG 分区，抽取集不受影响）。抽取集为空集，RNG 红线平凡满足。
//
// 偏差登记（宗门过滤/占用集合组装归 Kotlin，road_tx.h §2.36 同口径）：
// C++ GridBuildingData 无 sectId 字段（models.h 禁改——README §3.3 协议，
// 对拍键集红线），限建数量/占位重叠/升级 canFit 的同宗过滤无法在 C++
// 复现。落地为 Kotlin 门面组装 sectScopedIds（目标宗门建筑 instanceId
// 集）随请求传入，C++ 以实例集做作用域判定——判定语义逐字一致。移动
// 目标查找按 instanceId 全表匹配（UUID 全局唯一，Kotlin 侧 instanceId+sectId
// 双重判据的第二重不改变命中集）。
//
// 钱包口径（逐字对齐被替换写者）：place/upgrade 为低阶直扣
//（`spiritStones - cost`，Kotlin 原路径不走 wallet.deduct——无年度账本
// 记录、无自动售卖），remove 返还走 SpiritStoneWallet::add（记年度账，
// 与 Kotlin wallet.add(refund, LOW, Refund) 同原语）。
//
// 占位几何（border/worldW/H/门楼矩形）与限建标志（unlimitedBuild/
// globallyUnique/requiredSectLevel）、造价、counterKey 均由 Kotlin 门面按
// GameConfig.SectMap / FixedSectGateway / BuildingFeatureRegistry /
// BuildingUpgradeRegistry 逐次传入——单一事实源留在 Kotlin，C++ 不重复
// 常量表。packed cell 编码与 Kotlin GridSystem.packCell 同式。
// ============================================================
namespace gamecore::system {
namespace building_tx {

/// 建筑事务结果（失败信封载体；spiritStonesAfter 供对拍/诊断）
struct BuildingTxOutcome {
    bool ok = false;
    const char* errorType = "";
    std::string message;
    int64_t spiritStonesAfter = 0;
    // upgradeBatch 载荷
    int32_t upgradedCount = 0;
    int32_t spaceBlockedCount = 0;
    // remove 载荷（实际移除的实例——Kotlin 残差清理以回执为准）
    std::vector<std::string> removedInstanceIds;
};

/// 占位几何（Kotlin GameConfig.SectMap + FixedSectGateway 门楼矩形传参）
struct PlaceGeom {
    int32_t border = 0;
    int32_t worldWidth = 0;
    int32_t worldHeight = 0;
    int32_t gateX = 0;
    int32_t gateY = 0;
    int32_t gateWidth = 0;
    int32_t gateHeight = 0;
};

/// packed cell（Kotlin GridSystem.packCell 同式：x 高 32 位 | y 低 32 位）
inline int64_t packCell(int32_t x, int32_t y) {
    return (static_cast<int64_t>(x) << 32) |
           (static_cast<int64_t>(y) & 0xFFFFFFFFLL);
}

/// 轴对齐矩形严格重叠（边界相接不算——Kotlin GridRect.overlaps 同式）
inline bool rectsOverlap(int32_t ax, int32_t ay, int32_t aw, int32_t ah,
                         int32_t bx, int32_t by, int32_t bw, int32_t bh) {
    return ax < bx + bw && ax + aw > bx && ay < by + bh && ay + ah > by;
}

/// 环界内且不占用门楼禁建格（Kotlin isInsideBuildableArea 同式——
/// 门楼矩形以参数传入，格判定与 FixedSectGateway.blockedCells 展开等价）
inline bool insideBuildableArea(int32_t x, int32_t y, int32_t w, int32_t h,
                                const PlaceGeom& g) {
    if (x < g.border || y < g.border) return false;
    if (x + w > g.worldWidth - g.border ||
        y + h > g.worldHeight - g.border) {
        return false;
    }
    for (int32_t cy = y; cy < y + h; ++cy) {
        for (int32_t cx = x; cx < x + w; ++cx) {
            if (cx >= g.gateX && cx < g.gateX + g.gateWidth &&
                cy >= g.gateY && cy < g.gateY + g.gateHeight) {
                return false;
            }
        }
    }
    return true;
}

/// 玩家宗门等级（Kotlin worldMapSects.find { isPlayerSect }?.level ?:
/// SectLevel.SMALL(0)）
inline int32_t playerSectLevel(const gamecore::state::GameData& gd) {
    for (const auto& s : gd.worldMapSects) {
        if (s.isPlayerSect) return s.level;
    }
    return 0;
}

/// 按 instanceId 查找已放置建筑（线性扫——建筑表规模 O(10²)，编辑面
/// 低频调用；与 Kotlin find { it.instanceId == id } 同式）
inline gamecore::state::GridBuildingData* findBuildingById(
    gamecore::state::GameData& gd, const std::string& instanceId) {
    for (auto& b : gd.placedBuildings) {
        if (b.instanceId == instanceId) return &b;
    }
    return nullptr;
}

/// 建筑放置事务：校验链全过后 placedBuildings 增 + 灵石直扣 + 引导计数；
/// 失败零写入。
/// @param sectScopedIds 目标宗门建筑 instanceId 集（Kotlin 组装——
///                      限建数量与占位重叠的同宗作用域，见头注释偏差登记）
inline BuildingTxOutcome placeBuildingTx(
    gamecore::state::GameData& gd, const gamecore::state::GridBuildingData& b,
    int64_t cost, int32_t requiredSectLevel, bool unlimitedBuild,
    bool globallyUnique, const std::string& counterKey, const PlaceGeom& geom,
    const std::vector<std::string>& sectScopedIds) {
    BuildingTxOutcome out;
    const std::unordered_set<std::string> scope(sectScopedIds.begin(),
                                                sectScopedIds.end());
    // 校验序 = Kotlin doPlaceBuilding 判定序逐字（等级 → 环界/门楼 →
    // [事务内] 限建 → 重叠 → 灵石）
    if (requiredSectLevel > 0 && playerSectLevel(gd) < requiredSectLevel) {
        out.errorType = "SectLevel";
        out.message = "宗门等级不足";
        return out;
    }
    if (!insideBuildableArea(b.gridX, b.gridY, b.width, b.height, geom)) {
        out.errorType = "OutOfBounds";
        out.message = "越界可建环之外或门楼禁建格";
        return out;
    }
    if (!unlimitedBuild) {
        bool exists = false;
        if (globallyUnique) {
            // 全局唯一：跨宗门按 displayName 计数（Kotlin isGloballyUnique 分支）
            for (const auto& o : gd.placedBuildings) {
                if (o.displayName == b.displayName) {
                    exists = true;
                    break;
                }
            }
        } else {
            // 同宗限建：displayName 匹配 + 同宗作用域
            for (const auto& o : gd.placedBuildings) {
                if (o.displayName == b.displayName &&
                    scope.count(o.instanceId) != 0) {
                    exists = true;
                    break;
                }
            }
        }
        if (exists) {
            out.errorType = "BuildLimit";
            out.message = "达到限建数量";
            return out;
        }
    }
    for (const auto& o : gd.placedBuildings) {
        if (scope.count(o.instanceId) == 0) continue;
        if (rectsOverlap(b.gridX, b.gridY, b.width, b.height, o.gridX,
                         o.gridY, o.width, o.height)) {
            out.errorType = "Overlap";
            out.message = "与已有建筑重叠";
            return out;
        }
    }
    if (gd.spiritStones < cost) {
        out.errorType = "Insufficient";
        out.message = "灵石不足";
        return out;
    }
    gd.placedBuildings.push_back(b);
    gd.spiritStones -= cost;
    ++gd.guideCounters[counterKey];
    out.ok = true;
    out.spiritStonesAfter = gd.spiritStones;
    return out;
}

/// 建筑迁移事务：存在性 → 环界+门楼（按建筑自身占地）→ 坐标改写；
/// 失败零写入。无重叠检查（与 Kotlin moveBuildingDirect 逐字一致）。
inline BuildingTxOutcome moveBuildingTx(gamecore::state::GameData& gd,
                                        const std::string& instanceId,
                                        int32_t newGridX, int32_t newGridY,
                                        const PlaceGeom& geom) {
    BuildingTxOutcome out;
    gamecore::state::GridBuildingData* target =
        findBuildingById(gd, instanceId);
    if (target == nullptr) {
        out.errorType = "NotFound";
        out.message = "建筑不存在";
        return out;
    }
    if (!insideBuildableArea(newGridX, newGridY, target->width, target->height,
                             geom)) {
        out.errorType = "OutOfBounds";
        out.message = "越界可建环之外或门楼禁建格";
        return out;
    }
    target->gridX = newGridX;
    target->gridY = newGridY;
    out.ok = true;
    out.spiritStonesAfter = gd.spiritStones;
    return out;
}

/// 建筑升级事务（单座）：存在性 → 宗门等级(≥中型) → 灵石差价 → canFit
/// → 原地变换（buildingId/displayName/width/height；instanceId/gridX/gridY
/// 不变）+ 差价直扣；失败零写入。
/// @param targetKey/targetDisplayName/targetW/targetH 升级目标特征
///（Kotlin BuildingUpgradeRegistry + BuildingFeatureRegistry 解析后传入）
/// @param sectScopedIds 目标建筑所在宗门建筑 instanceId 集（canFit 同宗
///                      除己重叠判定作用域）
inline BuildingTxOutcome upgradeBuildingTx(
    gamecore::state::GameData& gd, const std::string& instanceId,
    const std::string& targetKey, const std::string& targetDisplayName,
    int32_t targetW, int32_t targetH, int64_t cost, const PlaceGeom& geom,
    const std::vector<std::string>& sectScopedIds) {
    BuildingTxOutcome out;
    // 校验序 = Kotlin checkUpgrade 判定序逐字（等级 → 灵石 → 空间）
    gamecore::state::GridBuildingData* target =
        findBuildingById(gd, instanceId);
    if (target == nullptr) {
        out.errorType = "NotFound";
        out.message = "建筑不存在";
        return out;
    }
    if (playerSectLevel(gd) < 1 /* SectLevel.MEDIUM */) {
        out.errorType = "SectLevel";
        out.message = "需要宗门等级达到中型";
        return out;
    }
    if (gd.spiritStones < cost) {
        out.errorType = "Insufficient";
        out.message = "灵石不足";
        return out;
    }
    const std::unordered_set<std::string> scope(sectScopedIds.begin(),
                                                sectScopedIds.end());
    if (!insideBuildableArea(target->gridX, target->gridY, targetW, targetH,
                             geom)) {
        out.errorType = "SpaceBlocked";
        out.message = "升级后占地扩大，空间不足";
        return out;
    }
    for (const auto& o : gd.placedBuildings) {
        if (scope.count(o.instanceId) == 0 || o.instanceId == instanceId) {
            continue;
        }
        if (rectsOverlap(target->gridX, target->gridY, targetW, targetH,
                         o.gridX, o.gridY, o.width, o.height)) {
            out.errorType = "SpaceBlocked";
            out.message = "升级后占地扩大，空间不足";
            return out;
        }
    }
    target->buildingId = targetKey;
    target->displayName = targetDisplayName;
    target->width = targetW;
    target->height = targetH;
    gd.spiritStones -= cost;
    out.ok = true;
    out.upgradedCount = 1;
    out.spiritStonesAfter = gd.spiritStones;
    return out;
}

/// 建筑批量升级事务（一键升级）：宗门等级整批判定 → 候选（同宗同源 key，
/// gridX/gridY/instanceId 稳定序）→ 可负担上限（min(灵石/差价, maxCount)，
/// ≤0 失败）→ 逐座「升级中间态」增量校验（空间不足计 spaceBlocked 跳过，
/// 不中断）→ 按实升级数扣差价。失败零写入。
///（cost≤0 防御分支按可负担上限=maxCount 处理——Kotlin 原路径该分支为
/// 除零崩溃路径，注册表差价 coerceAtLeast(0) 恒非负且现有链恒正不触达。）
inline BuildingTxOutcome upgradeBuildingsTx(
    gamecore::state::GameData& gd, const std::string& sourceKey,
    const std::string& targetKey, const std::string& targetDisplayName,
    int32_t targetW, int32_t targetH, int32_t maxCount, int64_t cost,
    const PlaceGeom& geom, const std::vector<std::string>& sectScopedIds) {
    BuildingTxOutcome out;
    // 宗门等级门槛整批判定（Kotlin checkUpgradeSectLevel 先行）
    if (playerSectLevel(gd) < 1 /* SectLevel.MEDIUM */) {
        out.errorType = "SectLevel";
        out.message = "需要宗门等级达到中型";
        return out;
    }
    const std::unordered_set<std::string> scope(sectScopedIds.begin(),
                                                sectScopedIds.end());
    // 候选：同宗同源 key，稳定序（gridX/gridY/instanceId——Kotlin
    // upgradeCandidates sortedWith 同式）
    std::vector<gamecore::state::GridBuildingData*> candidates;
    for (auto& b : gd.placedBuildings) {
        if (b.buildingId == sourceKey && scope.count(b.instanceId) != 0) {
            candidates.push_back(&b);
        }
    }
    if (candidates.empty()) {
        out.errorType = "NoCandidates";
        out.message = "没有可升级的建筑";
        return out;
    }
    std::sort(candidates.begin(), candidates.end(),
              [](const gamecore::state::GridBuildingData* a,
                 const gamecore::state::GridBuildingData* b) {
                  if (a->gridX != b->gridX) return a->gridX < b->gridX;
                  if (a->gridY != b->gridY) return a->gridY < b->gridY;
                  return a->instanceId < b->instanceId;
              });
    // 灵石可负担上限（Kotlin affordable = (stones / cost).coerceAtMost(maxCount)）
    const int64_t stones = gd.spiritStones;
    const int64_t byStones = cost > 0 ? stones / cost : static_cast<int64_t>(maxCount);
    int32_t affordable = static_cast<int32_t>(
        byStones < static_cast<int64_t>(maxCount) ? byStones
                                                  : static_cast<int64_t>(maxCount));
    if (affordable <= 0) {
        out.errorType = "Insufficient";
        out.message = "灵石不足，无法升级";
        return out;
    }
    // 逐座升级（增量校验以 placedBuildings 当前态为准——先前升级已原地
    // 变换，与 Kotlin「升级中间态」working 列表同语义）
    int64_t totalCost = 0;
    for (gamecore::state::GridBuildingData* candidate : candidates) {
        if (out.upgradedCount >= affordable) break;
        const bool fits =
            insideBuildableArea(candidate->gridX, candidate->gridY, targetW,
                                targetH, geom) &&
            [&] {
                for (const auto& o : gd.placedBuildings) {
                    if (scope.count(o.instanceId) == 0 ||
                        o.instanceId == candidate->instanceId) {
                        continue;
                    }
                    if (rectsOverlap(candidate->gridX, candidate->gridY,
                                     targetW, targetH, o.gridX, o.gridY,
                                     o.width, o.height)) {
                        return false;
                    }
                }
                return true;
            }();
        if (!fits) {
            ++out.spaceBlockedCount;
            continue;
        }
        candidate->buildingId = targetKey;
        candidate->displayName = targetDisplayName;
        candidate->width = targetW;
        candidate->height = targetH;
        ++out.upgradedCount;
        totalCost += cost;
    }
    gd.spiritStones -= totalCost;
    out.ok = true;
    out.spiritStonesAfter = gd.spiritStones;
    return out;
}

/// 建筑批量拆除事务：逐实例存在性（未知跳过）→ 灵石返还（wallet.add——
/// 记年度账）→ placedBuildings 删。返回实际移除实例集（Kotlin 残差清理
/// 以回执为准）。永不失败（空 refunds 调用方先行守卫；未知实例跳过与
/// Kotlin mapNotNull 预解析同式）。
inline BuildingTxOutcome removeBuildingsTx(
    gamecore::state::GameData& gd,
    const std::vector<std::pair<std::string, int64_t>>& refunds) {
    BuildingTxOutcome out;
    for (const auto& [instanceId, refund] : refunds) {
        gamecore::state::GridBuildingData* target =
            findBuildingById(gd, instanceId);
        if (target == nullptr) continue;  // 幽灵实例防御
        if (refund > 0) {
            SpiritStoneWallet::add(gd, refund, SpiritStoneGrade::LOW, "Refund");
        }
        std::vector<gamecore::state::GridBuildingData> remaining;
        remaining.reserve(gd.placedBuildings.size() > 0
                              ? gd.placedBuildings.size() - 1
                              : 0);
        for (auto& b : gd.placedBuildings) {
            if (b.instanceId != instanceId) remaining.push_back(std::move(b));
        }
        gd.placedBuildings = std::move(remaining);
        out.removedInstanceIds.push_back(instanceId);
    }
    out.ok = true;
    out.spiritStonesAfter = gd.spiritStones;
    return out;
}

}  // namespace building_tx
}  // namespace gamecore::system
