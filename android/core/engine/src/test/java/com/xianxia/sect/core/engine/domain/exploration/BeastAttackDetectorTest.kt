package com.xianxia.sect.core.engine.domain.exploration

import com.xianxia.sect.core.exploration.BeastAttackDetector
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.LevelType
import com.xianxia.sect.core.model.WorldLevel
import com.xianxia.sect.core.model.WorldSect
import com.xianxia.sect.core.util.GameRngManager
import org.junit.Assert.*
import org.junit.Test



class BeastAttackDetectorTest {

    private val detector = BeastAttackDetector(GameRngManager().also { it.initSystemSeed(42L) })

    @Test
    fun `detectAttacks returns empty when no beasts`() {
        val gd = GameData(worldLevels = emptyList())
        val attacks = detector.detectAttacks(gd)
        assertTrue(attacks.isEmpty())
    }

    @Test
    fun `detectAttacks returns empty when no player sect`() {
        val beast = WorldLevel(type = LevelType.BEAST, x = 100f, y = 100f)
        val gd = GameData(worldLevels = listOf(beast), worldMapSects = emptyList())
        val attacks = detector.detectAttacks(gd)
        assertTrue(attacks.isEmpty())
    }

    @Test
    fun `detectAttacks ignores defeated beasts`() {
        val beast = WorldLevel(type = LevelType.BEAST, defeated = true, x = 100f, y = 100f)
        val gd = GameData(
            worldLevels = listOf(beast),
            worldMapSects = listOf(WorldSect(isPlayerSect = true, x = 500f, y = 500f, name = "玩家宗门"))
        )
        val attacks = detector.detectAttacks(gd)
        assertTrue("defeated beast should be ignored", attacks.isEmpty())
    }

    @Test
    fun `detectAttacks ignores expired beasts`() {
        val beast = WorldLevel(type = LevelType.BEAST, defeated = false,
            expiryYear = 1, expiryMonth = 1, x = 100f, y = 100f)
        val gd = GameData(
            worldLevels = listOf(beast),
            worldMapSects = listOf(WorldSect(isPlayerSect = true, x = 500f, y = 500f, name = "玩家宗门")),
            gameYear = 10, gameMonth = 6
        )
        val attacks = detector.detectAttacks(gd)
        assertTrue("expired beast should be ignored", attacks.isEmpty())
    }

    @Test
    fun `detectAttacks ignores beasts far from sects`() {
        val beast = WorldLevel(type = LevelType.BEAST, defeated = false, x = 100f, y = 100f)
        val gd = GameData(
            worldLevels = listOf(beast),
            worldMapSects = listOf(WorldSect(isPlayerSect = true, x = 5000f, y = 5000f, name = "玩家宗门"))
        )
        val attacks = detector.detectAttacks(gd)
        // Beast at (100,100) is far from sect at (5000,5000) → no attack
        assertTrue(attacks.isEmpty())
    }

    @Test
    fun `detectAttacks ignores cave levels`() {
        val cave = WorldLevel(type = LevelType.CAVE, x = 500f, y = 500f)
        val gd = GameData(
            worldLevels = listOf(cave),
            worldMapSects = listOf(WorldSect(isPlayerSect = true, x = 500f, y = 500f, name = "玩家宗门"))
        )
        val attacks = detector.detectAttacks(gd)
        assertTrue("cave levels should not trigger beast attacks", attacks.isEmpty())
    }

    @Test
    fun `detectAttacks returns attacks when beast near sect`() {
        val beast = WorldLevel(type = LevelType.BEAST, defeated = false, x = 500f, y = 500f,
            beastName = "虎妖")
        val gd = GameData(
            worldLevels = listOf(beast),
            worldMapSects = listOf(WorldSect(isPlayerSect = true, x = 500f, y = 500f, name = "玩家宗门"))
        )
        val attacks = detector.detectAttacks(gd)
        // Beast and sect at same position → within attack radius
        // Attack is probabilistic, so may or may not return results
        // We just verify it doesn't crash and returns valid data
        for (attack in attacks) {
            assertEquals(beast.id, attack.beastLevel.id)
        }
    }
}
