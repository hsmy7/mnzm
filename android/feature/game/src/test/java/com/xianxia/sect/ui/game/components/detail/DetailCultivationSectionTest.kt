package com.xianxia.sect.ui.game.components.detail

import com.xianxia.sect.core.model.DiscipleAggregate
import com.xianxia.sect.core.model.DiscipleCombatStats
import com.xianxia.sect.core.model.DiscipleCore
import org.junit.Assert.*
import org.junit.Test

/**
 * 测试 HpMpBars 所依赖的 DiscipleAggregate HP/MP 数据映射逻辑。
 *
 * HpMpBars 中的核心计算：
 *   maxHp = maxHpOverride ?: disciple.maxHp
 *   currentHpDisplay = if (rawCurrentHp < 0) maxHp else rawCurrentHp
 *   hpFraction = (currentHpDisplay / maxHp).coerceIn(0,1)，maxHp==0 时 fallback 为 1
 *
 * 动画层（LaunchedEffect）需 Compose 测试框架，此处验证数据层正确性。
 */
class DetailCultivationSectionTest {

    // ---- DiscipleAggregate HP/MP 数据映射 ----

    @Test
    fun `currentHp - negative sentinel means full HP`() {
        val d = createAggregate(currentHp = -1, baseHp = 200)
        // sentinel -1 表示“无伤”，UI 层应映射为 maxHp
        assertTrue("负值 sentinel 必须被保留使 UI 层可识别为满血",
            d.currentHp < 0)
    }

    @Test
    fun `currentHp - zero means zero HP`() {
        val d = createAggregate(currentHp = 0, baseHp = 200)
        assertEquals(0, d.currentHp)
    }

    @Test
    fun `currentHp - positive value returned as-is`() {
        val d = createAggregate(currentHp = 150, baseHp = 200)
        assertEquals(150, d.currentHp)
    }

    @Test
    fun `currentHp - equals maxHp returns maxHp`() {
        val d = createAggregate(currentHp = 200, baseHp = 200)
        assertEquals(200, d.currentHp)
    }

    @Test
    fun `currentMp - negative sentinel symmetry with HP`() {
        val d = createAggregate(currentMp = -1, baseMp = 100)
        assertTrue("灵力也应使用负值 sentinel 表示满值",
            d.currentMp < 0)
    }

    // ---- sentinel → max 映射逻辑（HpMpBars 第 398-399 行） ----

    @Test
    fun `display logic - negative HP maps to maxHp`() {
        val d = createAggregate(currentHp = -1, baseHp = 300)
        // 模拟 HpMpBars 中的映射逻辑（第 398 行）
        val maxHp = 300
        val raw = d.currentHp
        assertEquals(-1, raw)
        val display = if (raw < 0) maxHp else raw
        assertEquals("sentinel -1 应映射为 maxHp 用于显示", maxHp, display)
    }

    @Test
    fun `display logic - positive HP displayed as-is`() {
        val d = createAggregate(currentHp = 50, baseHp = 300)
        val maxHp = 300
        val raw = d.currentHp
        assertEquals(50, raw)
        val display = if (raw < 0) maxHp else raw
        assertEquals(50, display)
    }

    // ---- hpFraction 计算（HpMpBars 第 400-401 行） ----

    @Test
    fun `fraction - full HP equals 1f`() {
        val currentHpDisplay = 200
        val maxHp = 200
        val fraction = (currentHpDisplay.toFloat() / maxHp).coerceIn(0f, 1f)
        assertEquals(1f, fraction)
    }

    @Test
    fun `fraction - half HP equals 0_5f`() {
        val fraction = (100f / 200).coerceIn(0f, 1f)
        assertEquals(0.5f, fraction)
    }

    @Test
    fun `fraction - zero HP equals 0f`() {
        val fraction = (0f / 200).coerceIn(0f, 1f)
        assertEquals(0f, fraction)
    }

    @Test
    fun `fraction - maxHp zero fallback to 1f no crash`() {
        // HpMpBars 第 400 行：if (maxHp > 0) ... else 1f
        val maxHp = 0
        val fraction = if (maxHp > 0) (150f / maxHp).coerceIn(0f, 1f) else 1f
        assertEquals(1f, fraction)
    }

    @Test
    fun `fraction - over-max HP clamped to 1f`() {
        // 防御性：currentHp > maxHp 时 coerceIn 限制为 1f
        val fraction = (250f / 200).coerceIn(0f, 1f)
        assertEquals(1f, fraction)
    }

    // ---- 辅助方法 ----

    private fun createAggregate(
        currentHp: Int = -1,
        currentMp: Int = -1,
        baseHp: Int = 200,
        baseMp: Int = 100
    ): DiscipleAggregate {
        val core = DiscipleCore(
            id = "test-disciple-1",
            name = "测试弟子",
            age = 18,
            realm = 5
        )
        val combat = DiscipleCombatStats(
            discipleId = "test-disciple-1",
            currentHp = currentHp,
            currentMp = currentMp,
            baseHp = baseHp,
            baseMp = baseMp
        )
        return DiscipleAggregate(
            core = core,
            combatStats = combat,
            equipment = null,
            extended = null,
            attributes = null
        )
    }
}
