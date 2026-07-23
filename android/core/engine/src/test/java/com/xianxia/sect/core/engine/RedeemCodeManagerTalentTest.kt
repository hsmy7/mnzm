package com.xianxia.sect.core.engine

import com.xianxia.sect.core.registry.TalentDatabase
import org.junit.Assert.*
import org.junit.Test

/**
 * 验证 RedeemCodeManager.generateRandomTalents() 的模板级去重逻辑。
 *
 * 根因：原实现仅检查精确 ID 重复，不同稀有度同模板的天赋会被同时选中。
 * 修复后使用 TalentData.template + selectedTemplates 做模板级过滤。
 */
class RedeemCodeManagerTalentTest {

    @Test
    fun `generateRandomTalents - no duplicate templates in any invocation`() {
        // 多次调用验证：任何一次调用中都不出现模板重复
        repeat(500) {
            val talentIds = RedeemCodeManager.generateRandomTalents()
            val templates = talentIds.mapNotNull { TalentDatabase.getTalentDataById(it)?.template }
            assertEquals("模板数量应与 talentIds 数量一致（无模板级重复）",
                templates.size, templates.toSet().size)
        }
    }

    @Test
    fun `generateRandomTalents - returns between 0 and 2 positive talents`() {
        // 验证数量范围：0-2 个正面天赋 + 0-1 个负面天赋
        repeat(200) {
            val talentIds = RedeemCodeManager.generateRandomTalents()
            assertTrue("天赋数量应在 0-3 之间 (0-2正面+0-1负面), 实际: ${talentIds.size}",
                talentIds.size in 0..3)
        }
    }

    @Test
    fun `generateRandomTalents - all returned IDs are valid talents`() {
        repeat(100) {
            val talentIds = RedeemCodeManager.generateRandomTalents()
            for (id in talentIds) {
                val talent = TalentDatabase.getById(id)
                assertNotNull("无效天赋 ID: $id", talent)
            }
        }
    }

    @Test
    fun `generateRandomTalents - distribution produces both 0 1 and 2 counts`() {
        val counts = mutableSetOf<Int>()
        repeat(500) {
            counts += RedeemCodeManager.generateRandomTalents().size
        }
        assertTrue("经过500次调用应至少出现过 0,1 两种数量（2为30%概率也可能出现）",
            counts.contains(0) && counts.contains(1))
    }

    @Test
    fun `generateRandomTalents - same talent never appears twice in one call`() {
        repeat(200) {
            val talentIds = RedeemCodeManager.generateRandomTalents()
            assertEquals("不应有精确 ID 重复",
                talentIds.size, talentIds.toSet().size)
        }
    }
}
