package com.xianxia.sect.core.engine.domain.disciple

import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.EquipAffixSet
import com.xianxia.sect.core.model.EquipGrowth
import com.xianxia.sect.core.model.EquipStat
import com.xianxia.sect.core.model.EquipStatValue
import com.xianxia.sect.core.model.EquipmentInstance
import com.xianxia.sect.core.model.EquipmentSet
import com.xianxia.sect.core.model.EquipmentSlot
import com.xianxia.sect.core.model.StorageBagItem
import com.xianxia.sect.core.nativebridge.FakeGameStateStore
import com.xianxia.sect.core.util.DomainResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 装备等级装卸保真守卫（R5 根因守卫，方案 §3.5/§6.1）。
 *
 * 历史 bug：等级/词条曾存弟子侧（孕养字段），穿到另一弟子/装卸往返后被
 * 重置归零。B3 起等级/经验/词条/强化次数全部随 [EquipmentInstance.growth]
 * **实例单点**——穿→卸→穿任意往返后必须逐位不变。仓库（实例表直穿）与
 * 储物袋（自动装配）两条来源均须成立。
 */
class EquipmentLevelPersistGuardTest {

    /** 带成长痕迹的武器实例：等级 7 / 经验 123 / 强化 [2,3,5]——任意一环丢失即红 */
    private fun loadedInstance(
        id: String = "w1",
        ownerId: String? = null,
        isEquipped: Boolean = false
    ) = EquipmentInstance(
        id = id,
        name = "青霄剑",
        setId = "lietian",
        part = EquipmentSlot.WEAPON,
        growth = EquipGrowth(
            level = 7,
            exp = 123,
            affix = EquipAffixSet(
                mainStat = EquipStatValue(EquipStat.ATTACK, 42.0),
                subStats = listOf(
                    EquipStatValue(EquipStat.DEFENSE, 3.0),
                    EquipStatValue(EquipStat.HP, 30.0),
                    EquipStatValue(EquipStat.CRIT_RATE, 0.01)
                ),
                subRolls = listOf(2, 3, 5)
            )
        ),
        meta = com.xianxia.sect.core.model.EquipInstanceMeta(rarity = 1, minRealm = 9),
        ownerId = ownerId,
        isEquipped = isEquipped
    )

    private fun storeWith(vararg instances: EquipmentInstance) = FakeGameStateStore().apply {
        update {
            discipleTables.replaceAll(
                listOf(Disciple(id = "1", name = "测试弟子", realm = 9))
            )
        }
        equipmentInstancesValue = instances.toList()
    }

    private fun serviceOf(store: FakeGameStateStore) = DiscipleEquipmentService(store)

    // ── 仓库来源：穿 → 卸 → 穿 ────────────────────────────────

    @Test
    fun `穿卸穿往返后成长逐位不变`() {
        val store = storeWith(loadedInstance())
        val service = serviceOf(store)
        val original = loadedInstance().growth

        // 第一次穿戴
        assertTrue(service.equipEquipment("1", "w1") is DomainResult.Success)
        store.equipmentInstancesValue.single().let { worn ->
            assertEquals("穿戴不改成长", original, worn.growth)
            assertTrue(worn.isEquipped)
            assertEquals("1", worn.ownerId)
        }
        assertEquals("槽位记录实例 id", "w1", store.discipleTables.weaponIds[1])

        // 卸下
        assertTrue(service.unequipEquipment("1", "w1") is DomainResult.Success)
        store.equipmentInstancesValue.single().let { stored ->
            assertEquals("卸下不改成长", original, stored.growth)
            assertEquals("卸下后下线态", false, stored.isEquipped)
            assertNull("ownerId 清空", stored.ownerId)
        }
        assertEquals("槽位清空", "", store.discipleTables.weaponIds[1])

        // 第二次穿戴（换手再来一遍）
        assertTrue(service.equipEquipment("1", "w1") is DomainResult.Success)
        store.equipmentInstancesValue.single().let { worn2 ->
            assertEquals("往返后成长仍逐位不变", original, worn2.growth)
            assertTrue(worn2.isEquipped)
        }
        assertEquals("w1", store.discipleTables.weaponIds[1])
    }

    @Test
    fun `卸下后储物袋条目持有完整成长实例`() {
        val store = storeWith(loadedInstance())
        val service = serviceOf(store)
        val original = loadedInstance().growth

        assertTrue(service.equipEquipment("1", "w1") is DomainResult.Success)
        assertTrue(service.unequipEquipment("1", "w1") is DomainResult.Success)

        val bag = store.discipleTables.storageBagItems[1] ?: emptyList()
        assertEquals("卸下应入袋恰一条", 1, bag.size)
        val bagInstance = bag.single().equipmentInstance
        assertNotNull("袋条目应携带完整实例", bagInstance)
        bagInstance ?: return
        assertEquals("袋内实例成长逐位保真", original, bagInstance.growth)
        assertEquals("青霄剑", bagInstance.name)
    }

    @Test
    fun `跨弟子装卸成长随实例走`() {
        // 弟子 1 卸下 → 弟子 2 穿上：等级词条不因换主而重置（R5 历史 bug 场景）
        val store = FakeGameStateStore().apply {
            update {
                discipleTables.replaceAll(
                    listOf(
                        Disciple(id = "1", name = "甲", realm = 9),
                        Disciple(id = "2", name = "乙", realm = 9)
                    )
                )
            }
            equipmentInstancesValue = listOf(loadedInstance())
        }
        val service = serviceOf(store)
        val original = loadedInstance().growth

        assertTrue(service.equipEquipment("1", "w1") is DomainResult.Success)
        assertTrue(service.unequipEquipment("1", "w1") is DomainResult.Success)
        assertTrue(service.equipEquipment("2", "w1") is DomainResult.Success)

        val worn = store.equipmentInstancesValue.single()
        assertEquals("跨弟子往返成长逐位不变", original, worn.growth)
        assertEquals("2", worn.ownerId)
        assertEquals("w1", store.discipleTables.weaponIds[2])
    }

    // ── 储物袋来源：自动装配 ─────────────────────────────────

    @Test
    fun `袋内实例自动装配成长保真且袋条目移除`() {
        val instance = loadedInstance(id = "bag1")
        val disciple = Disciple(
            id = "1", name = "测试弟子", realm = 9,
            equipment = EquipmentSet(
                storageBagItems = listOf(
                    StorageBagItem(
                        itemId = instance.id,
                        itemType = "equipment_instance",
                        name = instance.name,
                        rarity = instance.rarity,
                        quantity = 1,
                        equipmentInstance = instance
                    )
                )
            )
        )
        val result = DiscipleEquipmentManager().processAutoEquipFromWarehouse(disciple = disciple)
        assertEquals("应装配到武器位", "bag1", result.disciple.equipment.weaponId)
        assertEquals("袋条目应移除", 0, result.disciple.equipment.storageBagItems.size)
        assertEquals("attachedInstances 恰一件", 1, result.attachedInstances.size)
        assertEquals("装配实例成长逐位保真", instance.growth, result.attachedInstances[0].growth)
        assertTrue(result.attachedInstances[0].isEquipped)
        assertEquals("1", result.attachedInstances[0].ownerId)
    }

    @Test
    fun `自动替换旧装备回袋成长保真`() {
        val old = loadedInstance(id = "old", ownerId = "1", isEquipped = true)
        val new = loadedInstance(id = "new").copy(
            meta = com.xianxia.sect.core.model.EquipInstanceMeta(rarity = 3, minRealm = 9)
        )
        val disciple = Disciple(
            id = "1", name = "测试弟子", realm = 9,
            equipment = EquipmentSet(
                weaponId = "old",
                storageBagItems = listOf(
                    StorageBagItem(
                        itemId = new.id, itemType = "equipment_instance",
                        name = new.name, rarity = new.rarity, quantity = 1,
                        equipmentInstance = new
                    )
                )
            )
        )
        val result = DiscipleEquipmentManager().processAutoEquipFromWarehouse(
            disciple = disciple,
            equipmentInstances = mapOf("old" to old)
        )
        assertEquals("高品阶候选应替换", "new", result.disciple.equipment.weaponId)
        assertEquals("旧装备应登记为 replaced", listOf("old"), result.replacedInstances.map { it.id })
        assertEquals("旧装备成长保真", old.growth, result.replacedInstances[0].growth)
        val bagOld = result.disciple.equipment.storageBagItems
            .single { it.itemId == "old" }.equipmentInstance
        assertNotNull(bagOld)
        bagOld ?: return
        assertEquals("回袋旧装备成长逐位保真", old.growth, bagOld.growth)
    }

    @Test
    fun `境界不足袋内条目不装配`() {
        val tooHigh = loadedInstance(id = "high").copy(
            meta = com.xianxia.sect.core.model.EquipInstanceMeta(rarity = 6, minRealm = 2)
        )
        val disciple = Disciple(
            id = "1", name = "测试弟子", realm = 9,
            equipment = EquipmentSet(
                storageBagItems = listOf(
                    StorageBagItem(
                        itemId = tooHigh.id, itemType = "equipment_instance",
                        name = tooHigh.name, rarity = tooHigh.rarity, quantity = 1,
                        equipmentInstance = tooHigh
                    )
                )
            )
        )
        val result = DiscipleEquipmentManager().processAutoEquipFromWarehouse(disciple = disciple)
        assertEquals("境界不足不装配", "", result.disciple.equipment.weaponId)
        assertTrue("候选保留在袋内", result.disciple.equipment.storageBagItems.isNotEmpty())
        assertTrue(result.attachedInstances.isEmpty())
    }
}
