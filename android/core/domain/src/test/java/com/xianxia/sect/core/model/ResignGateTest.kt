package com.xianxia.sect.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [evaluateResignGate] 全部分支测试：17 状态 + 死亡/空闲分支。
 */
class ResignGateTest {

    private fun gate(status: DiscipleStatus, isAlive: Boolean = true) =
        evaluateResignGate(status, isAlive)

    // ==================== Disabled（按钮置灰） ====================

    @Test
    fun `IDLE returns Disabled`() {
        assertEquals(ResignGateResult.Disabled, gate(DiscipleStatus.IDLE))
    }

    @Test
    fun `DEAD returns Disabled`() {
        assertEquals(ResignGateResult.Disabled, gate(DiscipleStatus.DEAD))
    }

    @Test
    fun `dead disciple returns Disabled regardless of status`() {
        assertEquals(ResignGateResult.Disabled, gate(DiscipleStatus.MINING, isAlive = false))
        assertEquals(ResignGateResult.Disabled, gate(DiscipleStatus.REFINING, isAlive = false))
        assertEquals(ResignGateResult.Disabled, gate(DiscipleStatus.SECRET_REALM, isAlive = false))
    }

    // ==================== ConfirmRequired（二次确认） ====================

    @Test
    fun `REFINING returns ConfirmRequired with blood refinement message`() {
        val result = gate(DiscipleStatus.REFINING)
        assertTrue(
            "血炼应触发二次确认且文案必须明确告知卸任视为血炼失败",
            result is ResignGateResult.ConfirmRequired &&
                result.message.contains("血炼") && result.message.contains("失败")
        )
    }

    @Test
    fun `REFLECTING returns ConfirmRequired with release message`() {
        val result = gate(DiscipleStatus.REFLECTING)
        assertTrue(
            "监牢应触发二次确认且文案必须问是否释放",
            result is ResignGateResult.ConfirmRequired &&
                result.message.contains("监牢") && result.message.contains("释放")
        )
    }

    // ==================== Blocked（提示不可卸任） ====================

    @Test
    fun `ON_MISSION returns Blocked`() {
        val result = gate(DiscipleStatus.ON_MISSION)
        assertTrue(
            "任务中应阻塞卸任且文案提示任务",
            result is ResignGateResult.Blocked && result.message.contains("任务")
        )
    }

    @Test
    fun `SECRET_REALM returns Blocked`() {
        val result = gate(DiscipleStatus.SECRET_REALM)
        assertTrue(
            "秘境中应阻塞卸任且文案提示秘境",
            result is ResignGateResult.Blocked && result.message.contains("秘境")
        )
    }

    @Test
    fun `IN_TEAM returns Blocked`() {
        val result = gate(DiscipleStatus.IN_TEAM)
        assertTrue(
            "队伍中应阻塞卸任且文案提示队伍",
            result is ResignGateResult.Blocked && result.message.contains("队伍")
        )
    }

    // ==================== CanResign（直接卸任） ====================

    @Test
    fun `all duty statuses return CanResign`() {
        val dutyStatuses = listOf(
            DiscipleStatus.GARRISONING,
            DiscipleStatus.WAREHOUSE_GARRISON,
            DiscipleStatus.MANAGING,
            DiscipleStatus.LAW_ENFORCING,
            DiscipleStatus.PREACHING,
            DiscipleStatus.DEACONING,
            DiscipleStatus.STUDYING,
            DiscipleStatus.MINING,
            DiscipleStatus.PATROLLING,
            DiscipleStatus.ALCHEMY,
            DiscipleStatus.FORGE,
            DiscipleStatus.SPIRIT_PLANTING
        )
        dutyStatuses.forEach { status ->
            assertEquals("$status 应直接卸任", ResignGateResult.CanResign, gate(status))
        }
    }

    // ==================== 全枚举穷尽守卫 ====================

    @Test
    fun `all DiscipleStatus values produce a non-Disabled result when alive and on duty`() {
        // 穷尽守卫：新增状态必须显式决定卸任分流，禁止漏掉导致默认 CanResign
        DiscipleStatus.values().forEach { status ->
            if (status == DiscipleStatus.IDLE || status == DiscipleStatus.DEAD) return@forEach
            val result = gate(status)
            assertTrue(
                "状态 $status 的卸任分流必须是 CanResign/ConfirmRequired/Blocked 之一",
                result != ResignGateResult.Disabled
            )
        }
    }

    @Test
    fun `sealed result exhaustive when handles all four types`() {
        // 编译期守卫：when 穷尽 sealed interface 说明四态齐全
        val results = listOf(
            gate(DiscipleStatus.IDLE),
            gate(DiscipleStatus.REFINING),
            gate(DiscipleStatus.ON_MISSION),
            gate(DiscipleStatus.MINING)
        )
        var disabled = 0
        var confirm = 0
        var blocked = 0
        var canResign = 0
        results.forEach { result ->
            when (result) {
                is ResignGateResult.Disabled -> disabled++
                is ResignGateResult.ConfirmRequired -> confirm++
                is ResignGateResult.Blocked -> blocked++
                is ResignGateResult.CanResign -> canResign++
            }
        }
        assertEquals(1, disabled)
        assertEquals(1, confirm)
        assertEquals(1, blocked)
        assertEquals(1, canResign)
    }
}
