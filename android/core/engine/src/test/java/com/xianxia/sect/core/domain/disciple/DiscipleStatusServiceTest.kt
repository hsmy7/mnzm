package com.xianxia.sect.core.engine.domain.disciple

import com.xianxia.sect.core.model.DiscipleStatus
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [DiscipleStatusService.deriveDiscipleStatus] 纯函数单元测试。
 *
 * 覆盖：
 * - 死亡 → DEAD
 * - 三种受保护状态（REFLECTING / ON_MISSION / REFINING）不被覆盖
 * - 所有 12 种槽位类型按优先级推导
 * - 无分配 → IDLE
 * - 多槽位同时占用时按优先级取第一个
 */
class DiscipleStatusServiceTest {

    // ==================== 死亡 ====================

    @Test
    fun `deriveDiscipleStatus - dead disciple returns DEAD regardless of slots`() {
        assertEquals(
            DiscipleStatus.DEAD,
            DiscipleStatusService.deriveDiscipleStatus(
                isAlive = false,
                currentStatus = DiscipleStatus.IDLE,
                slotFlags = DiscipleStatusService.SlotFlags(inGarrison = true)
            )
        )
    }

    @Test
    fun `deriveDiscipleStatus - dead disciple returns DEAD even if protected`() {
        assertEquals(
            DiscipleStatus.DEAD,
            DiscipleStatusService.deriveDiscipleStatus(
                isAlive = false,
                currentStatus = DiscipleStatus.REFLECTING,
                slotFlags = DiscipleStatusService.SlotFlags()
            )
        )
    }

    // ==================== 受保护状态 ====================

    @Test
    fun `deriveDiscipleStatus - REFLECTING is preserved`() {
        assertEquals(
            DiscipleStatus.REFLECTING,
            DiscipleStatusService.deriveDiscipleStatus(
                isAlive = true,
                currentStatus = DiscipleStatus.REFLECTING,
                slotFlags = DiscipleStatusService.SlotFlags()
            )
        )
    }

    @Test
    fun `deriveDiscipleStatus - REFLECTING preserved even with slot assignments`() {
        assertEquals(
            DiscipleStatus.REFLECTING,
            DiscipleStatusService.deriveDiscipleStatus(
                isAlive = true,
                currentStatus = DiscipleStatus.REFLECTING,
                slotFlags = DiscipleStatusService.SlotFlags(inGarrison = true, mining = true, alchemy = true)
            )
        )
    }

    @Test
    fun `deriveDiscipleStatus - ON_MISSION is preserved`() {
        assertEquals(
            DiscipleStatus.ON_MISSION,
            DiscipleStatusService.deriveDiscipleStatus(
                isAlive = true,
                currentStatus = DiscipleStatus.ON_MISSION,
                slotFlags = DiscipleStatusService.SlotFlags()
            )
        )
    }

    @Test
    fun `deriveDiscipleStatus - ON_MISSION preserved even with slot assignments`() {
        assertEquals(
            DiscipleStatus.ON_MISSION,
            DiscipleStatusService.deriveDiscipleStatus(
                isAlive = true,
                currentStatus = DiscipleStatus.ON_MISSION,
                slotFlags = DiscipleStatusService.SlotFlags(studying = true, patrolling = true)
            )
        )
    }

    @Test
    fun `deriveDiscipleStatus - REFINING is preserved`() {
        assertEquals(
            DiscipleStatus.REFINING,
            DiscipleStatusService.deriveDiscipleStatus(
                isAlive = true,
                currentStatus = DiscipleStatus.REFINING,
                slotFlags = DiscipleStatusService.SlotFlags()
            )
        )
    }

    @Test
    fun `deriveDiscipleStatus - REFINING preserved even with slot assignments`() {
        assertEquals(
            DiscipleStatus.REFINING,
            DiscipleStatusService.deriveDiscipleStatus(
                isAlive = true,
                currentStatus = DiscipleStatus.REFINING,
                slotFlags = DiscipleStatusService.SlotFlags(managing = true)
            )
        )
    }

    // ==================== 单槽位类型 ====================

    @Test
    fun `deriveDiscipleStatus - inGarrison returns GARRISONING`() {
        assertEquals(
            DiscipleStatus.GARRISONING,
            DiscipleStatusService.deriveDiscipleStatus(
                isAlive = true,
                currentStatus = DiscipleStatus.IDLE,
                slotFlags = DiscipleStatusService.SlotFlags(inGarrison = true)
            )
        )
    }

    @Test
    fun `deriveDiscipleStatus - inTeam returns IN_TEAM`() {
        assertEquals(
            DiscipleStatus.IN_TEAM,
            DiscipleStatusService.deriveDiscipleStatus(
                isAlive = true,
                currentStatus = DiscipleStatus.IDLE,
                slotFlags = DiscipleStatusService.SlotFlags(inTeam = true)
            )
        )
    }

    @Test
    fun `deriveDiscipleStatus - lawEnforcing returns LAW_ENFORCING`() {
        assertEquals(
            DiscipleStatus.LAW_ENFORCING,
            DiscipleStatusService.deriveDiscipleStatus(
                isAlive = true,
                currentStatus = DiscipleStatus.IDLE,
                slotFlags = DiscipleStatusService.SlotFlags(lawEnforcing = true)
            )
        )
    }

    @Test
    fun `deriveDiscipleStatus - preaching returns PREACHING`() {
        assertEquals(
            DiscipleStatus.PREACHING,
            DiscipleStatusService.deriveDiscipleStatus(
                isAlive = true,
                currentStatus = DiscipleStatus.IDLE,
                slotFlags = DiscipleStatusService.SlotFlags(preaching = true)
            )
        )
    }

    @Test
    fun `deriveDiscipleStatus - deaconing returns DEACONING`() {
        assertEquals(
            DiscipleStatus.DEACONING,
            DiscipleStatusService.deriveDiscipleStatus(
                isAlive = true,
                currentStatus = DiscipleStatus.IDLE,
                slotFlags = DiscipleStatusService.SlotFlags(deaconing = true)
            )
        )
    }

    @Test
    fun `deriveDiscipleStatus - managing returns MANAGING`() {
        assertEquals(
            DiscipleStatus.MANAGING,
            DiscipleStatusService.deriveDiscipleStatus(
                isAlive = true,
                currentStatus = DiscipleStatus.IDLE,
                slotFlags = DiscipleStatusService.SlotFlags(managing = true)
            )
        )
    }

    @Test
    fun `deriveDiscipleStatus - studying returns STUDYING`() {
        assertEquals(
            DiscipleStatus.STUDYING,
            DiscipleStatusService.deriveDiscipleStatus(
                isAlive = true,
                currentStatus = DiscipleStatus.IDLE,
                slotFlags = DiscipleStatusService.SlotFlags(studying = true)
            )
        )
    }

    @Test
    fun `deriveDiscipleStatus - mining returns MINING`() {
        assertEquals(
            DiscipleStatus.MINING,
            DiscipleStatusService.deriveDiscipleStatus(
                isAlive = true,
                currentStatus = DiscipleStatus.IDLE,
                slotFlags = DiscipleStatusService.SlotFlags(mining = true)
            )
        )
    }

    @Test
    fun `deriveDiscipleStatus - patrolling returns PATROLLING`() {
        assertEquals(
            DiscipleStatus.PATROLLING,
            DiscipleStatusService.deriveDiscipleStatus(
                isAlive = true,
                currentStatus = DiscipleStatus.IDLE,
                slotFlags = DiscipleStatusService.SlotFlags(patrolling = true)
            )
        )
    }

    @Test
    fun `deriveDiscipleStatus - alchemy returns ALCHEMY`() {
        assertEquals(
            DiscipleStatus.ALCHEMY,
            DiscipleStatusService.deriveDiscipleStatus(
                isAlive = true,
                currentStatus = DiscipleStatus.IDLE,
                slotFlags = DiscipleStatusService.SlotFlags(alchemy = true)
            )
        )
    }

    @Test
    fun `deriveDiscipleStatus - forge returns FORGE`() {
        assertEquals(
            DiscipleStatus.FORGE,
            DiscipleStatusService.deriveDiscipleStatus(
                isAlive = true,
                currentStatus = DiscipleStatus.IDLE,
                slotFlags = DiscipleStatusService.SlotFlags(forge = true)
            )
        )
    }

    @Test
    fun `deriveDiscipleStatus - spiritPlanting returns SPIRIT_PLANTING`() {
        assertEquals(
            DiscipleStatus.SPIRIT_PLANTING,
            DiscipleStatusService.deriveDiscipleStatus(
                isAlive = true,
                currentStatus = DiscipleStatus.IDLE,
                slotFlags = DiscipleStatusService.SlotFlags(spiritPlanting = true)
            )
        )
    }

    @Test
    fun `deriveDiscipleStatus - no slots returns IDLE`() {
        assertEquals(
            DiscipleStatus.IDLE,
            DiscipleStatusService.deriveDiscipleStatus(
                isAlive = true,
                currentStatus = DiscipleStatus.IDLE,
                slotFlags = DiscipleStatusService.SlotFlags()
            )
        )
    }

    // ==================== 优先级（多槽位同时占用） ====================

    @Test
    fun `deriveDiscipleStatus - garrison has highest priority among slot types`() {
        assertEquals(
            DiscipleStatus.GARRISONING,
            DiscipleStatusService.deriveDiscipleStatus(
                isAlive = true,
                currentStatus = DiscipleStatus.IDLE,
                slotFlags = DiscipleStatusService.SlotFlags(
                    inGarrison = true, inTeam = true, mining = true, alchemy = true
                )
            )
        )
    }

    @Test
    fun `deriveDiscipleStatus - inTeam second priority after garrison`() {
        assertEquals(
            DiscipleStatus.IN_TEAM,
            DiscipleStatusService.deriveDiscipleStatus(
                isAlive = true,
                currentStatus = DiscipleStatus.IDLE,
                slotFlags = DiscipleStatusService.SlotFlags(
                    inTeam = true, lawEnforcing = true, studying = true
                )
            )
        )
    }

    @Test
    fun `deriveDiscipleStatus - alchemy beats forge and spiritPlanting`() {
        assertEquals(
            DiscipleStatus.ALCHEMY,
            DiscipleStatusService.deriveDiscipleStatus(
                isAlive = true,
                currentStatus = DiscipleStatus.IDLE,
                slotFlags = DiscipleStatusService.SlotFlags(
                    alchemy = true, forge = true, spiritPlanting = true
                )
            )
        )
    }

    @Test
    fun `deriveDiscipleStatus - forge beats spiritPlanting`() {
        assertEquals(
            DiscipleStatus.FORGE,
            DiscipleStatusService.deriveDiscipleStatus(
                isAlive = true,
                currentStatus = DiscipleStatus.IDLE,
                slotFlags = DiscipleStatusService.SlotFlags(
                    forge = true, spiritPlanting = true
                )
            )
        )
    }

    // ==================== IDLE 状态转换 ====================

    @Test
    fun `deriveDiscipleStatus - current IDLE with slots returns slot-derived status`() {
        assertEquals(
            DiscipleStatus.MINING,
            DiscipleStatusService.deriveDiscipleStatus(
                isAlive = true,
                currentStatus = DiscipleStatus.IDLE,
                slotFlags = DiscipleStatusService.SlotFlags(mining = true)
            )
        )
    }

    @Test
    fun `deriveDiscipleStatus - current IDLE without slots stays IDLE`() {
        assertEquals(
            DiscipleStatus.IDLE,
            DiscipleStatusService.deriveDiscipleStatus(
                isAlive = true,
                currentStatus = DiscipleStatus.IDLE,
                slotFlags = DiscipleStatusService.SlotFlags()
            )
        )
    }

    // ==================== 非受保护状态被覆盖 ====================

    @Test
    fun `deriveDiscipleStatus - current WORKING is overwritten by slot derivation`() {
        // 弟子当前是 MANAGING 但实际在采矿槽位中 → 应被覆盖为 MINING
        assertEquals(
            DiscipleStatus.MINING,
            DiscipleStatusService.deriveDiscipleStatus(
                isAlive = true,
                currentStatus = DiscipleStatus.MANAGING,
                slotFlags = DiscipleStatusService.SlotFlags(mining = true)
            )
        )
    }

    @Test
    fun `deriveDiscipleStatus - current GARRISONING overwritten when slots removed`() {
        assertEquals(
            DiscipleStatus.IDLE,
            DiscipleStatusService.deriveDiscipleStatus(
                isAlive = true,
                currentStatus = DiscipleStatus.GARRISONING,
                slotFlags = DiscipleStatusService.SlotFlags()
            )
        )
    }

    // ==================== 全标记验证 ====================

    @Test
    fun `deriveDiscipleStatus - all false with alive DiscipleStatus IDLE returns IDLE`() {
        val allFalse = DiscipleStatusService.SlotFlags()
        assertEquals(
            DiscipleStatus.IDLE,
            DiscipleStatusService.deriveDiscipleStatus(
                isAlive = true,
                currentStatus = DiscipleStatus.IDLE,
                slotFlags = allFalse
            )
        )
    }

    @Test
    fun `deriveDiscipleStatus - all true with alive returns GARRISONING (highest priority)`() {
        val allTrue = DiscipleStatusService.SlotFlags(
            inGarrison = true, inTeam = true, lawEnforcing = true,
            preaching = true, deaconing = true, managing = true,
            studying = true, mining = true, patrolling = true,
            alchemy = true, forge = true, spiritPlanting = true
        )
        assertEquals(
            DiscipleStatus.GARRISONING,
            DiscipleStatusService.deriveDiscipleStatus(
                isAlive = true,
                currentStatus = DiscipleStatus.IDLE,
                slotFlags = allTrue
            )
        )
    }
}
