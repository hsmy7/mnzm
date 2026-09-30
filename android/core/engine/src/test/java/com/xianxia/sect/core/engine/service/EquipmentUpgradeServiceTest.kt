package com.xianxia.sect.core.engine.service

import com.xianxia.sect.core.engine.EquipmentLevelSystem
import com.xianxia.sect.core.model.EquipAffixSet
import com.xianxia.sect.core.model.EquipGrowth
import com.xianxia.sect.core.model.EquipLevelCurve
import com.xianxia.sect.core.model.EquipStat
import com.xianxia.sect.core.model.EquipStatValue
import com.xianxia.sect.core.model.EquipmentInstance
import com.xianxia.sect.core.model.EquipmentSlot
import com.xianxia.sect.core.model.Material
import com.xianxia.sect.core.nativebridge.FakeGameStateStore
import com.xianxia.sect.core.util.DomainResult
import com.xianxia.sect.core.util.GameRngManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 装备升级/分解事务守卫（装备重构 B3，方案 §3.6/§3.9/§6.1——替换孕养体系 R4）。
 *
 * 钉死：材料校验先验后扣（不足失败且零改动）、满级失败、升级材料直接折算
 * 该级经验、强化节点 newLevel%3 且 subRolls 上限 11、兽材 (rarity,id) 升序
 * 逐堆叠扣、分解返还 50% floor + 袋条目 strip、锁/已穿戴拒分解。
 */
class EquipmentUpgradeServiceTest {

    /** 固定种子 RNG（强化节点判定确定性） */
    private fun rngManager() = GameRngManager().apply { initSystemSeed(20260930L) }

    private fun affix(rolls: List<Int> = listOf(1, 1, 1)) = EquipAffixSet(
        mainStat = EquipStatValue(EquipStat.ATTACK, 10.0),
        subStats = listOf(
            EquipStatValue(EquipStat.DEFENSE, 3.0),
            EquipStatValue(EquipStat.HP, 30.0),
            EquipStatValue(EquipStat.CRIT_RATE, 0.01)
        ),
        subRolls = rolls
    )

    private fun instance(
        id: String = "e1",
        level: Int = 1,
        rarity: Int = 1,
        rolls: List<Int> = listOf(1, 1, 1),
        isEquipped: Boolean = false,
        isLocked: Boolean = false
    ) = EquipmentInstance(
        id = id,
        name = "测试装备",
        setId = "lietian",
        part = EquipmentSlot.WEAPON,
        growth = EquipGrowth(level = level, affix = affix(rolls)),
        meta = com.xianxia.sect.core.model.EquipInstanceMeta(rarity = rarity),
        isEquipped = isEquipped
    ).let { if (isLocked) it.copy(meta = it.meta.copy(isLocked = true)) else it }

    private fun storeWith(
        stones: Long = 1_000_000L,
        equipment: List<EquipmentInstance>,
        materials: List<Material> = listOf(
            Material(id = "beast-a", name = "凡兽材", rarity = 1, quantity = 100),
            Material(id = "beast-b", name = "妖兽材", rarity = 2, quantity = 100)
        )
    ) = FakeGameStateStore().apply {
        gameDataValue = gameDataValue.copy(spiritStones = stones)
        equipmentInstancesValue = equipment
        materialsValue = materials
    }

    // ── 升级 ─────────────────────────────────────────────────

    @Test
    fun `正常升级扣灵石兽材且等级加一`() {
        val store = storeWith(equipment = listOf(instance(level = 1, rarity = 1)))
        val service = EquipmentUpgradeService(store, rngManager())
        val result = service.upgradeEquipment("e1")
        assertTrue("升级应成功", result is DomainResult.Success)
        val upgraded = store.equipmentInstancesValue.single()
        assertEquals(2, upgraded.level)
        assertEquals("升级材料直接折算该级经验，升级后 exp 归零", 0, upgraded.exp)

        val stoneCost = EquipmentLevelSystem.spiritStonesCost(1, 1)
        val beastCost = EquipmentLevelSystem.beastMaterialCost(1)
        assertEquals(1_000_000L - stoneCost, store.gameDataValue.spiritStones)
        val lowRarity = store.materialsValue.first { it.id == "beast-a" }
        assertEquals("应扣低稀有度堆叠", 100 - beastCost, lowRarity.quantity)
    }

    @Test
    fun `灵石不足失败且零改动`() {
        val stoneCost = EquipmentLevelSystem.spiritStonesCost(1, 1)
        val store = storeWith(stones = stoneCost - 1, equipment = listOf(instance()))
        val before = store.materialsValue.toList()
        val service = EquipmentUpgradeService(store, rngManager())
        val result = service.upgradeEquipment("e1")
        assertTrue("灵石不足应失败", result is DomainResult.Failure)
        assertEquals("灵石不得扣减", stoneCost - 1, store.gameDataValue.spiritStones)
        assertEquals("兽材不得扣减", before, store.materialsValue)
        assertEquals("实例不得变化", 1, store.equipmentInstancesValue.single().level)
    }

    @Test
    fun `兽材不足失败且零改动`() {
        val beastCost = EquipmentLevelSystem.beastMaterialCost(1)
        val store = storeWith(
            equipment = listOf(instance()),
            materials = listOf(Material(id = "beast-a", name = "凡兽材", rarity = 1, quantity = beastCost - 1))
        )
        val service = EquipmentUpgradeService(store, rngManager())
        val result = service.upgradeEquipment("e1")
        assertTrue("兽材不足应失败", result is DomainResult.Failure)
        assertEquals("实例不得变化", 1, store.equipmentInstancesValue.single().level)
        assertEquals(beastCost - 1, store.materialsValue.single().quantity)
    }

    @Test
    fun `满级升级失败`() {
        val store = storeWith(equipment = listOf(instance(level = 30)))
        val service = EquipmentUpgradeService(store, rngManager())
        val result = service.upgradeEquipment("e1")
        assertTrue("满级应失败", result is DomainResult.Failure)
        assertEquals(30, store.equipmentInstancesValue.single().level)
    }

    @Test
    fun `未知装备升级失败`() {
        val store = storeWith(equipment = listOf(instance()))
        val service = EquipmentUpgradeService(store, rngManager())
        assertTrue(service.upgradeEquipment("不存在") is DomainResult.Failure)
    }

    @Test
    fun `强化节点subRolls加一且同种子确定`() {
        // L2→L3 触发 newLevel%3==0
        val makeStore = {
            storeWith(equipment = listOf(instance(id = "e1", level = 2, rolls = listOf(1, 2, 3))))
        }
        val storeA = makeStore()
        val serviceA = EquipmentUpgradeService(storeA, rngManager())
        serviceA.upgradeEquipment("e1")
        val rollsA = storeA.equipmentInstancesValue.single().growth.affix.subRolls

        val storeB = makeStore()
        val serviceB = EquipmentUpgradeService(storeB, rngManager())
        serviceB.upgradeEquipment("e1")
        val rollsB = storeB.equipmentInstancesValue.single().growth.affix.subRolls

        assertEquals("同种子强化结果应逐位一致", rollsA, rollsB)
        assertEquals("恰好一条副词条 +1", 6 + 1, rollsA.sum())
        assertTrue("强化次数不得越上限", rollsA.all { it <= EquipLevelCurve.MAX_SUB_ROLLS })
    }

    @Test
    fun `非3倍数升级不触发强化`() {
        // L3→L4：newLevel=4 非强化节点
        val store = storeWith(equipment = listOf(instance(id = "e1", level = 3, rolls = listOf(2, 2, 2))))
        val service = EquipmentUpgradeService(store, rngManager())
        service.upgradeEquipment("e1")
        assertEquals(listOf(2, 2, 2), store.equipmentInstancesValue.single().growth.affix.subRolls)
    }

    @Test
    fun `强化次数达上限不再增长`() {
        // 三条全部 11：任意命中都不会再涨
        val store = storeWith(
            equipment = listOf(instance(id = "e1", level = 2, rolls = listOf(11, 11, 11)))
        )
        val service = EquipmentUpgradeService(store, rngManager())
        service.upgradeEquipment("e1")
        assertEquals(3, store.equipmentInstancesValue.single().level)
        assertEquals(listOf(11, 11, 11), store.equipmentInstancesValue.single().growth.affix.subRolls)
    }

    @Test
    fun `兽材按rarity升序逐堆叠扣`() {
        // cost=1：只应动 rarity=1 的 beast-a
        val store = storeWith(
            equipment = listOf(instance(id = "e1", level = 9)),
            materials = listOf(
                Material(id = "beast-b", name = "妖兽材", rarity = 2, quantity = 5),
                Material(id = "beast-a", name = "凡兽材", rarity = 1, quantity = 5)
            )
        )
        val service = EquipmentUpgradeService(store, rngManager())
        assertTrue(service.upgradeEquipment("e1") is DomainResult.Success)
        val byId = store.materialsValue.associateBy { it.id }
        assertEquals(4, byId.getValue("beast-a").quantity)
        assertEquals("高稀有度堆叠不得先扣", 5, byId.getValue("beast-b").quantity)
    }

    @Test
    fun `跨堆叠扣减-低稀有度耗尽再动高稀有度`() {
        // L20→L21 兽材 cost=2：beast-a 只剩 1 件 → 先扣完它再扣 beast-b 1 件
        val store = storeWith(
            equipment = listOf(instance(id = "e1", level = 20)),
            materials = listOf(
                Material(id = "beast-a", name = "凡兽材", rarity = 1, quantity = 1),
                Material(id = "beast-b", name = "妖兽材", rarity = 2, quantity = 5)
            )
        )
        val service = EquipmentUpgradeService(store, rngManager())
        assertTrue(service.upgradeEquipment("e1") is DomainResult.Success)
        val byId = store.materialsValue.associateBy { it.id }
        assertNull("低稀有度堆叠应被扣空移除", byId["beast-a"])
        assertEquals(4, byId.getValue("beast-b").quantity)
    }

    // ── 分解 ─────────────────────────────────────────────────

    @Test
    fun `分解返还50累计消耗并移除实例`() {
        // L4 r1：累计灵石 100+200+300=600 → 返 300；兽材 1+1+1=3 → floor(1.5)=1
        val store = storeWith(equipment = listOf(instance(id = "e1", level = 4)))
        val service = EquipmentUpgradeService(store, rngManager())
        val result = service.dismantleEquipment("e1")
        assertTrue("分解应成功", result is DomainResult.Success)
        assertEquals(1_000_000L + 300L, store.gameDataValue.spiritStones)
        assertEquals("兽材返还铸入 rarity=1 堆叠", 101, store.materialsValue.first { it.id == "beast-a" }.quantity)
        assertTrue("实例应移除", store.equipmentInstancesValue.isEmpty())
    }

    @Test
    fun `分解剥离储物袋同件条目防复活`() {
        val store = FakeGameStateStore().apply {
            gameDataValue = gameDataValue.copy(spiritStones = 0L)
            equipmentInstancesValue = listOf(instance(id = "e1", level = 4))
        }
        // 预置弟子与其储物袋内的同件条目
        store.update {
            discipleTables.replaceAll(
                listOf(
                    com.xianxia.sect.core.model.Disciple(
                        id = "1", name = "测试弟子", realm = 9
                    )
                )
            )
            val discipleId = 1
            discipleTables.storageBagItems[discipleId] = listOf(
                com.xianxia.sect.core.model.StorageBagItem(
                    itemId = "e1",
                    itemType = "equipment_instance",
                    name = "测试装备",
                    rarity = 1,
                    quantity = 1
                )
            )
        }
        val service = EquipmentUpgradeService(store, rngManager())
        assertTrue(service.dismantleEquipment("e1") is DomainResult.Success)
        assertTrue(
            "袋内同件条目应被剥离",
            store.discipleTables.storageBagItems[1].isNullOrEmpty()
        )
    }

    @Test
    fun `锁定装备拒分解`() {
        val store = storeWith(equipment = listOf(instance(id = "e1", level = 4, isLocked = true)))
        val service = EquipmentUpgradeService(store, rngManager())
        assertTrue(service.dismantleEquipment("e1") is DomainResult.Failure)
        assertNotNull(store.equipmentInstancesValue.single())
    }

    @Test
    fun `已穿戴装备拒分解`() {
        val store = storeWith(equipment = listOf(instance(id = "e1", level = 4, isEquipped = true)))
        val service = EquipmentUpgradeService(store, rngManager())
        assertTrue(service.dismantleEquipment("e1") is DomainResult.Failure)
        assertNotNull(store.equipmentInstancesValue.single())
    }

    @Test
    fun `未知装备分解失败`() {
        val store = storeWith(equipment = listOf(instance()))
        val service = EquipmentUpgradeService(store, rngManager())
        assertTrue(service.dismantleEquipment("不存在") is DomainResult.Failure)
    }
}
