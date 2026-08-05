package com.xianxia.sect.core.engine.system

import com.xianxia.sect.core.config.InventoryConfig
import com.xianxia.sect.core.engine.FakeAtomicStateStore
import com.xianxia.sect.core.engine.config.GameConfigProvider
import com.xianxia.sect.core.model.EquipmentInstance
import com.xianxia.sect.core.model.EquipmentSlot
import com.xianxia.sect.core.model.EquipmentStack
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.ManualInstance
import com.xianxia.sect.core.model.ManualStack
import com.xianxia.sect.core.model.ManualType
import com.xianxia.sect.core.util.DomainResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * P-20 迁移测试：InventorySystem.addEquipmentInstanceToBag / addManualInstanceToBag
 *（原 domain StorageBagUtils 的实例→堆叠转换，迁移后获得真实容量约束 + 溢出转邮件）。
 */
class InventoryBagTransferTest {

    private lateinit var store: FakeAtomicStateStore
    private lateinit var inventorySystem: InventorySystem
    private lateinit var overflowHandler: RecordingOverflowHandler

    /** 记录溢出邮件草稿的 handler（验证溢出转邮件语义） */
    private class RecordingOverflowHandler : com.xianxia.sect.core.overflow.OverflowMailHandler {
        val drafts = mutableListOf<com.xianxia.sect.core.overflow.OverflowMailDraft>()
        override fun sendOverflowMails(drafts: List<com.xianxia.sect.core.overflow.OverflowMailDraft>) {
            this.drafts.addAll(drafts)
        }
    }

    @Before
    fun setUp() {
        store = FakeAtomicStateStore()
        store.update { gameData = GameData(slotId = 1) }
        overflowHandler = RecordingOverflowHandler()
        inventorySystem = InventorySystem(
            stateStore = store,
            inventoryConfig = InventoryConfig(),
            spiritStoneWallet = com.xianxia.sect.core.wallet.SpiritStoneWallet(
                stateStore = store,
                ledger = org.mockito.Mockito.mock(com.xianxia.sect.core.wallet.SpiritStoneLedger::class.java),
                eventBus = org.mockito.Mockito.mock(com.xianxia.sect.core.event.EventBus::class.java)
            ),
            gameConfigProvider = GameConfigProvider(
                com.xianxia.sect.core.config.ConfigLoader(assetReader = { null })
            ),
            overflowMailHandler = overflowHandler
        )
    }

    @Test
    fun `addEquipmentInstanceToBag - merges into existing stack and removes instance`() {
        store.equipmentStacks.value = listOf(
            EquipmentStack(id = "s1", name = "铁剑", rarity = 1, slot = EquipmentSlot.WEAPON, quantity = 3)
        )
        store.equipmentInstances.value = listOf(
            EquipmentInstance(id = "i1", name = "铁剑", rarity = 1, slot = EquipmentSlot.WEAPON)
        )

        val result = inventorySystem.addEquipmentInstanceToBag(
            EquipmentInstance(id = "i1", name = "铁剑", rarity = 1, slot = EquipmentSlot.WEAPON)
        )

        assertTrue("应合并成功", result is DomainResult.Success)
        assertEquals("合并后堆叠数量", 4, store.equipmentStacks.value.first().quantity)
        assertEquals("实例已移除", 0, store.equipmentInstances.value.size)
        assertEquals("堆叠数不变", 1, store.equipmentStacks.value.size)
    }

    @Test
    fun `addEquipmentInstanceToBag - excludeStackId preserved at tail`() {
        store.equipmentStacks.value = listOf(
            EquipmentStack(id = "s1", name = "铁剑", rarity = 1, slot = EquipmentSlot.WEAPON, quantity = 1),
            EquipmentStack(id = "s2", name = "铁剑", rarity = 1, slot = EquipmentSlot.WEAPON, quantity = 1)
        )

        val result = inventorySystem.addEquipmentInstanceToBag(
            EquipmentInstance(id = "i1", name = "铁剑", rarity = 1, slot = EquipmentSlot.WEAPON),
            excludeStackId = "s1"
        )

        assertTrue(result is DomainResult.Success)
        // 合并进非排除的 s2；s1（背包引用堆叠）保留且数量不变
        val s1 = store.equipmentStacks.value.find { it.id == "s1" }
        val s2 = store.equipmentStacks.value.find { it.id == "s2" }
        assertEquals(1, s1?.quantity)
        assertEquals(2, s2?.quantity)
        assertEquals("排除堆叠（背包引用）放回尾部", "s1", store.equipmentStacks.value.last().id)
    }

    @Test
    fun `addEquipmentInstanceToBag - multiple returns merge into single stack`() {
        repeat(3) { i ->
            inventorySystem.addEquipmentInstanceToBag(
                EquipmentInstance(id = "i$i", name = "铁剑", rarity = 1, slot = EquipmentSlot.WEAPON)
            )
        }
        assertEquals(1, store.equipmentStacks.value.size)
        assertEquals(3, store.equipmentStacks.value.first().quantity)
        assertEquals(0, store.equipmentInstances.value.size)
    }

    @Test
    fun `addEquipmentInstanceToBag - warehouse capacity respected`() {
        // 占满仓库容量（baseCapacity 默认值见 GameConfig.Warehouse.BASE_CAPACITY）
        val baseCapacity = com.xianxia.sect.core.GameConfig.Warehouse.BASE_CAPACITY
        val otherTypes = 0
        repeat(baseCapacity - otherTypes) { i ->
            store.equipmentStacks.value = store.equipmentStacks.value +
                EquipmentStack(
                    id = "s$i", name = "独门武器$i", rarity = 1,
                    slot = EquipmentSlot.WEAPON, quantity = 1
                )
        }

        // 容量满时：零合并（名称不同）→ Failure（不再 candidates.size+1 绕过总容量）
        val result = inventorySystem.addEquipmentInstanceToBag(
            EquipmentInstance(id = "i-new", name = "新武器", rarity = 1, slot = EquipmentSlot.WEAPON)
        )

        assertTrue("容量满应返回 Failure（旧实现 candidates.size+1 绕过总容量）", result is DomainResult.Failure)
        assertEquals("堆叠数不变", baseCapacity, store.equipmentStacks.value.size)
        // 溢出转邮件语义：实例转邮件找回（不丢玩家物品），故实例移除 + 邮件草稿 1 条
        assertEquals("溢出应转邮件（实例不丢失）", 0, store.equipmentInstances.value.size)
        assertEquals("溢出邮件草稿", 1, overflowHandler.drafts.size)
        assertEquals("溢出数量", 1, overflowHandler.drafts[0].quantity)
    }

    @Test
    fun `addEquipmentStack - excludeStackId prevents merge-back to deducted source`() {
        // F1 对抗性审查守卫：放背包路径"扣减源堆叠 1 → addEquipmentStack"——
        // 若合并回源堆叠则数量净 0 但背包引用 +1（无限刷引用，回收洗白）。
        // excludeStackId 排除源堆叠 → 合并到其他同键堆叠或新建，数量守恒。
        store.equipmentStacks.value = listOf(
            EquipmentStack(id = "s1", name = "铁剑", rarity = 1, slot = EquipmentSlot.WEAPON, quantity = 2),
            EquipmentStack(id = "s2", name = "铁剑", rarity = 1, slot = EquipmentSlot.WEAPON, quantity = 1)
        )
        // 模拟放背包：扣减源堆叠 1 → add（排除源堆叠）
        val result = inventorySystem.addEquipmentStack(
            EquipmentStack(id = "new", name = "铁剑", rarity = 1, slot = EquipmentSlot.WEAPON, quantity = 1),
            excludeStackId = "s1"
        )
        assertTrue(result is DomainResult.Success)
        // 合并进 s2（非排除的同键堆叠），s1 保持扣减后状态
        val s1 = store.equipmentStacks.value.find { it.id == "s1" }
        val s2 = store.equipmentStacks.value.find { it.id == "s2" }
        assertEquals("源堆叠不被合并回", 2, s1?.quantity)
        assertEquals("合并到其他同键堆叠", 2, s2?.quantity)
    }

    @Test
    fun `addEquipmentStack - without exclude merges back to source`() {
        // 对照：不带 excludeStackId 时合并回源堆叠（F1 漏洞的原始形态——守卫验证修复必要）
        store.equipmentStacks.value = listOf(
            EquipmentStack(id = "s1", name = "铁剑", rarity = 1, slot = EquipmentSlot.WEAPON, quantity = 2)
        )
        val result = inventorySystem.addEquipmentStack(
            EquipmentStack(id = "new", name = "铁剑", rarity = 1, slot = EquipmentSlot.WEAPON, quantity = 1)
        )
        assertTrue(result is DomainResult.Success)
        assertEquals("不带 exclude 时合并回源堆叠", 3, store.equipmentStacks.value.first().quantity)
    }

    @Test
    fun `addManualInstanceToBag - merges into existing stack and removes instance`() {
        store.manualStacks.value = listOf(
            ManualStack(id = "m1", name = "太乙剑诀", rarity = 2, type = ManualType.ATTACK, quantity = 2)
        )
        store.manualInstances.value = listOf(
            ManualInstance(id = "mi1", name = "太乙剑诀", rarity = 2, type = ManualType.ATTACK)
        )

        val result = inventorySystem.addManualInstanceToBag(
            ManualInstance(id = "mi1", name = "太乙剑诀", rarity = 2, type = ManualType.ATTACK)
        )

        assertTrue(result is DomainResult.Success)
        assertEquals(3, store.manualStacks.value.first().quantity)
        assertEquals(0, store.manualInstances.value.size)
    }
}
