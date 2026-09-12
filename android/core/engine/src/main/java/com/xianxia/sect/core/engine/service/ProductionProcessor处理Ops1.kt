package com.xianxia.sect.core.engine.service

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import com.xianxia.sect.core.model.Herb
import com.xianxia.sect.core.model.PillGrade
import com.xianxia.sect.core.model.Seed
import com.xianxia.sect.core.model.SpiritFieldPlant
import com.xianxia.sect.core.model.guide.GuideCounterKeys
import com.xianxia.sect.core.model.production.ProductionSlot
import com.xianxia.sect.core.state.MutableGameState
import com.xianxia.sect.core.state.StackableItemStore
import com.xianxia.sect.core.registry.ForgeRecipeDatabase
import com.xianxia.sect.core.registry.ItemDatabase
import com.xianxia.sect.core.engine.system.InventorySystem
import com.xianxia.sect.core.engine.domain.building.HerbGardenAuraService
import com.xianxia.sect.core.registry.HerbDatabase
import com.xianxia.sect.core.util.BuildingNames
import com.xianxia.sect.core.util.DomainLog
import com.xianxia.sect.core.util.DomainResult
import com.xianxia.sect.core.util.RngPartition
import com.xianxia.sect.core.model.production.BuildingType
import com.xianxia.sect.core.repository.getSlotsByBuildingId
import com.xianxia.sect.core.repository.getSlotsByType

/**
 * 单用户定向补偿邮件（MailService 扩展，独立文件）。
 *
 * 拆分原因：MailService 类主体接近 detekt LargeClass（800 行）阈值，
 * 补偿邮件属独立运营配置，放独立文件保持 MailService 规模稳定；
 * stateStore/mailRepo 已放宽为 internal 供本扩展读取（三重防护）。
 */
// ── ProductionProcessor 拆分域 1/5（行为零变更） ──

private val TAG = ProductionProcessor.TAG
/** 丹药品阶 roll 阈值（与月变路径 ProductionProcessor 保持一致） */
private val PILL_GRADE_HIGH_THRESHOLD = ProductionProcessor.PILL_GRADE_HIGH_THRESHOLD
private val PILL_GRADE_MEDIUM_THRESHOLD = ProductionProcessor.PILL_GRADE_MEDIUM_THRESHOLD
/** 灵田收获附带种子数量上限：0..HARVEST_SEED_MAX_GAIN 各 20%（均匀分布，nextInt(n+1)） */
private val HARVEST_SEED_MAX_GAIN = ProductionProcessor.HARVEST_SEED_MAX_GAIN
fun ProductionProcessor.processBuildingProduction(year: Int, month: Int) {
    processForgeCompletion(year, month)
    processAlchemyCompletion(year, month)
}

internal fun ProductionProcessor.processForgeCompletion(year: Int, month: Int) {
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

internal fun ProductionProcessor.processAlchemyCompletion(year: Int, month: Int) {
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
 * successRate 先钳制到 [0,1]（存档篡改/旧数据可能越界）。
 * NaN 不被 coerceIn 钳制（NaN 比较恒 false）——显式归零，
 * 防损坏存档 successRate=NaN 时每次结算都失败但无明确语义。
 */

internal fun ProductionProcessor.rollProductionSuccess(slot: ProductionSlot): Boolean {
    val rate = if (slot.successRate.isNaN()) 0.0 else slot.successRate.coerceIn(0.0, 1.0)
    return rngManager.getRng(RngPartition.SYSTEM).nextDouble() <= rate
}

/**
 * 炼丹产出：品阶 roll + 丹药入库（withTrackingSource 统一入口，来源 "alchemy"）。
 *
 * @return true=产出成功（溢出自动转邮件也算成功）；false=入库失败/配方无效
 *         （B4：产出失败视为炼制失败，不结算晋升）
 */

internal fun ProductionProcessor.producePill(slot: ProductionSlot): Boolean {
    val alchemyRng = rngManager.getRng(RngPartition.SYSTEM)
    val roll = alchemyRng.nextDouble()
    val grade = when {
        roll < PILL_GRADE_HIGH_THRESHOLD -> PillGrade.HIGH
        roll < PILL_GRADE_MEDIUM_THRESHOLD -> PillGrade.MEDIUM
        else -> PillGrade.LOW
    }
    // 无配方（recipeId null/模板查不到）→ 炼制失败，
    // 与读档路径 producePillWithRecipe 语义一致（不回退 outputItemName 丹药）
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

internal fun ProductionProcessor.produceForgeEquipment(slot: ProductionSlot): Boolean {
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

internal fun ProductionProcessor.completeForgeSlot(slot: ProductionSlot): Boolean {
    // 锻造真实成功率判定：概率判定，
    // 失败不产出装备、材料不退还，成功才计入职业晋升进度）
    // 产出失败（入库失败/配方无效）同样视为炼制失败，不结算晋升
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

internal fun ProductionProcessor.completeAlchemySlot(slot: ProductionSlot): Boolean {
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

internal fun ProductionProcessor.resetSlotToIdle(slot: ProductionSlot, buildingId: String,
                             buildingType: BuildingType, keepDisciple: Boolean) {
    if (!keepDisciple) {
        // 死弟子场景同步清镜像（gameData.productionSlots），
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
                // 重置守卫：仅当缓存槽仍处于
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

fun ProductionProcessor.processSpiritFieldHarvest(state: MutableGameState) {
    val data = state.gameData
    val currentYear = data.gameYear
    val currentMonth = data.gameMonth
    val plants = data.spiritFieldPlants
    if (plants.isEmpty()) return

    // 全局加成/光环索引/灵草+种子仓库/地块列表副本均只构建一次（O(d+b+h+n) 总量；
    // 每块地重复构建会在地块多时持锁阻塞 UI 导致卡死）
    val context = buildHarvestMaturityContext(data, state.discipleTables)
    val stores = buildHarvestStores(state)
    val newPlants = plants.toMutableList()
    var hasChanges = false

    // 防御：循环中途异常时已完成地块的草药/种子仍随事务提交
    // （replaceAll 在循环后统一执行），未处理地块保持成熟待下月再收，
    // 避免"田已清空但草药整轮丢失"的语义退化；CancellationException/Error 照常抛出
    runCatching {
        plants.forEachIndexed { index, plant ->
            if (plant.seedId.isEmpty() || plant.growTime <= 0) return@forEachIndexed
            // 跨宗门地块隔离：sectId 非空且不属于当前宗门的田不收获
            // （正常数据仅损坏/越权可达，防止扣本宗种子续种到异常田）
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
                addHarvestedHerb(plant, dbHerb, stores.herbs, state)
                addHarvestedSeed(plant, stores.seeds)
                // 引导系统：累计收获灵植（annualHerbBySource 由 addHarvestedHerb 内部按实际收获量累加）
                val prevHerbCount = state.gameData.guideCounters[GuideCounterKeys.HERBS_HARVESTED] ?: 0L
                state.gameData = state.gameData.copy(
                    guideCounters = state.gameData.guideCounters + (GuideCounterKeys
                        .HERBS_HARVESTED to prevHerbCount + 1),
                    annualHerbCount = state.gameData.annualHerbCount + 1
                )
                updateSlotAfterHarvest(
                    index, plant, currentYear, currentMonth, newPlants, stores.seeds
                )
                hasChanges = true
            }
        }
    }.onFailure { e ->
        if (e is CancellationException || e is Error) throw e
        DomainLog.w(TAG, "灵田收获中途异常，已完成地块的草药仍入库: ${e.message}", e)
    }

    if (hasChanges) {
        // 整轮只 replaceAll 一次（原每块地一次 O(h) 重建）
        state.herbs.replaceAll(stores.herbs.all())
        state.seeds.replaceAll(stores.seeds.all())
        // 基于循环期间最新 gameData（含 guideCounters/annualHerbCount/
        // annualHerbBySource）——用循环前捕获的旧 data 引用覆盖写回会导致统计字段丢失
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
 * @param herbStore 整轮收获共享的草药合并仓库（由 [buildHarvestStores] 构建一次，
 *        整轮共享）
 * @return 实际入库数量
 */

internal fun ProductionProcessor.addHarvestedHerb(
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
                "spirit_field", "herb", dbHerb.name, dbHerb.rarity, result.overflow,
                itemId = dbHerb.id
            )
            finalYield - result.overflow
        }
        is DomainResult.Failure -> {
            inventorySystem.sendOverflowMail(
                "spirit_field", "herb", dbHerb.name, dbHerb.rarity, finalYield,
                itemId = dbHerb.id
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
 * 收获成熟灵植时一并获得同种种子（数量 0~4，各 20% 均匀分布——[HARVEST_SEED_MAX_GAIN]）。
 *
 * 种子直接合并进整轮共享的种子仓库（[StackableItemStore]，与 InventorySystem 主路径同一实现）；
 * 仓库容量不足时溢出部分通过 [InventorySystem.sendOverflowMail] 转为邮件通知玩家
 * （自动类路径物品不丢失）。溢出部分不进入 seedStore——续种只消耗"宗门仓库内"的种子
 * （见 [updateSlotAfterHarvest]），进邮件的种子不可被同一轮续种消耗。
 */

internal fun ProductionProcessor.addHarvestedSeed(
    plant: SpiritFieldPlant,
    seedStore: StackableItemStore<Seed>
) {
    val roll = rngManager.getRng(RngPartition.SYSTEM).nextInt(HARVEST_SEED_MAX_GAIN + 1)
    if (roll <= 0) return
    val template = HerbDatabase.getSeedByName(plant.seedName)
    if (template == null) {
        DomainLog.w(TAG, "processSpiritFieldHarvest: 未找到种子模板 " +
            "${plant.seedName}，跳过种子奖励")
        return
    }
    val newSeed = Seed(
        id = java.util.UUID.randomUUID().toString(),
        name = template.name,
        rarity = template.rarity,
        description = template.description,
        growTime = template.growTime,
        yield = template.yield,
        quantity = roll
    )
    when (val result = seedStore.add(newSeed)) {
        is DomainResult.Success -> Unit
        is DomainResult.Partial -> {
            inventorySystem.sendOverflowMail(
                "spirit_field", "seed", template.name, template.rarity, result.overflow,
                itemId = template.id
            )
            DomainLog.w(
                TAG, "灵田收获 ${template.name} 仓库空间不足，" +
                    "实际入库 ${roll - result.overflow}/$roll（溢出 ${result.overflow} 已转邮件）"
            )
        }
        is DomainResult.Failure -> {
            inventorySystem.sendOverflowMail(
                "spirit_field", "seed", template.name, template.rarity, roll,
                itemId = template.id
            )
            DomainLog.w(
                TAG, "灵田收获 ${template.name} 仓库空间不足，$roll 颗种子全部转邮件"
            )
        }
    }
}

/**
 * 构建整轮收获共享的灵草/种子合并仓库（整轮一次构建 O(h)）。
 *
 * 灵草与种子共用同一仓库槽位预算（[computeMaxSlots]），两 store 的 maxSlots 惰性求值并
 * 互相引用对方实时堆叠数（lateinit 前置声明解决 Kotlin 局部变量前向引用）：轮内草药/种子
 * 堆叠数的增减（含续种消耗、收获种子入仓）即时反映到对方的槽位上限，与
 * InventorySystem.addXxx 的"otherTypes 实时统计"语义一致。
 */
