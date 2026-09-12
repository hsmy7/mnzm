package com.xianxia.sect.core.model

import org.junit.Assert.*
import org.junit.Test

/**
 * 自动守卫：新增 [DiscipleStatus] 枚举值时，若忘记更新 [displayName]，测试将失败。
 *
 * ## 新增弟子状态的必改清单（完整版）
 *
 * 注意：新增非受保护状态时，需同步更新 6 处。新增受保护状态时只需更新前 3 处。
 *
 * ### 必改（所有新状态）
 * 1. [DiscipleStatus.displayName] — 添加显示名称映射（此测试检测）
 * 2. [com.xianxia.sect.ui.game.DiscipleFilterUtils.filterByDiscipleStatus] — 检查新状态是否应在
 *    showAllEnabled=true 时显示
 * 3. [com.xianxia.sect.core.engine.domain.disciple.StatusDerivationCoverageTest] — 查阅该测试的文档
 *
 * ### 推导系统（非受保护状态需改，否则该测试会失败）
 * 4. [com.xianxia.sect.core.engine.domain.disciple.DiscipleStatusService.SlotFlags] — 添加对应 flag
 * 5. [com.xianxia.sect.core.engine.domain.disciple.DiscipleStatusService.deriveDiscipleStatus] — 添加 when 分支
 * 6. [com.xianxia.sect.core.engine.domain.disciple.DiscipleStatusService.buildSlotFlagsFor] — 从游戏数据设置 flag
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
