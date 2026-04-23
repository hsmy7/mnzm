package com.xianxia.sect.core.engine

import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.DiscipleStatus
import com.xianxia.sect.core.model.Mission
import com.xianxia.sect.core.model.MissionDifficulty
import com.xianxia.sect.core.model.MissionTemplate
import com.xianxia.sect.core.model.MissionType
import com.xianxia.sect.core.model.EnemyType
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
    fun `MissionTemplate - 24个任务模板`() {
        assertEquals(24, MissionTemplate.values().size)
    }

    @Test
    fun `MissionTemplate - 每个难度6个模板`() {
        val simpleCount = MissionTemplate.values().count { it.difficulty == MissionDifficulty.SIMPLE }
        val normalCount = MissionTemplate.values().count { it.difficulty == MissionDifficulty.NORMAL }
        val hardCount = MissionTemplate.values().count { it.difficulty == MissionDifficulty.HARD }
        val forbiddenCount = MissionTemplate.values().count { it.difficulty == MissionDifficulty.FORBIDDEN }
        assertEquals(6, simpleCount)
        assertEquals(6, normalCount)
        assertEquals(6, hardCount)
        assertEquals(6, forbiddenCount)
    }

    @Test
    fun `MissionTemplate - 每个难度2个无战斗2个必战斗2个概率战斗`() {
        MissionDifficulty.values().forEach { difficulty ->
            val templates = MissionTemplate.values().filter { it.difficulty == difficulty }
            val noCombat = templates.count { it.missionType == MissionType.NO_COMBAT }
            val combatRequired = templates.count { it.missionType == MissionType.COMBAT_REQUIRED }
            val combatRandom = templates.count { it.missionType == MissionType.COMBAT_RANDOM }
            assertEquals(2, noCombat)
            assertEquals(2, combatRequired)
            assertEquals(2, combatRandom)
        }
    }

    @Test
    fun `Mission - memberCount 返回模板所需人数`() {
        val mission = Mission(
            template = MissionTemplate.ESCORT_CARAVAN,
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
    fun `MissionDifficulty - 境界限制正确`() {
        assertEquals(9, MissionDifficulty.SIMPLE.minRealm)
        assertEquals(7, MissionDifficulty.NORMAL.minRealm)
        assertEquals(5, MissionDifficulty.HARD.minRealm)
        assertEquals(3, MissionDifficulty.FORBIDDEN.minRealm)
    }

    @Test
    fun `MissionDifficulty - 敌人境界范围正确`() {
        assertEquals(9, MissionDifficulty.SIMPLE.enemyRealmMin)
        assertEquals(8, MissionDifficulty.SIMPLE.enemyRealmMax)
        assertEquals(7, MissionDifficulty.NORMAL.enemyRealmMin)
        assertEquals(6, MissionDifficulty.NORMAL.enemyRealmMax)
        assertEquals(5, MissionDifficulty.HARD.enemyRealmMin)
        assertEquals(4, MissionDifficulty.HARD.enemyRealmMax)
        assertEquals(3, MissionDifficulty.FORBIDDEN.enemyRealmMin)
        assertEquals(2, MissionDifficulty.FORBIDDEN.enemyRealmMax)
    }

    @Test
    fun `ESCORT_CARAVAN奖励为固定600灵石`() {
        val mission = MissionSystem.processMonthlyRefresh(
            existingMissions = emptyList(),
            currentYear = 1,
            currentMonth = 3
        ).newMissions.find { it.template == MissionTemplate.ESCORT_CARAVAN } ?: return

        assertEquals(600, mission.rewards.spiritStones)
        assertEquals(0, mission.rewards.spiritStonesMax)
        assertEquals(0, mission.rewards.materialCountMin)
        assertEquals(0, mission.rewards.materialCountMax)
    }

    @Test
    fun `SUPPRESS_LOW_BEASTS奖励正确`() {
        val mission = MissionSystem.processMonthlyRefresh(
            existingMissions = emptyList(),
            currentYear = 1,
            currentMonth = 3
        ).newMissions.find { it.template == MissionTemplate.SUPPRESS_LOW_BEASTS } ?: return

        assertEquals(400, mission.rewards.spiritStones)
        assertEquals(10, mission.rewards.materialCountMin)
        assertEquals(15, mission.rewards.materialCountMax)
        assertEquals(1, mission.rewards.materialMinRarity)
        assertEquals(1, mission.rewards.materialMaxRarity)
    }

    @Test
    fun `processMissionCompletion - NO_COMBAT任务ESCORT_CARAVAN固定600灵石`() {
        val mission = Mission(
            template = MissionTemplate.ESCORT_CARAVAN,
            name = "简单护送商队",
            description = "测试",
            difficulty = MissionDifficulty.SIMPLE,
            duration = 3,
            rewards = com.xianxia.sect.core.model.MissionRewardConfig(
                spiritStones = 600,
                spiritStonesMax = 0
            ),
            missionType = MissionType.NO_COMBAT
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
        assertTrue(result.victory)
    }

    @Test
    fun `processMissionCompletion - NO_COMBAT任务PATROL_TERRITORY生成材料`() {
        val mission = Mission(
            template = MissionTemplate.PATROL_TERRITORY,
            name = "简单巡查领地",
            description = "测试",
            difficulty = MissionDifficulty.SIMPLE,
            duration = 3,
            rewards = com.xianxia.sect.core.model.MissionRewardConfig(
                spiritStones = 300,
                materialCountMin = 5,
                materialCountMax = 10,
                materialMinRarity = 1,
                materialMaxRarity = 1
            ),
            missionType = MissionType.NO_COMBAT
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
        assertEquals(300, result.spiritStones)
        assertTrue(result.victory)
    }

    @Test
    fun `COMBAT_RANDOM任务有触发率`() {
        assertTrue(MissionTemplate.EXPLORE_ABANDONED_MINE.triggerChance > 0.0)
        assertTrue(MissionTemplate.EXPLORE_ANCIENT_CAVE.triggerChance > 0.0)
        assertTrue(MissionTemplate.EXPLORE_ANCIENT_BATTLEFIELD.triggerChance > 0.0)
        assertTrue(MissionTemplate.EXPLORE_CORE_BATTLEFIELD.triggerChance > 0.0)
    }

    @Test
    fun `NO_COMBAT任务触发率为0`() {
        MissionTemplate.values().filter { it.missionType == MissionType.NO_COMBAT }.forEach {
            assertEquals(0.0, it.triggerChance, 0.001)
        }
    }

    @Test
    fun `COMBAT_REQUIRED任务触发率为0`() {
        MissionTemplate.values().filter { it.missionType == MissionType.COMBAT_REQUIRED }.forEach {
            assertEquals(0.0, it.triggerChance, 0.001)
        }
    }

    @Test
    fun `validateDisciplesForMission - 简单任务外门弟子通过`() {
        val mission = Mission(
            template = MissionTemplate.ESCORT_CARAVAN,
            name = "简单护送商队",
            description = "测试",
            difficulty = MissionDifficulty.SIMPLE,
            duration = 3,
            rewards = com.xianxia.sect.core.model.MissionRewardConfig()
        )
        val disciples = (1..6).map { createDisciple(id = "d$it", name = "弟子$it", discipleType = "outer") }
        val result = MissionSystem.validateDisciplesForMission(mission, disciples)
        assertTrue(result.valid)
    }

    @Test
    fun `validateDisciplesForMission - 困难任务外门弟子不通过`() {
        val mission = Mission(
            template = MissionTemplate.SUPPRESS_HUASHEN_BEAST_KING,
            name = "困难镇压化神妖王",
            description = "测试",
            difficulty = MissionDifficulty.HARD,
            duration = 40,
            rewards = com.xianxia.sect.core.model.MissionRewardConfig()
        )
        val disciples = (1..6).map { createDisciple(id = "d$it", name = "弟子$it", discipleType = "outer", realm = 5) }
        val result = MissionSystem.validateDisciplesForMission(mission, disciples)
        assertFalse(result.valid)
    }

    @Test
    fun `validateDisciplesForMission - 普通任务境界不足不通过`() {
        val mission = Mission(
            template = MissionTemplate.SUPPRESS_JINDAN_BEASTS,
            name = "普通镇压金丹妖兽群",
            description = "测试",
            difficulty = MissionDifficulty.NORMAL,
            duration = 8,
            rewards = com.xianxia.sect.core.model.MissionRewardConfig()
        )
        val disciples = (1..6).map { createDisciple(id = "d$it", name = "弟子$it", discipleType = "inner", realm = 9) }
        val result = MissionSystem.validateDisciplesForMission(mission, disciples)
        assertFalse(result.valid)
    }
}
