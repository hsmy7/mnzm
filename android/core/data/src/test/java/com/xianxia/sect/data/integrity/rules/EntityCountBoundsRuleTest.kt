package com.xianxia.sect.data.integrity.rules

import com.xianxia.sect.core.model.BattleLog
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.EquipmentInstance
import com.xianxia.sect.core.model.EquipmentSet
import com.xianxia.sect.core.model.EquipmentStack
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.ManualStack
import com.xianxia.sect.core.model.StorageBagItem
import com.xianxia.sect.data.integrity.IntegrityResult
import com.xianxia.sect.data.integrity.SaveValidator
import com.xianxia.sect.data.model.SaveData
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test


class EntityCountBoundsRuleTest {

    @Before
    fun setup() {
        SaveValidationRuleRegistry.clear()
        SaveValidationRuleRegistry.register(EntityCountBoundsRule)
    }

    @After
    fun teardown() {
        SaveValidationRuleRegistry.clear()
    }

    @Test
    fun `normal counts pass`() {
        val data = saveData()
        assertEquals(IntegrityResult.Passed, SaveValidator.validate(data))
    }

    @Test
    fun `battle logs over hard cap truncated keeping newest by timestamp`() {
        val logs = (0..5000).map { BattleLog(id = "b-$it", timestamp = it.toLong(), year = 1, month = 1) }
        val data = saveData(battleLogs = logs)
        val result = SaveValidator.validate(data)
        assertTrue(result is IntegrityResult.Repaired)
        val kept = (result as IntegrityResult.Repaired).data.battleLogs
        assertEquals(5000, kept.size)
        // 保留时间戳最大的 5000 条：1..5000，timestamp=0 被移除
        assertTrue(kept.none { it.timestamp == 0L })
        assertTrue(kept.any { it.timestamp == 5000L })
    }

    @Test
    fun `battle logs above warn threshold but below hard cap untouched`() {
        // 2001 条 > 警告阈值 2000，但 < 硬上限 5000 → 数据不修改（警告语义保留）
        val logs = (0 until 2001).map { BattleLog(id = "b-$it", year = 1, month = 1) }
        val data = saveData(battleLogs = logs)
        val result = SaveValidator.validate(data)
        assertTrue(result is IntegrityResult.Repaired)
        assertEquals(2001, (result as IntegrityResult.Repaired).data.battleLogs.size)
    }

    @Test
    fun `equipment instances warn-only without truncation - B3 no hard cap`() {
        // B3（0.2-2 拍板）：装备实例无硬上限，超阈值只告警不截断、引用不动；
        // 旧堆叠载体（deprecated）由补偿链接管，本规则完全不触碰
        val instances = (0 until 801).map { EquipmentInstance(id = "inst-$it", name = "剑") }
        val d = makeDisciple(equipment = EquipmentSet(weaponId = "inst-500"))
        val data = saveData(equipmentInstances = instances, disciples = listOf(d))
        val result = SaveValidator.validate(data)
        assertTrue(result is IntegrityResult.Repaired)
        val fixed = (result as IntegrityResult.Repaired)
        assertEquals("装备实例不截断", 801, fixed.data.equipmentInstances.size)
        assertEquals("引用不动", "inst-500", fixed.data.disciples.first().equipment.weaponId)
        assertTrue("告警详情点名装备阈值", fixed.details.any { it.contains("装备实例") })
    }

    @Test
    fun `manual stacks over hard cap truncated and manualIds cleaned`() {
        val stacks = (0 until 50_001).map { ManualStack(id = "manual-$it", name = "心法") }
        val d = makeDisciple(manualIds = listOf("manual-1", "manual-50000"))
        val data = saveData(manualStacks = stacks, disciples = listOf(d))
        val result = SaveValidator.validate(data)
        assertTrue(result is IntegrityResult.Repaired)
        val fixed = (result as IntegrityResult.Repaired)
        assertEquals(50_000, fixed.data.manualStacks.size)
        // 被截断的 manual-50000 从弟子引用中移除，manual-1 保留
        assertEquals(listOf("manual-1"), fixed.data.disciples.first().manualIds)
    }

    @Test
    fun `disciples over hard cap judged corrupted`() {
        val disciples = (0 until 100_001).map { makeDisciple(id = "d-$it") }
        val data = saveData(disciples = disciples)
        val result = SaveValidator.validate(data)
        assertTrue(result is IntegrityResult.Corrupted)
    }

    @Test
    fun `kept equipment refs untouched when only legacy stacks present`() {
        // B3：旧堆叠载体不在本规则检查面（补偿链所有物）；仅堆叠在场时恒 Passed
        val stacks = (0 until 6000).map { EquipmentStack(id = "eq-$it", name = "剑") }
        val d = makeDisciple(equipment = EquipmentSet(weaponId = "eq-5000"))
        val data = saveData(equipmentStacks = stacks, disciples = listOf(d))
        val result = SaveValidator.validate(data)
        // 仅旧堆叠载体在场：不告警不截断恒 Passed（弟子引用不在本规则管辖内，原样保留）
        assertEquals(IntegrityResult.Passed, result)
    }

    // ── C10（2026-08-05）：截断后储物袋悬空引用清理 ──
    // D-03（2026-08-08）独立存储：袋条目持有自身数据（payload/stackedData），
    // 不再引用仓库堆叠——堆叠截断不影响袋条目，全部保留（清理反而误删玩家袋内物品）

    @Test
    fun `storageBagItems kept when equipment only warned - D03 independent storage`() {
        // B3：装备只告警不截断，袋条目（含旧装备三类）与六槽引用全部原样保留；
        // 引用式旧装备袋条目按防复制删除清点
        val instances = (0 until 801).map { EquipmentInstance(id = "inst-$it", name = "剑") }
        val d = makeDisciple(
            equipment = EquipmentSet(
                weaponId = "inst-0",
                storageBagItems = listOf(
                    StorageBagItem("eq-50000", "equipment", "旧装备", 1),
                    StorageBagItem("inst-keep", "equipment_instance", "实例", 1)
                )
            )
        )
        val data = saveData(equipmentInstances = instances, disciples = listOf(d))
        val result = SaveValidator.validate(data)
        assertTrue(result is IntegrityResult.Repaired)
        val fixed = (result as IntegrityResult.Repaired)
        val keptIds = fixed.data.disciples.first().equipment.storageBagItems.map { it.itemId }
        assertEquals("袋条目全部保留", listOf("eq-50000", "inst-keep"), keptIds)
        assertEquals("六槽引用保留", "inst-0", fixed.data.disciples.first().equipment.weaponId)
        assertEquals("装备实例不截断", 801, fixed.data.equipmentInstances.size)
    }

    @Test
    fun `storageBagItems kept when manual stacks truncated - D03 independent storage`() {
        val stacks = (0 until 50_001).map { ManualStack(id = "manual-$it", name = "心法") }
        val d = makeDisciple(
            equipment = EquipmentSet(
                storageBagItems = listOf(
                    StorageBagItem("manual-50000", "manual", "被截断功法", 1),
                    StorageBagItem("manual-3", "manual", "存活功法", 1)
                )
            )
        )
        val data = saveData(manualStacks = stacks, disciples = listOf(d))
        val result = SaveValidator.validate(data)
        assertTrue(result is IntegrityResult.Repaired)
        val keptIds = (result as IntegrityResult.Repaired).data.disciples.first()
            .equipment.storageBagItems.map { it.itemId }
        assertEquals(listOf("manual-50000", "manual-3"), keptIds)
    }

    private fun makeDisciple(
        id: String = "d-1",
        name: String = "甲",
        equipment: EquipmentSet = EquipmentSet(),
        manualIds: List<String> = emptyList()
    ) = Disciple(
        id = id, name = name, realm = 9, realmLayer = 1, cultivation = 10.0,
        isAlive = true,
        equipment = equipment, manualIds = manualIds
    )

    private fun saveData(
        disciples: List<Disciple> = emptyList(),
        equipmentStacks: List<EquipmentStack> = emptyList(),
        equipmentInstances: List<EquipmentInstance> = emptyList(),
        manualStacks: List<ManualStack> = emptyList(),
        battleLogs: List<BattleLog> = emptyList()
    ) = SaveData(
        gameData = GameData(sectName = "宗", gameYear = 5, gameMonth = 6),
        disciples = disciples, pills = emptyList(), materials = emptyList(),
        herbs = emptyList(), seeds = emptyList(),
        equipmentStacks = equipmentStacks, equipmentInstances = equipmentInstances,
        manualStacks = manualStacks, battleLogs = battleLogs
    )
}
