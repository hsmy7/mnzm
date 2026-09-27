package com.xianxia.sect.data.integrity.rules

import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.DiscipleStatus
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.GridBuildingData
import com.xianxia.sect.core.model.LibrarySlot
import com.xianxia.sect.core.model.ResidenceSlot
import com.xianxia.sect.core.model.SpiritMineSlot
import com.xianxia.sect.core.model.production.BuildingType
import com.xianxia.sect.core.model.production.ProductionSlot
import com.xianxia.sect.data.integrity.IntegrityResult
import com.xianxia.sect.data.integrity.SaveValidator
import com.xianxia.sect.data.model.SaveData
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 执法堂 / 监牢下线清理规则测试 — 覆盖 [LawEnforcementPrisonCleanupRule]：
 * 旧档残留建筑与关联槽位清理 + 思过/执法弟子状态归一化，且**幂等**（清完即 Passed）。
 */
class LawEnforcementPrisonCleanupRuleTest {

    @Before
    fun setup() {
        SaveValidationRuleRegistry.clear()
        SaveValidationRuleRegistry.register(LawEnforcementPrisonCleanupRule)
    }

    @After
    fun teardown() {
        SaveValidationRuleRegistry.clear()
    }

    @Test
    fun `validate - 无残留时 Passed 不触发落盘`() {
        val gd = GameData(sectName = "宗", gameYear = 1, gameMonth = 1)
        val result = SaveValidator.validate(saveData(gd))

        assertTrue("无残留必须 Passed（避免每次校验都落盘）", result is IntegrityResult.Passed)
    }

    @Test
    fun `validate - 两栋下线建筑与关联槽位一并移除 其余建筑原样保留`() {
        val gd = GameData(
            sectName = "宗", gameYear = 1, gameMonth = 1,
            placedBuildings = listOf(
                building("law_enforcement_hall", "执法堂", "law-1"),
                building("reflection_cliff", "监牢", "jail-1"),
                building("mission_hall", "任务阁", "keep-1")
            ),
            spiritMineSlots = listOf(
                SpiritMineSlot(index = 0, discipleId = "1", buildingInstanceId = "law-1"),
                SpiritMineSlot(index = 1, discipleId = "2", buildingInstanceId = "keep-1")
            ),
            librarySlots = listOf(
                LibrarySlot(index = 0, discipleId = "3", buildingInstanceId = "jail-1"),
                LibrarySlot(index = 1, discipleId = "4", buildingInstanceId = "keep-1")
            ),
            residenceSlots = listOf(
                ResidenceSlot(discipleId = "5", buildingInstanceId = "jail-1"),
                ResidenceSlot(discipleId = "6", buildingInstanceId = "keep-1")
            ),
            productionSlots = listOf(
                ProductionSlot(buildingType = BuildingType.ALCHEMY, buildingInstanceId = "law-1"),
                ProductionSlot(buildingType = BuildingType.FORGE, buildingInstanceId = "keep-1")
            )
        )

        val result = SaveValidator.validate(saveData(gd))

        assertTrue(result is IntegrityResult.Repaired)
        val cleaned = (result as IntegrityResult.Repaired).data.gameData
        assertEquals(
            "仅下线建筑被移除",
            listOf("keep-1"), cleaned.placedBuildings.map { it.instanceId }
        )
        assertEquals("下线实例的矿场槽被清", listOf("2"), cleaned.spiritMineSlots.map { it.discipleId })
        assertEquals("下线实例的藏经阁槽被清", listOf("4"), cleaned.librarySlots.map { it.discipleId })
        assertEquals("下线实例的住所槽被清", listOf("6"), cleaned.residenceSlots.map { it.discipleId })
        assertEquals("下线实例的生产槽被清", listOf("keep-1"), cleaned.productionSlots.map { it.buildingInstanceId })
        assertTrue("修复明细含建筑清理记录", result.details.any { it.contains("建筑实例 2 个") })
    }

    @Test
    fun `validate - REFLECTING 归一化为 IDLE 并剥离思过与职位键`() {
        val disciplinary = disciple(
            "1", DiscipleStatus.REFLECTING,
            mapOf("reflectionStartYear" to "3", "reflectionEndYear" to "4", "positionName" to "-")
        )
        val result = SaveValidator.validate(saveData(GameData(sectName = "宗"), listOf(disciplinary)))

        assertTrue(result is IntegrityResult.Repaired)
        val fixed = (result as IntegrityResult.Repaired).data.disciples.single()
        assertEquals("思过中必须归一化为空闲", DiscipleStatus.IDLE, fixed.status)
        assertTrue("思过双键与职位名一并剥离", fixed.statusData.isEmpty())
    }

    @Test
    fun `validate - LAW_ENFORCING 归一化为 IDLE`() {
        val enforcer = disciple("2", DiscipleStatus.LAW_ENFORCING, emptyMap())
        val result = SaveValidator.validate(saveData(GameData(sectName = "宗"), listOf(enforcer)))

        assertTrue(result is IntegrityResult.Repaired)
        val fixed = (result as IntegrityResult.Repaired).data.disciples.single()
        assertEquals(DiscipleStatus.IDLE, fixed.status)
    }

    @Test
    fun `validate - 未涉及的状态与键零改动`() {
        val normal = disciple(
            "3", DiscipleStatus.ALCHEMY,
            mapOf("positionName" to "炼丹弟子", "buildingId" to "alchemy")
        )
        val result = SaveValidator.validate(saveData(GameData(sectName = "宗"), listOf(normal)))

        assertTrue("无下线状态即无弟子侧修复", result is IntegrityResult.Passed)
    }

    @Test
    fun `validate - 二次校验返回 Passed（幂等）`() {
        val gd = GameData(
            sectName = "宗", gameYear = 1, gameMonth = 1,
            placedBuildings = listOf(building("reflection_cliff", "监牢", "jail-1"))
        )
        val first = SaveValidator.validate(
            saveData(gd, listOf(disciple("1", DiscipleStatus.REFLECTING, mapOf("reflectionEndYear" to "4"))))
        )
        assertTrue(first is IntegrityResult.Repaired)
        val cleaned = (first as IntegrityResult.Repaired).data

        val second = SaveValidator.validate(cleaned)
        assertTrue("清理后再次校验必须 Passed", second is IntegrityResult.Passed)
    }

    @Test
    fun `registry - 默认注册表含 law_enforcement_prison_cleanup 且 order 唯一`() {
        SaveValidationRuleRegistry.clear()
        SaveValidationRuleRegistry.registerDefaults()

        val rule = SaveValidationRuleRegistry.findById("law_enforcement_prison_cleanup")
        assertNotNull("registerDefaults 必须包含 id=law_enforcement_prison_cleanup", rule)
        assertEquals(24, rule!!.order)
        assertTrue(rule === LawEnforcementPrisonCleanupRule)
    }

    private fun saveData(
        gd: GameData,
        disciples: List<Disciple> = emptyList()
    ): SaveData = SaveData(
        gameData = gd, disciples = disciples, pills = emptyList(),
        materials = emptyList(), herbs = emptyList(), seeds = emptyList(),
    )

    private fun building(key: String, name: String, instanceId: String) = GridBuildingData(
        buildingId = key, displayName = name, gridX = 0, gridY = 0,
        width = 2, height = 2, instanceId = instanceId
    )

    private fun disciple(
        id: String,
        status: DiscipleStatus,
        statusData: Map<String, String>
    ): Disciple = Disciple(
        id = id,
        name = "弟子$id",
        realm = 1,
        realmLayer = 1,
        status = status,
        statusData = statusData
    )
}
