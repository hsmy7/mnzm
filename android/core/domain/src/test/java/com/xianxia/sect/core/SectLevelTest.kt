package com.xianxia.sect.core

import org.junit.Assert.*
import org.junit.Test

class SectLevelTest {

    // ── recruitRange() ──────────────────────────────────────────

    @Test
    fun `recruitRange - 小型宗门返回0到5`() {
        val range = SectLevel.recruitRange(SectLevel.SMALL)
        assertEquals(0, range.first)
        assertEquals(5, range.last)
    }

    @Test
    fun `recruitRange - 中型宗门返回1到8`() {
        val range = SectLevel.recruitRange(SectLevel.MEDIUM)
        assertEquals(1, range.first)
        assertEquals(8, range.last)
    }

    @Test
    fun `recruitRange - 大型宗门返回3到15`() {
        val range = SectLevel.recruitRange(SectLevel.LARGE)
        assertEquals(3, range.first)
        assertEquals(15, range.last)
    }

    @Test
    fun `recruitRange - 顶级宗门返回6到20`() {
        val range = SectLevel.recruitRange(SectLevel.TOP)
        assertEquals(6, range.first)
        assertEquals(20, range.last)
    }

    @Test
    fun `recruitRange - 非法等级兜底返回小型范围0到5`() {
        val range = SectLevel.recruitRange(99)
        assertEquals(0, range.first)
        assertEquals(5, range.last)
    }

    // ── 已有函数的回归测试 ──────────────────────────────────────

    @Test
    fun `levelName - 四个等级的名称正确`() {
        assertEquals("小型宗门", SectLevel.levelName(SectLevel.SMALL))
        assertEquals("中型宗门", SectLevel.levelName(SectLevel.MEDIUM))
        assertEquals("大型宗门", SectLevel.levelName(SectLevel.LARGE))
        assertEquals("顶级宗门", SectLevel.levelName(SectLevel.TOP))
    }

    @Test
    fun `fromHighestRealm - 各境界映射到正确等级`() {
        assertEquals(SectLevel.SMALL, SectLevel.fromHighestRealm(9))  // 炼气
        assertEquals(SectLevel.SMALL, SectLevel.fromHighestRealm(6))  // 元婴
        assertEquals(SectLevel.MEDIUM, SectLevel.fromHighestRealm(5)) // 化神
        assertEquals(SectLevel.LARGE, SectLevel.fromHighestRealm(4))  // 炼虚
        assertEquals(SectLevel.LARGE, SectLevel.fromHighestRealm(3))  // 合体
        assertEquals(SectLevel.TOP, SectLevel.fromHighestRealm(2))    // 大乘
        assertEquals(SectLevel.TOP, SectLevel.fromHighestRealm(1))    // 渡劫
        assertEquals(SectLevel.TOP, SectLevel.fromHighestRealm(0))    // 仙人
    }
}
