package com.xianxia.sect.core.registry

import com.xianxia.sect.core.model.MaterialCategory
import com.xianxia.sect.core.model.PillCategory
import com.xianxia.sect.core.model.PillGrade
import org.junit.Assert.*
import org.junit.Test

class ItemDatabaseTest {

    // ============================================================
    // getAllPills / getAllMaterials 基本非空验证
    // ============================================================

    @Test
    fun `getAllPills返回非空列表`() {
        val pills = ItemDatabase.allPills
        assertTrue("丹药列表不应为空", pills.isNotEmpty())
    }

    @Test
    fun `getAllMaterials返回非空列表`() {
        val materials = ItemDatabase.allMaterials
        assertTrue("材料列表不应为空", materials.isNotEmpty())
    }

    // ============================================================
    // getPillById 查询
    // ============================================================

    @Test
    fun `getPillById已知id返回对应模板`() {
        val firstPill = ItemDatabase.allPills.values.first()
        val result = ItemDatabase.getPillById(firstPill.id)
        assertNotNull("已知id应返回模板", result)
        assertEquals(firstPill.id, result!!.id)
    }

    @Test
    fun `getPillById未知id返回null`() {
        val result = ItemDatabase.getPillById("nonexistent_pill_id")
        assertNull("未知id应返回null", result)
    }

    // ============================================================
    // getMaterialById 查询
    // ============================================================

    @Test
    fun `getMaterialById已知id返回对应模板`() {
        val firstMaterial = ItemDatabase.allMaterials.values.first()
        val result = ItemDatabase.getMaterialById(firstMaterial.id)
        assertNotNull("已知id应返回模板", result)
        assertEquals(firstMaterial.id, result!!.id)
    }

    @Test
    fun `getMaterialById未知id返回null`() {
        val result = ItemDatabase.getMaterialById("nonexistent_material_id")
        assertNull("未知id应返回null", result)
    }

    // ============================================================
    // getPillsByRarity 稀有度过滤
    // ============================================================

    @Test
    fun `getPillsByRarity返回指定稀有度的丹药`() {
        val rarity = 1
        val pills = ItemDatabase.getPillsByRarity(rarity)
        assertTrue("稀有度1的丹药不应为空", pills.isNotEmpty())
        pills.forEach { pill ->
            assertEquals("丹药${pill.id}的稀有度应为$rarity", rarity, pill.rarity)
        }
    }

    @Test
    fun `getPillsByRarity传入0返回空列表`() {
        val pills = ItemDatabase.getPillsByRarity(0)
        assertTrue("稀有度0的丹药应为空列表", pills.isEmpty())
    }

    // ============================================================
    // getMaterialsByRarity 稀有度过滤
    // ============================================================

    @Test
    fun `getMaterialsByCategory返回指定类别的材料`() {
        val category = MaterialCategory.BEAST_HIDE
        val materials = ItemDatabase.getMaterialsByCategory(category)
        assertTrue("兽皮类材料不应为空", materials.isNotEmpty())
        materials.forEach { mat ->
            assertEquals("材料${mat.id}的类别应为$category", category, mat.category)
        }
    }

    // ============================================================
    // 所有丹药数据完整性
    // ============================================================

    @Test
    fun `所有丹药的稀有度在1到6范围内`() {
        ItemDatabase.allPills.values.forEach { pill ->
            assertTrue(
                "丹药${pill.id}的稀有度${pill.rarity}不在1-6范围内",
                pill.rarity in 1..6
            )
        }
    }

    @Test
    fun `所有丹药的id和name非空`() {
        ItemDatabase.allPills.values.forEach { pill ->
            assertTrue("丹药id不应为空", pill.id.isNotBlank())
            assertTrue("丹药name不应为空", pill.name.isNotBlank())
        }
    }

    @Test
    fun `丹药id应唯一`() {
        val ids = ItemDatabase.allPills.values.map { it.id }
        val uniqueIds = ids.toSet()
        assertEquals("丹药id应唯一，存在重复", uniqueIds.size, ids.size)
    }

    // ============================================================
    // 所有材料数据完整性
    // ============================================================

    @Test
    fun `所有材料的稀有度在1到6范围内`() {
        ItemDatabase.allMaterials.values.forEach { mat ->
            assertTrue(
                "材料${mat.id}的稀有度${mat.rarity}不在1-6范围内",
                mat.rarity in 1..6
            )
        }
    }

    @Test
    fun `所有材料的id和name非空`() {
        ItemDatabase.allMaterials.values.forEach { mat ->
            assertTrue("材料id不应为空", mat.id.isNotBlank())
            assertTrue("材料name不应为空", mat.name.isNotBlank())
        }
    }

    @Test
    fun `材料id应唯一`() {
        val ids = ItemDatabase.allMaterials.values.map { it.id }
        val uniqueIds = ids.toSet()
        assertEquals("材料id应唯一，存在重复", uniqueIds.size, ids.size)
    }

    // ============================================================
    // createPillFromTemplate
    // ============================================================

    @Test
    fun `createPillFromTemplate从模板创建Pill字段正确`() {
        val template = ItemDatabase.allPills.values.first()
        val pill = ItemDatabase.createPillFromTemplate(template)

        assertEquals(template.name, pill.name)
        assertEquals(template.rarity, pill.rarity)
        assertEquals(template.description, pill.description)
        assertEquals(template.category, pill.category)
        assertEquals(template.grade, pill.grade)
        assertEquals(template.pillType, pill.pillType)
        assertEquals(template.minRealm, pill.minRealm)
        // 新创建的Pill应有不同的UUID id
        assertNotEquals("Pill实例id应不同于模板id", template.id, pill.id)
        assertEquals(1, pill.quantity)
    }

    // ============================================================
    // createMaterialFromTemplate
    // ============================================================

    @Test
    fun `createMaterialFromTemplate从模板创建Material字段正确`() {
        val template = ItemDatabase.allMaterials.values.first()
        val material = ItemDatabase.createMaterialFromTemplate(template)

        assertEquals(template.name, material.name)
        assertEquals(template.rarity, material.rarity)
        assertEquals(template.description, material.description)
        assertEquals(template.category, material.category)
        assertNotEquals("Material实例id应不同于模板id", template.id, material.id)
        assertEquals(1, material.quantity)
    }
}
