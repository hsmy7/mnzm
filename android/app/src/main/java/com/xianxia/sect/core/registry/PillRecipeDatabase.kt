package com.xianxia.sect.core.registry

import com.xianxia.sect.core.model.PillCategory
import com.xianxia.sect.core.model.PillGrade
import kotlin.math.roundToInt

object PillRecipeDatabase {

    data class PillRecipe(
        val id: String,
        val name: String,
        val tier: Int,
        val rarity: Int,
        val category: PillCategory,
        val grade: PillGrade,
        val pillType: String,
        val description: String,
        val materials: Map<String, Int>,
        val duration: Int,
        val successRate: Double,
        val breakthroughChance: Double = 0.0,
        val targetRealm: Int = 0,
        val cultivationSpeedPercent: Double = 0.0,
        val skillExpSpeedPercent: Double = 0.0,
        val nurtureSpeedPercent: Double = 0.0,
        val cultivationAdd: Int = 0,
        val skillExpAdd: Int = 0,
        val nurtureAdd: Int = 0,
        val physicalAttackAdd: Int = 0,
        val magicAttackAdd: Int = 0,
        val physicalDefenseAdd: Int = 0,
        val magicDefenseAdd: Int = 0,
        val hpAdd: Int = 0,
        val mpAdd: Int = 0,
        val speedAdd: Int = 0,
        val critRateAdd: Double = 0.0,
        val critEffectAdd: Double = 0.0,
        val extendLife: Int = 0,
        val intelligenceAdd: Int = 0,
        val charmAdd: Int = 0,
        val loyaltyAdd: Int = 0,
        val comprehensionAdd: Int = 0,
        val artifactRefiningAdd: Int = 0,
        val pillRefiningAdd: Int = 0,
        val spiritPlantingAdd: Int = 0,
        val teachingAdd: Int = 0,
        val moralityAdd: Int = 0
    )

    private val TIER_DURATION = mapOf(1 to 2, 2 to 5, 3 to 9, 4 to 18, 5 to 30, 6 to 48)
    private val TIER_SUCCESS_RATE = mapOf(1 to 0.75, 2 to 0.65, 3 to 0.60, 4 to 0.45, 5 to 0.35, 6 to 0.20)

    private val TIER_HERB_IDS = mapOf(
        1 to listOf("spiritGrass1", "spiritGrass2", "spiritGrass3", "spiritFlower1", "spiritFlower2", "spiritFlower3", "spiritFruit1", "spiritFruit2", "spiritFruit3"),
        2 to listOf("spiritGrass4", "spiritGrass5", "spiritGrass6", "spiritFlower4", "spiritFlower5", "spiritFlower6", "spiritFruit4", "spiritFruit5", "spiritFruit6"),
        3 to listOf("spiritGrass7", "spiritGrass8", "spiritGrass9", "spiritFlower7", "spiritFlower8", "spiritFlower9", "spiritFruit7", "spiritFruit8", "spiritFruit9"),
        4 to listOf("spiritGrass10", "spiritGrass11", "spiritGrass12", "spiritFlower10", "spiritFlower11", "spiritFlower12", "spiritFruit10", "spiritFruit11", "spiritFruit12"),
        5 to listOf("spiritGrass13", "spiritGrass14", "spiritGrass15", "spiritFlower13", "spiritFlower14", "spiritFlower15", "spiritFruit13", "spiritFruit14", "spiritFruit15"),
        6 to listOf("spiritGrass16", "spiritGrass17", "spiritGrass18", "spiritFlower16", "spiritFlower17", "spiritFlower18", "spiritFruit16", "spiritFruit17", "spiritFruit18")
    )

    private fun herbMat(tier: Int, vararg indices: Int): Map<String, Int> {
        val herbs = TIER_HERB_IDS[tier]!!
        val result = mutableMapOf<String, Int>()
        for (idx in indices) {
            val herbId = herbs[idx.coerceIn(0, herbs.size - 1)]
            result[herbId] = (result[herbId] ?: 0) + 2
        }
        return result
    }

    private fun generateCultivationRecipes(): List<PillRecipe> {
        val recipes = mutableListOf<PillRecipe>()
        val pillTypes = listOf("cultivationSpeed", "skillExpSpeed", "nurtureSpeed", "cultivationAdd", "skillExpAdd", "nurtureAdd")
        val herbPatterns = listOf(
            listOf(0, 3), listOf(1, 6), listOf(2, 4), listOf(0, 7), listOf(5, 8), listOf(3, 7)
        )

        for (tier in 1..6) {
            val duration = TIER_DURATION[tier]!!
            val successRate = TIER_SUCCESS_RATE[tier]!!
            val rarity = tier

            for ((idx, pillType) in pillTypes.withIndex()) {
                val materials = herbMat(tier, *herbPatterns[idx].toIntArray())
                for (grade in PillGrade.entries) {
                    val template = ItemDatabase.getPillById("${pillType}_${tier}_${grade.name.lowercase()}") ?: continue
                    recipes.add(PillRecipe(
                        id = template.id,
                        name = template.name,
                        tier = tier,
                        rarity = rarity,
                        category = PillCategory.CULTIVATION,
                        grade = grade,
                        pillType = pillType,
                        description = template.description,
                        materials = materials,
                        duration = duration,
                        successRate = successRate,
                        cultivationSpeedPercent = template.cultivationSpeedPercent,
                        skillExpSpeedPercent = template.skillExpSpeedPercent,
                        nurtureSpeedPercent = template.nurtureSpeedPercent,
                        cultivationAdd = template.cultivationAdd,
                        skillExpAdd = template.skillExpAdd,
                        nurtureAdd = template.nurtureAdd
                    ))
                }
            }
        }

        val breakthroughData = listOf(
            Pair(2, listOf(Pair(8, "筑基丹"), Pair(7, "凝金丹"), Pair(6, "结婴丹"))),
            Pair(3, listOf(Pair(5, "化神丹"), Pair(4, "破虚丹"))),
            Pair(5, listOf(Pair(3, "合道丹"))),
            Pair(6, listOf(Pair(2, "大乘丹"), Pair(1, "渡劫丹"), Pair(0, "登仙丹")))
        )

        for ((tier, targets) in breakthroughData) {
            val duration = TIER_DURATION[tier]!!
            val successRate = TIER_SUCCESS_RATE[tier]!!
            val rarity = tier
            val herbs = TIER_HERB_IDS[tier]!!
            for ((idx, targetData) in targets.withIndex()) {
                val (targetRealm, _) = targetData
                val materials = mapOf(herbs[idx * 2 % herbs.size] to 2, herbs[(idx * 2 + 3) % herbs.size] to 2)
                if (tier >= 3) {
                    (materials as MutableMap)[herbs[(idx * 2 + 5) % herbs.size]] = 2
                }
                for (grade in PillGrade.entries) {
                    val template = ItemDatabase.getPillById("breakthrough_${targetRealm}_${grade.name.lowercase()}") ?: continue
                    recipes.add(PillRecipe(
                        id = template.id,
                        name = template.name,
                        tier = tier,
                        rarity = rarity,
                        category = PillCategory.CULTIVATION,
                        grade = grade,
                        pillType = "breakthrough",
                        description = template.description,
                        materials = if (tier >= 3) materials else materials,
                        duration = duration,
                        successRate = successRate,
                        breakthroughChance = template.breakthroughChance,
                        targetRealm = targetRealm
                    ))
                }
            }
        }

        return recipes
    }

    private fun generateBattleRecipes(): List<PillRecipe> {
        val recipes = mutableListOf<PillRecipe>()
        val singleTypes = listOf("physicalAttack", "magicAttack", "physicalDefense", "magicDefense", "hp", "mp", "speed")
        val dualTypes = listOf("physicalAttackDefense", "magicAttackDefense", "attackMixed", "defenseMixed", "hpMp", "attackSpeed", "magicSpeed")
        val critTypes = listOf("critRate", "critEffect")

        for (tier in 1..6) {
            val duration = TIER_DURATION[tier]!!
            val successRate = TIER_SUCCESS_RATE[tier]!!
            val rarity = tier
            val herbs = TIER_HERB_IDS[tier]!!

            for ((idx, pillType) in singleTypes.withIndex()) {
                val materials = mapOf(herbs[idx % herbs.size] to 2, herbs[(idx + 4) % herbs.size] to 2)
                for (grade in PillGrade.entries) {
                    val template = ItemDatabase.getPillById("${pillType}_${tier}_${grade.name.lowercase()}") ?: continue
                    recipes.add(PillRecipe(
                        id = template.id,
                        name = template.name,
                        tier = tier,
                        rarity = rarity,
                        category = PillCategory.BATTLE,
                        grade = grade,
                        pillType = pillType,
                        description = template.description,
                        materials = materials,
                        duration = duration,
                        successRate = successRate,
                        physicalAttackAdd = template.physicalAttackAdd,
                        magicAttackAdd = template.magicAttackAdd,
                        physicalDefenseAdd = template.physicalDefenseAdd,
                        magicDefenseAdd = template.magicDefenseAdd,
                        hpAdd = template.hpAdd,
                        mpAdd = template.mpAdd,
                        speedAdd = template.speedAdd
                    ))
                }
            }

            for ((idx, pillType) in dualTypes.withIndex()) {
                val materials = mapOf(herbs[idx % herbs.size] to 2, herbs[(idx + 3) % herbs.size] to 2)
                for (grade in PillGrade.entries) {
                    val template = ItemDatabase.getPillById("${pillType}_${tier}_${grade.name.lowercase()}") ?: continue
                    recipes.add(PillRecipe(
                        id = template.id,
                        name = template.name,
                        tier = tier,
                        rarity = rarity,
                        category = PillCategory.BATTLE,
                        grade = grade,
                        pillType = pillType,
                        description = template.description,
                        materials = materials,
                        duration = duration,
                        successRate = successRate,
                        physicalAttackAdd = template.physicalAttackAdd,
                        magicAttackAdd = template.magicAttackAdd,
                        physicalDefenseAdd = template.physicalDefenseAdd,
                        magicDefenseAdd = template.magicDefenseAdd,
                        hpAdd = template.hpAdd,
                        mpAdd = template.mpAdd,
                        speedAdd = template.speedAdd
                    ))
                }
            }

            for (pillType in critTypes) {
                val materials = mapOf(herbs[0] to 2, herbs[5] to 2)
                for (grade in PillGrade.entries) {
                    val template = ItemDatabase.getPillById("${pillType}_${tier}_${grade.name.lowercase()}") ?: continue
                    recipes.add(PillRecipe(
                        id = template.id,
                        name = template.name,
                        tier = tier,
                        rarity = rarity,
                        category = PillCategory.BATTLE,
                        grade = grade,
                        pillType = pillType,
                        description = template.description,
                        materials = materials,
                        duration = duration,
                        successRate = successRate,
                        critRateAdd = template.critRateAdd,
                        critEffectAdd = template.critEffectAdd
                    ))
                }
            }
        }

        return recipes
    }

    private fun generateFunctionalRecipes(): List<PillRecipe> {
        val recipes = mutableListOf<PillRecipe>()
        val singleTypes = listOf("extendLife", "intelligence", "charm", "loyalty", "comprehension", "artifactRefining", "pillRefining", "spiritPlanting", "teaching", "morality")
        val dualTypes = listOf("intelligenceComprehension", "charmLoyalty", "pillRefiningArtifactRefining", "spiritPlantingTeaching", "intelligenceCharm", "comprehensionMorality")

        for (tier in 1..6) {
            val duration = TIER_DURATION[tier]!!
            val successRate = TIER_SUCCESS_RATE[tier]!!
            val rarity = tier
            val herbs = TIER_HERB_IDS[tier]!!

            for ((idx, pillType) in singleTypes.withIndex()) {
                val materials = mapOf(herbs[idx % herbs.size] to 2, herbs[(idx + 6) % herbs.size] to 2)
                for (grade in PillGrade.entries) {
                    val template = ItemDatabase.getPillById("${pillType}_${tier}_${grade.name.lowercase()}") ?: continue
                    recipes.add(PillRecipe(
                        id = template.id,
                        name = template.name,
                        tier = tier,
                        rarity = rarity,
                        category = PillCategory.FUNCTIONAL,
                        grade = grade,
                        pillType = pillType,
                        description = template.description,
                        materials = materials,
                        duration = duration,
                        successRate = successRate,
                        extendLife = template.extendLife,
                        intelligenceAdd = template.intelligenceAdd,
                        charmAdd = template.charmAdd,
                        loyaltyAdd = template.loyaltyAdd,
                        comprehensionAdd = template.comprehensionAdd,
                        artifactRefiningAdd = template.artifactRefiningAdd,
                        pillRefiningAdd = template.pillRefiningAdd,
                        spiritPlantingAdd = template.spiritPlantingAdd,
                        teachingAdd = template.teachingAdd,
                        moralityAdd = template.moralityAdd
                    ))
                }
            }

            for ((idx, pillType) in dualTypes.withIndex()) {
                val materials = mapOf(herbs[idx % herbs.size] to 2, herbs[(idx + 2) % herbs.size] to 2)
                for (grade in PillGrade.entries) {
                    val template = ItemDatabase.getPillById("${pillType}_${tier}_${grade.name.lowercase()}") ?: continue
                    recipes.add(PillRecipe(
                        id = template.id,
                        name = template.name,
                        tier = tier,
                        rarity = rarity,
                        category = PillCategory.FUNCTIONAL,
                        grade = grade,
                        pillType = pillType,
                        description = template.description,
                        materials = materials,
                        duration = duration,
                        successRate = successRate,
                        intelligenceAdd = template.intelligenceAdd,
                        charmAdd = template.charmAdd,
                        loyaltyAdd = template.loyaltyAdd,
                        comprehensionAdd = template.comprehensionAdd,
                        artifactRefiningAdd = template.artifactRefiningAdd,
                        pillRefiningAdd = template.pillRefiningAdd,
                        spiritPlantingAdd = template.spiritPlantingAdd,
                        teachingAdd = template.teachingAdd,
                        moralityAdd = template.moralityAdd
                    ))
                }
            }
        }

        return recipes
    }

    private val _allRecipes: List<PillRecipe> by lazy {
        generateCultivationRecipes() + generateBattleRecipes() + generateFunctionalRecipes()
    }

    fun getAllRecipes(): List<PillRecipe> = _allRecipes

    fun getRecipeById(id: String): PillRecipe? = _allRecipes.find { it.id == id }

    fun getRecipeByName(name: String): PillRecipe? = _allRecipes.find { it.name == name }

    fun getRecipeByNameAndGrade(name: String, grade: PillGrade): PillRecipe? = _allRecipes.find { it.name == name && it.grade == grade }

    fun getRecipesByMaterial(materialId: String): List<PillRecipe> = _allRecipes.filter { it.materials.containsKey(materialId) }

    fun getRecipesByHerb(herbId: String): List<PillRecipe> = _allRecipes.filter { it.materials.containsKey(herbId) }

    fun getRecipesByTier(tier: Int): List<PillRecipe> = _allRecipes.filter { it.tier == tier }

    fun getDurationByTier(tier: Int): Int = TIER_DURATION[tier] ?: 2

    fun getTierName(tier: Int): String = when (tier) {
        1 -> "凡品"
        2 -> "灵品"
        3 -> "宝品"
        4 -> "玄品"
        5 -> "地品"
        6 -> "天品"
        else -> "未知"
    }
}
