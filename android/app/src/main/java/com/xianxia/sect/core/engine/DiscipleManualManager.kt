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
        maxStack: Int = MAX_MANUAL_STACK,
        instantMessage: Boolean = false
    ): ManualLearnResult {
        val events = mutableListOf<String>()
        var updatedDisciple = disciple
        var lastNewInstance: ManualInstance? = null
        var lastReplacedInstance: ManualInstance? = null
        var lastStackUpdate: StackUpdate? = null
        var lastReplacedManualStack: ManualStack? = null

        val bagStackRefs = disciple.storageBagItems
            .filter { it.itemType == "manual_stack" && !StorageBagUtils.isInCoolingPeriod(it, gameYear, gameMonth) }

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
        val currentTypes = currentManualIds.mapNotNull { manualInstances[it]?.type }.toSet()

        for (stack in availableStacks.sortedByDescending { it.rarity }) {
            if (stack.type == ManualType.MIND && currentTypes.contains(ManualType.MIND)) {
                val existingMindId = currentManualIds.find { manualInstances[it]?.type == ManualType.MIND }
                if (existingMindId != null) {
                    val replaceResult = tryReplaceManual(
                        disciple = updatedDisciple,
                        stack = stack,
                        existingInstanceId = existingMindId,
                        manualInstances = manualInstances,
                        manualStacks = manualStacks,
                        gameYear = gameYear,
                        gameMonth = gameMonth,
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
                continue
            }

            if (!currentTypes.contains(stack.type)) {
                val learnResult = learnNewManual(
                    disciple = updatedDisciple,
                    stack = stack,
                    manualInstances = manualInstances,
                    gameYear = gameYear,
                    gameMonth = gameMonth,
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
                val existingId = currentManualIds.find { manualInstances[it]?.type == stack.type }
                if (existingId != null) {
                    val existingRarity = manualInstances[existingId]?.rarity ?: 0
                    if (stack.rarity > existingRarity) {
                        val replaceResult = tryReplaceManual(
                            disciple = updatedDisciple,
                            stack = stack,
                            existingInstanceId = existingId,
                            manualInstances = manualInstances,
                            manualStacks = manualStacks,
                            gameYear = gameYear,
                            gameMonth = gameMonth,
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
            storageBagItems = StorageBagUtils.decreaseItemQuantity(updatedDisciple.storageBagItems, stack.id)
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
        maxStack: Int,
        instantMessage: Boolean
    ): ManualLearnResult {
        val events = mutableListOf<String>()
        var updatedDisciple = disciple

        val existingInstance = manualInstances[existingInstanceId]
            ?: return ManualLearnResult(disciple, null, null, null, null, emptyList())

        val oldStack = existingInstance.toStack(quantity = 1)

        val bagStackIds = disciple.storageBagItems
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
            forgetMonth = gameMonth
        )
        updatedDisciple = updatedDisciple.copyWith(
            storageBagItems = StorageBagUtils.increaseItemQuantity(updatedDisciple.storageBagItems, storageItem, maxStack)
                .map { bagItem ->
                    if (bagItem.itemId == storageItemId && bagItem.itemType == "manual_stack") {
                        bagItem.copy(forgetYear = gameYear, forgetMonth = gameMonth)
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
            storageBagItems = StorageBagUtils.decreaseItemQuantity(updatedDisciple.storageBagItems, stack.id)
        )

        val messagePrefix = if (instantMessage) "立即" else "自动"
        events.add("${disciple.name} ${messagePrefix}替换功法：${existingInstance.name} -> ${stack.name}")

        return ManualLearnResult(updatedDisciple, newInstance, existingInstance, stackUpdate, replacedManualStack, events)
    }

    fun canLearn(disciple: Disciple, stack: ManualStack): Boolean {
        return disciple.realm <= stack.minRealm
    }

    fun canLearn(disciple: Disciple, instance: ManualInstance): Boolean {
        return disciple.realm <= instance.minRealm
    }
}
