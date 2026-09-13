package com.xianxia.sect.core.state

/**
 * ReverseChannelPolicy — 反向增量通道**分域关闭策略**（batch-21 终局收敛批）。
 *
 * ## 通道语义
 * AUTHORITATIVE 稳态下 C++ 是真相源，Kotlin 侧 `stateStore.update {}`（非
 * [GameStateStore.updateMirror]）产生的变更由 `captureReverseDirty` 累积，
 * 每个 tick 经 `StateSyncService.applyDirtyToNative()` 回导 C++。本策略决定
 * **哪些状态单元仍需要这条回导**（未列出的单元一律照常传输——默认开放，
 * 关闭必须是显式且带证据的决定）。
 *
 * ## 为什么默认开放
 * 关闭某单元 = 该单元的 Kotlin 写入**永不到达 C++**（下一次前向镜像会以 C++
 * 侧值覆盖）。只有当"该单元在 AUTHORITATIVE 稳态下不存在 Kotlin 写者"被穷尽
 * 审计证实时才可关闭；因此未分类单元保持传输，配合
 * `ReverseChannelPolicyGuardTest` 的**穷尽分类守卫**（新增字段必须显式归类），
 * 关闭清单只增不减地接受审查。
 *
 * ## 逐域回滚
 * 每个关闭单元归属一个 [Domain]；[reopenDomain] 可把某域**整体**恢复为开启
 * （紧急回滚路径，见 batch-21 §9 风险表"关完后发现漏域 → 单域回滚"）。
 *
 * ## 关闭检测
 * 关闭后若仍有 Kotlin 写者触碰该单元，即为数据丢失缺陷。检测点：
 * - 集合/弟子通道：捕获阶段（`captureCollection` / 弟子脏 id）——引用变化即命中；
 * - gameData 字段：信封构建阶段（当前值 vs C++ 已知值）——见
 *   `StateSyncService.buildReverseEnvelope` 的关闭域写入检测。
 * 命中时记录 ERROR 日志并把单元名写入诊断计数（[noteClosedWrite]），
 * 使"漏域"从静默丢数据变为**可观测缺陷**。
 */
@Suppress("TooManyFunctions")  // 反向通道策略契约面：审计结论查询（域/单元/字段/集合/段）+ 逐域回滚 +
// 关闭域写入检测诊断 三类只读/开关入口不可合并（每类各 4–6 个具名查询面），属协议边界而非可下放助手
object ReverseChannelPolicy {

    /**
     * 反向通道域（与 `docs/ui-read-surface.md` §4.1 逐域写者审计表一一对应）。
     *
     * @property displayName 中文域短名（日志/文档口径）
     */
    enum class Domain(val displayName: String) {
        INVENTORY("库存"),
        BUILDING("建筑"),
        ROAD("道路"),
        PATROL("巡逻/住所/矿场"),
        DISCIPLE("弟子管理"),
        RECRUIT("招募/派遣/俘虏"),
        PRODUCTION("生产/灵田"),
        SECRET_REALM("秘境"),
        BOUNDARY("月年编排"),
        DIPLOMACY("外交/好感/附庸"),
        AI_SECT("AI 宗门"),
        BATTLE("战斗/探索"),
        LIFE_CYCLE("弟子生命周期"),
        SAVE_LOAD("存档/读档/自愈"),
    }

    /** 传输单元种类。 */
    enum class Kind {
        /** gameData 字段级补丁单元（名字 = GameData JSON 键） */
        GAME_DATA_FIELD,

        /** 实体集合段（名字 = 集合名，如 `equipmentStacks`） */
        COLLECTION,

        /** 弟子通道（脏 id → 全实体 upsert / removed） */
        DISCIPLE_CHANNEL,

        /** 顶层 @Transient 段（名字 = 段名，如 `aiSectDisciples`） */
        TOP_LEVEL_SECTION,
    }

    /** 域关闭状态。 */
    enum class Status {
        /** 该域稳态写者全部归 C++——关闭单元即该域全部传输面 */
        CLOSED,

        /** 部分单元已关闭，其余仍有稳态 Kotlin 写者（[DomainVerdict.residualEvidence] 给出证据） */
        PARTIAL,

        /** 仍有稳态 Kotlin 写者，该域整体保留传输 */
        OPEN,
    }

    /**
     * 已关闭的传输单元。
     *
     * @property domain 归属域（回滚粒度）
     * @property kind 单元种类
     * @property name 单元名（gameData 字段名 / 集合名 / 段名）
     */
    data class ClosedUnit(
        val domain: Domain,
        val kind: Kind,
        val name: String,
    )

    /**
     * 逐域审计结论（batch-21 前置复核产物，逐条可追溯）。
     *
     * @property domain 域
     * @property status 关闭状态
     * @property closedUnits 该域已关闭的传输单元（[Status.CLOSED] 时即该域全部传输面）
     * @property residualEvidence 保留传输的稳态 Kotlin 写者证据（`文件:行 函数` 形式；
     *           [Status.CLOSED] 时必须为空——空证据的保留视为审计未完成）
     */
    data class DomainVerdict(
        val domain: Domain,
        val status: Status,
        val closedUnits: List<ClosedUnit>,
        val residualEvidence: List<String>,
    )

    /** 弟子通道单元名（信封键 = `disciples`）。 */
    const val DISCIPLE_CHANNEL_NAME = "disciples"

    /** 顶层段名：AI 宗门弟子池（`@Transient`，反向信封单独全量段）。 */
    const val SECTION_AI_SECT_DISCIPLES = "aiSectDisciples"

    /** 顶层段名：妖兽视图锁定集（`@Transient`，反向信封单独全量段）。 */
    const val SECTION_LOCKED_BEAST_IDS = "lockedBeastIds"

    /**
     * 实体集合段名全集（反向信封协议名，与 `StateSyncService` 的集合常量同源）。
     *
     * 用途：关闭清单的合法性与覆盖性守卫（`ReverseChannelPolicyGuardTest`）——
     * 集合名新增/改名时守卫失败，强制同步本清单与关闭结论。
     */
    val COLLECTION_NAMES: Set<String> = linkedSetOf(
        "equipmentStacks",
        "equipmentInstances",
        "manualStacks",
        "manualInstances",
        "pills",
        "materials",
        "herbs",
        "seeds",
        "storageBags",
    )

    /**
     * 在册保留传输的 gameData 字段（逐域审计证据 → **不可关闭**）。
     *
     * 判定口径：该字段在 AUTHORITATIVE 稳态下存在 Kotlin 写者（UI 操作面无 native 臂、
     * native 事务后的 Kotlin 残差、月年编排内的 Kotlin 活路、自愈/平台效应）。
     * 关闭其中任一字段 = 该写入永不到达 C++（数据丢失缺陷）。
     *
     * 与 [DomainVerdict.residualEvidence] 的关系：本清单是**字段级**落点，
     * 结论表给出域级叙事与证据；两者由 `ReverseChannelPolicyGuardTest` 的
     * 穷尽分类守卫绑定（关闭清单 ∪ 本清单 == GameData 序列化面全字段）。
     */
    val transportedGameDataFields: Set<String> = linkedSetOf(
        // 玉符运行时（发放真相源在 Kotlin：循环钩子/跨天重置/存档 checkpoint）
        "jadeSymbols", "jadeSymbolsToday", "jadeAccumMs", "jadeDayAnchorMs",
        // 弟子槽位与状态派生（灵矿/住所/巡逻/藏经阁/长老单值槽/生产槽/驻军）
        "spiritMineSlots", "productionSlots", "elderSlots", "librarySlots",
        "residenceSlots", "patrolSlots", "patrolConfigs", "warehouseGarrisons",
        "spiritFieldPlants",
        // 灵矿月结水位：DiffAuthoritativeTickTest 的 AUTHORITATIVE 管线对拍把 Kotlin
        // 月变编排纳入稳态（测试面为生产超集）——关闭该字段会使对拍红，故保持传输
        "spiritMineLastSettledMonth",
        // 弟子生命周期/关系/日志/任务
        "gameEventRecords", "manualProficiencies", "recruitList", "activeMissions",
        "battleTeams",
        // 战斗/探索/秘境残差与运行态
        "activeBloodRefinements", "worldLevels", "sectBattleRecords", "sectDetails",
        "scoutInfo", "secretRealmState", "secretRealmSession", "secretRealmAITeams",
        "secretRealmCooldownYear", "pendingPatrolBattleResults",
        "caveExplorationTeams", "aiCaveTeams",
        // 世界/外交/经济（含年度收支账：Kotlin 钱包与统一入库入口为稳态写者）
        "placedBuildings", "spiritStones", "worldMapSects", "sectRelations",
        "vassalContracts", "suzerainSectId", "activeSectId", "sectName",
        "travelingMerchantItems", "merchantLastRefreshYear", "merchantRefreshCount",
        "merchantRefreshChances", "midGradeSpiritStones", "highGradeSpiritStones",
        "spiritHerbs", "theftJudgementsThisMonth",
        "annualIncomeBySource", "annualExpenditureByReason", "annualTotalIncome",
        "annualTotalExpenditure", "annualNewDisciples", "annualDeceasedDisciples",
        "annualDesertedDisciples", "annualTheftCount", "annualEquipmentBySource",
        "annualPillBySource", "annualHerbBySource",
        "autoBuyList", "mailRecords", "usedRedeemCodes", "guideClaimedRewardIds",
        "watchedItemIds", "shownWarningStageIds",
        // 天道试炼（登记不下沉：模板随机与凭据溢出抑制同事务）
        "heavenlyTrialState",
        // 对拍 harness 覆写（DiffAuthoritativeTickTest 把 Kotlin 月/年编排纳入
        // AUTHORITATIVE 管线 ⇒ 这些字段在测试面为稳态写者，关闭即对拍红）
        "annualAlchemyCount",
        "availableMissions",
        "yearlyReports",
        // 注：aiSectDisciples / lockedBeastIds 等 @Transient 顶层字段不进 gameData
        // 序列化面——传输与否由 TOP_LEVEL_SECTION 单元决定（见 closedUnits 与
        // SECTION_* 常量），不在本字段清单内
    )

    /**
     * 已关闭的传输单元（batch-21 逐域关闭清单）。
     *
     * 每条都经**穷尽写者审计**判定为"AUTHORITATIVE 稳态下无 Kotlin 写者"
     * （仅回退臂 / 读档新档 / 死代码 / 非运行期写入），证据见
     * `docs/ui-read-surface.md` §4.4 与 `docs/cpp-migration-handover-m0.md` §2.53。
     * 逐域回滚用 [reopenDomain]。
     */
    private val closedUnits: List<ClosedUnit> = listOf(
        // BOUNDARY
        ClosedUnit(Domain.BOUNDARY, Kind.GAME_DATA_FIELD, "sectPolicies"),
        ClosedUnit(Domain.BOUNDARY, Kind.GAME_DATA_FIELD, "guideCounters"),
        ClosedUnit(Domain.BOUNDARY, Kind.GAME_DATA_FIELD, "openRecruitmentLastPaidMonth"),
        ClosedUnit(Domain.BOUNDARY, Kind.GAME_DATA_FIELD, "isGameOver"),
        ClosedUnit(Domain.BOUNDARY, Kind.GAME_DATA_FIELD, "annualForgeCount"),
        ClosedUnit(Domain.BOUNDARY, Kind.GAME_DATA_FIELD, "annualHerbCount"),
        ClosedUnit(Domain.BOUNDARY, Kind.GAME_DATA_FIELD, "sectLevelClaimRecords"),
        ClosedUnit(Domain.BOUNDARY, Kind.GAME_DATA_FIELD, "sectCultivation"),
        ClosedUnit(Domain.BOUNDARY, Kind.GAME_DATA_FIELD, "autoEquipFromWarehouseFocused"),
        ClosedUnit(Domain.BOUNDARY, Kind.GAME_DATA_FIELD, "autoEquipFromWarehouseRootCounts"),
        ClosedUnit(Domain.BOUNDARY, Kind.GAME_DATA_FIELD, "autoLearnFromWarehouseFocused"),
        ClosedUnit(Domain.BOUNDARY, Kind.GAME_DATA_FIELD, "autoLearnFromWarehouseRootCounts"),
        ClosedUnit(Domain.BOUNDARY, Kind.GAME_DATA_FIELD, "autoRecruitSpiritRootFilter"),
        ClosedUnit(Domain.BOUNDARY, Kind.GAME_DATA_FIELD, "autoRejectSpiritRootFilter"),
        ClosedUnit(Domain.BOUNDARY, Kind.GAME_DATA_FIELD, "autoSellMidGradeForPurchase"),
        ClosedUnit(Domain.BOUNDARY, Kind.GAME_DATA_FIELD, "autoSellHighGradeForPurchase"),
        ClosedUnit(Domain.BOUNDARY, Kind.GAME_DATA_FIELD, "breakthroughAutoPillFocused"),
        ClosedUnit(Domain.BOUNDARY, Kind.GAME_DATA_FIELD, "breakthroughAutoPillRootCounts"),
        ClosedUnit(Domain.BOUNDARY, Kind.GAME_DATA_FIELD, "daoCompanionBannedRootCounts"),
        ClosedUnit(Domain.BOUNDARY, Kind.GAME_DATA_FIELD, "daoCompanionConsentRequired"),
        ClosedUnit(Domain.BOUNDARY, Kind.GAME_DATA_FIELD, "musicEnabled"),
        ClosedUnit(Domain.BOUNDARY, Kind.GAME_DATA_FIELD, "soundEnabled"),
        ClosedUnit(Domain.BOUNDARY, Kind.GAME_DATA_FIELD, "showAllAvailableDisciples"),
        ClosedUnit(Domain.BOUNDARY, Kind.GAME_DATA_FIELD, "patrolBattleResultPopup"),
        ClosedUnit(Domain.BOUNDARY, Kind.GAME_DATA_FIELD, "prisonerSpiritRootFilter"),
        // SAVE_LOAD
        ClosedUnit(Domain.SAVE_LOAD, Kind.GAME_DATA_FIELD, "gameYear"),
        ClosedUnit(Domain.SAVE_LOAD, Kind.GAME_DATA_FIELD, "gameMonth"),
        ClosedUnit(Domain.SAVE_LOAD, Kind.GAME_DATA_FIELD, "gamePhase"),
        ClosedUnit(Domain.SAVE_LOAD, Kind.GAME_DATA_FIELD, "currentSlot"),
        ClosedUnit(Domain.SAVE_LOAD, Kind.GAME_DATA_FIELD, "saveVersion"),
        ClosedUnit(Domain.SAVE_LOAD, Kind.GAME_DATA_FIELD, "mapSeed"),
        ClosedUnit(Domain.SAVE_LOAD, Kind.GAME_DATA_FIELD, "id"),
        ClosedUnit(Domain.SAVE_LOAD, Kind.GAME_DATA_FIELD, "lastSaveTime"),
        ClosedUnit(Domain.SAVE_LOAD, Kind.GAME_DATA_FIELD, "rngStates"),
        // DIPLOMACY
        ClosedUnit(Domain.DIPLOMACY, Kind.GAME_DATA_FIELD, "alliances"),
        ClosedUnit(Domain.DIPLOMACY, Kind.GAME_DATA_FIELD, "playerAllianceSlots"),
        ClosedUnit(Domain.DIPLOMACY, Kind.GAME_DATA_FIELD, "playerHasAttackedAI"),
        ClosedUnit(Domain.DIPLOMACY, Kind.GAME_DATA_FIELD, "playerProtectionEnabled"),
        ClosedUnit(Domain.DIPLOMACY, Kind.GAME_DATA_FIELD, "playerProtectionStartYear"),
        ClosedUnit(Domain.DIPLOMACY, Kind.GAME_DATA_FIELD, "lastYearSpiritStoneIncome"),
        // INVENTORY
        ClosedUnit(Domain.INVENTORY, Kind.GAME_DATA_FIELD, "merchantAcquisitionItems"),
        ClosedUnit(Domain.INVENTORY, Kind.GAME_DATA_FIELD, "merchantAcquisitionLastRefreshYear"),
        ClosedUnit(Domain.INVENTORY, Kind.GAME_DATA_FIELD, "merchantLastRefreshChanceGrantYear"),
        ClosedUnit(Domain.INVENTORY, Kind.GAME_DATA_FIELD, "playerListedItems"),
        // PATROL
        ClosedUnit(Domain.PATROL, Kind.GAME_DATA_FIELD, "patrolConfig"),
        ClosedUnit(Domain.PATROL, Kind.GAME_DATA_FIELD, "spiritMineExpansions"),
        ClosedUnit(Domain.PATROL, Kind.GAME_DATA_FIELD, "yearlySalary"),
        ClosedUnit(Domain.PATROL, Kind.GAME_DATA_FIELD, "yearlySalaryEnabled"),
        // ROAD
        ClosedUnit(Domain.ROAD, Kind.GAME_DATA_FIELD, "roads"),
        // DISCIPLE
        ClosedUnit(Domain.DISCIPLE, Kind.GAME_DATA_FIELD, "bloodRefinements"),
        ClosedUnit(Domain.DISCIPLE, Kind.GAME_DATA_FIELD, "bloodRefinementBonusTotals"),
        ClosedUnit(Domain.DISCIPLE, Kind.GAME_DATA_FIELD, "bloodRefinementPctTotals"),
        ClosedUnit(Domain.DISCIPLE, Kind.GAME_DATA_FIELD, "pendingTraitAdds"),
        ClosedUnit(Domain.DISCIPLE, Kind.GAME_DATA_FIELD, "battleTeamsInitialized"),
        // SECRET_REALM
        ClosedUnit(Domain.SECRET_REALM, Kind.GAME_DATA_FIELD, "cultivatorCaves"),
        // PRODUCTION
        ClosedUnit(Domain.PRODUCTION, Kind.GAME_DATA_FIELD, "unlockedManuals"),
        ClosedUnit(Domain.PRODUCTION, Kind.GAME_DATA_FIELD, "unlockedRecipes"),
        // RECRUIT
        ClosedUnit(Domain.RECRUIT, Kind.GAME_DATA_FIELD, "recruitCountThisMonth"),
        ClosedUnit(Domain.RECRUIT, Kind.GAME_DATA_FIELD, "lastRecruitYear"),
        ClosedUnit(Domain.RECRUIT, Kind.GAME_DATA_FIELD, "lastAiSectRecruitYear"),
        // AI_SECT
        ClosedUnit(Domain.AI_SECT, Kind.GAME_DATA_FIELD, "aiSectPersonalities"),
        // BATTLE
        ClosedUnit(Domain.BATTLE, Kind.GAME_DATA_FIELD, "worldLevelLastRefreshMonth"),
        ClosedUnit(Domain.BATTLE, Kind.GAME_DATA_FIELD, "exploredSects"),
        ClosedUnit(Domain.BATTLE, Kind.GAME_DATA_FIELD, "usedTeamNumbers"),
        ClosedUnit(Domain.BATTLE, Kind.GAME_DATA_FIELD, "activeAttackWarnings"),
        ClosedUnit(Domain.BATTLE, Kind.GAME_DATA_FIELD, "sectAttackCooldowns"),
        ClosedUnit(Domain.BATTLE, Kind.GAME_DATA_FIELD, "signInState"),
        ClosedUnit(Domain.BATTLE, Kind.TOP_LEVEL_SECTION, SECTION_LOCKED_BEAST_IDS),
    )

    /**
     * 逐域审计结论（域级叙事 + 证据；关闭单元由 [closedUnits] 按域归并）。
     *
     * 状态由证据自动判定：有关闭单元且有稳态写者证据 ⇒ [Status.PARTIAL]；
     * 仅关闭单元 ⇒ [Status.CLOSED]；仅有稳态写者 ⇒ [Status.OPEN]。
     */
    private val verdicts: List<DomainVerdict> = listOf(
        verdict(
            Domain.INVENTORY,
            "InventoryFacadeImpl.kt:678 openStorageBag — 开袋抽签已下沉 C++，逐件入库留 Kotlin（两臂共用）",
            "InventorySystem.kt:138 addEquipmentStack / 装备Ops4.kt:107 addPill / 丹药Ops5.kt:147 addHerb（统一入库入口，稳态残余共用）",
            "InventoryDelegate.kt:157/:177 自动购买列表 UI 直改（无 native 臂）",
        ),
        verdict(
            Domain.BUILDING,
            "BuildingNativeTx.kt:163 removeBuildings — native 拆除后的槽位/弟子释放残差（C++ GridBuildingData 无槽位字段）",
            "BuildingFacadeImpl同步Ops.kt:281 — 月变没收建筑（GameEngineCoreMonthOps.kt:95 无 native 臂）",
            "BuildingDelegate.kt:145 placeSlotsResidual — native 放置成功后的槽位派生残差",
        ),
        verdict(
            Domain.ROAD,
            "RoadFacadeImpl.kt:41/:141 — 道路事务 native 臂后的槽位/回执残差（road_tx 已下沉，残差留 Kotlin）",
        ),
        verdict(
            Domain.PATROL,
            "SpiritMineViewModel.kt:89/:147/:183/:252 — 灵矿槽位 UI 直改（无 native 门控）",
            "GameEnginePatrolOps.kt:49 validateAndFixSpiritMineData — 矿场自愈稳态写者",
        ),
        verdict(
            Domain.DISCIPLE,
            "GameEngineCoordination.kt:99/:120/:138 — 弟子属性/改名/类型直改（无 native 臂）",
            "DiscipleFacadeImpl战斗Ops2.kt:93/:119/:138/:157/:271 — 赏赐/服药 UI 直调（无 native 臂）",
            "DiscipleStatusService.kt:225/:279/:373 — 槽位状态派生同步族（稳态）",
            "DiscipleSlotManager.kt:59、DiscipleLifecycleNativeTx.kt:132 — native 事务后残差",
            "DiscipleSlotCleanup.kt:120 startBloodRefinementAtomic — 血炼启动清槽（UI 直改）",
        ),
        verdict(
            Domain.RECRUIT,
            "DiscipleFacadeImpl功法Ops1.kt:71 mirrorAppendJoinSectLifeEvent — 入宗 lifeEvent 补写（C++ 无该列）",
            "DiscipleService.kt:142 recruitDisciple / RecruitService.kt:398 refreshRecruitList — 招募族稳态/回退写者",
        ),
        verdict(
            Domain.PRODUCTION,
            "ProductionProcessorCleaOps3.kt:291 alignMirrorFromRepository — 月结前 repo→镜像整表对齐（native 就绪下必执行）",
            "ProductionProcessor构筑Ops2.kt:405 validateAutoSlot / :250/:341 — 自动续炼槽位写者（S4 月结编排内残差）",
        ),
        verdict(
            Domain.SECRET_REALM,
            "GameEngineSecretRealmOps.kt:57 — 出发换岗清理与弟子槽位状态",
            "GameEngineSecretRealmNativeOps.kt:100 rejectIfSecretRealmExpired — 到期兜底关闭（无 native 门控）",
            "GameEngineSecretRealmNativeOps.kt:263 recordSecretRealmBattleReport — 战报写回",
        ),
        verdict(
            Domain.BOUNDARY,
            "GameEngineCoreMonthOps.kt:90 / GameEngineCoreYearOps.kt:126 — native 月/年结算后的 Kotlin 扇出" +
                "（lifeEvents/秘境关闭邮件/袋物化/丧亲）",
            "GameEngineGuideOps.kt:57 claimGuideReward — 引导领奖（无 native 臂）",
            "RedeemCodeService.kt:153/:402 — 兑换码（无 native 臂）",
        ),
        verdict(
            Domain.DIPLOMACY,
            "DiplomacyService.kt:145 requestAllianceKotlin / :261 dissolveAllianceSimple — 外交稳态段",
            "VassalService.kt:99/:324 — 年度贡赋/月度脱离（月年编排内 Kotlin 活路）",
            "GameEngineDiplomacyOps.kt:18 — 预警去重（无 native 臂）",
        ),
        verdict(
            Domain.AI_SECT,
            "SaveFacadeImpl.kt:56 regenerateSectsBeforeSave — 存档前世界/AI 池自愈（会话中途稳态）",
            "GameEngineBattleOps.kt:176/:339 — 攻宗阵亡守军清理/吞并（剩余写者）",
            "GameEngineLifecycleOps.kt:177/:196 — 自愈同步族（经 upgradeSectLevel 稳态可达）",
        ),
        verdict(
            Domain.BATTLE,
            "CombatService.kt:78 — native 战斗后伤亡残差 + 宗门战纯 Kotlin",
            "GameEngineWorldBattleOps.kt:188/:288 — 关卡胜利/失败事务（魂力与属性增长在 Kotlin）",
            "GameEngineBattleOps.kt:66/:274/:339/:366 — 宗门战战后段（奖励入账/战史不下沉）",
            "GameEngineExplorationNativeOps.kt:134 — native 转发前的战前结算（无条件执行）",
        ),
        verdict(
            Domain.LIFE_CYCLE,
            "DiscipleLifecycleProcessor.kt:489 — 弟子槽位清理（偷盗叛逃事务内 + 永久属性丹两路稳态）",
            "DiscipleLifecycleManager.kt:100/:121 — 月变自动装备 lifeEvent / UI 查看补写",
            "GameEngine.kt:277/:306 婚姻提议审批/拒绝（C++ 事务未接线，玩家审批只走 Kotlin）",
        ),
        verdict(
            Domain.SAVE_LOAD,
            "SaveFacadeImpl.kt:56 — 存档前自愈（会话中途稳态写者）",
            "GameEngineServiceOps.kt:40 — 修炼检查点重锚（ElderManagementUseCase 调用）",
            "GameEngineServiceOps.kt:77 — 内存压力裁剪（GameLoopDelegate onMemoryPressure）",
        ),
    )

    /** 构造域级结论（状态按"关闭清单 × 稳态写者证据"自动判定）。 */
    private fun verdict(domain: Domain, vararg residualEvidence: String): DomainVerdict {
        val closed = closedUnits.filter { it.domain == domain }
        val evidence = residualEvidence.toList()
        val status = when {
            closed.isNotEmpty() && evidence.isEmpty() -> Status.CLOSED
            closed.isNotEmpty() -> Status.PARTIAL
            else -> Status.OPEN
        }
        return DomainVerdict(domain, status, closed, evidence)
    }


    /** 内置关闭单元全集（审计结论；覆盖为空时生效）。 */
    private val auditedClosedUnits: List<ClosedUnit> = closedUnits

    /** 关闭键 → 归属域（逐域回滚用）。 */
    private val closedUnitDomains: Map<String, Domain> =
        closedUnits.associate { key(it.kind, it.name) to it.domain }

    /** 紧急回滚开关：被重新打开的域（[reopenDomain]）。 */
    @Volatile
    private var reopenedDomains: Set<Domain> = emptySet()

    /**
     * 关闭清单覆盖（null = 使用内置审计结论）。
     *
     * 仅用于测试与演练：验证"关闭后仍被写入"的检测链路、逐域回滚语义、
     * 以及关闭前后的信封面差分测量。生产代码不得调用。
     */
    @Volatile
    private var closedUnitsOverride: List<ClosedUnit>? = null

    /** 关闭域写入检测记录（诊断面；有上限，仅保留最近 [MAX_CLOSED_WRITE_RECORDS] 条）。 */
    private val closedWriteRecords = ArrayDeque<String>()

    /** 关闭域写入累计计数（自进程启动；仅诊断，不参与逻辑）。 */
    @Volatile
    private var closedWriteCount: Long = 0

    /** 单元是否仍参与反向传输（默认 true = 保持现状）。 */
    fun isTransported(kind: Kind, name: String): Boolean {
        val override = closedUnitsOverride
        if (override != null) {
            val hit = override.firstOrNull { it.kind == kind && it.name == name } ?: return true
            return hit.domain in reopenedDomains
        }
        val domain = closedUnitDomains[key(kind, name)] ?: return true
        return domain in reopenedDomains
    }

    /** gameData 字段是否仍参与反向传输。 */
    fun isGameDataFieldTransported(field: String): Boolean =
        isTransported(Kind.GAME_DATA_FIELD, field)

    /** 实体集合是否仍参与反向传输。 */
    fun isCollectionTransported(collection: String): Boolean =
        isTransported(Kind.COLLECTION, collection)

    /** 顶层段是否仍参与反向传输。 */
    fun isSectionTransported(section: String): Boolean =
        isTransported(Kind.TOP_LEVEL_SECTION, section)

    /** 弟子通道是否仍参与反向传输。 */
    fun isDiscipleChannelTransported(): Boolean =
        isTransported(Kind.DISCIPLE_CHANNEL, DISCIPLE_CHANNEL_NAME)

    /** 生效的关闭单元（覆盖优先；覆盖为 null 时用内置审计结论）。 */
    private fun effectiveClosedUnits(): List<ClosedUnit> = closedUnitsOverride ?: auditedClosedUnits

    /** 已关闭的 gameData 字段（关闭域写入检测的比较面；回滚域不计入）。 */
    fun closedGameDataFields(): Set<String> = effectiveClosedUnits()
        .filter { it.kind == Kind.GAME_DATA_FIELD && it.domain !in reopenedDomains }
        .mapTo(HashSet()) { it.name }

    /** 已关闭的域集合（观测面）。 */
    fun closedDomains(): Set<Domain> = verdicts.filter { it.status != Status.OPEN }.mapTo(HashSet()) { it.domain }

    /** 某域的完整审计结论（不存在时为 null——守卫测试要求全覆盖）。 */
    fun verdictOf(domain: Domain): DomainVerdict? = verdicts.find { it.domain == domain }

    /** 逐域审计结论快照（守卫测试/文档一致性用）。 */
    fun verdictsSnapshot(): List<DomainVerdict> = verdicts

    /**
     * 紧急回滚：把某域全部关闭单元恢复为传输。
     *
     * @param domain 目标域
     */
    fun reopenDomain(domain: Domain) {
        reopenedDomains = reopenedDomains + domain
    }

    /** 撤销回滚（恢复关闭语义）。 */
    fun closeDomain(domain: Domain) {
        reopenedDomains = reopenedDomains - domain
    }

    /** 测试/回滚后复位：清空回滚开关与诊断计数。 */
    fun resetSwitches() {
        reopenedDomains = emptySet()
        closedUnitsOverride = null
        synchronized(closedWriteRecords) { closedWriteRecords.clear() }
        closedWriteCount = 0
    }

    /**
     * 覆盖关闭清单（null = 恢复内置审计结论）。
     *
     * 仅测试/演练使用（验证检测链路、回滚语义与关闭前后信封面差分）。
     */
    fun overrideClosedUnitsForTest(units: List<ClosedUnit>?) {
        closedUnitsOverride = units
    }

    /**
     * 登记一次"关闭域仍被 Kotlin 写入"检测（诊断，不影响状态）。
     *
     * @param kind 单元种类
     * @param name 单元名
     * @param source 检测点（日志定位用）
     */
    fun noteClosedWrite(kind: Kind, name: String, source: String) {
        closedWriteCount++
        synchronized(closedWriteRecords) {
            if (closedWriteRecords.size >= MAX_CLOSED_WRITE_RECORDS) closedWriteRecords.removeFirst()
            closedWriteRecords.addLast("${kind.name}:$name@$source")
        }
    }

    /** 关闭域写入检测累计次数（诊断面）。 */
    fun closedWriteCountSnapshot(): Long = closedWriteCount

    /** 关闭域写入检测明细快照（最近若干条）。 */
    fun closedWriteRecordsSnapshot(): List<String> = synchronized(closedWriteRecords) {
        closedWriteRecords.toList()
    }

    private fun key(kind: Kind, name: String): String = "${kind.name}:$name"

    /** 诊断记录上限（防无界增长）。 */
    private const val MAX_CLOSED_WRITE_RECORDS = 64
}
