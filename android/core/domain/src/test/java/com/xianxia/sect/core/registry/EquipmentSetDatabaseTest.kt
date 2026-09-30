package com.xianxia.sect.core.registry

import com.xianxia.sect.core.model.EquipStat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 套装注册表守卫（装备重构 B3，方案 §3.3/§3.7/§6.1）。
 *
 * 首期两套：物理套「裂天罡煞」（lietian）+ 法术套「紫府玄冥」（zifu），
 * 各 2/4/6 三档；2 件套 = 流派类型伤害差异化（§15.7 Q9）、穿满一套 = 该流派
 * +30% 类型伤害。本测试钉死表内容、档位门槛与两套差异化。
 */
class EquipmentSetDatabaseTest {

    @Test
    fun `两套套装与部件表的setId完全对应`() {
        val setIds = EquipmentSetDatabase.sets.map { it.id }.toSet()
        val pieceSetIds = EquipmentDatabase.setPieces.map { it.setId }.toSet()
        assertEquals("套装表与部件表的 setId 集合应一致", setIds, pieceSetIds)
        assertEquals("首期两套", 2, EquipmentSetDatabase.sets.size)
        // 12 部件 = 2 套 × 6 部位
        assertEquals("每套 6 部位", 12, EquipmentDatabase.setPieces.size)
        EquipmentSetDatabase.sets.forEach { set ->
            assertEquals(
                "套装 ${set.id} 部件数",
                6, EquipmentDatabase.setPieces.count { it.setId == set.id }
            )
        }
    }

    @Test
    fun `两套流派与名称钉死`() {
        val lietian = EquipmentSetDatabase.getById("lietian")
        val zifu = EquipmentSetDatabase.getById("zifu")
        assertNotNull(lietian)
        assertNotNull(zifu)
        lietian ?: return
        zifu ?: return
        assertEquals("裂天罡煞", lietian.name)
        assertEquals("紫府玄冥", zifu.name)
        assertEquals(EquipSchool.PHYSICAL, lietian.school)
        assertEquals(EquipSchool.MAGIC, zifu.school)
        assertNull("未知套装 id 应返回 null", EquipmentSetDatabase.getById("unknown"))
        assertEquals(lietian, EquipmentSetDatabase.getByName("裂天罡煞"))
        assertEquals(zifu, EquipmentSetDatabase.getByName("紫府玄冥"))
    }

    @Test
    fun `档位门槛不越级`() {
        EquipmentSetDatabase.sets.forEach { set ->
            assertTrue("0 件不应有档位生效", set.activeBonuses(0).isEmpty())
            assertTrue("1 件不应有档位生效", set.activeBonuses(1).isEmpty())
            assertEquals("2 件生效 1 档", 1, set.activeBonuses(2).size)
            assertEquals("3 件仍只 1 档（2 件档）", 1, set.activeBonuses(3).size)
            assertEquals("4 件生效 2 档", 2, set.activeBonuses(4).size)
            assertEquals("5 件仍 2 档", 2, set.activeBonuses(5).size)
            assertEquals("6 件三档全生效（不叠加不越级=全部列出）", 3, set.activeBonuses(6).size)
            // 档位引用与表声明一致
            assertEquals(listOf(set.bonus2), set.activeBonuses(2))
            assertEquals(listOf(set.bonus2, set.bonus4), set.activeBonuses(4))
            assertEquals(listOf(set.bonus2, set.bonus4, set.bonus6), set.activeBonuses(6))
        }
    }

    @Test
    fun `2件套类型伤害两套差异化`() {
        // §15.7 Q9：同为 +10% 但流派通道不同——物理套走 PHYSICAL、法术套走 MAGIC
        val lietian = EquipmentSetDatabase.getById("lietian")!!
        val zifu = EquipmentSetDatabase.getById("zifu")!!
        val lietianBonus2 = lietian.bonus2.entries.single()
        val zifuBonus2 = zifu.bonus2.entries.single()
        assertEquals(EquipStat.PHYSICAL_DAMAGE_PCT, lietianBonus2.stat)
        assertEquals(0.10, lietianBonus2.value, 1e-12)
        assertEquals(EquipStat.MAGIC_DAMAGE_PCT, zifuBonus2.stat)
        assertEquals(0.10, zifuBonus2.value, 1e-12)
    }

    @Test
    fun `4件套暴击面两套差异化`() {
        val lietian = EquipmentSetDatabase.getById("lietian")!!
        val zifu = EquipmentSetDatabase.getById("zifu")!!
        val lietianBonus4 = lietian.bonus4.entries.single()
        val zifuBonus4 = zifu.bonus4.entries.single()
        assertEquals(EquipStat.CRIT_RATE, lietianBonus4.stat)
        assertEquals(0.12, lietianBonus4.value, 1e-12)
        assertEquals(EquipStat.CRIT_DAMAGE, zifuBonus4.stat)
        assertEquals(0.25, zifuBonus4.value, 1e-12)
    }

    @Test
    fun `穿满一套为该流派加30类型伤害`() {
        EquipmentSetDatabase.sets.forEach { set ->
            val bonus2 = set.bonus2.entries.single()
            val bonus6 = set.bonus6.entries.single()
            assertEquals("套装 ${set.id} 6 件套应与其 2 件套同通道", bonus2.stat, bonus6.stat)
            assertEquals("套装 ${set.id} 2+6 件套类型伤害合计 +30%", 0.30, bonus2.value + bonus6.value, 1e-12)
            assertTrue("套装 ${set.id} 每档应为单条词条", set.bonus4.entries.size == 1)
        }
    }
}
