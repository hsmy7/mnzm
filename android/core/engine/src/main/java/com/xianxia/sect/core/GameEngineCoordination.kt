package com.xianxia.sect.core.engine

import com.xianxia.sect.core.util.TimeProgressUtil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import com.xianxia.sect.core.model.*
import com.xianxia.sect.core.model.guide.GuideCounterKeys
import com.xianxia.sect.core.state.*
import com.xianxia.sect.core.engine.service.CultivationService
import com.xianxia.sect.core.engine.service.MailService
import com.xianxia.sect.core.engine.service.RedeemCodeService
import com.xianxia.sect.core.engine.domain.disciple.DiscipleService
import com.xianxia.sect.core.engine.domain.building.BuildingService
import com.xianxia.sect.core.engine.domain.exploration.ExplorationService
import com.xianxia.sect.core.engine.domain.diplomacy.AISectDiscipleManager
import com.xianxia.sect.core.engine.domain.disciple.DiscipleStatCalculator
import com.xianxia.sect.core.engine.domain.exploration.MissionSystem
import com.xianxia.sect.core.engine.domain.building.BuildingFeatureRegistry
import com.xianxia.sect.core.engine.domain.production.ProductionCoordinator
import com.xianxia.sect.core.model.production.BuildingType
import com.xianxia.sect.core.model.production.ProductionSlot
import com.xianxia.sect.core.engine.system.InventorySystem
import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.config.InventoryConfig
import com.xianxia.sect.core.util.addManualInstanceToDiscipleBag
import com.xianxia.sect.core.util.manualBagStackIds
import com.xianxia.sect.core.util.DomainLog
import com.xianxia.sect.core.util.AppError
import com.xianxia.sect.core.util.DomainResult
import com.xianxia.sect.core.util.GameRandom
import java.util.UUID

/** AI 宗门每个宗门的标准弟子数 */
private const val MAX_AI_SECT_DISCIPLES = 50

/** 关注键最大长度（type 前缀 + 冒号 + 最长物品名，防御恶意超长键撑爆存档） */
private const val MAX_WATCHED_KEY_LENGTH = 64

// ── Focus / UI state ────────────────────────────────────────────────

fun GameEngine.setFocusedDiscipleId(id: String?) {
    // 焦点域已覆盖焦点弟子功能，不再需要独立焦点弟子跟踪
}

fun GameEngine.setActiveTab(tab: String) {
    stateStore.activeTab = tab
}

fun GameEngine.setActiveDialog(dialogName: String?) {
    stateStore.activeDialog = dialogName
}

fun GameEngine.pushSubDialogDomain(domainName: String) {
    stateStore.activeSubDialogs = stateStore.activeSubDialogs + domainName
}

fun GameEngine.popSubDialogDomain(domainName: String) {
    stateStore.activeSubDialogs = stateStore.activeSubDialogs - domainName
}

fun GameEngine.notifyUserInteraction() = gameEngineCore.onUserInteraction()

// ── Game lifecycle ──────────────────────────────────────────────────

suspend fun GameEngine.initializeNewGameSuspend(gameData: GameData) {
    return engineContextDispatcher.withEngineContext {
        stateStore.update { this.gameData = gameData }
    }
}

suspend fun GameEngine.ensureHeavyDataLoaded() {
    if (heavyDataLoaded) return
    val slot = stateStore.gameDataSnapshot.currentSlot
    heavyDataLoaded = true
    val currentSects = stateStore.gameDataSnapshot.worldMapSects
    DomainLog.d("GameEngine", "ensureHeavyDataLoaded: 完成 slot=$slot worldMapSects=${currentSects.size}")
}

// ════════════════════════════════════════════════════════════════════════
// 游戏数据完整性守卫 — 行业第4层防御：Data Regeneration
// ════════════════════════════════════════════════════════════════════════

/**
 * 游戏数据完整性守卫 — 检查并修复加载后所有依赖重型数据管线的关键字段。
 *
 * ## 对标（行业第4层防御：Data Regeneration）
 * 行业存档防御链共4层：① Atomic Write ② Backup Fallback ③ Schema Migration
 * ④ Data Regeneration。①-③已实现，此函数补齐第4层。
 *
 * 重型数据管线（game_heavy_data 表）可能因写入中断、反序列化版本不匹配或 Migration
 * 遗漏导致部分字段在合并后仍为空。此函数在加载管线终点集中从权威源数据恢复。
 *
 * ## 设计
 * - 正常路径零开销（前置 isEmpty 判定）
 * - 每个字段独立检查+修复，使用最新 snapshot 避免中间状态污染
 * - 仅修复可自动重生的字段；不可逆字段（exploredSects/scoutInfo）仅记录告警
 */
suspend fun GameEngine.ensureGameDataIntegrity() {
    checkAndRepairWorldMapSects()
    checkAndRepairAiSectDisciples()
    checkAndRepairMerchantAndRecruit()
    checkAndRepairWatchedItemIds()
}

/** 关注列表上限收敛：恶意/损坏存档可注入超长列表，超限会令每次保存失败 */
private suspend fun GameEngine.checkAndRepairWatchedItemIds() {
    val gd = stateStore.gameDataSnapshot
    val watched = gd.watchedItemIds
    if (watched.size <= GameData.MAX_WATCHED_ITEMS && watched.size == watched.toSet().size) return
    stateStore.update {
        gameData = gameData.copy(
            watchedItemIds = gameData.watchedItemIds
                .takeLast(GameData.MAX_WATCHED_ITEMS)
                .distinct()
        )
    }
}

private suspend fun GameEngine.checkAndRepairWorldMapSects() {
    val gd = stateStore.gameDataSnapshot
    // 阶段1：列表为空 → 重生
    if (gd.worldMapSects.isEmpty()) {
        regenerateAllWorldSects(gd.sectName)
        return
    }
    // 阶段2：列表非空但缺少玩家宗门 → 修复重生
    if (gd.worldMapSects.none { it.isPlayerSect }) {
        DomainLog.w("ensureGameDataIntegrity",
            "worldMapSects 非空 (${gd.worldMapSects.size} 个) 但缺少玩家宗门，修复重生" +
            " (sectName=${gd.sectName})")
        regenerateAllWorldSects(gd.sectName)
    }
}

/** 通过 WorldMapGenerator 重生全部宗门数据（含 sectRelations/aiSectDisciples） */
private suspend fun GameEngine.regenerateAllWorldSects(sectName: String) {
    if (sectName.isBlank()) {
        DomainLog.e("ensureGameDataIntegrity", "worldMapSects 为空/缺且 sectName 为空，无法重生")
        return
    }
    DomainLog.w("ensureGameDataIntegrity",
        "从 FixedSectPositions 重生 worldMapSects (sectName=$sectName)")
    val generationResult = WorldMapGenerator.generateWorldSects(sectName)
    val sectRelations = WorldMapGenerator.initializeSectRelations(generationResult.sects)
    stateStore.update {
        gameData = gameData.copy(
            worldMapSects = generationResult.sects,
            sectRelations = sectRelations,
            aiSectDisciples = if (gameData.aiSectDisciples.isEmpty())
                generationResult.aiSectDisciples else gameData.aiSectDisciples
        )
    }
}

private suspend fun GameEngine.checkAndRepairAiSectDisciples() {
    val gd = stateStore.gameDataSnapshot
    // 满员且全部弟子已带装备/功法才跳过（装备/功法为确定生成，可作老档完整性标志；
    // 体质/词条为 0-3 个随机生成，可能天然为 0，不作为判断标准）
    val hasGear = { d: Disciple -> d.equipment.hasEquippedItems && d.manualIds.isNotEmpty() }
    if (gd.aiSectDisciples.isNotEmpty() &&
        gd.aiSectDisciples.values.all { it.size >= MAX_AI_SECT_DISCIPLES && it.all(hasGear) }) return
    if (gd.worldMapSects.isEmpty()) return
    DomainLog.w("ensureGameDataIntegrity", "aiSectDisciples 不足或缺少装备/功法，填充/补全")
    val regenerated = mutableMapOf<String, List<Disciple>>()
    for (sect in gd.worldMapSects) {
        if (sect.isPlayerSect) continue
        if (sect.isPlayerOccupied) continue
        val existing = gd.aiSectDisciples[sect.id].orEmpty()
        if (existing.isEmpty()) {
            val (d, _) = AISectDiscipleManager.initializeSectDisciples(
                sect.name, sect.level)
            regenerated[sect.id] =
                AISectDiscipleManager.fillDisciplesToTarget(
                    sect.name, d, MAX_AI_SECT_DISCIPLES, sect.level)
        } else {
            // 老档补全：fillDisciplesToTarget 内部对存量弟子 ensureDiscipleGear（只补缺）、
            // 对新弟子 applyGearToDisciple；满员时早退分支同样执行 ensureDiscipleGear
            regenerated[sect.id] =
                AISectDiscipleManager.fillDisciplesToTarget(
                    sect.name, existing, MAX_AI_SECT_DISCIPLES, sect.level)
        }
    }
    if (regenerated.isNotEmpty()) {
        stateStore.update {
            val merged = gameData.aiSectDisciples.toMutableMap()
            merged.putAll(regenerated)
            gameData = gameData.copy(aiSectDisciples = merged)
        }
    }
}

private suspend fun GameEngine.checkAndRepairMerchantAndRecruit() {
    val gd = stateStore.gameDataSnapshot
    if (gd.travelingMerchantItems.isEmpty()) {
        DomainLog.w("ensureGameDataIntegrity", "travelingMerchantItems 为空，刷新")
        cultivationService.refreshTravelingMerchant(gd.gameYear, gd.gameMonth)
    }
    // 商人商品 id 去重净化：损坏/旧存档可能出现重复或空 id 商品，
    // 多个空 id 商品同时展示会触发 LazyGrid key="" 崩溃（Bugly #5079/#3091）
    if (gd.travelingMerchantItems.size != gd.travelingMerchantItems.distinctBy { it.id }.size) {
        val removed = gd.travelingMerchantItems.size - gd.travelingMerchantItems.distinctBy { it.id }.size
        DomainLog.w("ensureGameDataIntegrity", "travelingMerchantItems 存在重复 id，净化 $removed 条")
        stateStore.update {
            gameData = gameData.copy(
                travelingMerchantItems = gameData.travelingMerchantItems.distinctBy { it.id }
            )
        }
    }
    if (gd.recruitList.isEmpty() && gd.gameYear - gd.lastRecruitYear >= 3) {
        DomainLog.w("ensureGameDataIntegrity", "recruitList 为空，刷新")
        cultivationService.refreshRecruitList(gd.gameYear)
    }
}

/**
 * 读档弟子 id 归一化：空 id 重分配新 UUID，重复 id 去重（保留首个）。
 *
 * 防御旧存档的 LazyGrid key="" 重复崩溃（Bugly #5079/#3091）：
 * DiscipleSerializer 缺 id 字段时 surrogate 默认空串，损坏存档可能出现
 * 全体弟子空 id / 重复 id。空 id 不删除（静默丢弟子不可接受），重分配保命。
 * 纯函数便于测试。
 *
 * @param disciples 读档弟子列表（已通过幽灵过滤）
 * @return id 全部非空且唯一的弟子列表
 */
internal fun normalizeDiscipleIds(disciples: List<Disciple>): List<Disciple> {
    val seen = mutableSetOf<String>()
    return disciples.map { disciple ->
        when {
            disciple.id.isBlank() -> {
                DomainLog.w("GameEngine", "loadData: 弟子 ${disciple.name} id 为空，分配新 UUID")
                disciple.copy(id = UUID.randomUUID().toString())
            }
            !seen.add(disciple.id) -> {
                DomainLog.w("GameEngine", "loadData: 弟子 id=${disciple.id} 重复，仅保留首个")
                null
            }
            else -> disciple
        }
    }.filterNotNull()
}

suspend fun GameEngine.loadData(
    gameData: GameData, disciples: List<Disciple>, equipmentStacks: List<EquipmentStack>,
    equipmentInstances: List<EquipmentInstance>, manualStacks: List<ManualStack>,
    manualInstances: List<ManualInstance>, pills: List<Pill>, materials: List<Material> = emptyList(),
    herbs: List<Herb> = emptyList(), seeds: List<Seed> = emptyList(), teams: List<ExplorationTeam>,
    battleLogs: List<BattleLog> = emptyList(), alliances: List<Alliance> = emptyList(),
    productionSlots: List<ProductionSlot> = emptyList(),
    storageBags: List<StorageBag> = emptyList()
) {
    return engineContextDispatcher.withEngineContext {
        heavyDataLoaded = false
        // 重置招募惰性状态（纯运行时，不持久化）
        com.xianxia.sect.core.engine.service.RecruitService.resetAutoRecruitIdle()
        com.xianxia.sect.core.engine.service.RecruitService.resetAutoRejectIdle()
        val (migratedGameData, migratedDisciples) = migratePatrolSlotsIfNeeded(gameData, disciples)
        // 防御性幽灵过滤：读档时清除 name 为空的幽灵弟子（补充 SaveValidator 的保护）
        val cleanedDisciples = migratedDisciples.filter { it.name.isNotBlank() }
        if (cleanedDisciples.size != migratedDisciples.size) {
            val count = migratedDisciples.size - cleanedDisciples.size
            DomainLog.w("GameEngine", "loadData: 过滤了 $count 个幽灵弟子（name为空）")
        }
        // 防御性 id 归一化：空 id 重分配 UUID、重复 id 去重保留首个
        // （防旧存档 LazyGrid key="" 重复崩溃，Bugly #5079/#3091）
        val idSafeDisciples = normalizeDiscipleIds(cleanedDisciples)
        stateStore.loadFromSnapshot(
            gameData = migratedGameData, disciples = idSafeDisciples,
            equipmentStacks = equipmentStacks, equipmentInstances = equipmentInstances,
            manualStacks = manualStacks, manualInstances = manualInstances, pills = pills,
            materials = materials, herbs = herbs, seeds = seeds, storageBags = storageBags,
            teams = teams, battleLogs = battleLogs
        )
        // 丹药追踪字段迁移（必须在 stateStore.update 内执行，确保字段守卫通过）
        stateStore.update {
            val tables = discipleTables
            for (id in tables.ids) {
                val oldFunctionalTypes = tables.usedFunctionalPillTypes.getOrNull(id) ?: emptyList()
                val currentPermanentKeys = tables.usedPermanentPillKeys.getOrNull(id) ?: emptySet()
                if (currentPermanentKeys.isEmpty() && oldFunctionalTypes.isNotEmpty()) {
                    tables.usedPermanentPillKeys[id] = oldFunctionalTypes.flatMap { pillType ->
                        (1..6).map { tier -> "$tier#$pillType" }
                    }.toSet()
                }
                val oldExtendLifeIds = tables.usedExtendLifePillIds.getOrNull(id) ?: emptyList()
                val currentExtendLifeTypes = tables.usedExtendLifePillTypes.getOrNull(id) ?: emptySet()
                if (currentExtendLifeTypes.isEmpty() && oldExtendLifeIds.isNotEmpty()) {
                    tables.usedExtendLifePillTypes[id] = oldExtendLifeIds.toSet()
                }
                val oldActiveCategory = tables.activePillCategories.getOrNull(id) ?: ""
                val currentActiveTypes = tables.activePillTypes.getOrNull(id) ?: emptySet()
                if (currentActiveTypes.isEmpty() && oldActiveCategory.isNotEmpty()) {
                    tables.activePillTypes[id] = setOf(oldActiveCategory)
                }
            }
        }
        // 读档自愈（第二道防线）：cache 命中路径绕过 SaveValidator，此处兜底
        // 净化 recruitList 的损坏/重复/已入宗门残留条目（幽灵弟子根治）
        stateStore.update {
            val removed = com.xianxia.sect.core.engine.service.RecruitService
                .sanitizeRecruitList(this)
            if (removed > 0) {
                DomainLog.w("GameEngine", "loadData: 净化 recruitList $removed 条异常条目")
            }
        }
        val restoredRecruitCount = stateStore.gameDataSnapshot.recruitList.size
        DomainLog.d("GameEngine", "loadData: restored game year=${gameData.gameYear}, ${disciples.size} disciples, recruitList=$restoredRecruitCount unrecruited disciples")
        val alchemyCount = BuildingFeatureRegistry.countByType(gameData, BuildingType.ALCHEMY)
        val forgeCount = BuildingFeatureRegistry.countByType(gameData, BuildingType.FORGE)
        val fixedProductionSlots = fixAlchemyForgeSlotCount(productionSlots, alchemyCount, forgeCount)
        if (fixedProductionSlots.isNotEmpty()) {
            productionCoordinator.repository.restoreSlots(fixedProductionSlots, gameData.currentSlot)
        } else {
            productionCoordinator.repository.initializeAllSlots(gameData.currentSlot)
        }
        checkAndCollectCompletedSlots()
        val currentData = stateStore.gameDataSnapshot
        // 旧存档兼容：merchantRefreshChances=0（该字段加入前的存档）初始化为1
        // 同时设置 lastGrantYear 防止下一年度事件双倍发放
        if (currentData.merchantRefreshChances == 0 && currentData.merchantLastRefreshChanceGrantYear == 0) {
            stateStore.update {
                this.gameData = this.gameData.copy(
                    merchantRefreshChances = 1,
                    merchantLastRefreshChanceGrantYear = currentData.gameYear
                )
            }
            DomainLog.w("GameEngine", "loadData: merchantRefreshChances was 0, initialized to 1, lastGrantYear=${currentData.gameYear}")
        }
        discipleService.syncAllDiscipleStatuses()

        // 旧存档兼容：spiritMineLastSettledMonth=0（该字段加入前的存档）会导致首月灵矿产出暴增
        // 修复 P1-1：检测到 0 且游戏已有进度时，初始化为当前月份
        stateStore.update {
            val data = this.gameData
            if (data.spiritMineLastSettledMonth == 0) {
                val currentMonth = data.gameYear * 12 + data.gameMonth
                if (currentMonth > 1) {
                    this.gameData = data.copy(spiritMineLastSettledMonth = currentMonth)
                    DomainLog.w("GameEngine", "loadData: spiritMineLastSettledMonth was 0, initialized to $currentMonth")
                }
            }
        }

        try { mailService.resetAndInitSlot(gameData.slotId) } catch (e: Exception) { DomainLog.e("GameEngine", "Failed to initialize mail for slot ${gameData.slotId}", e) }
    }
}

suspend fun GameEngine.createNewGame(sectName: String, currentSlot: Int = 1) {
    return engineContextDispatcher.withEngineContext {
        // 重置招募惰性状态（纯运行时，新游戏开始时清理）
        com.xianxia.sect.core.engine.service.RecruitService.resetAutoRecruitIdle()
        com.xianxia.sect.core.engine.service.RecruitService.resetAutoRejectIdle()
        stateStore.resetForSlot(currentSlot); cultivationService.resetHighFrequencyData()
        // 1. 先初始化世界和游戏状态（邮件依赖 gameData 就绪）
        initializeWorldAndServices(sectName, currentSlot)
        val gridCells = GameConfig.SectMap.WORLD_WIDTH_CELLS
        val centerGrid = gridCells / 2 - 1  // 2x2 building centered on grid
        val mapSeed = GameRandom.nextInt(Int.MAX_VALUE)
        stateStore.update {
            val initialMine = GridBuildingData(buildingId = "灵矿场", displayName = "灵矿场", gridX = centerGrid, gridY = centerGrid, width = 2, height = 2, instanceId = java.util.UUID.randomUUID().toString(), sectId = "")
            gameData = gameData.copy(
                slotId = currentSlot,
                currentSlot = currentSlot,
                mapSeed = mapSeed,
                placedBuildings = listOf(initialMine),
                spiritMineSlots = (0..2).map { SpiritMineSlot(index = it, sectId = "") },
                spiritMineLastSettledMonth = 1 * 12 + 1,  // gameYear=1, gameMonth=1
                // 显式清零所有建筑/槽位相关字段，防止旧存档数据残留
                productionSlots = emptyList(),
                residenceSlots = emptyList(),
                warehouseGarrisons = emptyList(),
                patrolSlots = emptyList(),
                patrolConfig = PatrolConfig(),
                patrolConfigs = emptyList(),
                librarySlots = emptyList(),
                spiritFieldPlants = emptyList()
            )
            repeat(3) { discipleService.recruitDisciple(realm = 9) }
        }
        addInitialStorageBags()
        // 2. 世界初始化完成后才加载邮件（此时 mailRecords/slotId 等状态已就绪）
        // Note: isGameStarted is set to true later in SaveLoadViewModel.startNewGame()
        // after startGameLoop() succeeds, ensuring UI doesn't appear without a running game loop
        try { mailService.resetAndInitSlot(currentSlot) } catch (e: Exception) { DomainLog.e("GameEngine", "Failed to init mail for new game slot $currentSlot", e) }
    }
}

suspend fun GameEngine.restartGameSuspend(sectName: String = "", currentSlot: Int = 1) = restartGameInternal(sectName, currentSlot)

private suspend fun GameEngine.restartGameInternal(sectName: String, currentSlot: Int) {
    return engineContextDispatcher.withEngineContext {
        stateStore.resetForSlot(currentSlot); cultivationService.resetHighFrequencyData()
        // 每次重启生成新的地图/随机种子，避免全分区 PRNG 种子恒为 0、地图完全相同
        val mapSeed = GameRandom.nextInt(Int.MAX_VALUE)
        if (sectName.isNotBlank()) {
            // 1. 先初始化世界和游戏状态
            initializeWorldAndServices(sectName, currentSlot)
            val gridCells = GameConfig.SectMap.WORLD_WIDTH_CELLS
            val centerGrid = gridCells / 2 - 1
            stateStore.update {
                val initialMine = GridBuildingData(buildingId = "灵矿场", displayName = "灵矿场", gridX = centerGrid, gridY = centerGrid, width = 2, height = 2, instanceId = java.util.UUID.randomUUID().toString(), sectId = "")
                gameData = gameData.copy(
                    slotId = currentSlot,
                    currentSlot = currentSlot,
                    mapSeed = mapSeed,
                    placedBuildings = listOf(initialMine),
                    spiritMineSlots = (0..2).map { SpiritMineSlot(index = it, sectId = "") },
                spiritMineLastSettledMonth = 1 * 12 + 1,  // gameYear=1, gameMonth=1
                // 显式清零所有建筑/槽位相关字段，防止旧存档数据残留
                    productionSlots = emptyList(),
                    residenceSlots = emptyList(),
                    warehouseGarrisons = emptyList(),
                    patrolSlots = emptyList(),
                    patrolConfig = PatrolConfig(),
                    patrolConfigs = emptyList(),
                    librarySlots = emptyList(),
                    spiritFieldPlants = emptyList()
                )
                repeat(3) { discipleService.recruitDisciple(realm = 9) }
            }
            addInitialStorageBags()
            // 2. 世界初始化完成后才加载邮件
            // Note: isGameStarted is set to true later in SaveLoadViewModel.restartGame()
            // after startGameLoop() succeeds
            try { mailService.resetAndInitSlot(currentSlot) } catch (e: Exception) { DomainLog.e("GameEngine", "Failed to init mail for restarted game slot $currentSlot", e) }
        } else {
            stateStore.update { gameData = GameData().copy(currentSlot = currentSlot, mapSeed = mapSeed) }
        }
    }
}

private suspend fun GameEngine.initializeWorldAndServices(sectName: String, currentSlot: Int = 1) {
    return engineContextDispatcher.withEngineContext {
        val generationResult = WorldMapGenerator.generateWorldSects(sectName)
        val sectRelations = WorldMapGenerator.initializeSectRelations(generationResult.sects)
        productionCoordinator.repository.initializeAllSlots(currentSlot)
        cultivationService.refreshTravelingMerchant(1, 1)
        cultivationService.refreshRecruitList(1)
        cultivationService.refreshMerchantAcquisition(1, 1)

        // 为每个 AI 宗门分配唯一弟子头像
        val aiSects = generationResult.sects.filter { !it.isPlayerSect }
        val allPortraitNames = com.xianxia.sect.core.util.PortraitPool.allPortraitNames()
        val sectDetailsMap = aiSects.mapIndexed { index, sect ->
            val portraitRes = allPortraitNames[index % allPortraitNames.size]
            sect.id to com.xianxia.sect.core.model.SectDetail(sectId = sect.id, portraitRes = portraitRes)
        }.toMap()

        stateStore.update {
            gameData = gameData.copy(
                sectName = sectName,
                worldMapSects = generationResult.sects,
                sectRelations = sectRelations,
                aiSectDisciples = generationResult.aiSectDisciples,
                availableMissions = emptyList(),
                sectDetails = sectDetailsMap
            )
        }
    }
}

private suspend fun GameEngine.addInitialStorageBags() {
    return engineContextDispatcher.withEngineContext {
        // 单个堆叠 quantity=2（修复历史版本同稀有度储物袋分成两个独立条目的问题）
        inventorySystem.addStorageBag(StorageBag(name = "凡品储物袋", rarity = 1, quantity = 2))
    }
}

// ── Data update helpers ─────────────────────────────────────────────

suspend fun GameEngine.updateGameData(update: (GameData) -> GameData) {
    return engineContextDispatcher.withEngineContext {
        stateStore.update { gameData = update(gameData) }
    }
}

/**
 * 切换物品关注状态（键格式 "type:name"，如 "pill:聚气丹"）。
 * 已关注则取消，未关注则添加；去重并截断到 [GameData.MAX_WATCHED_ITEMS]。
 *
 * @param key 关注键，空白/超长/格式错误视为无效输入返回失败（正常 UI 路径不会产生）
 * @return 成功或校验失败
 */
suspend fun GameEngine.toggleWatchItem(key: String): DomainResult<Unit> {
    if (key.isBlank()) {
        return DomainResult.Failure(
            AppError.Domain.Validation.InvalidInput("物品关注键不能为空")
        )
    }
    if (key.length > MAX_WATCHED_KEY_LENGTH) {
        return DomainResult.Failure(
            AppError.Domain.Validation.InvalidInput("物品关注键过长")
        )
    }
    if (':' !in key) {
        return DomainResult.Failure(
            AppError.Domain.Validation.InvalidInput("物品关注键格式错误")
        )
    }
    return engineContextDispatcher.withEngineContext {
        stateStore.update { gameData = gameData.toggleWatchedItem(key) }
        DomainResult.Success(Unit)
    }
}

internal fun GameEngine.updateGameDataSync(update: (GameData) -> GameData) {
    gameEngineCore.launchInScope { stateStore.update { gameData = update(gameData) } }
}

suspend fun GameEngine.updateDisciple(discipleId: String, update: (Disciple) -> Disciple) {
    return engineContextDispatcher.withEngineContext {
        stateStore.update {
            val id = discipleId.toInt()
            if (id !in discipleTables.ids) return@update
            val current = discipleTables.assemble(id)
            val updated = update(current)
            discipleTables.remove(id)
            discipleTables.insert(updated)
        }
    }
}

/**
 * 原子重命名宗门弟子，并在同一事务内清除招募列表中与"旧身份"同人的残留条目。
 * 改名会破坏 [RecruitIntegrity.isSamePerson] 的 5 字段签名匹配，
 * 若不在此净化，残留双胞胎将永久逃脱净化、可被重复招募。
 *
 * @param discipleId 宗门弟子 ID
 * @param newName 新姓名
 */
suspend fun GameEngine.renameDisciple(discipleId: String, newName: String) {
    return engineContextDispatcher.withEngineContext {
        stateStore.update {
            val id = discipleId.toInt()
            if (id !in discipleTables.ids) return@update
            val current = discipleTables.assemble(id)
            val updated = current.copy(name = newName)
            discipleTables.remove(id)
            discipleTables.insert(updated)
            // 按改名前的旧身份过滤：签名+年龄容差命中的同人残留一并清除
            val kept = gameData.recruitList.filter { !RecruitIntegrity.isSamePerson(it, current) }
            if (kept.size != gameData.recruitList.size) {
                gameData = gameData.copy(recruitList = kept)
            }
        }
    }
}

suspend fun GameEngine.changeDiscipleTypeAtomic(discipleId: String, newType: String) {
    return engineContextDispatcher.withEngineContext {
        stateStore.update {
            val id = discipleId.toInt()
            if (id in discipleTables.ids) discipleTables.discipleTypes[id] = newType
        }
        discipleFacade.syncSingleDiscipleStatus(discipleId)
    }
}

suspend fun GameEngine.updateGameDataAndSync(update: (GameData) -> GameData) {
    return engineContextDispatcher.withEngineContext {
        stateStore.update { gameData = update(gameData) }
        discipleFacade.syncAllDiscipleStatuses()
    }
}

// ── Cross-domain: Sect / Map ────────────────────────────────────────

suspend fun GameEngine.enterSect(sectId: String) {
    return engineContextDispatcher.withEngineContext {
        stateStore.update { gameData = gameData.copy(activeSectId = sectId) }
    }
}

fun GameEngine.currentActiveSectId(): String = stateStore.gameDataSnapshot.activeSectId

// ── Cross-domain: Use pill ──────────────────────────────────────────

fun GameEngine.usePill(discipleId: String, pillId: String) {
    giveItemToDisciple(discipleId, pillId, "pill")
}

// ── Cross-domain: Manual operations ─────────────────────────────────

suspend fun GameEngine.equipItem(discipleId: String, equipmentId: String): DomainResult<Unit> {
    return discipleService.equipEquipment(discipleId, equipmentId)
}

suspend fun GameEngine.unequipItem(discipleId: String, slot: EquipmentSlot): DomainResult<Unit>? {
    val disciple = getDiscipleById(discipleId) ?: return null
    val equipId = when (slot) { EquipmentSlot.WEAPON -> disciple.equipment.weaponId; EquipmentSlot.ARMOR -> disciple.equipment.armorId; EquipmentSlot.BOOTS -> disciple.equipment.bootsId; EquipmentSlot.ACCESSORY -> disciple.equipment.accessoryId }
    if (equipId.isEmpty()) return null
    val result = discipleService.unequipEquipment(discipleId, equipId)
    cultivationService.markAutoEquipDirty(discipleId)
    return result
}

suspend fun GameEngine.unequipItemById(discipleId: String, equipmentId: String): DomainResult<Unit> {
    val result = discipleService.unequipEquipment(discipleId, equipmentId)
    cultivationService.markAutoEquipDirty(discipleId)
    return result
}

suspend fun GameEngine.forgetManual(discipleId: String, instanceId: String) {
    return engineContextDispatcher.withEngineContext {
        cultivationService.markAutoLearnDirty(discipleId)
        stateStore.update {
            val instance = manualInstances.get(instanceId) ?: return@update
            val id = discipleId.toInt()
            if (id !in discipleTables.ids) return@update
            val currentDisciple = discipleTables.assemble(id)
            val bagStackIds = currentDisciple.manualBagStackIds()
            val result = addManualInstanceToDiscipleBag(disciple = currentDisciple, instance = instance, bagStackIds = bagStackIds, gameYear = gameData.gameYear, gameMonth = gameData.gameMonth, gamePhase = gameData.gamePhase, maxStackSize = inventoryConfig.getMaxStackSize("manual_stack"))
            val updatedManualIds = currentDisciple.manualIds.filter { mid -> mid != instanceId }
            val updatedDisciple = result.updatedDisciple.copy(manualIds = updatedManualIds)
            discipleTables.remove(id)
            discipleTables.insert(updatedDisciple)
            val updatedProficiencies = gameData.manualProficiencies.toMutableMap()
            updatedProficiencies[discipleId]?.let { profList ->
                val filtered = profList.filter { it.manualId != instanceId }
                if (filtered.isEmpty()) updatedProficiencies.remove(discipleId) else updatedProficiencies[discipleId] = filtered
            }
            gameData = gameData.copy(manualProficiencies = updatedProficiencies)
        }
    }
}

suspend fun GameEngine.replaceManual(discipleId: String, oldInstanceId: String, newStackId: String) {
    return engineContextDispatcher.withEngineContext {
        stateStore.update {
            val oldInstance = manualInstances.get(oldInstanceId) ?: return@update
            val newStack = manualStacks.get(newStackId) ?: return@update
            val id = discipleId.toInt()
            if (id !in discipleTables.ids) return@update
            val disciple = discipleTables.assemble(id)
            if (newStack.quantity < 1) return@update
            if (!GameConfig.Realm.meetsRealmRequirement(disciple.realm, newStack.minRealm)) return@update
            val blocked = newStack.type == ManualType.MIND && oldInstance.type != ManualType.MIND && disciple.manualIds.filter { it != oldInstanceId }.any { mid -> manualInstances.get(mid)?.type == ManualType.MIND }
            if (blocked) return@update
            val hasSameName = disciple.manualIds.filter { it != oldInstanceId }.any { mid -> manualInstances.get(mid)?.name == newStack.name }
            if (hasSameName) return@update
            val bagStackIds = disciple.manualBagStackIds()
            val result = addManualInstanceToDiscipleBag(disciple = disciple, instance = oldInstance, bagStackIds = bagStackIds, gameYear = gameData.gameYear, gameMonth = gameData.gameMonth, gamePhase = gameData.gamePhase, maxStackSize = inventoryConfig.getMaxStackSize("manual_stack"))
            val currentNewStack = manualStacks.get(newStackId) ?: return@update
            val newQty = currentNewStack.quantity - 1
            if (newQty <= 0) manualStacks.remove(newStackId) else manualStacks.update(newStackId) { it.copy(quantity = newQty) }
            val newInstance = newStack.toInstance(id = java.util.UUID.randomUUID().toString(), ownerId = discipleId, isLearned = true)
            manualInstances.add(newInstance)
            val updatedProficiencies = gameData.manualProficiencies.toMutableMap()
            updatedProficiencies[discipleId]?.let { profList ->
                val filtered = profList.filter { it.manualId != oldInstanceId }
                if (filtered.isEmpty()) updatedProficiencies.remove(discipleId) else updatedProficiencies[discipleId] = filtered
            }
            val updatedManualIds = (disciple.manualIds.filter { mid -> mid != oldInstanceId }) + newInstance.id
            val updatedDisciple = result.updatedDisciple.copy(manualIds = updatedManualIds)
            discipleTables.remove(id)
            discipleTables.insert(updatedDisciple)

            // 记录功法替换日志
            val replaceAge = discipleTables.ages[id]
            val replaceEvents = discipleTables.lifeEvents.getOrDefault(id, emptyList())
            discipleTables.lifeEvents[id] = replaceEvents +
                "${replaceAge}岁：将功法${oldInstance.name}替换为${newStack.name}"

            gameData = gameData.copy(manualProficiencies = updatedProficiencies)
        }
    }
}

suspend fun GameEngine.learnManual(discipleId: String, stackId: String) {
    return engineContextDispatcher.withEngineContext {
        stateStore.update {
            val stack = manualStacks.get(stackId) ?: return@update
            val id = discipleId.toInt()
            if (id !in discipleTables.ids) return@update
            val disciple = discipleTables.assemble(id)
            if (!GameConfig.Realm.meetsRealmRequirement(disciple.realm, stack.minRealm)) return@update
            val maxSlots = DiscipleStatCalculator.getMaxManualSlots(disciple)
            if (disciple.manualIds.size >= maxSlots) return@update
            if (stack.type == ManualType.MIND && disciple.manualIds.any { mid -> manualInstances.get(mid)?.type == ManualType.MIND }) return@update
            if (disciple.manualIds.any { mid -> manualInstances.get(mid)?.name == stack.name }) return@update
            val newQty = stack.quantity - 1
            if (newQty <= 0) manualStacks.remove(stackId) else manualStacks.update(stackId) { it.copy(quantity = newQty) }
            val instanceId = java.util.UUID.randomUUID().toString()
            val instance = stack.toInstance(id = instanceId, ownerId = discipleId, isLearned = true)
            manualInstances.add(instance)
            if (!disciple.manualIds.contains(instanceId)) {
                val hpDelta = stack.stats["hp"] ?: stack.stats["maxHp"] ?: 0
                val mpDelta = stack.stats["mp"] ?: stack.stats["maxMp"] ?: 0
                val rawHp = disciple.combat.currentHp; val rawMp = disciple.combat.currentMp
                val updatedDisciple = disciple.copy(manualIds = disciple.manualIds + instanceId, combat = disciple.combat.copy(currentHp = if (rawHp >= 0 && hpDelta > 0) rawHp + hpDelta else rawHp, currentMp = if (rawMp >= 0 && mpDelta > 0) rawMp + mpDelta else rawMp))
                discipleTables.remove(id)
                discipleTables.insert(updatedDisciple)
            }
        }
    }
}

// ── Cross-domain: Recruit ───────────────────────────────────────────

suspend fun GameEngine.recruitAllFromList(): Int {
    return engineContextDispatcher.withEngineContext {
        var recruited = 0
        stateStore.update {
            // 事务开头净化：损坏/重复/残留条目同事务移除（与点击招募一致），
            // 防一键招募时幽灵/双胞胎入宗门
            val sanitized = com.xianxia.sect.core.engine.service.RecruitService
                .sanitizeRecruitList(this)
            if (sanitized > 0) {
                DomainLog.w("GameEngine", "recruitAllFromList: 净化 recruitList $sanitized 条异常条目")
            }
            val count = gameData.recruitCountThisMonth.coerceAtLeast(0)
            val remaining = GameConfig.RECRUIT_MONTHLY_LIMIT - count
            if (remaining <= 0) {
                DomainLog.i("GameEngine", "recruitAllFromList: monthly limit reached ($count/${GameConfig.RECRUIT_MONTHLY_LIMIT})")
                pendingNotification = GameNotification.RecruitFailed(
                    "本月招募已达上限（${GameConfig.RECRUIT_MONTHLY_LIMIT}人）"
                )
                return@update
            }

            // 净化已移除损坏条目并去重，此处过滤为防御纵深
            val validRecruits = gameData.recruitList.filter(RecruitIntegrity::isValidRecruit)
            if (validRecruits.isEmpty()) {
                // 净化后损坏条目理论上不可达（防御保留）
                if (gameData.recruitList.isNotEmpty()) {
                    DomainLog.w("GameEngine", "recruitAllFromList: all ${gameData.recruitList.size} recruits are corrupted, skipped")
                }
                return@update
            }

            val takeCount = minOf(validRecruits.size, remaining)
            val toRecruit = validRecruits.take(takeCount)
            // 按 id 移除已招募条目（而非 data class 全字段 equals，
            // 防同 id 不同内容的残余条目存活）
            val toRecruitIds = toRecruit.map { it.id }.toSet()
            val keepInList = gameData.recruitList.filter { it.id !in toRecruitIds }

            val droppedCount = gameData.recruitList.size - validRecruits.size
            val currentMonth = gameData.gameYear * 12 + gameData.gameMonth
            var successCount = 0
            toRecruit.forEach { disciple ->
                val newId = discipleTables.allocateAndInsert(
                    disciple.copy(usage = disciple.usage.copy(recruitedMonth = currentMonth))
                        .also { it.lifeEvents = listOf("${disciple.age}岁：加入宗门") }
                )
                if (newId.isNotEmpty()) {
                    // 俘虏自带装备/功法落库为玩家实例（幂等）
                    materializeCaptiveGear(disciple, newId)
                    successCount++
                }
            }
            recruited = successCount
            gameData = gameData.copy(
                recruitList = keepInList,
                recruitCountThisMonth = gameData.recruitCountThisMonth + successCount
            )
            if (droppedCount > 0 || takeCount < validRecruits.size) {
                DomainLog.w("GameEngine", "recruitAllFromList: dropped $droppedCount corrupted, recruited $recruited, ${keepInList.size} remain (monthly limit)")
            } else {
                DomainLog.i("GameEngine", "recruitAllFromList: recruited $recruited disciples")
            }
        }
        return@withEngineContext recruited
    }
}

fun GameEngine.removeFromRecruitList(discipleId: String) {
    updateGameDataSync { it.copy(recruitList = it.recruitList.toList().filter { it.id != discipleId }) }
}

// ── Cross-domain: Spirit mine / patrol / salary ─────────────────────

fun GameEngine.validateAndFixSpiritMineData() {
    // P-8：unifiedState → 独立窄流直读
    val data = stateStore.gameData.value
    val discipleMap = stateStore.disciples.value.associateBy { it.id }
    val globalMines = data.placedBuildings.filter {
        BuildingFeatureRegistry.findByDisplayName(it.displayName)?.buildingType == BuildingType.MINING
    }
    val rebuiltSlots = mutableListOf<SpiritMineSlot>()
    var slotIdx = 0
    for (mine in globalMines) {
        for (offset in 0 until 3) {
            val existing = data.spiritMineSlots.getOrNull(slotIdx + offset)
            val slot = if (existing != null) {
                if (existing.discipleId.isNotEmpty() && existing.discipleId !in discipleMap) existing.copy(discipleId = "", discipleName = "", index = rebuiltSlots.size, buildingInstanceId = mine.instanceId) else existing.copy(index = rebuiltSlots.size, buildingInstanceId = mine.instanceId)
            } else SpiritMineSlot(index = rebuiltSlots.size, sectId = mine.sectId, buildingInstanceId = mine.instanceId)
            rebuiltSlots.add(slot)
        }
        slotIdx += 3
    }
    val finalSlots = rebuiltSlots.toList()
    if (finalSlots != data.spiritMineSlots) {
        updateGameDataSync { it.copy(spiritMineSlots = finalSlots) }
    }
}

fun GameEngine.updateSpiritMineSlots(slots: List<SpiritMineSlot>) { updateGameDataSync { it.copy(spiritMineSlots = slots) } }
fun GameEngine.updatePatrolSlots(slots: List<PatrolSlot>) { updateGameDataSync { it.copy(patrolSlots = slots) } }
fun GameEngine.updatePatrolConfig(config: PatrolConfig) { updateGameDataSync { it.copy(patrolConfig = config) } }
fun GameEngine.updatePatrolConfigs(configs: List<PatrolConfig>) { updateGameDataSync { it.copy(patrolConfigs = configs) } }

fun GameEngine.addSpiritStones(amount: Long) {
    gameEngineCore.launchInScope {
        stateStore.modifyState { spiritStoneWallet.add(this, amount, com.xianxia.sect.core.model.SpiritStoneGrade.LOW,
            com.xianxia.sect.core.wallet.SpiritStoneSource.Internal) }
    }
}

fun GameEngine.updateYearlySalary(newSalary: Map<Int, Int>) { updateGameDataSync { it.copy(yearlySalary = newSalary) } }

// ── Cross-domain: Missions ──────────────────────────────────────────

fun GameEngine.startMission(mission: Mission, selectedDisciples: List<Disciple>) {
    val data = stateStore.gameDataSnapshot
    val activeMission = MissionSystem.createActiveMission(mission, selectedDisciples, data.gameYear, data.gameMonth)
    updateGameDataSync { it.copy(activeMissions = it.activeMissions + activeMission) }
    selectedDisciples.forEach { disciple -> gameEngineCore.launchInScope { updateDiscipleStatus(disciple.id, DiscipleStatus.ON_MISSION) } }
}

suspend fun GameEngine.checkAndProcessCompletedMissions(): List<String> {
    val data = stateStore.gameDataSnapshot
    val completedIds = mutableListOf<String>()
    val remainingActive = mutableListOf<ActiveMission>()
    for (activeMission in data.activeMissions) {
        if (activeMission.isComplete(data.gameYear, data.gameMonth)) {
            completedIds.add(activeMission.id)
            val aliveDisciples = activeMission.discipleIds.mapNotNull { did -> stateStore.disciplesSnapshot.find { it.id == did && it.isAlive } }
            if (aliveDisciples.isNotEmpty()) {
                val equipMap = stateStore.equipmentInstancesSnapshot.associateBy { it.id }
                val manualMap = stateStore.manualInstancesSnapshot.associateBy { it.id }
                val proficiencies = data.manualProficiencies.mapValues { (_, list) -> list.associateBy { it.manualId } }
                val result = MissionSystem.processMissionCompletion(activeMission, aliveDisciples, equipMap, manualMap, proficiencies, battleSystem)
                applyMissionResult(result, activeMission, data.gameYear, data.gameMonth, aliveDisciples)
            }
            for (did in activeMission.discipleIds) {
                val disciple = stateStore.disciplesSnapshot.find { it.id == did }
                if (disciple != null && disciple.isAlive) gameEngineCore.launchInScope { updateDiscipleStatus(did, DiscipleStatus.IDLE) }
            }
        } else remainingActive.add(activeMission)
    }
    if (completedIds.isNotEmpty()) updateGameDataSync { it.copy(activeMissions = remainingActive) }
    return completedIds
}

private suspend fun GameEngine.applyMissionResult(
    result: MissionSystem.MissionResult,
    activeMission: ActiveMission,
    year: Int,
    month: Int,
    aliveDisciples: List<Disciple>
) {
    // 引导系统：累计完成任务
    incrementGuideCounter(GuideCounterKeys.MISSIONS_COMPLETED)
    if (result.spiritStones > 0) addSpiritStones(result.spiritStones.toLong())
    // 统一 quest 来源（对抗性审查 LOW-11 修复：材料/功法溢出邮件来源不再显示"未知"）
    inventorySystem.withTrackingSource("quest") {
        result.materials.forEach { material ->
            when (val r = inventorySystem.addMaterial(material)) {
                is DomainResult.Success -> {}
                is DomainResult.Partial -> DomainLog.w("GameEngine", "材料 ${material.name} 溢出 ${r.overflow} 个")
                is DomainResult.Failure -> DomainLog.w("GameEngine", "添加材料失败: ${r.error}")
            }
        }
        result.pills.forEach { pill ->
            when (val r = inventorySystem.addPill(pill)) {
                is DomainResult.Success -> {}
                is DomainResult.Partial -> DomainLog.w("GameEngine", "丹药 ${pill.name} 溢出 ${r.overflow} 个")
                is DomainResult.Failure -> DomainLog.w("GameEngine", "添加丹药失败: ${r.error}")
            }
        }
        result.equipmentStacks.forEach { equipment ->
            when (val r = inventorySystem.addEquipmentStack(equipment)) {
                is DomainResult.Success -> {}
                is DomainResult.Partial -> DomainLog.w("GameEngine", "装备 ${equipment.name} 溢出 ${r.overflow} 个")
                is DomainResult.Failure -> DomainLog.w("GameEngine", "添加装备失败: ${r.error}")
            }
        }
        result.manualStacks.forEach { manual ->
            when (val r = inventorySystem.addManualStack(manual)) {
                is DomainResult.Success -> {}
                is DomainResult.Partial -> DomainLog.w("GameEngine", "功法 ${manual.name} 溢出 ${r.overflow} 个")
                is DomainResult.Failure -> DomainLog.w("GameEngine", "添加功法失败: ${r.error}")
            }
        }
    }

    // 有战斗则写入战斗日志
    if (result.combatTriggered && result.battleResult != null) {
        val bsr = result.battleResult
        val logData = bsr.log
        val teamMembers = logData.teamMembers.map { m ->
            BattleLogMember(
                id = m.id, name = m.name, realm = m.realm, realmName = m.realmName,
                hp = m.hp, maxHp = m.maxHp, mp = m.mp, maxMp = m.maxMp,
                isAlive = m.isAlive, portraitRes = m.portraitRes
            )
        }
        val enemies = logData.enemies.map { e ->
            BattleLogEnemy(
                id = e.id, name = "敌人", realm = e.realm, realmName = e.realmName,
                hp = e.hp, maxHp = e.maxHp, isAlive = e.isAlive, portraitRes = e.portraitRes
            )
        }
        val rounds = logData.rounds.map { r ->
            BattleLogRound(
                roundNumber = r.roundNumber,
                actions = r.actions.map { a ->
                    BattleLogAction(
                        type = a.type, attacker = a.attacker, attackerType = a.attackerType,
                        target = a.target, damage = a.damage, damageType = a.damageType,
                        isCrit = a.isCrit, isKill = a.isKill, message = a.message,
                        skillName = a.skillName
                    )
                }
            )
        }
        val drops = mutableListOf<String>()
        if (result.spiritStones > 0) drops.add("灵石 ×${result.spiritStones}")
        result.materials.forEach { drops.add("${it.name} ×${it.quantity}") }
        result.pills.forEach { drops.add("${it.name} ×${it.quantity}") }
        result.equipmentStacks.forEach { drops.add("${it.name} ×${it.quantity}") }
        result.manualStacks.forEach { drops.add("${it.name} ×${it.quantity}") }

        gameEngineCore.launchInScope {
            stateStore.update {
                recordPlayerBattle(
                    year = year,
                    month = month,
                    type = BattleType.PVE,
                    attackerName = "玩家队伍",
                    defenderName = activeMission.missionName,
                    result = if (result.victory) BattleResult.WIN else BattleResult.LOSE,
                    teamMembers = teamMembers,
                    enemies = enemies,
                    rounds = rounds,
                    turns = bsr.turnCount,
                    details = "执行任务「${activeMission.missionName}」，" +
                        if (result.victory) "战斗胜利" else "战斗失利",
                    drops = drops
                )
            }
        }
    }
}

// ── Service delegates ───────────────────────────────────────────────

suspend fun GameEngine.completeExploration(teamId: String, success: Boolean, survivorIds: List<String>) = explorationService.completeExploration(teamId, success, survivorIds)
suspend fun GameEngine.redeemCode(code: String, usedCodes: List<String>, currentYear: Int, currentMonth: Int): RedeemResult = redeemCodeService.redeemCode(code, usedCodes, currentYear, currentMonth)
fun GameEngine.resetCultivationTimer() { cultivationService.resetHighFrequencyData() }
suspend fun GameEngine.checkpointAllProduction() { cultivationService.checkpointAllProduction() }
suspend fun GameEngine.checkpointAllDisciples() {
    engineContextDispatcher.withEngineContext {
        stateStore.update { cultivationService.checkpointAllDisciples(this) }
    }
}

// ── Private: Production slot fix ────────────────────────────────────

private suspend fun GameEngine.checkAndCollectCompletedSlots() {
    autoHarvestCompletedAlchemySlots()
    val forgeSlots = productionCoordinator.repository.getSlotsByBuildingId("forge")
    forgeSlots.forEach { slot -> if (slot.status == com.xianxia.sect.core.model.production.ProductionSlotStatus.COMPLETED) buildingService.autoHarvestForgeSlot(slot) }
}

private fun fixAlchemyForgeSlotCount(slots: List<ProductionSlot>, alchemyCount: Int, forgeCount: Int): List<ProductionSlot> {
    val result = slots.toMutableList()
    val alchemySlots = result.filter { it.buildingType == BuildingType.ALCHEMY }; result.removeAll { it.buildingType == BuildingType.ALCHEMY }
    val fixedAlchemy = mutableListOf<ProductionSlot>()
    alchemySlots.sortedBy { it.slotIndex }.take(alchemyCount).forEach { fixedAlchemy.add(it) }
    if (fixedAlchemy.size < alchemyCount) { val existingIndices = fixedAlchemy.map { it.slotIndex }.toSet(); var nextIdx = 0; while (fixedAlchemy.size < alchemyCount) { if (nextIdx !in existingIndices) fixedAlchemy.add(ProductionSlot.createIdle(slotIndex = nextIdx, buildingType = BuildingType.ALCHEMY, buildingId = "alchemy")); nextIdx++ } }
    result.addAll(fixedAlchemy)
    val forgeSlots = result.filter { it.buildingType == BuildingType.FORGE }; result.removeAll { it.buildingType == BuildingType.FORGE }
    val fixedForge = mutableListOf<ProductionSlot>()
    forgeSlots.sortedBy { it.slotIndex }.take(forgeCount).forEach { fixedForge.add(it) }
    if (fixedForge.size < forgeCount) { val existingIndices = fixedForge.map { it.slotIndex }.toSet(); var nextIdx = 0; while (fixedForge.size < forgeCount) { if (nextIdx !in existingIndices) fixedForge.add(ProductionSlot.createIdle(slotIndex = nextIdx, buildingType = BuildingType.FORGE, buildingId = "forge")); nextIdx++ } }
    result.addAll(fixedForge)
    return result
}

// ── Private: Migration ──────────────────────────────────────────────

private fun migratePatrolSlotsIfNeeded(gameData: GameData, disciples: List<Disciple>): Pair<GameData, List<Disciple>> {
    val numTowers = gameData.placedBuildings.count {
        BuildingFeatureRegistry.findByDisplayName(it.displayName)?.buildingType == BuildingType.PATROL
    }
    if (numTowers == 0) return gameData to disciples
    val oldSlots = gameData.patrolSlots
    val expectedSize = numTowers * 8
    if (oldSlots.size <= expectedSize) {
        // 即使数量正确，也需回填 buildingInstanceId（旧存档可能为空）
        val towers = gameData.placedBuildings.filter {
            BuildingFeatureRegistry.findByDisplayName(it.displayName)?.buildingType == BuildingType.PATROL
        }
        val needsBackfill = oldSlots.any { it.buildingInstanceId.isEmpty() }
        if (!needsBackfill) return gameData to disciples
        val backfilledSlots = mutableListOf<PatrolSlot>()
        var globalIdx = 0
        for (towerIdx in towers.indices) {
            val tower = towers[towerIdx]
            for (localIdx in 0 until 8) {
                if (globalIdx < oldSlots.size) {
                    backfilledSlots.add(oldSlots[globalIdx].copy(buildingInstanceId = tower.instanceId))
                }
                globalIdx++
            }
        }
        return gameData.copy(patrolSlots = backfilledSlots) to disciples
    }
    DomainLog.w("GameEngine", "迁移巡逻槽位: ${oldSlots.size}槽/${numTowers}塔 → ${expectedSize}槽")
    var updatedDisciples = disciples.toMutableList()
    val towers = gameData.placedBuildings.filter { it.displayName == "巡视楼" }
    val newSlots = mutableListOf<PatrolSlot>()
    var newGlobalIndex = 0
    for (towerIdx in 0 until numTowers) {
        val tower = towers[towerIdx]
        val oldStart = towerIdx * 10
        for (localIdx in 0 until 8) {
            val globalIdx = oldStart + localIdx
            if (globalIdx < oldSlots.size) newSlots.add(oldSlots[globalIdx].copy(index = newGlobalIndex, buildingInstanceId = tower.instanceId)) else newSlots.add(PatrolSlot(index = newGlobalIndex, buildingInstanceId = tower.instanceId))
            newGlobalIndex++
        }
        for (discardLocalIdx in 8 until 10) {
            val discardGlobalIdx = oldStart + discardLocalIdx
            if (discardGlobalIdx < oldSlots.size) { val discardedSlot = oldSlots[discardGlobalIdx]; if (discardedSlot.discipleId.isNotEmpty()) updatedDisciples = updatedDisciples.map { if (it.id == discardedSlot.discipleId) it.copy(status = DiscipleStatus.IDLE) else it }.toMutableList() }
        }
    }
    val orphanedStart = numTowers * 10
    for (i in orphanedStart until oldSlots.size) { val slot = oldSlots[i]; if (slot.discipleId.isNotEmpty()) updatedDisciples = updatedDisciples.map { if (it.id == slot.discipleId) it.copy(status = DiscipleStatus.IDLE) else it }.toMutableList() }
    val newConfigs = gameData.patrolConfigs.take(numTowers)
    DomainLog.i("GameEngine", "巡逻槽位迁移完成: ${oldSlots.size} → ${newSlots.size}")
    return gameData.copy(patrolSlots = newSlots, patrolConfigs = newConfigs) to updatedDisciples
}

// ── Memory ──────────────────────────────────────────────────────────

fun GameEngine.getMemoryUsageInfo(): String {
    val sb = StringBuilder()
    sb.appendLine("=== 内存使用情况 ===")
    sb.appendLine("弟子数量: ${stateStore.discipleTables.count}"); sb.appendLine("装备栈数量: ${stateStore.equipmentStacks.value.size}")
    sb.appendLine("装备实例数量: ${stateStore.equipmentInstances.value.size}"); sb.appendLine("功法栈数量: ${stateStore.manualStacks.value.size}")
    sb.appendLine("功法实例数量: ${stateStore.manualInstances.value.size}"); sb.appendLine("丹药数量: ${stateStore.pills.value.size}")
    sb.appendLine("材料数量: ${stateStore.materials.value.size}"); sb.appendLine("灵草数量: ${stateStore.herbs.value.size}")
    sb.appendLine("种子数量: ${stateStore.seeds.value.size}"); sb.appendLine("探索队伍: ${stateStore.teams.value.size}")
    sb.appendLine("战斗日志: ${stateStore.battleLogs.value.size}/50")
    return sb.toString()
}

@Suppress("DEPRECATION")
fun GameEngine.releaseMemory(level: Int) {
    val normalizedLevel = when (level) {
        android.content.ComponentCallbacks2.TRIM_MEMORY_MODERATE, android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW, 1 -> 1
        android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL, android.content.ComponentCallbacks2.TRIM_MEMORY_COMPLETE, 2 -> 2
        else -> { DomainLog.w("GameEngine", "未知的内存压力级别: $level，忽略"); return }
    }
    val logsToKeep = if (normalizedLevel == 1) 20 else 10
    val levelName = if (normalizedLevel == 1) "MODERATE" else "CRITICAL"
    gameEngineCore.launchInScope {
        stateStore.update {
            if (battleLogs.size > logsToKeep) { battleLogs = battleLogs.take(logsToKeep); DomainLog.d("GameEngine", "内存释放($levelName): 战斗日志已清理，保留最近$logsToKeep 条") }
            if (normalizedLevel == 2) {
                var trimmed = false
                val worldSects = gameData.worldMapSects
                if (worldSects.size > 30) {
                    val playerSect = worldSects.find { it.isPlayerSect }
                    val otherSects = worldSects.filter { !it.isPlayerSect }.sortedByDescending { s -> s.relation }.take(if (playerSect != null) 29 else 30)
                    val trimmedSects = if (playerSect != null) listOf(playerSect) + otherSects else otherSects
                    gameData = gameData.copy(worldMapSects = trimmedSects); trimmed = true; DomainLog.d("GameEngine", "内存释放($levelName): worldMapSects 裁剪至 ${trimmedSects.size} 个")
                }
                val caveTeams = gameData.caveExplorationTeams
                if (caveTeams.size > 15) { gameData = gameData.copy(caveExplorationTeams = caveTeams.take(15)); trimmed = true; DomainLog.d("GameEngine", "内存释放($levelName): caveExplorationTeams 裁剪至 15 个") }
                val aiCaveTeams = gameData.aiCaveTeams
                if (aiCaveTeams.size > 15) { gameData = gameData.copy(aiCaveTeams = aiCaveTeams.take(15)); trimmed = true; DomainLog.d("GameEngine", "内存释放($levelName): aiCaveTeams 裁剪至 15 个") }
                if (!trimmed) DomainLog.d("GameEngine", "内存释放($levelName): 无需裁剪其他列表")
            }
        }
    }

}

// ── 血炼原子操作 ────────────────────────────────────────────────────

/** 血炼启动结果 */
sealed interface BloodRefinementStartResult {
    data object Success : BloodRefinementStartResult
    data class InsufficientStones(
        val required: Long, val current: Long
    ) : BloodRefinementStartResult
    data class InsufficientMaterials(
        val materialName: String, val missing: Int
    ) : BloodRefinementStartResult
    data class Error(val message: String) : BloodRefinementStartResult
}

/**
 * 原子化启动血炼：灵石扣除、材料消耗、进度写入、弟子状态更新
 * 在同一 [stateStore.update] 事务中完成，失败时整体回滚。
 */
suspend fun GameEngine.startBloodRefinementAtomic(
    materialName: String,
    materialRarity: Int,
    materialCount: Int,
    buildingInstanceId: String,
    requiredSpiritStones: Long,
    progress: BloodRefinementProgress
): BloodRefinementStartResult {
    return engineContextDispatcher.withEngineContext {
        if (requiredSpiritStones <= 0) {
            return@withEngineContext BloodRefinementStartResult.Error("灵石消耗必须为正数")
        }
        if (materialCount <= 0) {
            return@withEngineContext BloodRefinementStartResult.Error("材料消耗必须为正数")
        }
        if (progress.durationMonths <= 0 || progress.bonusPercent <= 0.0) {
            return@withEngineContext BloodRefinementStartResult.Error("血炼配置异常（duration/bonus）")
        }
        progress.discipleId.toIntOrNull() ?: return@withEngineContext BloodRefinementStartResult.Error("非法弟子ID")
        try {
            stateStore.update {
                checkStones(requiredSpiritStones)
                consumeMaterial(materialName, materialRarity, materialCount)
                commitBloodRefinement(buildingInstanceId, requiredSpiritStones, progress)
            }
            return@withEngineContext BloodRefinementStartResult.Success
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DomainLog.e("GameEngine", "血炼原子启动失败: ${e.message}", e)
            return@withEngineContext BloodRefinementStartResult.Error(e.message ?: "未知错误")
        }
    }
}

/** 原子化取消血炼：移除进度 + 恢复弟子状态为空闲 */
suspend fun GameEngine.cancelBloodRefinement(
    buildingInstanceId: String,
    discipleId: String
) {
    return engineContextDispatcher.withEngineContext {
        stateStore.update {
            cancelBloodRefinement(buildingInstanceId, discipleId)
        }
    }
}

/** 月度结算 — 处理所有到期血炼 */
suspend fun GameEngine.processBloodRefinementCompletions() {
    return engineContextDispatcher.withEngineContext {
        stateStore.update { processBloodRefinementCompletions() }
    }
}

/** 校验灵石是否足够 */
private fun MutableGameState.checkStones(required: Long) {
    if (gameData.spiritStones < required) {
        error("灵石不足: 需要 $required, 当前 ${gameData.spiritStones}")
    }
}

/** 跨堆叠扣除材料 */
private fun MutableGameState.consumeMaterial(
    name: String, rarity: Int, count: Int
) {
    var remaining = count
    val matching = materials.all().filter {
        it.name == name && it.rarity == rarity && !it.isLocked
    }
    for (mat in matching) {
        if (remaining <= 0) break
        val take = minOf(remaining, mat.quantity)
        val newQty = mat.quantity - take
        if (newQty <= 0) materials.remove(mat.id)
        else materials.update(mat.id) { it.copy(quantity = newQty) }
        remaining -= take
    }
    if (remaining > 0) error("兽血材料不足: 缺少 $remaining 份 $name")
}

/** 扣除灵石并写入血炼进度 */
private fun MutableGameState.commitBloodRefinement(
    buildingInstanceId: String,
    requiredSpiritStones: Long,
    progress: BloodRefinementProgress
) {
    // 排他性检查：该建筑已有血炼
    if (buildingInstanceId in gameData.activeBloodRefinements) {
        error("该血炼池已有进行中的血炼")
    }
    // 排他性检查：该弟子已在其他血炼池中
    if (gameData.activeBloodRefinements.values.any {
        it.discipleId == progress.discipleId
    }) {
        error("该弟子已在其他血炼池中")
    }
    val filledProgress = progress.copy(
        startYear = gameData.gameYear, startMonth = gameData.gameMonth
    )
    gameData = gameData.copy(
        spiritStones = gameData.spiritStones - requiredSpiritStones,
        activeBloodRefinements = gameData.activeBloodRefinements +
            (buildingInstanceId to filledProgress)
    )
    val dId = progress.discipleId.toIntOrNull()
    if (dId != null && dId in discipleTables.ids) {
        discipleTables.statuses[dId] = DiscipleStatus.REFINING
        discipleTables.statusData[dId] = mapOf(
            "buildingId" to buildingInstanceId
        )
    }
}

/** 血炼属性 key → 显示名映射 */
private val STAT_DISPLAY_NAMES = mapOf(
    "hp" to "生命",
    "physicalAttack" to "物攻",
    "magicAttack" to "法攻",
    "physicalDefense" to "物防",
    "magicDefense" to "法防",
    "speed" to "速度"
)

/**
 * 月度结算 — 检查并处理所有到期血炼。
 * 遍历 [activeBloodRefinements]，对已到期的条目逐条结算。
 */
fun MutableGameState.processBloodRefinementCompletions() {
    if (gameData.activeBloodRefinements.isEmpty()) return
    val remaining = mutableMapOf<String, BloodRefinementProgress>()
    val originals = gameData.activeBloodRefinements
    for ((buildingId, progress) in originals) {
        val elapsed = TimeProgressUtil.calculateElapsedMonths(
            progress.startYear, progress.startMonth,
            gameData.gameYear, gameData.gameMonth
        )
        if (elapsed < progress.durationMonths) {
            remaining[buildingId] = progress
        } else {
            settleSingleRefinement(buildingId, progress)
        }
    }
    if (remaining.size != originals.size) {
        gameData = gameData.copy(activeBloodRefinements = remaining)
    }
}

/**
 * 结算单条到期血炼：累加百分比乘区、记录完成、
 * 重置弟子状态、发送通知。
 *
 * 新系统使用乘区百分比，直接累加材料百分比到累计记录，
 * 不再写入 DiscipleTables.base* 列。
 */
private fun MutableGameState.settleSingleRefinement(
    buildingId: String,
    progress: BloodRefinementProgress
) {
    val dId = progress.discipleId.toIntOrNull()
    if (dId == null || dId !in discipleTables.ids) return
    val statKey = progress.selectedStat
    if (statKey.isEmpty()) return

    // 防御：血炼期间弟子可能因其他系统死亡
    if (discipleTables.isAlive[dId] == 0) return

    // 百分比累加：只传增量（addPctToTotal 内部做 total + pct）
    val existingTotal = gameData.bloodRefinementPctTotals[progress.discipleId]
    val safeBonusPct = progress.bonusPercent.coerceAtLeast(0.0)

    val updatedTotal = if (existingTotal != null) {
        DiscipleStatCalculator.addPctToTotal(existingTotal, statKey, safeBonusPct)
    } else {
        DiscipleStatCalculator.addPctToTotal(
            BloodRefinementPctTotal(discipleId = progress.discipleId),
            statKey, safeBonusPct
        )
    }

    // ❌ 不再写入 DiscipleTables.base* 列（血炼改为乘法乘区，计算时动态应用）
    // ❌ 不再需要 calculateSimpleInterestBonus

    val existingRefinements =
        gameData.bloodRefinements[progress.discipleId] ?: emptyList()
    val updatedRefinements = existingRefinements + progress.materialId
    gameData = gameData.copy(
        bloodRefinements = gameData.bloodRefinements +
            (progress.discipleId to updatedRefinements),
        bloodRefinementPctTotals = gameData.bloodRefinementPctTotals +
            (progress.discipleId to updatedTotal)
    )

    discipleTables.clearBloodRefinementStatusData(dId)

    val statName = STAT_DISPLAY_NAMES[statKey] ?: statKey
    recordGameEvent(
        GameEventCategory.SECT, GameEventType.BLOOD_REFINEMENT,
        "${progress.discipleName}的血练已完成！属性「$statName」获得提升。",
        relatedEntityName = progress.discipleName
    )
}
private fun MutableGameState.cancelBloodRefinement(
    buildingInstanceId: String,
    discipleId: String
) {
    gameData = gameData.copy(
        activeBloodRefinements = gameData.activeBloodRefinements - buildingInstanceId
    )
    val dId = discipleId.toIntOrNull()
    if (dId != null && dId in discipleTables.ids) {
        discipleTables.clearBloodRefinementStatusData(dId)
    }
}

/** 仅清除血炼相关的 statusData key，不擦除其他系统写入的数据 */
private fun DiscipleTables.clearBloodRefinementStatusData(dId: Int) {
    val current = statusData.getOrDefault(dId, emptyMap())
    statusData[dId] = current - "buildingId"
}

// ════════════════════════════════════════════════════════════
// 2026-08-01 生命周期状态引擎线程入口（架构合规）
//
// 修复前 SaveLoadViewModel 及 3 个 SaveLoad delegate 直接操作
// GameStateStore（resetBootPhase/setIdle/setPausedDirect/update），
// 违反 CLAUDE.md 4.4 与双线程模型（主线程直写绕过引擎串行化）。
// 本组入口统一走引擎线程，UI 层只允许读 StateFlow。
// ════════════════════════════════════════════════════════════

/** 重置生命周期状态（读档/重启路径：bootPhase → UNINITIALIZED, runState → IDLE）。 */
suspend fun GameEngine.resetLifecycleState() {
    engineContextDispatcher.withEngineContext {
        stateStore.resetBootPhase()
        stateStore.setIdle()
    }
}

/** 引擎线程设置暂停标志（SaveLoad 流程用，替代 UI 直调 setPausedDirect）。 */
suspend fun GameEngine.setPausedDirectOnEngine(paused: Boolean) {
    engineContextDispatcher.withEngineContext {
        stateStore.setPausedDirect(paused)
    }
}

/** 引擎线程批量设置保存/加载标志（替代 UI 层多次 stateStore.update）。 */
/**
 * 引擎线程批量设置保存/加载标志（2026-08-01 对抗性审查修复）。
 *
 * 由 fire-and-forget（launchOnEngine）改为 suspend + withEngineContext——
 * 旧实现调用立即返回，finally 异常兜底路径的标志复位无时序保证
 * （下一次 load 的 setLoading(true) 可能在复位之后执行，加载期间引擎误推进）。
 * 调用方 await 后标志立即生效。
 */
suspend fun GameEngine.setSaveLoadFlags(isSaving: Boolean, isLoading: Boolean) {
    engineContextDispatcher.withEngineContext {
        stateStore.update {
            this.isSaving = isSaving
            this.isLoading = isLoading
        }
    }
}

/**
 * 建筑占地迁移状态应用（2026-08-01 引擎线程入口）。
 *
 * 修复前 SaveLoadLoadDelegate.migrateOverflowBuildings 在主线程直接
 * stateStore.update（读档路径违规）。计算（纯函数）留在 delegate，
 * 状态应用（灵石退款 + 建筑列表 + 槽位清理 + 弟子状态）走本入口。
 */
suspend fun GameEngine.applyBuildingMigrationOnEngine(
    kept: List<com.xianxia.sect.core.model.GridBuildingData>,
    totalRefund: Long,
    freedDiscipleIds: Set<String>
) {
    engineContextDispatcher.withEngineContext {
        stateStore.update {
            if (totalRefund > 0) {
                spiritStoneWallet.add(
                    this, totalRefund,
                    com.xianxia.sect.core.model.SpiritStoneGrade.LOW,
                    com.xianxia.sect.core.wallet.SpiritStoneSource.Refund
                )
            }
            var gd = gameData.copy(placedBuildings = kept)
            // 清除已拆除建筑的关联槽位数据
            val keptIds = kept.map { it.instanceId }.toSet()
            val removedIds = gameData.placedBuildings.map { it.instanceId }.toSet() - keptIds
            if (removedIds.isNotEmpty()) {
                gd = gd.copy(
                    productionSlots = gd.productionSlots.filter { it.buildingInstanceId !in removedIds },
                    residenceSlots = gd.residenceSlots.filter { it.buildingInstanceId !in removedIds },
                    spiritMineSlots = gd.spiritMineSlots.filter { it.buildingInstanceId !in removedIds },
                    patrolSlots = gd.patrolSlots.filter { it.buildingInstanceId !in removedIds },
                    warehouseGarrisons = gd.warehouseGarrisons.filter { it.buildingInstanceId !in removedIds },
                    activeBloodRefinements = gd.activeBloodRefinements.filterKeys { it !in removedIds }
                )
            }
            gameData = gd

            for (didStr in freedDiscipleIds) {
                val id = didStr.toIntOrNull() ?: continue
                if (discipleTables.ids.contains(id) && discipleTables.isAlive[id] == 1) {
                    discipleTables.statuses[id] = com.xianxia.sect.core.model.DiscipleStatus.IDLE
                }
            }
        }
    }
}
