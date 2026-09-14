package com.xianxia.sect.core.engine.domain.disciple

import com.xianxia.sect.core.model.DiscipleStatus
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.WarehouseGarrisonSlot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [DiscipleStatusService.deriveDiscipleStatus] 纯函数单元测试。
 *
 * 覆盖：
 * - 死亡 → DEAD
 * - 三种受保护状态（REFLECTING / ON_MISSION / REFINING）不被覆盖
 * - 所有 14 种槽位类型按优先级推导
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
    fun `deriveDiscipleStatus - ON_MISSION derived from hasActiveMission`() {
        assertEquals(
            DiscipleStatus.ON_MISSION,
            DiscipleStatusService.deriveDiscipleStatus(
                isAlive = true,
                currentStatus = DiscipleStatus.ON_MISSION,
                slotFlags = DiscipleStatusService.SlotFlags(),
                hasActiveMission = true
            )
        )
    }

    @Test
    fun `deriveDiscipleStatus - ON_MISSION without hasActiveMission falls through to slots`() {
        assertEquals(
            DiscipleStatus.IDLE,
            DiscipleStatusService.deriveDiscipleStatus(
                isAlive = true,
                currentStatus = DiscipleStatus.ON_MISSION,
                slotFlags = DiscipleStatusService.SlotFlags()
            )
        )
    }

    @Test
    fun `deriveDiscipleStatus - ON_MISSION derived even with slot assignments`() {
        assertEquals(
            DiscipleStatus.ON_MISSION,
            DiscipleStatusService.deriveDiscipleStatus(
                isAlive = true,
                currentStatus = DiscipleStatus.ON_MISSION,
                slotFlags = DiscipleStatusService.SlotFlags(studying = true, patrolling = true),
                hasActiveMission = true
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

    // ==================== 仓库驻守推导（inWarehouseGarrison 独立标记） ====================

    @Test
    fun `buildSlotFlagsFor - warehouse garrison disciple inWarehouseGarrison is true`() {
        val flags = DiscipleStatusService.buildSlotFlagsFor(
            discipleId = "1",
            data = GameData(
                warehouseGarrisons = listOf(
                    WarehouseGarrisonSlot("wh1", "1", "弟子A", "sect")
                )
            )
        )
        assertTrue("仓库驻守弟子应推导 inWarehouseGarrison=true", flags.inWarehouseGarrison)
        assertTrue("仓库驻守弟子不应占用 inGarrison（据点驻军语义）", !flags.inGarrison)
    }

    @Test
    fun `buildSlotFlagsFor - warehouse garrison derives WAREHOUSE_GARRISON`() {
        val flags = DiscipleStatusService.buildSlotFlagsFor(
            discipleId = "1",
            data = GameData(
                warehouseGarrisons = listOf(
                    WarehouseGarrisonSlot("wh1", "1", "弟子A", "sect")
                )
            )
        )
        assertEquals(
            DiscipleStatus.WAREHOUSE_GARRISON,
            DiscipleStatusService.deriveDiscipleStatus(
                isAlive = true,
                currentStatus = DiscipleStatus.IDLE,
                slotFlags = flags
            )
        )
    }

    @Test
    fun `deriveDiscipleStatus - inWarehouseGarrison returns WAREHOUSE_GARRISON`() {
        assertEquals(
            DiscipleStatus.WAREHOUSE_GARRISON,
            DiscipleStatusService.deriveDiscipleStatus(
                isAlive = true,
                currentStatus = DiscipleStatus.IDLE,
                slotFlags = DiscipleStatusService.SlotFlags(inWarehouseGarrison = true)
            )
        )
    }

    @Test
    fun `deriveDiscipleStatus - inWarehouseGarrison has priority over inGarrison`() {
        assertEquals(
            DiscipleStatus.WAREHOUSE_GARRISON,
            DiscipleStatusService.deriveDiscipleStatus(
                isAlive = true,
                currentStatus = DiscipleStatus.IDLE,
                slotFlags = DiscipleStatusService.SlotFlags(
                    inWarehouseGarrison = true, inGarrison = true
                )
            )
        )
    }

    // ==================== 远古秘境推导（inSecretRealm 独立标记） ====================

    @Test
    fun `deriveDiscipleStatus - inSecretRealm returns SECRET_REALM`() {
        assertEquals(
            DiscipleStatus.SECRET_REALM,
            DiscipleStatusService.deriveDiscipleStatus(
                isAlive = true,
                currentStatus = DiscipleStatus.IDLE,
                slotFlags = DiscipleStatusService.SlotFlags(inSecretRealm = true)
            )
        )
    }

    @Test
    fun `deriveDiscipleStatus - inSecretRealm has priority over inTeam and warehouse`() {
        assertEquals(
            DiscipleStatus.SECRET_REALM,
            DiscipleStatusService.deriveDiscipleStatus(
                isAlive = true,
                currentStatus = DiscipleStatus.IDLE,
                slotFlags = DiscipleStatusService.SlotFlags(
                    inSecretRealm = true, inTeam = true, inWarehouseGarrison = true
                )
            )
        )
    }

    @Test
    fun `deriveDiscipleStatus - SECRET_REALM preserved not overwritten by slots`() {
        // 非受保护状态被槽位推导覆盖是正常行为，但秘境成员仍占秘境槽位时应保持 SECRET_REALM
        assertEquals(
            DiscipleStatus.SECRET_REALM,
            DiscipleStatusService.deriveDiscipleStatus(
                isAlive = true,
                currentStatus = DiscipleStatus.IDLE,
                slotFlags = DiscipleStatusService.SlotFlags(inSecretRealm = true)
            )
        )
    }

    @Test
    fun `deriveDiscipleStatus - REFLECTING preserved even with inSecretRealm flag`() {
        assertEquals(
            DiscipleStatus.REFLECTING,
            DiscipleStatusService.deriveDiscipleStatus(
                isAlive = true,
                currentStatus = DiscipleStatus.REFLECTING,
                slotFlags = DiscipleStatusService.SlotFlags(inSecretRealm = true)
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
    fun `deriveDiscipleStatus - all true with alive returns SECRET_REALM (highest priority)`() {
        val allTrue = DiscipleStatusService.SlotFlags(
            inGarrison = true, inWarehouseGarrison = true,
            inTeam = true, inSecretRealm = true,
            lawEnforcing = true, preaching = true, deaconing = true,
            managing = true, studying = true, mining = true,
            patrolling = true, alchemy = true, forge = true,
            spiritPlanting = true
        )
        assertEquals(
            DiscipleStatus.SECRET_REALM,
            DiscipleStatusService.deriveDiscipleStatus(
                isAlive = true,
                currentStatus = DiscipleStatus.IDLE,
                slotFlags = allTrue
            )
        )
    }
}
