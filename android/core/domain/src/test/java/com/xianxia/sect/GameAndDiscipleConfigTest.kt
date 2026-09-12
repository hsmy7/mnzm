package com.xianxia.sect

import com.xianxia.sect.core.GameConfig
import org.junit.Assert.*
import org.junit.Test

class GameAndDiscipleConfigTest {
    // ============================================================
    // Game 对象
    // ============================================================

    @Test
    fun `游戏名称应为模拟宗门`() {
        assertEquals("模拟宗门", GameConfig.Game.NAME)
    }

    @Test
    fun `游戏版本应为非空字符串且格式正确`() {
        val version = GameConfig.Game.VERSION
        assertTrue("版本号不应为空", version.isNotEmpty())
        assertTrue("版本号应符合语义化版本格式 (x.y.z)", version.matches(Regex("\\d+\\.\\d+\\.\\d+")))
    }

    @Test
    fun `最大存档槽位应为5`() {
        assertEquals(5, GameConfig.Game.MAX_SAVE_SLOTS)
    }

    // ============================================================
    // Disciple 对象
    // ============================================================

    @Test
    fun `忠诚度最小值应为0`() {
        assertEquals(0, GameConfig.Disciple.MIN_LOYALTY)
    }

    @Test
    fun `忠诚度最大值应为100`() {
        assertEquals(100, GameConfig.Disciple.MAX_LOYALTY)
    }

    @Test
    fun `年龄最小值应为5`() {
        assertEquals(5, GameConfig.Disciple.MIN_AGE)
    }

    @Test
    fun `年龄最大值应为100`() {
        assertEquals(100, GameConfig.Disciple.MAX_AGE)
    }
}
