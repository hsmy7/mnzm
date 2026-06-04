package com.xianxia.sect.core.config

import com.xianxia.sect.core.GameConfig
import org.junit.Assert.*
import org.junit.Test

class ConfigLoaderTest {

    private val configData = ConfigLoader.load()

    @Test
    fun load_returnsNonNullGameConfigData() {
        assertNotNull(configData)
    }

    @Test
    fun version_is1() {
        assertEquals(1, configData.version)
    }

    @Test
    fun gameConfig_name_is模拟宗门() {
        assertEquals("模拟宗门", configData.gameConfig.name)
    }

    @Test
    fun gameConfig_maxSaveSlots_is5() {
        assertEquals(5, configData.gameConfig.maxSaveSlots)
    }

    @Test
    fun discipleConfig_maxDisciples_is1000() {
        assertEquals(1000, configData.discipleConfig.maxDisciples)
    }

    @Test
    fun discipleConfig_recruitCost_is1000() {
        assertEquals(1000L, configData.discipleConfig.recruitCost)
    }

    @Test
    fun discipleConfig_minAge_is5_maxAge_is100() {
        assertEquals(5, configData.discipleConfig.minAge)
        assertEquals(100, configData.discipleConfig.maxAge)
    }

    @Test
    fun timeConfig_fieldsArePopulated() {
        assertTrue(configData.timeConfig.tickInterval > 0)
        assertTrue(configData.timeConfig.ticksPerSecond > 0)
        assertTrue(configData.timeConfig.daysPerMonth > 0)
        assertTrue(configData.timeConfig.monthsPerYear > 0)
    }

    @Test
    fun realmConfigs_has10Entries() {
        assertEquals(10, configData.realmConfigs.size)
    }

    @Test
    fun rarityConfigs_has6Entries() {
        assertEquals(6, configData.rarityConfigs.size)
    }

    @Test
    fun spiritRootConfigs_elements_has5Items() {
        assertEquals(5, configData.spiritRootConfigs.elements.size)
    }

    @Test
    fun spiritRootConfigs_types_has5Entries() {
        assertEquals(5, configData.spiritRootConfigs.types.size)
    }

    @Test
    fun spiritRootConfigs_countWeights_has5Entries() {
        assertEquals(5, configData.spiritRootConfigs.countWeights.size)
    }

    @Test
    fun beastTypeConfigs_isNotEmpty() {
        assertFalse(configData.beastTypeConfigs.isEmpty())
    }

    @Test
    fun battleConfig_fieldsArePopulated() {
        assertTrue(configData.battleConfig.maxTeamSize > 0)
        assertTrue(configData.battleConfig.minBeastCount > 0)
        assertTrue(configData.battleConfig.maxBeastCount > 0)
        assertTrue(configData.battleConfig.maxTurns > 0)
        assertTrue(configData.battleConfig.critMultiplier > 0.0)
    }

    @Test
    fun aiConfig_fieldsArePopulated() {
        assertTrue(configData.aiConfig.minDisciplesForAttack > 0)
        assertTrue(configData.aiConfig.powerRatioThreshold > 0.0)
        assertTrue(configData.aiConfig.teamSize > 0)
    }

    @Test
    fun worldMapConfig_fieldsArePopulated() {
        assertTrue(configData.worldMapConfig.mapWidth > 0)
        assertTrue(configData.worldMapConfig.mapHeight > 0)
        assertTrue(configData.worldMapConfig.targetSectCount > 0)
    }

    @Test
    fun diplomacyConfig_fieldsArePopulated() {
        assertTrue(configData.diplomacyConfig.minAllianceFavor > 0)
        assertTrue(configData.diplomacyConfig.allianceDurationYears > 0)
        assertTrue(configData.diplomacyConfig.diplomaticEventChance > 0.0)
    }
}
