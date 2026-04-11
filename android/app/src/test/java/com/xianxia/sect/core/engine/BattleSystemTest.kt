package com.xianxia.sect.core.engine

import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.Equipment
import com.xianxia.sect.core.model.Manual
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
    fun `createBattle - 创建战斗包含弟子队伍`() {
        val disciples = listOf(createDisciple(id = "d1"), createDisciple(id = "d2"))
        val battle = battleSystem.createBattle(
            disciples = disciples,
            equipmentMap = emptyMap(),
            manualMap = emptyMap(),
            beastLevel = 9,
            beastCount = 2
        )
        assertEquals(2, battle.team.size)
        assertEquals("d1", battle.team[0].id)
        assertEquals("d2", battle.team[1].id)
        assertEquals(CombatantType.DISCIPLE, battle.team[0].type)
    }

    @Test
    fun `createBattle - 创建战斗包含妖兽`() {
        val disciples = listOf(createDisciple())
        val battle = battleSystem.createBattle(
            disciples = disciples,
            equipmentMap = emptyMap(),
            manualMap = emptyMap(),
            beastLevel = 9,
            beastCount = 3
        )
        assertEquals(3, battle.beasts.size)
        battle.beasts.forEach { beast ->
            assertEquals(CombatantType.BEAST, beast.type)
            assertTrue(beast.hp > 0)
            assertTrue(beast.maxHp > 0)
        }
    }

    @Test
    fun `createBattle - 战斗初始状态未结束`() {
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
    fun `createBattle - 弟子属性基于境界计算`() {
        val weakDisciple = createDisciple(realm = 9, realmLayer = 1)
        val strongDisciple = createDisciple(realm = 5, realmLayer = 5)
        val battle = battleSystem.createBattle(
            disciples = listOf(weakDisciple, strongDisciple),
            equipmentMap = emptyMap(),
            manualMap = emptyMap(),
            beastLevel = 9,
            beastCount = 1
        )
        val weakCombatant = battle.team[0]
        val strongCombatant = battle.team[1]
        assertTrue(strongCombatant.maxHp > weakCombatant.maxHp)
    }

    @Test
    fun `createBattle - 妖兽数量可指定`() {
        val battle = battleSystem.createBattle(
            disciples = listOf(createDisciple()),
            equipmentMap = emptyMap(),
            manualMap = emptyMap(),
            beastLevel = 9,
            beastCount = 5
        )
        assertEquals(5, battle.beasts.size)
    }

    @Test
    fun `createBattle - 妖兽属性基于弟子境界`() {
        val lowDisciples = listOf(createDisciple(realm = 9))
        val highDisciples = listOf(createDisciple(realm = 5))
        val lowBattle = battleSystem.createBattle(
            disciples = lowDisciples,
            equipmentMap = emptyMap(),
            manualMap = emptyMap(),
            beastLevel = 9,
            beastCount = 1
        )
        val highBattle = battleSystem.createBattle(
            disciples = highDisciples,
            equipmentMap = emptyMap(),
            manualMap = emptyMap(),
            beastLevel = 5,
            beastCount = 1
        )
        assertTrue(highBattle.beasts[0].maxHp > lowBattle.beasts[0].maxHp)
    }

    @Test
    fun `executeBattle - 战斗必定结束`() {
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
    fun `executeBattle - 裸装弟子对战妖兽有胜负结果`() {
        val disciples = listOf(
            createDisciple(id = "d1", realm = 5),
            createDisciple(id = "d2", realm = 5),
            createDisciple(id = "d3", realm = 5)
        )
        val battle = battleSystem.createBattle(
            disciples = disciples,
            equipmentMap = emptyMap(),
            manualMap = emptyMap(),
            beastLevel = 9,
            beastCount = 1
        )
        val result = battleSystem.executeBattle(battle)
        assertNotNull(result)
        assertTrue(result.battle.isFinished)
    }

    @Test
    fun `executeBattle - 返回战斗日志`() {
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
        assertTrue(result.durationMs >= 0)
    }

    @Test
    fun `executeBattle - 胜利时获得奖励`() {
        val disciples = listOf(
            createDisciple(id = "d1", realm = 2),
            createDisciple(id = "d2", realm = 2),
            createDisciple(id = "d3", realm = 2)
        )
        val battle = battleSystem.createBattle(
            disciples = disciples,
            equipmentMap = emptyMap(),
            manualMap = emptyMap(),
            beastLevel = 9,
            beastCount = 3
        )
        val result = battleSystem.executeBattle(battle)
        if (result.victory) {
            assertTrue(result.rewards.containsKey("spiritStones"))
            assertTrue(result.rewards["spiritStones"]!! > 0)
        }
    }

    @Test
    fun `Combatant - isDead 当hp为0`() {
        val combatant = Combatant(
            id = "test",
            name = "Test",
            type = CombatantType.DISCIPLE,
            hp = 0,
            maxHp = 100,
            mp = 50,
            maxMp = 50,
            physicalAttack = 10,
            magicAttack = 10,
            physicalDefense = 5,
            magicDefense = 5,
            speed = 10,
            critRate = 0.05,
            skills = emptyList()
        )
        assertTrue(combatant.isDead)
    }

    @Test
    fun `Combatant - isDead 当hp为负`() {
        val combatant = Combatant(
            id = "test",
            name = "Test",
            type = CombatantType.BEAST,
            hp = -10,
            maxHp = 100,
            mp = 50,
            maxMp = 50,
            physicalAttack = 10,
            magicAttack = 10,
            physicalDefense = 5,
            magicDefense = 5,
            speed = 10,
            critRate = 0.05,
            skills = emptyList()
        )
        assertTrue(combatant.isDead)
    }

    @Test
    fun `Combatant - isAlive 当hp大于0`() {
        val combatant = Combatant(
            id = "test",
            name = "Test",
            type = CombatantType.DISCIPLE,
            hp = 50,
            maxHp = 100,
            mp = 50,
            maxMp = 50,
            physicalAttack = 10,
            magicAttack = 10,
            physicalDefense = 5,
            magicDefense = 5,
            speed = 10,
            critRate = 0.05,
            skills = emptyList()
        )
        assertFalse(combatant.isDead)
    }

    @Test
    fun `Combatant - hpPercent 正确计算`() {
        val combatant = Combatant(
            id = "test",
            name = "Test",
            type = CombatantType.DISCIPLE,
            hp = 30,
            maxHp = 100,
            mp = 50,
            maxMp = 50,
            physicalAttack = 10,
            magicAttack = 10,
            physicalDefense = 5,
            magicDefense = 5,
            speed = 10,
            critRate = 0.05,
            skills = emptyList()
        )
        assertEquals(0.3, combatant.hpPercent, 0.01)
    }

    @Test
    fun `Combatant - effectivePhysicalAttack 含buff加成`() {
        val buff = CombatBuff(BuffType.PHYSICAL_ATTACK_BOOST, 0.5, 3)
        val combatant = Combatant(
            id = "test",
            name = "Test",
            type = CombatantType.DISCIPLE,
            hp = 100,
            maxHp = 100,
            mp = 50,
            maxMp = 50,
            physicalAttack = 100,
            magicAttack = 50,
            physicalDefense = 10,
            magicDefense = 10,
            speed = 20,
            critRate = 0.05,
            skills = emptyList(),
            buffs = listOf(buff)
        )
        assertEquals(150, combatant.effectivePhysicalAttack)
    }

    @Test
    fun `Combatant - effectiveSpeed 含buff加成`() {
        val buff = CombatBuff(BuffType.SPEED_BOOST, 0.3, 2)
        val combatant = Combatant(
            id = "test",
            name = "Test",
            type = CombatantType.DISCIPLE,
            hp = 100,
            maxHp = 100,
            mp = 50,
            maxMp = 50,
            physicalAttack = 10,
            magicAttack = 10,
            physicalDefense = 10,
            magicDefense = 10,
            speed = 100,
            critRate = 0.05,
            skills = emptyList(),
            buffs = listOf(buff)
        )
        assertEquals(130, combatant.effectiveSpeed)
    }

    @Test
    fun `BattleWinner - 枚举值完整`() {
        assertEquals(3, BattleWinner.values().size)
        assertNotNull(BattleWinner.TEAM)
        assertNotNull(BattleWinner.BEASTS)
        assertNotNull(BattleWinner.DRAW)
    }

    @Test
    fun `CombatantType - 枚举值完整`() {
        assertEquals(2, CombatantType.values().size)
        assertNotNull(CombatantType.DISCIPLE)
        assertNotNull(CombatantType.BEAST)
    }

    @Test
    fun `BuffType - 所有buff类型存在`() {
        assertTrue(BuffType.values().size >= 8)
        assertNotNull(BuffType.HP_BOOST)
        assertNotNull(BuffType.MP_BOOST)
        assertNotNull(BuffType.SPEED_BOOST)
        assertNotNull(BuffType.PHYSICAL_ATTACK_BOOST)
        assertNotNull(BuffType.MAGIC_ATTACK_BOOST)
        assertNotNull(BuffType.PHYSICAL_DEFENSE_BOOST)
        assertNotNull(BuffType.MAGIC_DEFENSE_BOOST)
        assertNotNull(BuffType.CRIT_RATE_BOOST)
    }

    @Test
    fun `CombatBuff - 剩余持续时间递减`() {
        val buff = CombatBuff(BuffType.HP_BOOST, 0.5, 3)
        assertEquals(3, buff.remainingDuration)
        val reduced = buff.copy(remainingDuration = buff.remainingDuration - 1)
        assertEquals(2, reduced.remainingDuration)
    }

    @Test
    fun `executeBattle - 多弟子多妖兽战斗`() {
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
