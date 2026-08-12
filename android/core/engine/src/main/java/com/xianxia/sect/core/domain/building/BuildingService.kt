package com.xianxia.sect.core.engine.domain.building

import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.engine.LazyEvaluationDispatcher
import com.xianxia.sect.core.engine.annotation.GameService
import com.xianxia.sect.core.engine.service.FormulaService
import com.xianxia.sect.core.engine.service.settleProductionCompletion
import com.xianxia.sect.core.model.AlchemyResult
import com.xianxia.sect.core.model.DiscipleStatus
import com.xianxia.sect.core.model.ForgeRecipe
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.Pill
import com.xianxia.sect.core.model.PillCategory
import com.xianxia.sect.core.model.PillGrade
import com.xianxia.sect.core.model.artifactRefining
import com.xianxia.sect.core.model.pillRefining
import com.xianxia.sect.core.model.spiritPlanting
import com.xianxia.sect.core.profession.ProfessionRules
import com.xianxia.sect.core.registry.ForgeRecipeDatabase
import com.xianxia.sect.core.registry.ItemDatabase
import com.xianxia.sect.core.registry.PillRecipeDatabase
import com.xianxia.sect.core.engine.domain.production.ProductionCoordinator
import com.xianxia.sect.core.model.production.ProductionSlot
import com.xianxia.sect.core.model.production.ProductionSlotStatus
import com.xianxia.sect.core.model.production.BuildingType
import com.xianxia.sect.core.model.production.SlotStateMachine
import com.xianxia.sect.core.repository.ProductionSlotRepository
import com.xianxia.sect.core.engine.domain.disciple.DiscipleAssignmentGate
import com.xianxia.sect.core.engine.domain.disciple.DiscipleStatCalculator
import com.xianxia.sect.core.model.SlotCategory
import com.xianxia.sect.core.model.SlotRef
import com.xianxia.sect.core.state.GameStateStore
import com.xianxia.sect.core.engine.system.InventorySystem
import com.xianxia.sect.core.util.AppError
import com.xianxia.sect.core.util.BuildingNames
import com.xianxia.sect.core.util.DeterministicRng
import com.xianxia.sect.core.util.DomainLog
import com.xianxia.sect.core.util.DomainResult
import com.xianxia.sect.core.engine.di.IoDispatcher
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton






@GameService("BuildingService")
@Singleton
class BuildingService @Inject constructor(
    private val stateStore: GameStateStore,
    private val productionCoordinator: ProductionCoordinator,
    private val productionSlotRepository: ProductionSlotRepository,
    private val inventorySystem: InventorySystem,
    private val formulaService: FormulaService,
    private val rngManager: com.xianxia.sect.core.util.GameRngManager,
    private val assignmentGate: DiscipleAssignmentGate,
    private val ioDispatcher: IoDispatcher,
) {
    companion object {
        private const val TAG = "BuildingService"

        /** 丹药品阶 roll 阈值（与月变路径 ProductionProcessor 保持一致） */
        private const val PILL_GRADE_HIGH_THRESHOLD = 0.06
        private const val PILL_GRADE_MEDIUM_THRESHOLD = 0.40
    }

    suspend fun assignDiscipleToBuilding(
        buildingId: String, slotIndex: Int, discipleId: String
    ) {
        if (discipleId.isEmpty()) {
            removeDiscipleFromBuildingInternal(buildingId, slotIndex)
            return
        }

        val discipleName = getDiscipleNameIfAvailable(discipleId)
        if (discipleName.isEmpty()) return

        // Prevent assigning same disciple to multiple building slots.
        //
        // 历史 bug：旧实现使用 `it.buildingId != buildingId`
        // 做排他判断，但 buildingId 是类型标识
        // （如 "alchemy"/"forge"），非实例标识。
        // 多个同类型建筑实例共享同一 buildingId，
        // 导致排他检查失效。
        // 已迁移到 DiscipleAssignmentGate（见下方事务内清理）
        val targetSlot = SlotRef(
            category = SlotCategory.PRODUCTION_SLOT,
            slotType = "$buildingId:$slotIndex",
            slotId = "production_${buildingId}_${slotIndex}"
        )

        val existingSlot =
            productionSlotRepository.getSlotByBuildingId(buildingId, slotIndex)

        if (existingSlot != null && existingSlot.isWorking) {
            return
        }

        // 旧 occupant：补 gate.release（回归：此前只置 IDLE 不释放，注册表残留
        // 导致旧弟子从可用列表"消失"）
        existingSlot?.assignedDiscipleId?.let { oldDiscipleId ->
            if (oldDiscipleId.isNotEmpty() && oldDiscipleId != discipleId) {
                assignmentGate.release(oldDiscipleId)
                updateDiscipleStatus(oldDiscipleId, DiscipleStatus.IDLE)
            }
        }

        // 事务内清理 GameData 全部槽位 + 同步 GameData.productionSlots 镜像
        // （回归：此前无任何清理——注释声称已迁移 Gate 但实际没有互斥检查，
        // 同一弟子可同时占多个炼丹/锻造槽，也可与巡逻/长老并存）
        stateStore.update {
            gameData = com.xianxia.sect.core.engine.domain.disciple.DiscipleSlotCleanup(assignmentGate)
                .clearAllSlotsDataOnly(gameData, discipleId)
            gameData = gameData.copy(
                productionSlots = gameData.productionSlots.map { slot ->
                    when {
                        slot.buildingId == buildingId && slot.slotIndex == slotIndex ->
                            slot.copy(assignedDiscipleId = discipleId, assignedDiscipleName = discipleName)
                        slot.assignedDiscipleId == discipleId ->
                            slot.copy(assignedDiscipleId = null, assignedDiscipleName = "")
                        else -> slot
                    }
                }
            )
        }

        assignDiscipleToSlot(
            buildingId, slotIndex, discipleId,
            discipleName, existingSlot
        )
        // 清旧注册再登记新分配（未注册时 release 为空操作，安全）
        assignmentGate.release(discipleId)
        assignmentGate.confirmAssign(discipleId, targetSlot)
    }

    suspend fun removeDiscipleFromBuilding(buildingId: String, slotIndex: Int) {
        removeDiscipleFromBuildingInternal(buildingId, slotIndex)
    }

    private suspend fun removeDiscipleFromBuildingInternal(
        buildingId: String, slotIndex: Int
    ) {
        val existingSlot =
            productionSlotRepository.getSlotByBuildingId(
                buildingId, slotIndex
            ) ?: return

        if (existingSlot.isWorking) {
            return
        }

        val oldDiscipleId = existingSlot.assignedDiscipleId

        // repo 先写、成功才清镜像（失败两端皆未变）——镜像残留会让状态推导仍 WORKING、
        // 自动重启按镜像判定继续生产（与 removeDiscipleFromProductionSlot 同链路）
        val result = withContext(ioDispatcher.dispatcher) {
            productionSlotRepository.updateSlotByBuildingId(
                buildingId, slotIndex
            ) { slot ->
                slot.copy(assignedDiscipleId = null, assignedDiscipleName = "")
            }
        }
        if (result.isFailure) {
            DomainLog.e(
                TAG,
                "卸任失败: $buildingId[$slotIndex] disciple=$oldDiscipleId, " +
                    (result.exceptionOrNull()?.message ?: "unknown")
            )
        } else {
            // repo 写成功 → 同步清镜像，双端一致
            stateStore.update {
                gameData = gameData.copy(
                    productionSlots = gameData.productionSlots.map { slot ->
                        if (slot.buildingId == buildingId && slot.slotIndex == slotIndex) {
                            slot.copy(assignedDiscipleId = null, assignedDiscipleName = "")
                        } else slot
                    }
                )
            }

            if (!oldDiscipleId.isNullOrEmpty()) {
                assignmentGate.release(oldDiscipleId)
            }
        }
    }

    suspend fun startAlchemy(
        slotIndex: Int, recipeId: String
    ): DomainResult<ProductionSlot> {
        if (slotIndex < 0) {
            return DomainResult.Failure(
                AppError.Domain.Production.InvalidSlot(slotIndex = slotIndex)
            )
        }

        val data = stateStore.gameData.value

        val alchemySlot = productionSlotRepository.getSlotByBuildingId(
            BuildingNames.ALCHEMY, slotIndex
        )
        if (alchemySlot != null && alchemySlot.isWorking) {
            return DomainResult.Failure(
                AppError.Domain.Production.SlotBusy(slotIndex = slotIndex)
            )
        }
        if (alchemySlot?.assignedDiscipleId.isNullOrEmpty()) {
            return DomainResult.Failure(
                AppError.Domain.Production.DiscipleNotAvailable(discipleId = "")
            )
        }

        val recipe = PillRecipeDatabase.getRecipeById(recipeId)
            ?: return DomainResult.Failure(
                AppError.Domain.Production.RecipeNotFound(recipeId = recipeId)
            )

        // 职业品阶门禁（反绕过拦截）：无职业/职业等级不足不可炼该品阶
        val workerLevel = alchemySlot?.assignedDiscipleId
            ?.let { id -> stateStore.disciples.value.find { it.id == id }?.skills?.alchemyLevel }
            ?: 0
        checkProfessionGate(workerLevel, recipe.tier, recipeId)?.let { return it }

        val alchemyPolicyBonus = if (data.sectPolicies.alchemyIncentive)
            GameConfig.PolicyConfig.ALCHEMY_INCENTIVE_EFFECT else 0.0
        val effectiveSuccessRate = buildAlchemySuccessRate(
            alchemySlot, recipe, alchemyPolicyBonus
        )

        return executeAlchemyStart(slotIndex, recipe, recipeId, data, effectiveSuccessRate)
    }

    suspend fun startForging(
        slotIndex: Int, recipeId: String
    ): DomainResult<ProductionSlot> {
        if (slotIndex < 0) {
            return DomainResult.Failure(
                AppError.Domain.Production.InvalidSlot(slotIndex = slotIndex)
            )
        }
        val data = stateStore.gameData.value

        val forgeSlot = productionSlotRepository.getSlotByBuildingId(
            BuildingNames.FORGE, slotIndex
        )
        if (forgeSlot != null && forgeSlot.isWorking) {
            return DomainResult.Failure(
                AppError.Domain.Production.SlotBusy(slotIndex = slotIndex)
            )
        }
        if (forgeSlot?.assignedDiscipleId.isNullOrEmpty()) {
            return DomainResult.Failure(
                AppError.Domain.Production.DiscipleNotAvailable(discipleId = "")
            )
        }

        val recipe = ForgeRecipeDatabase.getRecipeById(recipeId)
            ?: return DomainResult.Failure(
                AppError.Domain.Production.RecipeNotFound(recipeId = recipeId)
            )

        // 职业品阶门禁（反绕过拦截）：无职业/职业等级不足不可锻该品阶
        val workerLevel = forgeSlot?.assignedDiscipleId
            ?.let { id -> stateStore.disciples.value.find { it.id == id }?.skills?.forgeLevel }
            ?: 0
        checkProfessionGate(workerLevel, recipe.tier, recipeId)?.let { return it }

        val forgePolicyBonus = if (data.sectPolicies.forgeIncentive)
            GameConfig.PolicyConfig.FORGE_INCENTIVE_EFFECT else 0.0
        val effectiveSuccessRate = buildForgingSuccessRate(
            forgeSlot, recipe, forgePolicyBonus
        )

        return executeForgingStart(slotIndex, recipe, recipeId, data, effectiveSuccessRate)
    }

    /**
     * 职业品阶门禁（反绕过拦截）：职业等级不足时返回 [AppError.Domain.Production.RecipeTierLocked]，
     * 放行返回 null。手动/自动路径共用同一入口，杜绝绕过。
     */
    private fun checkProfessionGate(
        workerLevel: Int,
        recipeTier: Int,
        recipeId: String
    ): DomainResult.Failure? {
        if (ProfessionRules.canCraftTier(workerLevel, recipeTier)) return null
        return DomainResult.Failure(
            AppError.Domain.Production.RecipeTierLocked(
                recipeId = recipeId,
                requiredTier = recipeTier,
                maxCraftableTier = ProfessionRules.maxCraftableTier(workerLevel)
            )
        )
    }

    /** 炼丹启动事务尾段：扣材料、写槽位 WORKING，返回更新后槽位 */
    private suspend fun executeAlchemyStart(
        slotIndex: Int,
        recipe: PillRecipeDatabase.PillRecipe,
        recipeId: String,
        data: GameData,
        effectiveSuccessRate: Double
    ): DomainResult<ProductionSlot> {
        val result = productionCoordinator.startAlchemyAtomic(
            slotIndex = slotIndex,
            recipeId = recipeId,
            currentYear = data.gameYear,
            currentMonth = data.gameMonth,
            herbs = stateStore.getCurrentHerbs(),
            buildingId = BuildingNames.ALCHEMY,
            successRate = effectiveSuccessRate
        )
        val startData = when (result) {
            is DomainResult.Failure -> return result
            is DomainResult.Partial -> result.data
            is DomainResult.Success -> result.data
        }
        stateStore.update { herbs.replaceAll(startData.materialUpdate.herbs) }
        val actualDuration = calculateWorkDurationWithAllDisciples(
            recipe.duration, BuildingNames.ALCHEMY
        )
        updateSlotToWorkingStateAlchemy(
            slotIndex, data, recipe, recipeId,
            actualDuration, effectiveSuccessRate
        )
        return DomainResult.Success(startData.slot)
    }

    /** 锻造启动事务尾段：扣材料、写槽位 WORKING，返回更新后槽位 */
    private suspend fun executeForgingStart(
        slotIndex: Int,
        recipe: ForgeRecipeDatabase.ForgeRecipe,
        recipeId: String,
        data: GameData,
        effectiveSuccessRate: Double
    ): DomainResult<ProductionSlot> {
        val result = productionCoordinator.startForgingAtomic(
            slotIndex = slotIndex,
            recipeId = recipeId,
            currentYear = data.gameYear,
            currentMonth = data.gameMonth,
            materials = stateStore.getCurrentMaterials(),
            buildingId = BuildingNames.FORGE,
            successRate = effectiveSuccessRate
        )
        val startData = when (result) {
            is DomainResult.Failure -> return result
            is DomainResult.Partial -> result.data
            is DomainResult.Success -> result.data
        }
        stateStore.update {
            materials.replaceAll(startData.materialUpdate.materials)
        }
        val baseDuration = ForgeRecipeDatabase.getDurationByTier(recipe.tier)
        val actualDuration = calculateWorkDurationWithAllDisciples(
            baseDuration, BuildingNames.FORGE
        )
        updateSlotToWorkingStateForging(
            slotIndex, data, recipe, recipeId,
            baseDuration, actualDuration, effectiveSuccessRate
        )
        return DomainResult.Success(startData.slot)
    }

    // -- 新提取的私有辅助方法 --

    private suspend fun assignDiscipleToSlot(
        buildingId: String,
        slotIndex: Int,
        discipleId: String,
        discipleName: String,
        existingSlot: ProductionSlot?
    ) {
        if (existingSlot != null) {
            withContext(ioDispatcher.dispatcher) {
                productionSlotRepository.updateSlotByBuildingId(
                    buildingId, slotIndex
                ) { slot ->
                    slot.copy(
                        assignedDiscipleId = discipleId,
                        assignedDiscipleName = discipleName
                    )
                }
            }
        } else {
            val buildingType = ProductionSlot.resolveBuildingType(buildingId)
            withContext(ioDispatcher.dispatcher) {
                productionSlotRepository.addSlot(
                    ProductionSlot.createIdle(
                        slotIndex = slotIndex,
                        buildingType = buildingType,
                        buildingId = buildingId
                    ).copy(
                        assignedDiscipleId = discipleId,
                        assignedDiscipleName = discipleName
                    )
                )
            }
        }
    }

    private suspend fun getDiscipleNameIfAvailable(discipleId: String): String {
        var discipleName = ""
        stateStore.update {
            val disciple = discipleTables.assemble(
                discipleId.toIntOrNull() ?: return@update
            )
            if (!disciple.isAlive || disciple.status != DiscipleStatus.IDLE) {
                return@update
            }
            if (disciple.age < 5) {
                return@update
            }
            discipleName = disciple.name
        }
        return discipleName
    }

    private fun buildAlchemySuccessRate(
        alchemySlot: ProductionSlot,
        recipe: PillRecipeDatabase.PillRecipe,
        alchemyPolicyBonus: Double
    ): Double {
        val disciple = alchemySlot.assignedDiscipleId?.let { id ->
            stateStore.disciples.value.find { it.id == id }
        }
        return formulaService.buildSuccessRateZones(
            disciple = disciple,
            buildingId = BuildingNames.ALCHEMY,
            recipeTier = recipe.tier,
            policyBonus = alchemyPolicyBonus
        ).calculate()
    }

    /**
     * B5 已知偏差（不修）：仅写 Repository 状态、镜像 productionSlots 的 status 保持旧值。
     * 状态推导（DiscipleStatusService）不读镜像 status，暂不致病；勿依赖镜像 status 为真源。
     */
    private suspend fun updateSlotToWorkingStateAlchemy(
        slotIndex: Int,
        data: GameData,
        recipe: PillRecipeDatabase.PillRecipe,
        recipeId: String,
        actualDuration: Int,
        effectiveSuccessRate: Double
    ) {
        val currentAbsoluteMonth = LazyEvaluationDispatcher.toAbsoluteMonth(
            data.gameYear, data.gameMonth
        )
        withContext(ioDispatcher.dispatcher) {
            productionSlotRepository.updateSlotByBuildingId(
                BuildingNames.ALCHEMY, slotIndex
            ) { slot ->
                slot.copy(
                    status = ProductionSlotStatus.WORKING,
                    recipeId = recipeId,
                    recipeName = recipe.name,
                    startYear = data.gameYear,
                    startMonth = data.gameMonth,
                    duration = actualDuration,
                    baseDuration = recipe.duration,
                    successRate = effectiveSuccessRate,
                    requiredMaterials = recipe.materials,
                    outputItemId = recipeId,
                    outputItemName = recipe.name,
                    outputItemRarity = recipe.rarity,
                    completionMonth = currentAbsoluteMonth +
                        actualDuration.coerceAtLeast(1),
                    completionPhase = 2
                )
            }
        }
    }

    private fun buildForgingSuccessRate(
        forgeSlot: ProductionSlot,
        recipe: ForgeRecipeDatabase.ForgeRecipe,
        forgePolicyBonus: Double
    ): Double {
        val disciple = forgeSlot.assignedDiscipleId?.let { id ->
            stateStore.disciples.value.find { it.id == id }
        }
        return formulaService.buildSuccessRateZones(
            disciple = disciple,
            buildingId = BuildingNames.FORGE,
            recipeTier = recipe.tier,
            policyBonus = forgePolicyBonus
        ).calculate()
    }

    /** B5 已知偏差（不修）：同 [updateSlotToWorkingStateAlchemy]，仅写 Repository 不写镜像 status。 */
    private suspend fun updateSlotToWorkingStateForging(
        slotIndex: Int,
        data: GameData,
        recipe: ForgeRecipeDatabase.ForgeRecipe,
        recipeId: String,
        baseDuration: Int,
        actualDuration: Int,
        effectiveSuccessRate: Double
    ) {
        val currentAbsoluteMonth = LazyEvaluationDispatcher.toAbsoluteMonth(
            data.gameYear, data.gameMonth
        )
        withContext(ioDispatcher.dispatcher) {
            productionSlotRepository.updateSlotByBuildingId(
                BuildingNames.FORGE, slotIndex
            ) { slot ->
                slot.copy(
                    status = ProductionSlotStatus.WORKING,
                    recipeId = recipeId,
                    recipeName = recipe.name,
                    startYear = data.gameYear,
                    startMonth = data.gameMonth,
                    duration = actualDuration,
                    baseDuration = baseDuration,
                    successRate = effectiveSuccessRate,
                    outputItemId = recipeId,
                    outputItemName = recipe.name,
                    outputItemRarity = recipe.rarity,
                    outputItemSlot = recipe.type.name,
                    completionMonth = currentAbsoluteMonth +
                        actualDuration.coerceAtLeast(1),
                    completionPhase = 2
                )
            }
        }
    }

    // -- 原方法保持不变 --

    /**
     * Auto-collect a completed slot's result and reset it to IDLE.
     * Called internally by the auto-harvest system during month advancement.
     */
    private suspend fun autoCollectSlotResult(slot: ProductionSlot) {
        // 锻造真实成功率判定（2026-08-09 职业系统）：读档/惰性收获路径与月变路径
        // 一致——失败不产出装备、材料不退还，成功才计入职业晋升进度。
        // 此前锻造读档 100% 产出，绕过概率设计（对抗性审查发现）
        // B4：产出入库失败（addEquipmentStack Failure/配方无效）视为炼制失败，
        // 不结算晋升，防装备静默丢失
        // L7（对抗性审查）：NaN 不被 coerceIn 钳制，显式归零
        val rate = if (slot.successRate.isNaN()) 0.0 else slot.successRate.coerceIn(0.0, 1.0)
        var success = rngManager.getRng(
            com.xianxia.sect.core.util.RngPartition.SYSTEM
        ).nextDouble() <= rate
        if (success) {
            success = completeBuildingTaskFromProductionSlot(slot)
        }

        // 统一结算（与月变路径共用扩展）：引导计数 + 年度计数 + 弟子回 IDLE + 职业晋升
        val discipleAlive = slot.assignedDiscipleId?.let { discipleId ->
            stateStore.settleProductionCompletion(slot, discipleId, success, isAlchemy = false)
        } ?: false
        // 双存储同步：重置 Repository 槽位的同时清镜像槽位——收获后镜像残留
        // 会让状态推导把弟子重新拉回工作状态（双槽分叉根因）
        clearMirrorProductionSlot(slot.buildingId, slot.slotIndex)

        // 发现2/L5（对抗性审查）：reset 与 B3 补清合并为单次 writeMutex 事务——
        // 原两次独立获取之间残留"IDLE+死弟子"中间态，可被并发排班占用（死弟子
        // 启动生产）后再补清 → WORKING+空弟子停滞（材料已扣、永不结算）。
        // 合并后：排班要么看到 COMPLETED/WORKING 跳过，要么看到已清空的 IDLE
        // 跳过（无弟子不排班），无中间态。
        withContext(ioDispatcher.dispatcher) {
            productionSlotRepository.updateSlotByBuildingId(
                slot.buildingId, slot.slotIndex
            ) { s ->
                // B5 式身份守卫：窗口内排班已启动新炼制（WORKING 且身份不符）→ 不打扰；
                // COMPLETED/WORKING 一致（本次收获目标）→ reset；IDLE → reset 无害
                // （validateTransition 拒绝同态转换时 getOrElse 原样返回）
                if (s.status == ProductionSlotStatus.WORKING &&
                    (s.completionMonth != slot.completionMonth || s.recipeId != slot.recipeId)
                ) {
                    s
                } else {
                    val reset = SlotStateMachine.resetSlot(s).getOrElse { e ->
                        DomainLog.w(TAG, "resetSlot state transition failed: ${e.message}")
                        return@updateSlotByBuildingId s
                    }
                    // B3：弟子死亡/查无此人 → 清空弟子关联（resetSlot 保留弟子字段，
                    // 不清会导致死弟子永久占用槽位）
                    if (discipleAlive) reset
                    else reset.copy(assignedDiscipleId = null, assignedDiscipleName = "")
                }
            }
        }
    }

    /** 清镜像（GameData.productionSlots）中的指定槽位关联（Repository 清理见调用方）。 */
    private fun clearMirrorProductionSlot(buildingId: String, slotIndex: Int) {
        stateStore.update {
            gameData = gameData.copy(
                productionSlots = gameData.productionSlots.map { s ->
                    if (s.buildingId == buildingId && s.slotIndex == slotIndex) {
                        s.copy(assignedDiscipleId = null, assignedDiscipleName = "")
                    } else s
                }
            )
        }
    }

    /**
     * 炼丹产出：品阶 roll + 丹药入库（withTrackingSource 统一入口，来源 "building"）。
     *
     * @return null = 入库失败/配方无效（B4：视为炼制失败，不结算晋升）
     */
    private fun producePillFromSlot(slot: ProductionSlot, rng: DeterministicRng): Pill? {
        val roll = rng.nextDouble()
        val grade = when {
            roll < PILL_GRADE_HIGH_THRESHOLD -> PillGrade.HIGH
            roll < PILL_GRADE_MEDIUM_THRESHOLD -> PillGrade.MEDIUM
            else -> PillGrade.LOW
        }
        // M3（对抗性审查）：无配方（recipeId null/模板查不到）→ 炼制失败，
        // 与读档路径 producePillWithRecipe 及月变路径 producePill 语义统一——
        // 不再回退 outputItemName 丹药（旧存档无配方槽位三路径行为随机）
        val template = slot.recipeId?.let { it.substringBeforeLast("_") }
            ?.let { baseId -> ItemDatabase.getPillById("${baseId}_${grade.name.lowercase()}") }
            ?: return null
        val pill = ItemDatabase.createPillFromTemplate(template)
        val r = inventorySystem.withTrackingSource("building") { inventorySystem.addPill(pill) }
        return when (r) {
            is DomainResult.Success -> pill
            is DomainResult.Partial -> {
                DomainLog.w(TAG, "丹药 ${pill.name} 溢出 ${r.overflow} 个")
                pill
            }
            is DomainResult.Failure -> {
                // B4：入库失败 → 本次炼制视为失败（不结算晋升，防丹药静默丢失）
                DomainLog.e(TAG, "丹药 ${pill.name} 添加失败: ${r.error}")
                null
            }
        }
    }

    /**
     * Auto-collect a completed alchemy slot with success rate check.
     * Returns the alchemy result for event recording.
     */
    private suspend fun autoCollectAlchemyResult(
        slot: ProductionSlot
    ): AlchemyResult? {
        val rng = rngManager.getRng(
            com.xianxia.sect.core.util.RngPartition.SYSTEM
        )
        // successRate 先钳制到 [0,1]（对抗性审查：存档篡改/旧数据可能越界）；
        // L7：NaN 不被 coerceIn 钳制，显式归零
        // B4：入库失败时改为 false（视为炼制失败）
        val rate = if (slot.successRate.isNaN()) 0.0 else slot.successRate.coerceIn(0.0, 1.0)
        var success = rng.nextDouble() <= rate

        var pill: Pill? = null
        if (success) {
            pill = producePillFromSlot(slot, rng)
            if (pill == null) success = false
        }

        // 统一结算（2026-08-09 职业系统）：引导计数 + 年度计数 + 弟子回 IDLE +
        // 职业晋升，与月变路径 ProductionProcessor 共用同一扩展——
        // 此前读档/惰性收获路径缺晋升与统计（对抗性审查：正常玩家读档即丢一次晋升计数）
        val discipleAlive = slot.assignedDiscipleId?.let { discipleId ->
            stateStore.settleProductionCompletion(slot, discipleId, success, isAlchemy = true)
        } ?: false
        // 双存储同步：重置 Repository 槽位的同时清镜像槽位——收获后镜像残留
        // 会让状态推导把弟子重新拉回工作状态（双槽分叉根因）
        clearMirrorProductionSlot(BuildingNames.ALCHEMY, slot.slotIndex)

        // 发现2/L5（对抗性审查）：reset 与 B3 补清合并为单次 writeMutex 事务——
        // 消除"IDLE+死弟子"中间态被并发排班占用（详见 autoCollectSlotResult 注释）
        withContext(ioDispatcher.dispatcher) {
            productionSlotRepository.updateSlotByBuildingId(
                BuildingNames.ALCHEMY, slot.slotIndex
            ) { s ->
                // B5 式身份守卫：窗口内排班已启动新炼制（WORKING 且身份不符）→ 不打扰
                if (s.status == ProductionSlotStatus.WORKING &&
                    (s.completionMonth != slot.completionMonth || s.recipeId != slot.recipeId)
                ) {
                    s
                } else {
                    val reset = SlotStateMachine.resetSlot(s).getOrElse { e ->
                        DomainLog.w(TAG, "resetSlot state transition failed: ${e.message}")
                        return@updateSlotByBuildingId s
                    }
                    // B3：弟子死亡/查无此人 → 清空弟子关联（防死弟子永久占用槽位）
                    if (discipleAlive) reset
                    else reset.copy(assignedDiscipleId = null, assignedDiscipleName = "")
                }
            }
        }

        return AlchemyResult(
            success = success,
            pill = pill,
            message = if (success) "成功" else "失败"
        )
    }

    /**
     * Auto-harvest all completed alchemy slots.
     * Called internally during month advancement.
     */
    suspend fun autoHarvestCompletedAlchemySlots(): List<AlchemyResult> {
        val data = stateStore.gameData.value
        val results = mutableListOf<AlchemyResult>()
        val alchemySlots = productionSlotRepository.getSlotsByType(
            BuildingType.ALCHEMY
        )
        alchemySlots.forEach { slot ->
            if (slot.isCompleted || (slot.isWorking && slot.isFinished(
                    data.gameYear, data.gameMonth
                ))
            ) {
                autoCollectAlchemyResult(slot)?.let { results.add(it) }
            }
        }
        return results
    }

    /**
     * Auto-harvest a completed forge slot.
     * Called internally during month advancement.
     */
    suspend fun autoHarvestForgeSlot(slot: ProductionSlot) {
        autoCollectSlotResult(slot)
    }

    private suspend fun updateDiscipleStatus(
        discipleId: String, status: DiscipleStatus
    ) {
        stateStore.update {
            val currentList = discipleTables.assembleAll()
            val updated = currentList.map {
                if (it.id == discipleId) it.copy(status = status) else it
            }
            discipleTables.replaceAll(updated)
        }
    }

    private fun getBuildingName(buildingId: String): String =
        BuildingNames.getDisplayName(buildingId)

    private fun calculateWorkDurationWithAllDisciples(
        baseDuration: Int, buildingId: String
    ): Int {
        var totalSpeedBonus = 0.0
        val data = stateStore.gameData.value

        totalSpeedBonus += getElderPositionBonusLocal(buildingId)

        when (buildingId) {
            BuildingNames.FORGE, BuildingNames.ALCHEMY, "herbGarden" -> {
                val assignedDiscipleIds = when (buildingId) {
                    BuildingNames.FORGE ->
                        productionSlotRepository.getSlotsByBuildingId(
                            BuildingNames.FORGE
                        ).mapNotNull { it.assignedDiscipleId }
                    BuildingNames.ALCHEMY -> emptyList()
                    else -> emptyList()
                }
                if (assignedDiscipleIds.isNotEmpty()) {
                    val elderBonus = getElderPositionBonusLocal(buildingId)
                    totalSpeedBonus += elderBonus
                }
            }
        }

        return calculateReducedDurationLocal(baseDuration, totalSpeedBonus)
    }

    private fun getElderPositionBonusLocal(buildingId: String): Double {
        val data = stateStore.gameData.value
        val elderSlots = data.elderSlots

        val elderDiscipleId = when (buildingId) {
            BuildingNames.FORGE -> elderSlots.forgeElder
            BuildingNames.ALCHEMY -> elderSlots.alchemyElder
            "herbGarden" -> elderSlots.herbGardenElder
            else -> null
        } ?: return 0.0

        val elderDisciple = stateStore.disciples.value.find {
            it.id == elderDiscipleId
        } ?: return 0.0

        return when (buildingId) {
            BuildingNames.FORGE -> {
                val diff = (
                    DiscipleStatCalculator.getBaseStats(elderDisciple).artifactRefining -
                        GameConfig.PolicyConfig.ELDER_SKILL_BASELINE
                ).coerceAtLeast(0)
                diff * 0.01
            }
            BuildingNames.ALCHEMY -> {
                val diff = (
                    DiscipleStatCalculator.getBaseStats(elderDisciple).pillRefining -
                        GameConfig.PolicyConfig.ELDER_SKILL_BASELINE
                ).coerceAtLeast(0)
                diff * 0.01
            }
            "herbGarden" -> {
                val diff = (
                    DiscipleStatCalculator.getBaseStats(elderDisciple).spiritPlanting -
                        GameConfig.PolicyConfig.ELDER_SKILL_BASELINE
                ).coerceAtLeast(0)
                diff * 0.01
            }
            else -> 0.0
        }
    }

    private fun calculateReducedDurationLocal(
        baseDuration: Int, speedBonus: Double
    ): Int {
        if (speedBonus <= 0) return baseDuration
        val reductionPercent =
            speedBonus / GameConfig.PolicyConfig.SPEED_REDUCTION_DIVISOR
        val reducedMonths = (baseDuration * reductionPercent).toInt()
        return (baseDuration - reducedMonths).coerceAtLeast(1)
    }

    /**
     * 读档收获产出入库（来源 "building"）。
     *
     * @return true=产出成功（溢出自动转邮件也算成功）；false=入库失败/配方无效
     *         （B4：产出失败视为炼制失败，不结算晋升，防装备/丹药静默丢失）
     */
    private suspend fun completeBuildingTaskFromProductionSlot(
        slot: ProductionSlot
    ): Boolean {
        val recipeId = slot.recipeId ?: return false
        return when (slot.buildingId) {
            BuildingNames.FORGE -> produceForgeEquipmentFromSlot(recipeId)
            BuildingNames.ALCHEMY -> producePillWithRecipe(recipeId)
            else -> false
        }
    }

    /** 锻造产出入库（配方无效 → 产出失败）。 */
    private suspend fun produceForgeEquipmentFromSlot(recipeId: String): Boolean {
        val recipe = ForgeRecipeDatabase.getRecipeById(recipeId) ?: return false
        val equipment = inventorySystem.createEquipmentFromRecipe(recipe)
        val r = inventorySystem.withTrackingSource("building") { inventorySystem.addEquipmentStack(equipment) }
        return when (r) {
            is DomainResult.Success -> true
            is DomainResult.Partial -> {
                DomainLog.w(TAG, "装备 ${equipment.name} 溢出 ${r.overflow} 个")
                true
            }
            is DomainResult.Failure -> {
                DomainLog.e(TAG, "装备 ${equipment.name} 添加失败: ${r.error}")
                false
            }
        }
    }

    /** 炼丹产出入库（品阶 roll + 入库；配方无效 → 产出失败）。 */
    private suspend fun producePillWithRecipe(recipeId: String): Boolean {
        val recipe = PillRecipeDatabase.getRecipeById(recipeId) ?: return false
        val roll = rngManager.getRng(
            com.xianxia.sect.core.util.RngPartition.SYSTEM
        ).nextDouble()
        val grade = when {
            roll < PILL_GRADE_HIGH_THRESHOLD -> PillGrade.HIGH
            roll < PILL_GRADE_MEDIUM_THRESHOLD -> PillGrade.MEDIUM
            else -> PillGrade.LOW
        }
        val baseId = recipeId.substringBeforeLast("_")
        val template = ItemDatabase.getPillById(
            "${baseId}_${grade.name.lowercase()}"
        )
        val pill = if (template != null) {
            ItemDatabase.createPillFromTemplate(template)
        } else {
            Pill(
                name = recipe.name,
                rarity = recipe.rarity,
                grade = grade,
                category = PillCategory.CULTIVATION,
                description = "通过炼丹炉炼制而成",
                minRealm = GameConfig.Realm.getMinRealmForRarity(
                    recipe.rarity
                ),
                quantity = 1
            )
        }
        val r = inventorySystem.withTrackingSource("building") { inventorySystem.addPill(pill) }
        return when (r) {
            is DomainResult.Success -> true
            is DomainResult.Partial -> {
                DomainLog.w(TAG, "丹药 ${pill.name} 溢出 ${r.overflow} 个")
                true
            }
            is DomainResult.Failure -> {
                DomainLog.e(TAG, "丹药 ${pill.name} 添加失败: ${r.error}")
                false
            }
        }
    }
}
