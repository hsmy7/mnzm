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
        assertTrue(result.newMissions.size <= MissionSystem.MAX_REFRESH_COUNT)
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
    fun `processMonthlyRefresh - 刷新任务数量在0到5之间`() {
        val counts = mutableSetOf<Int>()
        repeat(200) {
            val result = MissionSystem.processMonthlyRefresh(
                existingMissions = emptyList(),
                currentYear = 1,
                currentMonth = 3
            )
            val size = result.newMissions.size
            assertTrue(size in 0..MissionSystem.MAX_REFRESH_COUNT)
            counts.add(size)
        }
        assertTrue(counts.size > 1)
    }

    @Test
    fun `processMonthlyRefresh - 任务可重复`() {
        var hasDuplicate = false
        repeat(100) {
            val result = MissionSystem.processMonthlyRefresh(
                existingMissions = emptyList(),
                currentYear = 1,
                currentMonth = 3
            )
            val templates = result.newMissions.map { it.template }
            if (templates.size != templates.toSet().size) {
                hasDuplicate = true
            }
        }
    }

    @Test
    fun `processMonthlyRefresh - 刷新月份3-6-9-12均可刷新`() {
        for (month in listOf(3, 6, 9, 12)) {
            val result = MissionSystem.processMonthlyRefresh(
                existingMissions = emptyList(),
                currentYear = 1,
                currentMonth = month
            )
            assertTrue(result.newMissions.size <= MissionSystem.MAX_REFRESH_COUNT)
        }
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
    fun `MAX_REFRESH_COUNT 为5`() {
        assertEquals(5, MissionSystem.MAX_REFRESH_COUNT)
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

    @Test
    fun `ESCORT奖励为固定600灵石`() {
        val mission = MissionSystem.processMonthlyRefresh(
            existingMissions = emptyList(),
            currentYear = 1,
            currentMonth = 3
        ).newMissions.find { it.template == MissionTemplate.ESCORT } ?: return

        assertEquals(600, mission.rewards.spiritStones)
        assertEquals(0, mission.rewards.spiritStonesMax)
        assertEquals(0, mission.rewards.materialCountMin)
        assertEquals(0, mission.rewards.materialCountMax)
    }

    @Test
    fun `SUPPRESS_BEASTS奖励为10到20个妖兽材料无灵石`() {
        val mission = MissionSystem.processMonthlyRefresh(
            existingMissions = emptyList(),
            currentYear = 1,
            currentMonth = 3
        ).newMissions.find { it.template == MissionTemplate.SUPPRESS_BEASTS } ?: return

        assertEquals(0, mission.rewards.spiritStones)
        assertEquals(0, mission.rewards.spiritStonesMax)
        assertEquals(10, mission.rewards.materialCountMin)
        assertEquals(20, mission.rewards.materialCountMax)
        assertEquals(1, mission.rewards.materialMinRarity)
        assertEquals(2, mission.rewards.materialMaxRarity)
    }

    @Test
    fun `processMissionCompletion - ESCORT固定600灵石`() {
        val mission = Mission(
            template = MissionTemplate.ESCORT,
            name = "简单护送商队",
            description = "测试",
            difficulty = MissionDifficulty.SIMPLE,
            duration = 3,
            rewards = com.xianxia.sect.core.model.MissionRewardConfig(
                spiritStones = 600,
                spiritStonesMax = 0
            )
        )
        val activeMission = MissionSystem.createActiveMission(
            mission = mission,
            disciples = (1..6).map { createDisciple(id = "d$it", name = "弟子$it") },
            currentYear = 1,
            currentMonth = 1
        )
        val result = MissionSystem.processMissionCompletion(
            activeMission = activeMission,
            disciples = emptyList()
        )
        assertEquals(600, result.spiritStones)
        assertTrue(result.materials.isEmpty())
    }

    @Test
    fun `processMissionCompletion - SUPPRESS_BEASTS生成10到20个材料`() {
        val mission = Mission(
            template = MissionTemplate.SUPPRESS_BEASTS,
            name = "简单妖兽作乱",
            description = "测试",
            difficulty = MissionDifficulty.SIMPLE,
            duration = 4,
            rewards = com.xianxia.sect.core.model.MissionRewardConfig(
                spiritStones = 0,
                spiritStonesMax = 0,
                materialCountMin = 10,
                materialCountMax = 20,
                materialMinRarity = 1,
                materialMaxRarity = 2
            )
        )
        val activeMission = MissionSystem.createActiveMission(
            mission = mission,
            disciples = (1..6).map { createDisciple(id = "d$it", name = "弟子$it") },
            currentYear = 1,
            currentMonth = 1
        )
        repeat(50) {
            val result = MissionSystem.processMissionCompletion(
                activeMission = activeMission,
                disciples = emptyList()
            )
            assertEquals(0, result.spiritStones)
            assertTrue(result.materials.size in 10..20)
            result.materials.forEach { material ->
                assertTrue(material.rarity in 1..2)
            }
        }
    }

    @Test
    fun `SUPPRESS_BEASTS_NORMAL难度为普通`() {
        assertEquals(MissionDifficulty.NORMAL, MissionTemplate.SUPPRESS_BEASTS_NORMAL.difficulty)
    }

    @Test
    fun `SUPPRESS_BEASTS_NORMAL持续时间为4个月`() {
        assertEquals(4, MissionTemplate.SUPPRESS_BEASTS_NORMAL.duration)
    }

    @Test
    fun `SUPPRESS_BEASTS_NORMAL奖励为10到20个灵品宝品材料无灵石`() {
        val mission = MissionSystem.processMonthlyRefresh(
            existingMissions = emptyList(),
            currentYear = 1,
            currentMonth = 3
        ).newMissions.find { it.template == MissionTemplate.SUPPRESS_BEASTS_NORMAL } ?: return

        assertEquals(0, mission.rewards.spiritStones)
        assertEquals(0, mission.rewards.spiritStonesMax)
        assertEquals(10, mission.rewards.materialCountMin)
        assertEquals(20, mission.rewards.materialCountMax)
        assertEquals(2, mission.rewards.materialMinRarity)
        assertEquals(3, mission.rewards.materialMaxRarity)
    }

    @Test
    fun `SUPPRESS_BEASTS_NORMAL描述包含元婴`() {
        assertTrue(MissionTemplate.SUPPRESS_BEASTS_NORMAL.description.contains("元婴"))
    }

    @Test
    fun `processMissionCompletion - SUPPRESS_BEASTS_NORMAL生成10到20个灵品宝品材料`() {
        val mission = Mission(
            template = MissionTemplate.SUPPRESS_BEASTS_NORMAL,
            name = "普通妖兽作乱",
            description = "测试",
            difficulty = MissionDifficulty.NORMAL,
            duration = 4,
            rewards = com.xianxia.sect.core.model.MissionRewardConfig(
                spiritStones = 0,
                spiritStonesMax = 0,
                materialCountMin = 10,
                materialCountMax = 20,
                materialMinRarity = 2,
                materialMaxRarity = 3
            )
        )
        val activeMission = MissionSystem.createActiveMission(
            mission = mission,
            disciples = (1..6).map { createDisciple(id = "d$it", name = "弟子$it", discipleType = "inner") },
            currentYear = 1,
            currentMonth = 1
        )
        repeat(50) {
            val result = MissionSystem.processMissionCompletion(
                activeMission = activeMission,
                disciples = emptyList()
            )
            assertEquals(0, result.spiritStones)
            assertTrue(result.materials.size in 10..20)
            result.materials.forEach { material ->
                assertTrue(material.rarity in 2..3)
            }
        }
    }
}
