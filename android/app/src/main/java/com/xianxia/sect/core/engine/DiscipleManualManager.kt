package com.xianxia.sect.core.engine

import com.xianxia.sect.core.engine.system.StackUpdate
import com.xianxia.sect.core.model.*
import com.xianxia.sect.core.util.StorageBagUtils

object DiscipleManualManager {

    private const val MAX_MANUAL_STACK = 999

    data class ManualLearnResult(
        val disciple: Disciple,
        val newInstance: ManualInstance?,
        val replacedInstance: ManualInstance?,
        val stackUpdate: StackUpdate?,
        val replacedManualStack: ManualStack?,
        val events: List<String>
    )

    fun processAutoLearn(
        disciple: Disciple,
        manualStacks: List<ManualStack>,
        manualInstances: Map<String, ManualInstance>,
        gameYear: Int,
        gameMonth: Int,
        gamePhase: Int,
        maxStack: Int = MAX_MANUAL_STACK,
        instantMessage: Boolean = false
    ): ManualLearnResult {
        val events = mutableListOf<String>()
        var updatedDisciple = disciple
        var lastNewInstance: ManualInstance? = null
        var lastReplacedInstance: ManualInstance? = null
        var lastStackUpdate: StackUpdate? = null
        var lastReplacedManualStack: ManualStack? = null

        val bagStackRefs = disciple.equipment.storageBagItems
            .filter { it.itemType == "manual_stack" && !StorageBagUtils.isInCoolingPeriod(it, gameYear, gameMonth, gamePhase) }

        if (bagStackRefs.isEmpty()) {
            return ManualLearnResult(disciple, null, null, null, null, emptyList())
        }

        val availableStacks = bagStackRefs.mapNotNull { ref ->
            manualStacks.find { it.id == ref.itemId }
        }.filter { disciple.realm <= it.minRealm }

        if (availableStacks.isEmpty()) {
            return ManualLearnResult(disciple, null, null, null, null, emptyList())
        }

        val currentManualIds = disciple.manualIds
        val maxSlots = DiscipleStatCalculator.getMaxManualSlots(disciple)
        val learnedNames = currentManualIds.mapNotNull { manualInstances[it]?.name }.toSet()

        for (stack in availableStacks.sortedByDescending { it.rarity }) {
            if (stack.type == ManualType.MIND) {
                val existingMindId = currentManualIds.find { manualInstances[it]?.type == ManualType.MIND }
                if (existingMindId != null) {
                    val otherNames = currentManualIds
                        .filter { it != existingMindId }
                        .mapNotNull { manualInstances[it]?.name }
                        .toSet()
                    if (stack.name in otherNames) continue
                    val existingRarity = manualInstances[existingMindId]?.rarity ?: 0
                    if (stack.rarity > existingRarity) {
                        val replaceResult = tryReplaceManual(
                            disciple = updatedDisciple,
                            stack = stack,
                            existingInstanceId = existingMindId,
                            manualInstances = manualInstances,
                            manualStacks = manualStacks,
                            gameYear = gameYear,
                            gameMonth = gameMonth,
                            gamePhase = gamePhase,
                            maxStack = maxStack,
                            instantMessage = instantMessage
                        )
                        if (replaceResult.newInstance != null) {
                            updatedDisciple = replaceResult.disciple
                            lastNewInstance = replaceResult.newInstance
                            lastReplacedInstance = replaceResult.replacedInstance
                            lastStackUpdate = replaceResult.stackUpdate
                            lastReplacedManualStack = replaceResult.replacedManualStack
                            events.addAll(replaceResult.events)
                            break
                        }
                    }
                } else {
                    if (currentManualIds.size < maxSlots && stack.name !in learnedNames) {
                        val learnResult = learnNewManual(
                            disciple = updatedDisciple,
                            stack = stack,
                            manualInstances = manualInstances,
                            gameYear = gameYear,
                            gameMonth = gameMonth,
                            gamePhase = gamePhase,
                            instantMessage = instantMessage
                        )
                        if (learnResult.newInstance != null) {
                            updatedDisciple = learnResult.disciple
                            lastNewInstance = learnResult.newInstance
                            lastReplacedInstance = learnResult.replacedInstance
                            lastStackUpdate = learnResult.stackUpdate
                            events.addAll(learnResult.events)
                            break
                        }
                    }
                }
                continue
            }

            if (currentManualIds.size < maxSlots) {
                if (stack.name in learnedNames) continue
                val learnResult = learnNewManual(
                    disciple = updatedDisciple,
                    stack = stack,
                    manualInstances = manualInstances,
                    gameYear = gameYear,
                    gameMonth = gameMonth,
                    gamePhase = gamePhase,
                    instantMessage = instantMessage
                )
                if (learnResult.newInstance != null) {
                    updatedDisciple = learnResult.disciple
                    lastNewInstance = learnResult.newInstance
                    lastReplacedInstance = learnResult.replacedInstance
                    lastStackUpdate = learnResult.stackUpdate
                    events.addAll(learnResult.events)
                    break
                }
            } else {
                val lowestRarityId = currentManualIds.minByOrNull { manualInstances[it]?.rarity ?: 0 }
                if (lowestRarityId != null) {
                    val otherNames = currentManualIds
                        .filter { it != lowestRarityId }
                        .mapNotNull { manualInstances[it]?.name }
                        .toSet()
                    if (stack.name in otherNames) continue
                    val existingRarity = manualInstances[lowestRarityId]?.rarity ?: 0
                    if (stack.rarity > existingRarity) {
                        val replaceResult = tryReplaceManual(
                            disciple = updatedDisciple,
                            stack = stack,
                            existingInstanceId = lowestRarityId,
                            manualInstances = manualInstances,
                            manualStacks = manualStacks,
                            gameYear = gameYear,
                            gameMonth = gameMonth,
                            gamePhase = gamePhase,
                            maxStack = maxStack,
                            instantMessage = instantMessage
                        )
                        if (replaceResult.newInstance != null) {
                            updatedDisciple = replaceResult.disciple
                            lastNewInstance = replaceResult.newInstance
                            lastReplacedInstance = replaceResult.replacedInstance
                            lastStackUpdate = replaceResult.stackUpdate
                            lastReplacedManualStack = replaceResult.replacedManualStack
                            events.addAll(replaceResult.events)
                            break
                        }
                    }
                }
            }
        }

        return ManualLearnResult(updatedDisciple, lastNewInstance, lastReplacedInstance, lastStackUpdate, lastReplacedManualStack, events)
    }

    private fun learnNewManual(
        disciple: Disciple,
        stack: ManualStack,
        manualInstances: Map<String, ManualInstance>,
        gameYear: Int,
        gameMonth: Int,
        gamePhase: Int,
        instantMessage: Boolean
    ): ManualLearnResult {
        val events = mutableListOf<String>()
        var updatedDisciple = disciple

        val instanceId = java.util.UUID.randomUUID().toString()
        val newInstance = stack.toInstance(id = instanceId, ownerId = disciple.id, isLearned = true)

        val newQty = stack.quantity - 1
        val stackUpdate = if (newQty <= 0) {
            StackUpdate(stackId = stack.id, newQuantity = 0, isDeletion = true)
        } else {
            StackUpdate(stackId = stack.id, newQuantity = newQty, isDeletion = false)
        }

        val hpDelta = stack.stats["hp"] ?: stack.stats["maxHp"] ?: 0
        val mpDelta = stack.stats["mp"] ?: stack.stats["maxMp"] ?: 0
        val rawCurrentHp = updatedDisciple.combat.currentHp
        val rawCurrentMp = updatedDisciple.combat.currentMp
        val newHp = if (rawCurrentHp >= 0 && hpDelta > 0) rawCurrentHp + hpDelta else rawCurrentHp
        val newMp = if (rawCurrentMp >= 0 && mpDelta > 0) rawCurrentMp + mpDelta else rawCurrentMp

        updatedDisciple = updatedDisciple.copyWith(
            manualIds = updatedDisciple.manualIds + instanceId,
            storageBagItems = StorageBagUtils.decreaseItemQuantity(updatedDisciple.equipment.storageBagItems, stack.id),
            currentHp = newHp,
            currentMp = newMp
        )

        val messagePrefix = if (instantMessage) "立即" else "自动"
        events.add("${disciple.name} ${messagePrefix}学习了 ${stack.name}")

        return ManualLearnResult(updatedDisciple, newInstance, null, stackUpdate, null, events)
    }

    private fun tryReplaceManual(
        disciple: Disciple,
        stack: ManualStack,
        existingInstanceId: String,
        manualInstances: Map<String, ManualInstance>,
        manualStacks: List<ManualStack>,
        gameYear: Int,
        gameMonth: Int,
        gamePhase: Int,
        maxStack: Int,
        instantMessage: Boolean
    ): ManualLearnResult {
        val events = mutableListOf<String>()
        var updatedDisciple = disciple

        val existingInstance = manualInstances[existingInstanceId]
            ?: return ManualLearnResult(disciple, null, null, null, null, emptyList())

        val oldStack = existingInstance.toStack(quantity = 1)

        val existingWarehouseStack = manualStacks.find {
            it.name == oldStack.name && it.rarity == oldStack.rarity && it.type == oldStack.type
        }

        val replacedManualStack: ManualStack = if (existingWarehouseStack != null) {
            val mergedQty = (existingWarehouseStack.quantity + 1).coerceAtMost(maxStack)
            existingWarehouseStack.copy(quantity = mergedQty)
        } else {
            oldStack
        }

        val instanceId = java.util.UUID.randomUUID().toString()
        val newInstance = stack.toInstance(id = instanceId, ownerId = disciple.id, isLearned = true)

        val newQty = stack.quantity - 1
        val stackUpdate = if (newQty <= 0) {
            StackUpdate(stackId = stack.id, newQuantity = 0, isDeletion = true)
        } else {
            StackUpdate(stackId = stack.id, newQuantity = newQty, isDeletion = false)
        }

        val oldHpDelta = existingInstance.stats["hp"] ?: existingInstance.stats["maxHp"] ?: 0
        val oldMpDelta = existingInstance.stats["mp"] ?: existingInstance.stats["maxMp"] ?: 0
        val newHpDelta = stack.stats["hp"] ?: stack.stats["maxHp"] ?: 0
        val newMpDelta = stack.stats["mp"] ?: stack.stats["maxMp"] ?: 0
        val netHpDelta = newHpDelta - oldHpDelta
        val netMpDelta = newMpDelta - oldMpDelta
        val rawCurrentHp = updatedDisciple.combat.currentHp
        val rawCurrentMp = updatedDisciple.combat.currentMp
        val newHp = if (rawCurrentHp >= 0 && netHpDelta != 0) (rawCurrentHp + netHpDelta).coerceAtLeast(0) else rawCurrentHp
        val newMp = if (rawCurrentMp >= 0 && netMpDelta != 0) (rawCurrentMp + netMpDelta).coerceAtLeast(0) else rawCurrentMp

        updatedDisciple = updatedDisciple.copyWith(
            manualIds = updatedDisciple.manualIds.map { if (it == existingInstanceId) instanceId else it },
            storageBagItems = StorageBagUtils.decreaseItemQuantity(updatedDisciple.equipment.storageBagItems, stack.id),
            currentHp = newHp,
            currentMp = newMp
        )

        val messagePrefix = if (instantMessage) "立即" else "自动"
        events.add("${disciple.name} ${messagePrefix}替换功法：${existingInstance.name} -> ${stack.name}")

        return ManualLearnResult(updatedDisciple, newInstance, existingInstance, stackUpdate, replacedManualStack, events)
    }

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

        // 比较攻击力偏好：物攻高优先物理功法，法攻高优先魔法功法
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

        val updatedDisciple = disciple.copyWith(
            manualIds = disciple.manualIds + instanceId,
            currentHp = newHp,
            currentMp = newMp
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
