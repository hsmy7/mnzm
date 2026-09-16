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
    // INVENTORY（商人收购池 / 上架池 / 刷新凭据；W4-B/B3 起含旅行商人池与刷新凭据——
    // 写者归 C++ merchant_tx 1770–1773，Kotlin 残余均为回退臂-only）
    gameDataField(Domain.INVENTORY, "merchantAcquisitionItems"),
    gameDataField(Domain.INVENTORY, "merchantAcquisitionLastRefreshYear"),
    gameDataField(Domain.INVENTORY, "merchantLastRefreshChanceGrantYear"),
    gameDataField(Domain.INVENTORY, "playerListedItems"),
    gameDataField(Domain.INVENTORY, "travelingMerchantItems"),
    gameDataField(Domain.INVENTORY, "merchantLastRefreshYear"),
    gameDataField(Domain.INVENTORY, "merchantRefreshCount"),
    gameDataField(Domain.INVENTORY, "merchantRefreshChances"),
    // PATROL（含死 API patrolConfig）
    gameDataField(Domain.PATROL, "patrolConfig"),
    gameDataField(Domain.PATROL, "spiritMineExpansions"),
    gameDataField(Domain.PATROL, "yearlySalary"),
    gameDataField(Domain.PATROL, "yearlySalaryEnabled"),
)

/** 本批域的**在册保留**gameData 字段（不可关闭；口径见 `ReverseChannelPolicy.transportedGameDataFields`）。 */
internal val w4BRetainedGameDataFields: Set<String> = linkedSetOf(
    // 🔴 §2.80 起为空——pendingPatrolBattleResults（清空 updateMirror + 消费重建）/
    // autoBuyList（引擎 wrapper 接线）/mailRecords（领取 updateMirror + 重建）/
    // sectRelations（遭遇战 + 交易接线）/heavenlyTrialState（通关与领取接线）
    // 已随 W4-D/§2.80 第二段转关闭（见 W4DChannelClosures.kt）
)
/** 本批域的**域级审计结论证据**（`文件:行 函数` 形式；CLOSED 域必须为空）。 */
internal val w4BDomainEvidence: Map<Domain, List<String>> = mapOf(
    Domain.PATROL to listOf(
        "W4-B/B1（2026-09-15）：SpiritMineViewModel.kt:89/:147/:183/:252 灵矿槽位 UI 直改四处已消除" +
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
        "W4-B/B4（2026-09-15）：DiplomacyService.kt:145/:261 结盟/散盟——batch-09 native 臂已在位" +
            "（DIPLOMACY_TX=1500 + diplomacy_tx.h 双事务），本批核实零改动；" +
            "GameEngineDiplomacyOps.kt:18 预警标记已下沉（DIPLOMACY_WARNING_STAGE_TX=1843，" +
            "shownWarningStageIds 按 ① 保守处置）",
        "VassalService.kt:99/:324 — 年度贡赋/月度脱离登记不下沉：C++ 逻辑已在位" +
            "（year_settlement.h，AUTHORITATIVE 年结管线）；调用点在冻结宿主（w3-11 面）" +
            "⇒ 本批开臂即双重扣贡，门控统一归 W4-D/D2（批文档 §8 技术债首行同结论）",
    ),
    Domain.INVENTORY to listOf(
        "W4-B/B3（2026-09-15）：行商族写者归 C++ merchant_tx 1770–1773——" +
            "MerchantAndRecruitService.kt:65/:319/:344/:377 降级回退臂-only" +
            "（travelingMerchantItems/merchantLastRefreshYear/merchantRefreshCount/" +
            "merchantRefreshChances 四字段本批转入关闭）",
        "InventoryFacadeImpl.kt:678 openStorageBag — 开袋抽签已下沉，逐件入库留 Kotlin（两臂共用）",
        "InventorySystem.kt:138 / 装备Ops4.kt:107 / 丹药Ops5.kt:147（统一入库入口，稳态残余共用）",
        "InventoryDelegate.kt:157/:177 自动购买列表 UI 直改（无 native 臂）",
    ),
    Domain.SAVE_LOAD to listOf(
        "W4-B/B4（2026-09-15）登记不下沉——SaveFacadeImpl.kt:56 / GameEngineServiceOps.kt:40/:77" +
            "（逐点判定见 diplomacy_selfheal_tx.h 头注）：",
        "SaveFacadeImpl.kt:56 — 存档前自愈（WorldMapGenerator 世界生成面，与 W4-C WS-5b" +
            "「生成即数据」同域 ⇒ W4-D 评估）",
        "GameEngineServiceOps.kt:40 — 修炼检查点重锚（写 DiscipleTables 检查点列，弟子域" +
            "语义归 W4-A；C++ 模型无检查点列 ⇒ 需 models.h 扩列租约 ⇒ W4-D）",
        "GameEngineServiceOps.kt:77 — 内存压力裁剪（③类平台决策面，裁剪清单语义与" +
            "DiscipleSlotCleanup/死亡处理交叉 ⇒ W4-D）",
    ),
)
