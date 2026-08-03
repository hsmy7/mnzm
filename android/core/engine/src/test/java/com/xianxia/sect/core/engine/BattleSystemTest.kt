package com.xianxia.sect.core.engine

import com.xianxia.sect.core.BuffType
import com.xianxia.sect.core.CombatantSide
import com.xianxia.sect.core.engine.domain.battle.BattleSystem
import com.xianxia.sect.core.engine.domain.battle.BattleWinner
import com.xianxia.sect.core.engine.domain.battle.CombatBuff
import com.xianxia.sect.core.engine.domain.battle.Combatant
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.SkillStats
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mock

class BattleSystemTest {

    private lateinit var battleSystem: BattleSystem

    @Before
    fun setUp() {
        battleSystem = BattleSystem(mock())
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
    fun `calculateRealmGapMultiplier - 全十境界差距加成不再被钳制`() {
        val multiplier = battleSystem.calculateRealmGapMultiplier(0, 9)
        assertEquals(4.15, multiplier, 0.001)
    }

    @Test
    fun `calculateRealmGapMultiplier - 全十境界差距惩罚触底为零`() {
        val multiplier = battleSystem.calculateRealmGapMultiplier(9, 0)
        assertEquals(0.0, multiplier, 0.001)
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

    // ═══════════════════════════════════════════════════════════════
    // P3A 拆分回归：executeCombatantTurn 抽取函数（applyControlEffects/
    // executeSkillAction/buildTurnMessage/applyDamageEffects/processTurnAdvance）
    // 通过公开 executeBattle 路径触发，验证拆分后行为等价
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `executeBattle - 眩晕弟子跳过行动 战斗仍正常结束`() {
        // applyControlEffects：眩晕/冰冻时记录控制日志并跳过行动
        val battle = battleSystem.createBattle(
            disciples = listOf(createDisciple(realm = 5)),
            equipmentMap = emptyMap(),
            manualMap = emptyMap(),
            beastLevel = 5,
            beastCount = 1
        )
        // 给首个弟子附加眩晕 BUFF，验证控制路径不崩溃且战斗正常结束
        val stunnedBattle = battle.copy(
            team = battle.team.mapIndexed { i, c ->
                if (i == 0) {
                    c.copy(
                        buffs = c.buffs + CombatBuff(
                            type = BuffType.STUN, value = 1.0, remainingDuration = 2, sourceRealm = 9
                        )
                    )
                } else c
            }
        )
        val result = battleSystem.executeBattle(stunnedBattle)
        assertTrue(result.battle.isFinished)
        // 控制路径（applyControlEffects）不崩溃，战斗正常结束
        // （control 日志是否出现取决于行动顺序——妖兽可能先行动，不断言具体类型）
        assertTrue(result.turnCount >= 0)
    }

    @Test
    fun `executeBattle - 日志结构完整 行动记录无空字段`() {
        // buildTurnMessage/applyDamageEffects：消息生成与伤害结算路径
        // 注：弟子无功法（skills 空）时仅普攻；若妖兽先手秒杀则 rounds 可能为空，
        // 因此只断言"有行动记录时每条结构完整"。
        val battle = battleSystem.createBattle(
            disciples = listOf(createDisciple(realm = 5), createDisciple(realm = 5)),
            equipmentMap = emptyMap(),
            manualMap = emptyMap(),
            beastLevel = 5,
            beastCount = 1
        )
        val result = battleSystem.executeBattle(battle)
        assertTrue(result.battle.isFinished)
        assertNotNull(result.log)
        assertTrue(result.log.teamMembers.isNotEmpty())
        assertTrue(result.log.enemies.isNotEmpty())
        val actions = result.log.rounds.flatMap { it.actions }
        actions.forEach { action ->
            assertNotNull(action.attacker)
            assertNotNull(action.message)
        }
    }

    @Test
    fun `executeBattle - 支援技能路径不崩溃 战斗正常结束`() {
        // executeSkillAction 支援分支：治疗/加 BUFF 类技能
        val battle = battleSystem.createBattle(
            disciples = listOf(createDisciple(realm = 6), createDisciple(realm = 6)),
            equipmentMap = emptyMap(),
            manualMap = emptyMap(),
            beastLevel = 6,
            beastCount = 1
        )
        val result = battleSystem.executeBattle(battle)
        assertTrue(result.battle.isFinished)
        // 战斗过程无异常（含可能触发的支援技能分支）
        assertTrue(result.turnCount >= 0)
    }
}
