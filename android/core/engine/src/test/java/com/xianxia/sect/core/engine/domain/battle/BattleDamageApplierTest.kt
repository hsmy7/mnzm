package com.xianxia.sect.core.engine.domain.battle

import com.xianxia.sect.core.BuffType
import com.xianxia.sect.core.CombatantSide
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 共享伤害应用层守卫测试（2026-08-04 双引擎收敛）。
 *
 * 覆盖护盾吸收（含护盾余量写回）、伤害分摊、伤害链接——主战斗引擎与宗门战引擎共用。
 */
class BattleDamageApplierTest {

    private fun combatant(
        id: String,
        side: CombatantSide = CombatantSide.DEFENDER,
        hp: Int = 1000,
        maxHp: Int = 1000,
        buffs: List<CombatBuff> = emptyList()
    ) = Combatant(
        id = id, name = id, side = side,
        hp = hp, maxHp = maxHp, mp = 500, maxMp = 500,
        physicalAttack = 100, magicAttack = 100,
        physicalDefense = 50, magicDefense = 50,
        speed = 100, critRate = 0.05,
        skills = emptyList(), buffs = buffs
    )

    @Test
    fun `applyDamageToTarget - 无护盾直接扣血`() {
        val target = combatant("t", hp = 800, maxHp = 1000)
        val updated = BattleDamageApplier.applyDamageToTarget(target, 300)
        assertEquals(500, updated.hp)
        assertEquals("无护盾不改变 buffs", 0, updated.buffs.size)
    }

    @Test
    fun `applyDamageToTarget - 护盾吸收伤害并写回护盾余量`() {
        // 护盾 20% = 200；伤害 500 → 吸收 200 → 扣 300 → hp 500，护盾余量 0
        val shield = CombatBuff(BuffType.SHIELD, value = 0.2, remainingDuration = 3)
        val target = combatant("t", hp = 800, maxHp = 1000, buffs = listOf(shield))
        val updated = BattleDamageApplier.applyDamageToTarget(target, 500)
        assertEquals(500, updated.hp)
        val updatedShield = updated.buffs.first { it.type == BuffType.SHIELD }
        assertEquals("护盾被完全消耗后余量写回 0", 0.0, updatedShield.value, 1e-9)
    }

    @Test
    fun `applyDamageToTarget - 护盾部分吸收保留余量比例`() {
        // 护盾 20% = 200；伤害 100 → 全吸收 → hp 不变 800，护盾余量 100/1000 = 0.1
        val shield = CombatBuff(BuffType.SHIELD, value = 0.2, remainingDuration = 3)
        val target = combatant("t", hp = 800, maxHp = 1000, buffs = listOf(shield))
        val updated = BattleDamageApplier.applyDamageToTarget(target, 100)
        assertEquals(800, updated.hp)
        val updatedShield = updated.buffs.first { it.type == BuffType.SHIELD }
        assertEquals("护盾余量按比例写回", 0.1, updatedShield.value, 1e-9)
    }

    @Test
    fun `applyDamageToTarget - 同剩余时长多护盾只写回被消耗的`() {
        // 对抗性审查：两个护盾同 duration（3 回合）不同 value（0.2 与 0.3）——
        // 0.3 被消耗后只写回 0.3 的余量，0.2 不得被一并覆写
        val shieldSmall = CombatBuff(BuffType.SHIELD, value = 0.2, remainingDuration = 3)
        val shieldBig = CombatBuff(BuffType.SHIELD, value = 0.3, remainingDuration = 3)
        val target = combatant("t", hp = 800, maxHp = 1000, buffs = listOf(shieldSmall, shieldBig))
        // 伤害 500：max 护盾 0.3×1000=300 吸收 → 余量 0，扣 200 → hp 600
        val updated = BattleDamageApplier.applyDamageToTarget(target, 500)
        assertEquals(600, updated.hp)
        val small = updated.buffs.first { it.value == 0.2 }
        val big = updated.buffs.first { it.value < 0.2 }
        assertEquals("未消耗的护盾保持原值", 0.2, small.value, 1e-9)
        assertEquals("被消耗的护盾余量写回 0", 0.0, big.value, 1e-9)
    }

    @Test
    fun `applySharedDamage - 分担者受伤且护盾吸收`() {
        val target = combatant("t", hp = 800, maxHp = 1000)
        val sharer = combatant("s", hp = 900, maxHp = 1000, buffs = listOf(
            CombatBuff(BuffType.DAMAGE_SHARE, value = 0.3, remainingDuration = 2)
        ))
        // 伤害 500 → 分担 30% = 150
        val updated = BattleDamageApplier.applySharedDamage(target, 500, team = listOf(target, sharer), beasts = emptyList())
        assertEquals(750, updated.getValue("s").hp)
    }

    @Test
    fun `applyLinkedDamage - 链接者承受链接伤害`() {
        val attacker = combatant("a", side = CombatantSide.DEFENDER)
        val target = combatant("t", side = CombatantSide.ATTACKER, hp = 800, maxHp = 1000)
        val linked = combatant("l", side = CombatantSide.ATTACKER, hp = 800, maxHp = 1000, buffs = listOf(
            CombatBuff(BuffType.DAMAGE_LINK, value = 0.5, remainingDuration = 2)
        ))
        // 伤害 400 → 链接 50% = 200
        val updated = BattleDamageApplier.applyLinkedDamage(
            attacker, target, 400,
            team = listOf(attacker), beasts = listOf(target, linked)
        )
        assertEquals(600, updated.getValue("l").hp)
    }
}
