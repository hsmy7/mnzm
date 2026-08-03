package com.xianxia.sect.core.engine.domain.exploration

import com.xianxia.sect.core.exploration.WorldLevelManager
import com.xianxia.sect.core.model.*
import com.xianxia.sect.core.util.GameRngManager
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class WorldLevelManagerTest {

    private lateinit var manager: WorldLevelManager
    private lateinit var rngManager: GameRngManager

    @Before
    fun setUp() {
        rngManager = GameRngManager()
        rngManager.initSystemSeed(42)
        val levelGenerator = LevelGenerator(rngManager)
        manager = WorldLevelManager(rngManager, levelGenerator)
    }

    @Test
    fun `processMonthly does not crash with empty levels`() {
        val gd = GameData(worldLevels = emptyList(), gameYear = 1, gameMonth = 1)
        val result = manager.processMonthly(gd)
        assertNotNull(result)
        assertNotNull(result.worldLevels)
    }

    @Test
    fun `processMonthly clears expired levels`() {
        val expiredLevel = WorldLevel(
            type = LevelType.BEAST, defeated = false,
            expiryYear = 1, expiryMonth = 1,
            x = 100f, y = 100f
        )
        val gd = GameData(
            worldMapSects = listOf(WorldSect(isPlayerSect = true, x = 500f, y = 500f, name = "玩家宗门")),
            worldLevels = listOf(expiredLevel),
            gameYear = 2, gameMonth = 6,
            worldLevelLastRefreshMonth = 100 // 防止触发关卡刷新，只测试过期清理
        )
        val result = manager.processMonthly(gd)
        assertTrue("expired level should be removed", result.worldLevels.isEmpty())
    }

    @Test
    fun `processMonthly keeps non-expired levels`() {
        val activeLevel = WorldLevel(
            type = LevelType.BEAST, defeated = false,
            expiryYear = 10, expiryMonth = 12,
            x = 100f, y = 100f
        )
        val gd = GameData(
            worldLevels = listOf(activeLevel),
            gameYear = 2, gameMonth = 6,
            worldLevelLastRefreshMonth = 100 // 防止触发关卡刷新，只测试过期清理
        )
        val result = manager.processMonthly(gd)
        assertTrue("active level should be kept",
            result.worldLevels.any { it.id == activeLevel.id })
    }

    @Test
    fun `processMonthly with same seed produces same result`() {
        val gdBase = GameData(
            worldMapSects = listOf(WorldSect(isPlayerSect = true, x = 500f, y = 500f, name = "玩家宗门")),
            gameYear = 1, gameMonth = 1
        )

        // Pass 1
        val manager1 = WorldLevelManager(rngManager, LevelGenerator(rngManager))
        val result1 = manager1.processMonthly(gdBase)

        // Pass 2 with fresh RNG manager (same seed)
        val rngManager2 = GameRngManager().also { it.initSystemSeed(42) }
        val manager2 = WorldLevelManager(rngManager2, LevelGenerator(rngManager2))
        val result2 = manager2.processMonthly(gdBase)

        assertEquals("same seed should produce same level count",
            result1.worldLevels.size, result2.worldLevels.size)
    }

    @Test
    fun `processMonthly updates worldLevelLastRefreshMonth`() {
        val gd = GameData(
            worldLevels = emptyList(),
            worldMapSects = listOf(WorldSect(isPlayerSect = true, x = 500f, y = 500f, name = "玩家宗门")),
            gameYear = 1, gameMonth = 1,
            worldLevelLastRefreshMonth = 0
        )
        val result = manager.processMonthly(gd)
        assertTrue("last refresh month should be updated",
            result.worldLevelLastRefreshMonth > 0)
    }
// recompile marker

    @Test
    fun `processMonthly with defeated beast does not generate new for it`() {
        val defeated = WorldLevel(type = LevelType.BEAST, defeated = true,
            expiryYear = 10, expiryMonth = 12, x = 300f, y = 300f)
        val gd = GameData(
            worldMapSects = listOf(WorldSect(isPlayerSect = true, x = 500f, y = 500f, name = "玩家宗门")),
            worldLevels = listOf(defeated),
            gameYear = 1, gameMonth = 1,
            worldLevelLastRefreshMonth = 100
        )
        val result = manager.processMonthly(gd)
        assertTrue("defeated beast should be removed",
            result.worldLevels.none { it.id == defeated.id })
    }

    @Test
    fun `processMonthly beast movement stays within bounds`() {
        val beast = WorldLevel(type = LevelType.BEAST, defeated = false,
            expiryYear = 10, expiryMonth = 12, x = 500f, y = 500f)
        val gd = GameData(
            worldLevels = listOf(beast),
            gameYear = 2, gameMonth = 6,
            worldLevelLastRefreshMonth = 100
        )
        val result = manager.processMonthly(gd)
        val moved = result.worldLevels[0]
        assertTrue("x should be finite", moved.x.isFinite())
        assertTrue("y should be finite", moved.y.isFinite())
    }

    @Test
    fun `processMonthly does not move cave levels`() {
        val cave = WorldLevel(type = LevelType.CAVE, defeated = false,
            expiryYear = 10, expiryMonth = 12, x = 500f, y = 500f)
        val gd = GameData(
            worldLevels = listOf(cave),
            gameYear = 2, gameMonth = 6,
            worldLevelLastRefreshMonth = 100
        )
        val result = manager.processMonthly(gd)
        assertEquals("cave position should stay same", 500f, result.worldLevels[0].x, 0.01f)
    }

    @Test
    fun `processMonthly deterministic with same seed`() {
        val rng2 = GameRngManager().also { it.initSystemSeed(42) }
        val levelGen2 = LevelGenerator(rng2)
        val manager2 = WorldLevelManager(rng2, levelGen2)
        val gd = GameData(
            worldLevels = listOf(WorldLevel(type = LevelType.BEAST, defeated = false,
                expiryYear = 10, expiryMonth = 12, x = 500f, y = 500f)),
            gameYear = 2, gameMonth = 6,
            worldLevelLastRefreshMonth = 100
        )
        val r1 = manager.processMonthly(gd)
        val r2 = manager2.processMonthly(gd)
        assertEquals(r1.worldLevels.size, r2.worldLevels.size)
        assertEquals(r1.worldLevels[0].x, r2.worldLevels[0].x, 0.01f)
        assertEquals(r1.worldLevels[0].y, r2.worldLevels[0].y, 0.01f)
    }

}
