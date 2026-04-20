@file:Suppress("DEPRECATION")

package com.xianxia.sect.core.engine

import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.engine.DiscipleStatCalculator
import com.xianxia.sect.core.model.*

object DiscipleManualManager {
    
    data class ManualLearnResult(
        val disciple: Disciple,
        val events: List<String>,
        val learnedManual: Manual? = null,
        val replacedManual: Manual? = null,
        val remainingManual: Manual? = null
    )
    
    fun processAutoLearn(
        disciple: Disciple,
        manuals: Map<String, Manual>,
        gameYear: Int,
        gameMonth: Int,
        instantMessage: Boolean = false
    ): ManualLearnResult {
        val events = mutableListOf<String>()
        
        if (disciple.storageBagItems.isEmpty()) {
            return ManualLearnResult(disciple, events)
        }
        
        var updatedDisciple = disciple
        
        val hasMindManual = disciple.manualIds.any { manualId ->
            manuals[manualId]?.type == ManualType.MIND
        }
        
        val bagManuals = disciple.storageBagItems
            .filter { it.itemType == "manual" && !disciple.manualIds.contains(it.itemId) }
            .mapNotNull { manuals[it.itemId] }
            .filter { manual -> 
                val canLearnByRealm = GameConfig.Realm.meetsRealmRequirement(disciple.realm, manual.minRealm)
                if (hasMindManual) manual.type != ManualType.MIND && canLearnByRealm else canLearnByRealm
            }
        
        if (bagManuals.isEmpty()) {
            return ManualLearnResult(updatedDisciple, events)
        }
        
        val learnedManuals = disciple.manualIds.mapNotNull { manuals[it] }
        val maxManualSlots = calculateMaxManualSlots(disciple)
        
        return if (learnedManuals.size < maxManualSlots) {
            learnNewManual(
                disciple = updatedDisciple,
                bagManuals = bagManuals,
                manuals = manuals,
                gameYear = gameYear,
                gameMonth = gameMonth,
                instantMessage = instantMessage
            )
        } else {
            tryReplaceManual(
                disciple = updatedDisciple,
                bagManuals = bagManuals,
                learnedManuals = learnedManuals,
                hasMindManual = hasMindManual,
                manuals = manuals,
                gameYear = gameYear,
                gameMonth = gameMonth,
                instantMessage = instantMessage
            )
        }
    }
    
    private fun calculateMaxManualSlots(disciple: Disciple): Int {
        return DiscipleStatCalculator.getMaxManualSlots(disciple)
    }
    
    private fun learnNewManual(
        disciple: Disciple,
        bagManuals: List<Manual>,
        manuals: Map<String, Manual>,
        gameYear: Int,
        gameMonth: Int,
        instantMessage: Boolean
    ): ManualLearnResult {
        val events = mutableListOf<String>()
        val bestManual = bagManuals.maxByOrNull { it.rarity } ?: return ManualLearnResult(disciple, events)
        
        val learnedManual: Manual
        val remainingManual: Manual?
        
        if (bestManual.quantity > 1) {
            val learnedManualId = java.util.UUID.randomUUID().toString()
            learnedManual = bestManual.copy(
                id = learnedManualId,
                quantity = 1,
                isLearned = true,
                ownerId = disciple.id
            )
            remainingManual = bestManual.copy(quantity = bestManual.quantity - 1, isLearned = false, ownerId = null)
            
            var updatedDisciple = disciple.copyWith(
                manualIds = disciple.manualIds + learnedManualId
            )
            updatedDisciple = updatedDisciple.copyWith(
                storageBagItems = updatedDisciple.storageBagItems.filter { it.itemId != bestManual.id }
            )
            
            val messagePrefix = if (instantMessage) "立即" else "自动"
            events.add("${disciple.name} ${messagePrefix}学习了功法 ${bestManual.name}")
            
            return ManualLearnResult(updatedDisciple, events, learnedManual, null, remainingManual)
        } else {
            var updatedDisciple = disciple.copyWith(
                manualIds = disciple.manualIds + bestManual.id
            )
            updatedDisciple = updatedDisciple.copyWith(
                storageBagItems = updatedDisciple.storageBagItems.filter { it.itemId != bestManual.id }
            )
            learnedManual = bestManual.copy(isLearned = true, ownerId = disciple.id)
            
            val messagePrefix = if (instantMessage) "立即" else "自动"
            events.add("${disciple.name} ${messagePrefix}学习了功法 ${bestManual.name}")
            
            return ManualLearnResult(updatedDisciple, events, learnedManual)
        }
    }
    
    private fun tryReplaceManual(
        disciple: Disciple,
        bagManuals: List<Manual>,
        learnedManuals: List<Manual>,
        hasMindManual: Boolean,
        manuals: Map<String, Manual>,
        gameYear: Int,
        gameMonth: Int,
        instantMessage: Boolean
    ): ManualLearnResult {
        val events = mutableListOf<String>()
        
        val lowestLearned = learnedManuals.minByOrNull { it.rarity } ?: return ManualLearnResult(disciple, events)
        val highestBag = bagManuals.maxByOrNull { it.rarity } ?: return ManualLearnResult(disciple, events)
        
        if (highestBag.rarity <= lowestLearned.rarity) {
            return ManualLearnResult(disciple, events)
        }
        
        if (hasMindManual && highestBag.type == ManualType.MIND && lowestLearned.type != ManualType.MIND) {
            return ManualLearnResult(disciple, events)
        }
        
        var updatedDisciple = disciple
        
        val learnedManual: Manual
        val remainingManual: Manual?
        
        if (highestBag.quantity > 1) {
            val learnedManualId = java.util.UUID.randomUUID().toString()
            learnedManual = highestBag.copy(
                id = learnedManualId,
                quantity = 1,
                isLearned = true,
                ownerId = disciple.id
            )
            remainingManual = highestBag.copy(quantity = highestBag.quantity - 1, isLearned = false, ownerId = null)
            
            val updatedManualIds = disciple.manualIds.toMutableList()
            updatedManualIds.remove(lowestLearned.id)
            updatedManualIds.add(learnedManualId)
            updatedDisciple = updatedDisciple.copyWith(manualIds = updatedManualIds)
        } else {
            val updatedManualIds = disciple.manualIds.toMutableList()
            updatedManualIds.remove(lowestLearned.id)
            updatedManualIds.add(highestBag.id)
            updatedDisciple = updatedDisciple.copyWith(manualIds = updatedManualIds)
            
            learnedManual = highestBag.copy(isLearned = true, ownerId = disciple.id)
            remainingManual = null
        }

        updatedDisciple = updatedDisciple.copyWith(
            storageBagItems = updatedDisciple.storageBagItems.filter { it.itemId != highestBag.id }
        )

        val oldItem = StorageBagItem(
            itemId = lowestLearned.id,
            itemType = "manual",
            name = lowestLearned.name,
            rarity = lowestLearned.rarity,
            quantity = 1,
            obtainedYear = gameYear,
            obtainedMonth = gameMonth
        )
        updatedDisciple = updatedDisciple.copyWith(
            storageBagItems = updatedDisciple.storageBagItems + oldItem
        )
        
        val replacedManual = lowestLearned.copy(isLearned = false, ownerId = null)
        
        val messagePrefix = if (instantMessage) "立即" else "自动"
        events.add("${disciple.name} ${messagePrefix}替换功法：${lowestLearned.name} → ${highestBag.name}")
        
        return ManualLearnResult(updatedDisciple, events, learnedManual, replacedManual, remainingManual)
    }
    
    fun canLearn(disciple: Disciple, manual: Manual, allManuals: Map<String, Manual>): Boolean {
        val maxSlots = calculateMaxManualSlots(disciple)
        val hasMindManual = disciple.manualIds.any { allManuals[it]?.type == ManualType.MIND }
        
        return !disciple.manualIds.contains(manual.id) &&
               GameConfig.Realm.meetsRealmRequirement(disciple.realm, manual.minRealm) &&
               (manual.type != ManualType.MIND || !hasMindManual) &&
               disciple.manualIds.size < maxSlots
    }
}
