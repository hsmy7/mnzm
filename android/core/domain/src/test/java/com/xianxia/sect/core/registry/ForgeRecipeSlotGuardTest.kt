package com.xianxia.sect.core.registry

import com.xianxia.sect.core.model.EquipmentSlot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 锻造配方产出部位守卫（四部位化 F2 / 方案 §六 6.1）。
 *
 * 锻造是装备的唯一产出入口：配方 `part` 直接决定穿戴槽位。本守卫钉死：
 * 1. 全部配方的产出部位 ∈ [EquipmentSlot.displayOrder]（四部位）；
 * 2. 配方/部件 id 无退役部位后缀（`_WEAPON`/`_LEGS`）——防"改名保留"；
 * 3. 配方数 = 部件数（24），每套恰 4 部位（与 [EquipmentDatabase] 同源对偶）。
 */
class ForgeRecipeSlotGuardTest {

    @Test
    fun `全部配方产出部位在四部位集合内`() {
        val valid = EquipmentSlot.displayOrder.toSet()
        val offenders = ForgeRecipeDatabase.getAllRecipes()
            .filter { it.part !in valid }
            .map { "${it.id}→${it.part.name}" }
        assertTrue(
            "配方产出部位越界（只允许 头/身/手/脚）：$offenders。" +
                "处置：退役部位（WEAPON/LEGS）已随四部位化退役，禁新增其配方。",
            offenders.isEmpty()
        )
    }

    @Test
    fun `配方与部件id无退役部位后缀`() {
        val retiredSuffixes = listOf("_WEAPON", "_LEGS")
        val offenders = ForgeRecipeDatabase.getAllRecipes()
            .flatMap { r -> listOf(r.id, r.pieceId).map { id -> id to r } }
            .filter { (id, _) -> retiredSuffixes.any { id.uppercase().endsWith(it) } }
            .map { (id, r) -> "${r.id}:${id}" }
        assertTrue(
            "配方/部件 id 含退役部位后缀（防改名保留）：$offenders",
            offenders.isEmpty()
        )
    }

    @Test
    fun `配方数与部件数对偶且每套恰四部位`() {
        val recipes = ForgeRecipeDatabase.getAllRecipes()
        assertEquals(
            "配方数应为 24（6 套 × 4 部位，四部位化 F2）",
            24, recipes.size
        )
        assertEquals(
            "配方 pieceId 集合应与 EquipmentDatabase 部件集合一一对应（锻造产出面 = 部件全集）",
            // 部件全集 = 展开条目的 pieceId 去重（24 部件 × 6 品阶 → 24）
            EquipmentDatabase.allTemplates.values.map { it.pieceId }.toSet(),
            recipes.map { it.pieceId }.toSet()
        )
        val bySet = recipes.groupBy { it.setId }
        assertEquals("套装数应为 6", 6, bySet.size)
        bySet.forEach { (setId, setRecipes) ->
            assertEquals(
                "套装 $setId 应恰 4 部位（头/身/手/脚各一）",
                setOf("HEAD", "BODY", "HANDS", "FEET"),
                setRecipes.map { it.part.name }.toSet()
            )
        }
    }
}
