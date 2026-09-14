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
    // W4-A·A1（w3-01）后：弟子操作面写者已全部获得 C++ 真相先行臂
    //（Kotlin 原路径降级为回退臂；信封残差 = lifeEvents 瞬态列回写 +
    // 偷盗判定钩子（执法域不下沉）+ Gate/Room 运行态）。通道本身（弟子行）
    // 的关闭动作按红线 13 统一在 W4-D 执行。
    Domain.DISCIPLE to listOf(
        // A1 已收口（native 臂就位，以下为回执驱动残差记录）：
        "GameEngineCoordination.kt:120/:138 rename/type — native 臂就位（1740/1741）；回退臂保留",
        "DiscipleFacadeImpl战斗Ops2.kt:93/:119/:138/:157/:271 赏赐/服药 — native 臂就位（1743/1744）；残差=日志草稿+偷盗钩子",
        "DiscipleStatusService.kt:225/:279/:373 派生同步 — native 臂就位（1747/1748，派生列唯一计算方 = C++）",
        "GameEngineBloodRefinementOps.kt:60 血炼启动 — native 臂就位（1746）；残差=Gate 释放+Room 清理",
        "GameEngineManualOps.kt:139 replaceManual — native 臂就位（1745）；残差=替换日志草稿",
        // A5 处置记录（形参化/分区化路线——ADR 阶段 3 口径）：
        "GameEngineCoordination.kt:99 updateDisciple — 交谈效果写者仍为 Kotlin（决策写不上沉，1850–1854 退段空置）；" +
            "决策类抽取已改 RngPartition.CHAT 引擎侧签发（GameEngineConversationDraw），文本变体走 PresentationRandom——" +
            "随机源达规（R1/R3），写入面留待弟子通道关闭决策（W4-D，红线 13）",
    ),
    Domain.PRODUCTION to listOf(
        "ProductionProcessorCleaOps3.kt:291 alignMirrorFromRepository — 月结前 repo→镜像整表对齐",
        "ProductionProcessor构筑Ops2.kt:405 validateAutoSlot / :250/:341 — 自动续炼槽位写者",
    ),
    Domain.LIFE_CYCLE to listOf(
        // A2（w3-02）已收口——批准/拒绝 native 臂就位，槽位清理双路核对，lifeEvent 分类登记：
        "GameEngine.kt:276 approveMarriageProposal — native 臂就位（batch-14 就绪地基 1592 接线）；" +
            "提议移除留 Kotlin（运行态字段）；NotFound 幽灵列边界回退原路径（batch-14 口径）",
        "GameEngine.kt:306 rejectMarriageProposal — native 臂就位（1750 MARRIAGE 拒绝事件直写）；" +
            "零弟子表写入/零 RNG/无失败臂；提议移除留 Kotlin",
        "DiscipleLifecycleProcessor.kt:489 clearDiscipleFromAllSlots — 双路核对结论：结算偷盗叛逃链" +
            "（含 11 类槽位清理）已由 C++ 结算直辖（month_settlement.h:1257），Kotlin 链为回退臂；" +
            "UI 丹药偷盗钩子（执法域）按 batch-14/A1 口径不下沉、Kotlin 原序执行；" +
            "本函数仅 Gate/Room 运行态残差（幂等，镜像零写入）——无协议列稳态写者",
        "DiscipleLifecycleManager.kt:100/:121 addLifeEvent/initializeLifeEvents — 分类②纯表现：" +
            "lifeEvents 为 @Ignore 非序列化列（DiscipleSerializer.kt:28，非协议字段），零协议列写者" +
            "（batch-14 审计同结论）——不进 C++、不需回导、不设关闭单元",
    ),
)
