package com.xianxia.sect.core.engine.domain.disciple

import com.xianxia.sect.core.DamageType
import com.xianxia.sect.core.model.CharacterTemplateDb
import com.xianxia.sect.core.model.InnateDamageType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S21 · 固有伤害属性守卫（装备重构 B1 §15.3 Q1；五行属性伤害系统 2026-09-30 架构修正后口径）。
 *
 * **架构语义**：普攻伤害类型是**角色配置驱动**的数据（`CharacterTemplate.innateDamageType`
 * → 弟子创建时继承 → 战斗管线按 `innateDamageType` 判定普攻类型），不是架构恒等式。
 * **当前全部角色设定为物理**是内容现状；未来法术/五行普攻角色在模板配对应 `DamageType`
 * 值即可，届时本测试的「当前全物理」数据守卫按新设定同步更新。
 *
 * 守护面：
 * 1. **模板覆盖完整且当前全物理**——六条具名模板的 `innateDamageType` 全部非空、
 *    取值合法（`DamageType` 活跃值）且当前设定均为 PHYSICAL（内容设定数据守卫）；
 * 2. **派生幂等 + 兜底物理**——`InnateDamageType.derive` 对同一输入恒等（弟子
 *    按 templateId 幂等回填的前提）；模板缺失/未知 id 兜底 PHYSICAL（「首灵根
 *    物法二分派生」随 MAGIC 类型退役）。
 */
class InnateDamageTypeGuardTest {

    @Test
    fun `模板覆盖完整且当前全部设定为物理`() {
        assertTrue("模板表为空", CharacterTemplateDb.ALL.isNotEmpty())
        for (template in CharacterTemplateDb.ALL) {
            assertTrue(
                "模板 ${template.id} 的 innateDamageType 为空（创建时继承源缺失）",
                template.innateDamageType.isNotEmpty()
            )
            // 取值必须是 DamageType 合法 name（含退役段解析不炸）
            assertTrue(
                "模板 ${template.id} 的 innateDamageType=${template.innateDamageType} " +
                    "不是合法 DamageType name",
                DamageType.fromName(template.innateDamageType) != null
            )
            // 当前内容设定：全部角色普攻物理（未来加入非物理普攻角色时，按新设定更新本断言）
            assertEquals(
                "模板 ${template.id} 的 innateDamageType 与当前内容设定（全角色普攻物理）不一致",
                InnateDamageType.PHYSICAL, template.innateDamageType
            )
        }
    }

    @Test
    fun `derive 按模板命中且幂等`() {
        for (template in CharacterTemplateDb.ALL) {
            val first = InnateDamageType.derive(template.id, "fire")
            val second = InnateDamageType.derive(template.id, "fire")
            assertEquals(
                "模板 ${template.id} 的 derive 不幂等（旧档回填前提破坏）",
                first, second
            )
            assertEquals(
                "模板 ${template.id} 命中时应取模板值（灵根只是占位）",
                template.innateDamageType, first
            )
        }
    }

    @Test
    fun `模板缺失兜底物理`() {
        // 旧「首灵根物法二分派生」随 MAGIC 退役：无显式配置的弟子保守取物理
        for (root in listOf("metal", "wood", "water", "fire", "earth", "", "unknown")) {
            assertEquals(
                "无模板弟子首灵根 [$root] 应兜底物理",
                InnateDamageType.PHYSICAL, InnateDamageType.derive("", root)
            )
            assertEquals(
                "未知模板 id 应兜底物理（灵根不再参与派生）",
                InnateDamageType.PHYSICAL, InnateDamageType.derive("__no_such__", root)
            )
        }
    }
}
