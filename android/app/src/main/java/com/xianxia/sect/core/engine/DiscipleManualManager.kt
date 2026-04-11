@file:Suppress("DEPRECATION")

package com.xianxia.sect.core.engine

import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.data.TalentDatabase
import com.xianxia.sect.core.model.*

object DiscipleManualManager {
    
    data class ManualLearnResult(
        val disciple: Disciple,
        val events: List<String>,
        val learnedManual: Manual? = null,
        val replacedManual: Manual? = null
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
        val talentEffects = TalentDatabase.calculateTalentEffects(disciple.talentIds)
        val manualSlotBonus = talentEffects["manualSlot"]?.toInt() ?: 0
        return 6 + manualSlotBonus
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
        
        var updatedDisciple = disciple.copyWith(
            manualIds = disciple.manualIds + bestManual.id
        )

        updatedDisciple = updatedDisciple.copyWith(
            storageBagItems = updatedDisciple.storageBagItems.filter { it.itemId != bestManual.id }
        )
        
        val learnedManual = bestManual.copy(isLearned = true, ownerId = disciple.id)
        
        val messagePrefix = if (instantMessage) "立即" else "自动"
        events.add("${disciple.name} ${messagePrefix}学习了功法 ${bestManual.name}")
        
        return ManualLearnResult(updatedDisciple, events, learnedManual)
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
        
        val updatedManualIds = disciple.manualIds.toMutableList()
        updatedManualIds.remove(lowestLearned.id)
        updatedManualIds.add(highestBag.id)
        updatedDisciple = updatedDisciple.copyWith(manualIds = updatedManualIds)

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
        
        val learnedManual = highestBag.copy(isLearned = true, ownerId = disciple.id)
        val replacedManual = lowestLearned.copy(isLearned = false, ownerId = null)
        
        val messagePrefix = if (instantMessage) "立即" else "自动"
        events.add("${disciple.name} ${messagePrefix}替换功法：${lowestLearned.name} → ${highestBag.name}")
        
        return ManualLearnResult(updatedDisciple, events, learnedManual, replacedManual)
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
