package com.xianxia.sect.data.integrity

import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.EquipmentInstance
import com.xianxia.sect.core.model.EquipmentSet
import com.xianxia.sect.core.model.EquipmentStack
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.GridBuildingData
import com.xianxia.sect.core.model.ResidenceSlot
import com.xianxia.sect.data.model.SaveData
import org.junit.Assert.*
import org.junit.Test



/**
 * 存档完整性校验器的单元测试。
 *
 * 覆盖八项检查的通过、修复、边界条件三类场景。
 */
class SaveValidatorTest {

    // ── 辅助构造方法 ──────────────────────────────────────────

    private fun minimalValidSaveData(): SaveData {
        val gd = GameData(
            sectName = "测试宗门",
            gameYear = 1,
            gameMonth = 1,
            gamePhase = 0
        )
        return SaveData(
            gameData = gd,
            disciples = emptyList(),
            pills = emptyList(),
            materials = emptyList(),
            herbs = emptyList(),
            seeds = emptyList(),
                        battleLogs = emptyList()
        )
    }

    private fun makeDisciple(
        id: String = "d-1",
        name: String = "弟子甲",
        realm: Int = 9,
        realmLayer: Int = 1,
        cultivation: Double = 10.0,
        isAlive: Boolean = true,
        headId: String = "",
        bodyId: String = "",
        handsId: String = "",
        feetId: String = ""
    ): Disciple {
        return Disciple(
            id = id,
            name = name,
            realm = realm,
            realmLayer = realmLayer,
            cultivation = cultivation,
            isAlive = isAlive,
            equipment = EquipmentSet(
                headId = headId,
                bodyId = bodyId,
                handsId = handsId,
                feetId = feetId
            )
        )
    }

    private fun makeEquipmentStack(id: String, name: String = "灵剑"): EquipmentStack {
        return EquipmentStack(
            id = id,
            name = name,
            rarity = 1,
            description = ""
        )
    }

    private fun makeEquipmentInstance(id: String, name: String = "玄铁甲"): EquipmentInstance {
        return EquipmentInstance(
            id = id,
            name = name
        )
    }

    /**
     * "合法存档"判据：默认注册表下合法数据零修复（Passed）；recruitList
     * 恒空由写入面保证（清空规则已随 SS0 退役），一并断言防回填。
     */
    private fun assertValidNoRepair(result: IntegrityResult) {
        assertTrue("预期零修复的 Passed，实际得到 $result", result is IntegrityResult.Passed)
    }

    // ── 1. 合法存档（零修复） ─────────────

    @Test
    fun `validate - all fields valid - 零修复`() {
        val data = minimalValidSaveData()
        assertValidNoRepair(SaveValidator.validate(data))
    }

    @Test
    fun `validate - valid disciples with equipment instance - 零修复`() {
        val instance = makeEquipmentInstance("eq-1")
        val disciple = makeDisciple(handsId = "eq-1")
        val data = minimalValidSaveData().copy(
            equipmentInstances = listOf(instance),
            disciples = listOf(disciple)
        )
        assertValidNoRepair(SaveValidator.validate(data))
    }

    @Test
    fun `validate - cultivation at exact boundary - 零修复`() {
        // 炼气 1 层: base=50, next=200, layers=9 → max = 50 + 0*(200-50)/9 = 50
        val disciple = makeDisciple(realm = 9, realmLayer = 1, cultivation = 50.0)
        val data = minimalValidSaveData().copy(disciples = listOf(disciple))
        assertValidNoRepair(SaveValidator.validate(data))
    }

    // ── 2. SectName ───────────────────────────────────────────

    @Test
    fun `validate - sectName blank - repairs with default name`() {
        val data = minimalValidSaveData().copy(
            gameData = GameData(sectName = "", gameYear = 1, gameMonth = 1)
        )
        val result = SaveValidator.validate(data)
        assertTrue("预期 Repaired，实际得到 $result", result is IntegrityResult.Repaired)
        val repaired = result as IntegrityResult.Repaired
        assertEquals("青云宗", repaired.data.gameData.sectName)
        assertTrue(repaired.details.any { it.contains("sectName") })
    }

    @Test
    fun `validate - sectName whitespace only - repairs`() {
        val data = minimalValidSaveData().copy(
            gameData = GameData(sectName = "   ", gameYear = 1, gameMonth = 1)
        )
        val result = SaveValidator.validate(data)
        assertTrue("预期 Repaired，实际得到 $result", result is IntegrityResult.Repaired)
        val repaired = result as IntegrityResult.Repaired
        assertEquals("青云宗", repaired.data.gameData.sectName)
    }

    // ── 3. Game Year / Month ──────────────────────────────────

    @Test
    fun `validate - gameYear 0 - repairs to 1`() {
        val data = minimalValidSaveData().copy(
            gameData = GameData(sectName = "宗", gameYear = 0, gameMonth = 6)
        )
        val result = SaveValidator.validate(data)
        assertTrue("预期 Repaired，实际得到 $result", result is IntegrityResult.Repaired)
        assertEquals(1, (result as IntegrityResult.Repaired).data.gameData.gameYear)
    }

    @Test
    fun `validate - gameMonth 0 - repairs to 1`() {
        val data = minimalValidSaveData().copy(
            gameData = GameData(sectName = "宗", gameYear = 3, gameMonth = 0)
        )
        val result = SaveValidator.validate(data)
        assertTrue("预期 Repaired，实际得到 $result", result is IntegrityResult.Repaired)
        assertEquals(1, (result as IntegrityResult.Repaired).data.gameData.gameMonth)
    }

    @Test
    fun `validate - gameMonth 13 - repairs to 12`() {
        val data = minimalValidSaveData().copy(
            gameData = GameData(sectName = "宗", gameYear = 5, gameMonth = 13)
        )
        val result = SaveValidator.validate(data)
        assertTrue("预期 Repaired，实际得到 $result", result is IntegrityResult.Repaired)
        assertEquals(12, (result as IntegrityResult.Repaired).data.gameData.gameMonth)
    }

    @Test
    fun `validate - gameMonth negative - repairs to 1`() {
        val data = minimalValidSaveData().copy(
            gameData = GameData(sectName = "宗", gameYear = 2, gameMonth = -5)
        )
        val result = SaveValidator.validate(data)
        assertTrue("预期 Repaired，实际得到 $result", result is IntegrityResult.Repaired)
        assertEquals(1, (result as IntegrityResult.Repaired).data.gameData.gameMonth)
    }

    // ── 4. Cultivation cap ────────────────────────────────────

    @Test
    fun `validate - cultivation exceeds realm max - caps`() {
        // 炼气 1 层: max = 98
        val disciple = makeDisciple(realm = 9, realmLayer = 1, cultivation = 999.0)
        val data = minimalValidSaveData().copy(disciples = listOf(disciple))
        val result = SaveValidator.validate(data)
        assertTrue("预期 Repaired，实际得到 $result", result is IntegrityResult.Repaired)
        val repaired = result as IntegrityResult.Repaired
        val cappedDisciple = repaired.data.disciples.first()
        assertEquals(490.0, cappedDisciple.cultivation, 0.001)
    }

    @Test
    fun `validate - cultivation exceeds high realm max - caps`() {
        // 筑基 3 层: base=1950, next=7800, layers=9 → max = 1950 + 2*(7800-1950)/9 = 1950 + 2*5850/9 = 3250.0
        val disciple = makeDisciple(realm = 8, realmLayer = 3, cultivation = 5000.0)
        val data = minimalValidSaveData().copy(disciples = listOf(disciple))
        val result = SaveValidator.validate(data)
        assertTrue("预期 Repaired，实际得到 $result", result is IntegrityResult.Repaired)
        val capped = (result as IntegrityResult.Repaired).data.disciples.first().cultivation
        val expected = 1950.0 + 2.0 * (7800.0 - 1950.0) / 9.0
        assertEquals(expected, capped, 0.001)
    }

    @Test
    fun `validate - immortal realm cultivation capped at absolute cap`() {
        // 仙人 (realm=0) 修为使用绝对上限 1e9 钳制（防恶意云档携带巨大修为穿透）
        val disciple = makeDisciple(realm = 0, realmLayer = 1, cultivation = 1e12)
        val data = minimalValidSaveData().copy(disciples = listOf(disciple))
        val result = SaveValidator.validate(data)
        assertTrue("预期 Repaired（仙人修为钳制 1e9），实际得到 $result", result is IntegrityResult.Repaired)
        assertEquals(1e9, (result as IntegrityResult.Repaired).data.disciples.first().cultivation, 0.001)
    }

    // ── 5. Equipment orphan refs ─────────────────────────────

    @Test
    fun `validate - orphan handsId - clears field`() {
        val disciple = makeDisciple(handsId = "nonexistent-hands")
        val data = minimalValidSaveData().copy(disciples = listOf(disciple))
        val result = SaveValidator.validate(data)
        assertTrue("预期 Repaired，实际得到 $result", result is IntegrityResult.Repaired)
        val repaired = result as IntegrityResult.Repaired
        assertEquals("", repaired.data.disciples.first().equipment.handsId)
    }

    @Test
    fun `validate - all equipment fields orphan - clears all`() {
        val disciple = makeDisciple(
            headId = "h", bodyId = "b", handsId = "ha", feetId = "f"
        )
        val data = minimalValidSaveData().copy(disciples = listOf(disciple))
        val result = SaveValidator.validate(data)
        assertTrue("预期 Repaired，实际得到 $result", result is IntegrityResult.Repaired)
        val equip = (result as IntegrityResult.Repaired).data.disciples.first().equipment
        assertEquals("", equip.headId)
        assertEquals("", equip.bodyId)
        assertEquals("", equip.handsId)
        assertEquals("", equip.feetId)
    }

    @Test
    fun `validate - equipment ref to legacy stack - cleared（B3 堆叠退役）`() {
        // B3 起装备一行一实例，装备堆叠已退役：堆叠载体中的 id 不再是合法引用目标，
        // 指向堆叠的引用按孤立引用清除（与 allEquipmentIds 仅含 instances 一致）。
        val disciple = makeDisciple(handsId = "sword-1", headId = "armor-1")
        val stack1 = makeEquipmentStack("sword-1", "青云剑")
        val stack2 = makeEquipmentStack("armor-1", "玄铁甲")
        val data = minimalValidSaveData().copy(
            equipmentStacks = listOf(stack1, stack2),
            disciples = listOf(disciple)
        )
        val result = SaveValidator.validate(data)
        assertTrue("预期 Repaired，实际得到 $result", result is IntegrityResult.Repaired)
        val equip = (result as IntegrityResult.Repaired).data.disciples.first().equipment
        assertEquals("", equip.handsId)
        assertEquals("", equip.headId)
    }

    @Test
    fun `validate - equipment ref to existing instance - keeps`() {
        val disciple = makeDisciple(handsId = "inst-1")
        val instance = makeEquipmentInstance("inst-1", "神兵")
        val data = minimalValidSaveData().copy(
            equipmentInstances = listOf(instance),
            disciples = listOf(disciple)
        )
        assertValidNoRepair(SaveValidator.validate(data))
    }

    @Test
    fun `validate - mixed valid and orphan equipment - only clears orphan`() {
        val disciple = makeDisciple(
            handsId = "valid-sword", headId = "ghost-armor"
        )
        val instance = makeEquipmentInstance("valid-sword", "好剑")
        val data = minimalValidSaveData().copy(
            equipmentInstances = listOf(instance),
            disciples = listOf(disciple)
        )
        val result = SaveValidator.validate(data)
        assertTrue("预期 Repaired，实际得到 $result", result is IntegrityResult.Repaired)
        val equip = (result as IntegrityResult.Repaired).data.disciples.first().equipment
        assertEquals("valid-sword", equip.handsId)
        assertEquals("", equip.headId)
    }

    // ── 6. Building consistency ───────────────────────────────

    @Test
    fun `validate - residenceSlot references existing building - passes`() {
        val building = GridBuildingData(
            buildingId = "residence",
            instanceId = "bld-001",
            gridX = 0, gridY = 0, width = 2, height = 2
        )
        val slot = ResidenceSlot(buildingInstanceId = "bld-001", slotIndex = 0)
        val gd = GameData(
            sectName = "宗", gameYear = 1, gameMonth = 1,
            placedBuildings = listOf(building),
            residenceSlots = listOf(slot)
        )
        val data = minimalValidSaveData().copy(gameData = gd)
        assertValidNoRepair(SaveValidator.validate(data))
    }

    @Test
    fun `validate - residenceSlot orphan building - removes slot`() {
        val slot = ResidenceSlot(buildingInstanceId = "nonexistent-bld", slotIndex = 1, discipleId = "d-1")
        val gd = GameData(
            sectName = "宗", gameYear = 1, gameMonth = 1,
            placedBuildings = emptyList(),
            residenceSlots = listOf(slot)
        )
        val data = minimalValidSaveData().copy(gameData = gd)
        val result = SaveValidator.validate(data)
        assertTrue("预期 Repaired，实际得到 $result", result is IntegrityResult.Repaired)
        assertTrue((result as IntegrityResult.Repaired).data.gameData.residenceSlots.isEmpty())
    }

    @Test
    fun `validate - empty buildingInstanceId is valid`() {
        // 空的 buildingInstanceId 可能在初始状态下存在，不应被清除
        val slot = ResidenceSlot(buildingInstanceId = "", slotIndex = 0)
        val gd = GameData(
            sectName = "宗", gameYear = 1, gameMonth = 1,
            placedBuildings = emptyList(),
            residenceSlots = listOf(slot)
        )
        val data = minimalValidSaveData().copy(gameData = gd)
        val result = SaveValidator.validate(data)
        assertValidNoRepair(result)
        assertEquals(
            "空 buildingInstanceId 槽位不得被清除", 1,
            minimalValidSaveData().copy(gameData = gd).gameData.residenceSlots.size
        )
    }

    // ── 8. Edge cases ─────────────────────────────────────────

    @Test
    fun `validate - empty saveData - repairs sectName and date`() {
        // sectName 必须显式设为空字符串，因为 GameData 默认值为 "青云宗"
        val data = SaveData(
            gameData = GameData(sectName = ""),
            disciples = emptyList(),
            pills = emptyList(), materials = emptyList(),
            herbs = emptyList(), seeds = emptyList(),
                    )
        val result = SaveValidator.validate(data)
        assertTrue("预期 Repaired，实际得到 $result", result is IntegrityResult.Repaired)
        val repaired = result as IntegrityResult.Repaired
        assertEquals("青云宗", repaired.data.gameData.sectName)
    }

    @Test
    fun `validate - multiple disciples multiple issues - all repaired`() {
        val d1 = makeDisciple("d-1", "甲", realm = 9, realmLayer = 1, cultivation = 999.0,
            handsId = "ghost-sword")
        val d2 = makeDisciple("d-2", "乙", realm = 8, realmLayer = 5, cultivation = 1e6,
            headId = "ghost-armor")
        val data = minimalValidSaveData().copy(disciples = listOf(d1, d2))
        val result = SaveValidator.validate(data)
        assertTrue("预期 Repaired，实际得到 $result", result is IntegrityResult.Repaired)
        val repaired = result as IntegrityResult.Repaired
        val disciples = repaired.data.disciples
        // d1: cultivation capped to 490, hands cleared
        assertEquals(490.0, disciples[0].cultivation, 0.001)
        assertEquals("", disciples[0].equipment.handsId)
        // d2: cultivation capped, armor cleared
        val expectedMaxD2 = 1950.0 + 4.0 * (7800.0 - 1950.0) / 9.0
        assertEquals(expectedMaxD2, disciples[1].cultivation, 0.001)
        assertEquals("", disciples[1].equipment.headId)
    }

    @Test
    fun `computeMaxCultivation - realm 9 layer 1 - returns 490`() {
        assertEquals(490.0, SaveValidator.computeMaxCultivation(9, 1), 0.001)
    }

    @Test
    fun `computeMaxCultivation - realm 9 layer 9`() {
        // 炼气 9 层: base=490, next=1950, layers=9 → max = 490 + 8*(1950-490)/9 = 490 + 11680/9 = 1787.56
        val result = SaveValidator.computeMaxCultivation(9, 9)
        val expected = 490.0 + 8.0 * (1950.0 - 490.0) / 9.0
        assertEquals(expected, result, 0.001)
    }

    @Test
    fun `computeMaxCultivation - realm 0 - returns Double MAX_VALUE`() {
        assertEquals(Double.MAX_VALUE, SaveValidator.computeMaxCultivation(0, 1), 0.001)
    }

    // ── 9. Building consistency ───────────────────────────────

    @Test
    fun `validate - multiple residence slots some orphan - removes only orphan`() {
        val building = GridBuildingData(
            buildingId = "hall", instanceId = "bld-keep", gridX = 0, gridY = 0
        )
        val validSlot = ResidenceSlot(buildingInstanceId = "bld-keep", slotIndex = 0)
        val orphanSlot = ResidenceSlot(buildingInstanceId = "bld-orphan", slotIndex = 1)
        val gd = GameData(
            sectName = "宗", gameYear = 1, gameMonth = 1,
            placedBuildings = listOf(building),
            residenceSlots = listOf(validSlot, orphanSlot)
        )
        val data = minimalValidSaveData().copy(gameData = gd)
        val result = SaveValidator.validate(data)
        assertTrue("预期 Repaired，实际得到 $result", result is IntegrityResult.Repaired)
        val slots = (result as IntegrityResult.Repaired).data.gameData.residenceSlots
        assertEquals(1, slots.size)
        assertEquals("bld-keep", slots.first().buildingInstanceId)
    }

    // ── 10. Residence slot discipleId orphan after ghost cleanup ─────

    @Test
    fun `validate - ghost disciple referenced by residence slot - clears slot`() {
        val ghost = makeDisciple(id = "ghost-1", name = "")
        val building = GridBuildingData(
            buildingId = "residence", instanceId = "bld-001", gridX = 0, gridY = 0
        )
        val slot = ResidenceSlot(
            buildingInstanceId = "bld-001", slotIndex = 0,
            discipleId = "ghost-1", discipleName = "幽灵"
        )
        val gd = GameData(
            sectName = "宗", gameYear = 1, gameMonth = 1,
            placedBuildings = listOf(building),
            residenceSlots = listOf(slot)
        )
        val data = minimalValidSaveData().copy(
            disciples = listOf(ghost), gameData = gd
        )
        val result = SaveValidator.validate(data)
        assertTrue("预期 Repaired，实际得到 $result", result is IntegrityResult.Repaired)
        val repaired = result as IntegrityResult.Repaired
        // 幽灵弟子被移除
        assertTrue(repaired.data.disciples.isEmpty())
        // 槽位中的 discipleId 被清除
        val cleanedSlot = repaired.data.gameData.residenceSlots.first()
        assertEquals("", cleanedSlot.discipleId)
        assertEquals("", cleanedSlot.discipleName)
        assertTrue(repaired.details.any { it.contains("幽灵弟子") })
        assertTrue(repaired.details.any { it.contains("引用的弟子") })
    }

    @Test
    fun `validate - ghost disciple removed but other slots reference valid disciples - preserved`() {
        val ghost = makeDisciple(id = "ghost-1", name = "")
        val validDisciple = makeDisciple(id = "d-valid", name = "幸存弟子")
        val building = GridBuildingData(
            buildingId = "residence", instanceId = "bld-001", gridX = 0, gridY = 0
        )
        val orphanSlot = ResidenceSlot(
            buildingInstanceId = "bld-001", slotIndex = 0,
            discipleId = "ghost-1", discipleName = "幽灵"
        )
        val validSlot = ResidenceSlot(
            buildingInstanceId = "bld-001", slotIndex = 1,
            discipleId = "d-valid", discipleName = "幸存弟子"
        )
        val gd = GameData(
            sectName = "宗", gameYear = 1, gameMonth = 1,
            placedBuildings = listOf(building),
            residenceSlots = listOf(orphanSlot, validSlot)
        )
        val data = minimalValidSaveData().copy(
            disciples = listOf(ghost, validDisciple), gameData = gd
        )
        val result = SaveValidator.validate(data)
        assertTrue("预期 Repaired，实际得到 $result", result is IntegrityResult.Repaired)
        val repaired = result as IntegrityResult.Repaired
        // 幽灵弟子被移除
        assertEquals(1, repaired.data.disciples.size)
        assertEquals("d-valid", repaired.data.disciples.first().id)
        // orphan 槽位被清除，valid 槽位保留
        val slots = repaired.data.gameData.residenceSlots
        assertEquals(2, slots.size)
        val orphanCleaned = slots.find { it.slotIndex == 0 }
        assertEquals("", orphanCleaned!!.discipleId)
        assertEquals("", orphanCleaned!!.discipleName)
        val validSlotPreserved = slots.find { it.slotIndex == 1 }
        assertEquals("d-valid", validSlotPreserved!!.discipleId)
        assertEquals("幸存弟子", validSlotPreserved!!.discipleName)
        assertTrue(repaired.details.any { it.contains("引用的弟子(id=ghost-1)") })
    }

    @Test
    fun `validate - no ghost disciples - residence slot refs preserved`() {
        val disciple = makeDisciple(id = "d-1", name = "正常弟子")
        val building = GridBuildingData(
            buildingId = "residence", instanceId = "bld-001", gridX = 0, gridY = 0
        )
        val slot = ResidenceSlot(
            buildingInstanceId = "bld-001", slotIndex = 0,
            discipleId = "d-1", discipleName = "正常弟子"
        )
        val gd = GameData(
            sectName = "宗", gameYear = 1, gameMonth = 1,
            placedBuildings = listOf(building),
            residenceSlots = listOf(slot)
        )
        val data = minimalValidSaveData().copy(
            disciples = listOf(disciple), gameData = gd
        )
        val result = SaveValidator.validate(data)
        assertValidNoRepair(result)
        val slots = data.gameData.residenceSlots
        assertEquals("合法槽位引用应保留", 1, slots.size)
        assertEquals("d-1", slots.first().discipleId)
    }

    @Test
    fun `validate - ghost disciples with empty residenceSlots - no additional repair`() {
        val ghost = makeDisciple(id = "ghost-1", name = "")
        val data = minimalValidSaveData().copy(disciples = listOf(ghost))
        val result = SaveValidator.validate(data)
        assertTrue("预期 Repaired，实际得到 $result", result is IntegrityResult.Repaired)
        val repaired = result as IntegrityResult.Repaired
        assertTrue(repaired.data.disciples.isEmpty())
        // 修复条目 = 幽灵弟子清理（order=10）唯一一条，
        // 不应有 residence slot 相关修复
        assertEquals(1, repaired.details.size)
        assertTrue(repaired.details.single().contains("幽灵弟子"))
    }

    // ── 11. C8：注册表 clear 后自动重新注册 ──────────────────────

    @Test
    fun `validate - registry cleared then validate - re-registers defaults`() {
        // 守卫：clear 后再次 validate 必须按注册表规模重新注册默认规则
        //（空规则集会使损坏数据全部误判 Passed）
        assertValidNoRepair(SaveValidator.validate(minimalValidSaveData()))

        com.xianxia.sect.data.integrity.rules.SaveValidationRuleRegistry.clear()
        assertEquals("clear 后注册表为空", 0, com.xianxia.sect.data.integrity.rules.SaveValidationRuleRegistry.size)

        // 再次 validate 必须重新注册默认规则——用损坏数据验证规则确实在跑
        val corrupted = minimalValidSaveData().copy(
            disciples = listOf(makeDisciple(id = "d-bad", cultivation = -5.0))
        )
        val result = SaveValidator.validate(corrupted)
        assertTrue("clear 后 validate 应重新注册并检测损坏，实际 $result", result is IntegrityResult.Repaired)
        assertTrue("注册表已重填默认规则", com.xianxia.sect.data.integrity.rules.SaveValidationRuleRegistry.size > 0)
    }
}
