package com.xianxia.sect.core.engine.domain.disciple

import com.xianxia.sect.core.model.EquipAffixSet
import com.xianxia.sect.core.model.EquipGrowth
import com.xianxia.sect.core.model.EquipStat
import com.xianxia.sect.core.model.EquipStatValue
import com.xianxia.sect.core.model.EquipmentInstance
import com.xianxia.sect.core.model.EquipmentSlot
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 套装效果档位守卫（装备重构 B3，方案 §3.3/§6.1）。
 *
 * 0..6 件逐一断言：2/4/6 达档即生效、可越级不叠加（穿满 6 件时三档同时生效）；
 * 两套混穿按各自件数独立计档；同一 setId 的件数只按六槽位内实例条数统计。
 */
class EquipmentSetBonusTest {

    /** 最小空词条实例（套装件数统计只看 setId，词条全部清零隔离干扰） */
    private fun setPiece(setId: String, part: EquipmentSlot, id: String = "$setId-${part.name}") =
        EquipmentInstance(
            id = id,
            name = "测试件$id",
            setId = setId,
            part = part,
            growth = EquipGrowth(
                affix = EquipAffixSet(mainStat = EquipStatValue(EquipStat.ATTACK, 0.0))
            ),
            meta = com.xianxia.sect.core.model.EquipInstanceMeta(rarity = 1)
        )

    /** 六部位表（声明序即穿戴组合基准序） */
    private val parts = EquipmentSlot.entries.toList()

    @Test
    fun `0件与1件无套装效果`() {
        assertEquals(EquipBonus(), EquipStatResolver.resolveSetBonus(emptyList()))
        assertEquals(EquipBonus(), EquipStatResolver.resolveSetBonus(listOf(setPiece("lietian", parts[0]))))
    }

    @Test
    fun `2件套生效且3件不越级`() {
        val two = listOf(
            setPiece("lietian", parts[0]),
            setPiece("lietian", parts[1])
        )
        val bonus2 = EquipStatResolver.resolveSetBonus(two)
        assertEquals(0.10, bonus2.physicalDamageBonus, 1e-12)

        val three = two + setPiece("lietian", parts[2])
        val bonus3 = EquipStatResolver.resolveSetBonus(three)
        assertEquals("3 件仍只有 2 件档", 0.10, bonus3.physicalDamageBonus, 1e-12)
        assertEquals("3 件不触发 4 件套暴击", 0.0, bonus3.critRate, 1e-12)
    }

    @Test
    fun `4件套生效且5件不越级`() {
        val four = parts.take(4).map { setPiece("lietian", it) }
        val bonus4 = EquipStatResolver.resolveSetBonus(four)
        assertEquals("2 件档 +10% 类型伤害", 0.10, bonus4.physicalDamageBonus, 1e-12)
        assertEquals("4 件档 +12% 暴击率", 0.12, bonus4.critRate, 1e-12)

        val five = parts.take(5).map { setPiece("lietian", it) }
        val bonus5 = EquipStatResolver.resolveSetBonus(five)
        assertEquals("5 件仍只有 2+4 档", 0.12, bonus5.critRate, 1e-12)
    }

    @Test
    fun `穿满6件三档同时生效合计30类型伤害`() {
        val six = parts.map { setPiece("lietian", it) }
        val bonus6 = EquipStatResolver.resolveSetBonus(six)
        assertEquals("2+6 件档类型伤害合计 +30%", 0.30, bonus6.physicalDamageBonus, 1e-12)
        assertEquals("4 件档暴击率 +12%", 0.12, bonus6.critRate, 1e-12)
    }

    @Test
    fun `法术套通道独立`() {
        val six = parts.map { setPiece("zifu", it) }
        val bonus6 = EquipStatResolver.resolveSetBonus(six)
        assertEquals(0.30, bonus6.magicDamageBonus, 1e-12)
        assertEquals(0.25, bonus6.critDamage, 1e-12)
        assertEquals("物理通道零串扰", 0.0, bonus6.physicalDamageBonus, 1e-12)
    }

    @Test
    fun `两套混穿按各自件数独立计档`() {
        // 2 件裂天（物理 +10%）+ 4 件紫府（法术 +10%、暴伤 +25%）
        val mixed = parts.take(2).map { setPiece("lietian", it) } +
            parts.take(4).map { setPiece("zifu", it, id = "zifu-${it.name}") }
        val bonus = EquipStatResolver.resolveSetBonus(mixed)
        assertEquals(0.10, bonus.physicalDamageBonus, 1e-12)
        assertEquals(0.10, bonus.magicDamageBonus, 1e-12)
        assertEquals(0.25, bonus.critDamage, 1e-12)
        assertEquals("裂天未达 4 件档不产暴率", 0.0, bonus.critRate, 1e-12)
    }

    @Test
    fun `同套件数按实例条数统计不越表`() {
        // 同一 setId 的 6 件 = 12 件同样只算穿满（档位封顶三档）
        val twelve = parts.map { setPiece("lietian", it) } +
            parts.map { setPiece("lietian", it, id = "dup-${it.name}") }
        val bonus = EquipStatResolver.resolveSetBonus(twelve)
        assertEquals("超量件数不叠加第四档", 0.30, bonus.physicalDamageBonus, 1e-12)
    }

    @Test
    fun `散件与未知套装不计档`() {
        val scattered = listOf(
            setPiece("", parts[0]),
            setPiece("unknown_set", parts[1]),
            setPiece("", parts[2]),
            setPiece("unknown_set", parts[3])
        )
        assertEquals(EquipBonus(), EquipStatResolver.resolveSetBonus(scattered))
    }
}
