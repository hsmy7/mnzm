package com.xianxia.sect.core.engine.domain.disciple

import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.EquipAffixSet
import com.xianxia.sect.core.model.EquipGrowth
import com.xianxia.sect.core.model.EquipInstanceMeta
import com.xianxia.sect.core.model.EquipStat
import com.xianxia.sect.core.model.EquipStatValue
import com.xianxia.sect.core.model.EquipmentInstance
import com.xianxia.sect.core.model.EquipmentSlot
import com.xianxia.sect.core.model.EquipmentSet
import com.xianxia.sect.core.model.StorageBagItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 自动装备候选（储物袋实例单源）与更高品阶替换（装备重构 B3）。
 *
 * 覆盖：
 * - 袋内 equipment_instance 直接装配（attachedInstances 重建入表、袋条目移除、等级保真）
 * - 已装备低品阶 + 袋内高品阶 → 自动替换（旧装备回袋、replacedInstances 供同步）
 * - 已装备高品阶 + 更低候选 → 不替换
 * - 比较键：品阶 → 套装流派 → 等级（仓库堆叠候选与 equipment_stack 模板重建
 *   已随 B3 堆叠轨退役，不再有用例）
 * - 境界不足袋内条目不装配
 */
class DiscipleEquipmentManagerBagAutoEquipTest {

    private val manager = DiscipleEquipmentManager()

    private fun disciple(
        weaponId: String = "",
        bag: List<StorageBagItem> = emptyList()
    ) = Disciple(
        id = "1",
        name = "测试弟子",
        realm = 9,
        equipment = EquipmentSet(weaponId = weaponId, storageBagItems = bag)
    )

    /** B3 实例轨袋条目：完整实例随条目携带（level/rarity 经 growth/meta 承载） */
    private fun bagInstance(
        itemId: String,
        name: String,
        rarity: Int,
        level: Int = 1,
        setId: String = ""
    ): StorageBagItem {
        val instance = EquipmentInstance(
            id = itemId, name = name,
            setId = setId, part = EquipmentSlot.WEAPON,
            growth = EquipGrowth(
                level = level,
                affix = EquipAffixSet(mainStat = EquipStatValue(EquipStat.ATTACK, 10.0))
            ),
            meta = EquipInstanceMeta(rarity = rarity, minRealm = 9),
            ownerId = "1", isEquipped = false
        )
        return StorageBagItem(
            itemId = itemId, itemType = ITEM_TYPE_EQUIPMENT_INSTANCE,
            name = name, rarity = rarity, quantity = 1,
            equipmentInstance = instance
        )
    }

    // ── 袋内实例直接装配 ──────────────────────────────────────────

    @Test
    fun `袋内实例装配 - 空槽装配且袋条目移除等级保真`() {
        val d = disciple(bag = listOf(bagInstance("i1", "青云剑", 4, level = 2)))

        val result = manager.processAutoEquipFromWarehouse(
            disciple = d, equipmentInstances = emptyMap(),
            gameYear = 1, gameMonth = 1
        )

        assertEquals("应装配袋内实例", "i1", result.disciple.equipment.weaponId)
        assertTrue("袋条目应移除", result.disciple.equipment.storageBagItems.isEmpty())
        assertEquals("attachedInstances 应含装配实例", 1, result.attachedInstances.size)
        assertEquals("等级保真", 2, result.attachedInstances[0].level)
        assertTrue("attached 实例应置 isEquipped", result.attachedInstances[0].isEquipped)
        assertEquals("装配实例 ownerId 应为弟子", "1", result.attachedInstances[0].ownerId)
        assertTrue("newInstances 应空（实例已存在）", result.newInstances.isEmpty())
    }

    // ── 更高品阶替换 ──────────────────────────────────────────────

    @Test
    fun `更高品阶替换 - 低品阶换袋内高品阶且旧装备回袋`() {
        val old = EquipmentInstance(
            id = "w1", name = "铁剑",
            part = EquipmentSlot.WEAPON,
            growth = EquipGrowth(
                affix = EquipAffixSet(mainStat = EquipStatValue(EquipStat.ATTACK, 15.0))
            ),
            meta = EquipInstanceMeta(rarity = 1, minRealm = 9),
            ownerId = "1", isEquipped = true
        )
        val d = disciple(
            weaponId = "w1",
            bag = listOf(bagInstance("n1", "青云剑", 4))
        )

        val result = manager.processAutoEquipFromWarehouse(
            disciple = d,
            equipmentInstances = mapOf("w1" to old),
            gameYear = 3, gameMonth = 5
        )

        assertEquals("应替换为高品阶", "n1", result.disciple.equipment.weaponId)
        assertEquals("replacedInstances 应含旧实例", listOf("w1"), result.replacedInstances.map { it.id })
        // 旧装备已回袋（equipment_instance 保真条目）
        val bagItems = result.disciple.equipment.storageBagItems
        assertEquals("旧装备应回袋", 1, bagItems.size)
        assertEquals("w1", bagItems[0].itemId)
        assertEquals(ITEM_TYPE_EQUIPMENT_INSTANCE, bagItems[0].itemType)
        assertEquals("回袋时间应取当前年", 3, bagItems[0].obtainedYear)
        assertEquals("attachedInstances 应含新实例", listOf("n1"), result.attachedInstances.map { it.id })
    }

    @Test
    fun `更高品阶替换 - 已装备高品阶不降级`() {
        val current = EquipmentInstance(
            id = "w1", name = "青云剑",
            part = EquipmentSlot.WEAPON,
            growth = EquipGrowth(
                affix = EquipAffixSet(mainStat = EquipStatValue(EquipStat.ATTACK, 100.0))
            ),
            meta = EquipInstanceMeta(rarity = 4, minRealm = 9),
            ownerId = "1", isEquipped = true
        )
        val d = disciple(
            weaponId = "w1",
            bag = listOf(bagInstance("n1", "铁剑", 1))
        )

        val result = manager.processAutoEquipFromWarehouse(
            disciple = d,
            equipmentInstances = mapOf("w1" to current),
            gameYear = 1, gameMonth = 1
        )

        assertEquals("高品阶不应被低品阶替换", "w1", result.disciple.equipment.weaponId)
        assertTrue("不应有替换", result.replacedInstances.isEmpty())
        assertTrue("低品阶候选应保留袋内", result.disciple.equipment.storageBagItems.size == 1)
    }

    // ── 比较键次级：同品阶按等级 ─────────────────────────────────

    @Test
    fun `同品阶 - 高等级袋内实例优先装配`() {
        val d = disciple(bag = listOf(
            bagInstance("low", "低级剑", 4, level = 1),
            bagInstance("high", "高级剑", 4, level = 5)
        ))

        val result = manager.processAutoEquipFromWarehouse(
            disciple = d, equipmentInstances = emptyMap(),
            gameYear = 1, gameMonth = 1
        )

        assertEquals("同品阶应选高等级实例", "high", result.disciple.equipment.weaponId)
    }

    // ── 境界门槛 ──────────────────────────────────────────────────

    @Test
    fun `境界不足 - 袋内条目不装配`() {
        val instance = EquipmentInstance(
            id = "i1", name = "高阶剑",
            part = EquipmentSlot.WEAPON,
            growth = EquipGrowth(
                affix = EquipAffixSet(mainStat = EquipStatValue(EquipStat.ATTACK, 500.0))
            ),
            meta = EquipInstanceMeta(rarity = 5, minRealm = 2),
            ownerId = "1", isEquipped = false
        )
        val d = disciple(bag = listOf(
            StorageBagItem(
                itemId = "i1", itemType = ITEM_TYPE_EQUIPMENT_INSTANCE,
                name = "高阶剑", rarity = 5, quantity = 1, equipmentInstance = instance
            )
        ))

        val result = manager.processAutoEquipFromWarehouse(
            disciple = d, equipmentInstances = emptyMap(),
            gameYear = 1, gameMonth = 1
        )

        assertTrue("境界不足不应装配", result.disciple.equipment.weaponId.isEmpty())
        assertTrue(result.attachedInstances.isEmpty())
        assertEquals("袋内条目应保留", 1, result.disciple.equipment.storageBagItems.size)
    }
}
