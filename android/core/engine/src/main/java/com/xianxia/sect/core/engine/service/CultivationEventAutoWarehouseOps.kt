package com.xianxia.sect.core.engine.service

import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.EquipmentInstance
import com.xianxia.sect.core.model.EquipmentStack
import com.xianxia.sect.core.model.ManualInstance
import com.xianxia.sect.core.model.ManualStack
import com.xianxia.sect.core.model.accessoryId
import com.xianxia.sect.core.model.armorId
import com.xianxia.sect.core.model.bootsId
import com.xianxia.sect.core.model.secretRealmMemberIds
import com.xianxia.sect.core.model.storageBagItems
import com.xianxia.sect.core.model.weaponId
import com.xianxia.sect.core.state.DiscipleTables
import com.xianxia.sect.core.state.MutableGameState

/**
 * 实时轨专用：自动从仓库装备/学习。
 * 仅由 [PhaseSettlementExecutor.execute]（Kotlin 对拍/回归基准路径；生产 AUTHORITATIVE
 * 每旬走 C++ `runPhaseSettlementCore`）调用。
 */
// ── 自动入库域（自 CultivationEventProcessor 拆出，行为零变更） ───────────────
fun CultivationEventProcessor.processAutoFromWarehouseRealtime(state: MutableGameState) {
    val d = state.gameData
    processAutoFromWarehouse(d.gameYear, d.gameMonth, state)
}

internal fun CultivationEventProcessor.processAutoFromWarehouse(
    year: Int, month: Int, state: MutableGameState
) {
    val gameData = state.gameData
    val equipFocused = gameData.autoEquipFromWarehouseFocused
    val equipRootCounts = gameData.autoEquipFromWarehouseRootCounts
    val learnFocused = gameData.autoLearnFromWarehouseFocused
    val learnRootCounts = gameData.autoLearnFromWarehouseRootCounts
    val hasAutoEquip = equipFocused || equipRootCounts.isNotEmpty()
    val hasAutoLearn = learnFocused || learnRootCounts.isNotEmpty()
    if (!hasAutoEquip && !hasAutoLearn) return
    val tables = state.discipleTables

    // 列级直读资格预判后只 assemble 合格弟子（性能优化意图保留）
    // 注意：候选物品来自宗门仓库而非弟子背包——按背包内容过滤会误删全部
    // 空背包弟子（860bd2a4 引入的回归，天枢殿自动学习/装备因此失效）。
    // 远古秘境：探索中弟子不自动装备/学习（状态冻结语义，与修炼/服药跳过一致）
    val secretRealmMemberIds = gameData.secretRealmMemberIds()
    val updatedDisciples = tables.ids.filter { tables.isAlive[it] == 1 }
        .filter { id -> id !in secretRealmMemberIds }
        .filter { id ->
            qualifiesForSectAutoById(
                id, tables,
                equipFocused, equipRootCounts,
                learnFocused, learnRootCounts
            )
        }
        .mapNotNull { tables.assemble(it)?.takeIf { d -> d.isAlive } }
        .toMutableList()
    var eqStacks = state.equipmentStacks.all()
    var mnStacks = state.manualStacks.all()
    val eqInstancesById = state.equipmentInstances.associateById()
    val mnInstancesById = state.manualInstances.associateById()
    val newEqInstances = mutableListOf<EquipmentInstance>()
    val newMnInstances = mutableListOf<ManualInstance>()
    // 袋内实例装配 / 被替换旧实例（已回袋，需从实例表移除）
    val attachedEqInstances = mutableListOf<EquipmentInstance>()
    val replacedEqInstances = mutableListOf<EquipmentInstance>()
    val attachedMnInstances = mutableListOf<ManualInstance>()
    val replacedMnInstances = mutableListOf<ManualInstance>()
    val sortedIndices = updatedDisciples.indices
        .sortedWith(compareByDescending<Int> { updatedDisciples[it].statusData["followed"] == "true" }
            .thenBy { updatedDisciples[it].realm }
            .thenByDescending { updatedDisciples[it].realmLayer })
    for (idx in sortedIndices) {
        val step = processSingleDiscipleAuto(
            updatedDisciples[idx], year, month, tables,
            equipFocused, equipRootCounts, learnFocused, learnRootCounts,
            eqStacks, mnStacks, eqInstancesById, mnInstancesById,
            newEqInstances, attachedEqInstances, replacedEqInstances,
            newMnInstances, attachedMnInstances, replacedMnInstances
        )
        if (step.disciple !== updatedDisciples[idx]) {
            updatedDisciples[idx] = step.disciple
        }
        eqStacks = step.eqStacks
        mnStacks = step.mnStacks
    }
    writeAutoWarehouseResults(
        state, tables, updatedDisciples, eqStacks, mnStacks,
        newEqInstances, newMnInstances,
        attachedEqInstances, replacedEqInstances,
        attachedMnInstances, replacedMnInstances
    )
}

/** 单个弟子的自动装备 + 自动学习（先装备后学习，对齐 Kotlin 编排） */

@Suppress("LongParameterList") // 类级同名单注解留在源类（27 服务 DI 注入口径），随拆分迁至函数级
internal fun CultivationEventProcessor.processSingleDiscipleAuto(
    disciple: Disciple,
    year: Int, month: Int,
    tables: DiscipleTables,
    equipFocused: Boolean, equipRootCounts: Set<Int>,
    learnFocused: Boolean, learnRootCounts: Set<Int>,
    eqStacks: List<EquipmentStack>, mnStacks: List<ManualStack>,
    eqInstancesById: Map<String, EquipmentInstance>,
    mnInstancesById: Map<String, ManualInstance>,
    newEqInstances: MutableList<EquipmentInstance>,
    attachedEqInstances: MutableList<EquipmentInstance>,
    replacedEqInstances: MutableList<EquipmentInstance>,
    newMnInstances: MutableList<ManualInstance>,
    attachedMnInstances: MutableList<ManualInstance>,
    replacedMnInstances: MutableList<ManualInstance>
): AutoWarehouseResult {
    var d = disciple
    var eqs = eqStacks
    var mns = mnStacks
    if (qualifiesForSectAutoPublic(d, equipFocused, equipRootCounts)) {
        val result = processSingleAutoEquip(
            d, year, month, tables, eqs, eqInstancesById,
            newEqInstances, attachedEqInstances, replacedEqInstances
        )
        d = result.first
        eqs = result.second
    }
    if (qualifiesForSectAutoPublic(d, learnFocused, learnRootCounts)) {
        val result = processSingleAutoLearn(
            d, year, month, tables, mns, mnInstancesById,
            newMnInstances, attachedMnInstances, replacedMnInstances
        )
        d = result.first
        mns = result.second
    }
    return AutoWarehouseResult(d, eqs, mns)
}

/**
 * 列级直读资格预判（不 assemble）。语义与 [qualifiesForSectAutoPublic] 完全一致：
 * equip/learn 分别判定、或语义；focused 分支优先于灵根数分支。
 * 只依赖两列：statusData["followed"]、spiritRootTypes 灵根数（缺列兜底 "metal"，与 assemble 一致）。
 */

internal fun CultivationEventProcessor.qualifiesForSectAutoById(
    id: Int, tables: DiscipleTables,
    equipFocused: Boolean, equipRootCounts: Set<Int>,
    learnFocused: Boolean, learnRootCounts: Set<Int>
): Boolean {
    val equipOk = (equipFocused || equipRootCounts.isNotEmpty()) &&
        qualifiesByColumns(id, tables, equipFocused, equipRootCounts)
    val learnOk = (learnFocused || learnRootCounts.isNotEmpty()) &&
        qualifiesByColumns(id, tables, learnFocused, learnRootCounts)
    return equipOk || learnOk
}

/**
 * 单侧资格列级判定，与 [qualifiesForSectAutoPublic] 逐分支对齐：
 * focused 分支优先于 rootCounts 分支；focused 判定失败时回退灵根数判定
 * （开关全关时 rootCounts 为空集，in 判定恒为 false，等价于原早退分支）。
 */

internal fun CultivationEventProcessor.qualifiesByColumns(
    id: Int, tables: DiscipleTables, focused: Boolean, rootCounts: Set<Int>
): Boolean {
    if (focused && tables.statusData.getOrNull(id)?.get("followed") == "true") return true
    val rootCount = (tables.spiritRootTypes.getOrNull(id) ?: "metal").split(",").size
    return rootCount in rootCounts
}

/**
 * 处理单个弟子的自动装备：调用 equipmentManager 后更新堆叠状态并记录日志。
 * @return (更新后的弟子, 更新后的装备堆叠列表)
 */

@Suppress("LongParameterList") // 类级同名单注解留在源类（27 服务 DI 注入口径），随拆分迁至函数级
internal fun CultivationEventProcessor.processSingleAutoEquip(
    d: Disciple, year: Int, month: Int, tables: DiscipleTables,
    eqStacks: List<EquipmentStack>, eqInstancesById: Map<String, EquipmentInstance>,
    newEqInstances: MutableList<EquipmentInstance>,
    attachedEqInstances: MutableList<EquipmentInstance>,
    replacedEqInstances: MutableList<EquipmentInstance>
): Pair<Disciple, List<EquipmentStack>> {
    val result = equipmentManager.processAutoEquipFromWarehouse(
        disciple = d, warehouseStacks = eqStacks, equipmentInstances = eqInstancesById,
        gameYear = year, gameMonth = month
    )
    if (result.newInstances.isEmpty() && result.attachedInstances.isEmpty() &&
        result.replacedInstances.isEmpty()
    ) {
        return d to eqStacks
    }
    var stacks = eqStacks
    newEqInstances.addAll(result.newInstances)
    // B：袋内实例装配（重建入表）与被替换旧实例（已回袋，从实例表移除）
    attachedEqInstances.addAll(result.attachedInstances)
    replacedEqInstances.addAll(result.replacedInstances)
    val equipName = (result.newInstances.firstOrNull() ?: result.attachedInstances.firstOrNull())?.name ?: ""
    if (equipName.isNotEmpty()) {
        discipleService.addLifeEvent(d.id, "${tables.ages[d.id.toInt()]}岁：自动装备了${equipName}")
    }
    for (update in result.stackUpdates) {
        stacks = if (update.isDeletion) stacks.filter { it.id != update.stackId }
        else stacks.map { if (it.id == update.stackId) it.copy(quantity = update.newQuantity) else it }
    }
    return result.disciple to stacks
}

/**
 * 处理单个弟子的自动学习功法：调用 manualManager 后更新堆叠状态并记录日志。
 * @return (更新后的弟子, 更新后的功法堆叠列表)
 */

@Suppress("LongParameterList") // 类级同名单注解留在源类（27 服务 DI 注入口径），随拆分迁至函数级
internal fun CultivationEventProcessor.processSingleAutoLearn(
    d: Disciple, year: Int, month: Int, tables: DiscipleTables,
    mnStacks: List<ManualStack>, mnInstancesById: Map<String, ManualInstance>,
    newMnInstances: MutableList<ManualInstance>,
    attachedMnInstances: MutableList<ManualInstance>,
    replacedMnInstances: MutableList<ManualInstance>
): Pair<Disciple, List<ManualStack>> {
    val result = manualManager.processAutoLearnFromWarehouse(
        disciple = d, warehouseStacks = mnStacks, manualInstances = mnInstancesById,
        gameYear = year, gameMonth = month
    )
    if (result.newInstance == null && result.attachedInstance == null &&
        result.replacedInstance == null
    ) {
        return d to mnStacks
    }
    var stacks = mnStacks
    result.newInstance?.let { newMnInstances.add(it) }
    // B：袋内实例装配（重建入表）与被替换旧实例（已回袋，从实例表移除）
    result.attachedInstance?.let { attachedMnInstances.add(it) }
    result.replacedInstance?.let { replacedMnInstances.add(it) }
    val manualName = (result.newInstance ?: result.attachedInstance)?.name ?: ""
    if (manualName.isNotEmpty()) {
        discipleService.addLifeEvent(d.id, "${tables.ages[d.id.toInt()]}岁：自动学习了${manualName}")
    }
    result.stackUpdate?.let { update ->
        stacks = if (update.isDeletion) stacks.filter { it.id != update.stackId }
        else stacks.map { if (it.id == update.stackId) it.copy(quantity = update.newQuantity) else it }
    }
    return result.disciple to stacks
}

/**
 * 精准字段写回：仅写回自动装备/学习实际修改的字段，不执行全量 clear()+insert()。
 */

@Suppress("LongParameterList") // 类级同名单注解留在源类（27 服务 DI 注入口径），随拆分迁至函数级
internal fun CultivationEventProcessor.writeAutoWarehouseResults(
    state: MutableGameState, tables: DiscipleTables,
    updatedDisciples: List<Disciple>,
    eqStacks: List<EquipmentStack>, mnStacks: List<ManualStack>,
    newEqInstances: List<EquipmentInstance>, newMnInstances: List<ManualInstance>,
    attachedEqInstances: List<EquipmentInstance>, replacedEqInstances: List<EquipmentInstance>,
    attachedMnInstances: List<ManualInstance>, replacedMnInstances: List<ManualInstance>
) {
    for (disciple in updatedDisciples) {
        val id = disciple.id.toInt()
        tables.storageBagItems[id] = disciple.equipment.storageBagItems
        tables.weaponIds[id] = disciple.equipment.weaponId
        tables.armorIds[id] = disciple.equipment.armorId
        tables.bootsIds[id] = disciple.equipment.bootsId
        tables.accessoryIds[id] = disciple.equipment.accessoryId

        // 清理被替换功法的残留熟练度
        val oldManualIds = tables.manualIds.getOrDefault(id, emptyList())
        tables.manualIds[id] = disciple.manualIds
        clearRemovedManualProficiencies(state, disciple, oldManualIds.toSet() - disciple.manualIds.toSet())
    }
    state.equipmentStacks.setItems(eqStacks)
    state.manualStacks.setItems(mnStacks)
    newEqInstances.forEach { state.equipmentInstances.add(it) }
    newMnInstances.forEach { state.manualInstances.add(it) }
    // 袋内实例装配——可能不在实例表（防双持有），
    // 在表内则更新保序、不在则新增
    attachedEqInstances.forEach { attached ->
        if (state.equipmentInstances.contains(attached.id)) {
            state.equipmentInstances.update(attached.id) { attached }
        } else {
            state.equipmentInstances.add(attached)
        }
    }
    attachedMnInstances.forEach { attached ->
        if (state.manualInstances.contains(attached.id)) {
            state.manualInstances.update(attached.id) { attached }
        } else {
            state.manualInstances.add(attached)
        }
    }
    // B：被替换的旧实例已回储物袋，从实例表移除（防双持有）
    replacedEqInstances.forEach { state.equipmentInstances.remove(it.id) }
    replacedMnInstances.forEach { state.manualInstances.remove(it.id) }
}

/**
 * 清理被替换功法的残留熟练度——
 * removedIds 为空零成本早退（不构建 profMap、不 copy state）。
 */
