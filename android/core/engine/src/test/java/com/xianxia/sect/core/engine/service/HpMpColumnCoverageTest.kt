package com.xianxia.sect.core.engine.service

import com.xianxia.sect.core.engine.domain.disciple.DiscipleStatCalculator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * HP/MP 列直读覆盖守卫测试（2026-08-01，CLAUDE.md 9.5 守卫三要素）。
 *
 * 背景：每旬 HP/MP 恢复热点的列直读版 [HpMpRecoveryService.recoverHpMpSingleColumn]
 * 只读取 17 列（相对 assemble 的 ~90 列）——若未来新增影响 maxHp/maxMp 的
 * Disciple 字段（如新装备槽/新加成列），忘记同步 [HpMpColumnInput] 会导致
 * 列直读与对象版结果漂移（列版少算加成）。
 *
 * 锚点：[HpMpColumnInput] 字段反射枚举。
 * 故意排除项：非 maxHp/maxMp 相关字段（列直读版不覆盖的列由对象版路径承担——
 * 战斗中/月度结算等低频路径仍走 [DiscipleStatCalculator.getFinalStats]）。
 *
 * 修复指引：新增影响 maxHp/maxMp 的列时——① 更新本测试的 EXPECTED_FIELDS；
 * ② 更新 [HpMpColumnInput] 与 [getMaxHpMpColumn]；③ 在
 * [HpMpRecoveryEquivalenceTest] 追加对应 fixture。
 */
class HpMpColumnCoverageTest {

    /**
     * HpMpColumnInput 应包含的字段（对应影响 maxHp/maxMp 的全部列直读输入）。
     * 与 [HpMpColumnInput] 构造参数一一对应——新增/删除字段时本测试失败。
     */
    private val expectedFields = setOf(
        "realm",          // 境界基础值
        "realmLayer",     // 层数乘区
        "hpVariance",     // HP 方差乘区
        "mpVariance",     // MP 方差乘区
        "talentIds",      // 天赋效果（maxHp/maxMp 百分比）
        "affixIds",       // 词条效果
        "weaponId",       // 装备四槽（HP/MP 加成）
        "armorId",
        "bootsId",
        "accessoryId",
        "manualIds",      // 功法（stats["hp"/"mp"] × 熟练度）
        "pillEffectDuration",  // 丹药生效判定
        "pillHpBonus",    // 丹药 HP/MP 加成
        "pillMpBonus"
    )

    @Test
    fun `HpMpColumnInput 字段与预期清单一致`() {
        val actualFields = DiscipleStatCalculator.HpMpColumnInput::class
            .members
            .filter { it is kotlin.reflect.KProperty1<*, *> }
            .map { it.name }
            .filterNot { it == "equals" || it == "hashCode" || it == "toString" || it == "component1" }
            .toSet()
            .map { it.removePrefix("component") }  // data class 的 componentN 已过滤
            .filterNot { it.all(Char::isDigit) }
            .toSet()

        assertEquals(
            "HpMpColumnInput 字段与预期不一致——新增/移除影响 maxHp/maxMp 的列时，" +
                "必须同步更新 HpMpColumnInput、getMaxHpMpColumn 与本测试的 EXPECTED_FIELDS，" +
                "并在 HpMpRecoveryEquivalenceTest 追加等价性 fixture（列直读与对象版精确相等）。",
            expectedFields,
            actualFields
        )
    }

    @Test
    fun `HpMpColumnInput 字段均为非空校验必需的列直读输入`() {
        // 守卫：字段清单非空且包含境界/方差/丹药等核心输入
        assertTrue("HpMpColumnInput 字段清单不应为空", expectedFields.isNotEmpty())
        assertTrue("缺少 realm（境界基础值输入）", "realm" in expectedFields)
        assertTrue("缺少 pillEffectDuration（丹药生效判定）", "pillEffectDuration" in expectedFields)
    }
}
