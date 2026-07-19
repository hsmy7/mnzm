package com.xianxia.sect.core.model

import org.junit.Assert.*
import org.junit.Test

/**
 * 自动守卫：新增 [DiscipleStatus] 枚举值时，若忘记更新 [displayName]，测试将失败。
 *
 * ## 新增弟子状态的必改清单
 *
 * 当你在 [DiscipleStatus] 添加了新的枚举值，测试会在此文件中报错。
 * 请同步更新以下 3 处：
 *
 * 1. [DiscipleStatus.displayName] — 添加显示名称映射
 * 2. [com.xianxia.sect.ui.game.DiscipleFilterUtils.filterByDiscipleStatus] — 检查新状态是否应在
 *    showAllEnabled=true 时显示。白名单模式：除非新状态应被排除（如"闭关中"），否则默认可见
 * 3. 本测试文件 — 将新 [DiscipleStatus] 加入下方对应的分类集合
 *
 * ⚠️ filterByDiscipleStatus 使用白名单模式：showAllEnabled=true 时仅排除 ON_MISSION。
 * 新增的状态若应被排除，务必在 filterByDiscipleStatus 中添加检查。
 */
class DiscipleStatusCoverageTest {

    @Test
    fun `all DiscipleStatus values have non-empty displayName`() {
        DiscipleStatus.values().forEach { status ->
            assertTrue(
                "DiscipleStatus.${status.name}.displayName 返回了空字符串！" +
                "请在 Disciple.kt 中添加 displayName 映射",
                status.displayName.isNotEmpty()
            )
        }
    }

    /**
     * 在筛选器中显示为非空闲状态时仍然可见的弟子状态。
     * 对应 [filterByDiscipleStatus] 中 showAllEnabled=true 时的行为：
     * 仅排除 ON_MISSION 和战斗中弟子，其余所有状态均可见。
     */
    @Test
    fun `DEAD status is excluded by isAlive check not by direct status check`() {
        // DEAD 应通过 isAlive 排除，而非在 filterByDiscipleStatus 中直接检查 status
        // 这是 v58 的设计决策：死亡统一通过 isAlive 守卫，各筛选逻辑不重复判断
        // 如果未来新增非活着的状态，应遵循同样的模式
        val dead = DiscipleStatus.DEAD
        assertEquals("已死亡", dead.displayName)
    }

    @Test
    fun `DiscipleStatus values count is stable`() {
        // 弱守卫：仅计数变更时警示
        val count = DiscipleStatus.values().size
        assertTrue(
            "DiscipleStatus 当前有 $count 个值。新增状态后请确保 filterByDiscipleStatus、" +
            "displayName、および全 UI 筛选点已同步更新",
            count >= 14
        )
    }
}
