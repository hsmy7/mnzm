@file:Suppress("DEPRECATION")

package com.xianxia.sect.core.engine.system

import android.util.Log
import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.engine.DiscipleStatCalculator
import com.xianxia.sect.core.model.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

@Singleton
class CultivationSystem @Inject constructor() : GameSystem {
    
    companion object {
        private const val TAG = "CultivationSystem"
        const val SYSTEM_NAME = "CultivationSystem"
    }
    
    override val systemName: String = SYSTEM_NAME
    
    override fun initialize() {
        Log.d(TAG, "CultivationSystem initialized")
    }
    
    override fun release() {
        Log.d(TAG, "CultivationSystem released")
    }
    
    override suspend fun clear() {
    }

    data class CultivationResult(
        val disciple: Disciple,
        val cultivationGain: Double,
        val breakthroughAttempted: Boolean = false,
        val breakthroughSuccess: Boolean = false,
        val events: List<String> = emptyList()
    )
    
    data class BreakthroughResult(
        val success: Boolean,
        val newRealm: Int? = null,
        val newLayer: Int? = null,
        val lifespanGain: Int = 0,
        val message: String
    )
    
    fun processDiscipleCultivation(
        disciple: Disciple,
        manuals: List<Manual>,
        pills: List<Pill>,
        isSecondTick: Boolean = true
    ): CultivationResult {
        if (!disciple.isAlive || disciple.age < 5) {
            return CultivationResult(disciple, 0.0)
        }

        val events = mutableListOf<String>()
        var currentDisciple = disciple

        val manualsMap = manuals.associateBy { it.id }

        val speedPerSecond = DiscipleStatCalculator.calculateCultivationSpeed(
            disciple = currentDisciple,
            manuals = manualsMap
        )

        val cultivationGain = maxOf(0.0, speedPerSecond *
            if (isSecondTick) 1.0 else 0.5)

        currentDisciple = currentDisciple.copyWith(
            cultivation = currentDisciple.cultivation + cultivationGain,
            totalCultivation = currentDisciple.totalCultivation + cultivationGain.toLong()
        )

        if (currentDisciple.cultivation >= currentDisciple.maxCultivation && currentDisciple.realm > 0) {
            val breakthroughResult = attemptBreakthrough(currentDisciple)
            if (breakthroughResult.success) {
                currentDisciple = currentDisciple.copyWith(
                    realm = breakthroughResult.newRealm ?: currentDisciple.realm,
                    realmLayer = breakthroughResult.newLayer ?: currentDisciple.realmLayer,
                    lifespan = currentDisciple.lifespan + breakthroughResult.lifespanGain,
                    cultivation = 0.0,
                    breakthroughCount = currentDisciple.breakthroughCount + 1
                )
                events.add(breakthroughResult.message)
                return CultivationResult(
                    currentDisciple,
                    cultivationGain,
                    true,
                    true,
                    events
                )
            } else {
                events.add(breakthroughResult.message)
                return CultivationResult(
                    currentDisciple,
                    cultivationGain,
                    true,
                    false,
                    events
                )
            }
        }

        return CultivationResult(currentDisciple, cultivationGain, events = events)
    }
    
    fun attemptBreakthrough(
        disciple: Disciple,
        pills: List<Pill> = emptyList(),
        pillBonus: Double = 0.0
    ): BreakthroughResult {
        if (disciple.realm == 0) {
            return BreakthroughResult(
                false,
                message = "${disciple.name}已达到最高境界"
            )
        }

        val isMajorBreakthrough = disciple.realmLayer >= GameConfig.Realm.get(disciple.realm).maxLayers
        if (isMajorBreakthrough && !DiscipleStatCalculator.meetsSoulPowerRequirement(disciple)) {
            val targetRealm = disciple.realm - 1
            val requiredSoul = GameConfig.Realm.getSoulPowerRequirement(targetRealm)
            return BreakthroughResult(
                false,
                message = "${disciple.name}神魂不足（${disciple.soulPower}/$requiredSoul），无法突破至${GameConfig.Realm.getName(targetRealm)}"
            )
        }

        var effectivePillBonus = pillBonus

        val breakthroughPill = pills.find {
            it.category == PillCategory.BREAKTHROUGH &&
            it.targetRealm == disciple.realm &&
            disciple.id !in disciple.usedExtendLifePillIds
        }

        if (breakthroughPill != null) {
            effectivePillBonus += breakthroughPill.breakthroughChance
        }

        val breakthroughChance = DiscipleStatCalculator.getBreakthroughChance(
            disciple = disciple,
            pillBonus = effectivePillBonus
        )
        val success = Random.nextDouble() < breakthroughChance

        return if (success) {
            var newRealm = disciple.realm
            var newLayer = disciple.realmLayer + 1

            if (newLayer > 9) {
                newRealm = (disciple.realm - 1).coerceAtLeast(0)
                newLayer = 1
            }

            val lifespanGain = when (newRealm) {
                8 -> 50
                7 -> 100
                6 -> 200
                5 -> 400
                4 -> 800
                3 -> 1500
                2 -> 3000
                1 -> 5000
                0 -> 10000
                else -> 0
            }

            BreakthroughResult(
                success = true,
                newRealm = newRealm,
                newLayer = newLayer,
                lifespanGain = lifespanGain,
                message = "恭喜${disciple.name}突破至${GameConfig.Realm.getName(newRealm)}${if (newRealm > 0) "${newLayer}层" else ""}！"
            )
        } else {
            BreakthroughResult(
                success = false,
                message = "${disciple.name}突破失败"
            )
        }
    }
    
    fun autoUseCultivationPills(
        disciple: Disciple,
        pills: List<Pill>
    ): Pair<Disciple, Double> {
        var currentDisciple = disciple
        var cultivationBonus = 0.0
        
        val cultivationPills = pills.filter { 
            it.category == PillCategory.CULTIVATION &&
            it.id !in currentDisciple.monthlyUsedPillIds
        }
        
        for (pill in cultivationPills) {
            if (pill.cultivationSpeed > 0) {
                currentDisciple = currentDisciple.copyWith(
                    monthlyUsedPillIds = currentDisciple.monthlyUsedPillIds + pill.id
                )
                cultivationBonus += pill.cultivationSpeed / 100.0
            }
        }
        
        return currentDisciple to cultivationBonus
    }

    fun autoUseBattlePills(disciple: Disciple, pills: List<Pill>): Disciple {
        var currentDisciple = disciple
        
        val battlePills = pills.filter {
            it.category.isBattlePill &&
            it.id !in currentDisciple.monthlyUsedPillIds
        }
        
        for (pill in battlePills) {
            currentDisciple = currentDisciple.copyWith(
                pillPhysicalAttackBonus = currentDisciple.pillPhysicalAttackBonus + pill.physicalAttackPercent / 100.0,
                pillMagicAttackBonus = currentDisciple.pillMagicAttackBonus + pill.magicAttackPercent / 100.0,
                pillPhysicalDefenseBonus = currentDisciple.pillPhysicalDefenseBonus + pill.physicalDefensePercent / 100.0,
                pillMagicDefenseBonus = currentDisciple.pillMagicDefenseBonus + pill.magicDefensePercent / 100.0,
                pillHpBonus = currentDisciple.pillHpBonus + pill.hpPercent / 100.0,
                pillMpBonus = currentDisciple.pillMpBonus + pill.mpPercent / 100.0,
                pillSpeedBonus = currentDisciple.pillSpeedBonus + pill.speedPercent / 100.0,
                pillEffectDuration = pill.duration,
                monthlyUsedPillIds = currentDisciple.monthlyUsedPillIds + pill.id
            )
        }
        
        return currentDisciple
    }
    
    fun autoUseHealingPills(disciple: Disciple, pills: List<Pill>): Disciple {
        var currentDisciple = disciple
        
        val healingPills = pills.filter {
            it.category == PillCategory.HEALING &&
            it.id !in currentDisciple.monthlyUsedPillIds
        }
        
        for (pill in healingPills) {
            currentDisciple = currentDisciple.copyWith(
                hasReviveEffect = currentDisciple.hasReviveEffect || pill.revive,
                hasClearAllEffect = currentDisciple.hasClearAllEffect || pill.clearAll,
                monthlyUsedPillIds = currentDisciple.monthlyUsedPillIds + pill.id
            )
        }
        
        return currentDisciple
    }
    
    fun processPillEffectDuration(disciple: Disciple): Disciple {
        var result = disciple

        if (result.pillEffectDuration > 0) {
            val newDuration = result.pillEffectDuration - 1
            result = if (newDuration <= 0) {
                result.copyWith(
                    pillEffectDuration = 0,
                    pillPhysicalAttackBonus = 0.0,
                    pillMagicAttackBonus = 0.0,
                    pillPhysicalDefenseBonus = 0.0,
                    pillMagicDefenseBonus = 0.0,
                    pillHpBonus = 0.0,
                    pillMpBonus = 0.0,
                    pillSpeedBonus = 0.0
                )
            } else {
                result.copyWith(pillEffectDuration = newDuration)
            }
        }

        if (result.cultivationSpeedDuration > 0) {
            val newCultivationDuration = result.cultivationSpeedDuration - 1
            result = if (newCultivationDuration <= 0) {
                result.copyWith(
                    cultivationSpeedDuration = 0,
                    cultivationSpeedBonus = 1.0
                )
            } else {
                result.copyWith(cultivationSpeedDuration = newCultivationDuration)
            }
        }

        return result
    }
    
    fun resetMonthlyUsedPills(disciple: Disciple): Disciple {
        return disciple.copyWith(monthlyUsedPillIds = emptyList())
    }
}
