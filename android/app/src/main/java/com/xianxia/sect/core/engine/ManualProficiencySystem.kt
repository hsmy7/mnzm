package com.xianxia.sect.core.engine

import com.xianxia.sect.core.GameConfig
import kotlin.math.pow
import kotlin.random.Random

object ManualProficiencySystem {
    
    const val BASE_PROFICIENCY_GAIN = 1.0
    const val MASTERY_THRESHOLD = 100.0

    /**
     * 藏经阁功法熟练度修炼加成比例。
     *
     * 藏经阁仅对分配到3个槽位内的弟子提供加成效果：
     * - 处于藏经阁槽位的弟子：修炼速度 = 基础速度 × (1.0 + 0.5)（即加快 50%）
     * - 未在藏经阁中的弟子：无加成
     */
    const val LIBRARY_PROFICIENCY_BONUS_RATE = 0.5
    
    // 根据功法品阶获取熟练度阈值（品阶越高，需要的熟练度越多）
    fun getProficiencyThresholds(manualRarity: Int): Map<MasteryLevel, Double> {
        val baseThresholds = mapOf(
            MasteryLevel.NOVICE to 0.0,
            MasteryLevel.SMALL_SUCCESS to 100.0,
            MasteryLevel.GREAT_SUCCESS to 200.0,
            MasteryLevel.PERFECTION to 300.0
        )

        // 根据品阶调整阈值（品阶越高，阈值越高）
        val rarityMultiplier = when (manualRarity) {
            1 -> 1.0    // 凡品
            2 -> 1.5    // 灵品
            3 -> 2.0    // 宝品
            4 -> 3.0    // 玄品
            5 -> 4.0    // 地品
            6 -> 5.0    // 天品
            else -> 1.0
        }

        return baseThresholds.mapValues { (_, threshold) ->
            if (threshold == 0.0) 0.0 else threshold * rarityMultiplier
        }
    }

    // 获取最大熟练度（根据品阶）
    fun getMaxProficiency(manualRarity: Int): Double {
        return 400.0 * when (manualRarity) {
            1 -> 1.0
            2 -> 1.5
            3 -> 2.0
            4 -> 3.0
            5 -> 4.0
            6 -> 5.0
            else -> 1.0
        }
    }

    enum class MasteryLevel(val level: Int, val displayName: String, val bonus: Double) {
        NOVICE(0, "入门", 1.0),
        SMALL_SUCCESS(1, "小成", 1.2),
        GREAT_SUCCESS(2, "大成", 1.5),
        PERFECTION(3, "圆满", 2.0);

        companion object {
            fun fromProficiency(proficiency: Double, manualRarity: Int = 1): MasteryLevel {
                val thresholds = getProficiencyThresholds(manualRarity)
                val perfectionThreshold = thresholds[PERFECTION] ?: 300.0
                val greatSuccessThreshold = thresholds[GREAT_SUCCESS] ?: 200.0
                val smallSuccessThreshold = thresholds[SMALL_SUCCESS] ?: 100.0
                
                return when {
                    proficiency >= perfectionThreshold -> PERFECTION
                    proficiency >= greatSuccessThreshold -> GREAT_SUCCESS
                    proficiency >= smallSuccessThreshold -> SMALL_SUCCESS
                    else -> NOVICE
                }
            }

            fun fromLevel(level: Int): MasteryLevel {
                return values().find { it.level == level } ?: NOVICE
            }
        }
    }
    
    data class ManualProficiency(
        val manualId: String,
        val manualName: String,
        val proficiency: Double = 0.0,
        val masteryLevel: Int = 0
    ) {
        val mastery: MasteryLevel get() = MasteryLevel.fromProficiency(proficiency)
        val masteryName: String get() = mastery.displayName
        val bonus: Double get() = mastery.bonus
        
        val progressToNextLevel: Double
            get() = when (mastery) {
                MasteryLevel.NOVICE -> (proficiency / 100.0).coerceIn(0.0, 1.0)
                MasteryLevel.SMALL_SUCCESS -> ((proficiency - 100) / 100.0).coerceIn(0.0, 1.0)
                MasteryLevel.GREAT_SUCCESS -> ((proficiency - 200) / 100.0).coerceIn(0.0, 1.0)
                MasteryLevel.PERFECTION -> 1.0
            }
        
        val nextLevelName: String
            get() = when (mastery) {
                MasteryLevel.NOVICE -> "小成"
                MasteryLevel.SMALL_SUCCESS -> "大成"
                MasteryLevel.GREAT_SUCCESS -> "圆满"
                MasteryLevel.PERFECTION -> "已圆满"
            }
    }
    
    fun calculateProficiencyGain(
        baseGain: Double = BASE_PROFICIENCY_GAIN,
        discipleRealm: Int,
        manualRarity: Int,
        libraryBonus: Double = 0.0,
        talentBonus: Double = 0.0
    ): Double {
        var gain = baseGain
        
        // Realm bonus: higher realm disciples learn faster
        val realmBonus = when {
            discipleRealm <= 7 -> 1.0 + (9 - discipleRealm) * 0.1
            else -> 1.0
        }
        gain *= realmBonus
        
        // Rarity penalty: higher rarity manuals are harder to learn
        val rarityPenalty = when (manualRarity) {
            1 -> 1.0
            2 -> 0.9
            3 -> 0.8
            4 -> 0.7
            5 -> 0.6
            else -> 1.0
        }
        gain *= rarityPenalty
        
        // Library bonus: disciples studying in library learn faster
        gain *= (1.0 + libraryBonus)
        
        // Talent bonus: certain talents improve learning
        gain *= (1.0 + talentBonus)
        
        return gain
    }
    
    fun updateProficiency(
        current: ManualProficiency,
        gain: Double,
        manualRarity: Int = 1
    ): ManualProficiency {
        val maxProficiency = getMaxProficiency(manualRarity)
        val newProficiency = (current.proficiency + gain).coerceAtMost(maxProficiency)
        val newMasteryLevel = MasteryLevel.fromProficiency(newProficiency, manualRarity).level

        return current.copy(
            proficiency = newProficiency,
            masteryLevel = newMasteryLevel
        )
    }
    
    fun calculateManualStatsBonus(
        baseStats: Map<String, Int>,
        masteryLevel: Int
    ): Map<String, Int> {
        val mastery = MasteryLevel.fromLevel(masteryLevel)
        return baseStats.mapValues { (_, value) ->
            (value * mastery.bonus).toInt()
        }
    }
    
    fun calculateSkillDamageMultiplier(
        baseMultiplier: Double,
        masteryLevel: Int
    ): Double {
        val mastery = MasteryLevel.fromLevel(masteryLevel)
        return baseMultiplier * mastery.bonus
    }
    
    fun shouldAutoLearnManual(
        discipleRealm: Int,
        manualRarity: Int,
        currentManualCount: Int
    ): Boolean {
        // Disciples can only learn manuals appropriate for their realm
        val minRealm = GameConfig.Realm.getMinRealmForRarity(manualRarity)
        
        if (discipleRealm > minRealm) return false
        
        // Limit number of manuals a disciple can learn
        val maxManuals = when {
            discipleRealm >= 8 -> 3
            discipleRealm >= 6 -> 4
            discipleRealm >= 4 -> 5
            else -> 6
        }
        
        return currentManualCount < maxManuals
    }
    
    fun selectBestManualToLearn(
        availableManuals: List<ManualInfo>,
        discipleRealm: Int,
        discipleSpiritRoot: String,
        currentManualTypes: Set<String>
    ): ManualInfo? {
        val eligible = availableManuals.filter { manual ->
            val minRealm = GameConfig.Realm.getMinRealmForRarity(manual.rarity)
            
            discipleRealm <= minRealm &&
            !currentManualTypes.contains(manual.type) &&
            (manual.spiritRootRequirement == null || manual.spiritRootRequirement == discipleSpiritRoot)
        }
        
        if (eligible.isEmpty()) return null
        
        // Prefer higher rarity manuals
        return eligible.sortedByDescending { it.rarity }.firstOrNull()
    }
    
    data class ManualInfo(
        val id: String,
        val name: String,
        val type: String,
        val rarity: Int,
        val spiritRootRequirement: String? = null
    )
    
    fun generateProficiencyGainMessage(
        manualName: String,
        oldMastery: MasteryLevel,
        newMastery: MasteryLevel
    ): String? {
        return if (newMastery.level > oldMastery.level) {
            "《$manualName》修炼至${newMastery.displayName}境界！"
        } else {
            null
        }
    }
}
