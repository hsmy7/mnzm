package com.xianxia.sect.core.engine

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.map
import com.xianxia.sect.core.model.Alliance
import com.xianxia.sect.core.model.BattleLog
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.EquipmentInstance
import com.xianxia.sect.core.model.EquipmentStack
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.GridBuildingData
import com.xianxia.sect.core.model.Herb
import com.xianxia.sect.core.model.ManualInstance
import com.xianxia.sect.core.model.ManualStack
import com.xianxia.sect.core.model.Material
import com.xianxia.sect.core.model.PatrolConfig
import com.xianxia.sect.core.model.Pill
import com.xianxia.sect.core.model.SaveVersion
import com.xianxia.sect.core.model.SectDetail
import com.xianxia.sect.core.model.Seed
import com.xianxia.sect.core.model.SpiritMineSlot
import com.xianxia.sect.core.model.StorageBag
import com.xianxia.sect.core.model.usedExtendLifePillIds
import com.xianxia.sect.core.model.production.ProductionSlot
import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.util.DomainLog
import com.xianxia.sect.core.util.EngineEntropy
import java.util.UUID




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

/**
 * ⚠️ 调用方必须保证游戏循环已停止（stopGameLoopAndWait）后再调用本函数：
 * 循环 finally 的 JadeSymbolService.onLoopStop()（checkpointNow 绝对值覆盖写）
 * 晚于本函数替换 gameData 执行时，会用旧运行时值覆盖新档玉符四字段
 * （参照 SaveLoadViewModel.performLoadToSlot / applyCloudSaveToEngine 的
 * stopGameLoopAndWait 前置模式）。
 */
// 防御兜底: 异常源跨IO/SDK不可枚举, 降级继续+日志留痕, 非静默吞噬
@Suppress("UnusedParameter", "TooGenericExceptionCaught")
suspend fun GameEngine.loadData(
    gameData: GameData, disciples: List<Disciple>, equipmentStacks: List<EquipmentStack>,
    equipmentInstances: List<EquipmentInstance>, manualStacks: List<ManualStack>,
    manualInstances: List<ManualInstance>, pills: List<Pill>, materials: List<Material> = emptyList(),
    herbs: List<Herb> = emptyList(), seeds: List<Seed> = emptyList(),
    battleLogs: List<BattleLog> = emptyList(), alliances: List<Alliance> = emptyList(),
    productionSlots: List<ProductionSlot> = emptyList(),
    storageBags: List<StorageBag> = emptyList()
) {
    return engineContextDispatcher.withEngineContext {
        heavyDataLoaded = false
        // 丢弃年变延迟队列残留：旧档未保存的延迟组
        // 若不清除会作用于新档（跨存档污染——旧年 op 改新档数据）。
        // 存档时已 flush（"快照 ⇒ 队列已空"），此残留只可能是进程内未保存的脏 op。
        cultivationService.clearYearlyOpsQueue()
        // 重置招募惰性状态（纯运行时，不持久化）
        com.xianxia.sect.core.engine.service.RecruitService.resetAutoRecruitIdle()
        com.xianxia.sect.core.engine.service.RecruitService.resetAutoRejectIdle()
        // 迁移 + null 槽净化 + 幽灵过滤 + id 归一化 + 快照装载
        prepareLoadedGameData(
            gameData = gameData, disciples = disciples,
            equipmentStacks = equipmentStacks, equipmentInstances = equipmentInstances,
            manualStacks = manualStacks, manualInstances = manualInstances, pills = pills,
            materials = materials, herbs = herbs, seeds = seeds, storageBags = storageBags,
            battleLogs = battleLogs
        )
        // 丹药追踪字段迁移（必须在 stateStore.update 内执行，确保字段守卫通过）
        migratePillTrackingFieldsAfterLoad()
        // 读档自愈（第二道防线）：cache 命中路径绕过 SaveValidator，此处兜底
        // 净化 recruitList 的损坏/重复/已入宗门残留条目（幽灵弟子根治）
        sanitizeRecruitListAfterLoad()
        val restoredRecruitCount = stateStore.gameDataSnapshot.recruitList.size
        DomainLog.d(
            "GameEngine",
            "loadData: restored game year=${gameData.gameYear}, ${disciples.size} disciples, " +
                "recruitList=$restoredRecruitCount unrecruited disciples")
        restoreProductionSlotsForLoad(gameData, productionSlots)
        checkAndCollectCompletedSlots()
        // 双存储对齐（读档自愈）：以 Repository 为真源（restoreSlots 刚写入），
        // 写回镜像 gameData.productionSlots，消除历史分叉存档——镜像残留/缺失会导致
        // 状态推导与 UI 展示不一致（弟子自动脱离槽位/被自动任命其他槽位根因）
        alignProductionSlotsWithRepository()
        val currentData = stateStore.gameDataSnapshot
        // 旧存档兼容：merchantRefreshChances=0（该字段加入前的存档）初始化为1
        // 同时设置 lastGrantYear 防止下一年度事件双倍发放
        initMerchantRefreshChances(currentData)
        discipleService.syncAllDiscipleStatuses()
        // 旧存档兼容：spiritMineLastSettledMonth=0（该字段加入前的存档）会导致首月灵矿产出暴增
        // 检测到 0 且游戏已有进度时，初始化为当前月份
        initSpiritMineLastSettledMonth()
        // 邮件永久保留：resetAndInitSlot 不删除任何邮件，未领取的溢出/直发邮件跨读档保留
        try {
            mailService.resetAndInitSlot(gameData.slotId)
        } catch (e: CancellationException) {
            throw e // 取消穿透: 读档取消时中止, 不再进入 native 基线同步
        } catch (e: Exception) {
            DomainLog.e("GameEngine", "Failed to initialize mail for slot ${gameData.slotId}", e)
        }
        // 读档后把 Kotlin 新档状态导入 C++ native 引擎基线——
        // 否则 AUTHORITATIVE tick 反向镜像会把 native 残留的旧档状态覆盖回 Kotlin
        //（本地读档/云下载同路径）
        syncNativeBaselineAfterLoad()
    }
}

/**
 * 读档数据准备与快照装载：迁移 + null 生产槽净化 + 幽灵过滤 + id 归一化。
 * @Suppress 原因：装载参数聚合（12 参数，与 loadData 签名一致，纯透传无逻辑）
 */
@Suppress("LongParameterList")
private suspend fun GameEngine.prepareLoadedGameData(
    gameData: GameData, disciples: List<Disciple>, equipmentStacks: List<EquipmentStack>,
    equipmentInstances: List<EquipmentInstance>, manualStacks: List<ManualStack>,
    manualInstances: List<ManualInstance>, pills: List<Pill>, materials: List<Material>,
    herbs: List<Herb>, seeds: List<Seed>, storageBags: List<StorageBag>,
    battleLogs: List<BattleLog>
) {
    val (migratedGameData, migratedDisciples) = migratePatrolSlotsIfNeeded(gameData, disciples)
    // 防御（Bugly #13014）：损坏存档可能携带 null 生产槽位元素——
    // 在 fixAlchemyForgeSlotCount（内部访问 buildingType）与
    // gameData.productionSlots 直读（ProductionProcessor/StorageEngine）之前净化
    @Suppress("SENSELESS_COMPARISON")
    val safeGameData = if (migratedGameData.productionSlots.any { it == null }) {
        val cleaned = migratedGameData.productionSlots.filterNotNull()
        DomainLog.w(
            "GameEngine",
            "loadData: 净化 ${migratedGameData.productionSlots.size - cleaned.size} 个 null 生产槽位"
        )
        migratedGameData.copy(productionSlots = cleaned)
    } else {
        migratedGameData
    }
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
        gameData = safeGameData, disciples = idSafeDisciples,
        equipmentStacks = equipmentStacks, equipmentInstances = equipmentInstances,
        manualStacks = manualStacks, manualInstances = manualInstances, pills = pills,
        materials = materials, herbs = herbs, seeds = seeds, storageBags = storageBags,
        battleLogs = battleLogs
    )
}

/** 丹药追踪字段迁移：旧 functionalTypes/ExtendLifeIds/ActiveCategory 字段回填新表 */
private suspend fun GameEngine.migratePillTrackingFieldsAfterLoad() {
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
}

/** 读档 recruitList 自愈：净化损坏/重复/已入宗门残留条目 */
private suspend fun GameEngine.sanitizeRecruitListAfterLoad() {
    // 读档自愈（第二道防线）：cache 命中路径绕过 SaveValidator，此处兜底
    // 净化 recruitList 的损坏/重复/已入宗门残留条目（幽灵弟子根治）
    stateStore.update {
        val removed = com.xianxia.sect.core.engine.service.RecruitService
            .sanitizeRecruitList(this)
        if (removed > 0) {
            DomainLog.w("GameEngine", "loadData: 净化 recruitList $removed 条异常条目")
        }
    }
}

/** 读档 merchantRefreshChances 兼容：0 值初始化为 1 并设置 lastGrantYear */
private suspend fun GameEngine.initMerchantRefreshChances(currentData: GameData) {
    // 旧存档兼容：merchantRefreshChances=0（该字段加入前的存档）初始化为1
    // 同时设置 lastGrantYear 防止下一年度事件双倍发放
    if (currentData.merchantRefreshChances == 0 && currentData.merchantLastRefreshChanceGrantYear == 0) {
        stateStore.update {
            this.gameData = this.gameData.copy(
                merchantRefreshChances = 1,
                merchantLastRefreshChanceGrantYear = currentData.gameYear
            )
        }
        DomainLog.w("GameEngine",
            "loadData: merchantRefreshChances was 0, initialized to 1, lastGrantYear=${currentData.gameYear}")
    }
}

/** 读档 spiritMineLastSettledMonth 兼容：0 值且已有进度时初始化为当前月份 */
private suspend fun GameEngine.initSpiritMineLastSettledMonth() {
    // 旧存档兼容：spiritMineLastSettledMonth=0（该字段加入前的存档）会导致首月灵矿产出暴增
    // 检测到 0 且游戏已有进度时，初始化为当前月份
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
}

@Suppress("TooGenericExceptionCaught") // 防御兜底: 异常源跨IO/SDK不可枚举, 降级继续+日志留痕, 非静默吞噬
suspend fun GameEngine.createNewGame(sectName: String, currentSlot: Int = 1) {
    return engineContextDispatcher.withEngineContext {
        // 重置招募惰性状态（纯运行时，新游戏开始时清理）
        com.xianxia.sect.core.engine.service.RecruitService.resetAutoRecruitIdle()
        com.xianxia.sect.core.engine.service.RecruitService.resetAutoRejectIdle()
        stateStore.resetForSlot(currentSlot); cultivationService.resetHighFrequencyData()
        // 地图种子须在生成世界前产生；AI 分区 RNG 的播种由随后的
        // gameRngManager.initSystemSeed 统一完成（Kotlin 全分区 + C++ aiRng_ 同式
        // `seed + 6×31337`）——AI 弟子生成（generateWorldSects）必须使用已播种的
        // 确定性流，否则会退化为非确定性来源、初始弟子全员克隆。
        // 熵源 = EngineEntropy 显式会话熵（非分区 PRNG：mapSeed 是分区种子的上游，
        // 走分区构成循环依赖；且新档/重启本就要求"新世界"而非可复现重放）
        val mapSeed = EngineEntropy.nextWorldSeed()
        // 引擎线程播种 8 分区 RNG（线程契约：RNG 播种不得与
        // 引擎线程 RNG 消费并发；收敛到引擎线程同步完成，
        // 确保世界生成期的 GameRngManager 消费已确定性播种）
        gameRngManager.initSystemSeed(mapSeed.toLong())
        // 1. 先初始化世界和游戏状态（邮件依赖 gameData 就绪）
        initializeWorldAndServices(sectName, currentSlot)
        val gridCells = GameConfig.SectMap.WORLD_WIDTH_CELLS
        val centerGrid = gridCells / 2 - 1  // 4x4 building centered on grid
        stateStore.update {
            // 灵矿场占地 4×4（与 BuildingConfigService 默认配置 + BuildingFeatureBoot
            // spirit_mine + SpriteAtlasDef.FOOTPRINT_BY_NAME_INDEX 一致）。
            // 渲染按占地 footprint(4×4) 绘制，数据尺寸若小于 footprint 会导致
            // 新档首次会话只有中间 2×2 可点（fixupBuildingSizes 仅在读档执行）。
            // gridX/gridY 不变：渲染位置由配置 footprint 决定，与数据尺寸无关，无视觉偏移。
            val initialMine = GridBuildingData(
                buildingId = "灵矿场", displayName = "灵矿场",
                gridX = centerGrid, gridY = centerGrid,
                width = 4, height = 4,
                instanceId = java.util.UUID.randomUUID().toString(), sectId = ""
            )
            gameData = gameData.copy(
                slotId = currentSlot,
                currentSlot = currentSlot,
                mapSeed = mapSeed,
                // 新档必须盖章当前存档版本——否则以 saveVersion=0 落库，
                // 首次读档被 v0→1 迁移误 ÷10
                saveVersion = SaveVersion.CURRENT,
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
        try {
            mailService.resetAndInitSlot(currentSlot)
        } catch (e: CancellationException) {
            throw e // 取消穿透: 新档初始化取消时中止, 不再进入 native 基线同步
        } catch (e: Exception) {
            DomainLog.e("GameEngine", "Failed to init mail for new game slot $currentSlot", e)
        }
        // 新游戏世界状态导入 C++ native 引擎基线（同 loadData，
        // 防 AUTHORITATIVE tick 反向镜像把 native 残留旧档覆盖回新档）
        syncNativeBaselineAfterLoad()
    }
}

suspend fun GameEngine.restartGameSuspend(sectName: String = "", currentSlot: Int = 1) = restartGameInternal(sectName,
    currentSlot)

@Suppress("TooGenericExceptionCaught") // 防御兜底: 异常源跨IO/SDK不可枚举, 降级继续+日志留痕, 非静默吞噬
private suspend fun GameEngine.restartGameInternal(sectName: String, currentSlot: Int) {
    return engineContextDispatcher.withEngineContext {
        stateStore.resetForSlot(currentSlot); cultivationService.resetHighFrequencyData()
        // 每次重启生成新的地图/随机种子，避免全分区 PRNG 种子恒为 0、地图完全相同。
        // 熵源同 createNewGame = EngineEntropy 显式会话熵；AI 分区 RNG 的播种
        // 由下方的 gameRngManager.initSystemSeed 统一完成（Kotlin 全分区 + C++
        // aiRng_ 同式 `seed + 6×31337`），须在生成世界前完成
        val mapSeed = EngineEntropy.nextWorldSeed()
        // 全局分区重播种并入引擎
        // 重启操作——与 createNewGame 的"播种在引擎线程、生成世界前完成"模式
        // 对齐（线程契约：播种不得在 UI 协程跨线程执行）。
        // 注入 C++ PCG 真相源（委托模式）并重建本地分区。
        gameRngManager.initSystemSeed(mapSeed.toLong())
        if (sectName.isNotBlank()) {
            // 1. 先初始化世界和游戏状态
            initializeWorldAndServices(sectName, currentSlot)
            val gridCells = GameConfig.SectMap.WORLD_WIDTH_CELLS
            val centerGrid = gridCells / 2 - 1
            stateStore.update {
                // 灵矿场占地 4×4（与配置一致，同 createNewGameInternal）
                val initialMine = GridBuildingData(
                    buildingId = "灵矿场", displayName = "灵矿场",
                    gridX = centerGrid, gridY = centerGrid,
                    width = 4, height = 4,
                    instanceId = java.util.UUID.randomUUID().toString(), sectId = ""
                )
                gameData = gameData.copy(
                    slotId = currentSlot,
                    currentSlot = currentSlot,
                    mapSeed = mapSeed,
                    // 新档盖章当前存档版本（同 createNewGame）
                    saveVersion = SaveVersion.CURRENT,
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
            try {
                mailService.resetAndInitSlot(currentSlot)
            } catch (e: CancellationException) {
                throw e // 取消穿透: 重启取消时中止, 不再进入 native 基线同步
            } catch (e: Exception) {
                DomainLog.e("GameEngine", "Failed to init mail for restarted game slot $currentSlot", e)
            }
        } else {
            stateStore.update {
                gameData = GameData().copy(
                    currentSlot = currentSlot,
                    mapSeed = mapSeed,
                    saveVersion = SaveVersion.CURRENT
                )
            }
        }
        // 重启新世界状态导入 C++ native 引擎基线（同 loadData，
        // 防 AUTHORITATIVE tick 反向镜像把 native 残留旧世界覆盖回重启后的新世界）
        syncNativeBaselineAfterLoad()
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
