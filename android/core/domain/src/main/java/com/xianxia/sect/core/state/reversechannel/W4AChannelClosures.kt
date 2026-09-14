package com.xianxia.sect.core.state.reversechannel

import com.xianxia.sect.core.state.ReverseChannelPolicy
import com.xianxia.sect.core.state.ReverseChannelPolicy.Domain

/**
 * W4-A 批次（弟子与建设轴）的反向通道关闭数据。
 *
 * ## 为什么按批切文件（W4-00 并行前置批）
 * 三个并行批次在收尾各自域时都需要「新增关闭单元 + 摘除在册保留字段 + 改写域级结论」。
 * 原始形态是 `ReverseChannelPolicy` 内三处**单一列表字面量** ⇒ 三批必然编辑同一段文本。
 * 切分后每批只改自己这一个文件，`ReverseChannelPolicy` 只做聚合、此后冻结。
 * 见 `docs/parallel-batches-w4/README.md` §3.1 项 5 与 §5.3。
 *
 * 🔴 **只有 W4-A 可写本文件**（域：DISCIPLE / LIFE_CYCLE / BUILDING / ROAD / PRODUCTION）。
 */

/** 本批域的**已关闭**传输单元（逐域关闭清单的 W4-A 分片）。 */
internal val w4AClosedUnits: List<ReverseChannelPolicy.ClosedUnit> = listOf(
    // ROAD
    gameDataField(Domain.ROAD, "roads"),
    // DISCIPLE
    gameDataField(Domain.DISCIPLE, "bloodRefinements"),
    gameDataField(Domain.DISCIPLE, "bloodRefinementBonusTotals"),
    gameDataField(Domain.DISCIPLE, "bloodRefinementPctTotals"),
    gameDataField(Domain.DISCIPLE, "pendingTraitAdds"),
    gameDataField(Domain.DISCIPLE, "battleTeamsInitialized"),
    // PRODUCTION
    gameDataField(Domain.PRODUCTION, "unlockedManuals"),
    gameDataField(Domain.PRODUCTION, "unlockedRecipes"),
)

/** 本批域的**在册保留**gameData 字段（不可关闭；口径见 `ReverseChannelPolicy.transportedGameDataFields`）。 */
internal val w4ARetainedGameDataFields: Set<String> = linkedSetOf(
    // 弟子槽位与状态派生（藏经阁/长老单值槽/驻军）
    "elderSlots", "librarySlots", "warehouseGarrisons",
    // 生产槽位与灵田（生产域稳态写者）
    "productionSlots", "spiritFieldPlants",
    // 弟子生命周期/关系/日志
    "gameEventRecords", "battleTeams", "manualProficiencies",
    // 血炼运行态（DISCIPLE 域稳态写者）
    "activeBloodRefinements",
    // 建筑（BUILDING 域稳态写者）
    "placedBuildings",
)

/** 本批域的**域级审计结论证据**（`文件:行 函数` 形式；CLOSED 域必须为空）。 */
internal val w4ADomainEvidence: Map<Domain, List<String>> = mapOf(
    Domain.BUILDING to listOf(
        "BuildingNativeTx.kt:163 removeBuildings — native 拆除后的槽位/弟子释放残差（C++ 模型无槽位字段）",
        "BuildingFacadeImpl同步Ops.kt:281 — 月变没收建筑（GameEngineCoreMonthOps.kt:95 无 native 臂）",
        "BuildingDelegate.kt:145 placeSlotsResidual — native 放置成功后的槽位派生残差",
    ),
    Domain.ROAD to listOf(
        "RoadFacadeImpl.kt:67/:85 placeRoad/removeRoad — native 臂后的槽位/回执残差（road_tx 已下沉）",
    ),
    Domain.DISCIPLE to listOf(
        "GameEngineCoordination.kt:99/:120/:138 — 弟子属性/改名/类型直改（无 native 臂）",
        "DiscipleFacadeImpl战斗Ops2.kt:93/:119/:138/:157/:271 — 赏赐/服药 UI 直调（无 native 臂）",
        "DiscipleStatusService.kt:225/:279/:373 — 槽位状态派生同步族（稳态）",
        "DiscipleSlotManager.kt:59、DiscipleLifecycleNativeTx.kt:132 — native 事务后残差",
        "GameEngineBloodRefinementOps.kt:60 startBloodRefinementAtomic — 血炼启动清槽（UI 直改）",
        "GameEngineManualOps.kt:139 replaceManual — 功法替换（活 UI，无 native 臂；2026-09-15 核查新增）",
    ),
    Domain.PRODUCTION to listOf(
        "ProductionProcessorCleaOps3.kt:291 alignMirrorFromRepository — 月结前 repo→镜像整表对齐",
        "ProductionProcessor构筑Ops2.kt:405 validateAutoSlot / :250/:341 — 自动续炼槽位写者",
    ),
    Domain.LIFE_CYCLE to listOf(
        "DiscipleLifecycleProcessor.kt:489 — 弟子槽位清理（偷盗叛逃事务内 + 永久属性丹两路稳态）",
        "DiscipleLifecycleManager.kt:100/:121 — 月变自动装备 lifeEvent / UI 查看补写",
        "GameEngine.kt:277/:306 婚姻提议审批/拒绝（C++ 事务未接线，玩家审批只走 Kotlin）",
    ),
)
