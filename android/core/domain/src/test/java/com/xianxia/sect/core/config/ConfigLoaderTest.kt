package com.xianxia.sect.core.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * ConfigLoader 测试。
 *
 * 使用 Robolectric 提供的 Application Context 访问 assets/config/game_config.json。
 * 注意：Robolectric 默认环境下 app/assets 不自动合并到测试 classpath，
 * load() 应优雅降级到 GameConfigData 默认值（来自 GameConfig const val 基线）。
 */
@RunWith(RobolectricTestRunner::class)
class ConfigLoaderTest {

    private fun newLoader(): ConfigLoader = ConfigLoader(RuntimeEnvironment.getApplication())

    @Test
    fun load_returnsNonNullConfigData() {
        val config = newLoader().load()
        assertNotNull(config)
    }

    @Test
    fun load_returnsDefaultValuesWhenAssetsMissing() {
        val config = newLoader().load()
        assertEquals("模拟宗门", config.game.name)
        assertEquals("4.0.00", config.version)
    }

    @Test
    fun gameSection_defaultsAreCorrect() {
        val config = newLoader().load()
        assertEquals("模拟宗门", config.game.name)
        assertEquals(5, config.game.maxSaveSlots)
        assertEquals(60L, config.game.autoSaveIntervalSeconds)
        assertEquals(30000L, config.game.autoSaveDebounceMs)
    }

    @Test
    fun discipleSection_defaultsAreCorrect() {
        val config = newLoader().load()
        assertEquals(1000, config.disciple.maxDisciples)
        assertEquals(1000L, config.disciple.recruitCost)
        assertEquals(5, config.disciple.minAge)
        assertEquals(100, config.disciple.maxAge)
        assertEquals(12, config.disciple.protectionMonths)
    }

    @Test
    fun timeSection_defaultsAreCorrect() {
        val config = newLoader().load()
        assertTrue(config.time.tickInterval > 0)
        assertTrue(config.time.ticksPerSecond > 0)
        assertTrue(config.time.monthsPerYear > 0)
        assertEquals(3, config.time.phasesPerMonth)
    }

    @Test
    fun battleSection_defaultsAreCorrect() {
        val config = newLoader().load()
        assertTrue(config.battle.maxTeamSize > 0)
        assertTrue(config.battle.minBeastCount > 0)
        assertTrue(config.battle.maxBeastCount > 0)
        assertTrue(config.battle.critMultiplier > 0.0)
        assertEquals(0.5, config.battle.realmGap.damageBonusPerRealm, 0.001)
        assertEquals(3, config.battle.realmGap.instantKillGap)
    }

    @Test
    fun aiSection_defaultsAreCorrect() {
        val config = newLoader().load()
        assertTrue(config.ai.minDisciplesForAttack > 0)
        assertTrue(config.ai.powerRatioThreshold > 0.0)
        assertTrue(config.ai.teamSize > 0)
        assertEquals(100.0, config.ai.powerWeights.realmBase, 0.001)
    }

    @Test
    fun worldMapSection_defaultsAreCorrect() {
        val config = newLoader().load()
        assertTrue(config.worldMap.mapWidth > 0)
        assertTrue(config.worldMap.mapHeight > 0)
        assertTrue(config.worldMap.targetSectCount > 0)
    }

    @Test
    fun diplomacySection_defaultsAreCorrect() {
        val config = newLoader().load()
        assertTrue(config.diplomacy.minAllianceFavor > 0)
        assertTrue(config.diplomacy.allianceDurationYears > 0)
        assertTrue(config.diplomacy.diplomaticEventChance > 0.0)
        assertEquals(0.1, config.diplomacy.breakPenalty.spiritStonePenaltyRatio, 0.001)
    }

    @Test
    fun policyConfigSection_defaultsAreCorrect() {
        val config = newLoader().load()
        assertEquals(3000L, config.policyConfig.enhancedSecurityCost)
        assertEquals(0.2, config.policyConfig.spiritMineBoostBaseEffect, 0.001)
        assertEquals("灵矿增产", config.policyConfig.spiritMineBoostName)
    }

    @Test
    fun lawEnforcementSection_defaultsAreCorrect() {
        val config = newLoader().load()
        assertEquals(30, config.lawEnforcement.loyaltyThreshold)
        assertEquals(0.03, config.lawEnforcement.probPerPoint, 0.001)
        assertEquals(12, config.lawEnforcement.newDiscipleProtectionMonths)
    }

    @Test
    fun load_cachesResult() {
        val loader = newLoader()
        val first = loader.load()
        val second = loader.load()
        assertTrue(first === second) // 同一实例（缓存生效）
    }

    @Test
    fun invalidateCache_forcesReload() {
        val loader = newLoader()
        val first = loader.load()
        loader.invalidateCache()
        val second = loader.load()
        // 内容应相等（缓存清除后重新加载）
        assertEquals(first.version, second.version)
    }
}
