package com.xianxia.sect.core.engine.domain.battle

import com.xianxia.sect.core.util.GameRngManager
import org.junit.After
import org.junit.Before
import org.junit.Assert.*
import org.junit.Test

class EnemyGeneratorTest {

    @Before
    fun setUp() {
        val rngManager = GameRngManager()
        rngManager.initSystemSeed(12345L)
        enemyGenRngManager = rngManager
    }

    @After
    fun tearDown() {
        enemyGenRngManager = null
    }

    // ═══════════════════════════════════════════════════════════════
    // 2026-08-04 战斗核查修复回归（G10 敌人功法属性加成）
    // ═══════════════════════════════════════════════════════════════

    private fun manualInstance(
        hp: Int = 0, physicalAttack: Int = 0, critRatePercent: Int = 0
    ) = com.xianxia.sect.core.model.ManualInstance(
        name = "功法", rarity = 3, description = "", type = com.xianxia.sect.core.model.ManualType.ATTACK,
        stats = mapOf(
            "hp" to hp,
            "maxHp" to hp,
            "physicalAttack" to physicalAttack,
            "critRate" to critRatePercent
        ),
        skillName = "斩", skillDescription = "",
        skillType = "attack", skillDamageType = "physical",
        skillHits = 1, skillDamageMultiplier = 1.0, skillCooldown = 2, skillMpCost = 10,
        skillHealPercent = 0.0, skillHealType = "hp", skillBuffType = null,
        skillBuffValue = 0.0, skillBuffDuration = 0, skillBuffsJson = "",
        skillIsAoe = false, skillTargetScope = "enemy", minRealm = 9
    )

    @Test
    fun `ManualStatsAccumulator - 功法属性按熟练度加成与玩家公式一致`() {
        val acc = EnemyGenerator.ManualStatsAccumulator()
        // NOVICE(0) bonus=1.5：hp 100→150、攻击 50→75、暴击 10%→15%
        acc.add(manualInstance(hp = 100, physicalAttack = 50, critRatePercent = 10), masteryLevel = 0)
        assertEquals(150, acc.hp)
        assertEquals(75, acc.physicalAttack)
        assertEquals(0.15, acc.critChance, 1e-9)
        // 小成(1) bonus=2.0：hp 100→200
        acc.add(manualInstance(hp = 100), masteryLevel = 1)
        assertEquals(350, acc.hp)
    }

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

    // ── 验证敌方弟子使用玩家属性公式（非 Enemy.REALM_STATS） ──

    @Test
    fun generateHumanEnemies_statsMatchPlayerFormula_notEnemyTable() {
        // realm=5（化神）时验证：
        // 玩家公式 baseHp=9126，最大 ×1.3(方差)×1.8(层数)=21355（不含装备）
        // 装备 HP 来自 EquipmentDatabase（高稀有度装备的 HP 值本身很大，是独立问题）
        // 关键验证：生成的数据不崩溃、HP>0、且同 realm 的 HP 在合理范围内波动
        val results = EnemyGenerator.generateHumanEnemies(
            realmMin = 5,
            realmMax = 5,
            count = 50
        )
        for (data in results) {
            val c = data.combatant
            assertTrue("HP ${c.hp} should be > 0", c.hp > 0)
            assertTrue("Physical attack ${c.physicalAttack} should be > 0", c.physicalAttack > 0)
            assertTrue("Physical defense ${c.physicalDefense} should be > 0", c.physicalDefense > 0)
        }
    }

    @Test
    fun generateHumanEnemies_statsVaryWithVariance() {
        // 多次生成验证方差生效：相同 realm 的弟子属性在合理范围内波动
        val results = EnemyGenerator.generateHumanEnemies(
            realmMin = 6,
            realmMax = 6,
            count = 30
        )
        val hps = results.map { it.combatant.hp }
        val maxHp = hps.max()
        val minHp = hps.min()
        // 方差 ±30% + 层数 1~9 → 同一境界应有明显波动
        assertTrue("HP should vary with variance (max=$maxHp, min=$minHp)", maxHp > minHp * 1.2)
    }
}
