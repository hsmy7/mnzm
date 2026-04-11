package com.xianxia.sect.core.engine

import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.DiscipleStatus
import com.xianxia.sect.core.model.Mission
import com.xianxia.sect.core.model.MissionDifficulty
import com.xianxia.sect.core.model.MissionTemplate
import com.xianxia.sect.core.model.SkillStats
import org.junit.Assert.*
import org.junit.Test

class MissionSystemTest {

    private fun createDisciple(
        id: String = "d1",
        name: String = "TestDisciple",
        realm: Int = 9,
        discipleType: String = "outer",
        isAlive: Boolean = true
    ): Disciple {
        return Disciple(
            id = id,
            name = name,
            realm = realm,
            isAlive = isAlive,
            discipleType = discipleType,
            skills = SkillStats(loyalty = 50)
        )
    }

    @Test
    fun `MissionDifficulty - 所有难度有显示名称`() {
        MissionDifficulty.values().forEach { difficulty ->
            assertTrue(difficulty.displayName.isNotEmpty())
        }
    }

    @Test
    fun `MissionDifficulty - 生成概率递减`() {
        assertTrue(MissionDifficulty.SIMPLE.spawnChance > MissionDifficulty.NORMAL.spawnChance)
        assertTrue(MissionDifficulty.NORMAL.spawnChance > MissionDifficulty.HARD.spawnChance)
        assertTrue(MissionDifficulty.HARD.spawnChance > MissionDifficulty.FORBIDDEN.spawnChance)
    }

    @Test
    fun `MissionDifficulty - 持续时间递增`() {
        assertTrue(MissionDifficulty.SIMPLE.durationMonths < MissionDifficulty.NORMAL.durationMonths)
        assertTrue(MissionDifficulty.NORMAL.durationMonths < MissionDifficulty.HARD.durationMonths)
        assertTrue(MissionDifficulty.HARD.durationMonths < MissionDifficulty.FORBIDDEN.durationMonths)
    }

    @Test
    fun `MissionTemplate - 所有模板有显示名称`() {
        MissionTemplate.values().forEach { template ->
            assertTrue(template.displayName.isNotEmpty())
        }
    }

    @Test
    fun `MissionTemplate - 所有模板有描述`() {
        MissionTemplate.values().forEach { template ->
            assertTrue(template.description.isNotEmpty())
        }
    }

    @Test
    fun `MissionTemplate - 所有模板需要6名弟子`() {
        MissionTemplate.values().forEach { template ->
            assertEquals(6, template.requiredMemberCount)
        }
    }

    @Test
    fun `Mission - memberCount 返回模板所需人数`() {
        val mission = Mission(
            template = MissionTemplate.ESCORT,
            name = "测试任务",
            description = "测试",
            difficulty = MissionDifficulty.SIMPLE,
            duration = 3,
            rewards = com.xianxia.sect.core.model.MissionRewardConfig()
        )
        assertEquals(6, mission.memberCount)
    }

    @Test
    fun `processMonthlyRefresh - 每3个月刷新任务`() {
        val result = MissionSystem.processMonthlyRefresh(
            existingMissions = emptyList(),
            currentYear = 1,
            currentMonth = 3
        )
        assertNotNull(result)
    }

    @Test
    fun `processMonthlyRefresh - 非刷新月份不生成新任务`() {
        val result = MissionSystem.processMonthlyRefresh(
            existingMissions = emptyList(),
            currentYear = 1,
            currentMonth = 2
        )
        assertEquals(0, result.newMissions.size)
    }

    @Test
    fun `EXPIRY_MONTHS 为3`() {
        assertEquals(3, MissionSystem.EXPIRY_MONTHS)
    }

    @Test
    fun `REFRESH_INTERVAL_MONTHS 为3`() {
        assertEquals(3, MissionSystem.REFRESH_INTERVAL_MONTHS)
    }

    @Test
    fun `MissionDifficulty - SIMPLE允许外门弟子`() {
        assertTrue(MissionDifficulty.SIMPLE.allowedPositions.contains("外门弟子"))
    }

    @Test
    fun `MissionDifficulty - FORBIDDEN不允许外门弟子`() {
        assertFalse(MissionDifficulty.FORBIDDEN.allowedPositions.contains("外门弟子"))
    }

    @Test
    fun `MissionDifficulty - HARD和FORBIDDEN允许内门弟子`() {
        assertTrue(MissionDifficulty.HARD.allowedPositions.contains("内门弟子"))
        assertTrue(MissionDifficulty.FORBIDDEN.allowedPositions.contains("内门弟子"))
    }
}
