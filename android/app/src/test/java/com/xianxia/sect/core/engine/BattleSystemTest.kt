package com.xianxia.sect.core.engine

import com.xianxia.sect.core.BuffType
import com.xianxia.sect.core.CombatantSide
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.SkillStats
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class BattleSystemTest {

    private lateinit var battleSystem: BattleSystem

    @Before
    fun setUp() {
        battleSystem = BattleSystem()
    }

    private fun createDisciple(
        id: String = "d1",
        name: String = "TestDisciple",
        realm: Int = 9,
        realmLayer: Int = 1,
        isAlive: Boolean = true
    ): Disciple {
        return Disciple(
            id = id,
            name = name,
            realm = realm,
            realmLayer = realmLayer,
            isAlive = isAlive,
            skills = SkillStats(loyalty = 50)
        )
    }

    @Test
    fun `createBattle - creates battle with disciple team`() {
        val disciples = listOf(createDisciple(id = "d1"), createDisciple(id = "d2"))
        val battle = battleSystem.createBattle(
            disciples = disciples,
            equipmentMap = emptyMap(),
            manualMap = emptyMap(),
            beastLevel = 9,
            beastCount = 2
        )
        assertEquals(2, battle.team.size)
        assertEquals(CombatantSide.DEFENDER, battle.team[0].side)
    }

    @Test
    fun `createBattle - creates battle with beasts`() {
        val battle = battleSystem.createBattle(
            disciples = listOf(createDisciple()),
            equipmentMap = emptyMap(),
            manualMap = emptyMap(),
            beastLevel = 9,
            beastCount = 3
        )
        assertEquals(3, battle.beasts.size)
        battle.beasts.forEach { beast ->
            assertEquals(CombatantSide.ATTACKER, beast.side)
            assertTrue(beast.hp > 0)
        }
    }

    @Test
    fun `createBattle - battle initial state not finished`() {
        val battle = battleSystem.createBattle(
            disciples = listOf(createDisciple()),
            equipmentMap = emptyMap(),
            manualMap = emptyMap(),
            beastLevel = 9,
            beastCount = 1
        )
        assertFalse(battle.isFinished)
        assertNull(battle.winner)
        assertEquals(0, battle.turn)
    }

    @Test
    fun `executeBattle - battle always finishes`() {
        val battle = battleSystem.createBattle(
            disciples = listOf(createDisciple(realm = 5)),
            equipmentMap = emptyMap(),
            manualMap = emptyMap(),
            beastLevel = 5,
            beastCount = 1
        )
        val result = battleSystem.executeBattle(battle)
        assertTrue(result.battle.isFinished)
        assertNotNull(result.battle.winner)
    }

    @Test
    fun `executeBattle - returns battle log`() {
        val battle = battleSystem.createBattle(
            disciples = listOf(createDisciple(realm = 7)),
            equipmentMap = emptyMap(),
            manualMap = emptyMap(),
            beastLevel = 7,
            beastCount = 1
        )
        val result = battleSystem.executeBattle(battle)
        assertNotNull(result.log)
        assertTrue(result.turnCount >= 0)
    }

    @Test
    fun `Combatant - isDead when hp is zero`() {
        val combatant = Combatant(
            id = "test", name = "Test", side = CombatantSide.DEFENDER,
            hp = 0, maxHp = 100, mp = 50, maxMp = 50,
            physicalAttack = 10, magicAttack = 10, physicalDefense = 5, magicDefense = 5,
            speed = 10, critRate = 0.05, skills = emptyList()
        )
        assertTrue(combatant.isDead)
    }

    @Test
    fun `Combatant - isDead when hp is negative`() {
        val combatant = Combatant(
            id = "test", name = "Test", side = CombatantSide.ATTACKER,
            hp = -10, maxHp = 100, mp = 50, maxMp = 50,
            physicalAttack = 10, magicAttack = 10, physicalDefense = 5, magicDefense = 5,
            speed = 10, critRate = 0.05, skills = emptyList()
        )
        assertTrue(combatant.isDead)
    }

    @Test
    fun `Combatant - hpPercent correct`() {
        val combatant = Combatant(
            id = "test", name = "Test", side = CombatantSide.DEFENDER,
            hp = 30, maxHp = 100, mp = 50, maxMp = 50,
            physicalAttack = 10, magicAttack = 10, physicalDefense = 5, magicDefense = 5,
            speed = 10, critRate = 0.05, skills = emptyList()
        )
        assertEquals(0.3, combatant.hpPercent, 0.01)
    }

    @Test
    fun `Combatant - effectivePhysicalAttack with buff`() {
        val buff = CombatBuff(BuffType.PHYSICAL_ATTACK_BOOST, 0.5, 3)
        val combatant = Combatant(
            id = "test", name = "Test", side = CombatantSide.DEFENDER,
            hp = 100, maxHp = 100, mp = 50, maxMp = 50,
            physicalAttack = 100, magicAttack = 50, physicalDefense = 10, magicDefense = 10,
            speed = 20, critRate = 0.05, skills = emptyList(), buffs = listOf(buff)
        )
        assertEquals(150, combatant.effectivePhysicalAttack)
    }

    @Test
    fun `Combatant - effectiveSpeed with buff`() {
        val buff = CombatBuff(BuffType.SPEED_BOOST, 0.3, 2)
        val combatant = Combatant(
            id = "test", name = "Test", side = CombatantSide.DEFENDER,
            hp = 100, maxHp = 100, mp = 50, maxMp = 50,
            physicalAttack = 10, magicAttack = 10, physicalDefense = 10, magicDefense = 10,
            speed = 100, critRate = 0.05, skills = emptyList(), buffs = listOf(buff)
        )
        assertEquals(130, combatant.effectiveSpeed)
    }

    @Test
    fun `Combatant - debuff reduces attack`() {
        val debuff = CombatBuff(BuffType.PHYSICAL_ATTACK_REDUCE, 0.3, 2)
        val combatant = Combatant(
            id = "test", name = "Test", side = CombatantSide.DEFENDER,
            hp = 100, maxHp = 100, mp = 50, maxMp = 50,
            physicalAttack = 100, magicAttack = 50, physicalDefense = 10, magicDefense = 10,
            speed = 20, critRate = 0.05, skills = emptyList(), buffs = listOf(debuff)
        )
        assertEquals(70, combatant.effectivePhysicalAttack)
    }

    @Test
    fun `BattleWinner - enum values complete`() {
        assertEquals(3, BattleWinner.values().size)
        assertNotNull(BattleWinner.TEAM)
        assertNotNull(BattleWinner.BEASTS)
        assertNotNull(BattleWinner.DRAW)
    }

    @Test
    fun `BuffType - all buff types exist`() {
        assertTrue(BuffType.values().size >= 20)
        assertNotNull(BuffType.HP_BOOST)
        assertNotNull(BuffType.POISON)
        assertNotNull(BuffType.BURN)
        assertNotNull(BuffType.STUN)
        assertNotNull(BuffType.FREEZE)
        assertNotNull(BuffType.SILENCE)
        assertNotNull(BuffType.PHYSICAL_ATTACK_REDUCE)
        assertNotNull(BuffType.MAGIC_ATTACK_REDUCE)
    }

    @Test
    fun `calculateRealmGapMultiplier - same realm returns 1`() {
        assertEquals(1.0, battleSystem.calculateRealmGapMultiplier(5, 5), 0.001)
    }

    @Test
    fun `calculateRealmGapMultiplier - 高境界攻击低境界获得加成`() {
        val multiplier = battleSystem.calculateRealmGapMultiplier(3, 5)
        assertTrue(multiplier > 1.0)
    }

    @Test
    fun `calculateRealmGapMultiplier - 低境界攻击高境界受到惩罚`() {
        val multiplier = battleSystem.calculateRealmGapMultiplier(7, 5)
        assertTrue(multiplier < 1.0)
    }

    @Test
    fun `calculateElementMultiplier - advantage returns higher`() {
        val multiplier = battleSystem.calculateElementMultiplier("metal", "wood")
        assertTrue(multiplier > 1.0)
    }

    @Test
    fun `calculateElementMultiplier - disadvantage returns lower`() {
        val multiplier = battleSystem.calculateElementMultiplier("wood", "metal")
        assertTrue(multiplier < 1.0)
    }

    @Test
    fun `calculateElementMultiplier - neutral returns 1`() {
        val multiplier = battleSystem.calculateElementMultiplier("metal", "water")
        assertEquals(1.0, multiplier, 0.001)
    }

    @Test
    fun `Combatant - hasControlEffect with stun`() {
        val stunBuff = CombatBuff(BuffType.STUN, 1.0, 2)
        val combatant = Combatant(
            id = "test", name = "Test", side = CombatantSide.DEFENDER,
            hp = 100, maxHp = 100, mp = 50, maxMp = 50,
            physicalAttack = 10, magicAttack = 10, physicalDefense = 5, magicDefense = 5,
            speed = 10, critRate = 0.05, skills = emptyList(), buffs = listOf(stunBuff)
        )
        assertTrue(combatant.hasControlEffect)
    }

    @Test
    fun `Combatant - no control effect without stun or freeze`() {
        val buff = CombatBuff(BuffType.PHYSICAL_ATTACK_BOOST, 0.5, 3)
        val combatant = Combatant(
            id = "test", name = "Test", side = CombatantSide.DEFENDER,
            hp = 100, maxHp = 100, mp = 50, maxMp = 50,
            physicalAttack = 10, magicAttack = 10, physicalDefense = 5, magicDefense = 5,
            speed = 10, critRate = 0.05, skills = emptyList(), buffs = listOf(buff)
        )
        assertFalse(combatant.hasControlEffect)
    }

    @Test
    fun `executeBattle - multi disciple multi beast battle`() {
        val disciples = listOf(
            createDisciple(id = "d1", realm = 7),
            createDisciple(id = "d2", realm = 7),
            createDisciple(id = "d3", realm = 7)
        )
        val battle = battleSystem.createBattle(
            disciples = disciples,
            equipmentMap = emptyMap(),
            manualMap = emptyMap(),
            beastLevel = 7,
            beastCount = 3
        )
        val result = battleSystem.executeBattle(battle)
        assertTrue(result.battle.isFinished)
        assertTrue(result.log.teamMembers.size == 3)
        assertTrue(result.log.enemies.size == 3)
    }
}
