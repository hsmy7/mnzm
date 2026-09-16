package com.xianxia.sect.core.engine

import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.engine.domain.disciple.DiscipleStatCalculator
import com.xianxia.sect.core.model.ManualStack
import com.xianxia.sect.core.model.ManualType
import com.xianxia.sect.core.state.MutableGameState
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.DiscipleAggregate
import com.xianxia.sect.core.model.RewardSelectedItem
import com.xianxia.sect.core.util.DomainResult
import com.xianxia.sect.core.engine.domain.disciple.getAllDiscipleAggregates
import com.xianxia.sect.core.engine.domain.disciple.getDiscipleAggregate
import com.xianxia.sect.core.engine.domain.disciple.getMaxManualSlots


suspend fun GameEngine.equipEquipment(discipleId: String,
    equipmentId: String): DomainResult<Unit> = discipleFacade.equipEquipment(discipleId, equipmentId)
suspend fun GameEngine.unequipEquipment(discipleId: String,
    equipmentId: String): DomainResult<Unit> = discipleFacade.unequipEquipment(discipleId, equipmentId)
fun GameEngine.getDiscipleAggregate(discipleId: String): DiscipleAggregate? = discipleFacade
    .getDiscipleAggregate(discipleId)
fun GameEngine.getAllDiscipleAggregates(): List<DiscipleAggregate> = discipleFacade.getAllDiscipleAggregates()
fun GameEngine.giveItemToDisciple(discipleId: String, itemId: String,
    itemType: String) = discipleFacade.giveItemToDisciple(discipleId, itemId, itemType)
suspend fun GameEngine.rewardItemsToDisciple(discipleId: String,
    items: List<RewardSelectedItem>) = discipleFacade.rewardItemsToDisciple(discipleId, items)
/** 功法学习资格守卫：境界 / 名额 / 心法唯一 / 同名唯一，校验序与原早退链一致 */
internal fun MutableGameState.canLearnManualFromStack(disciple: Disciple, stack: ManualStack): Boolean {
    if (!GameConfig.Realm.meetsRealmRequirement(disciple.realm, stack.minRealm)) return false
    val maxSlots = DiscipleStatCalculator.getMaxManualSlots(disciple)
    if (disciple.manualIds.size >= maxSlots) return false
    if (stack.type == ManualType.MIND &&
        disciple.manualIds.any { mid -> manualInstances.get(mid)?.type == ManualType.MIND }
    ) return false
    if (disciple.manualIds.any { mid -> manualInstances.get(mid)?.name == stack.name }) return false
    return true
}

/** 功法堆叠消耗：最后一本整摞移除，否则数量减一 */
internal fun MutableGameState.consumeManualStackForLearn(stackId: String, stack: ManualStack) {
    val newQty = stack.quantity - 1
    if (newQty <= 0) manualStacks.remove(stackId)
    else manualStacks.update(stackId) { it.copy(quantity = newQty) }
}

/** 学习成果写回组件表：功法挂载 + HP/MP 增益，以重装行方式落表 */
internal fun MutableGameState.applyLearnedManualSnapshot(
    id: Int,
    disciple: Disciple,
    stack: ManualStack,
    instanceId: String
) {
    val hpDelta = stack.stats["hp"] ?: stack.stats["maxHp"] ?: 0
    val mpDelta = stack.stats["mp"] ?: stack.stats["maxMp"] ?: 0
    val rawHp = disciple.combat.currentHp; val rawMp = disciple.combat.currentMp
    val updatedDisciple = disciple.copy(manualIds = disciple.manualIds + instanceId,
        combat = disciple.combat.copy(currentHp = if (rawHp >= 0 && hpDelta > 0) rawHp + hpDelta else rawHp,
            currentMp = if (rawMp >= 0 && mpDelta > 0) rawMp + mpDelta else rawMp))
    discipleTables.remove(id)
    discipleTables.insert(updatedDisciple)
}
