package com.xianxia.sect.core.registry

import com.xianxia.sect.core.model.EquipmentSlot

/**
 * 锻造配方：**24 条套装部件配方**（6 套 × 4 部位），按品阶产出。
 *
 * 产出品阶 = 锻造槽位 tier（由工作弟子锻造职业等级决定），并经
 * `EquipmentFactory.create` 的境界约束单点钳制（0.2-5）；
 * 材料按目标品阶取 [tierMaterials] 对应档（部位材料族沿用旧体系，
 * 材料 id 与兽材静态库 beast_material_db 一致）。
 *
 * 时长/成功率与旧体系同表（[TIER_DURATION]/[TIER_SUCCESS_RATE]，按 tier 全局一致）。
 */
object ForgeRecipeDatabase {

    /** 品阶 1..6 锻造时长（旬；沿用旧表） */
    val TIER_DURATION = mapOf(1 to 3, 2 to 6, 3 to 12, 4 to 36, 5 to 72, 6 to 120)

    /** 品阶 1..6 成功率（沿用旧表） */
    val TIER_SUCCESS_RATE = listOf(0.70, 0.65, 0.60, 0.35, 0.30, 0.25)

    data class ForgeRecipe(
        val id: String,
        /** 套装部件 id（= `EquipmentDatabase.SetPieceTemplate.id`） */
        val pieceId: String,
        val setId: String,
        val part: EquipmentSlot,
        val name: String,
        val description: String,
        /** 品阶 1..6 材料表（产出品阶 r 消耗 [materialsFor] 第 r 档） */
        val tierMaterials: List<Map<String, Int>>
    ) {
        fun materialsFor(tier: Int): Map<String, Int> =
            tierMaterials.getOrElse(tier - 1) { tierMaterials.last() }

        fun durationFor(tier: Int): Int = TIER_DURATION[tier.coerceIn(1, 6)] ?: 2

        fun successRateFor(tier: Int): Double =
            TIER_SUCCESS_RATE.getOrElse(tier - 1) { TIER_SUCCESS_RATE.last() }
    }

    private fun recipe(pieceId: String, part: EquipmentSlot, materials: List<Map<String, Int>>): ForgeRecipe {
        val piece = EquipmentDatabase.getPieceById(pieceId)
            ?: error("ForgeRecipe 引用了不存在的部件: $pieceId")
        return ForgeRecipe(
            id = "forge_$pieceId",
            pieceId = pieceId,
            setId = piece.setId,
            part = part,
            name = piece.name,
            description = piece.description,
            tierMaterials = materials
        )
    }

    private fun m(vararg pairs: Pair<String, Int>): Map<String, Int> = mapOf(*pairs)

    /** 24 条配方（6 套 × 4 部位；材料表按品阶 1..6；部位材料族沿用旧锻造体系的同族兽材，
     *  与套无关——材料表逐部位同构复用） */
    private val allRecipes = listOf(
        // ── 物理套「裂天罡煞」 ──
        recipe("lietian_HEAD", EquipmentSlot.HEAD, listOf(
            m("bearHide0" to 3, "bearBone0" to 2), m("bearHide1" to 4, "bearBone1" to 3),
            m("bearHide2" to 5, "bearBone2" to 3, "bearCore2" to 2),
            m("bearHide3" to 5, "bearBone3" to 4, "bearCore3" to 3),
            m("bearHide4" to 6, "bearBone4" to 4, "bearCore4" to 2, "dragonScale4" to 2),
            m("bearHide5" to 8, "bearBone5" to 5, "bearCore5" to 3, "dragonClaw5" to 3)
        )),
        recipe("lietian_BODY", EquipmentSlot.BODY, listOf(
            m("bearHide0" to 4, "bearBone0" to 2), m("bearHide1" to 5, "bearBone1" to 2),
            m("snakeScale2" to 5, "snakeBlood2" to 3, "snakeCore2" to 2),
            m("snakeScale3" to 6, "snakeBlood3" to 4, "snakeCore3" to 2),
            m("snakeScale4" to 6, "snakeBlood4" to 4, "snakeCore4" to 2, "dragonScale4" to 2),
            m("snakeScale5" to 8, "snakeBlood5" to 5, "snakeCore5" to 3, "dragonScale5" to 3)
        )),
        recipe("lietian_HANDS", EquipmentSlot.HANDS, listOf(
            m("eagleClaw0" to 3, "eagleFeather0" to 2), m("eagleClaw1" to 4, "eagleFeather1" to 3),
            m("eagleFeather2" to 5, "eagleClaw2" to 3, "eagleCore2" to 2),
            m("eagleFeather3" to 6, "eagleClaw3" to 4, "eagleCore3" to 2),
            m("eagleFeather4" to 6, "eagleClaw4" to 4, "eagleCore4" to 2, "snakeCore4" to 2),
            m("eagleFeather5" to 7, "eagleClaw5" to 5, "eagleCore5" to 3, "dragonCore5" to 3)
        )),
        recipe("lietian_FEET", EquipmentSlot.FEET, listOf(
            m("wolfHide0" to 3, "wolfBone0" to 2), m("wolfHide1" to 4, "wolfBone1" to 2),
            m("wolfHide2" to 4, "wolfBone2" to 3, "wolfCore2" to 2),
            m("wolfHide3" to 5, "wolfBone3" to 3, "wolfCore3" to 2),
            m("wolfHide4" to 5, "wolfTooth4" to 4, "wolfCore4" to 2, "dragonScale4" to 2),
            m("wolfHide5" to 7, "wolfTooth5" to 5, "wolfCore5" to 3, "dragonScale5" to 3)
        )),
        // ── 金套「庚金白虎」（部位材料族与物理套同构，方案 §3.8） ──
        recipe("gengjin_HEAD", EquipmentSlot.HEAD, listOf(
            m("bearHide0" to 3, "bearBone0" to 2), m("bearHide1" to 4, "bearBone1" to 3),
            m("bearHide2" to 5, "bearBone2" to 3, "bearCore2" to 2),
            m("bearHide3" to 5, "bearBone3" to 4, "bearCore3" to 3),
            m("bearHide4" to 6, "bearBone4" to 4, "bearCore4" to 2, "dragonScale4" to 2),
            m("bearHide5" to 8, "bearBone5" to 5, "bearCore5" to 3, "dragonClaw5" to 3)
        )),
        recipe("gengjin_BODY", EquipmentSlot.BODY, listOf(
            m("bearHide0" to 4, "bearBone0" to 2), m("bearHide1" to 5, "bearBone1" to 2),
            m("snakeScale2" to 5, "snakeBlood2" to 3, "snakeCore2" to 2),
            m("snakeScale3" to 6, "snakeBlood3" to 4, "snakeCore3" to 2),
            m("snakeScale4" to 6, "snakeBlood4" to 4, "snakeCore4" to 2, "dragonScale4" to 2),
            m("snakeScale5" to 8, "snakeBlood5" to 5, "snakeCore5" to 3, "dragonScale5" to 3)
        )),
        recipe("gengjin_HANDS", EquipmentSlot.HANDS, listOf(
            m("eagleClaw0" to 3, "eagleFeather0" to 2), m("eagleClaw1" to 4, "eagleFeather1" to 3),
            m("eagleFeather2" to 5, "eagleClaw2" to 3, "eagleCore2" to 2),
            m("eagleFeather3" to 6, "eagleClaw3" to 4, "eagleCore3" to 2),
            m("eagleFeather4" to 6, "eagleClaw4" to 4, "eagleCore4" to 2, "snakeCore4" to 2),
            m("eagleFeather5" to 7, "eagleClaw5" to 5, "eagleCore5" to 3, "dragonCore5" to 3)
        )),
        recipe("gengjin_FEET", EquipmentSlot.FEET, listOf(
            m("wolfHide0" to 3, "wolfBone0" to 2), m("wolfHide1" to 4, "wolfBone1" to 2),
            m("wolfHide2" to 4, "wolfBone2" to 3, "wolfCore2" to 2),
            m("wolfHide3" to 5, "wolfBone3" to 3, "wolfCore3" to 2),
            m("wolfHide4" to 5, "wolfTooth4" to 4, "wolfCore4" to 2, "dragonScale4" to 2),
            m("wolfHide5" to 7, "wolfTooth5" to 5, "wolfCore5" to 3, "dragonScale5" to 3)
        )),

        // ── 木套「青木长生」（部位材料族与物理套同构，方案 §3.8） ──
        recipe("qingmu_HEAD", EquipmentSlot.HEAD, listOf( // MARK151
            m("bearHide0" to 3, "bearBone0" to 2), m("bearHide1" to 4, "bearBone1" to 3),
            m("bearHide2" to 5, "bearBone2" to 3, "bearCore2" to 2),
            m("bearHide3" to 5, "bearBone3" to 4, "bearCore3" to 3),
            m("bearHide4" to 6, "bearBone4" to 4, "bearCore4" to 2, "dragonScale4" to 2),
            m("bearHide5" to 8, "bearBone5" to 5, "bearCore5" to 3, "dragonClaw5" to 3)
        )),
        recipe("qingmu_BODY", EquipmentSlot.BODY, listOf(
            m("bearHide0" to 4, "bearBone0" to 2), m("bearHide1" to 5, "bearBone1" to 2),
            m("snakeScale2" to 5, "snakeBlood2" to 3, "snakeCore2" to 2),
            m("snakeScale3" to 6, "snakeBlood3" to 4, "snakeCore3" to 2),
            m("snakeScale4" to 6, "snakeBlood4" to 4, "snakeCore4" to 2, "dragonScale4" to 2),
            m("snakeScale5" to 8, "snakeBlood5" to 5, "snakeCore5" to 3, "dragonScale5" to 3)
        )),
        recipe("qingmu_HANDS", EquipmentSlot.HANDS, listOf(
            m("eagleClaw0" to 3, "eagleFeather0" to 2), m("eagleClaw1" to 4, "eagleFeather1" to 3),
            m("eagleFeather2" to 5, "eagleClaw2" to 3, "eagleCore2" to 2),
            m("eagleFeather3" to 6, "eagleClaw3" to 4, "eagleCore3" to 2),
            m("eagleFeather4" to 6, "eagleClaw4" to 4, "eagleCore4" to 2, "snakeCore4" to 2),
            m("eagleFeather5" to 7, "eagleClaw5" to 5, "eagleCore5" to 3, "dragonCore5" to 3)
        )),
        recipe("qingmu_FEET", EquipmentSlot.FEET, listOf(
            m("wolfHide0" to 3, "wolfBone0" to 2), m("wolfHide1" to 4, "wolfBone1" to 2),
            m("wolfHide2" to 4, "wolfBone2" to 3, "wolfCore2" to 2),
            m("wolfHide3" to 5, "wolfBone3" to 3, "wolfCore3" to 2),
            m("wolfHide4" to 5, "wolfTooth4" to 4, "wolfCore4" to 2, "dragonScale4" to 2),
            m("wolfHide5" to 7, "wolfTooth5" to 5, "wolfCore5" to 3, "dragonScale5" to 3)
        )),

        // ── 水套「玄水寒渊」（部位材料族与物理套同构，方案 §3.8） ──
        recipe("xuanshui_HEAD", EquipmentSlot.HEAD, listOf(
            m("bearHide0" to 3, "bearBone0" to 2), m("bearHide1" to 4, "bearBone1" to 3),
            m("bearHide2" to 5, "bearBone2" to 3, "bearCore2" to 2),
            m("bearHide3" to 5, "bearBone3" to 4, "bearCore3" to 3),
            m("bearHide4" to 6, "bearBone4" to 4, "bearCore4" to 2, "dragonScale4" to 2),
            m("bearHide5" to 8, "bearBone5" to 5, "bearCore5" to 3, "dragonClaw5" to 3)
        )),
        recipe("xuanshui_BODY", EquipmentSlot.BODY, listOf(
            m("bearHide0" to 4, "bearBone0" to 2), m("bearHide1" to 5, "bearBone1" to 2),
            m("snakeScale2" to 5, "snakeBlood2" to 3, "snakeCore2" to 2),
            m("snakeScale3" to 6, "snakeBlood3" to 4, "snakeCore3" to 2),
            m("snakeScale4" to 6, "snakeBlood4" to 4, "snakeCore4" to 2, "dragonScale4" to 2),
            m("snakeScale5" to 8, "snakeBlood5" to 5, "snakeCore5" to 3, "dragonScale5" to 3)
        )),
        recipe("xuanshui_HANDS", EquipmentSlot.HANDS, listOf(
            m("eagleClaw0" to 3, "eagleFeather0" to 2), m("eagleClaw1" to 4, "eagleFeather1" to 3),
            m("eagleFeather2" to 5, "eagleClaw2" to 3, "eagleCore2" to 2),
            m("eagleFeather3" to 6, "eagleClaw3" to 4, "eagleCore3" to 2),
            m("eagleFeather4" to 6, "eagleClaw4" to 4, "eagleCore4" to 2, "snakeCore4" to 2),
            m("eagleFeather5" to 7, "eagleClaw5" to 5, "eagleCore5" to 3, "dragonCore5" to 3)
        )),
        recipe("xuanshui_FEET", EquipmentSlot.FEET, listOf(
            m("wolfHide0" to 3, "wolfBone0" to 2), m("wolfHide1" to 4, "wolfBone1" to 2),
            m("wolfHide2" to 4, "wolfBone2" to 3, "wolfCore2" to 2),
            m("wolfHide3" to 5, "wolfBone3" to 3, "wolfCore3" to 2),
            m("wolfHide4" to 5, "wolfTooth4" to 4, "wolfCore4" to 2, "dragonScale4" to 2),
            m("wolfHide5" to 7, "wolfTooth5" to 5, "wolfCore5" to 3, "dragonScale5" to 3)
        )),

        // ── 火套「离火焚天」（部位材料族与物理套同构，方案 §3.8） ──
        recipe("lihuo_HEAD", EquipmentSlot.HEAD, listOf(
            m("bearHide0" to 3, "bearBone0" to 2), m("bearHide1" to 4, "bearBone1" to 3),
            m("bearHide2" to 5, "bearBone2" to 3, "bearCore2" to 2),
            m("bearHide3" to 5, "bearBone3" to 4, "bearCore3" to 3),
            m("bearHide4" to 6, "bearBone4" to 4, "bearCore4" to 2, "dragonScale4" to 2),
            m("bearHide5" to 8, "bearBone5" to 5, "bearCore5" to 3, "dragonClaw5" to 3)
        )),
        recipe("lihuo_BODY", EquipmentSlot.BODY, listOf(
            m("bearHide0" to 4, "bearBone0" to 2), m("bearHide1" to 5, "bearBone1" to 2),
            m("snakeScale2" to 5, "snakeBlood2" to 3, "snakeCore2" to 2),
            m("snakeScale3" to 6, "snakeBlood3" to 4, "snakeCore3" to 2),
            m("snakeScale4" to 6, "snakeBlood4" to 4, "snakeCore4" to 2, "dragonScale4" to 2),
            m("snakeScale5" to 8, "snakeBlood5" to 5, "snakeCore5" to 3, "dragonScale5" to 3)
        )),
        recipe("lihuo_HANDS", EquipmentSlot.HANDS, listOf(
            m("eagleClaw0" to 3, "eagleFeather0" to 2), m("eagleClaw1" to 4, "eagleFeather1" to 3),
            m("eagleFeather2" to 5, "eagleClaw2" to 3, "eagleCore2" to 2),
            m("eagleFeather3" to 6, "eagleClaw3" to 4, "eagleCore3" to 2),
            m("eagleFeather4" to 6, "eagleClaw4" to 4, "eagleCore4" to 2, "snakeCore4" to 2),
            m("eagleFeather5" to 7, "eagleClaw5" to 5, "eagleCore5" to 3, "dragonCore5" to 3)
        )),
        recipe("lihuo_FEET", EquipmentSlot.FEET, listOf(
            m("wolfHide0" to 3, "wolfBone0" to 2), m("wolfHide1" to 4, "wolfBone1" to 2),
            m("wolfHide2" to 4, "wolfBone2" to 3, "wolfCore2" to 2),
            m("wolfHide3" to 5, "wolfBone3" to 3, "wolfCore3" to 2),
            m("wolfHide4" to 5, "wolfTooth4" to 4, "wolfCore4" to 2, "dragonScale4" to 2),
            m("wolfHide5" to 7, "wolfTooth5" to 5, "wolfCore5" to 3, "dragonScale5" to 3)
        )),

        // ── 土套「厚土镇岳」（部位材料族与物理套同构，方案 §3.8） ──
        recipe("houtu_HEAD", EquipmentSlot.HEAD, listOf(
            m("bearHide0" to 3, "bearBone0" to 2), m("bearHide1" to 4, "bearBone1" to 3),
            m("bearHide2" to 5, "bearBone2" to 3, "bearCore2" to 2),
            m("bearHide3" to 5, "bearBone3" to 4, "bearCore3" to 3),
            m("bearHide4" to 6, "bearBone4" to 4, "bearCore4" to 2, "dragonScale4" to 2),
            m("bearHide5" to 8, "bearBone5" to 5, "bearCore5" to 3, "dragonClaw5" to 3)
        )),
        recipe("houtu_BODY", EquipmentSlot.BODY, listOf(
            m("bearHide0" to 4, "bearBone0" to 2), m("bearHide1" to 5, "bearBone1" to 2),
            m("snakeScale2" to 5, "snakeBlood2" to 3, "snakeCore2" to 2),
            m("snakeScale3" to 6, "snakeBlood3" to 4, "snakeCore3" to 2),
            m("snakeScale4" to 6, "snakeBlood4" to 4, "snakeCore4" to 2, "dragonScale4" to 2),
            m("snakeScale5" to 8, "snakeBlood5" to 5, "snakeCore5" to 3, "dragonScale5" to 3)
        )),
        recipe("houtu_HANDS", EquipmentSlot.HANDS, listOf(
            m("eagleClaw0" to 3, "eagleFeather0" to 2), m("eagleClaw1" to 4, "eagleFeather1" to 3),
            m("eagleFeather2" to 5, "eagleClaw2" to 3, "eagleCore2" to 2),
            m("eagleFeather3" to 6, "eagleClaw3" to 4, "eagleCore3" to 2),
            m("eagleFeather4" to 6, "eagleClaw4" to 4, "eagleCore4" to 2, "snakeCore4" to 2),
            m("eagleFeather5" to 7, "eagleClaw5" to 5, "eagleCore5" to 3, "dragonCore5" to 3)
        )),
        recipe("houtu_FEET", EquipmentSlot.FEET, listOf(
            m("wolfHide0" to 3, "wolfBone0" to 2), m("wolfHide1" to 4, "wolfBone1" to 2),
            m("wolfHide2" to 4, "wolfBone2" to 3, "wolfCore2" to 2),
            m("wolfHide3" to 5, "wolfBone3" to 3, "wolfCore3" to 2),
            m("wolfHide4" to 5, "wolfTooth4" to 4, "wolfCore4" to 2, "dragonScale4" to 2),
            m("wolfHide5" to 7, "wolfTooth5" to 5, "wolfCore5" to 3, "dragonScale5" to 3)
        )),
    )

    fun getAllRecipes(): List<ForgeRecipe> = allRecipes

    /**
     * 可锻造配方（全部 24 条恒全量，四部位化 F3：6 套 × 4 部位）。
     */
    @Suppress("UnusedParameter") // B3 退役占位：配方不再按品阶上限过滤，产出品阶由槽位 tier 决定；
    // 保留形参维持签名契约，恒全量行为由 ForgeRecipeDatabaseTest.getCraftableRecipes_alwaysReturnsAll 多值守卫
    fun getCraftableRecipes(maxTier: Int): List<ForgeRecipe> = allRecipes

    fun getRecipeById(id: String): ForgeRecipe? = allRecipes.find { it.id == id }

    fun getRecipeByPiece(pieceId: String): ForgeRecipe? = allRecipes.find { it.pieceId == pieceId }

    fun getRecipesByMaterial(materialId: String): List<ForgeRecipe> =
        allRecipes.filter { recipe -> recipe.tierMaterials.any { it.containsKey(materialId) } }

    fun getRecipesByType(type: EquipmentSlot): List<ForgeRecipe> =
        allRecipes.filter { it.part == type }

    fun getDurationByTier(tier: Int): Int = TIER_DURATION[tier] ?: 2
}
