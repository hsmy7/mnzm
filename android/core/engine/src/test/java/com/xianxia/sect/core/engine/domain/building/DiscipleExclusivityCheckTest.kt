package com.xianxia.sect.core.engine.domain.building

import com.xianxia.sect.core.model.production.BuildingType
import com.xianxia.sect.core.model.production.ProductionSlot
import com.xianxia.sect.core.model.production.ProductionSlotStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [BuildingFacadeImpl.isDiscipleAssignedToOtherSlot] 单元测试。
 *
 * 覆盖历史 bug #4：
 * - 旧实现在 `BuildingService.assignDiscipleToBuilding` 中使用
 *   `it.buildingId != buildingId && it.assignedDiscipleId == discipleId` 做排他判断，
 *   但 `buildingId` 是类型标识（如 "alchemy"/"forge"），非实例标识。
 *   多个同类型建筑实例共享同一 `buildingId`，导致排他检查失效。
 * - `BuildingFacadeImpl.assignDiscipleToProductionSlot`（UI 实际调用路径）
 *   完全没有排他性检查，弟子可被同时分配到多个炼丹炉/锻造坊。
 *
 * 修复方案：提取纯函数 [BuildingFacadeImpl.isDiscipleAssignedToOtherSlot]，
 * 按 buildingType + slotIndex 排除当前槽位后检查弟子是否已分配到其他任意槽位。
 */
class DiscipleExclusivityCheckTest {

    // ── 基本场景 ──────────────────────────────────────────────

    @Test
    fun `isDiscipleAssignedToOtherSlot - 弟子未分配到任何槽位时返回false`() {
        val slots = listOf(
            ProductionSlot(slotIndex = 0, buildingType = BuildingType.ALCHEMY, assignedDiscipleId = null),
            ProductionSlot(slotIndex = 1, buildingType = BuildingType.ALCHEMY, assignedDiscipleId = null)
        )

        val result = BuildingFacadeImpl.isDiscipleAssignedToOtherSlot(
            discipleId = "d1",
            slots = slots,
            currentBuildingType = BuildingType.ALCHEMY,
            currentSlotIndex = 0
        )

        assertFalse("弟子未分配到任何槽位应允许分配", result)
    }

    @Test
    fun `isDiscipleAssignedToOtherSlot - 弟子已分配到当前槽位时返回false（允许重分配）`() {
        val slots = listOf(
            ProductionSlot(slotIndex = 0, buildingType = BuildingType.ALCHEMY, assignedDiscipleId = "d1"),
            ProductionSlot(slotIndex = 1, buildingType = BuildingType.ALCHEMY, assignedDiscipleId = null)
        )

        val result = BuildingFacadeImpl.isDiscipleAssignedToOtherSlot(
            discipleId = "d1",
            slots = slots,
            currentBuildingType = BuildingType.ALCHEMY,
            currentSlotIndex = 0
        )

        assertFalse("弟子仅分配到当前槽位应允许重分配", result)
    }

    @Test
    fun `isDiscipleAssignedToOtherSlot - 弟子已分配到同类型其他槽位时返回true`() {
        // 场景：两个炼丹炉，弟子已分配到炼丹炉#0，尝试分配到炼丹炉#1
        val slots = listOf(
            ProductionSlot(slotIndex = 0, buildingType = BuildingType.ALCHEMY, assignedDiscipleId = "d1"),
            ProductionSlot(slotIndex = 1, buildingType = BuildingType.ALCHEMY, assignedDiscipleId = null)
        )

        val result = BuildingFacadeImpl.isDiscipleAssignedToOtherSlot(
            discipleId = "d1",
            slots = slots,
            currentBuildingType = BuildingType.ALCHEMY,
            currentSlotIndex = 1
        )

        assertTrue("弟子已分配到其他同类型槽位应阻止分配", result)
    }

    @Test
    fun `isDiscipleAssignedToOtherSlot - 弟子已分配到不同类型槽位时返回true`() {
        // 场景：弟子已分配到炼丹炉，尝试分配到锻造坊
        val slots = listOf(
            ProductionSlot(slotIndex = 0, buildingType = BuildingType.ALCHEMY, assignedDiscipleId = "d1"),
            ProductionSlot(slotIndex = 0, buildingType = BuildingType.FORGE, assignedDiscipleId = null)
        )

        val result = BuildingFacadeImpl.isDiscipleAssignedToOtherSlot(
            discipleId = "d1",
            slots = slots,
            currentBuildingType = BuildingType.FORGE,
            currentSlotIndex = 0
        )

        assertTrue("弟子已分配到其他类型槽位应阻止分配", result)
    }

    // ── 历史 bug 回归测试 ──────────────────────────────────────────────

    @Test
    fun `isDiscipleAssignedToOtherSlot - 多个同类型建筑实例时正确阻止重复分配`() {
        // 历史 bug 回归：旧实现使用 buildingId（类型）做排他判断，
        // 多个同类型建筑实例共享同一 buildingId，导致排他检查失效。
        // 场景：3个炼丹炉，弟子已分配到#0，尝试分配到#2
        val slots = listOf(
            ProductionSlot(slotIndex = 0, buildingType = BuildingType.ALCHEMY, assignedDiscipleId = "d1"),
            ProductionSlot(slotIndex = 1, buildingType = BuildingType.ALCHEMY, assignedDiscipleId = "d2"),
            ProductionSlot(slotIndex = 2, buildingType = BuildingType.ALCHEMY, assignedDiscipleId = null)
        )

        val result = BuildingFacadeImpl.isDiscipleAssignedToOtherSlot(
            discipleId = "d1",
            slots = slots,
            currentBuildingType = BuildingType.ALCHEMY,
            currentSlotIndex = 2
        )

        assertTrue("多个同类型建筑实例时应正确阻止弟子重复分配", result)
    }

    @Test
    fun `isDiscipleAssignedToOtherSlot - 弟子分配到当前槽位时不阻止（即使有其他同类型槽位）`() {
        // 场景：弟子已分配到炼丹炉#1，重新分配到炼丹炉#1（同一槽位）
        val slots = listOf(
            ProductionSlot(slotIndex = 0, buildingType = BuildingType.ALCHEMY, assignedDiscipleId = "d2"),
            ProductionSlot(slotIndex = 1, buildingType = BuildingType.ALCHEMY, assignedDiscipleId = "d1")
        )

        val result = BuildingFacadeImpl.isDiscipleAssignedToOtherSlot(
            discipleId = "d1",
            slots = slots,
            currentBuildingType = BuildingType.ALCHEMY,
            currentSlotIndex = 1
        )

        assertFalse("重新分配到同一槽位应允许", result)
    }

    // ── 边界情况 ──────────────────────────────────────────────

    @Test
    fun `isDiscipleAssignedToOtherSlot - 空槽位列表时返回false`() {
        val result = BuildingFacadeImpl.isDiscipleAssignedToOtherSlot(
            discipleId = "d1",
            slots = emptyList(),
            currentBuildingType = BuildingType.ALCHEMY,
            currentSlotIndex = 0
        )

        assertFalse("空槽位列表应允许分配", result)
    }

    @Test
    fun `isDiscipleAssignedToOtherSlot - 空弟子ID时返回false`() {
        val slots = listOf(
            ProductionSlot(slotIndex = 0, buildingType = BuildingType.ALCHEMY, assignedDiscipleId = "")
        )

        val result = BuildingFacadeImpl.isDiscipleAssignedToOtherSlot(
            discipleId = "",
            slots = slots,
            currentBuildingType = BuildingType.ALCHEMY,
            currentSlotIndex = 0
        )

        assertFalse("空弟子ID应允许分配（用于移除弟子的场景）", result)
    }

    @Test
    fun `isDiscipleAssignedToOtherSlot - 所有槽位的assignedDiscipleId为null时返回false`() {
        val slots = listOf(
            ProductionSlot(slotIndex = 0, buildingType = BuildingType.ALCHEMY, assignedDiscipleId = null),
            ProductionSlot(slotIndex = 1, buildingType = BuildingType.ALCHEMY, assignedDiscipleId = null),
            ProductionSlot(slotIndex = 0, buildingType = BuildingType.FORGE, assignedDiscipleId = null)
        )

        val result = BuildingFacadeImpl.isDiscipleAssignedToOtherSlot(
            discipleId = "d1",
            slots = slots,
            currentBuildingType = BuildingType.ALCHEMY,
            currentSlotIndex = 0
        )

        assertFalse("所有槽位无弟子时应允许分配", result)
    }

    @Test
    fun `isDiscipleAssignedToOtherSlot - 弟子分配到当前槽位和其他槽位时返回true`() {
        // 场景：弟子被错误地同时分配到两个槽位（数据不一致），
        // 尝试重新分配到其中一个时应阻止（因为还在另一个槽位上）
        val slots = listOf(
            ProductionSlot(slotIndex = 0, buildingType = BuildingType.ALCHEMY, assignedDiscipleId = "d1"),
            ProductionSlot(slotIndex = 1, buildingType = BuildingType.ALCHEMY, assignedDiscipleId = "d1")
        )

        val result = BuildingFacadeImpl.isDiscipleAssignedToOtherSlot(
            discipleId = "d1",
            slots = slots,
            currentBuildingType = BuildingType.ALCHEMY,
            currentSlotIndex = 0
        )

        assertTrue("弟子同时分配到多个槽位时应阻止重分配到其中任一个", result)
    }

    @Test
    fun `isDiscipleAssignedToOtherSlot - 不同弟子分配到不同槽位时不互相阻止`() {
        val slots = listOf(
            ProductionSlot(slotIndex = 0, buildingType = BuildingType.ALCHEMY, assignedDiscipleId = "d1"),
            ProductionSlot(slotIndex = 1, buildingType = BuildingType.ALCHEMY, assignedDiscipleId = "d2")
        )

        val result = BuildingFacadeImpl.isDiscipleAssignedToOtherSlot(
            discipleId = "d3",
            slots = slots,
            currentBuildingType = BuildingType.ALCHEMY,
            currentSlotIndex = 2
        )

        assertFalse("不同弟子不应互相阻止分配", result)
    }

    @Test
    fun `isDiscipleAssignedToOtherSlot - 跨建筑类型排他检查`() {
        // 场景：弟子已分配到锻造坊，尝试分配到炼丹炉
        val slots = listOf(
            ProductionSlot(slotIndex = 0, buildingType = BuildingType.FORGE, assignedDiscipleId = "d1"),
            ProductionSlot(slotIndex = 0, buildingType = BuildingType.ALCHEMY, assignedDiscipleId = null)
        )

        val result = BuildingFacadeImpl.isDiscipleAssignedToOtherSlot(
            discipleId = "d1",
            slots = slots,
            currentBuildingType = BuildingType.ALCHEMY,
            currentSlotIndex = 0
        )

        assertTrue("弟子已分配到锻造坊时应阻止分配到炼丹炉", result)
    }
}
