package com.xianxia.sect.core.model

import org.junit.Assert.*
import org.junit.Test

class MissionTest {

    // ==================== MissionDifficulty 枚举 ====================

    @Test
    fun missionDifficulty_displayName() {
        assertEquals("简单", MissionDifficulty.SIMPLE.displayName)
        assertEquals("普通", MissionDifficulty.NORMAL.displayName)
        assertEquals("困难", MissionDifficulty.HARD.displayName)
        assertEquals("禁忌", MissionDifficulty.FORBIDDEN.displayName)
    }

    @Test
    fun missionDifficulty_spawnChance() {
        assertEquals(0.25, MissionDifficulty.SIMPLE.spawnChance, 0.001)
        assertEquals(0.12, MissionDifficulty.NORMAL.spawnChance, 0.001)
        assertEquals(0.03, MissionDifficulty.HARD.spawnChance, 0.001)
        assertEquals(0.005, MissionDifficulty.FORBIDDEN.spawnChance, 0.001)
    }

    @Test
    fun missionDifficulty_durationMonths() {
        assertEquals(3, MissionDifficulty.SIMPLE.durationMonths)
        assertEquals(7, MissionDifficulty.NORMAL.durationMonths)
        assertEquals(36, MissionDifficulty.HARD.durationMonths)
        assertEquals(58, MissionDifficulty.FORBIDDEN.durationMonths)
    }

    @Test
    fun missionDifficulty_allowedPositions() {
        assertEquals(listOf("外门弟子"), MissionDifficulty.SIMPLE.allowedPositions)
        assertEquals(listOf("外门弟子", "内门弟子"), MissionDifficulty.NORMAL.allowedPositions)
        assertEquals(listOf("内门弟子"), MissionDifficulty.HARD.allowedPositions)
        assertEquals(listOf("内门弟子"), MissionDifficulty.FORBIDDEN.allowedPositions)
    }

    @Test
    fun missionDifficulty_conditionText() {
        assertEquals("外门弟子", MissionDifficulty.SIMPLE.conditionText)
        assertEquals("无条件", MissionDifficulty.NORMAL.conditionText)
        assertEquals("内门弟子", MissionDifficulty.HARD.conditionText)
        assertEquals("内门弟子", MissionDifficulty.FORBIDDEN.conditionText)
    }

    @Test
    fun missionDifficulty_minRealm() {
        assertEquals(9, MissionDifficulty.SIMPLE.minRealm)
        assertEquals(7, MissionDifficulty.NORMAL.minRealm)
        assertEquals(5, MissionDifficulty.HARD.minRealm)
        assertEquals(3, MissionDifficulty.FORBIDDEN.minRealm)
    }

    @Test
    fun missionDifficulty_allowedDiscipleTypes() {
        assertEquals(listOf("outer"), MissionDifficulty.SIMPLE.allowedDiscipleTypes)
        assertEquals(listOf("outer", "inner"), MissionDifficulty.NORMAL.allowedDiscipleTypes)
        assertEquals(listOf("inner"), MissionDifficulty.HARD.allowedDiscipleTypes)
        assertEquals(listOf("inner"), MissionDifficulty.FORBIDDEN.allowedDiscipleTypes)
    }

    @Test
    fun missionDifficulty_enemyRealmMin() {
        assertEquals(8, MissionDifficulty.SIMPLE.enemyRealmMin)
        assertEquals(6, MissionDifficulty.NORMAL.enemyRealmMin)
        assertEquals(4, MissionDifficulty.HARD.enemyRealmMin)
        assertEquals(2, MissionDifficulty.FORBIDDEN.enemyRealmMin)
    }

    @Test
    fun missionDifficulty_enemyRealmMax() {
        assertEquals(9, MissionDifficulty.SIMPLE.enemyRealmMax)
        assertEquals(7, MissionDifficulty.NORMAL.enemyRealmMax)
        assertEquals(5, MissionDifficulty.HARD.enemyRealmMax)
        assertEquals(3, MissionDifficulty.FORBIDDEN.enemyRealmMax)
    }

    @Test
    fun missionDifficulty_values() {
        assertEquals(4, MissionDifficulty.values().size)
    }

    // ==================== MissionType 枚举 ====================

    @Test
    fun missionType_values() {
        assertEquals(3, MissionType.values().size)
    }

    @Test
    fun missionType_entries() {
        assertTrue(MissionType.values().contains(MissionType.NO_COMBAT))
        assertTrue(MissionType.values().contains(MissionType.COMBAT_REQUIRED))
        assertTrue(MissionType.values().contains(MissionType.COMBAT_RANDOM))
    }

    // ==================== EnemyType 枚举 ====================

    @Test
    fun enemyType_values() {
        assertEquals(2, EnemyType.values().size)
    }

    // ==================== MissionTemplate 枚举 ====================

    @Test
    fun missionTemplate_values_count() {
        assertEquals(24, MissionTemplate.values().size)
    }

    @Test
    fun missionTemplate_difficulty_simple() {
        val simpleTemplates = listOf(
            MissionTemplate.ESCORT_CARAVAN,
            MissionTemplate.PATROL_TERRITORY,
            MissionTemplate.DELIVER_SUPPLIES,
            MissionTemplate.SUPPRESS_LOW_BEASTS,
            MissionTemplate.CLEAR_BANDITS,
            MissionTemplate.EXPLORE_ABANDONED_MINE
        )
        simpleTemplates.forEach {
            assertEquals("$it should be SIMPLE", MissionDifficulty.SIMPLE, it.difficulty)
        }
    }

    @Test
    fun missionTemplate_difficulty_normal() {
        val normalTemplates = listOf(
            MissionTemplate.ESCORT_SPIRIT_CARAVAN,
            MissionTemplate.INVESTIGATE_ANOMALY,
            MissionTemplate.DELIVER_PILLS,
            MissionTemplate.SUPPRESS_JINDAN_BEASTS,
            MissionTemplate.DESTROY_MAGIC_OUTPOST,
            MissionTemplate.EXPLORE_ANCIENT_CAVE
        )
        normalTemplates.forEach {
            assertEquals("$it should be NORMAL", MissionDifficulty.NORMAL, it.difficulty)
        }
    }

    @Test
    fun missionTemplate_difficulty_hard() {
        val hardTemplates = listOf(
            MissionTemplate.ESCORT_IMMORTAL_ENVOY,
            MissionTemplate.REPAIR_ANCIENT_FORMATION,
            MissionTemplate.SEARCH_MISSING_ELDER,
            MissionTemplate.SUPPRESS_HUASHEN_BEAST_KING,
            MissionTemplate.DESTROY_MAGIC_BRANCH,
            MissionTemplate.EXPLORE_ANCIENT_BATTLEFIELD
        )
        hardTemplates.forEach {
            assertEquals("$it should be HARD", MissionDifficulty.HARD, it.difficulty)
        }
    }

    @Test
    fun missionTemplate_difficulty_forbidden() {
        val forbiddenTemplates = listOf(
            MissionTemplate.ESCORT_RELIC_ARTIFACT,
            MissionTemplate.SEAL_SPATIAL_RIFT,
            MissionTemplate.SEARCH_SECRET_REALM_CLUE,
            MissionTemplate.SUPPRESS_ANCIENT_FIEND,
            MissionTemplate.DESTROY_MAGIC_HEADQUARTERS,
            MissionTemplate.EXPLORE_CORE_BATTLEFIELD
        )
        forbiddenTemplates.forEach {
            assertEquals("$it should be FORBIDDEN", MissionDifficulty.FORBIDDEN, it.difficulty)
        }
    }

    @Test
    fun missionTemplate_missionType_noCombat() {
        val noCombatTemplates = listOf(
            MissionTemplate.ESCORT_CARAVAN, MissionTemplate.PATROL_TERRITORY,
            MissionTemplate.DELIVER_SUPPLIES, MissionTemplate.ESCORT_SPIRIT_CARAVAN,
            MissionTemplate.INVESTIGATE_ANOMALY, MissionTemplate.DELIVER_PILLS,
            MissionTemplate.ESCORT_IMMORTAL_ENVOY, MissionTemplate.REPAIR_ANCIENT_FORMATION,
            MissionTemplate.SEARCH_MISSING_ELDER, MissionTemplate.ESCORT_RELIC_ARTIFACT,
            MissionTemplate.SEAL_SPATIAL_RIFT, MissionTemplate.SEARCH_SECRET_REALM_CLUE
        )
        noCombatTemplates.forEach {
            assertEquals("$it should be NO_COMBAT", MissionType.NO_COMBAT, it.missionType)
        }
    }

    @Test
    fun missionTemplate_missionType_combatRequired() {
        val combatRequiredTemplates = listOf(
            MissionTemplate.SUPPRESS_LOW_BEASTS, MissionTemplate.CLEAR_BANDITS,
            MissionTemplate.SUPPRESS_JINDAN_BEASTS, MissionTemplate.DESTROY_MAGIC_OUTPOST,
            MissionTemplate.SUPPRESS_HUASHEN_BEAST_KING, MissionTemplate.DESTROY_MAGIC_BRANCH,
            MissionTemplate.SUPPRESS_ANCIENT_FIEND, MissionTemplate.DESTROY_MAGIC_HEADQUARTERS
        )
        combatRequiredTemplates.forEach {
            assertEquals("$it should be COMBAT_REQUIRED", MissionType.COMBAT_REQUIRED, it.missionType)
        }
    }

    @Test
    fun missionTemplate_missionType_combatRandom() {
        val combatRandomTemplates = listOf(
            MissionTemplate.EXPLORE_ABANDONED_MINE, MissionTemplate.EXPLORE_ANCIENT_CAVE,
            MissionTemplate.EXPLORE_ANCIENT_BATTLEFIELD, MissionTemplate.EXPLORE_CORE_BATTLEFIELD
        )
        combatRandomTemplates.forEach {
            assertEquals("$it should be COMBAT_RANDOM", MissionType.COMBAT_RANDOM, it.missionType)
        }
    }

    @Test
    fun missionTemplate_triggerChance_exploreTemplates() {
        assertEquals(0.40, MissionTemplate.EXPLORE_ABANDONED_MINE.triggerChance, 0.001)
        assertEquals(0.50, MissionTemplate.EXPLORE_ANCIENT_CAVE.triggerChance, 0.001)
        assertEquals(0.60, MissionTemplate.EXPLORE_ANCIENT_BATTLEFIELD.triggerChance, 0.001)
        assertEquals(0.70, MissionTemplate.EXPLORE_CORE_BATTLEFIELD.triggerChance, 0.001)
    }

    @Test
    fun missionTemplate_triggerChance_nonExploreTemplates() {
        assertEquals(0.0, MissionTemplate.ESCORT_CARAVAN.triggerChance, 0.001)
        assertEquals(0.0, MissionTemplate.SUPPRESS_LOW_BEASTS.triggerChance, 0.001)
        assertEquals(0.0, MissionTemplate.SUPPRESS_ANCIENT_FIEND.triggerChance, 0.001)
    }

    @Test
    fun missionTemplate_requiredMemberCount_always6() {
        MissionTemplate.values().forEach {
            assertEquals(6, it.requiredMemberCount)
        }
    }

    @Test
    fun missionTemplate_duration_simpleTemplates() {
        assertEquals(3, MissionTemplate.ESCORT_CARAVAN.duration)
        assertEquals(3, MissionTemplate.PATROL_TERRITORY.duration)
        assertEquals(3, MissionTemplate.DELIVER_SUPPLIES.duration)
        assertEquals(4, MissionTemplate.SUPPRESS_LOW_BEASTS.duration)
        assertEquals(4, MissionTemplate.CLEAR_BANDITS.duration)
        assertEquals(4, MissionTemplate.EXPLORE_ABANDONED_MINE.duration)
    }

    @Test
    fun missionTemplate_duration_normalTemplates() {
        assertEquals(7, MissionTemplate.ESCORT_SPIRIT_CARAVAN.duration)
        assertEquals(7, MissionTemplate.INVESTIGATE_ANOMALY.duration)
        assertEquals(7, MissionTemplate.DELIVER_PILLS.duration)
        assertEquals(8, MissionTemplate.SUPPRESS_JINDAN_BEASTS.duration)
        assertEquals(8, MissionTemplate.DESTROY_MAGIC_OUTPOST.duration)
        assertEquals(8, MissionTemplate.EXPLORE_ANCIENT_CAVE.duration)
    }

    @Test
    fun missionTemplate_duration_hardTemplates() {
        assertEquals(36, MissionTemplate.ESCORT_IMMORTAL_ENVOY.duration)
        assertEquals(36, MissionTemplate.REPAIR_ANCIENT_FORMATION.duration)
        assertEquals(36, MissionTemplate.SEARCH_MISSING_ELDER.duration)
        assertEquals(40, MissionTemplate.SUPPRESS_HUASHEN_BEAST_KING.duration)
        assertEquals(40, MissionTemplate.DESTROY_MAGIC_BRANCH.duration)
        assertEquals(40, MissionTemplate.EXPLORE_ANCIENT_BATTLEFIELD.duration)
    }

    @Test
    fun missionTemplate_duration_forbiddenTemplates() {
        assertEquals(58, MissionTemplate.ESCORT_RELIC_ARTIFACT.duration)
        assertEquals(58, MissionTemplate.SEAL_SPATIAL_RIFT.duration)
        assertEquals(58, MissionTemplate.SEARCH_SECRET_REALM_CLUE.duration)
        assertEquals(64, MissionTemplate.SUPPRESS_ANCIENT_FIEND.duration)
        assertEquals(64, MissionTemplate.DESTROY_MAGIC_HEADQUARTERS.duration)
        assertEquals(64, MissionTemplate.EXPLORE_CORE_BATTLEFIELD.duration)
    }

    @Test
    fun missionTemplate_enemyType_beast() {
        val beastTemplates = listOf(
            MissionTemplate.SUPPRESS_LOW_BEASTS, MissionTemplate.SUPPRESS_JINDAN_BEASTS,
            MissionTemplate.SUPPRESS_HUASHEN_BEAST_KING, MissionTemplate.SUPPRESS_ANCIENT_FIEND,
            MissionTemplate.EXPLORE_ABANDONED_MINE
        )
        beastTemplates.forEach {
            assertEquals("$it should be BEAST", EnemyType.BEAST, it.enemyType)
        }
    }

    @Test
    fun missionTemplate_enemyType_human() {
        val humanTemplates = listOf(
            MissionTemplate.CLEAR_BANDITS, MissionTemplate.DESTROY_MAGIC_OUTPOST,
            MissionTemplate.DESTROY_MAGIC_BRANCH, MissionTemplate.DESTROY_MAGIC_HEADQUARTERS,
            MissionTemplate.EXPLORE_ANCIENT_CAVE, MissionTemplate.EXPLORE_ANCIENT_BATTLEFIELD
        )
        humanTemplates.forEach {
            assertEquals("$it should be HUMAN", EnemyType.HUMAN, it.enemyType)
        }
    }

    @Test
    fun missionTemplate_displayName_notEmpty() {
        MissionTemplate.values().forEach {
            assertTrue("$it displayName should not be empty", it.displayName.isNotEmpty())
        }
    }

    @Test
    fun missionTemplate_description_notEmpty() {
        MissionTemplate.values().forEach {
            assertTrue("$it description should not be empty", it.description.isNotEmpty())
        }
    }

    @Test
    fun missionTemplate_beastCountRange() {
        MissionTemplate.values().forEach {
            assertEquals(4, it.beastCountRange.first)
            assertEquals(10, it.beastCountRange.last)
        }
    }

    @Test
    fun missionTemplate_humanCountRange() {
        MissionTemplate.values().forEach {
            assertEquals(4, it.humanCountRange.first)
            assertEquals(8, it.humanCountRange.last)
        }
    }

    // ==================== MissionRewardConfig ====================

    @Test
    fun missionRewardConfig_defaultConstruction() {
        val config = MissionRewardConfig()
        assertEquals(0, config.spiritStones)
        assertEquals(0, config.spiritStonesMax)
        assertEquals(0, config.materialCountMin)
        assertEquals(0, config.materialCountMax)
        assertEquals(1, config.materialMinRarity)
        assertEquals(2, config.materialMaxRarity)
        assertEquals(0, config.pillCountMin)
        assertEquals(0, config.pillCountMax)
        assertEquals(1, config.pillMinRarity)
        assertEquals(1, config.pillMaxRarity)
        assertEquals(0.0, config.equipmentChance, 0.001)
        assertEquals(1, config.equipmentMinRarity)
        assertEquals(1, config.equipmentMaxRarity)
        assertEquals(0.0, config.manualChance, 0.001)
        assertEquals(1, config.manualMinRarity)
        assertEquals(1, config.manualMaxRarity)
        assertEquals(0, config.baseSpiritStones)
        assertEquals(0, config.baseMaterialCountMin)
        assertEquals(0, config.baseMaterialCountMax)
        assertEquals(1, config.baseMaterialMinRarity)
        assertEquals(1, config.baseMaterialMaxRarity)
    }

    @Test
    fun missionRewardConfig_copy() {
        val config = MissionRewardConfig(spiritStones = 100, spiritStonesMax = 200)
        val copied = config.copy(spiritStones = 300)
        assertEquals(100, config.spiritStones)
        assertEquals(300, copied.spiritStones)
        assertEquals(200, copied.spiritStonesMax)
    }

    // ==================== Mission ====================

    @Test
    fun mission_defaultConstruction() {
        val mission = Mission(
            template = MissionTemplate.ESCORT_CARAVAN,
            name = "护送商队",
            description = "描述",
            difficulty = MissionDifficulty.SIMPLE,
            duration = 3,
            rewards = MissionRewardConfig()
        )
        assertNotNull(mission.id)
        assertEquals(MissionTemplate.ESCORT_CARAVAN, mission.template)
        assertEquals("护送商队", mission.name)
        assertEquals(MissionDifficulty.SIMPLE, mission.difficulty)
        assertEquals(3, mission.duration)
        assertEquals(MissionType.NO_COMBAT, mission.missionType)
        assertEquals(EnemyType.BEAST, mission.enemyType)
        assertEquals(0.0, mission.triggerChance, 0.001)
        assertEquals(1, mission.createdYear)
        assertEquals(1, mission.createdMonth)
    }

    @Test
    fun mission_memberCount_delegatesToTemplate() {
        val mission = Mission(
            template = MissionTemplate.SUPPRESS_LOW_BEASTS,
            name = "镇压低阶妖兽",
            description = "",
            difficulty = MissionDifficulty.SIMPLE,
            duration = 4,
            rewards = MissionRewardConfig()
        )
        assertEquals(6, mission.memberCount)
    }

    @Test
    fun mission_copy() {
        val mission = Mission(
            template = MissionTemplate.ESCORT_CARAVAN,
            name = "护送商队",
            description = "描述",
            difficulty = MissionDifficulty.SIMPLE,
            duration = 3,
            rewards = MissionRewardConfig()
        )
        val copied = mission.copy(duration = 5)
        assertEquals(3, mission.duration)
        assertEquals(5, copied.duration)
        assertEquals(mission.template, copied.template)
    }

    // ==================== ActiveMission ====================

    @Test
    fun activeMission_memberCount() {
        val active = ActiveMission(
            missionId = "m1",
            missionName = "任务",
            template = MissionTemplate.ESCORT_CARAVAN,
            difficulty = MissionDifficulty.SIMPLE,
            discipleIds = listOf("d1", "d2", "d3"),
            discipleNames = listOf("弟子1", "弟子2", "弟子3"),
            discipleRealms = listOf("炼气", "炼气", "炼气"),
            startYear = 1,
            startMonth = 1,
            duration = 3,
            rewards = MissionRewardConfig()
        )
        assertEquals(3, active.memberCount)
    }

    @Test
    fun activeMission_memberCount_empty() {
        val active = ActiveMission(
            missionId = "m1",
            missionName = "任务",
            template = MissionTemplate.ESCORT_CARAVAN,
            difficulty = MissionDifficulty.SIMPLE,
            discipleIds = emptyList(),
            discipleNames = emptyList(),
            discipleRealms = emptyList(),
            startYear = 1,
            startMonth = 1,
            duration = 3,
            rewards = MissionRewardConfig()
        )
        assertEquals(0, active.memberCount)
    }

    @Test
    fun activeMission_getProgressPercent_zeroDuration_returns100() {
        val active = ActiveMission(
            missionId = "m1", missionName = "任务",
            template = MissionTemplate.ESCORT_CARAVAN, difficulty = MissionDifficulty.SIMPLE,
            discipleIds = listOf("d1"), discipleNames = listOf("弟子1"), discipleRealms = listOf("炼气"),
            startYear = 1, startMonth = 1, duration = 0, rewards = MissionRewardConfig()
        )
        assertEquals(100, active.getProgressPercent(1, 1))
    }

    @Test
    fun activeMission_isComplete_whenComplete() {
        val active = ActiveMission(
            missionId = "m1", missionName = "任务",
            template = MissionTemplate.ESCORT_CARAVAN, difficulty = MissionDifficulty.SIMPLE,
            discipleIds = listOf("d1"), discipleNames = listOf("弟子1"), discipleRealms = listOf("炼气"),
            startYear = 1, startMonth = 1, duration = 3, rewards = MissionRewardConfig()
        )
        assertTrue(active.isComplete(1, 4))
    }

    @Test
    fun activeMission_isNotComplete_whenInProgress() {
        val active = ActiveMission(
            missionId = "m1", missionName = "任务",
            template = MissionTemplate.ESCORT_CARAVAN, difficulty = MissionDifficulty.SIMPLE,
            discipleIds = listOf("d1"), discipleNames = listOf("弟子1"), discipleRealms = listOf("炼气"),
            startYear = 1, startMonth = 1, duration = 3, rewards = MissionRewardConfig()
        )
        assertFalse(active.isComplete(1, 2))
    }

    @Test
    fun activeMission_defaultMissionType() {
        val active = ActiveMission(
            missionId = "m1", missionName = "任务",
            template = MissionTemplate.ESCORT_CARAVAN, difficulty = MissionDifficulty.SIMPLE,
            discipleIds = emptyList(), discipleNames = emptyList(), discipleRealms = emptyList(),
            startYear = 1, startMonth = 1, duration = 3, rewards = MissionRewardConfig()
        )
        assertEquals(MissionType.NO_COMBAT, active.missionType)
        assertEquals(EnemyType.BEAST, active.enemyType)
        assertEquals(0.0, active.triggerChance, 0.001)
    }
}
