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
        gameDay: Int,
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
            .filter { it.itemType == "manual_stack" && !StorageBagUtils.isInCoolingPeriod(it, gameYear, gameMonth, gameDay) }

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

        for (stack in availableStacks.sortedByDescending { it.rarity }) {
            if (stack.type == ManualType.MIND) {
                val existingMindId = currentManualIds.find { manualInstances[it]?.type == ManualType.MIND }
                if (existingMindId != null) {
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
                            gameDay = gameDay,
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
                    if (currentManualIds.size < maxSlots) {
                        val learnResult = learnNewManual(
                            disciple = updatedDisciple,
                            stack = stack,
                            manualInstances = manualInstances,
                            gameYear = gameYear,
                            gameMonth = gameMonth,
                            gameDay = gameDay,
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
                val learnResult = learnNewManual(
                    disciple = updatedDisciple,
                    stack = stack,
                    manualInstances = manualInstances,
                    gameYear = gameYear,
                    gameMonth = gameMonth,
                    gameDay = gameDay,
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
                            gameDay = gameDay,
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
        gameDay: Int,
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

        updatedDisciple = updatedDisciple.copyWith(
            manualIds = updatedDisciple.manualIds + instanceId,
            storageBagItems = StorageBagUtils.decreaseItemQuantity(updatedDisciple.equipment.storageBagItems, stack.id)
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
        gameDay: Int,
        maxStack: Int,
        instantMessage: Boolean
    ): ManualLearnResult {
        val events = mutableListOf<String>()
        var updatedDisciple = disciple

        val existingInstance = manualInstances[existingInstanceId]
            ?: return ManualLearnResult(disciple, null, null, null, null, emptyList())

        val oldStack = existingInstance.toStack(quantity = 1)

        val bagStackIds = disciple.equipment.storageBagItems
            .filter { it.itemType == "manual_stack" }
            .map { it.itemId }
            .toSet()

        val existingBagStack = manualStacks.find {
            it.name == oldStack.name && it.rarity == oldStack.rarity && it.type == oldStack.type && it.id in bagStackIds
        }

        val replacedManualStack: ManualStack
        val storageItemId: String

        if (existingBagStack != null) {
            val mergedQty = (existingBagStack.quantity + 1).coerceAtMost(maxStack)
            replacedManualStack = existingBagStack.copy(quantity = mergedQty)
            storageItemId = existingBagStack.id
        } else {
            replacedManualStack = oldStack
            storageItemId = oldStack.id
        }

        val storageItem = StorageBagItem(
            itemId = storageItemId,
            itemType = "manual_stack",
            name = existingInstance.name,
            rarity = existingInstance.rarity,
            quantity = 1,
            obtainedYear = gameYear,
            obtainedMonth = gameMonth,
            forgetYear = gameYear,
            forgetMonth = gameMonth,
            forgetDay = gameDay
        )
        updatedDisciple = updatedDisciple.copyWith(
            storageBagItems = StorageBagUtils.increaseItemQuantity(updatedDisciple.equipment.storageBagItems, storageItem, maxStack)
                .map { bagItem ->
                    if (bagItem.itemId == storageItemId && bagItem.itemType == "manual_stack") {
                        bagItem.copy(forgetYear = gameYear, forgetMonth = gameMonth, forgetDay = gameDay)
                    } else bagItem
                }
        )

        val instanceId = java.util.UUID.randomUUID().toString()
        val newInstance = stack.toInstance(id = instanceId, ownerId = disciple.id, isLearned = true)

        val newQty = stack.quantity - 1
        val stackUpdate = if (newQty <= 0) {
            StackUpdate(stackId = stack.id, newQuantity = 0, isDeletion = true)
        } else {
            StackUpdate(stackId = stack.id, newQuantity = newQty, isDeletion = false)
        }

        updatedDisciple = updatedDisciple.copyWith(
            manualIds = updatedDisciple.manualIds.map { if (it == existingInstanceId) instanceId else it },
            storageBagItems = StorageBagUtils.decreaseItemQuantity(updatedDisciple.equipment.storageBagItems, stack.id)
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
        return true
    }

    fun canLearn(disciple: Disciple, instance: ManualInstance, manualInstances: Map<String, ManualInstance>): Boolean {
        if (disciple.realm > instance.minRealm) return false
        val maxSlots = DiscipleStatCalculator.getMaxManualSlots(disciple)
        if (disciple.manualIds.size >= maxSlots) return false
        if (instance.type == ManualType.MIND && disciple.manualIds.any { mid -> manualInstances[mid]?.type == ManualType.MIND }) return false
        return true
    }
}
