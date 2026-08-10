package com.xianxia.sect.core.engine.service

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import com.xianxia.sect.core.engine.di.IoDispatcher
import kotlin.math.roundToInt
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.DiscipleStatus
import com.xianxia.sect.core.model.ElderSlots
import com.xianxia.sect.core.model.ExplorationTeam
import com.xianxia.sect.core.model.ForgeRecipe
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.Herb
import com.xianxia.sect.core.model.Pill
import com.xianxia.sect.core.model.PillCategory
import com.xianxia.sect.core.model.PillGrade
import com.xianxia.sect.core.model.SectPolicies
import com.xianxia.sect.core.model.SpiritFieldPlant
import com.xianxia.sect.core.model.artifactRefining
import com.xianxia.sect.core.model.comprehension
import com.xianxia.sect.core.model.mining
import com.xianxia.sect.core.model.pillRefining
import com.xianxia.sect.core.model.spiritPlanting
import com.xianxia.sect.core.model.guide.GuideCounterKeys
import com.xianxia.sect.core.model.production.ProductionSlot
import com.xianxia.sect.core.model.production.ProductionSlotStatus
import com.xianxia.sect.core.engine.system.InventoryFactories
import com.xianxia.sect.core.state.DiscipleTables
import com.xianxia.sect.core.state.GameStateStore
import com.xianxia.sect.core.state.MutableGameState
import com.xianxia.sect.core.state.StackKeys
import com.xianxia.sect.core.state.StackableItemStore
import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.profession.ProfessionRules
import com.xianxia.sect.core.registry.BeastMaterialDatabase
import com.xianxia.sect.core.registry.ForgeRecipeDatabase
import com.xianxia.sect.core.registry.ItemDatabase
import com.xianxia.sect.core.registry.PillRecipeDatabase
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
import com.xianxia.sect.core.engine.domain.disciple.DiscipleStatusService
import com.xianxia.sect.core.util.GameRngManager
import com.xianxia.sect.core.util.RngPartition
import com.xianxia.sect.core.engine.LazyEvaluationDispatcher
import com.xianxia.sect.core.model.production.BuildingType
import javax.inject.Inject
import javax.inject.Singleton






/**
 * 自动排班互斥决策（2026-08-10，详见 buildOccupiedSlotDiscipleIds）：
 * 候选过滤走"status==IDLE（存储权威）+ 全槽位占用集合（第二层防御）"，
 * **不**注册 DiscipleAssignmentGate（confirmAssign 不会调用
 * gate.filterAvailableDisciples——注册会造成 gate 陈旧条目，validateAutoSlot
 * 清槽路径不 release；gate 一致性由读档 rebuildFromGameData 兜底，
 * UI 全走 status 过滤）。
 */
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
                // B3：弟子死亡时 complete 返回 false → 槽位重置同时清空弟子关联
                val discipleAlive = completeForgeSlot(slot)
                resetSlotToIdle(slot, BuildingNames.FORGE,
                    BuildingType.FORGE, keepDisciple = discipleAlive)
            }
        }
    }

    private fun processAlchemyCompletion(year: Int, month: Int) {
        val alchemySlots = productionSlotRepository.getSlotsByType(
            BuildingType.ALCHEMY)
        alchemySlots.forEach { slot ->
            if (slot.isWorking && slot.assignedDiscipleId.isNullOrEmpty()) return@forEach
            if (slot.isWorking && isSlotCompleteDynamic(slot, year, month)) {
                val discipleAlive = completeAlchemySlot(slot)
                resetSlotToIdle(slot, BuildingNames.ALCHEMY,
                    BuildingType.ALCHEMY, keepDisciple = discipleAlive)
            }
        }
    }

    /**
     * 炼制成功判定：真实成功率（RngPartition.SYSTEM 分区 RNG，手动/影子路径同源）。
     * successRate 先钳制到 [0,1]（对抗性审查：存档篡改/旧数据可能越界）。
     * L7（对抗性审查）：NaN 不被 coerceIn 钳制（NaN 比较恒 false）——显式归零，
     * 防损坏存档 successRate=NaN 时每次结算都失败但无明确语义。
     */
    private fun rollProductionSuccess(slot: ProductionSlot): Boolean {
        val rate = if (slot.successRate.isNaN()) 0.0 else slot.successRate.coerceIn(0.0, 1.0)
        return rngManager.getRng(RngPartition.SYSTEM).nextDouble() <= rate
    }

    /**
     * 炼丹产出：品阶 roll + 丹药入库（withTrackingSource 统一入口，来源 "alchemy"）。
     *
     * @return true=产出成功（溢出自动转邮件也算成功）；false=入库失败/配方无效
     *         （B4：产出失败视为炼制失败，不结算晋升）
     */
    private fun producePill(slot: ProductionSlot): Boolean {
        val alchemyRng = rngManager.getRng(RngPartition.SYSTEM)
        val roll = alchemyRng.nextDouble()
        val grade = when {
            roll < PILL_GRADE_HIGH_THRESHOLD -> PillGrade.HIGH
            roll < PILL_GRADE_MEDIUM_THRESHOLD -> PillGrade.MEDIUM
            else -> PillGrade.LOW
        }
        // M3（对抗性审查）：无配方（recipeId null/模板查不到）→ 炼制失败，
        // 与读档路径 producePillWithRecipe 语义一致——旧存档无配方槽位不再
        // "回退 outputItemName 丹药算成功"（三路径行为随机，B4 意图未落地）
        val template = slot.recipeId?.let { it.substringBeforeLast("_") }
            ?.let { baseId -> ItemDatabase.getPillById("${baseId}_${grade.name.lowercase()}") }
            ?: return false
        val pill = ItemDatabase.createPillFromTemplate(template)
        val r = inventorySystem.withTrackingSource("alchemy") {
            inventorySystem.addPill(pill)
        }
        return when (r) {
            is DomainResult.Success -> true
            is DomainResult.Partial -> {
                DomainLog.w(TAG, "丹药 ${pill.name} 溢出 ${r.overflow} 个")
                true
            }
            is DomainResult.Failure -> {
                DomainLog.e(TAG, "丹药 ${pill.name} 入库失败: ${r.error}")
                false
            }
        }
    }

    /**
     * 锻造产出：装备入库（withTrackingSource 统一入口，来源 "forge"）。
     *
     * @return true=产出成功（溢出自动转邮件也算成功）；false=入库失败/配方无效
     *         （B4：产出失败视为炼制失败，不结算晋升）
     */
    private fun produceForgeEquipment(slot: ProductionSlot): Boolean {
        val recipe = slot.recipeId?.let { ForgeRecipeDatabase.getRecipeById(it) } ?: return false
        val equipment = inventorySystem.createEquipmentFromRecipe(recipe)
        val r = inventorySystem.withTrackingSource("forge") {
            inventorySystem.addEquipmentStack(equipment)
        }
        return when (r) {
            is DomainResult.Success -> true
            is DomainResult.Partial -> {
                DomainLog.w(TAG, "装备 ${equipment.name} 溢出 ${r.overflow} 个")
                true
            }
            is DomainResult.Failure -> {
                DomainLog.e(TAG, "装备 ${equipment.name} 入库失败: ${r.error}")
                false
            }
        }
    }

    /**
     * 完成锻造槽结算。
     *
     * @return 弟子是否存在且存活（供槽位重置决定是否保留弟子关联，B3）
     */
    private fun completeForgeSlot(slot: ProductionSlot): Boolean {
        // 锻造真实成功率判定（2026-08-09 职业系统：锻造从 100% 产出改为概率判定，
        // 失败不产出装备、材料不退还，成功才计入职业晋升进度）
        // B4：产出失败（入库失败/配方无效）同样视为炼制失败，不结算晋升
        var success = rollProductionSuccess(slot)
        if (success) {
            success = produceForgeEquipment(slot)
        }
        return slot.assignedDiscipleId?.let { discipleId ->
            stateStore.settleProductionCompletion(slot, discipleId, success, isAlchemy = false)
        } ?: false
    }

    /**
     * 完成炼丹槽结算。
     *
     * @return 弟子是否存在且存活（供槽位重置决定是否保留弟子关联，B3）
     */
    private fun completeAlchemySlot(slot: ProductionSlot): Boolean {
        var success = rollProductionSuccess(slot)
        if (success) {
            success = producePill(slot)
        }
        return slot.assignedDiscipleId?.let { discipleId ->
            stateStore.settleProductionCompletion(slot, discipleId, success, isAlchemy = true)
        } ?: false
    }

    /**
     * 重置槽位为 IDLE（异步 IO）。保留 autoRestart/配方用于续炼。
     *
     * @param keepDisciple true=保留弟子关联（auto-restart 续炼）；false=弟子死亡/
     *                     查无此人 → 清空关联（B3：防槽位被死弟子永久占用）
     */
    private fun resetSlotToIdle(slot: ProductionSlot, buildingId: String,
                                 buildingType: BuildingType, keepDisciple: Boolean) {
        if (!keepDisciple) {
            // L2（对抗性审查）：死弟子场景同步清镜像（gameData.productionSlots），
            // Repository 关联由下方异步重置清空——镜像残留死弟子会让状态推导/
            // 自动排班长期空置该槽位（镜像非真源，但按"双存储同步"惯例一并清理）
            stateStore.update {
                gameData = gameData.copy(
                    productionSlots = gameData.productionSlots.map { s ->
                        if (s.buildingId == buildingId && s.slotIndex == slot.slotIndex) {
                            s.copy(assignedDiscipleId = null, assignedDiscipleName = "")
                        } else s
                    }
                )
            }
        }
        scopeProvider.scope.launch(ioDispatcher.dispatcher) {
            try {
                productionSlotRepository.updateSlotByBuildingId(
                    buildingId, slot.slotIndex
                ) { s ->
                    // B5 守卫（2026-08-09 对抗性审查加固）：仅当缓存槽仍处于
                    // "本次结算的炼制"才重置——其余一律不动：
                    //  - IDLE：玩家已取消/其他入口已重置 → 快照重建会复活旧配置
                    //  - COMPLETED：玩家已手动收获 → 快照重建会覆盖收获结果
                    //  - WORKING 但身份不同：排班已启动新炼制 → 打回 IDLE 造成材料双扣
                    // 不能仅按状态 WORKING 拦截：月变结算后槽位仍为 WORKING
                    // （settle 不改槽位状态），只看状态会把正常结算的槽位也拦死
                    // （槽位永不重置、下月重复结算——回归）。
                    if (!shouldResetSlotForCompletion(s, slot)) {
                        s
                    } else {
                        ProductionSlot.createIdle(
                            id = s.id,
                            slotIndex = slot.slotIndex,
                            buildingType = buildingType,
                            buildingId = buildingId,
                            // 字段取当前缓存 s 而非结算快照：玩家在窗口内翻转的
                            // autoRestart 不被覆盖；身份一致时 recipeId/弟子必然相同
                            autoRestartEnabled = s.autoRestartEnabled,
                            assignedDiscipleId = if (keepDisciple) s.assignedDiscipleId else null,
                            assignedDiscipleName = if (keepDisciple) s.assignedDiscipleName else "",
                            recipeId = s.recipeId
                        )
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                // DAO 写失败（基础设施故障）：内存缓存已置 IDLE（锁内先更新缓存
                // 再 DAO 写），本会话正常；DB 残留 WORKING+到期由读档补结算恢复，
                // 产出语义一致不会双份。记录日志防静默失败（发现 3）。
                DomainLog.e(
                    TAG, "resetSlotToIdle 槽位重置失败: $buildingId[${slot.slotIndex}]", e
                )
            }
        }
    }

    fun processSpiritFieldHarvest(state: MutableGameState) {
        val data = state.gameData
        val currentYear = data.gameYear
        val currentMonth = data.gameMonth
        val plants = data.spiritFieldPlants
        if (plants.isEmpty()) return

        // 全局加成/光环索引/草药仓库/地块列表副本均只构建一次（O(d+b+h+n) 总量，
        // 原实现每块地重复 O(d)+O(b)+O(n)+O(h)，地块多时引擎线程持锁阻塞 UI 导致卡死）
        val context = buildHarvestMaturityContext(data, state.discipleTables)
        val herbStore = buildHarvestHerbStore(state)
        val newPlants = plants.toMutableList()
        var hasChanges = false

        // 防御（对抗性审查发现 2）：循环中途异常时已完成地块的草药仍随事务提交
        // （replaceAll 在循环后统一执行），未处理地块保持成熟待下月再收，
        // 避免"田已清空但草药整轮丢失"的语义退化；CancellationException/Error 照常抛出
        runCatching {
            plants.forEachIndexed { index, plant ->
                if (plant.seedId.isEmpty() || plant.growTime <= 0) return@forEachIndexed
                // 跨宗门地块隔离：sectId 非空且不属于当前宗门的田不收获
                // （正常数据仅损坏/越权可达，防止扣本宗种子续种到异常田——对抗性审查 F3）
                if (plant.sectId.isNotEmpty() && plant.sectId != data.activeSectId) {
                    return@forEachIndexed
                }

                val elapsedMonths = ((currentYear - plant.plantYear) * 12 +
                    (currentMonth - plant.plantMonth)).coerceAtLeast(0)
                val effectiveGrowTime = HerbGardenAuraService.calculateEffectiveGrowTime(
                    plant.growTime, context.bonusFor(plant.buildingInstanceId))

                if (elapsedMonths >= effectiveGrowTime) {
                    val dbHerb = HerbDatabase.getHerbFromSeedName(plant.seedName)
                    if (dbHerb == null) {
                        DomainLog.w(TAG, "processSpiritFieldHarvest: 未找到种子 " +
                            "${plant.seedName} 对应的灵草定义，跳过收获")
                        return@forEachIndexed
                    }
                    addHarvestedHerb(plant, dbHerb, herbStore, state)
                    // 引导系统：累计收获灵植（annualHerbBySource 由 addHarvestedHerb 内部按实际收获量累加）
                    val prevHerbCount = state.gameData.guideCounters[GuideCounterKeys.HERBS_HARVESTED] ?: 0L
                    state.gameData = state.gameData.copy(
                        guideCounters = state.gameData.guideCounters + (GuideCounterKeys.HERBS_HARVESTED to prevHerbCount + 1),
                        annualHerbCount = state.gameData.annualHerbCount + 1
                    )
                    updateSlotAfterHarvest(index, plant, state, currentYear, currentMonth, newPlants)
                    hasChanges = true
                }
            }
        }.onFailure { e ->
            if (e is CancellationException || e is Error) throw e
            DomainLog.w(TAG, "灵田收获中途异常，已完成地块的草药仍入库: ${e.message}", e)
        }

        if (hasChanges) {
            // 整轮只 replaceAll 一次（原每块地一次 O(h) 重建）
            state.herbs.replaceAll(herbStore.all())
            // Bug A 修复：基于循环期间最新 gameData（含 guideCounters/annualHerbCount/
            // annualHerbBySource），原实现用函数开头捕获的旧 data 引用覆盖写回导致统计字段丢失
            state.gameData = state.gameData.copy(spiritFieldPlants = newPlants)
        }
    }

    /**
     * 将收获的灵草合并到整轮共享的草药合并仓库（自动类：PlantingSystem 月度自动触发）。
     *
     * 本方法直接操作 state 参数（区别于其他服务的 stateStore.update 模式——
     * 灵田收获由 PlantingSystem.onMonthlyEvent 传入事务缓冲），
     * 合并逻辑统一走 [StackableItemStore]（与 InventorySystem 主路径同一实现）；
     * 仓库满时溢出部分通过 [InventorySystem.sendOverflowMail] 转为邮件通知玩家
     * （自动类路径物品不丢失），年度报告按实际入库量累加。
     *
     * @param herbStore 整轮收获共享的草药合并仓库（由 [buildHarvestHerbStore] 构建一次，
     *        替代原实现每块地重建 O(h)）
     * @return 实际入库数量
     */
    private fun addHarvestedHerb(
        plant: SpiritFieldPlant,
        dbHerb: HerbDatabase.Herb,
        herbStore: StackableItemStore<Herb>,
        state: MutableGameState
    ): Int {
        val finalYield = plant.expectedYield.coerceAtLeast(1)
        val newHerb = Herb(
            id = java.util.UUID.randomUUID().toString(),
            name = dbHerb.name, rarity = dbHerb.rarity,
            description = dbHerb.description,
            category = dbHerb.category, quantity = finalYield
        )
        val result = herbStore.add(newHerb)
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
     * 构建整轮收获共享的草药合并仓库（原实现每块地重建 O(h)，此处一次 O(h)）。
     *
     * maxSlots 惰性求值保留"种子消耗释放槽位"的动态语义——轮内只有 seeds.size 会变化
     * （续种消耗），其余类型槽位（装备/功法/丹药/材料）与 computeMaxSlots
     * （只依赖 placedBuildings）在轮内固定，故提取为固定值 maxSlotsBase 只计算一次。
     */
    private fun buildHarvestHerbStore(state: MutableGameState): StackableItemStore<Herb> {
        val fixedOtherTypes = state.equipmentStacks.size + state.manualStacks.size +
            state.pills.size + state.materials.size
        val maxSlotsBase = state.computeMaxSlots() - fixedOtherTypes
        return StackableItemStore(
            initialItems = state.herbs.all(),
            stackKeyOf = StackKeys::herb,
            maxStack = inventoryConfig.getMaxStackSize("herb"),
            maxSlots = { maxSlotsBase - state.seeds.size },
            notFound = { AppError.Domain.Inventory.NotFound(it) }
        )
    }

    /**
     * 收获后处理灵田槽位：消耗种子重新种植或清空槽位（下标直写，O(1)）。
     *
     * @param index 与 newPlants 一一对应的下标（收获循环 forEachIndexed 提供，
     *        替代原 indexOfFirst + 每块地 toMutableList 的 O(n²) 复制）
     */
    private fun updateSlotAfterHarvest(
        index: Int,
        plant: SpiritFieldPlant,
        state: MutableGameState,
        currentYear: Int,
        currentMonth: Int,
        newPlants: MutableList<SpiritFieldPlant>
    ) {
        val matchingSeed = HerbDatabase.getSeedByName(plant.seedName)
        // isLocked 排除：全系统"锁定=不可消耗"语义（种植/丢弃/卖出/炼丹均检查），
        // 自动续种不可绕过锁定保护（对抗性审查发现 1）
        val existingSeed = state.seeds.all().find { s ->
            s.name == plant.seedName &&
                s.rarity == (matchingSeed?.rarity ?: 1) &&
                s.growTime == plant.growTime && s.quantity > 0 && !s.isLocked
        }
        val currentAbsoluteMonth = LazyEvaluationDispatcher.toAbsoluteMonth(
            currentYear, currentMonth)
        if (existingSeed != null) {
            val newQty = existingSeed.quantity - 1
            if (newQty <= 0) {
                state.seeds.remove(existingSeed.id)
            } else {
                state.seeds.update(existingSeed.id) { it.copy(quantity = newQty) }
            }
            newPlants[index] = plant.copy(
                // seedId 指向实际消耗的种子堆叠：原实现保留旧 seedId，
                // 其堆叠已被扣尽移除后悬空导致 UI 误显示存量 0（对抗性审查 F2）
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

    /**
     * 灵田收获全局加成上下文：与地块无关的加成只计算一次（性能优化——
     * 原实现每块地重算 O(d)+O(b)，地块多时持锁阻塞 UI 线程）。
     *
     * @param auraByField 建筑 instanceId → 是否处于灵植阁光环内
     *        （null 表示光环值为 0，无需构建索引）
     */
    private class HarvestMaturityContext(
        val elderZone: Double,
        val auraZone: Double,
        val policyZone: Double,
        private val auraByField: Map<String, Boolean>?
    ) {
        /** 单地块总加速倍率（O(1)，光环判定走预构建索引） */
        fun bonusFor(buildingInstanceId: String): Double {
            val aura = if (auraByField?.get(buildingInstanceId) == true) auraZone else 0.0
            return ZoneCalculator.calculate(1.0, elderZone, aura, policyZone) - 1.0
        }
    }

    /** 构建整轮收获的全局加成上下文（无长老且无光环弟子时跳过弟子表组装） */
    private fun buildHarvestMaturityContext(
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
    private fun findCraftableForgeRecipe(
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
    private fun hasMaterials(
        recipe: ForgeRecipeDatabase.ForgeRecipe,
        materialIndex: Map<Pair<String, Int>, Int>
    ): Boolean = recipe.materials.all { (materialId, requiredQuantity) ->
        val materialData = BeastMaterialDatabase.getMaterialById(materialId)
        materialData != null && (materialIndex[materialData.name to materialData.rarity] ?: 0) >= requiredQuantity
    }

    /**
     * D-17 自动重启槽位共用守卫（processAutoAlchemySlot/processAutoForgeSlot 拆分）。
     *
     * 镜像一致性检查：Repository 槽位有弟子但镜像槽位已无此弟子
     * → 视为玩家已释放（历史只清镜像的入口曾产生此分叉），清 Repository 残留并跳过重启，
     *   否则自动重启会把已释放弟子拉回槽位（双槽分叉根因：弟子"被自动任命"回原槽）。
     * 弟子验证：仍存活且空闲（防止自动重启窗口期内弟子被调走）。
     *
     * @param expectedStatus 槽位对应的工作状态（ALCHEMY/FORGE）
     * @return true 可继续自动重启；false 槽位已清除（调用方中断本槽位处理）
     */
    private suspend fun validateAutoSlot(
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

    /** D-17 清 Repository 槽位占用（validateAutoSlot 拆分；镜像不一致场景 Repository 为真源，仅清 repo） */
    private fun clearSlotAssignment(buildingName: String, slotIndex: Int) {
        scopeProvider.scope.launch(ioDispatcher.dispatcher) {
            productionSlotRepository.updateSlotByBuildingId(buildingName, slotIndex) { s ->
                s.copy(assignedDiscipleId = null, assignedDiscipleName = "")
            }
        }
    }

    fun processAutoAssign(state: MutableGameState) {
        val data = state.gameData
        val policies = data.sectPolicies
        // 双槽分叉防线（2026-08-10 互斥化）：全槽位占用弟子排除在自动排班候选之外。
        // - status==IDLE 为第一层（存储权威）
        // - occupiedIds 为第二层：扫描全部工作槽位（长老/生产/灵矿/藏经阁/仓库驻守/
        //   巡视/宗门驻守/战斗队伍/活跃任务/秘境/洞穴/探索队伍/血炼）——防御"分配后
        //   尚未 syncAllDiscipleStatuses"的陈旧状态窗口与推导缺口（如纳徒长老被推导
        //   为 IDLE 后从"可用弟子"可见），杜绝占用弟子被捕获制造双槽位
        val occupiedIds = buildOccupiedSlotDiscipleIds(data, state.teams)
        val idleDisciples = state.discipleTables.assembleAll()
            .filter { d -> d.status == DiscipleStatus.IDLE && d.isAlive && d.id !in occupiedIds }
            .toMutableList()


        val occupiedResidentIds = data.residenceSlots
            .filter { it.discipleId.isNotEmpty() }
            .map { it.discipleId }
            .toSet()
        val allAssignments = computeResidenceAssignments(state, data, policies, occupiedResidentIds)
        idleDisciples.removeAll { it.id in allAssignments.values.map { it.first }.toSet() }

        // ── 生产槽位候选预计算（按优先级逐级筛选，候选从 idleDisciples 移除） ──
        val herbCandidates = takeCandidates(
            idleDisciples, policies.autoPlantFocused, policies.autoPlantRootCounts,
            policies.autoPlantThreshold
        ) { it.spiritPlanting }
        val mineCandidates = takeCandidates(
            idleDisciples, policies.autoMineFocused, policies.autoMineRootCounts,
            policies.autoMineThreshold
        ) { it.mining }
        val alchemyCandidates = takeCandidates(
            idleDisciples, policies.autoAlchemyFocused, policies.autoAlchemyRootCounts,
            policies.autoAlchemyThreshold
        ) { it.pillRefining }
        val forgeCandidates = takeCandidates(
            idleDisciples, policies.autoForgeFocused, policies.autoForgeRootCounts,
            policies.autoForgeThreshold
        ) { it.artifactRefining }

        // ══════════════════════════════════════════════════════════════════
        // 单次原子写入（所有 5 步骤在同一事务内完成，由调用方 stateStore.update 包裹）
        // ══════════════════════════════════════════════════════════════════
        if (allAssignments.isEmpty() && herbCandidates.isEmpty() && mineCandidates.isEmpty()
            && alchemyCandidates.isEmpty() && forgeCandidates.isEmpty()) return

        applyAutoAssignments(
            state, allAssignments, herbCandidates, mineCandidates, alchemyCandidates, forgeCandidates
        )
    }

    /**
     * 自动分配原子写入：住所 + 灵植/灵矿/炼丹/锻造 5 步（预计算候选迭代器分配）。
     * 在调用方 stateStore.update 事务内执行。
     */
    private fun applyAutoAssignments(
        state: MutableGameState,
        allAssignments: Map<String, Pair<String, String>>,
        herbCandidates: List<Disciple>,
        mineCandidates: List<Disciple>,
        alchemyCandidates: List<Disciple>,
        forgeCandidates: List<Disciple>
    ) {
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
        if (updates.isEmpty()) return

        // 镜像先行（与 stateStore 同一事务，保证本帧状态推导一致）
        state.gameData = state.gameData.copy(
            productionSlots = state.gameData.productionSlots.map { slot ->
                val update = updates[slot.slotIndex]
                if (update != null) slot.copy(
                    assignedDiscipleId = update.first,
                    assignedDiscipleName = update.second
                ) else slot
            }
        )

        // Repository 回写（双存储对齐）：UI 读 repo 真源，仅写镜像会导致
        // UI 显示空闲但弟子已被占用（4.00.91 玩家反馈症状）
        scopeProvider.scope.launch(ioDispatcher.dispatcher) {
            for ((slotIndex, assignment) in updates) {
                writeBatchAssignmentToRepo(buildingId, slotIndex, assignment)
            }
        }
    }

    /**
     * S3 repo 回写单个自动排班槽位。
     * transform 内条件覆盖（锁内原子）防止与玩家手动任命竞态；
     * 回写失败或竞态被跳过时回滚镜像，保持双端一致。
     */
    private suspend fun writeBatchAssignmentToRepo(
        buildingId: String,
        slotIndex: Int,
        assignment: Pair<String, String>
    ) {
        val result = productionSlotRepository.updateSlotByBuildingId(buildingId, slotIndex) { s ->
            if (!s.assignedDiscipleId.isNullOrEmpty()) s
            else s.copy(assignedDiscipleId = assignment.first, assignedDiscipleName = assignment.second)
        }
        val written = result.getOrNull()
        if (result.isFailure || written == null || written.assignedDiscipleId != assignment.first) {
            DomainLog.w(
                TAG,
                "batchAssignToProductionSlots: repo 回写被跳过/失败 $buildingId[$slotIndex] " +
                    "disciple=${assignment.first} current=${written?.assignedDiscipleId}",
                result.exceptionOrNull()
            )
            rollbackMirrorBatchAssignment(buildingId, slotIndex, assignment.first)
        }
    }

    /** S3 回滚镜像中自动排班写入的槽位（仅当仍是该弟子，防覆盖玩家后续任命） */
    private fun rollbackMirrorBatchAssignment(buildingId: String, slotIndex: Int, discipleId: String) {
        stateStore.update {
            gameData = gameData.copy(
                productionSlots = gameData.productionSlots.map { s ->
                    if (s.buildingId == buildingId && s.slotIndex == slotIndex &&
                        s.assignedDiscipleId == discipleId
                    ) s.copy(assignedDiscipleId = null, assignedDiscipleName = "")
                    else s
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
            val slotIdx = slots.indexOfFirst {
                it.buildingType == BuildingType.ALCHEMY && it.slotIndex == slotIndex
            }
            if (slotIdx < 0) continue

            // 职业门禁：按槽位弟子炼丹师职业等级限制可炼品阶（无职业只能炼凡品）
            val worker = slots[slotIdx].assignedDiscipleId
                ?.let { id -> state.discipleTables.assembleAll().find { it.id == id } }
            val maxTier = worker?.let { ProfessionRules.maxCraftableTier(it.skills.alchemyLevel) }
                ?: 1
            val recipeToStart = findRecipe(currentHerbs, maxTier) ?: break

            // 消耗材料
            consumeHerbsForRecipeLocal(recipeToStart.materials, currentHerbs, state)
            val absoluteMonth = gd.gameYear * 12 + gd.gameMonth

            val assignedId = slots[slotIdx].assignedDiscipleId
            val assignedName = slots[slotIdx].assignedDiscipleName
            // 公式化成功率（属性+职业合成基础率 × 乘区），不再用配方 successRate
            val effectiveSuccessRate = formulaService.buildSuccessRateZones(
                disciple = worker,
                buildingId = BuildingNames.ALCHEMY,
                recipeTier = recipeToStart.tier,
                policyBonus = policyBonus
            ).calculate()
            slots[slotIdx] = slots[slotIdx].copy(
                status = ProductionSlotStatus.WORKING,
                recipeId = recipeToStart.id,
                recipeName = recipeToStart.name,
                startYear = gd.gameYear,
                startMonth = gd.gameMonth,
                duration = recipeToStart.duration,
                baseDuration = recipeToStart.duration,
                successRate = effectiveSuccessRate,
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
            val slotIdx = slots.indexOfFirst {
                it.buildingType == BuildingType.FORGE && it.slotIndex == slotIndex
            }
            if (slotIdx < 0) continue

            // 职业门禁：按槽位弟子炼器师职业等级限制可锻品阶（无职业只能锻凡品）
            val worker = slots[slotIdx].assignedDiscipleId
                ?.let { id -> state.discipleTables.assembleAll().find { it.id == id } }
            val maxTier = worker?.let { ProfessionRules.maxCraftableTier(it.skills.forgeLevel) }
                ?: 1
            val recipeToStart = findForgeRecipe(allRecipes, materialIndex, maxTier) ?: break

            consumeMaterialsForRecipeLocal(recipeToStart.materials, state)
            val absoluteMonth = gd.gameYear * 12 + gd.gameMonth
            val duration = ForgeRecipeDatabase.getDurationByTier(recipeToStart.tier)

            // 公式化成功率（属性+职业合成基础率 × 乘区），不再用配方 successRate
            val effectiveSuccessRate = formulaService.buildSuccessRateZones(
                disciple = worker,
                buildingId = BuildingNames.FORGE,
                recipeTier = recipeToStart.tier,
                policyBonus = policyBonus
            ).calculate()
            slots[slotIdx] = slots[slotIdx].copy(
                status = ProductionSlotStatus.WORKING,
                recipeId = recipeToStart.id,
                recipeName = recipeToStart.name,
                startYear = gd.gameYear,
                startMonth = gd.gameMonth,
                duration = duration,
                successRate = effectiveSuccessRate,
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
            if (!isCompleteForgeSlot(slot, year, month)) continue

            // 锻造真实成功率判定（与手动路径 completeForgeSlot 一致）
            val success = rollProductionSuccess(slot)
            if (success) {
                produceForgeEquipmentShadow(slot, state)
            }
            // 职业晋升进度（影子版直接操作 state.discipleTables）
            // B3：弟子死亡/查无此人 → 槽位清空弟子关联（防死弟子占用槽位）
            val discipleAlive = slot.assignedDiscipleId?.let { discipleId ->
                settleForgeCompletionShadow(state, slot, discipleId, success)
            } ?: false
            slots[i] = ProductionSlot.createIdle(
                id = slot.id, slotIndex = slot.slotIndex,
                buildingType = BuildingType.FORGE,
                buildingId = slot.buildingId,
                autoRestartEnabled = slot.autoRestartEnabled,
                assignedDiscipleId = if (discipleAlive) slot.assignedDiscipleId else null,
                assignedDiscipleName = if (discipleAlive) slot.assignedDiscipleName ?: "" else "",
                recipeId = slot.recipeId
            )
        }
    }

    /** 完成判定：锻造槽 + WORKING + 到期待结算 */
    private fun isCompleteForgeSlot(slot: ProductionSlot, year: Int, month: Int): Boolean =
        slot.buildingType == BuildingType.FORGE &&
            slot.status == ProductionSlotStatus.WORKING &&
            isSlotCompleteDynamic(slot, year, month)

    /** 影子版锻造产出（直接入 state.equipmentStacks，与手动版 withTrackingSource 路径分开） */
    private fun produceForgeEquipmentShadow(slot: ProductionSlot, state: MutableGameState) {
        val recipeId = slot.recipeId ?: return
        val recipe = ForgeRecipeDatabase.getRecipeById(recipeId) ?: return
        state.equipmentStacks.add(InventoryFactories.createEquipmentFromRecipe(recipe))
    }

    /**
     * 影子版锻造结算：弟子回 IDLE + 职业晋升（MutableGameState 直接操作）。
     *
     * @return 弟子是否存在且存活（false → 槽位应清空弟子关联，B3）
     */
    private fun settleForgeCompletionShadow(
        state: MutableGameState,
        slot: ProductionSlot,
        discipleId: String,
        success: Boolean
    ): Boolean {
        // 配方无效（数据损坏）时 recipeTier=0：低阶不充数规则下不结算晋升（对抗性审查 A2）
        val recipeTier = slot.recipeId?.let { ForgeRecipeDatabase.getRecipeById(it)?.tier } ?: 0
        val currentList = state.discipleTables.assembleAll()
        var discipleAlive = false
        val updated = currentList.map {
            if (it.id == discipleId && it.isAlive) {
                discipleAlive = true
                state.settleDiscipleProduction(it, recipeTier, success, isAlchemy = false)
            } else it
        }
        state.discipleTables.replaceAll(updated)
        return discipleAlive
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
            val success = alchemyRng.nextDouble() <= slot.successRate.coerceIn(0.0, 1.0)
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
            // 职业晋升进度（影子版直接操作 state.discipleTables，与锻造影子路径共用
            // settleDiscipleProduction——统一"弟子回 IDLE + 晋升"语义）
            // B3：弟子死亡/查无此人 → 槽位清空弟子关联（防死弟子占用槽位）
            val discipleAlive = slot.assignedDiscipleId?.let { discipleId ->
                settleAlchemyCompletionShadow(state, slot, discipleId, success)
            } ?: false
            slots[i] = ProductionSlot.createIdle(
                id = slot.id, slotIndex = slot.slotIndex,
                buildingType = BuildingType.ALCHEMY,
                buildingId = slot.buildingId,
                autoRestartEnabled = slot.autoRestartEnabled,
                assignedDiscipleId = if (discipleAlive) slot.assignedDiscipleId else null,
                assignedDiscipleName = if (discipleAlive) slot.assignedDiscipleName ?: "" else "",
                recipeId = slot.recipeId
            )
        }
    }

    /**
     * 影子版炼丹结算：弟子回 IDLE + 职业晋升（MutableGameState 直接操作，
     * 与锻造影子路径共用 settleDiscipleProduction——统一"弟子回 IDLE + 晋升"语义）。
     *
     * @return 弟子是否存在且存活（false → 槽位应清空弟子关联，B3）
     */
    private fun settleAlchemyCompletionShadow(
        state: MutableGameState,
        slot: ProductionSlot,
        discipleId: String,
        success: Boolean
    ): Boolean {
        val recipeTier = slot.recipeId?.let { PillRecipeDatabase.getRecipeById(it)?.tier } ?: 0
        val currentList = state.discipleTables.assembleAll()
        var discipleAlive = false
        val updated = currentList.map {
            if (it.id == discipleId && it.isAlive) {
                discipleAlive = true
                state.settleDiscipleProduction(it, recipeTier, success, isAlchemy = true)
            } else it
        }
        state.discipleTables.replaceAll(updated)
        return discipleAlive
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
        herbs: List<Herb>,
        maxTier: Int = 1
    ): PillRecipeDatabase.PillRecipe? {
        return PillRecipeDatabase.findBestCraftableRecipe(herbs, maxTier)
    }

    private fun findForgeRecipe(
        recipes: List<ForgeRecipeDatabase.ForgeRecipe>,
        materialIndex: Map<Pair<String, Int>, Int>,
        maxTier: Int = 1
    ): ForgeRecipeDatabase.ForgeRecipe? {
        return recipes.firstOrNull { recipe ->
            recipe.tier <= maxTier && recipe.materials.all { (materialId, requiredQty) ->
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

    /**
     * 按当前政策/长老/槽位弟子状态重算槽位的 successRate。
     *
     * 职业系统重构后（2026-08-09）：配方 successRate 不再参与（恒 0），
     * 基础率由工作弟子属性 + 职业等级合成，走乘区法
     * （buildSuccessRateZones.calculate()）。
     */
    private fun recalculateSuccessRate(data: GameData, slot: ProductionSlot): Double {
        val recipeTier = when (slot.buildingType) {
            BuildingType.ALCHEMY ->
                PillRecipeDatabase.getRecipeById(slot.recipeId ?: "")?.tier
            BuildingType.FORGE ->
                ForgeRecipeDatabase.getRecipeById(slot.recipeId ?: "")?.tier
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
        val disciple = slot.assignedDiscipleId
            ?.let { id -> stateStore.disciples.value.find { it.id == id } }
        return formulaService.buildSuccessRateZones(
            disciple = disciple,
            buildingId = slot.buildingId,
            recipeTier = recipeTier,
            policyBonus = policyBonus
        ).calculate()
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
            // 已分配单人住所的弟子不再进入多人候选（避免同弟子占位导致多人槽位空置）
            val singleAssignedIds = singleAssignments.values.map { it.first }.toSet()
            val multiCandidates = allCandidates.filter { d ->
                val matchesFilter = (policies.autoMultiResidenceFocused && isDiscipleFollowed(d)) ||
                    d.spiritRoot.types.size in policies.autoMultiResidenceRootCounts
                d.id !in singleAssignedIds && matchesFilter && d.comprehension >= policies.autoMultiResidenceThreshold
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
     * 生产槽位候选提取：预排序候选弟子并从池中移除（按优先级逐级筛选）。
     * 政策未启用时返回空列表。
     */
    private fun takeCandidates(
        pool: MutableList<Disciple>,
        focused: Boolean,
        rootCounts: List<Int>,
        threshold: Int,
        attr: (Disciple) -> Int
    ): List<Disciple> {
        if (!focused && rootCounts.isEmpty()) return emptyList()
        val sorted = precomputeCandidates(pool, focused, rootCounts, threshold, attr)
        sorted.forEach { pool.remove(it) }
        return sorted
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

/**
 * B5 重置守卫（2026-08-09 对抗性审查提取为顶层 internal 供测试调用真身）：
 * 结算时刻的异步槽位重置，仅当缓存槽仍处于"本次结算的炼制"才允许重置。
 *
 * 身份判别 = 状态 WORKING + completionMonth/recipeId 与结算快照一致。
 * 其余（IDLE 已取消/收获、COMPLETED 已手动收集、WORKING 但身份不同的新炼制）
 * 一律不动——防结算快照重建覆盖窗口内的玩家操作或排班启动的新炼制。
 *
 * 注意：completionMonth/recipeId 属可被存档篡改的字段（对抗性审查 M2 边界）——
 * 篡改使身份判别失效时，最坏情况是"重置被跳过 + 槽位保持 WORKING"，下月由
 * isSlotCompleteDynamic 重算再次结算。该字段对结算判定本身无影响（结算判定
 * 完全动态重算），守卫只承担防乱序覆盖职责，不承担防篡改职责。
 */
internal fun shouldResetSlotForCompletion(
    current: ProductionSlot,
    settled: ProductionSlot
): Boolean = current.status == ProductionSlotStatus.WORKING &&
    current.completionMonth == settled.completionMonth &&
    current.recipeId == settled.recipeId

/**
 * 全槽位占用弟子 ID 收集（2026-08-10 月度自动排班互斥化）。
 *
 * 扫描全部工作槽位：长老全槽位（含纳徒长老 recruitingElder）、生产镜像槽、
 * 灵矿/藏经阁/仓库驻守/巡视/玩家宗门驻守、战斗队伍、活跃任务、远古秘境
 * 探索成员（secretRealmState.exists 时）、洞穴探索队伍（仅活跃状态）、
 * 玩家探索队伍（仅活跃状态，与 [DiscipleStatusService.buildInTeamIds]
 * 同状态条件）、血炼进度。
 *
 * 调用方 [ProductionProcessor.processAutoAssign] 以 status==IDLE 为第一层
 * 过滤（存储权威），本函数为第二层防御——覆盖同一事务内"分配后尚未
 * syncAllDiscipleStatuses"的陈旧状态窗口与推导缺口（如纳徒长老被推导为
 * IDLE），杜绝占用弟子被当作空闲捕获制造双槽位。
 *
 * @param data 当前游戏数据（含全部槽位字段）
 * @param teams 玩家探索队伍（仅活跃状态参与，与状态推导判定一致）
 */
internal fun buildOccupiedSlotDiscipleIds(data: GameData, teams: List<ExplorationTeam>): Set<String> = buildSet {
    addAll(collectElderSlotDiscipleIds(data.elderSlots))
    data.spiritMineSlots.filter { it.discipleId.isNotEmpty() }.forEach { add(it.discipleId) }
    data.librarySlots.filter { it.discipleId.isNotEmpty() }.forEach { add(it.discipleId) }
    data.warehouseGarrisons.filter { it.discipleId.isNotEmpty() }.forEach { add(it.discipleId) }
    data.patrolSlots.filter { it.discipleId.isNotEmpty() }.forEach { add(it.discipleId) }
    data.worldMapSects.find { it.isPlayerSect }?.garrisonSlots
        ?.filter { it.discipleId.isNotEmpty() }?.forEach { add(it.discipleId) }
    data.battleTeams.flatMap { it.slots }
        .filter { it.discipleId.isNotEmpty() }.forEach { add(it.discipleId) }
    data.activeMissions.forEach { addAll(it.discipleIds) }
    if (data.secretRealmState.exists) {
        data.secretRealmSession.members.filter { !it.isDead }.forEach { add(it.discipleId) }
    }
    data.caveExplorationTeams.filter { it.status in DiscipleStatusService.caveExplorationStatuses }
        .forEach { addAll(it.memberIds) }
    teams.filter { it.status in DiscipleStatusService.explorationStatuses }
        .forEach { addAll(it.memberIds) }
    data.activeBloodRefinements.values.filter { it.discipleId.isNotEmpty() }.forEach { add(it.discipleId) }
    data.productionSlots
        .mapNotNull { it.assignedDiscipleId?.takeIf { id -> id.isNotEmpty() } }
        .forEach { add(it) }
}

/**
 * 长老槽位全部占用弟子 ID 收集（10 个单槽字段 + 7 个亲传弟子列表字段）。
 * 显式清单 + 守卫测试（ElderSlotsStatusCoverageTest 反射双向校验）保证新增
 * 字段不遗漏——遗漏会导致该槽位弟子被自动排班当作空闲调动（双槽位根因）。
 */
internal fun collectElderSlotDiscipleIds(elderSlots: ElderSlots): Set<String> = buildSet {
    listOf(
        elderSlots.viceSectMaster, elderSlots.herbGardenElder, elderSlots.alchemyElder,
        elderSlots.forgeElder, elderSlots.outerElder, elderSlots.preachingElder,
        elderSlots.lawEnforcementElder, elderSlots.innerElder,
        elderSlots.qingyunPreachingElder, elderSlots.recruitingElder
    ).filter { it.isNotEmpty() }.forEach { add(it) }
    listOf(
        elderSlots.preachingMasters, elderSlots.lawEnforcementDisciples,
        elderSlots.qingyunPreachingMasters, elderSlots.herbGardenDisciples,
        elderSlots.alchemyDisciples, elderSlots.forgeDisciples,
        elderSlots.spiritMineDeaconDisciples
    ).flatten().filter { it.discipleId.isNotEmpty() }.forEach { add(it.discipleId) }
}
