package com.xianxia.sect.data.integrity.rules

import com.xianxia.sect.core.model.BattleLog
import com.xianxia.sect.core.model.Disciple
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
    fun `equipment stacks over hard cap truncated and disciple refs cleared`() {
        val stacks = (0 until 50_001).map { EquipmentStack(id = "eq-$it", name = "剑") }
        // 弟子指向第 50000 个堆叠（会被截断移除）
        val d = makeDisciple(equipment = EquipmentSet(weaponId = "eq-50000"))
        val data = saveData(equipmentStacks = stacks, disciples = listOf(d))
        val result = SaveValidator.validate(data)
        assertTrue(result is IntegrityResult.Repaired)
        val fixed = (result as IntegrityResult.Repaired)
        assertEquals(50_000, fixed.data.equipmentStacks.size)
        // 悬空引用已清除
        assertEquals("", fixed.data.disciples.first().equipment.weaponId)
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
    fun `kept stack refs untouched when no truncation needed`() {
        // 不超硬上限时，即使超警告阈值，弟子引用不被误清
        val stacks = (0 until 6000).map { EquipmentStack(id = "eq-$it", name = "剑") }
        val d = makeDisciple(equipment = EquipmentSet(weaponId = "eq-5000"))
        val data = saveData(equipmentStacks = stacks, disciples = listOf(d))
        val result = SaveValidator.validate(data)
        assertTrue(result is IntegrityResult.Repaired)
        assertEquals("eq-5000", (result as IntegrityResult.Repaired).data.disciples.first().equipment.weaponId)
    }

    // ── C10（2026-08-05）：截断后储物袋悬空引用清理 ──
    // D-03（2026-08-08）独立存储：袋条目持有自身数据（payload/stackedData），
    // 不再引用仓库堆叠——堆叠截断不影响袋条目，全部保留（清理反而误删玩家袋内物品）

    @Test
    fun `storageBagItems kept when stacks truncated - D03 independent storage`() {
        // 袋条目物理上独立于仓库堆叠：堆叠被截断时袋条目原样保留
        val stacks = (0 until 50_001).map { EquipmentStack(id = "eq-$it", name = "剑") }
        val d = makeDisciple(
            equipment = EquipmentSet(
                storageBagItems = listOf(
                    StorageBagItem("eq-50000", "equipment", "被截断剑", 1),
                    StorageBagItem("eq-1", "equipment", "存活堆叠", 1),
                    StorageBagItem("inst-keep", "equipment_instance", "实例", 1)
                )
            )
        )
        val data = saveData(equipmentStacks = stacks, disciples = listOf(d))
        val result = SaveValidator.validate(data)
        assertTrue(result is IntegrityResult.Repaired)
        val keptItems = (result as IntegrityResult.Repaired).data.disciples.first().equipment.storageBagItems
        val keptIds = keptItems.map { it.itemId }
        // 三条袋条目全部保留（含指向被截断堆叠的 eq-50000）
        assertEquals(listOf("eq-50000", "eq-1", "inst-keep"), keptIds)
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
        age = 20, lifespan = 80, isAlive = true,
        equipment = equipment, manualIds = manualIds
    )

    private fun saveData(
        disciples: List<Disciple> = emptyList(),
        equipmentStacks: List<EquipmentStack> = emptyList(),
        manualStacks: List<ManualStack> = emptyList(),
        battleLogs: List<BattleLog> = emptyList()
    ) = SaveData(
        gameData = GameData(sectName = "宗", gameYear = 5, gameMonth = 6),
        disciples = disciples, pills = emptyList(), materials = emptyList(),
        herbs = emptyList(), seeds = emptyList(),
        equipmentStacks = equipmentStacks, manualStacks = manualStacks,
        battleLogs = battleLogs
    )
}
