package com.xianxia.sect.core.engine.service

import kotlinx.coroutines.launch
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.DiscipleStatus
import com.xianxia.sect.core.model.ForgeRecipe
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.Seed
import com.xianxia.sect.core.model.SpiritFieldPlant
import com.xianxia.sect.core.model.production.ProductionSlot
import com.xianxia.sect.core.model.production.ProductionSlotStatus
import com.xianxia.sect.core.state.DiscipleTables
import com.xianxia.sect.core.state.MutableGameState
import com.xianxia.sect.core.state.StackKeys
import com.xianxia.sect.core.state.StackableItemStore
import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.profession.ProfessionRules
import com.xianxia.sect.core.registry.BeastMaterialDatabase
import com.xianxia.sect.core.registry.ForgeRecipeDatabase
import com.xianxia.sect.core.registry.PillRecipeDatabase
import com.xianxia.sect.core.engine.domain.building.HerbGardenAuraService
import com.xianxia.sect.core.registry.HerbDatabase
import com.xianxia.sect.core.engine.system.computeMaxSlots
import com.xianxia.sect.core.util.AppError
import com.xianxia.sect.core.util.BuildingNames
import com.xianxia.sect.core.util.DomainResult
import com.xianxia.sect.core.engine.LazyEvaluationDispatcher
import com.xianxia.sect.core.model.production.BuildingType
import com.xianxia.sect.core.repository.getSlotsByType
import com.xianxia.sect.core.repository.getSlotsByBuildingId
import com.xianxia.sect.core.engine.service.ProductionProcessor.HarvestMaturityContext
import com.xianxia.sect.core.engine.service.ProductionProcessor.HarvestStoreContext
import com.xianxia.sect.core.engine.service.ProductionProcessor.HerbGardenMaturityZones

/**
 * 构建整轮收获共享的灵草/种子合并仓库（整轮一次构建 O(h)）。
 *
 * 灵草与种子共用同一仓库槽位预算（[computeMaxSlots]），两 store 的 maxSlots 惰性求值并
 * 互相引用对方实时堆叠数（lateinit 前置声明解决 Kotlin 局部变量前向引用）：轮内草药/种子
 * 堆叠数的增减（含续种消耗、收获种子入仓）即时反映到对方的槽位上限，与
 * InventorySystem.addXxx 的"otherTypes 实时统计"语义一致。
 */
// ── ProductionProcessor 拆分域 2/5（行为零变更） ──
internal fun ProductionProcessor.buildHarvestStores(state: MutableGameState): HarvestStoreContext {
    val fixedOtherTypes = state.equipmentStacks.size + state.manualStacks.size +
        state.pills.size + state.materials.size
    val maxSlotsBase = state.computeMaxSlots() - fixedOtherTypes
    lateinit var seeds: StackableItemStore<Seed>
    val herbs = StackableItemStore(
        initialItems = state.herbs.all(),
        stackKeyOf = StackKeys::herb,
        maxStack = inventoryConfig.getMaxStackSize("herb"),
        maxSlots = { maxSlotsBase - seeds.all().size },
        notFound = { AppError.Domain.Inventory.NotFound(it) }
    )
    seeds = StackableItemStore(
        initialItems = state.seeds.all(),
        stackKeyOf = StackKeys::seed,
        maxStack = inventoryConfig.getMaxStackSize("seed"),
        maxSlots = { maxSlotsBase - herbs.all().size },
        notFound = { AppError.Domain.Inventory.NotFound(it) }
    )
    return HarvestStoreContext(herbs, seeds)
}

/**
 * 收获后处理灵田槽位：消耗种子重新种植或清空槽位（下标直写，O(1)）。
 *
 * 续种只消耗"宗门仓库内"的种子——从本轮共享的 [seedStore]（权威镜像，含本轮
 * 收获入仓种子、已扣减续种消耗）中查找并扣减，**溢出转邮件的种子不在其中，不可续种**。
 *
 * @param index 与 newPlants 一一对应的下标（收获循环 forEachIndexed 提供，
 *        替代原 indexOfFirst + 每块地 toMutableList 的 O(n²) 复制）
 * @param seedStore 整轮共享的种子合并仓库（由 [buildHarvestStores] 构建）
 */

internal fun ProductionProcessor.updateSlotAfterHarvest(
    index: Int,
    plant: SpiritFieldPlant,
    currentYear: Int,
    currentMonth: Int,
    newPlants: MutableList<SpiritFieldPlant>,
    seedStore: StackableItemStore<Seed>
) {
    val matchingSeed = HerbDatabase.getSeedByName(plant.seedName)
    // isLocked 排除：全系统"锁定=不可消耗"语义（种植/丢弃/卖出/炼丹均检查），
    // 自动续种不可绕过锁定保护
    val existingSeed = seedStore.all().find { s ->
        s.name == plant.seedName &&
            s.rarity == (matchingSeed?.rarity ?: 1) &&
            s.growTime == plant.growTime && s.quantity > 0 && !s.isLocked
    }
    val currentAbsoluteMonth = LazyEvaluationDispatcher.toAbsoluteMonth(
        currentYear, currentMonth)
    if (existingSeed != null) {
        seedStore.remove(existingSeed.id, 1)
        newPlants[index] = plant.copy(
            // seedId 指向实际消耗的种子堆叠——保留旧 seedId 会在其堆叠
            // 扣尽移除后悬空，UI 误显示存量 0
            seedId = existingSeed.id,
            plantYear = currentYear, plantMonth = currentMonth,
            completionMonth = currentAbsoluteMonth +
                plant.growTime.coerceAtLeast(1),
            completionPhase = 3
        )
    } else {
        newPlants[index] = plant.copy(
            seedId = "", seedName = "", growTime = 0, expectedYield = 0,
            plantYear = 0, plantMonth = 0,
            completionMonth = 0, completionPhase = 1
        )
    }
}

/** 构建整轮收获的全局加成上下文（无长老且无光环弟子时跳过弟子表组装） */

internal fun ProductionProcessor.buildHarvestMaturityContext(
    data: GameData,
    tables: DiscipleTables
): HarvestMaturityContext {
    val hasElder = data.elderSlots.herbGardenElder.isNotBlank()
    val hasAuraDisciple = data.elderSlots.herbGardenDisciples.any { it.isActive }
    val allDisciples = if (hasElder || hasAuraDisciple) {
        tables.ids.filter { tables.isAlive[it] == 1 }
            .map { tables.assemble(it) }
    } else emptyList()
    val elderZone = HerbGardenAuraService.calculateElderMaturityBonus(data.elderSlots, allDisciples)
    val auraZone = HerbGardenAuraService.calculateAuraMaturityBonus(data.elderSlots, allDisciples)
    val policyZone = (if (data.sectPolicies.herbCultivation)
        GameConfig.PolicyConfig.HERB_CULTIVATION_EFFECT else 0.0) +
        (if (data.sectPolicies.spiritSpring)
            GameConfig.PolicyConfig.SPIRIT_SPRING_YIELD else 0.0)
    // 光环值为 0 时无需构建光环索引（O(b×灵植阁数) 只在真正有光环时付出）
    val auraByField = if (auraZone > 0.0) {
        HerbGardenAuraService.buildSpiritFieldAuraMap(data.placedBuildings)
    } else null
    return HarvestMaturityContext(elderZone, auraZone, policyZone, auraByField)
}

fun ProductionProcessor.calculateSpiritFieldMaturityBonus(
    plant: SpiritFieldPlant,
    gameData: GameData,
    allDisciples: List<Disciple>
): Double {
    val zones = buildHerbGardenMaturityZones(plant, gameData, allDisciples)
    return zones.totalMultiplier()
}

internal fun ProductionProcessor.buildHerbGardenMaturityZones(
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

suspend fun ProductionProcessor.processAutoAlchemy() {
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

internal suspend fun ProductionProcessor.processAutoAlchemySlot(
    slot: ProductionSlot,
    data: GameData,
    allDisciples: List<Disciple>,
    alchemyPolicyBonus: Double
) {
    val currentHerbs = stateStore.getCurrentHerbs()
    val slotIndex = slot.slotIndex
    if (!validateAutoSlot(slot, data, allDisciples, BuildingNames.ALCHEMY, DiscipleStatus.ALCHEMY)) {
        return
    }

    // 职业门禁：按槽位弟子炼丹师职业等级限制可炼品阶（无职业只能炼凡品）
    val worker = slot.assignedDiscipleId?.let { id -> allDisciples.find { it.id == id } }
    val maxTier = worker?.let { ProfessionRules.maxCraftableTier(it.skills.alchemyLevel) }
        ?: 1

    val recipeToStart = slot.recipeId
        ?.let { prevRecipeId ->
            PillRecipeDatabase.getRecipeById(prevRecipeId)?.takeIf { recipe ->
                recipe.tier <= maxTier && recipe.materials.all { (materialId, requiredQuantity) ->
                    val herbData = HerbDatabase.getHerbById(materialId)
                        ?: return@all false
                    currentHerbs.filter {
                        it.name == herbData.name && it.rarity == herbData.rarity
                    }.sumOf { it.quantity } >= requiredQuantity
                }
            }
        }
        ?: PillRecipeDatabase.findBestCraftableRecipe(currentHerbs, maxTier) ?: return

    // 公式化成功率（属性+职业合成基础率 × 乘区），不再用配方 successRate
    val effectiveSuccessRate = formulaService.buildSuccessRateZones(
        disciple = worker,
        buildingId = BuildingNames.ALCHEMY,
        recipeTier = recipeToStart.tier,
        policyBonus = alchemyPolicyBonus
    ).calculate()

    val result = productionCoordinator.startAlchemyAtomic(
        slotIndex = slotIndex,
        recipeId = recipeToStart.id,
        currentYear = data.gameYear,
        currentMonth = data.gameMonth,
        herbs = currentHerbs,
        buildingId = BuildingNames.ALCHEMY,
        successRate = effectiveSuccessRate
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

suspend fun ProductionProcessor.processAutoForge() {
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
        val slot = forgeSlots.find { it.slotIndex == slotIndex }
        if (slot != null && !processAutoForgeSlot(
                slot, data, allDisciples, forgePolicyBonus, allRecipes)) {
            break
        }
    }
}

/**
 * @return true 表示继续循环下一个槽位，false 表示中断循环
 */

internal suspend fun ProductionProcessor.processAutoForgeSlot(
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
    if (!validateAutoSlot(slot, data, allDisciples, BuildingNames.FORGE, DiscipleStatus.FORGE)) {
        return true
    }

    // 职业门禁：按槽位弟子炼器师职业等级限制可锻品阶（无职业只能锻凡品）
    val worker = slot.assignedDiscipleId?.let { id -> allDisciples.find { it.id == id } }
    val maxTier = worker?.let { ProfessionRules.maxCraftableTier(it.skills.forgeLevel) }
        ?: 1
    val craftableRecipes = allRecipes.filter { it.tier <= maxTier }

    val recipeToStart = findCraftableForgeRecipe(slot, craftableRecipes, materialIndex) ?: return true

    // 公式化成功率（属性+职业合成基础率 × 乘区），不再用配方 successRate
    val effectiveSuccessRate = formulaService.buildSuccessRateZones(
        disciple = worker,
        buildingId = BuildingNames.FORGE,
        recipeTier = recipeToStart.tier,
        policyBonus = forgePolicyBonus
    ).calculate()

    val result = productionCoordinator.startForgingAtomic(
        slotIndex = slotIndex,
        recipeId = recipeToStart.id,
        currentYear = data.gameYear,
        currentMonth = data.gameMonth,
        materials = currentMaterials,
        buildingId = BuildingNames.FORGE,
        successRate = effectiveSuccessRate
    )

    if (result is DomainResult.Success) {
        stateStore.update {
            this.materials.replaceAll(result.data.materialUpdate.materials)
        }
        return true
    }
    return false
}

/** 自动锻造配方选取：优先续炼原配方，否则取材料充足的最高阶配方（null 表示无配方） */

internal fun ProductionProcessor.findCraftableForgeRecipe(
    slot: ProductionSlot,
    craftableRecipes: List<ForgeRecipeDatabase.ForgeRecipe>,
    materialIndex: Map<Pair<String, Int>, Int>
): ForgeRecipeDatabase.ForgeRecipe? {
    val prevRecipe = slot.recipeId?.let { rid ->
        craftableRecipes.find { it.id == rid }?.takeIf { recipe -> hasMaterials(recipe, materialIndex) }
    }
    return prevRecipe ?: craftableRecipes.firstOrNull { recipe -> hasMaterials(recipe, materialIndex) }
}

/** 配方材料是否充足（按 name to rarity 聚合索引查余量） */

internal fun ProductionProcessor.hasMaterials(
    recipe: ForgeRecipeDatabase.ForgeRecipe,
    materialIndex: Map<Pair<String, Int>, Int>
): Boolean = recipe.materials.all { (materialId, requiredQuantity) ->
    val materialData = BeastMaterialDatabase.getMaterialById(materialId)
    materialData != null && (materialIndex[materialData.name to materialData.rarity] ?: 0) >= requiredQuantity
}

/**
 * 自动重启槽位共用守卫。
 *
 * 镜像一致性检查：Repository 槽位有弟子但镜像槽位已无此弟子
 * → 视为玩家已释放（历史只清镜像的入口曾产生此分叉），清 Repository 残留并跳过重启，
 *   否则自动重启会把已释放弟子拉回槽位（双槽分叉根因：弟子"被自动任命"回原槽）。
 * 弟子验证：仍存活且空闲（防止自动重启窗口期内弟子被调走）。
 *
 * @param expectedStatus 槽位对应的工作状态（ALCHEMY/FORGE）
 * @return true 可继续自动重启；false 槽位已清除（调用方中断本槽位处理）
 */

internal suspend fun ProductionProcessor.validateAutoSlot(
    slot: ProductionSlot,
    data: GameData,
    allDisciples: List<Disciple>,
    buildingName: String,
    expectedStatus: DiscipleStatus
): Boolean {
    val slotIndex = slot.slotIndex
    val mirrorStillAssigned = data.productionSlots
        .any { it.buildingId == buildingName && it.slotIndex == slotIndex
            && it.assignedDiscipleId == slot.assignedDiscipleId }
    if (!mirrorStillAssigned) {
        clearSlotAssignment(buildingName, slotIndex)
        return false
    }
    // 验证弟子仍存活且空闲（防止自动重启窗口期内弟子被调走）
    val disciple = slot.assignedDiscipleId?.let { id -> allDisciples.find { it.id == id } }
    val discipleUsable = disciple != null && disciple.isAlive &&
        (disciple.status == DiscipleStatus.IDLE || disciple.status == expectedStatus)
    if (!discipleUsable) {
        // 弟子不可用 → 双存储同时清除槽位关联（镜像在事务内，Repository 在 IO 线程）
        stateStore.update {
            gameData = gameData.copy(
                productionSlots = gameData.productionSlots.map { s ->
                    if (s.buildingId == buildingName && s.slotIndex == slotIndex) {
                        s.copy(assignedDiscipleId = null, assignedDiscipleName = "")
                    } else s
                }
            )
        }
        clearSlotAssignment(buildingName, slotIndex)
    }
    return discipleUsable
}

/** 清 Repository 槽位占用（validateAutoSlot 拆分；镜像不一致场景 Repository 为真源，仅清 repo） */
