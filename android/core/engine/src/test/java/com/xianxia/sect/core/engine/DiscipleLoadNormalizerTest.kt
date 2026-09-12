package com.xianxia.sect.core.engine

import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.SkillStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [normalizeDiscipleIds] 读档弟子 id 归一化测试。
 *
 * 回归守卫（Bugly #5079/#3091）：旧存档 DiscipleSerializer surrogate 缺 id
 * 字段时默认空串，损坏存档可能全体弟子空 id / 重复 id——直接进入
 * LazyVerticalGrid 会因 key="" 重复崩溃。引擎读档时必须净化。
 */
class DiscipleLoadNormalizerTest {

    private fun disciple(id: String, name: String = "弟子"): Disciple {
        return Disciple(
            id = id,
            name = name,
            realm = 9,
            isAlive = true,
            discipleType = "outer",
            skills = SkillStats(loyalty = 50)
        )
    }

    @Test
    fun `normalizeDiscipleIds - 正常弟子保持原样`() {
        val disciples = listOf(
            disciple("d1", "弟子1"),
            disciple("d2", "弟子2")
        )
        val result = normalizeDiscipleIds(disciples)
        assertEquals(listOf("d1", "d2"), result.map { it.id })
        assertEquals("正常数据不应产生副本", 2, result.size)
    }

    @Test
    fun `normalizeDiscipleIds - 空 id 弟子重分配新 UUID 而非删除`() {
        val disciples = listOf(
            disciple("", "无id弟子"),
            disciple("d2", "弟子2")
        )
        val result = normalizeDiscipleIds(disciples)
        assertEquals("空 id 弟子不应被删除", 2, result.size)
        val normalized = result.first { it.name == "无id弟子" }
        assertFalse("空 id 必须被重分配", normalized.id.isBlank())
        assertNotEquals("重分配 id 不能仍是空串", "", normalized.id)
        assertTrue("重分配 id 应为 UUID 格式", normalized.id.matches(
            Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
        ))
        assertEquals("重分配后 id 唯一", 2, result.map { it.id }.distinct().size)
    }

    @Test
    fun `normalizeDiscipleIds - 重复 id 去重保留首个`() {
        val disciples = listOf(
            disciple("dup", "首个"),
            disciple("dup", "重复副本"),
            disciple("d3", "弟子3")
        )
        val result = normalizeDiscipleIds(disciples)
        assertEquals("重复 id 应去重", 2, result.size)
        assertEquals("保留首个（数据顺序稳定）", listOf("dup", "d3"), result.map { it.id })
        assertEquals("保留的应是首个条目", "首个", result.first { it.id == "dup" }.name)
    }

    @Test
    fun `normalizeDiscipleIds - 空 id 与重复 id 混合时结果唯一且非空`() {
        val disciples = listOf(
            disciple("", "空id1"),
            disciple("dup", "重复1"),
            disciple("dup", "重复2"),
            disciple("", "空id2")
        )
        val result = normalizeDiscipleIds(disciples)
        assertTrue("全部 id 非空", result.all { it.id.isNotBlank() })
        assertEquals("id 全部唯一", result.size, result.map { it.id }.distinct().size)
    }
}
