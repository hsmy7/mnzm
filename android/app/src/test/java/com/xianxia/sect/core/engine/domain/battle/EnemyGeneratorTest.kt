package com.xianxia.sect.core.engine.domain.battle

import org.junit.Assert.*
import org.junit.Test

class EnemyGeneratorTest {

    // ---- HumanEnemyData ----

    @Test
    fun humanEnemyData_construction() {
        val combatant = Combatant(
            id = "human_enemy_1",
            name = "魔修1",
            side = com.xianxia.sect.core.CombatantSide.ATTACKER,
            hp = 1000,
            maxHp = 1000,
            mp = 500,
            maxMp = 500,
            physicalAttack = 100,
            magicAttack = 80,
            physicalDefense = 60,
            magicDefense = 40,
            speed = 50,
            critRate = 0.1,
            skills = emptyList(),
            realm = 5,
            realmName = "化神",
            realmLayer = 3,
            element = "fire"
        )
        val data = EnemyGenerator.HumanEnemyData(
            combatant = combatant,
            equipmentInstances = emptyList(),
            manualInstances = emptyList()
        )
        assertSame(combatant, data.combatant)
        assertEquals(emptyList<Any>(), data.equipmentInstances)
        assertEquals(emptyList<Any>(), data.manualInstances)
    }

    // ---- generateHumanEnemies ----

    @Test
    fun generateHumanEnemies_returnsCorrectCount() {
        val results = EnemyGenerator.generateHumanEnemies(
            realmMin = 5,
            realmMax = 7,
            count = 3
        )
        assertEquals(3, results.size)
    }

    @Test
    fun generateHumanEnemies_returnsEmptyListForZeroCount() {
        val results = EnemyGenerator.generateHumanEnemies(
            realmMin = 5,
            realmMax = 7,
            count = 0
        )
        assertEquals(0, results.size)
    }

    @Test
    fun generateHumanEnemies_eachResultHasCombatant() {
        val results = EnemyGenerator.generateHumanEnemies(
            realmMin = 5,
            realmMax = 7,
            count = 5
        )
        for (data in results) {
            assertNotNull(data.combatant)
            assertTrue(data.combatant.hp > 0)
            assertTrue(data.combatant.maxHp > 0)
        }
    }

    @Test
    fun generateHumanEnemies_combatantIdFollowsPattern() {
        val results = EnemyGenerator.generateHumanEnemies(
            realmMin = 5,
            realmMax = 7,
            count = 3
        )
        for ((index, data) in results.withIndex()) {
            assertEquals("human_enemy_${index + 1}", data.combatant.id)
        }
    }

    @Test
    fun generateHumanEnemies_combatantIsAttackerSide() {
        val results = EnemyGenerator.generateHumanEnemies(
            realmMin = 5,
            realmMax = 7,
            count = 3
        )
        for (data in results) {
            assertEquals(com.xianxia.sect.core.CombatantSide.ATTACKER, data.combatant.side)
        }
    }

    @Test
    fun generateHumanEnemies_realmWithinRange() {
        val results = EnemyGenerator.generateHumanEnemies(
            realmMin = 5,
            realmMax = 7,
            count = 20
        )
        for (data in results) {
            assertTrue(
                "Realm ${data.combatant.realm} should be in [5, 7]",
                data.combatant.realm in 5..7
            )
        }
    }

    @Test
    fun generateHumanEnemies_realmLayerInRange() {
        val results = EnemyGenerator.generateHumanEnemies(
            realmMin = 5,
            realmMax = 7,
            count = 20
        )
        for (data in results) {
            assertTrue(
                "RealmLayer ${data.combatant.realmLayer} should be in [1, 9]",
                data.combatant.realmLayer in 1..9
            )
        }
    }

    @Test
    fun generateHumanEnemies_elementIsValid() {
        val validElements = setOf("metal", "wood", "water", "fire", "earth")
        val results = EnemyGenerator.generateHumanEnemies(
            realmMin = 5,
            realmMax = 7,
            count = 20
        )
        for (data in results) {
            assertTrue(
                "Element ${data.combatant.element} should be valid",
                data.combatant.element in validElements
            )
        }
    }

    @Test
    fun generateHumanEnemies_combatantHasSkills() {
        val results = EnemyGenerator.generateHumanEnemies(
            realmMin = 5,
            realmMax = 7,
            count = 10
        )
        for (data in results) {
            assertTrue("Combatant should have at least one skill", data.combatant.skills.isNotEmpty())
        }
    }

    @Test
    fun generateHumanEnemies_critRateIsNonNegative() {
        val results = EnemyGenerator.generateHumanEnemies(
            realmMin = 5,
            realmMax = 7,
            count = 10
        )
        for (data in results) {
            assertTrue("Crit rate should be non-negative", data.combatant.critRate >= 0.0)
        }
    }

    @Test
    fun generateHumanEnemies_singleEnemy() {
        val results = EnemyGenerator.generateHumanEnemies(
            realmMin = 3,
            realmMax = 3,
            count = 1
        )
        assertEquals(1, results.size)
        assertEquals(3, results[0].combatant.realm)
    }
}
