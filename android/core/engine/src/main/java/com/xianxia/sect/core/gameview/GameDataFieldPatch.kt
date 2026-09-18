package com.xianxia.sect.core.gameview

import com.xianxia.sect.core.model.ActiveMission
import com.xianxia.sect.core.model.AICaveTeam
import com.xianxia.sect.core.model.AISectPersonality
import com.xianxia.sect.core.model.Alliance
import com.xianxia.sect.core.model.AttackWarning
import com.xianxia.sect.core.model.AutoBuyEntry
import com.xianxia.sect.core.model.BattleTeam
import com.xianxia.sect.core.model.BloodRefinementBonusTotal
import com.xianxia.sect.core.model.BloodRefinementPctTotal
import com.xianxia.sect.core.model.BloodRefinementProgress
import com.xianxia.sect.core.model.CaveExplorationTeam
import com.xianxia.sect.core.model.CultivatorCave
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.ElderSlots
import com.xianxia.sect.core.model.ExploredSectInfo
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.GameEventRecord
import com.xianxia.sect.core.model.GridBuildingData
import com.xianxia.sect.core.model.HeavenlyTrialSaveData
import com.xianxia.sect.core.model.LibrarySlot
import com.xianxia.sect.core.model.MailClaimRecord
import com.xianxia.sect.core.model.ManualProficiencyData
import com.xianxia.sect.core.model.MerchantItem
import com.xianxia.sect.core.model.Mission
import com.xianxia.sect.core.model.PatrolConfig
import com.xianxia.sect.core.model.PatrolSlot
import com.xianxia.sect.core.model.PendingTraitAdd
import com.xianxia.sect.core.model.production.ProductionSlot
import com.xianxia.sect.core.model.ResidenceSlot
import com.xianxia.sect.core.model.RoadData
import com.xianxia.sect.core.model.SecretRealmAITeam
import com.xianxia.sect.core.model.SecretRealmExplorationSession
import com.xianxia.sect.core.model.SecretRealmState
import com.xianxia.sect.core.model.SectBattleRecord
import com.xianxia.sect.core.model.SectDetail
import com.xianxia.sect.core.model.SectLevelClaimRecord
import com.xianxia.sect.core.model.SectPolicies
import com.xianxia.sect.core.model.SectRelation
import com.xianxia.sect.core.model.SectScoutInfo
import com.xianxia.sect.core.model.SignInState
import com.xianxia.sect.core.model.SpiritFieldPlant
import com.xianxia.sect.core.model.SpiritMineSlot
import com.xianxia.sect.core.model.VassalContract
import com.xianxia.sect.core.model.WarehouseGarrisonSlot
import com.xianxia.sect.core.model.WorldLevel
import com.xianxia.sect.core.model.WorldSect
import com.xianxia.sect.core.model.YearlyReport
import com.xianxia.sect.core.state.BattleResultUIData
import kotlinx.serialization.descriptors.elementNames
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement

/**
 * gameData 单字段写入器：把变更集里的一个 `gameData.<name>` 值就地写进
 * COW 副本（[GameData.copy] 之后的浅拷贝对象）。
 */
internal typealias GameDataFieldWriter = (GameData, JsonElement, Json) -> Unit

/**
 * GameDataFieldPatch —— 镜像增量对 gameData 的**字段级**应用
 * （重构方案 R2.3 第二波「GameStateStore 每旬级全量重建路径退场」）。
 *
 * ## 退场的形状
 * 第一波（B06/B07）及之前，每旬镜像对 gameData 的应用是
 * `encodeToJsonElement(整份 GameData) → 覆盖变更键 → decodeFromJsonElement(整份)`
 * ——只为改 3~5 个标量，却要按 137 个序列化字段（含 gameEventRecords /
 * recruitList / worldMapSects 等巨型容器）整树序列化 + 反序列化各一次，
 * 即"每旬级全量重建"。本对象把该形状换成**一次浅拷贝 + 变更字段逐个解码**：
 * 成本与"本封变了什么"成比例，与"状态有多大"无关。
 *
 * ## 与旧全量往返的逐值等价（守卫锁定）
 * - 未知键（不在 GameData 序列化面）宽松忽略、不中断其余字段——旧路径
 *   `Json { ignoreUnknownKeys = true }` 同语义；
 * - 任一变更多余的在册字段解码失败 ⇒ 整组丢弃、gameData 保持现状——旧路径
 *   "解码异常 → 保留 Kotlin 现状"同语义；
 * - [LEGACY_RESET_ON_MIRROR] 显式复刻旧全量解码的一个副作用：@Transient 字段
 *   不进 JSON，整份解码必然把它们打回声明默认值。逐值等价是本批红线，故先
 *   原样复刻，并把"镜像每旬重置运行态字段"作为独立缺陷登记（方案 §7.2 B08 行），
 *   不在重构批里顺手改行为。
 *
 * ## fail-fast 红线（方案 §5「投影缺失字段 fail-fast 而非静默空」）
 * 字段名在 GameData 序列化面内、但本表无写入器 ⇒ 抛错（新增字段漏登记立即
 * 暴露），而不是静默丢变更。表完整性另有守卫锁定
 * `写入器键集 ↔ GameData 序列化器字段面` 双射。
 */
internal object GameDataFieldPatch {

    private fun f(name: String, writer: GameDataFieldWriter) = name to writer

    /** GameData 序列化面（kotlinx 描述符 elementNames，@Transient 天然不在其中） */
    private val SERIALIZED_FIELDS: Set<String> by lazy(LazyThreadSafetyMode.PUBLICATION) {
        GameData.serializer().descriptor.elementNames.toSet()
    }

    /** 旧全量 JSON 往返必然打回声明默认值的 @Transient 字段（逐值等价复刻面） */
    private val LEGACY_RESET_ON_MIRROR: List<(GameData) -> Unit> by lazy(
        LazyThreadSafetyMode.PUBLICATION
    ) {
        val defaults = GameData()
        listOf(
            { target -> target.slotId = defaults.slotId },
            { target -> target.autoSaveIntervalMonths = defaults.autoSaveIntervalMonths },
            { target -> target.aiBeastEncounterTargets = defaults.aiBeastEncounterTargets },
            { target -> target.battleTeam = defaults.battleTeam },
            { target -> target.aiBattleTeams = defaults.aiBattleTeams }
        )
    }

    /** 本表覆盖的字段名集（守卫比对 GameData 序列化面用）。 */
    val coveredFields: Set<String> get() = WRITERS.keys

    /**
     * 字段级应用 gameData 变更。
     *
     * @param changes 已去掉 `gameData.` 前缀的 (字段名 → 新值) 序列（同一封变更集）
     * @return 应用后的**新** GameData 实例（引用变化即 store 事务的提交判据）；
     *         无变更、或任一在册字段解码失败时返回 null（调用方保持现状）
     */
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    fun apply(current: GameData, changes: List<Pair<String, JsonElement>>, json: Json): GameData? {
        if (changes.isEmpty()) return null
        val next = current.copy()
        return try {
            for ((name, value) in changes) writerOf(name)?.invoke(next, value, json)
            LEGACY_RESET_ON_MIRROR.forEach { reset -> reset(next) }
            next
        } catch (e: Exception) {
            null
        }
    }

    /** 字段名 → 写入器；在册未登记 = 协议漂移，立即抛错而非静默丢变更。 */
    private fun writerOf(name: String): GameDataFieldWriter? {
        WRITERS[name]?.let { return it }
        require(!SERIALIZED_FIELDS.contains(name)) {
            "gameData 字段级应用缺写入器（字段在序列化面内但未登记，静默忽略即丢镜像变更）：$name"
        }
        return null
    }

    // 表体 = GameData 构造器序列化字段全集（137 项，@Transient 9 项除外）。
    // 逐字段用与整份解码同一个 Json 实例 + 同一 serializer ⇒ 逐值等价。
    @Suppress("LargeClass")
    private val WRITERS: Map<String, GameDataFieldWriter> = mapOf(
        f("id", { gd, el, j -> gd.id = j.decodeFromJsonElement<String>(el) }),
        f("sectName", { gd, el, j -> gd.sectName = j.decodeFromJsonElement<String>(el) }),
        f("currentSlot", { gd, el, j -> gd.currentSlot = j.decodeFromJsonElement<Int>(el) }),
        f("gameYear", { gd, el, j -> gd.gameYear = j.decodeFromJsonElement<Int>(el) }),
        f("gameMonth", { gd, el, j -> gd.gameMonth = j.decodeFromJsonElement<Int>(el) }),
        f("gamePhase", { gd, el, j -> gd.gamePhase = j.decodeFromJsonElement<Int>(el) }),
        f("spiritStones", { gd, el, j -> gd.spiritStones = j.decodeFromJsonElement<Long>(el) }),
        f("midGradeSpiritStones", { gd, el, j -> gd.midGradeSpiritStones = j.decodeFromJsonElement<Long>(el) }),
        f("highGradeSpiritStones", { gd, el, j -> gd.highGradeSpiritStones = j.decodeFromJsonElement<Long>(el) }),
        f("spiritHerbs", { gd, el, j -> gd.spiritHerbs = j.decodeFromJsonElement<Int>(el) }),
        f("sectCultivation", { gd, el, j -> gd.sectCultivation = j.decodeFromJsonElement<Double>(el) }),
        f("yearlySalary", { gd, el, j -> gd.yearlySalary = j.decodeFromJsonElement<Map<Int, Int>>(el) }),
        f("yearlySalaryEnabled", { gd, el, j ->
            gd.yearlySalaryEnabled = j.decodeFromJsonElement<Map<Int, Boolean>>(el)
        }),
        f("worldMapSects", { gd, el, j -> gd.worldMapSects = j.decodeFromJsonElement<List<WorldSect>>(el) }),
        f("sectDetails", { gd, el, j -> gd.sectDetails = j.decodeFromJsonElement<Map<String, SectDetail>>(el) }),
        f("exploredSects", { gd, el, j ->
            gd.exploredSects = j.decodeFromJsonElement<Map<String, ExploredSectInfo>>(el)
        }),
        f("scoutInfo", { gd, el, j -> gd.scoutInfo = j.decodeFromJsonElement<Map<String, SectScoutInfo>>(el) }),
        f("manualProficiencies", { gd, el, j ->
            gd.manualProficiencies = j.decodeFromJsonElement<Map<String, List<ManualProficiencyData>>>(el)
        }),
        f("travelingMerchantItems", { gd, el, j ->
            gd.travelingMerchantItems = j.decodeFromJsonElement<List<MerchantItem>>(el)
        }),
        f("merchantLastRefreshYear", { gd, el, j -> gd.merchantLastRefreshYear = j.decodeFromJsonElement<Int>(el) }),
        f("merchantRefreshCount", { gd, el, j -> gd.merchantRefreshCount = j.decodeFromJsonElement<Int>(el) }),
        f("merchantRefreshChances", { gd, el, j -> gd.merchantRefreshChances = j.decodeFromJsonElement<Int>(el) }),
        f("merchantLastRefreshChanceGrantYear", { gd, el, j ->
            gd.merchantLastRefreshChanceGrantYear = j.decodeFromJsonElement<Int>(el)
        }),
        f("playerListedItems", { gd, el, j -> gd.playerListedItems = j.decodeFromJsonElement<List<MerchantItem>>(el) }),
        f("merchantAcquisitionItems", { gd, el, j ->
            gd.merchantAcquisitionItems = j.decodeFromJsonElement<List<MerchantItem>>(el)
        }),
        f("merchantAcquisitionLastRefreshYear", { gd, el, j ->
            gd.merchantAcquisitionLastRefreshYear = j.decodeFromJsonElement<Int>(el)
        }),
        f("autoBuyList", { gd, el, j -> gd.autoBuyList = j.decodeFromJsonElement<List<AutoBuyEntry>>(el) }),
        f("recruitList", { gd, el, j -> gd.recruitList = j.decodeFromJsonElement<List<Disciple>>(el) }),
        f("lastRecruitYear", { gd, el, j -> gd.lastRecruitYear = j.decodeFromJsonElement<Int>(el) }),
        f("lastAiSectRecruitYear", { gd, el, j -> gd.lastAiSectRecruitYear = j.decodeFromJsonElement<Int>(el) }),
        f("jadeSymbols", { gd, el, j -> gd.jadeSymbols = j.decodeFromJsonElement<Int>(el) }),
        f("jadeSymbolsToday", { gd, el, j -> gd.jadeSymbolsToday = j.decodeFromJsonElement<Int>(el) }),
        f("jadeDayAnchorMs", { gd, el, j -> gd.jadeDayAnchorMs = j.decodeFromJsonElement<Long>(el) }),
        f("jadeAccumMs", { gd, el, j -> gd.jadeAccumMs = j.decodeFromJsonElement<Long>(el) }),
        f("worldLevels", { gd, el, j -> gd.worldLevels = j.decodeFromJsonElement<List<WorldLevel>>(el) }),
        f("worldLevelLastRefreshMonth", { gd, el, j ->
            gd.worldLevelLastRefreshMonth = j.decodeFromJsonElement<Int>(el)
        }),
        f("rngStates", { gd, el, j -> gd.rngStates = j.decodeFromJsonElement<Map<Int, Long>>(el) }),
        f("cultivatorCaves", { gd, el, j -> gd.cultivatorCaves = j.decodeFromJsonElement<List<CultivatorCave>>(el) }),
        f("caveExplorationTeams", { gd, el, j ->
            gd.caveExplorationTeams = j.decodeFromJsonElement<List<CaveExplorationTeam>>(el)
        }),
        f("aiCaveTeams", { gd, el, j -> gd.aiCaveTeams = j.decodeFromJsonElement<List<AICaveTeam>>(el) }),
        f("unlockedRecipes", { gd, el, j -> gd.unlockedRecipes = j.decodeFromJsonElement<List<String>>(el) }),
        f("unlockedManuals", { gd, el, j -> gd.unlockedManuals = j.decodeFromJsonElement<List<String>>(el) }),
        f("lastSaveTime", { gd, el, j -> gd.lastSaveTime = j.decodeFromJsonElement<Long>(el) }),
        f("elderSlots", { gd, el, j -> gd.elderSlots = j.decodeFromJsonElement<ElderSlots>(el) }),
        f("spiritMineSlots", { gd, el, j -> gd.spiritMineSlots = j.decodeFromJsonElement<List<SpiritMineSlot>>(el) }),
        f("spiritMineExpansions", { gd, el, j -> gd.spiritMineExpansions = j.decodeFromJsonElement<Int>(el) }),
        f("spiritMineLastSettledMonth", { gd, el, j ->
            gd.spiritMineLastSettledMonth = j.decodeFromJsonElement<Int>(el)
        }),
        f("librarySlots", { gd, el, j -> gd.librarySlots = j.decodeFromJsonElement<List<LibrarySlot>>(el) }),
        f("productionSlots", { gd, el, j -> gd.productionSlots = j.decodeFromJsonElement<List<ProductionSlot>>(el) }),
        f("placedBuildings", { gd, el, j -> gd.placedBuildings = j.decodeFromJsonElement<List<GridBuildingData>>(el) }),
        f("roads", { gd, el, j -> gd.roads = j.decodeFromJsonElement<List<RoadData>>(el) }),
        f("spiritFieldPlants", { gd, el, j ->
            gd.spiritFieldPlants = j.decodeFromJsonElement<List<SpiritFieldPlant>>(el)
        }),
        f("activeSectId", { gd, el, j -> gd.activeSectId = j.decodeFromJsonElement<String>(el) }),
        f("residenceSlots", { gd, el, j -> gd.residenceSlots = j.decodeFromJsonElement<List<ResidenceSlot>>(el) }),
        f("warehouseGarrisons", { gd, el, j ->
            gd.warehouseGarrisons = j.decodeFromJsonElement<List<WarehouseGarrisonSlot>>(el)
        }),
        f("patrolSlots", { gd, el, j -> gd.patrolSlots = j.decodeFromJsonElement<List<PatrolSlot>>(el) }),
        f("patrolConfig", { gd, el, j -> gd.patrolConfig = j.decodeFromJsonElement<PatrolConfig>(el) }),
        f("patrolConfigs", { gd, el, j -> gd.patrolConfigs = j.decodeFromJsonElement<List<PatrolConfig>>(el) }),
        f("pendingPatrolBattleResults", { gd, el, j ->
            gd.pendingPatrolBattleResults = j.decodeFromJsonElement<List<BattleResultUIData>>(el)
        }),
        f("alliances", { gd, el, j -> gd.alliances = j.decodeFromJsonElement<List<Alliance>>(el) }),
        f("vassalContracts", { gd, el, j -> gd.vassalContracts = j.decodeFromJsonElement<List<VassalContract>>(el) }),
        f("sectRelations", { gd, el, j -> gd.sectRelations = j.decodeFromJsonElement<List<SectRelation>>(el) }),
        f("playerAllianceSlots", { gd, el, j -> gd.playerAllianceSlots = j.decodeFromJsonElement<Int>(el) }),
        f("sectPolicies", { gd, el, j -> gd.sectPolicies = j.decodeFromJsonElement<SectPolicies>(el) }),
        f("openRecruitmentLastPaidMonth", { gd, el, j ->
            gd.openRecruitmentLastPaidMonth = j.decodeFromJsonElement<Int>(el)
        }),
        f("battleTeams", { gd, el, j -> gd.battleTeams = j.decodeFromJsonElement<List<BattleTeam>>(el) }),
        f("usedTeamNumbers", { gd, el, j -> gd.usedTeamNumbers = j.decodeFromJsonElement<List<Int>>(el) }),
        f("battleTeamsInitialized", { gd, el, j -> gd.battleTeamsInitialized = j.decodeFromJsonElement<Boolean>(el) }),
        f("usedRedeemCodes", { gd, el, j -> gd.usedRedeemCodes = j.decodeFromJsonElement<List<String>>(el) }),
        f("mailRecords", { gd, el, j -> gd.mailRecords = j.decodeFromJsonElement<List<MailClaimRecord>>(el) }),
        f("sectLevelClaimRecords", { gd, el, j ->
            gd.sectLevelClaimRecords = j.decodeFromJsonElement<List<SectLevelClaimRecord>>(el)
        }),
        f("saveVersion", { gd, el, j -> gd.saveVersion = j.decodeFromJsonElement<Int>(el) }),
        f("playerProtectionEnabled", { gd, el, j ->
            gd.playerProtectionEnabled = j.decodeFromJsonElement<Boolean>(el)
        }),
        f("playerProtectionStartYear", { gd, el, j ->
            gd.playerProtectionStartYear = j.decodeFromJsonElement<Int>(el)
        }),
        f("playerHasAttackedAI", { gd, el, j -> gd.playerHasAttackedAI = j.decodeFromJsonElement<Boolean>(el) }),
        f("activeMissions", { gd, el, j -> gd.activeMissions = j.decodeFromJsonElement<List<ActiveMission>>(el) }),
        f("availableMissions", { gd, el, j -> gd.availableMissions = j.decodeFromJsonElement<List<Mission>>(el) }),
        f("autoRecruitSpiritRootFilter", { gd, el, j ->
            gd.autoRecruitSpiritRootFilter = j.decodeFromJsonElement<Set<Int>>(el)
        }),
        f("prisonerSpiritRootFilter", { gd, el, j ->
            gd.prisonerSpiritRootFilter = j.decodeFromJsonElement<Set<Int>>(el)
        }),
        f("recruitCountThisMonth", { gd, el, j -> gd.recruitCountThisMonth = j.decodeFromJsonElement<Int>(el) }),
        f("autoRejectSpiritRootFilter", { gd, el, j ->
            gd.autoRejectSpiritRootFilter = j.decodeFromJsonElement<Set<Int>>(el)
        }),
        f("watchedItemIds", { gd, el, j -> gd.watchedItemIds = j.decodeFromJsonElement<List<String>>(el) }),
        f("secretRealmState", { gd, el, j -> gd.secretRealmState = j.decodeFromJsonElement<SecretRealmState>(el) }),
        f("secretRealmCooldownYear", { gd, el, j -> gd.secretRealmCooldownYear = j.decodeFromJsonElement<Int>(el) }),
        f("secretRealmSession", { gd, el, j ->
            gd.secretRealmSession = j.decodeFromJsonElement<SecretRealmExplorationSession>(el)
        }),
        f("secretRealmAITeams", { gd, el, j ->
            gd.secretRealmAITeams = j.decodeFromJsonElement<List<SecretRealmAITeam>>(el)
        }),
        f("daoCompanionBannedRootCounts", { gd, el, j ->
            gd.daoCompanionBannedRootCounts = j.decodeFromJsonElement<Set<Int>>(el)
        }),
        f("daoCompanionConsentRequired", { gd, el, j ->
            gd.daoCompanionConsentRequired = j.decodeFromJsonElement<Boolean>(el)
        }),
        f("patrolBattleResultPopup", { gd, el, j ->
            gd.patrolBattleResultPopup = j.decodeFromJsonElement<Boolean>(el)
        }),
        f("autoSellMidGradeForPurchase", { gd, el, j ->
            gd.autoSellMidGradeForPurchase = j.decodeFromJsonElement<Boolean>(el)
        }),
        f("autoSellHighGradeForPurchase", { gd, el, j ->
            gd.autoSellHighGradeForPurchase = j.decodeFromJsonElement<Boolean>(el)
        }),
        f("showAllAvailableDisciples", { gd, el, j ->
            gd.showAllAvailableDisciples = j.decodeFromJsonElement<Boolean>(el)
        }),
        f("breakthroughAutoPillFocused", { gd, el, j ->
            gd.breakthroughAutoPillFocused = j.decodeFromJsonElement<Boolean>(el)
        }),
        f("breakthroughAutoPillRootCounts", { gd, el, j ->
            gd.breakthroughAutoPillRootCounts = j.decodeFromJsonElement<Set<Int>>(el)
        }),
        f("autoEquipFromWarehouseFocused", { gd, el, j ->
            gd.autoEquipFromWarehouseFocused = j.decodeFromJsonElement<Boolean>(el)
        }),
        f("autoEquipFromWarehouseRootCounts", { gd, el, j ->
            gd.autoEquipFromWarehouseRootCounts = j.decodeFromJsonElement<Set<Int>>(el)
        }),
        f("autoLearnFromWarehouseFocused", { gd, el, j ->
            gd.autoLearnFromWarehouseFocused = j.decodeFromJsonElement<Boolean>(el)
        }),
        f("autoLearnFromWarehouseRootCounts", { gd, el, j ->
            gd.autoLearnFromWarehouseRootCounts = j.decodeFromJsonElement<Set<Int>>(el)
        }),
        f("isGameOver", { gd, el, j -> gd.isGameOver = j.decodeFromJsonElement<Boolean>(el) }),
        f("bloodRefinements", { gd, el, j ->
            gd.bloodRefinements = j.decodeFromJsonElement<Map<String, List<String>>>(el)
        }),
        f("activeBloodRefinements", { gd, el, j ->
            gd.activeBloodRefinements = j.decodeFromJsonElement<Map<String, BloodRefinementProgress>>(el)
        }),
        f("bloodRefinementBonusTotals", { gd, el, j ->
            gd.bloodRefinementBonusTotals = j.decodeFromJsonElement<Map<String, BloodRefinementBonusTotal>>(el)
        }),
        f("bloodRefinementPctTotals", { gd, el, j ->
            gd.bloodRefinementPctTotals = j.decodeFromJsonElement<Map<String, BloodRefinementPctTotal>>(el)
        }),
        f("heavenlyTrialState", { gd, el, j ->
            gd.heavenlyTrialState = j.decodeFromJsonElement<HeavenlyTrialSaveData>(el)
        }),
        f("signInState", { gd, el, j -> gd.signInState = j.decodeFromJsonElement<SignInState>(el) }),
        f("aiSectPersonalities", { gd, el, j ->
            gd.aiSectPersonalities = j.decodeFromJsonElement<Map<String, AISectPersonality>>(el)
        }),
        f("suzerainSectId", { gd, el, j -> gd.suzerainSectId = j.decodeFromJsonElement<String>(el) }),
        f("lastYearSpiritStoneIncome", { gd, el, j ->
            gd.lastYearSpiritStoneIncome = j.decodeFromJsonElement<Long>(el)
        }),
        f("activeAttackWarnings", { gd, el, j ->
            gd.activeAttackWarnings = j.decodeFromJsonElement<List<AttackWarning>>(el)
        }),
        f("shownWarningStageIds", { gd, el, j -> gd.shownWarningStageIds = j.decodeFromJsonElement<List<String>>(el) }),
        f("sectAttackCooldowns", { gd, el, j ->
            gd.sectAttackCooldowns = j.decodeFromJsonElement<Map<String, Int>>(el)
        }),
        f("sectBattleRecords", { gd, el, j ->
            gd.sectBattleRecords = j.decodeFromJsonElement<List<SectBattleRecord>>(el)
        }),
        f("gameEventRecords", { gd, el, j ->
            gd.gameEventRecords = j.decodeFromJsonElement<List<GameEventRecord>>(el)
        }),
        f("guideClaimedRewardIds", { gd, el, j -> gd.guideClaimedRewardIds = j.decodeFromJsonElement<Set<Int>>(el) }),
        f("guideCounters", { gd, el, j -> gd.guideCounters = j.decodeFromJsonElement<Map<String, Long>>(el) }),
        f("mapSeed", { gd, el, j -> gd.mapSeed = j.decodeFromJsonElement<Int>(el) }),
        f("annualIncomeBySource", { gd, el, j ->
            gd.annualIncomeBySource = j.decodeFromJsonElement<Map<String, Long>>(el)
        }),
        f("annualExpenditureByReason", { gd, el, j ->
            gd.annualExpenditureByReason = j.decodeFromJsonElement<Map<String, Long>>(el)
        }),
        f("annualTotalIncome", { gd, el, j -> gd.annualTotalIncome = j.decodeFromJsonElement<Long>(el) }),
        f("annualTotalExpenditure", { gd, el, j -> gd.annualTotalExpenditure = j.decodeFromJsonElement<Long>(el) }),
        f("annualAlchemyCount", { gd, el, j -> gd.annualAlchemyCount = j.decodeFromJsonElement<Int>(el) }),
        f("annualForgeCount", { gd, el, j -> gd.annualForgeCount = j.decodeFromJsonElement<Int>(el) }),
        f("annualHerbCount", { gd, el, j -> gd.annualHerbCount = j.decodeFromJsonElement<Int>(el) }),
        f("annualNewDisciples", { gd, el, j -> gd.annualNewDisciples = j.decodeFromJsonElement<Int>(el) }),
        f("annualDeceasedDisciples", { gd, el, j -> gd.annualDeceasedDisciples = j.decodeFromJsonElement<Int>(el) }),
        f("annualDesertedDisciples", { gd, el, j -> gd.annualDesertedDisciples = j.decodeFromJsonElement<Int>(el) }),
        f("annualTheftCount", { gd, el, j -> gd.annualTheftCount = j.decodeFromJsonElement<Int>(el) }),
        f("theftJudgementsThisMonth", { gd, el, j -> gd.theftJudgementsThisMonth = j.decodeFromJsonElement<Int>(el) }),
        f("annualEquipmentBySource", { gd, el, j ->
            gd.annualEquipmentBySource = j.decodeFromJsonElement<Map<String, Int>>(el)
        }),
        f("annualPillBySource", { gd, el, j -> gd.annualPillBySource = j.decodeFromJsonElement<Map<String, Int>>(el) }),
        f("annualHerbBySource", { gd, el, j -> gd.annualHerbBySource = j.decodeFromJsonElement<Map<String, Int>>(el) }),
        f("yearlyReports", { gd, el, j -> gd.yearlyReports = j.decodeFromJsonElement<List<YearlyReport>>(el) }),
        f("soundEnabled", { gd, el, j -> gd.soundEnabled = j.decodeFromJsonElement<Boolean>(el) }),
        f("musicEnabled", { gd, el, j -> gd.musicEnabled = j.decodeFromJsonElement<Boolean>(el) }),
        f("pendingTraitAdds", { gd, el, j ->
            gd.pendingTraitAdds = j.decodeFromJsonElement<List<PendingTraitAdd>>(el)
        }),
        f("mapGenVersion", { gd, el, j -> gd.mapGenVersion = j.decodeFromJsonElement<Int>(el) }),
        f("terrainTiles", { gd, el, j -> gd.terrainTiles = j.decodeFromJsonElement<List<Int>>(el) }),
    )
}
