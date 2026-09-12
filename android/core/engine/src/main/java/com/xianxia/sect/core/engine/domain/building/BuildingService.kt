package com.xianxia.sect.core.engine.domain.building

import com.xianxia.sect.core.GameConfig
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
import com.xianxia.sect.core.repository.getSlotsByType






@GameService("BuildingService")
@Singleton
class BuildingService @Inject constructor(
    internal val stateStore: GameStateStore,
    private val productionCoordinator: ProductionCoordinator,
    internal val productionSlotRepository: ProductionSlotRepository,
    private val inventorySystem: InventorySystem,
    internal val formulaService: FormulaService,
    private val rngManager: com.xianxia.sect.core.util.GameRngManager,
    private val assignmentGate: DiscipleAssignmentGate,
    internal val ioDispatcher: IoDispatcher,
) {
    companion object {
        /**
         * 单用户定向补偿邮件（MailService 扩展，独立文件）。
         *
         * 拆分原因：MailService 类主体接近 detekt LargeClass（800 行）阈值，
         * 补偿邮件属独立运营配置，放独立文件保持 MailService 规模稳定；
         * stateStore/mailRepo 已放宽为 internal 供本扩展读取（三重防护）。
         */
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
        // 排他以建筑实例为单位（buildingId 是类型标识，非实例标识），
        // 互斥经 DiscipleAssignmentGate 注册表实现（见下方事务内清理）。
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

        // 旧 occupant：gate.release + 置 IDLE（不释放注册表会导致旧弟子从可用列表"消失"）
        existingSlot?.assignedDiscipleId?.let { oldDiscipleId ->
            if (oldDiscipleId.isNotEmpty() && oldDiscipleId != discipleId) {
                assignmentGate.release(oldDiscipleId)
                updateDiscipleStatus(oldDiscipleId, DiscipleStatus.IDLE)
            }
        }

        // 事务内清理 GameData 全部槽位 + 同步 GameData.productionSlots 镜像
        // （防同一弟子同时占多个生产槽位，或与巡逻/长老槽并存）
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
        val data = stateStore.gameData.value
        val (failure, slotOrNull) = validateSlotForProductionStart(slotIndex, BuildingNames.ALCHEMY)
        if (failure != null) return failure
        // 校验通过时槽位必然已存在（缺槽会在"弟子在位"检查被拦截）
        val alchemySlot = requireNotNull(slotOrNull)

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
        val data = stateStore.gameData.value
        val (failure, slotOrNull) = validateSlotForProductionStart(slotIndex, BuildingNames.FORGE)
        if (failure != null) return failure
        // 校验通过时槽位必然已存在（缺槽会在"弟子在位"检查被拦截）
        val forgeSlot = requireNotNull(slotOrNull)

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
     * 手动排班槽位前置校验（startAlchemy/startForging 共享）：槽位有效性 → 槽忙 →
     * 弟子在位三道检查。
     *
     * @return first = 校验失败结果（null = 通过）；second = 槽位（新槽惰性创建前为 null）
     */
    private suspend fun validateSlotForProductionStart(
        slotIndex: Int,
        buildingName: String
    ): Pair<DomainResult.Failure?, ProductionSlot?> {
        if (slotIndex < 0) {
            return DomainResult.Failure(
                AppError.Domain.Production.InvalidSlot(slotIndex = slotIndex)
            ) to null
        }

        val slot = productionSlotRepository.getSlotByBuildingId(buildingName, slotIndex)
        if (slot != null && slot.isWorking) {
            return DomainResult.Failure(
                AppError.Domain.Production.SlotBusy(slotIndex = slotIndex)
            ) to slot
        }
        if (slot?.assignedDiscipleId.isNullOrEmpty()) {
            return DomainResult.Failure(
                AppError.Domain.Production.DiscipleNotAvailable(discipleId = "")
            ) to slot
        }
        return null to slot
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

    /**
     * Auto-collect a completed slot's result and reset it to IDLE.
     * Called internally by the auto-harvest system during month advancement.
     */
    private suspend fun autoCollectSlotResult(slot: ProductionSlot) {
        // 锻造真实成功率判定：读档/惰性收获路径与月变路径
        // 一致——失败不产出装备、材料不退还，成功才计入职业晋升进度。
        // 产出入库失败（addEquipmentStack Failure/配方无效）视为炼制失败，
        // 不结算晋升，防装备静默丢失。
        // NaN 不被 coerceIn 钳制，显式归零
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

        // reset 与补清合并为单次 writeMutex 事务：消除"IDLE+死弟子"中间态，
        // 防止被并发排班占用后停滞（材料已扣、永不结算）。合并后排班要么看到
        // COMPLETED/WORKING 跳过，要么看到已清空的 IDLE 跳过，无中间态。
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
        // 无配方（recipeId null/模板查不到）→ 炼制失败，
        // 与读档路径 producePillWithRecipe 及月变路径 producePill 语义统一
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
        /** 出生随机流走 SYSTEM 分区（与伴侣配对/弟子招募同类系统级随机） */
        val rng = rngManager.getRng(
            com.xianxia.sect.core.util.RngPartition.SYSTEM
        )
        // successRate 先钳制到 [0,1]（存档篡改/旧数据可能越界）；
        // NaN 不被 coerceIn 钳制，显式归零。
        // 入库失败时视为炼制失败（success=false）
        val rate = if (slot.successRate.isNaN()) 0.0 else slot.successRate.coerceIn(0.0, 1.0)
        var success = rng.nextDouble() <= rate

        var pill: Pill? = null
        if (success) {
            pill = producePillFromSlot(slot, rng)
            if (pill == null) success = false
        }

        // 统一结算：引导计数 + 年度计数 + 弟子回 IDLE +
        // 职业晋升，与月变路径 ProductionProcessor 共用同一扩展
        // （读档/惰性收获路径同样完整结算，防止晋升计数丢失）
        val discipleAlive = slot.assignedDiscipleId?.let { discipleId ->
            stateStore.settleProductionCompletion(slot, discipleId, success, isAlchemy = true)
        } ?: false
        // 双存储同步：重置 Repository 槽位的同时清镜像槽位——收获后镜像残留
        // 会让状态推导把弟子重新拉回工作状态（双槽分叉根因）
        clearMirrorProductionSlot(BuildingNames.ALCHEMY, slot.slotIndex)

        // reset 与补清合并为单次 writeMutex 事务——
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
