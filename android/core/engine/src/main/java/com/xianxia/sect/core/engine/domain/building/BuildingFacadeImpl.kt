package com.xianxia.sect.core.engine.domain.building

import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.SectLevel
import com.xianxia.sect.core.engine.GameEngineCore
import com.xianxia.sect.core.engine.domain.production.ProductionCoordinator
import com.xianxia.sect.core.engine.system.InventorySystem
import com.xianxia.sect.core.model.AlchemyResult
import com.xianxia.sect.core.model.BuildingSlot
import com.xianxia.sect.core.model.DiscipleStatus
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.GridBuildingData
import com.xianxia.sect.core.model.SlotCategory
import com.xianxia.sect.core.model.SlotRef
import com.xianxia.sect.core.model.SpiritFieldPlant
import com.xianxia.sect.core.model.SpiritStoneGrade
import com.xianxia.sect.core.model.production.BuildingType
import com.xianxia.sect.core.model.production.ProductionSlot
import com.xianxia.sect.core.model.guide.GuideCounterKeys
import com.xianxia.sect.core.util.AppError
import com.xianxia.sect.core.util.BuildingNames
import com.xianxia.sect.core.util.DomainLog
import com.xianxia.sect.core.util.DomainResult
import com.xianxia.sect.core.util.FixedSectGateway
import com.xianxia.sect.core.util.GridSystem
import com.xianxia.sect.core.state.GameStateStore
import com.xianxia.sect.core.state.MutableGameState
import com.xianxia.sect.core.wallet.SpiritStoneSource
import com.xianxia.sect.core.wallet.SpiritStoneWallet
import com.xianxia.sect.core.engine.domain.disciple.DiscipleSlotCleanup
import com.xianxia.sect.core.engine.domain.disciple.DiscipleStatusService
import com.xianxia.sect.core.engine.di.IoDispatcher
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton



@Singleton
@Suppress("TooManyFunctions")  // 27 个 override 镜像 BuildingFacade 接口契约下界 + 深耦合成员扩展
// （与注入服务双接收者绑定）——TMF 余量为契约骨架，可移动函数已拆出 BuildingFacadeImpl同步Ops.kt
class BuildingFacadeImpl @Inject constructor(
    override val buildingService: BuildingService,
    internal val stateStore: GameStateStore,
    internal val gameEngineCore: GameEngineCore,
    internal val productionCoordinator: ProductionCoordinator,
    private val inventorySystem: InventorySystem,
    private val spiritStoneWallet: SpiritStoneWallet,
    internal val assignmentGate: com.xianxia.sect.core.engine.domain.disciple.DiscipleAssignmentGate,
    internal val discipleStatusService: DiscipleStatusService,
    internal val ioDispatcher: IoDispatcher,
) : BuildingFacade {

    internal companion object {
        /**
         * 单用户定向补偿邮件（MailService 扩展，独立文件）。
         *
         * 拆分原因：MailService 类主体接近 detekt LargeClass（800 行）阈值，
         * 补偿邮件属独立运营配置，放独立文件保持 MailService 规模稳定；
         * stateStore/mailRepo 已放宽为 internal 供本扩展读取（三重防护）。
         */
        const val TAG = "BuildingFacadeImpl"
    }

    /** batch-06 建筑域 native 事务转发臂（懒构造，依赖与门面同源） */
    private val nativeTx by lazy {
        BuildingNativeTx(stateStore, gameEngineCore, productionCoordinator, assignmentGate, discipleStatusService)
    }

    /**
     * 建筑放置 native 事务尝试（batch-06 写者下沉，转发臂见 [BuildingNativeTx]）。
     * AUTHORITATIVE 门控 + 镜像回读；失败信封/降级返回 false——调用方回退
     * Kotlin 原路径重执行校验链（双实现并行契约）。
     */
    override fun tryNativePlaceBuilding(
        building: GridBuildingData,
        feature: BuildingFeature,
        cost: Long
    ): Boolean = nativeTx.tryPlace(building, feature, cost)

    /**
     * 放置槽位派生事务尝试（W4-A·w3-09，1811）——createSlots 残差下沉，
     * 生产/长老组留 Kotlin（偏差登记见 [BuildingNativeTx.tryPlaceSlots]）。
     */
    override fun tryNativePlaceSlotsResidual(
        feature: BuildingFeature,
        instanceId: String,
        activeId: String
    ): Boolean = nativeTx.tryPlaceSlots(feature, instanceId, activeId)

    override suspend fun placeBuilding(building: GridBuildingData) {
        val sectId = stateStore.gameDataSnapshot.activeSectId
        val counterKey = GuideCounterKeys.buildingBuiltKey(building.displayName)
        stateStore.update {
            gameData = gameData.copy(
                placedBuildings = gameData.placedBuildings + building.copy(sectId = sectId),
                guideCounters = gameData.guideCounters +
                    (counterKey to ((gameData.guideCounters[counterKey] ?: 0L) + 1))
            )
        }
        if (BuildingFeatureRegistry.findByDisplayName(building.displayName)?.buildingType == BuildingType.MINING) {
            syncSpiritMineSlotsAfterPlace()
        }
    }

    override suspend fun moveBuildingDirect(instanceId: String, newGridX: Int, newGridY: Int) {
        // batch-06 native 臂：迁移事务（存在性/环界门楼校验 + 坐标改写）C++
        // 真相先行；失败信封/降级回退下方 Kotlin 原路径（校验重执行，非法
        // 移动静默拒绝语义不变——双实现并行契约）
        if (nativeTx.move(instanceId = instanceId, newGridX = newGridX, newGridY = newGridY)) return
        val sectId = stateStore.gameDataSnapshot.activeSectId
        // 第二层防御：验证新位置不在边界树木区域、不重叠固定结构（宗门入口）占地
        val building = stateStore.gameDataSnapshot.placedBuildings
            .find { it.instanceId == instanceId && it.sectId == sectId } ?: return
        val border = GameConfig.SectMap.BORDER_TREE_RING
        val outOfBounds = newGridX < border || newGridY < border ||
            newGridX + building.width > GameConfig.SectMap.WORLD_WIDTH_CELLS - border ||
            newGridY + building.height > GameConfig.SectMap.WORLD_HEIGHT_CELLS - border
        val blocked = FixedSectGateway.blockedCells
        val overlapsBlocked = (newGridY until newGridY + building.height).any { cy ->
            (newGridX until newGridX + building.width).any { cx ->
                GridSystem.packCell(cx, cy) in blocked
            }
        }
        if (outOfBounds || overlapsBlocked) return

        stateStore.update {
            gameData = gameData.copy(
                placedBuildings = gameData.placedBuildings.map {
                    if (it.instanceId == instanceId && it.sectId == sectId) it.copy(gridX = newGridX, gridY = newGridY)
                    else it
                }
            )
        }
    }

    override suspend fun assignDiscipleToBuilding(buildingId: String, slotIndex: Int, discipleId: String) {
        buildingService.assignDiscipleToBuilding(buildingId, slotIndex, discipleId)
    }

    override suspend fun removeDiscipleFromBuilding(buildingId: String, slotIndex: Int) {
        buildingService.removeDiscipleFromBuilding(buildingId, slotIndex)
    }

    override fun getBuildingSlots(buildingId: String): List<BuildingSlot> =
        productionCoordinator.getSlotsByBuildingId(buildingId).map { it.toBuildingSlot() }

    /** 开始炼丹。成功返回 [DomainResult.Success] 含槽位，失败携带具体错误原因。 */
    override suspend fun startAlchemy(slotIndex: Int, recipeId: String): DomainResult<ProductionSlot> {
        // 手动排班 C++ 真相先行（AUTHORITATIVE 转发——校验链/材料消耗/
        // 槽位 WORKING 在 C++；successRate=-1 哨兵由 C++ 原生公式计算）。
        // 失败信封 → 回退 Kotlin 原路径（校验错误由 Kotlin 同语义复现）。
        productionNativeStart(BuildingNames.ALCHEMY, slotIndex, recipeId, true)?.let { return it }
        return buildingService.startAlchemy(slotIndex, recipeId)
    }

    /** 开始锻造。成功返回 [DomainResult.Success] 含槽位，失败携带具体错误原因。 */
    override suspend fun startForging(slotIndex: Int, recipeId: String): DomainResult<ProductionSlot> {
        productionNativeStart(BuildingNames.FORGE, slotIndex, recipeId, false)?.let { return it }
        return buildingService.startForging(slotIndex, recipeId)
    }

    /**
     * Auto-harvest all completed alchemy slots.
     * Called internally during month advancement.
     */
    override suspend fun autoHarvestCompletedAlchemySlots(): List<AlchemyResult> {
        return buildingService.autoHarvestCompletedAlchemySlots()
    }

    override fun getForgeSlots(): List<BuildingSlot> =
        productionCoordinator.getSlotsByBuildingId(BuildingNames.FORGE).map { it.toBuildingSlot() }

    override fun getAlchemyFurnaceCount(): Int {
        return BuildingFeatureRegistry.countByType(stateStore.gameDataSnapshot, BuildingType.ALCHEMY)
    }

    override fun getForgeWorkshopCount(): Int {
        return BuildingFeatureRegistry.countByType(stateStore.gameDataSnapshot, BuildingType.FORGE)
    }

    override fun getAssignedDiscipleForSlot(buildingType: BuildingType, slotIndex: Int): Pair<String, String>? {
        val slot = productionCoordinator.repository.getSlotByIndex(buildingType, slotIndex)
        val id = slot?.assignedDiscipleId
        return if (id.isNullOrEmpty()) null else Pair(id, slot.assignedDiscipleName)
    }

    override fun assignDiscipleToProductionSlot(
        buildingType: BuildingType,
        slotIndex: Int,
        discipleId: String,
        discipleName: String
    ) {
        gameEngineCore.launchInScope {
            // 登记新分配（覆盖任何旧分配记录）
            val targetSlot = com.xianxia.sect.core.model.SlotRef(
                category = com.xianxia.sect.core.model.SlotCategory.PRODUCTION_SLOT,
                slotType = "${buildingType}:${slotIndex}",
                slotId = "production_${buildingType}_${slotIndex}"
            )

            // 若目标槽位已有弟子，先释放其 gate 注册（状态 sync 在事务完成后执行，
            // 此处 GameData 仍含旧槽位，sync 会推导出旧状态造成残留）
            val existingSlot = productionCoordinator.repository.getSlotByIndex(buildingType, slotIndex)
            releaseGateIfOccupantChanged(existingSlot, discipleId)

            // batch-17 native 臂：镜像事务（全槽位清理 GameData 11 类槽位 + 目标槽
            // 写 occupant + 该弟子他槽清 occupant）C++ 真相先行；降级/校验失败
            // （零写入）回退下方 Kotlin 原路径镜像事务（双实现并行契约）
            if (!productionNativeAssign(buildingType, slotIndex, discipleId, discipleName)) {
                // 事务内清理 GameData 全部槽位（漏清巡逻/长老/藏经阁等槽位
                // 会导致同一弟子多槽位），并同步 GameData.productionSlots 镜像
                stateStore.update {
                    gameData = DiscipleSlotCleanup(assignmentGate)
                        .clearAllSlotsDataOnly(gameData, discipleId)
                    gameData = gameData.copy(
                        productionSlots = gameData.productionSlots.map { slot ->
                            when {
                                slot.buildingType == buildingType && slot.slotIndex == slotIndex ->
                                    slot.copy(assignedDiscipleId = discipleId, assignedDiscipleName = discipleName)
                                slot.assignedDiscipleId == discipleId ->
                                    slot.copy(assignedDiscipleId = null, assignedDiscipleName = "")
                                else -> slot
                            }
                        }
                    )
                }
            }

            // 残差（镜像已由 native/回退臂写就）：repo 回写 + gate 登记 + 旧 occupant 状态同步
            finishProductionAssignment(
                buildingType, slotIndex, discipleId, discipleName, existingSlot, targetSlot
            )
        }
    }

    override fun removeDiscipleFromProductionSlot(buildingType: BuildingType, slotIndex: Int) {
        gameEngineCore.launchInScope {
            val slot = productionCoordinator.repository.getSlotByIndex(buildingType, slotIndex)
            val discipleId = slot?.assignedDiscipleId

            // batch-17 native 臂：镜像事务（占用捕获 + WORKING 剩余时长归一 +
            // 槽位清空）；成功 → 镜像槽单槽回放 repo（S7 productionNativeReset 同族）。
            // 降级/校验失败回退下方 Kotlin 原路径（repo 先行 + 镜像清空）
            if (productionNativeRemove(buildingType, slotIndex)) {
                replayMirrorSlotToRepository(buildingType, slotIndex)
            } else {
                val data = stateStore.gameDataSnapshot
                val currentYear = data.gameYear
                val currentMonth = data.gameMonth

                // repo 先写、成功才清镜像（失败两端皆未变，无补偿需求）——镜像残留会让状态推导
                // 仍 WORKING、自动重启按镜像判定继续生产（玩家"卸不掉/槽位仍占用"链路）
                val result = withContext(ioDispatcher.dispatcher) {
                    productionCoordinator.repository.updateSlot(buildingType, slotIndex) { s ->
                        if (s.isWorking && !s.assignedDiscipleId.isNullOrEmpty()) {
                            val remaining = s.remainingTime(currentYear, currentMonth)
                            s.copy(
                                assignedDiscipleId = null,
                                assignedDiscipleName = "",
                                startYear = currentYear,
                                startMonth = currentMonth,
                                duration = remaining.coerceAtLeast(1)
                            )
                        } else {
                            s.copy(assignedDiscipleId = null, assignedDiscipleName = "")
                        }
                    }
                }
                if (result.isFailure) {
                    DomainLog.e(
                        TAG,
                        "卸任失败: ${buildingType}[$slotIndex] disciple=$discipleId, " +
                            (result.exceptionOrNull()?.message ?: "unknown")
                    )
                    return@launchInScope
                }
                // repo 写成功 → 同步清镜像，双端一致
                stateStore.update {
                    gameData = gameData.copy(
                        productionSlots = gameData.productionSlots.map { s ->
                            if (s.buildingType == buildingType && s.slotIndex == slotIndex) {
                                s.copy(assignedDiscipleId = null, assignedDiscipleName = "")
                            } else s
                        }
                    )
                }
            }
            if (discipleId != null) {
                assignmentGate.release(discipleId)
                discipleStatusService.syncSingleDiscipleStatus(discipleId)
            }
        }
    }

    override suspend fun toggleAutoRestart(buildingType: BuildingType, slotIndex: Int) {
        // batch-17 native 臂：镜像字段翻转（槽位存在性校验，失败零写入）；
        // 成功 → 单字段回放 repo。降级/校验失败回退下方 Kotlin 原路径（repo 翻转 + 镜像同步）
        val nativeValue = productionNativeToggleAutoRestart(buildingType, slotIndex)
        if (nativeValue != null) {
            replayToggleToRepository(buildingType, slotIndex, nativeValue)
            return
        }
        val result = withContext(ioDispatcher.dispatcher) {
            productionCoordinator.repository.updateSlot(buildingType, slotIndex) { slot ->
                slot.copy(autoRestartEnabled = !slot.autoRestartEnabled)
            }
        }
        if (result.isFailure) {
            DomainLog.e(
                TAG,
                "切换自动重启失败: ${buildingType}[$slotIndex], " +
                    (result.exceptionOrNull()?.message ?: "unknown")
            )
            return
        }
        // repo 更新成功 → 同步镜像字段（防镜像过期；行为以 repo 为准）
        val newValue = result.getOrNull()?.autoRestartEnabled ?: return
        stateStore.update {
            gameData = gameData.copy(
                productionSlots = gameData.productionSlots.map { s ->
                    if (s.buildingType == buildingType && s.slotIndex == slotIndex) {
                        s.copy(autoRestartEnabled = newValue)
                    } else s
                }
            )
        }
    }

    override suspend fun addProductionSlot(slot: ProductionSlot) {
        // batch-17 native 臂：镜像 upsert（按 buildingId+slotIndex 幂等；放置建筑时
        // Kotlin 已把新槽追加进镜像，本臂使"C++ 真相先行"约定自洽）。降级无副作用。
        productionNativeAddSlot(slot)
        withContext(ioDispatcher.dispatcher) {
            productionCoordinator.repository.addSlot(slot)
        }
    }

    /** 播种时序快照（一次取值，循环内不变） */
    internal data class PlantingTime(val year: Int, val month: Int, val absoluteMonth: Int)

    /** 目标地块是否可播种：空地且属于本宗（空 sectId 为旧数据兼容） */
    internal fun SpiritFieldPlant.isPlantable(sectId: String): Boolean =
        seedId.isEmpty() && (this.sectId.isEmpty() || this.sectId == sectId)

    override suspend fun plantOnSpiritField(buildingInstanceId: String, seedId: String, sectId: String) {
        // batch-17 native 臂：种子校验（存在/未锁定/余量>0）+ 空地匹配 + 同事务扣种
        // C++ 真相先行；降级/校验失败（零写入）回退下方 Kotlin 原路径
        if (spiritFieldNativePlantOne(buildingInstanceId, seedId, sectId)) return
        val seed = inventorySystem.getSeedById(seedId) ?: return
        if (seed.quantity <= 0) return

        stateStore.update {
            // 事务内读取最新种子数量/锁定态，同事务扣种——替代事务外 removeSeedSync
            // （其返回值被忽略是"种子不足也种满、免费种田"根因，Bug B）
            val seedEntry = seeds.get(seedId)
            val available = seedEntry?.quantity ?: 0
            if (seedEntry == null || available <= 0 || seedEntry.isLocked) return@update

            val idx = gameData.spiritFieldPlants.indexOfFirst {
                it.buildingInstanceId == buildingInstanceId && it.isPlantable(sectId)
            }
            if (idx < 0) return@update

            val currentYear = gameData.gameYear
            val currentMonth = gameData.gameMonth
            val currentAbsoluteMonth = com.xianxia.sect.core.engine.LazyEvaluationDispatcher
                .toAbsoluteMonth(currentYear, currentMonth)
            val updatedPlants = gameData.spiritFieldPlants.toMutableList()
            updatedPlants[idx] = updatedPlants[idx].copy(
                seedId = seedId,
                seedName = seed.name,
                growTime = seed.growTime,
                expectedYield = seed.yield,
                plantYear = currentYear,
                plantMonth = currentMonth,
                sectId = sectId,
                completionMonth = currentAbsoluteMonth + seed.growTime.coerceAtLeast(1),
                completionPhase = 3  // 种植下旬
            )
            gameData = gameData.copy(spiritFieldPlants = updatedPlants)
            // 同事务扣种（种植与扣种原子提交，无竞态窗口）
            val newQty = available - 1
            if (newQty <= 0) seeds.remove(seedId)
            else seeds.update(seedId) { it.copy(quantity = newQty) }
        }
    }

    override suspend fun plantOnSpiritFields(instanceIds: List<String>, seedId: String, sectId: String) {
        // batch-17 native 臂：余量约束上限 + 地块镜像序播种 + 按实际播种数扣种；
        // 降级/校验失败/零命中（零写入）回退下方 Kotlin 原路径
        if (spiritFieldNativePlantBatch(instanceIds, seedId, sectId)) return
        if (instanceIds.isEmpty()) return
        val seed = inventorySystem.getSeedById(seedId) ?: return
        if (seed.quantity <= 0) return

        stateStore.update {
            // 事务内读取最新种子数量/锁定态，同事务扣种（Bug B：种植数受种子数量约束，
            // 替代事务外 removeSeedSync——其返回值被忽略导致免费种田）
            val seedEntry = seeds.get(seedId)
            val available = seedEntry?.quantity ?: 0
            if (seedEntry == null || available <= 0 || seedEntry.isLocked) return@update

            val timing = PlantingTime(
                year = gameData.gameYear,
                month = gameData.gameMonth,
                absoluteMonth = com.xianxia.sect.core.engine.LazyEvaluationDispatcher.toAbsoluteMonth(
                    gameData.gameYear, gameData.gameMonth)
            )
            val (updatedPlants, planted) = plantFields(
                plants = gameData.spiritFieldPlants,
                emptyFieldIds = instanceIds.toSet(),
                seed = seed,
                maxToPlant = minOf(available, instanceIds.size),
                timing = timing,
                sectId = sectId
            )
            if (planted > 0) {
                gameData = gameData.copy(spiritFieldPlants = updatedPlants)
                // 同事务扣种（种植与扣种原子提交）
                val newQty = available - planted
                if (newQty <= 0) seeds.remove(seedId)
                else seeds.update(seedId) { it.copy(quantity = newQty) }
            }
        }
    }

    override suspend fun removePlantFromSpiritField(buildingInstanceId: String) {
        // batch-17 native 臂：首命中实例清空种植字段（未知实例为静默成功空操作）；
        // 降级回退下方 Kotlin 原路径
        if (spiritFieldNativeRemoveOne(buildingInstanceId)) return
        stateStore.update {
            val idx = gameData.spiritFieldPlants.indexOfFirst { it.buildingInstanceId == buildingInstanceId }
            if (idx < 0) return@update

            val updatedPlants = gameData.spiritFieldPlants.toMutableList()
            updatedPlants[idx] = updatedPlants[idx].copy(
                seedId = "",
                seedName = "",
                growTime = 0,
                expectedYield = 0,
                plantYear = 0,
                plantMonth = 0,
                completionMonth = 0,
                completionPhase = 1
            )
            gameData = gameData.copy(spiritFieldPlants = updatedPlants)
        }
    }

    override suspend fun removePlantsFromSpiritFields(instanceIds: List<String>) {
        // batch-17 native 臂：实例集合清空种植字段；空集合为静默成功空操作。
        // 降级回退下方 Kotlin 原路径
        if (spiritFieldNativeRemoveBatch(instanceIds)) return
        if (instanceIds.isEmpty()) return
        stateStore.update {
            val idSet = instanceIds.toSet()
            val updatedPlants = gameData.spiritFieldPlants.map { plant ->
                if (plant.buildingInstanceId in idSet) {
                    plant.copy(
                        seedId = "",
                        seedName = "",
                        growTime = 0,
                        expectedYield = 0,
                        plantYear = 0,
                        plantMonth = 0,
                        completionMonth = 0,
                        completionPhase = 1
                    )
                } else plant
            }
            gameData = gameData.copy(spiritFieldPlants = updatedPlants)
        }
    }

    override fun clearAlchemySlot(slotIndex: Int): DomainResult<Unit> {
        if (slotIndex < 0) return DomainResult.Failure(AppError.Domain.Production.InvalidSlot(slotIndex = slotIndex))
        gameEngineCore.launchInScope {
            if (!productionNativeReset(BuildingNames.ALCHEMY, slotIndex)) {
                withContext(ioDispatcher.dispatcher) {
                    productionCoordinator.resetSlotByBuildingIdAtomic(BuildingNames.ALCHEMY, slotIndex)
                }
            }
        }
        return DomainResult.Success(Unit)
    }

    override fun clearForgeSlot(slotIndex: Int): DomainResult<Unit> {
        if (slotIndex < 0) return DomainResult.Failure(AppError.Domain.Production.InvalidSlot(slotIndex = slotIndex))
        gameEngineCore.launchInScope {
            val slot = productionCoordinator.repository.getSlotByBuildingId(BuildingNames.FORGE, slotIndex)
            if (slot != null && !slot.isWorking) {
                slot.assignedDiscipleId?.let { discipleId ->
                    updateDiscipleStatus(discipleId, DiscipleStatus.IDLE)
                }
            }
            if (!productionNativeReset(BuildingNames.FORGE, slotIndex)) {
                withContext(ioDispatcher.dispatcher) {
                    productionCoordinator.resetSlotByBuildingIdAtomic(BuildingNames.FORGE, slotIndex)
                }
            }
        }
        return DomainResult.Success(Unit)
    }

    override suspend fun removeBuilding(instanceId: String, refund: Long) {
        removeBuildings(mapOf(instanceId to refund))
    }

    /**
     * 批量拆除多座建筑（一键拆除）。
     * 单次事务内逐栋清理关联槽位并返还灵石，事务后统一同步弟子状态。
     *
     * @param refunds 建筑 instanceId → 返还灵石数映射；未知实例自动跳过
     */
    override suspend fun removeBuildings(refunds: Map<String, Long>) {
        // batch-06 native 臂：拆除事务（存在性/幽灵防御 + 灵石返还）C++ 真相
        // 先行，残差派生清理（槽位/弟子/特例/repo）在 [BuildingNativeTx] 内
        // 承担；降级/失败信封回退 Kotlin 原路径完整事务
        if (refunds.isNotEmpty() && nativeTx.removeBuildings(refunds)) return
        removeBuildingsInternal(refunds)
    }

    /**
     * 单座建筑升级（原地变换 + 扣差价）。
     *
     * 任一条件不满足（宗门等级 / 灵石差价 / 升级后占地空间）时返回
     * [UpgradeResult.Failure] 且状态零变更（原子性）。
     *
     * @param instanceId 目标建筑实例 id
     * @return [UpgradeResult.Success]（升级 1 座）或 [UpgradeResult.Failure]（含逐条原因）
     */
    override suspend fun upgradeBuilding(instanceId: String): UpgradeResult =
        withContext(ioDispatcher.dispatcher) {
            val snapshot = stateStore.gameDataSnapshot
            val building = snapshot.placedBuildings.find { it.instanceId == instanceId }
                ?: return@withContext UpgradeResult.Failure(listOf("建筑不存在"))
            val def = BuildingUpgradeRegistry.findUpgrade(building.buildingId)
                ?: return@withContext UpgradeResult.Failure(listOf("该建筑不可升级"))
            val target = BuildingFeatureRegistry.findByKey(def.targetKey)
                ?: return@withContext UpgradeResult.Failure(listOf("升级目标配置缺失：${def.targetKey}"))
            val cost = BuildingUpgradeRegistry.upgradeCost(def)
            // batch-06 native 臂：升级事务（等级/差价/canFit 校验 + 原地变换 +
            // 差价直扣）C++ 真相先行；失败信封/降级回退下方原路径（checkUpgrade
            // 重执行产出逐条中文原因——双实现并行契约）
            nativeTx.upgradeSingle(instanceId, building.sectId, target, cost)
                ?.let { return@withContext it }
            var outcome: UpgradeResult = UpgradeResult.Failure(listOf("未知错误"))
            stateStore.update {
                when (val check = BuildingUpgradeCalculator.checkUpgrade(gameData, instanceId)) {
                    is UpgradeCheckResult.ConditionsUnmet -> outcome = UpgradeResult.Failure(check.reasons)
                    UpgradeCheckResult.NotUpgradeable -> outcome = UpgradeResult.Failure(listOf("该建筑不可升级"))
                    UpgradeCheckResult.Upgradeable -> {
                        val index = gameData.placedBuildings.indexOfFirst { it.instanceId == instanceId }
                        if (index < 0) {
                            outcome = UpgradeResult.Failure(listOf("建筑不存在"))
                            return@update
                        }
                        val updated = gameData.placedBuildings.toMutableList()
                        updated[index] = updated[index].copy(
                            buildingId = target.key,
                            displayName = target.displayName,
                            width = target.gridWidth,
                            height = target.gridHeight
                        )
                        gameData = gameData.copy(
                            spiritStones = gameData.spiritStones - cost,
                            placedBuildings = updated
                        )
                        outcome = UpgradeResult.Success(1)
                    }
                }
            }
            outcome
        }

    override suspend fun upgradeBuildings(
        sectId: String,
        sourceKey: String,
        maxCount: Int
    ): UpgradeResult = withContext(ioDispatcher.dispatcher) {
        val def = BuildingUpgradeRegistry.findUpgrade(sourceKey)
            ?: return@withContext UpgradeResult.Failure(listOf("该建筑不可升级"))
        if (maxCount <= 0) {
            return@withContext UpgradeResult.Failure(listOf("灵石不足，无法升级"))
        }
        val target = BuildingFeatureRegistry.findByKey(def.targetKey)
            ?: return@withContext UpgradeResult.Failure(listOf("升级目标配置缺失：${def.targetKey}"))
        val cost = BuildingUpgradeRegistry.upgradeCost(def)
        // batch-06 native 臂：批量升级事务（整批等级/候选稳定序/可负担上限/
        // 增量 canFit）C++ 真相先行；失败信封/降级回退下方 Kotlin 原路径事务
        nativeTx.upgradeBatch(sectId, sourceKey, target, maxCount, cost)
            ?.let { return@withContext it }
        upgradeBuildingsFallbackTx(sectId, sourceKey, def, target, cost, maxCount)
    }

    /** 批量升级回退臂事务（Kotlin 原路径——native 失败/降级时重执行校验链）。 */
    private fun upgradeBuildingsFallbackTx(
        sectId: String,
        sourceKey: String,
        def: BuildingUpgradeDef,
        target: BuildingFeature,
        cost: Long,
        maxCount: Int
    ): UpgradeResult {
        val sourceName = BuildingFeatureRegistry.findByKey(def.sourceKey)?.displayName ?: sourceKey
        var outcome: UpgradeResult = UpgradeResult.Failure(listOf("未知错误"))
        stateStore.update {
            // 宗门等级门槛整批判定（需求：灵石以外条件不满足 → 明确告知）
            val levelFailure = checkUpgradeSectLevel(gameData)
            if (levelFailure != null) {
                outcome = UpgradeResult.Failure(listOf(levelFailure))
                return@update
            }

            val candidates = upgradeCandidates(gameData, sectId, sourceKey)
            if (candidates.isEmpty()) {
                outcome = UpgradeResult.Failure(listOf("没有可升级的$sourceName"))
                return@update
            }

            // 灵石可负担上限（需求：灵石不足以全部升级时按可负担数量升级）
            val affordable = (gameData.spiritStones / cost).coerceAtMost(maxCount.toLong()).toInt()
            if (affordable <= 0) {
                outcome = UpgradeResult.Failure(
                    listOf("灵石不足（升级需$cost 灵石/座，当前${gameData.spiritStones}），无法升级")
                )
                return@update
            }

            val working = gameData.placedBuildings.toMutableList()
            var remainingStones = gameData.spiritStones
            var upgraded = 0
            var spaceBlocked = 0
            for (candidate in candidates) {
                if (upgraded >= affordable) break
                when (val result = tryUpgradeOne(working, candidate, def, target)) {
                    UpgradeOneResult.Upgraded -> {
                        upgraded++
                        remainingStones -= cost
                    }
                    UpgradeOneResult.SpaceBlocked -> spaceBlocked++
                    UpgradeOneResult.Missing -> Unit
                }
            }
            // 零升级时 copy 幂等（working/remainingStones 与原值相等），无需单独守卫
            gameData = gameData.copy(
                spiritStones = remainingStones,
                placedBuildings = working
            )
            outcome = UpgradeResult.Success(upgraded, spaceBlocked)
        }
        return outcome
    }

    /**
     * 没收某宗门全部建筑（无灵石返还，拆除返还额为 0）。
     *
     * 玩家占领宗门被 AI 夺回时由引擎月度结算调用——玩家在该宗门建造的建筑
     * 整体拆除，槽位/弟子完整清理（复用 [removeBuildingsInternal] 全流程），
     * 灵石不返还（没收语义，与"自动拆除"产品决策一致）。
     */
    override fun seizeBuildingsOfSect(sectId: String) {
        if (sectId.isEmpty()) return  // 本宗（""）不可没收
        val refunds = stateStore.gameDataSnapshot.placedBuildings
            .filter { it.sectId == sectId }
            .associate { it.instanceId to 0L }
        if (refunds.isEmpty()) return
        // W4-A·w3-09 native 臂：拆除 + 残差清扫 C++ 真相先行（与玩家拆除
        // 同一 [BuildingNativeTx.removeBuildings] 入口）；降级/失败信封回退
        // removeBuildingsInternal 原路径。月变没收调用点（宿主文件族经
        // GameEngine.seizedBuildingsHandler 转入）零改动。
        if (nativeTx.removeBuildings(refunds)) return
        removeBuildingsInternal(refunds)
    }

    internal fun MutableGameState.cleanupBuildingSlots(
        feature: BuildingFeature, building: GridBuildingData, refund: Long
    ): GameData {
        val instanceId = building.instanceId
        // 收集关联弟子须在槽位过滤之前（filterFromGameData 会删除槽位记录，
        // 之后收集将丢失弟子 ID）；生产槽位须查运行时 Repository（GameData 仅存档镜像）
        val discipleIds = feature.slotGroups
            .flatMap { it.collectDiscipleIds(gameData, instanceId, feature) }
            .toMutableSet()
        if (feature.slotGroups.any { it is SlotGroup.ProductionSlotGroup }) {
            discipleIds += productionCoordinator.repository.getSlots()
                .filter { it.buildingInstanceId == instanceId }
                .mapNotNull { it.assignedDiscipleId }
        }
        // 通过钱包记录灵石返还
        spiritStoneWallet.add(this, refund, SpiritStoneGrade.LOW, SpiritStoneSource.Refund)
        // 移除建筑 + 清洁关联槽位（灵石已由 applyAdd 处理）
        var gd = gameData.copy(
            placedBuildings = gameData.placedBuildings.filter { it.instanceId != instanceId }
        )
        for (group in feature.slotGroups) {
            gd = group.filterFromGameData(gd, instanceId, feature)
        }
        releaseBuildingDiscipleIds(discipleIds)
        if (feature.buildingType == BuildingType.REFLECTION_CLIFF) {
            releaseReflectingDisciples()
        }
        // 任务阁拆除：清理所有活跃任务并释放卡在 ON_MISSION 的弟子
        if (feature.buildingType == BuildingType.MISSION_HALL) {
            gd = gd.copy(activeMissions = emptyList())
            for (id in discipleTables.ids) {
                if (discipleTables.statuses[id] == DiscipleStatus.ON_MISSION &&
                    discipleTables.isAlive[id] == 1
                ) {
                    discipleTables.statuses[id] = DiscipleStatus.IDLE
                }
            }
        }
        return gd
    }

    /**
     * 释放建筑关联弟子：Gate 注册 + 血炼 REFINING 状态。
     * 血炼受保护状态须在事务内显式打破，否则事务外重推拉不回 IDLE。
     */
    internal fun MutableGameState.releaseBuildingDiscipleIds(discipleIds: Set<String>) {
        discipleIds.forEach { assignmentGate.release(it) }
        discipleIds.mapNotNull { it.toIntOrNull() }
            .filter { it in discipleTables.ids }
            .filter { discipleTables.statuses[it] == DiscipleStatus.REFINING }
            .forEach { dId ->
                discipleTables.statuses[dId] = DiscipleStatus.IDLE
                discipleTables.statusData[dId] =
                    (discipleTables.statusData[dId] ?: emptyMap()) - setOf("buildingId")
            }
    }

    /** 监牢拆除：释放所有思过弟子（监牢限建 1 座，无实例归属记录，全量释放）。 */
}

// ===== 批量升级辅助（upgradeBuildings 拆分，保持主流程 ≤60 行 / 圈复杂度 ≤15） =====

/** 批量升级单座结果 */
private sealed interface UpgradeOneResult {
    data object Upgraded : UpgradeOneResult
    data object SpaceBlocked : UpgradeOneResult
    data object Missing : UpgradeOneResult
}

/**
 * 单座升级尝试：以「升级中间态」增量校验空间（防相邻建筑同时扩占地互相重叠），
 * 通过则原地变换为升级目标建筑（保留 instanceId/gridX/gridY/sectId）。
 */
private fun tryUpgradeOne(
    working: MutableList<GridBuildingData>,
    candidate: GridBuildingData,
    def: BuildingUpgradeDef,
    target: BuildingFeature
): UpgradeOneResult {
    val index = working.indexOfFirst { it.instanceId == candidate.instanceId }
    if (index >= 0 && BuildingUpgradeCalculator.canFitUpgrade(working, candidate, def)) {
        working[index] = working[index].copy(
            buildingId = target.key,
            displayName = target.displayName,
            width = target.gridWidth,
            height = target.gridHeight
        )
        return UpgradeOneResult.Upgraded
    }
    return if (index < 0) UpgradeOneResult.Missing else UpgradeOneResult.SpaceBlocked
}

/**
 * 批量升级候选：同宗门同类型的建筑按 稳定序（gridX/gridY/instanceId）排序。
 */
private fun upgradeCandidates(
    gameData: GameData,
    sectId: String,
    sourceKey: String
): List<GridBuildingData> = gameData.placedBuildings
    .filter { it.sectId == sectId && it.buildingId == sourceKey }
    .sortedWith(compareBy({ it.gridX }, { it.gridY }, { it.instanceId }))

/**
 * 批量升级前置整批判定：宗门等级达到中型才允许升级。
 *
 * @return 不满足时的失败文案（直接用于提示框），满足时 null
 */
private fun checkUpgradeSectLevel(gameData: GameData): String? {
    val currentLevel = gameData.worldMapSects.find { it.isPlayerSect }?.level ?: SectLevel.SMALL
    return if (currentLevel < SectLevel.MEDIUM) {
        "需要宗门等级达到中型（当前${SectLevel.levelName(currentLevel)}），无法升级"
    } else null
}
