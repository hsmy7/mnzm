package com.xianxia.sect.core.state.reversechannel

import com.xianxia.sect.core.state.ReverseChannelPolicy
import com.xianxia.sect.core.state.ReverseChannelPolicy.Domain

/**
 * W4-B 批次（内政与经济运营轴）的反向通道关闭数据。
 *
 * ## 为什么按批切文件（W4-00 并行前置批）
 * 三个并行批次在收尾各自域时都需要「新增关闭单元 + 摘除在册保留字段 + 改写域级结论」。
 * 原始形态是 `ReverseChannelPolicy` 内三处**单一列表字面量** ⇒ 三批必然编辑同一段文本。
 * 切分后每批只改自己这一个文件，`ReverseChannelPolicy` 只做聚合、此后冻结。
 * 见 `docs/parallel-batches-w4/README.md` §3.1 项 5 与 §5.3。
 *
 * 🔴 **只有 W4-B 可写本文件**（域：PATROL / BOUNDARY / INVENTORY / DIPLOMACY / SAVE_LOAD）。
 */

/** 本批域的**已关闭**传输单元（逐域关闭清单的 W4-B 分片）。 */
internal val w4BClosedUnits: List<ReverseChannelPolicy.ClosedUnit> = listOf(
    // BOUNDARY（月年编排面：政策开关 / 引导计数 / 设置项族 / 年度计数）
    gameDataField(Domain.BOUNDARY, "sectPolicies"),
    gameDataField(Domain.BOUNDARY, "guideCounters"),
    gameDataField(Domain.BOUNDARY, "openRecruitmentLastPaidMonth"),
    gameDataField(Domain.BOUNDARY, "isGameOver"),
    gameDataField(Domain.BOUNDARY, "annualForgeCount"),
    gameDataField(Domain.BOUNDARY, "annualHerbCount"),
    gameDataField(Domain.BOUNDARY, "sectLevelClaimRecords"),
    gameDataField(Domain.BOUNDARY, "sectCultivation"),
    gameDataField(Domain.BOUNDARY, "autoEquipFromWarehouseFocused"),
    gameDataField(Domain.BOUNDARY, "autoEquipFromWarehouseRootCounts"),
    gameDataField(Domain.BOUNDARY, "autoLearnFromWarehouseFocused"),
    gameDataField(Domain.BOUNDARY, "autoLearnFromWarehouseRootCounts"),
    gameDataField(Domain.BOUNDARY, "autoRecruitSpiritRootFilter"),
    gameDataField(Domain.BOUNDARY, "autoRejectSpiritRootFilter"),
    gameDataField(Domain.BOUNDARY, "autoSellMidGradeForPurchase"),
    gameDataField(Domain.BOUNDARY, "autoSellHighGradeForPurchase"),
    gameDataField(Domain.BOUNDARY, "breakthroughAutoPillFocused"),
    gameDataField(Domain.BOUNDARY, "breakthroughAutoPillRootCounts"),
    gameDataField(Domain.BOUNDARY, "daoCompanionBannedRootCounts"),
    gameDataField(Domain.BOUNDARY, "daoCompanionConsentRequired"),
    gameDataField(Domain.BOUNDARY, "musicEnabled"),
    gameDataField(Domain.BOUNDARY, "soundEnabled"),
    gameDataField(Domain.BOUNDARY, "showAllAvailableDisciples"),
    gameDataField(Domain.BOUNDARY, "patrolBattleResultPopup"),
    gameDataField(Domain.BOUNDARY, "prisonerSpiritRootFilter"),
    // SAVE_LOAD（时间三件 / 槽位 / 版本 / 种子 / rngStates）
    gameDataField(Domain.SAVE_LOAD, "gameYear"),
    gameDataField(Domain.SAVE_LOAD, "gameMonth"),
    gameDataField(Domain.SAVE_LOAD, "gamePhase"),
    gameDataField(Domain.SAVE_LOAD, "currentSlot"),
    gameDataField(Domain.SAVE_LOAD, "saveVersion"),
    gameDataField(Domain.SAVE_LOAD, "mapSeed"),
    gameDataField(Domain.SAVE_LOAD, "id"),
    gameDataField(Domain.SAVE_LOAD, "lastSaveTime"),
    gameDataField(Domain.SAVE_LOAD, "rngStates"),
    // DIPLOMACY
    gameDataField(Domain.DIPLOMACY, "alliances"),
    gameDataField(Domain.DIPLOMACY, "playerAllianceSlots"),
    gameDataField(Domain.DIPLOMACY, "playerHasAttackedAI"),
    gameDataField(Domain.DIPLOMACY, "playerProtectionEnabled"),
    gameDataField(Domain.DIPLOMACY, "playerProtectionStartYear"),
    gameDataField(Domain.DIPLOMACY, "lastYearSpiritStoneIncome"),
    // INVENTORY（商人收购池 / 上架池 / 刷新凭据）
    gameDataField(Domain.INVENTORY, "merchantAcquisitionItems"),
    gameDataField(Domain.INVENTORY, "merchantAcquisitionLastRefreshYear"),
    gameDataField(Domain.INVENTORY, "merchantLastRefreshChanceGrantYear"),
    gameDataField(Domain.INVENTORY, "playerListedItems"),
    // PATROL（含死 API patrolConfig）
    gameDataField(Domain.PATROL, "patrolConfig"),
    gameDataField(Domain.PATROL, "spiritMineExpansions"),
    gameDataField(Domain.PATROL, "yearlySalary"),
    gameDataField(Domain.PATROL, "yearlySalaryEnabled"),
)

/** 本批域的**在册保留**gameData 字段（不可关闭；口径见 `ReverseChannelPolicy.transportedGameDataFields`）。 */
internal val w4BRetainedGameDataFields: Set<String> = linkedSetOf(
    // 玉符运行时（发放真相源在 Kotlin：循环钩子/跨天重置/存档 checkpoint）
    "jadeSymbols", "jadeSymbolsToday", "jadeAccumMs", "jadeDayAnchorMs",
    // 灵矿/住所/巡逻槽位与配置（PATROL 域稳态写者）
    "spiritMineSlots", "residenceSlots", "patrolSlots", "patrolConfigs",
    // 灵矿月结水位：DiffAuthoritativeTickTest 的 AUTHORITATIVE 管线对拍把 Kotlin
    // 月变编排纳入稳态（测试面为生产超集）——关闭该字段会使对拍红，故保持传输
    "spiritMineLastSettledMonth",
    // 巡逻战斗待结算（PATROL 域）
    "pendingPatrolBattleResults",
    // 行商池与刷新凭据（INVENTORY/BOUNDARY 域稳态写者）
    "travelingMerchantItems", "merchantLastRefreshYear", "merchantRefreshCount",
    "merchantRefreshChances",
    // 自动购买列表与邮件账本（INVENTORY 域）
    "autoBuyList", "mailRecords",
    // 引导领奖（BOUNDARY 域）
    "guideClaimedRewardIds",
    // 外交/附庸（DIPLOMACY 域）
    "sectRelations", "vassalContracts", "suzerainSectId",
    // 天道试炼（登记不下沉：模板随机与凭据溢出抑制同事务）
    "heavenlyTrialState",
)

/** 本批域的**域级审计结论证据**（`文件:行 函数` 形式；CLOSED 域必须为空）。 */
internal val w4BDomainEvidence: Map<Domain, List<String>> = mapOf(
    Domain.PATROL to listOf(
        "W4-B/B1（2026-09-15）：SpiritMineViewModel.kt 灵矿槽位 UI 直改四处（原 :89/:147/:183/:252）已消除" +
            "——槽位整表覆写改走 updateSpiritMineSlots（native PATROL_UPDATE_SPIRIT_MINE_SLOTS + 回退臂），" +
            "亲传槽位卸任改走 removeDirectDisciple（native DISCIPLE_TX_UNASSIGN_SLOT + 回退臂）",
        "GameEnginePatrolOps.kt:88 updateSpiritMineSlots 回退臂 / :49 validateAndFixSpiritMineData 回退臂" +
            "（AUTHORITATIVE 稳态写者 = C++ patrol_tx.h 事务 7/8/9）；死 API updatePatrolConfig（单参）/" +
            "updatePatrolSlots 已删除（patrolConfig 字段在册关闭项自此无生产写者）",
        "跨批残余（本批不可关闭）：CombatService.kt:106 战斗伤亡清理（W4-C）/ BuildingFacadeImpl同步Ops.kt:65 " +
            "拆除重建（W4-A）/ GameEngineSelfHealOps.kt:161 与 GameEngineServiceOps.kt:198 自愈/迁移（W4-B/B4）/" +
            "BootSequenceController、LoadDataOps 族（LOAD_BOOT）",
    ),
    Domain.BOUNDARY to listOf(
        "GameEngineCoreMonthOps.kt:90 / GameEngineCoreYearOps.kt:126 — native 月/年结算后的 Kotlin 扇出" +
            "（lifeEvents/秘境关闭邮件/袋物化/丧亲）",
        "GameEngineGuideOps.kt:57 claimGuideReward — 引导领奖（无 native 臂）",
        "RedeemCodeService.kt:153/:402 — 兑换码（无 native 臂）",
    ),
    Domain.DIPLOMACY to listOf(
        "DiplomacyService.kt:145 requestAllianceSimple / :261 dissolveAllianceSimple — 外交稳态段",
        "VassalService.kt:99/:324 — 年度贡赋/月度脱离（月年编排内 Kotlin 活路）",
        "GameEngineDiplomacyOps.kt:18 — 预警去重（无 native 臂）",
    ),
    Domain.INVENTORY to listOf(
        "InventoryFacadeImpl.kt:678 openStorageBag — 开袋抽签已下沉，逐件入库留 Kotlin（两臂共用）",
        "InventorySystem.kt:138 / 装备Ops4.kt:107 / 丹药Ops5.kt:147（统一入库入口，稳态残余共用）",
        "InventoryDelegate.kt:157/:177 自动购买列表 UI 直改（无 native 臂）",
    ),
    Domain.SAVE_LOAD to listOf(
        "SaveFacadeImpl.kt:56 — 存档前自愈（会话中途稳态写者）",
        "GameEngineServiceOps.kt:40 — 修炼检查点重锚（ElderManagementUseCase 调用）",
        "GameEngineServiceOps.kt:77 — 内存压力裁剪（GameLoopDelegate onMemoryPressure）",
    ),
)
