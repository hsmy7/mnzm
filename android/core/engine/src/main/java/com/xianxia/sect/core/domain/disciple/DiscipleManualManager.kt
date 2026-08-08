package com.xianxia.sect.core.engine.domain.disciple

import com.xianxia.sect.core.engine.system.StackUpdate
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.ManualInstance
import com.xianxia.sect.core.model.ManualStack
import com.xianxia.sect.core.model.ManualType
import com.xianxia.sect.core.model.baseMagicAttack
import com.xianxia.sect.core.model.basePhysicalAttack
import com.xianxia.sect.core.model.currentHp
import com.xianxia.sect.core.model.currentMp
import javax.inject.Inject
import javax.inject.Singleton






@Singleton
class DiscipleManualManager @Inject constructor() {

    companion object {
        private const val MAX_MANUAL_STACK = 999
    }

    data class ManualLearnResult(
        val disciple: Disciple,
        val newInstance: ManualInstance?,
        val replacedInstance: ManualInstance?,
        val stackUpdate: StackUpdate?,
        val replacedManualStack: ManualStack?,
        val events: List<String>
    )

    fun canLearn(disciple: Disciple, stack: ManualStack, manualInstances: Map<String, ManualInstance>): Boolean {
        if (disciple.realm > stack.minRealm) return false
        val maxSlots = DiscipleStatCalculator.getMaxManualSlots(disciple)
        if (disciple.manualIds.size >= maxSlots) return false
        if (stack.type == ManualType.MIND && disciple.manualIds.any { mid -> manualInstances[mid]?.type == ManualType.MIND }) return false
        if (disciple.manualIds.any { mid -> manualInstances[mid]?.name == stack.name }) return false
        return true
    }

    fun processAutoLearnFromWarehouse(
        disciple: Disciple,
        warehouseStacks: List<ManualStack>,
        manualInstances: Map<String, ManualInstance>,
        gameYear: Int,
        gameMonth: Int,
        gamePhase: Int,
        maxStack: Int = MAX_MANUAL_STACK
    ): ManualLearnResult {
        val maxSlots = DiscipleStatCalculator.getMaxManualSlots(disciple)
        if (disciple.manualIds.size >= maxSlots) {
            return ManualLearnResult(disciple, null, null, null, null, emptyList())
        }

        val learnedNames = disciple.manualIds.mapNotNull { manualInstances[it]?.name }.toSet()
        val hasMindManual = disciple.manualIds.any { mid -> manualInstances[mid]?.type == ManualType.MIND }

        val candidates = warehouseStacks.filter { stack ->
            disciple.realm <= stack.minRealm &&
            !stack.isLocked &&
            stack.name !in learnedNames &&
            !(hasMindManual && stack.type == ManualType.MIND)
        }

        val prefersPhysical = disciple.basePhysicalAttack >= disciple.baseMagicAttack
        val bestStack = candidates.maxWithOrNull(
            compareBy<ManualStack> { stack ->
                if (prefersPhysical && stack.skillDamageType == "physical") 1
                else if (!prefersPhysical && stack.skillDamageType == "magic") 1
                else 0
            }.thenBy { it.rarity }
        ) ?: return ManualLearnResult(disciple, null, null, null, null, emptyList())

        val instanceId = java.util.UUID.randomUUID().toString()
        val newInstance = bestStack.toInstance(id = instanceId, ownerId = disciple.id, isLearned = true)

        val newQty = bestStack.quantity - 1
        val stackUpdate = if (newQty <= 0) {
            StackUpdate(stackId = bestStack.id, newQuantity = 0, isDeletion = true)
        } else {
            StackUpdate(stackId = bestStack.id, newQuantity = newQty, isDeletion = false)
        }

        val hpDelta = bestStack.stats["hp"] ?: bestStack.stats["maxHp"] ?: 0
        val mpDelta = bestStack.stats["mp"] ?: bestStack.stats["maxMp"] ?: 0
        val rawCurrentHp = disciple.combat.currentHp
        val rawCurrentMp = disciple.combat.currentMp
        val newHp = if (rawCurrentHp >= 0 && hpDelta > 0) rawCurrentHp + hpDelta else rawCurrentHp
        val newMp = if (rawCurrentMp >= 0 && mpDelta > 0) rawCurrentMp + mpDelta else rawCurrentMp

        val updatedDisciple = disciple.copy(
            manualIds = disciple.manualIds + instanceId,
            combat = disciple.combat.copy(
                currentHp = newHp,
                currentMp = newMp
            )
        )

        return ManualLearnResult(
            disciple = updatedDisciple,
            newInstance = newInstance,
            replacedInstance = null,
            stackUpdate = stackUpdate,
            replacedManualStack = null,
            events = listOf("${disciple.name} 自动学习了 ${bestStack.name}")
        )
    }

    fun canLearn(disciple: Disciple, instance: ManualInstance, manualInstances: Map<String, ManualInstance>): Boolean {
        if (disciple.realm > instance.minRealm) return false
        val maxSlots = DiscipleStatCalculator.getMaxManualSlots(disciple)
        if (disciple.manualIds.size >= maxSlots) return false
        if (instance.type == ManualType.MIND && disciple.manualIds.any { mid -> manualInstances[mid]?.type == ManualType.MIND }) return false
        if (disciple.manualIds.any { mid -> manualInstances[mid]?.name == instance.name }) return false
        return true
    }
}
