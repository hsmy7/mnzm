package com.xianxia.sect.core.state

import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.SkillStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 半幽灵防御一致性测试（2026-08-01 F3 修复验证）。
 *
 * 修复前：assembleAll/assembleAllIncremental 做三表检查（isAlive + names + realms），
 * 但 deepCopy 只按 isAlive 单表过滤——isAlive 有而 names/realms 缺的半幽灵会进入
 * 快照 ids，任何直接遍历 ids 的代码（count/checkpointAllDisciples/cullDeadDisciples）
 * 都能看到。修复后三处统一 isCompleteId 三表判据。
 *
 * 本测试守卫：
 * 1. 四类幽灵（isAlive 缺 / names 缺 / realms 缺 / 空名）在三条组装路径的行为
 * 2. deepCopy().assembleAll() 与 assembleAll() 输出等价（不一致即失败）
 * 3. 空名弟子（三表齐全）在 deepCopy 中保留——与 assembleAll 的空名防御构成
 *    有意差异，组合保证 UI 永不见空名/半幽灵
 */
@RunWith(RobolectricTestRunner::class)
class DiscipleTablesGhostDefenseTest {

    @get:Rule val writeGuardRule = WriteGuardRule()

    private fun disciple(id: Int, name: String = "弟子$id"): Disciple =
        Disciple(
            id = id.toString(),
            name = name,
            realm = 5,
            cultivation = 100.0 * id,
            skills = SkillStats(loyalty = 50)
        )

    /** 构造 5 个弟子：A/B/C 为半幽灵（单表缺失），D 为空名（三表齐全），normal 正常 */
    private fun buildGhostFixture(): DiscipleTables {
        val tables = DiscipleTables()
        tables.insert(disciple(1))          // ghostA：isAlive 缺失
        tables.insert(disciple(2))          // ghostB：names 缺失
        tables.insert(disciple(3))          // ghostC：realms 缺失
        tables.insert(disciple(4, name = " ")) // ghostD：空名（三表齐全）
        tables.insert(disciple(5))          // normal
        tables.isAlive.remove(1)
        tables.names.remove(2)
        tables.realms.remove(3)
        return tables
    }

    @Test
    fun `assembleAll 跳过三类半幽灵与空名弟子，仅返回正常弟子`() {
        val tables = buildGhostFixture()

        val result = tables.assembleAll()

        assertEquals("半幽灵/空名/正常共 5 人，仅正常 1 人可见", 1, result.size)
        assertEquals("可见弟子为 normal（id=5）", "5", result[0].id)
    }

    @Test
    fun `assembleAllIncremental 跳过三类半幽灵（空名保留——与 assembleAll 的有意差异）`() {
        val tables = buildGhostFixture()

        val result = tables.assembleAllIncremental(
            prevSnapshot = emptyList(),
            changedIds = setOf(1, 2, 3, 4, 5)
        )

        // 三表判据对齐后半幽灵被跳过；空名防御仅 assembleAll 独有（增量路径不检查）
        assertEquals("半幽灵剔除、空名保留", listOf("4", "5"), result.map { it.id })
    }

    @Test
    fun `deepCopy 三表过滤：剔除半幽灵但保留空名与正常弟子`() {
        val tables = buildGhostFixture()

        val copyIds = tables.deepCopy().ids

        assertFalse("isAlive 缺失的半幽灵（id=1）不应进入快照 ids", copyIds.contains(1))
        assertFalse("names 缺失的半幽灵（id=2）不应进入快照 ids", copyIds.contains(2))
        assertFalse("realms 缺失的半幽灵（id=3）不应进入快照 ids", copyIds.contains(3))
        assertTrue("空名弟子（id=4，三表齐全）应保留", copyIds.contains(4))
        assertTrue("正常弟子（id=5）应保留", copyIds.contains(5))
    }

    @Test
    fun `不变量：deepCopy 后 assembleAll 与源表 assembleAll 输出等价`() {
        val tables = buildGhostFixture()

        val source = tables.assembleAll()
        val copy = tables.deepCopy().assembleAll()

        assertEquals("两组输出数量一致", source.size, copy.size)
        assertEquals("两组输出 id 列表一致", source.map { it.id }, copy.map { it.id })
        for (i in source.indices) {
            assertEquals("id=${source[i].id} name 一致", source[i].name, copy[i].name)
            assertEquals("id=${source[i].id} realm 一致", source[i].realm, copy[i].realm)
            assertEquals(
                "id=${source[i].id} cultivation 一致",
                source[i].cultivation, copy[i].cultivation, 1e-9
            )
        }
    }

    @Test
    fun `正常弟子数据在 deepCopy 快照上组装完整`() {
        val tables = DiscipleTables()
        tables.insert(disciple(5, name = "张三"))
        tables.cultivations[5] = 888.0

        val snapshot = tables.deepCopy()
        val assembled = snapshot.assembleAll()

        assertEquals(1, assembled.size)
        assertEquals("张三", assembled[0].name)
        assertEquals(888.0, assembled[0].cultivation, 1e-9)
    }
}
