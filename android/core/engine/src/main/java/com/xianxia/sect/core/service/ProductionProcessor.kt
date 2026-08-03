package com.xianxia.sect.core.engine.service

import kotlinx.coroutines.launch
import com.xianxia.sect.core.engine.di.IoDispatcher
import kotlin.math.roundToInt
import com.xianxia.sect.core.model.*
import com.xianxia.sect.core.model.guide.GuideCounterKeys
import com.xianxia.sect.core.model.production.ProductionSlot
import com.xianxia.sect.core.model.production.ProductionSlotStatus
import com.xianxia.sect.core.engine.system.InventoryFactories
import com.xianxia.sect.core.state.*
import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.registry.*
import com.xianxia.sect.core.engine.system.InventorySystem
import com.xianxia.sect.core.engine.domain.production.ProductionCoordinator
import com.xianxia.sect.core.engine.domain.building.HerbGardenAuraService
import com.xianxia.sect.core.engine.domain.building.buildingFeatureDisplayNames
import com.xianxia.sect.core.engine.domain.building.SlotGroup
import com.xianxia.sect.core.registry.HerbDatabase
import com.xianxia.sect.core.repository.ProductionSlotRepository
import com.xianxia.sect.core.config.InventoryConfig
import com.xianxia.sect.core.engine.system.computeMaxSlots
import com.xianxia.sect.core.util.AppError
import com.xianxia.sect.core.util.BuildingNames
import com.xianxia.sect.core.util.CoroutineScopeProvider
import com.xianxia.sect.core.util.DomainLog
import com.xianxia.sect.core.util.DomainResult
import com.xianxia.sect.core.util.ZoneCalculator
import com.xianxia.sect.core.util.TimeProgressUtil
import com.xianxia.sect.core.engine.annotation.GameService
import com.xianxia.sect.core.util.GameRngManager
import com.xianxia.sect.core.util.RngPartition
import com.xianxia.sect.core.engine.LazyEvaluationDispatcher
import com.xianxia.sect.core.model.production.BuildingType
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
@GameService("ProductionProcessor")
class ProductionProcessor @Inject constructor(
    private val stateStore: GameStateStore,
    private val inventorySystem: InventorySystem,
    private val productionCoordinator: ProductionCoordinator,
    private val productionSlotRepository: ProductionSlotRepository,
    private val formulaService: FormulaService,
    private val rngManager: GameRngManager,
    private val scopeProvider: CoroutineScopeProvider,
    private val ioDispatcher: IoDispatcher,
    private val inventoryConfig: com.xianxia.sect.core.config.InventoryConfig,
) {

    companion object {
        private const val TAG = "ProductionProcessor"
        private const val PILL_GRADE_HIGH_THRESHOLD = 0.06
        private const val PILL_GRADE_MEDIUM_THRESHOLD = 0.40
        private const val SINGLE_RESIDENCE_SLOTS = 1
        private const val MULTI_RESIDENCE_SLOTS = 4
    }

    // ── 建筑生产 ──────────────────────────────────────────────────────

    fun processBuildingProduction(year: Int, month: Int) {
        processForgeCompletion(year, month)
        processAlchemyCompletion(year, month)
    }

    private fun processForgeCompletion(year: Int, month: Int) {
        val forgeSlots = productionSlotRepository.getSlotsByBuildingId(BuildingNames.FORGE)
        forgeSlots.forEach { slot ->
            if (slot.isWorking && slot.assignedDiscipleId.isNullOrEmpty()) return@forEach
            if (slot.isWorking && isSlotCompleteDynamic(slot, year, month)) {
                completeForgeSlot(slot)
                resetSlotToIdle(slot, BuildingNames.FORGE,
                    BuildingType.FORGE)
            }
        }
    }

    private fun processAlchemyCompletion(year: Int, month: Int) {
        val alchemySlots = productionSlotRepository.getSlotsByType(
            BuildingType.ALCHEMY)
        alchemySlots.forEach { slot ->
            if (slot.isWorking && slot.assignedDiscipleId.isNullOrEmpty()) return@forEach
            if (slot.isWorking && isSlotCompleteDynamic(slot, year, month)) {
                completeAlchemySlot(slot)
                resetSlotToIdle(slot, BuildingNames.ALCHEMY,
                    BuildingType.ALCHEMY)
            }
        }
    }

    private fun completeForgeSlot(slot: ProductionSlot) {
        val recipeId = slot.recipeId
        if (recipeId != null) {
            val recipe = ForgeRecipeDatabase.getRecipeById(recipeId)
            if (recipe != null) {
                val equipment = inventorySystem.createEquipmentFromRecipe(recipe)
                val r = inventorySystem.withTrackingSource("forge") {
                    inventorySystem.addEquipmentStack(equipment)
                }
                when (r) {
                    is DomainResult.Success -> { /* 添加成功 */ }
                    is DomainResult.Partial -> DomainLog.w(TAG, "装备 ${equipment.name} 溢出 ${r.overflow} 个")
                    is DomainResult.Failure -> DomainLog.w(TAG, "装备 ${equipment.name} 添加失败: ${r.error}")
                }
            }
        }
        slot.assignedDiscipleId?.let { discipleId ->
            stateStore.update {
                val currentCount = gameData.guideCounters[GuideCounterKeys.FORGE_COMPLETED] ?: 0L
                gameData = gameData.copy(
                    guideCounters = gameData.guideCounters + (GuideCounterKeys.FORGE_COMPLETED to currentCount + 1),
                    annualForgeCount = gameData.annualForgeCount + 1
                )
                val currentList = discipleTables.assembleAll()
                val updated = currentList.map {
                    if (it.id == discipleId) it.copy(status = DiscipleStatus.IDLE) else it
                }
                discipleTables.replaceAll(updated)
            }
        }
    }

    private fun completeAlchemySlot(slot: ProductionSlot) {
        val alchemyRng = rngManager.getRng(RngPartition.SYSTEM)
        val success = alchemyRng.nextDouble() <= slot.successRate
        if (success) {
            val roll = alchemyRng.nextDouble()
            val grade = when {
                roll < PILL_GRADE_HIGH_THRESHOLD -> PillGrade.HIGH
                roll < PILL_GRADE_MEDIUM_THRESHOLD -> PillGrade.MEDIUM
                else -> PillGrade.LOW
            }
            val template = slot.recipeId?.let { rid ->
                val baseId = rid.substringBeforeLast("_")
                ItemDatabase.getPillById("${baseId}_${grade.name.lowercase()}")
            }
            val pill = if (template != null) {
                ItemDatabase.createPillFromTemplate(template)
            } else {
                Pill(
                    name = slot.outputItemName,
                    rarity = slot.outputItemRarity,
                    grade = grade,
                    category = PillCategory.CULTIVATION,
                    description = "通过炼丹炉炼制而成",
                    minRealm = GameConfig.Realm.getMinRealmForRarity(slot.outputItemRarity),
                    quantity = 1
                )
            }
            val r = inventorySystem.withTrackingSource("alchemy") {
                inventorySystem.addPill(pill)
            }
            when (r) {
                is DomainResult.Success -> { /* 添加成功 */ }
                is DomainResult.Partial -> DomainLog.w(TAG, "丹药 ${pill.name} 溢出 ${r.overflow} 个")
                is DomainResult.Failure -> DomainLog.w(TAG, "丹药 ${pill.name} 添加失败: ${r.error}")
            }
        }
        slot.assignedDiscipleId?.let { discipleId ->
            stateStore.update {
                val currentCount = gameData.guideCounters[GuideCounterKeys.ALCHEMY_COMPLETED] ?: 0L
                gameData = gameData.copy(
                    guideCounters = gameData.guideCounters + (GuideCounterKeys.ALCHEMY_COMPLETED to currentCount + 1),
                    annualAlchemyCount = gameData.annualAlchemyCount + 1
                )
                val currentList = discipleTables.assembleAll()
                val updated = currentList.map {
                    if (it.id == discipleId && it.isAlive) {
                        it.copy(status = DiscipleStatus.IDLE)
                    } else it
                }
                discipleTables.replaceAll(updated)
            }
        }
    }

    private fun resetSlotToIdle(slot: ProductionSlot, buildingId: String,
                                 buildingType: BuildingType) {
        scopeProvider.scope.launch(ioDispatcher.dispatcher) {
            productionSlotRepository.updateSlotByBuildingId(buildingId, slot.slotIndex) { s ->
                ProductionSlot.createIdle(
                    id = s.id,
                    slotIndex = slot.slotIndex,
                    buildingType = buildingType,
                    buildingId = buildingId,
                    autoRestartEnabled = slot.autoRestartEnabled,
                    assignedDiscipleId = slot.assignedDiscipleId,
                    assignedDiscipleName = slot.assignedDiscipleName,
                    recipeId = slot.recipeId
                )
            }
        }
    }

    fun processSpiritFieldHarvest(state: MutableGameState) {
        val data = state.gameData
        val currentYear = data.gameYear
        val currentMonth = data.gameMonth
        val tables = state.discipleTables

        // 从影子组件表组装存活弟子（仅用于长老加成的属性查询）
        val allDisciples = tables.ids.filter { tables.isAlive[it] == 1 }
            .map { tables.assemble(it) }

        val plants = data.spiritFieldPlants
        if (plants.isEmpty()) return

        var updatedPlants = plants
        var hasChanges = false

        plants.forEach { plant ->
            if (plant.seedId.isEmpty() || plant.growTime <= 0) return@forEach

            val elapsedMonths = ((currentYear - plant.plantYear) * 12 +
                (currentMonth - plant.plantMonth)).coerceAtLeast(0)
            val speedBonus = calculateSpiritFieldMaturityBonus(plant, data, allDisciples)
            val effectiveGrowTime = HerbGardenAuraService.calculateEffectiveGrowTime(
                plant.growTime, speedBonus)

            if (elapsedMonths >= effectiveGrowTime) {
                val dbHerb = HerbDatabase.getHerbFromSeedName(plant.seedName)
                if (dbHerb == null) {
                    DomainLog.w(TAG, "processSpiritFieldHarvest: 未找到种子 " +
                        "${plant.seedName} 对应的灵草定义，跳过收获")
                    return@forEach
                }
                addHarvestedHerbsToState(plant, dbHerb, state)
                // 引导系统：累计收获灵植（annualHerbBySource 由 addHerb 内部按实际收获量累加）
                val prevHerbCount = state.gameData.guideCounters[GuideCounterKeys.HERBS_HARVESTED] ?: 0L
                state.gameData = state.gameData.copy(
                    guideCounters = state.gameData.guideCounters + (GuideCounterKeys.HERBS_HARVESTED to prevHerbCount + 1),
                    annualHerbCount = state.gameData.annualHerbCount + 1
                )

                val (newPlants, changed) = updateSlotAfterHarvest(
                    plant, state, currentYear, currentMonth, updatedPlants)
                if (changed) {
                    updatedPlants = newPlants
                    hasChanges = true
                }
            }
        }

        if (hasChanges) {
            state.gameData = data.copy(spiritFieldPlants = updatedPlants)
        }
    }

    /**
     * 将收获的灵草合并到传入的事务缓冲 state 中（自动类：PlantingSystem 月度自动触发）。
     *
     * 本方法直接操作 state 参数（区别于其他服务的 stateStore.update 模式——
     * 灵田收获由 PlantingSystem.onMonthlyEvent 传入事务缓冲），
     * 合并逻辑统一走 [StackableItemStore]（与 InventorySystem 主路径同一实现）；
     * 仓库满时溢出部分通过 [InventorySystem.sendOverflowMail] 转为邮件通知玩家
     * （自动类路径物品不丢失），年度报告按实际入库量累加。
     *
     * @return 实际入库数量
     */
    private fun addHarvestedHerbsToState(
        plant: SpiritFieldPlant,
        dbHerb: HerbDatabase.Herb,
        state: MutableGameState
    ): Int {
        val finalYield = plant.expectedYield.coerceAtLeast(1)
        val newHerb = Herb(
            id = java.util.UUID.randomUUID().toString(),
            name = dbHerb.name, rarity = dbHerb.rarity,
            description = dbHerb.description,
            category = dbHerb.category, quantity = finalYield
        )
        val otherTypes = state.equipmentStacks.size + state.manualStacks.size +
            state.pills.size + state.materials.size + state.seeds.size
        val store = StackableItemStore(
            initialItems = state.herbs.all(),
            stackKeyOf = StackKeys::herb,
            maxStack = inventoryConfig.getMaxStackSize("herb"),
            maxSlots = { state.computeMaxSlots() - otherTypes },
            notFound = { AppError.Domain.Inventory.NotFound(it) }
        )
        val result = store.add(newHerb)
        state.herbs.replaceAll(store.all())
        val actualAdded = when (result) {
            is DomainResult.Success -> finalYield
            is DomainResult.Partial -> {
                inventorySystem.sendOverflowMail(
                    "spirit_field", "herb", dbHerb.name, dbHerb.rarity, result.overflow
                )
                finalYield - result.overflow
            }
            is DomainResult.Failure -> {
                inventorySystem.sendOverflowMail(
                    "spirit_field", "herb", dbHerb.name, dbHerb.rarity, finalYield
                )
                0
            }
        }
        if (actualAdded < finalYield) {
            DomainLog.w(
                TAG,
                "灵田收获 ${dbHerb.name} 仓库空间不足，实际入库 $actualAdded/$finalYield（溢出已转邮件）"
            )
        }
        state.gameData = state.gameData.copy(
            annualHerbBySource = state.gameData.annualHerbBySource +
                ("spirit_field" to (state.gameData.annualHerbBySource["spirit_field"] ?: 0) + actualAdded)
        )
        return actualAdded
    }

    /**
     * 收获后处理灵田槽位：消耗种子重新种植或清空槽位。
     *
     * @return Pair(更新后的 plants 列表, 是否有变化)
     */
    private fun updateSlotAfterHarvest(
        plant: SpiritFieldPlant,
        state: MutableGameState,
        currentYear: Int,
        currentMonth: Int,
        updatedPlants: List<SpiritFieldPlant>
    ): Pair<List<SpiritFieldPlant>, Boolean> {
        val idx = updatedPlants.indexOfFirst {
            it.buildingInstanceId == plant.buildingInstanceId
        }
        if (idx < 0) return updatedPlants to false

        val matchingSeed = HerbDatabase.getSeedByName(plant.seedName)
        val existingSeed = state.seeds.all().find { s ->
            s.name == plant.seedName &&
                s.rarity == (matchingSeed?.rarity ?: 1) &&
                s.growTime == plant.growTime && s.quantity > 0
        }
        val currentAbsoluteMonth = LazyEvaluationDispatcher.toAbsoluteMonth(
            currentYear, currentMonth)
        val newPlants = updatedPlants.toMutableList().also {
            if (existingSeed != null) {
                val newQty = existingSeed.quantity - 1
                if (newQty <= 0) {
                    state.seeds.remove(existingSeed.id)
                } else {
                    state.seeds.update(existingSeed.id) { it.copy(quantity = newQty) }
                }
                it[idx] = it[idx].copy(
                    plantYear = currentYear, plantMonth = currentMonth,
                    completionMonth = currentAbsoluteMonth +
                        plant.growTime.coerceAtLeast(1),
                    completionPhase = 3
                )
            } else {
                it[idx] = it[idx].copy(
                    seedId = "", seedName = "", growTime = 0, expectedYield = 0,
                    plantYear = 0, plantMonth = 0,
                    completionMonth = 0, completionPhase = 1
                )
            }
        }
        return newPlants to true
    }

    /**
     * 灵植成熟速度乘区（Herb Garden Maturity Zone）。
     *
     * 公式：有效生长时间 = ceil(baseGrowTime / ((1 + elderZone) × (1 + auraZone) × (1 + policyZone)))
     */
    data class HerbGardenMaturityZones(
        val elderZone: Double = 0.0,   // 灵植长老乘区
        val auraZone: Double = 0.0,    // 光环弟子乘区
        val policyZone: Double = 0.0,  // 灵药培育政策乘区
    ) {
        /** 计算总加速倍率（纯加成值，如 0.155 = 15.5%） */
        fun totalMultiplier(): Double =
            ZoneCalculator.calculate(1.0, elderZone, auraZone, policyZone) - 1.0
    }

    fun calculateSpiritFieldMaturityBonus(
        plant: SpiritFieldPlant,
        gameData: GameData,
        allDisciples: List<Disciple>
    ): Double {
        val zones = buildHerbGardenMaturityZones(plant, gameData, allDisciples)
        return zones.totalMultiplier()
    }

    private fun buildHerbGardenMaturityZones(
        plant: SpiritFieldPlant,
        gameData: GameData,
        allDisciples: List<Disciple>
    ): HerbGardenMaturityZones {
        val elderBonus = HerbGardenAuraService.calculateElderMaturityBonus(
            gameData.elderSlots, allDisciples
        )
        val herbPolicyBonus = if (gameData.sectPolicies.herbCultivation) {
            GameConfig.PolicyConfig.HERB_CULTIVATION_EFFECT
        } else 0.0
        val springBonus = if (gameData.sectPolicies.spiritSpring) {
            GameConfig.PolicyConfig.SPIRIT_SPRING_YIELD
        } else 0.0
        val policyBonus = herbPolicyBonus + springBonus
        val auraBonus = if (HerbGardenAuraService.isSpiritFieldInAura(
                plant.buildingInstanceId, gameData.placedBuildings
            )) {
            HerbGardenAuraService.calculateAuraMaturityBonus(
                gameData.elderSlots, allDisciples)
        } else 0.0

        return HerbGardenMaturityZones(
            elderZone = elderBonus,
            auraZone = auraBonus,
            policyZone = policyBonus
        )
    }

    suspend fun processAutoAlchemy() {
        val data = stateStore.gameData.value

        val alchemySlots = productionSlotRepository.getSlotsByType(BuildingType.ALCHEMY)
        val idleSlotIndices = alchemySlots
            .filter { it.autoRestartEnabled
                && it.status == ProductionSlotStatus.IDLE
                && it.assignedDiscipleId.isNullOrEmpty().not() }
            .map { it.slotIndex }
        if (idleSlotIndices.isEmpty()) return

        val alchemyPolicyBonus = if (data.sectPolicies.alchemyIncentive)
            GameConfig.PolicyConfig.ALCHEMY_INCENTIVE_EFFECT else 0.0

        val allDisciples = stateStore.disciples.value

        for (slotIndex in idleSlotIndices) {
            val slot = alchemySlots.find { it.slotIndex == slotIndex } ?: continue
            processAutoAlchemySlot(slot, data, allDisciples, alchemyPolicyBonus)
        }
    }

    private suspend fun processAutoAlchemySlot(
        slot: ProductionSlot,
        data: GameData,
        allDisciples: List<Disciple>,
        alchemyPolicyBonus: Double
    ) {
        val currentHerbs = stateStore.getCurrentHerbs()
        val slotIndex = slot.slotIndex

        // 验证弟子仍存活且空闲（防止自动重启窗口期内弟子被调走）
        val disciple = slot.assignedDiscipleId?.let { id ->
            allDisciples.find { it.id == id }
        }
        if (disciple == null || !disciple.isAlive || (disciple.status != DiscipleStatus.IDLE && disciple.status != DiscipleStatus.ALCHEMY)) {
            // 弟子不可用 → 清除槽位关联，等待玩家手动处理
            scopeProvider.scope.launch(ioDispatcher.dispatcher) {
                productionSlotRepository.updateSlotByBuildingId(BuildingNames.ALCHEMY, slotIndex) { s ->
                    s.copy(assignedDiscipleId = null, assignedDiscipleName = "")
                }
            }
            return
        }

        val recipeToStart = slot.recipeId
            ?.let { prevRecipeId ->
                PillRecipeDatabase.getRecipeById(prevRecipeId)?.takeIf { recipe ->
                    recipe.materials.all { (materialId, requiredQuantity) ->
                        val herbData = HerbDatabase.getHerbById(materialId)
                            ?: return@all false
                        currentHerbs.filter {
                            it.name == herbData.name && it.rarity == herbData.rarity
                        }.sumOf { it.quantity } >= requiredQuantity
                    }
                }
            }
            ?: PillRecipeDatabase.findBestCraftableRecipe(currentHerbs) ?: return

        val result = productionCoordinator.startAlchemyAtomic(
            slotIndex = slotIndex,
            recipeId = recipeToStart.id,
            currentYear = data.gameYear,
            currentMonth = data.gameMonth,
            herbs = currentHerbs,
            buildingId = BuildingNames.ALCHEMY,
            alchemyPolicyBonus = alchemyPolicyBonus
        )

        if (result is DomainResult.Success) {
            stateStore.update {
                this.herbs.replaceAll(result.data.materialUpdate.herbs)
            }
            // 用 FormulaService 重算 duration（startAlchemyAtomic 写入的是原始值）
            val actualDuration = formulaService.calculateWorkDurationWithAllDisciples(
                recipeToStart.duration, BuildingNames.ALCHEMY)
            val absMonth = data.gameYear * 12 + data.gameMonth
            scopeProvider.scope.launch(ioDispatcher.dispatcher) {
                productionSlotRepository.updateSlotByBuildingId(BuildingNames.ALCHEMY, slotIndex) { s ->
                    s.copy(
                        duration = actualDuration,
                        baseDuration = recipeToStart.duration,
                        completionMonth = absMonth + actualDuration.coerceAtLeast(1)
                    )
                }
            }
        }
    }

    suspend fun processAutoForge() {
        val data = stateStore.gameData.value

        val forgeSlots = productionSlotRepository.getSlotsByBuildingId(BuildingNames.FORGE)
        val idleSlotIndices = forgeSlots
            .filter { it.autoRestartEnabled
                && it.status == ProductionSlotStatus.IDLE
                && it.assignedDiscipleId.isNullOrEmpty().not() }
            .map { it.slotIndex }
        if (idleSlotIndices.isEmpty()) return

        val allRecipes = ForgeRecipeDatabase.getAllRecipes().sortedByDescending { it.rarity }
        val forgePolicyBonus = if (data.sectPolicies.forgeIncentive)
            GameConfig.PolicyConfig.FORGE_INCENTIVE_EFFECT else 0.0

        val allDisciples = stateStore.disciples.value

        for (slotIndex in idleSlotIndices) {
            val slot = forgeSlots.find { it.slotIndex == slotIndex } ?: continue
            if (!processAutoForgeSlot(
                    slot, data, allDisciples, forgePolicyBonus, allRecipes)) {
                break
            }
        }
    }

    /**
     * @return true 表示继续循环下一个槽位，false 表示中断循环
     */
    private suspend fun processAutoForgeSlot(
        slot: ProductionSlot,
        data: GameData,
        allDisciples: List<Disciple>,
        forgePolicyBonus: Double,
        allRecipes: List<ForgeRecipeDatabase.ForgeRecipe>
    ): Boolean {
        val currentMaterials = stateStore.getCurrentMaterials()
        val materialIndex = currentMaterials.groupBy { it.name to it.rarity }
            .mapValues { (_, list) -> list.sumOf { it.quantity } }
        val slotIndex = slot.slotIndex

        // 验证弟子仍存活且空闲
        val disciple = slot.assignedDiscipleId?.let { id ->
            allDisciples.find { it.id == id }
        }
        if (disciple == null || !disciple.isAlive || (disciple.status != DiscipleStatus.IDLE && disciple.status != DiscipleStatus.FORGE)) {
            scopeProvider.scope.launch(ioDispatcher.dispatcher) {
                productionSlotRepository.updateSlotByBuildingId(BuildingNames.FORGE, slotIndex) { s ->
                    s.copy(assignedDiscipleId = null, assignedDiscipleName = "")
                }
            }
            return true
        }

        val recipeToStart = slot.recipeId
            ?.let { prevRecipeId ->
                allRecipes.find { it.id == prevRecipeId }?.takeIf { recipe ->
                    recipe.materials.all { (materialId, requiredQuantity) ->
                        val materialData = BeastMaterialDatabase.getMaterialById(materialId)
                        materialData != null && run {
                            val available = materialIndex[materialData.name to materialData.rarity] ?: 0
                            available >= requiredQuantity
                        }
                    }
                }
            }
            ?: allRecipes.firstOrNull { recipe ->
                recipe.materials.all { (materialId, requiredQuantity) ->
                    val materialData = BeastMaterialDatabase.getMaterialById(materialId)
                    materialData != null && run {
                        val available = materialIndex[materialData.name to materialData.rarity] ?: 0
                        available >= requiredQuantity
                    }
                }
            } ?: return true

        val result = productionCoordinator.startForgingAtomic(
            slotIndex = slotIndex,
            recipeId = recipeToStart.id,
            currentYear = data.gameYear,
            currentMonth = data.gameMonth,
            materials = currentMaterials,
            buildingId = BuildingNames.FORGE,
            forgePolicyBonus = forgePolicyBonus
        )

        if (result is DomainResult.Success) {
            stateStore.update {
                this.materials.replaceAll(result.data.materialUpdate.materials)
            }
            return true
        }
        return false
    }

    fun processAutoAssign(state: MutableGameState) {
        val data = state.gameData
        val policies = data.sectPolicies
        val idleDisciples = state.discipleTables.assembleAll()
            .filter { d -> d.status == DiscipleStatus.IDLE && d.isAlive }
            .toMutableList()


        val occupiedResidentIds = data.residenceSlots
            .filter { it.discipleId.isNotEmpty() }
            .map { it.discipleId }
            .toSet()
        val allAssignments = computeResidenceAssignments(state, data, policies, occupiedResidentIds)
        idleDisciples.removeAll { it.id in allAssignments.values.map { it.first }.toSet() }

        // ── 生产槽位候选预计算（按优先级逐级筛选，候选从 idleDisciples 移除） ──
        val herbCandidates = if (policies.autoPlantFocused || policies.autoPlantRootCounts.isNotEmpty()) {
            val sorted = precomputeCandidates(
                idleDisciples,
                policies.autoPlantFocused, policies.autoPlantRootCounts,
                policies.autoPlantThreshold
            ) { it.spiritPlanting }
            // 从池中移除已选中弟子
            sorted.forEach { idleDisciples.remove(it) }
            sorted
        } else emptyList()

        val mineCandidates = if (policies.autoMineFocused || policies.autoMineRootCounts.isNotEmpty()) {
            val sorted = precomputeCandidates(
                idleDisciples,
                policies.autoMineFocused, policies.autoMineRootCounts,
                policies.autoMineThreshold
            ) { it.mining }
            sorted.forEach { idleDisciples.remove(it) }
            sorted
        } else emptyList()

        val alchemyCandidates = if (policies.autoAlchemyFocused || policies.autoAlchemyRootCounts.isNotEmpty()) {
            val sorted = precomputeCandidates(
                idleDisciples,
                policies.autoAlchemyFocused, policies.autoAlchemyRootCounts,
                policies.autoAlchemyThreshold
            ) { it.pillRefining }
            sorted.forEach { idleDisciples.remove(it) }
            sorted
        } else emptyList()

        val forgeCandidates = if (policies.autoForgeFocused || policies.autoForgeRootCounts.isNotEmpty()) {
            precomputeCandidates(
                idleDisciples,
                policies.autoForgeFocused, policies.autoForgeRootCounts,
                policies.autoForgeThreshold
            ) { it.artifactRefining }
        } else emptyList()

        // ══════════════════════════════════════════════════════════════════
        // 单次原子写入（所有 5 步骤在同一事务内完成，由调用方 stateStore.update 包裹）
        // ══════════════════════════════════════════════════════════════════
        if (allAssignments.isEmpty() && herbCandidates.isEmpty() && mineCandidates.isEmpty()
            && alchemyCandidates.isEmpty() && forgeCandidates.isEmpty()) return

        val herbIter = herbCandidates.iterator()
        val mineIter = mineCandidates.iterator()
        val alchemyIter = alchemyCandidates.iterator()
        val forgeIter = forgeCandidates.iterator()

        // 1. 住所写入 + 状态同步
        if (allAssignments.isNotEmpty()) {
            val writtenIds = mutableSetOf<String>()
            state.gameData = state.gameData.copy(
                residenceSlots = state.gameData.residenceSlots.map { slot ->
                    val key = "${slot.buildingInstanceId}:${slot.slotIndex}"
                    val assignment = allAssignments[key]
                    if (assignment != null && slot.discipleId.isEmpty()
                        && assignment.first !in writtenIds
                    ) {
                        writtenIds.add(assignment.first)
                        slot.copy(discipleId = assignment.first, discipleName = assignment.second)
                    } else slot
                }
            )
        }

        // 2. 灵植（使用预计算候选迭代器）
        if (herbCandidates.isNotEmpty()) {
            batchAssignToProductionSlots(
                BuildingType.HERB_GARDEN, BuildingNames.HERB_GARDEN,
                { if (herbIter.hasNext()) herbIter.next() else null }, state
            )
        }

        // 3. 灵矿（inline 写入 + 状态同步）
        if (mineCandidates.isNotEmpty()) {
            val mineAssignments = mineCandidates.map { it.id to it.name }
            val mineAssignIter = mineAssignments.iterator()
            state.gameData = state.gameData.copy(
                spiritMineSlots = state.gameData.spiritMineSlots.map { slot ->
                    if (slot.discipleId.isEmpty() && mineAssignIter.hasNext()) {
                        val (id, name) = mineAssignIter.next()
                        slot.copy(discipleId = id, discipleName = name)
                    } else slot
                }
            )
        }

        // 4. 炼丹（使用预计算候选迭代器）
        if (alchemyCandidates.isNotEmpty()) {
            batchAssignToProductionSlots(
                BuildingType.ALCHEMY, BuildingNames.ALCHEMY,
                { if (alchemyIter.hasNext()) alchemyIter.next() else null }, state
            )
        }

        // 5. 锻造（使用预计算候选迭代器）
        if (forgeCandidates.isNotEmpty()) {
            batchAssignToProductionSlots(
                BuildingType.FORGE, BuildingNames.FORGE,
                { if (forgeIter.hasNext()) forgeIter.next() else null }, state
            )
        }
    }

    /**
     * 批量安排弟子到指定生产建筑的所有空闲槽位。
     *
     * 从 [MutableGameState.productionSlots] 读取/写入，确保与 stateStore 在同一事务内。
     */
    private fun batchAssignToProductionSlots(
        type: BuildingType,
        buildingId: String,
        takeNext: () -> Disciple?,
        state: MutableGameState
    ) {
        val slots = state.gameData.productionSlots.filter { it.buildingType == type }
        val emptySlots = slots.filter { slot ->
            slot.assignedDiscipleId.isNullOrEmpty()
                && slot.status == ProductionSlotStatus.IDLE
        }
        if (emptySlots.isEmpty()) return

        val assignedStatus = when (type) {
            BuildingType.ALCHEMY -> DiscipleStatus.ALCHEMY
            BuildingType.FORGE -> DiscipleStatus.FORGE
            BuildingType.HERB_GARDEN -> DiscipleStatus.SPIRIT_PLANTING
            else -> DiscipleStatus.IDLE
        }

        val updates = mutableMapOf<Int, Pair<String, String>>() // slotIndex → (discipleId, discipleName)
        for (emptySlot in emptySlots) {
            val candidate = takeNext() ?: break
            updates[emptySlot.slotIndex] = candidate.id to candidate.name
            val cid = candidate.id.toIntOrNull()
            if (cid == null) {
                DomainLog.w(TAG, "batchAssignToProductionSlots: invalid disciple id ${candidate.id}")
            }
        }
        if (updates.isNotEmpty()) {
            state.gameData = state.gameData.copy(
                productionSlots = state.gameData.productionSlots.map { slot ->
                    val update = updates[slot.slotIndex]
                    if (update != null) slot.copy(
                        assignedDiscipleId = update.first,
                        assignedDiscipleName = update.second
                    ) else slot
                }
            )
        }
    }

    fun isDiscipleFollowed(d: Disciple): Boolean {
        return d.statusData["followed"] == "true"
    }

    // ═══════════════════════════════════════════════════════════════
    // 影子状态批量生产方法
    //
    // 操作 [MutableGameState]（shadow）和 [MutableList]（productionSlots），
    // 不走 Repository/stateStore，用于并行 computePhaseTick。
    // 与现有同名方法的区别：所有 I/O 方向改为本地列表 + state 字段。
    // ═══════════════════════════════════════════════════════════════

    /**
     * 在影子状态上模拟 N 个月的生产循环。
     * 可由 [ProductionSubsystem.computePhaseTick] 在 ParallelDispatcher 上调用。
     */
    fun processMonthlyProductionOnSlots(
        slots: MutableList<ProductionSlot>,
        state: MutableGameState,
        months: Int
    ) {
        repeat(months) {
            batchAutoAlchemy(slots, state)
            batchAutoForge(slots, state)
            batchBuildingCompletion(slots, state)
            batchSpiritFieldHarvest(slots, state)
        }
    }

    /** 影子版自动炼丹：从 state 读取政策/草药，直接修改 slots */
    private fun batchAutoAlchemy(
        slots: MutableList<ProductionSlot>,
        state: MutableGameState
    ) {
        val gd = state.gameData
        val policyBonus = if (gd.sectPolicies.alchemyIncentive)
            GameConfig.PolicyConfig.ALCHEMY_INCENTIVE_EFFECT else 0.0

        val idleSlotIndices = slots
            .filter { it.buildingType == BuildingType.ALCHEMY }
            .filter { it.autoRestartEnabled && it.status == ProductionSlotStatus.IDLE
                && !it.assignedDiscipleId.isNullOrEmpty() }
            .map { it.slotIndex }

        for (slotIndex in idleSlotIndices) {
            val currentHerbs = state.herbs.all()
            val recipeToStart = findRecipe(currentHerbs) ?: break
            val slotIdx = slots.indexOfFirst {
                it.buildingType == BuildingType.ALCHEMY && it.slotIndex == slotIndex
            }
            if (slotIdx < 0) continue

            // 消耗材料
            consumeHerbsForRecipeLocal(recipeToStart.materials, currentHerbs, state)
            val absoluteMonth = gd.gameYear * 12 + gd.gameMonth

            val assignedId = slots[slotIdx].assignedDiscipleId
            val assignedName = slots[slotIdx].assignedDiscipleName
            slots[slotIdx] = slots[slotIdx].copy(
                status = ProductionSlotStatus.WORKING,
                recipeId = recipeToStart.id,
                recipeName = recipeToStart.name,
                startYear = gd.gameYear,
                startMonth = gd.gameMonth,
                duration = recipeToStart.duration,
                baseDuration = recipeToStart.duration,
                successRate = (recipeToStart.successRate + policyBonus).coerceIn(0.0, 1.0),
                completionMonth = absoluteMonth + recipeToStart.duration.coerceAtLeast(1),
                completionPhase = 3,
                outputItemId = recipeToStart.id,
                outputItemName = recipeToStart.name,
                outputItemRarity = recipeToStart.rarity
            )
        }
    }

    /** 影子版自动锻造 */
    private fun batchAutoForge(
        slots: MutableList<ProductionSlot>,
        state: MutableGameState
    ) {
        val gd = state.gameData
        val policyBonus = if (gd.sectPolicies.forgeIncentive)
            GameConfig.PolicyConfig.FORGE_INCENTIVE_EFFECT else 0.0
        val allRecipes = ForgeRecipeDatabase.getAllRecipes().sortedByDescending { it.rarity }
        val materialIndex = state.materials.all().groupBy { it.name to it.rarity }
            .mapValues { (_, list) -> list.sumOf { it.quantity } }

        val idleSlotIndices = slots
            .filter { it.buildingType == BuildingType.FORGE }
            .filter { it.autoRestartEnabled && it.status == ProductionSlotStatus.IDLE
                && !it.assignedDiscipleId.isNullOrEmpty() }
            .map { it.slotIndex }

        for (slotIndex in idleSlotIndices) {
            val recipeToStart = findForgeRecipe(allRecipes, materialIndex) ?: break
            val slotIdx = slots.indexOfFirst {
                it.buildingType == BuildingType.FORGE && it.slotIndex == slotIndex
            }
            if (slotIdx < 0) continue

            consumeMaterialsForRecipeLocal(recipeToStart.materials, state)
            val absoluteMonth = gd.gameYear * 12 + gd.gameMonth
            val duration = ForgeRecipeDatabase.getDurationByTier(recipeToStart.tier)

            slots[slotIdx] = slots[slotIdx].copy(
                status = ProductionSlotStatus.WORKING,
                recipeId = recipeToStart.id,
                recipeName = recipeToStart.name,
                startYear = gd.gameYear,
                startMonth = gd.gameMonth,
                duration = duration,
                successRate = (recipeToStart.successRate + policyBonus).coerceIn(0.0, 1.0),
                completionMonth = absoluteMonth + duration.coerceAtLeast(1),
                completionPhase = 3,
                outputItemId = recipeToStart.id,
                outputItemName = recipeToStart.name,
                outputItemRarity = recipeToStart.rarity
            )
        }
    }

    /** 影子版生产完成检测 */
    private fun batchBuildingCompletion(
        slots: MutableList<ProductionSlot>,
        state: MutableGameState
    ) {
        batchForgeCompletion(slots, state)
        batchAlchemyCompletion(slots, state)
    }

    private fun batchForgeCompletion(
        slots: MutableList<ProductionSlot>,
        state: MutableGameState
    ) {
        val year = state.gameData.gameYear
        val month = state.gameData.gameMonth
        for (i in slots.indices) {
            val slot = slots[i]
            if (slot.buildingType != BuildingType.FORGE) continue
            if (slot.status != ProductionSlotStatus.WORKING) continue
            if (!isSlotCompleteDynamic(slot, year, month)) continue

            slot.recipeId?.let { rid ->
                val recipe = ForgeRecipeDatabase.getRecipeById(rid)
                if (recipe != null) {
                    val equipment = InventoryFactories.createEquipmentFromRecipe(recipe)
                    state.equipmentStacks.add(equipment)
                }
            }
            slots[i] = ProductionSlot.createIdle(
                id = slot.id, slotIndex = slot.slotIndex,
                buildingType = BuildingType.FORGE,
                buildingId = slot.buildingId,
                autoRestartEnabled = slot.autoRestartEnabled,
                assignedDiscipleId = slot.assignedDiscipleId,
                assignedDiscipleName = slot.assignedDiscipleName ?: "",
                recipeId = slot.recipeId
            )
        }
    }

    private fun batchAlchemyCompletion(
        slots: MutableList<ProductionSlot>,
        state: MutableGameState
    ) {
        val year = state.gameData.gameYear
        val month = state.gameData.gameMonth
        for (i in slots.indices) {
            val slot = slots[i]
            if (slot.buildingType != BuildingType.ALCHEMY) continue
            if (slot.status != ProductionSlotStatus.WORKING) continue
            if (!isSlotCompleteDynamic(slot, year, month)) continue

            val alchemyRng = rngManager.getRng(RngPartition.SYSTEM)
            val success = alchemyRng.nextDouble() <= slot.successRate
            if (success) {
                val roll = alchemyRng.nextDouble()
                val grade = when {
                    roll < 0.06 -> PillGrade.HIGH
                    roll < 0.40 -> PillGrade.MEDIUM
                    else -> PillGrade.LOW
                }
                val baseId = slot.recipeId?.substringBeforeLast("_")
                val pillId = "${baseId}_${grade.name.lowercase()}"
                val template = baseId?.let { ItemDatabase.getPillById(pillId) }
                val pill = if (template != null) ItemDatabase.createPillFromTemplate(template)
                else Pill(
                    name = slot.outputItemName, rarity = slot.outputItemRarity,
                    grade = grade, category = PillCategory.CULTIVATION,
                    description = "通过炼丹炉炼制而成",
                    minRealm = GameConfig.Realm.getMinRealmForRarity(slot.outputItemRarity),
                    quantity = 1
                )
                state.pills.add(pill)
            }
            slots[i] = ProductionSlot.createIdle(
                id = slot.id, slotIndex = slot.slotIndex,
                buildingType = BuildingType.ALCHEMY,
                buildingId = slot.buildingId,
                autoRestartEnabled = slot.autoRestartEnabled,
                assignedDiscipleId = slot.assignedDiscipleId,
                assignedDiscipleName = slot.assignedDiscipleName ?: "",
                recipeId = slot.recipeId
            )
        }
    }

    /** 影子版灵田收获（已用 state，直接复用） */
    fun batchSpiritFieldHarvest(
        slots: MutableList<ProductionSlot>,
        state: MutableGameState
    ) {
        // processSpiritFieldHarvest 已操作 state，只需确保 year/month 来自 state
        processSpiritFieldHarvest(state)
    }



    // ═══════════════════════════════════════════════════════════════
    // 影子版工具方法
    // ═══════════════════════════════════════════════════════════════

    private fun findRecipe(
        herbs: List<Herb>
    ): PillRecipeDatabase.PillRecipe? {
        return PillRecipeDatabase.findBestCraftableRecipe(herbs)
    }

    private fun findForgeRecipe(
        recipes: List<ForgeRecipeDatabase.ForgeRecipe>,
        materialIndex: Map<Pair<String, Int>, Int>
    ): ForgeRecipeDatabase.ForgeRecipe? {
        return recipes.firstOrNull { recipe ->
            recipe.materials.all { (materialId, requiredQty) ->
                val matData = BeastMaterialDatabase.getMaterialById(materialId)
                matData != null && (materialIndex[matData.name to matData.rarity] ?: 0) >= requiredQty
            }
        }
    }

    private fun consumeHerbsForRecipeLocal(
        materials: Map<String, Int>,
        herbs: List<Herb>,
        state: MutableGameState
    ) {
        for ((herbId, requiredQty) in materials) {
            val herbData = HerbDatabase.getHerbById(herbId) ?: continue
            var remaining = requiredQty
            val iter = state.herbs.all().iterator()
            while (iter.hasNext() && remaining > 0) {
                val herb = iter.next()
                if (herb.name != herbData.name || herb.rarity != herbData.rarity) continue
                val consume = minOf(remaining, herb.quantity)
                remaining -= consume
                val newQty = herb.quantity - consume
                if (newQty <= 0) state.herbs.remove(herb.id)
                else state.herbs.update(herb.id) { it.copy(quantity = newQty) }
            }
        }
    }

    private fun consumeMaterialsForRecipeLocal(
        materials: Map<String, Int>,
        state: MutableGameState
    ) {
        for ((materialId, requiredQty) in materials) {
            val matData = BeastMaterialDatabase.getMaterialById(materialId) ?: continue
            var remaining = requiredQty
            val iter = state.materials.all().iterator()
            while (iter.hasNext() && remaining > 0) {
                val item = iter.next()
                if (item.name != matData.name || item.rarity != matData.rarity) continue
                val consume = minOf(remaining, item.quantity)
                remaining -= consume
                val newQty = item.quantity - consume
                if (newQty <= 0) state.materials.remove(item.id)
                else state.materials.update(item.id) { it.copy(quantity = newQty) }
            }
        }
    }

    // ── Checkpoint 快照法：动态完成检测 ──

    /**
     * 动态检查生产槽位是否完成（Checkpoint 快照法）。
     *
     * 每次检查时按当前策略/长老状态重算有效 duration，
     * 替代使用缓存 duration 的 [ProductionSlot.isFinished]。
     */
    private fun isSlotCompleteDynamic(slot: ProductionSlot, year: Int, month: Int): Boolean {
        if (!slot.isWorking) return slot.status == ProductionSlotStatus.COMPLETED
        if (slot.duration <= 0) return true  // 保护：duration=0 → 立即完成

        val effectiveDuration = if (slot.baseDuration > 0) {
            formulaService.calculateWorkDurationWithAllDisciples(
                slot.baseDuration, slot.buildingId)
        } else {
            slot.duration  // 旧数据回退
        }

        return TimeProgressUtil.isTimeElapsed(
            slot.startYear, slot.startMonth, effectiveDuration, year, month)
    }

    /**
     * 全量重算所有活跃生产槽位的完成时间（Checkpoint 快照法）。
     *
     * 在策略切换/长老变更后调用，确保所有槽位的 completionMonth
     * 反映当前速率。由 [CultivationService.checkpointAllProduction] 委托。
     */
    fun recalculateAllCompletionMonths() {
        val data = stateStore.gameData.value
        val currentMonth = data.gameYear * 12 + data.gameMonth

        val allSlots = productionSlotRepository.getSlots()
        for (slot in allSlots) {
            if (!slot.isWorking) continue
            // 旧存档兼容：baseDuration=0 的槽位用当前 duration 作为基础值，
            // 确保政策/长老变化也能影响这些槽位（P2-4 fix）
            val effectiveBase = if (slot.baseDuration > 0) slot.baseDuration else slot.duration
            if (effectiveBase <= 0) continue

            val oldDuration = slot.duration.coerceAtLeast(1)
            val elapsedMonths = ((data.gameYear - slot.startYear) * 12 +
                (data.gameMonth - slot.startMonth)).coerceAtLeast(0)
            val progressRatio = elapsedMonths.toDouble() / oldDuration
            if (progressRatio >= 1.0) continue

            val newDuration = formulaService.calculateWorkDurationWithAllDisciples(
                effectiveBase, slot.buildingId
            )
            if (newDuration == slot.duration) continue

            // 同步更新 successRate（政策/长老变化影响成功率）
            val newSuccessRate = recalculateSuccessRate(data, slot)

            val remainingMonths = ((1.0 - progressRatio) * newDuration)
                .roundToInt().coerceAtLeast(1)
            scopeProvider.scope.launch(ioDispatcher.dispatcher) {
                productionSlotRepository.updateSlot(
                    slot.buildingType, slot.slotIndex
                ) { s ->
                    s.copy(
                        duration = newDuration,
                        completionMonth = currentMonth + remainingMonths,
                        successRate = newSuccessRate
                    )
                }
            }
        }
    }

    /** 根据当前政策重算槽位的 successRate。从配方数据库读取基础值 + 当前政策加成。 */
    private fun recalculateSuccessRate(data: GameData, slot: ProductionSlot): Double {
        val baseRate = when (slot.buildingType) {
            BuildingType.ALCHEMY ->
                PillRecipeDatabase.getRecipeById(slot.recipeId ?: "")?.successRate
            BuildingType.FORGE ->
                ForgeRecipeDatabase.getRecipeById(slot.recipeId ?: "")?.successRate
            else -> null
        } ?: return slot.successRate

        val policyBonus = when (slot.buildingType) {
            BuildingType.ALCHEMY ->
                if (data.sectPolicies.alchemyIncentive)
                    GameConfig.PolicyConfig.ALCHEMY_INCENTIVE_EFFECT else 0.0
            BuildingType.FORGE ->
                if (data.sectPolicies.forgeIncentive)
                    GameConfig.PolicyConfig.FORGE_INCENTIVE_EFFECT else 0.0
            else -> 0.0
        }
        return (baseRate + policyBonus).coerceIn(0.0, 1.0)
    }

    /**
     * 住所自动分配：按单人/多人住所政策从空闲弟子中筛选（关注/灵根/属性）。
     * 纯计算不修改 state，返回分配映射（buildingInstanceId:slotIndex → 弟子 id/name）。
     */
    private fun computeResidenceAssignments(
        state: MutableGameState,
        data: GameData,
        policies: SectPolicies,
        occupiedResidentIds: Set<String>
    ): Map<String, Pair<String, String>> {
        val singleResEnabled = policies.autoSingleResidenceFocused || policies.autoSingleResidenceRootCounts.isNotEmpty()
        val multiResEnabled = policies.autoMultiResidenceFocused || policies.autoMultiResidenceRootCounts.isNotEmpty()
        if (!singleResEnabled && !multiResEnabled) return emptyMap()

        val singleResBuildingIds = if (singleResEnabled) {
            data.placedBuildings
                .filter { it.displayName in buildingFeatureDisplayNames {
                    it is SlotGroup.Residence && it.slotsPerInstance == SINGLE_RESIDENCE_SLOTS
                } }.map { it.instanceId }.toSet()
        } else emptySet()
        val multiResBuildingIds = if (multiResEnabled) {
            data.placedBuildings
                .filter { it.displayName in buildingFeatureDisplayNames {
                    it is SlotGroup.Residence && it.slotsPerInstance == MULTI_RESIDENCE_SLOTS
                } }.map { it.instanceId }.toSet()
        } else emptySet()

        val allCandidates = state.discipleTables.assembleAll()
            .filter { d -> d.isAlive && d.id !in occupiedResidentIds }

        val singleAssignments = mutableMapOf<String, Pair<String, String>>()
        if (singleResEnabled && singleResBuildingIds.isNotEmpty()) {
            val singleCandidates = allCandidates.filter { d ->
                val matchesFilter = (policies.autoSingleResidenceFocused && isDiscipleFollowed(d)) ||
                    d.spiritRoot.types.size in policies.autoSingleResidenceRootCounts
                matchesFilter && d.comprehension >= policies.autoSingleResidenceThreshold
            }
            .sortedWith(
                compareByDescending<Disciple> { isDiscipleFollowed(it) }
                    .thenBy { it.spiritRoot.types.size }
                    .thenByDescending { it.comprehension }
            )
            val emptySingleSlots = data.residenceSlots.filter { s ->
                s.buildingInstanceId in singleResBuildingIds && s.discipleId.isEmpty()
            }
            for ((i, slot) in emptySingleSlots.withIndex()) {
                if (i >= singleCandidates.size) break
                val c = singleCandidates[i]
                singleAssignments["${slot.buildingInstanceId}:${slot.slotIndex}"] = c.id to c.name
            }
        }

        val multiAssignments = mutableMapOf<String, Pair<String, String>>()
        if (multiResEnabled && multiResBuildingIds.isNotEmpty()) {
            val multiCandidates = allCandidates.filter { d ->
                val matchesFilter = (policies.autoMultiResidenceFocused && isDiscipleFollowed(d)) ||
                    d.spiritRoot.types.size in policies.autoMultiResidenceRootCounts
                matchesFilter && d.comprehension >= policies.autoMultiResidenceThreshold
            }
            .sortedWith(
                compareByDescending<Disciple> { isDiscipleFollowed(it) }
                    .thenBy { it.spiritRoot.types.size }
                    .thenByDescending { it.comprehension }
            )
            val emptyMultiSlots = data.residenceSlots.filter { s ->
                s.buildingInstanceId in multiResBuildingIds && s.discipleId.isEmpty()
            }
            for ((i, slot) in emptyMultiSlots.withIndex()) {
                if (i >= multiCandidates.size) break
                val c = multiCandidates[i]
                multiAssignments["${slot.buildingInstanceId}:${slot.slotIndex}"] = c.id to c.name
            }
        }
        return singleAssignments + multiAssignments
    }

    /**
     * 预排序候选弟子（按关注/灵根数/属性降序），不移除 pool 元素。
     * 从 processAutoAssign 嵌套函数提级（类级私有）。
     */
    private fun precomputeCandidates(
        pool: List<Disciple>,
        focused: Boolean, rootCounts: List<Int>,
        threshold: Int, attr: (Disciple) -> Int
    ): List<Disciple> {
        val enabled = focused || rootCounts.isNotEmpty()
        if (!enabled || pool.isEmpty()) return emptyList()
        return pool
            .filter { d ->
                val matchesFilter = (focused && isDiscipleFollowed(d)) ||
                    d.spiritRoot.types.size in rootCounts
                matchesFilter && attr(d) >= threshold
            }
            .sortedWith(
                compareByDescending<Disciple> { if (focused) isDiscipleFollowed(it) else false }
                    .thenBy { it.spiritRoot.types.size }
                    .thenByDescending { attr(it) }
            )
    }
}
