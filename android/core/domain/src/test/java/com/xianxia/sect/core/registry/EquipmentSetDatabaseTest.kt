package com.xianxia.sect.core.registry

import com.xianxia.sect.core.model.EquipStat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 套装注册表守卫（五行属性伤害系统，方案 §3.4/§6.1）。
 *
 * 六套：物理套「裂天罡煞」（lietian）+ 五行套（庚金白虎/青木长生/玄水寒渊/
 * 离火焚天/厚土镇岳），各 2/4/6 三档。**同构骨架仅元素不同**：2 件套 = 本系
 * 伤害 +10%、4 件套 = 暴击率 +12%、6 件套 = 本系伤害 +20%；穿满一套 = 该系
 * +30% 类型伤害。本测试钉死表内容、档位门槛与同构骨架。
 */
class EquipmentSetDatabaseTest {

    /** 六套（id, 名称, 流派, 本系伤害词条）的钉死表 */
    private val expectedSets: List<List<Any>> = listOf(
        listOf("lietian", "裂天罡煞", EquipSchool.PHYSICAL, EquipStat.PHYSICAL_DAMAGE_PCT),
        listOf("gengjin", "庚金白虎", EquipSchool.METAL, EquipStat.METAL_DAMAGE_PCT),
        listOf("qingmu", "青木长生", EquipSchool.WOOD, EquipStat.WOOD_DAMAGE_PCT),
        listOf("xuanshui", "玄水寒渊", EquipSchool.WATER, EquipStat.WATER_DAMAGE_PCT),
        listOf("lihuo", "离火焚天", EquipSchool.FIRE, EquipStat.FIRE_DAMAGE_PCT),
        listOf("houtu", "厚土镇岳", EquipSchool.EARTH, EquipStat.EARTH_DAMAGE_PCT)
    )

    @Test
    fun `六套套装与部件表的setId完全对应`() {
        val setIds = EquipmentSetDatabase.sets.map { it.id }.toSet()
        val pieceSetIds = EquipmentDatabase.setPieces.map { it.setId }.toSet()
        assertEquals("套装表与部件表的 setId 集合应一致", setIds, pieceSetIds)
        assertEquals("六套（物理 + 五行）", 6, EquipmentSetDatabase.sets.size)
        // 36 部件 = 6 套 × 6 部位
        assertEquals("每套 6 部位", 36, EquipmentDatabase.setPieces.size)
        EquipmentSetDatabase.sets.forEach { set ->
            assertEquals(
                "套装 ${set.id} 部件数",
                6, EquipmentDatabase.setPieces.count { it.setId == set.id }
            )
        }
    }

    @Test
    fun `六套流派与名称钉死`() {
        assertEquals(expectedSets.size, EquipmentSetDatabase.sets.size)
        EquipmentSetDatabase.sets.forEachIndexed { index, set ->
            assertEquals(expectedSets[index][0], set.id)
            assertEquals(expectedSets[index][1], set.name)
            assertEquals(expectedSets[index][2], set.school)
        }
        assertNull("未知套装 id 应返回 null", EquipmentSetDatabase.getById("unknown"))
        assertNull("退役法术套 zifu 应已移除", EquipmentSetDatabase.getById("zifu"))
        assertEquals(
            EquipmentSetDatabase.sets.first(),
            EquipmentSetDatabase.getByName("裂天罡煞")
        )
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
    fun `同构骨架六套统一 —— 本系伤害与暴击率档位`() {
        EquipmentSetDatabase.sets.forEachIndexed { index, set ->
            val stat = expectedSets[index][3] as EquipStat
            val bonus2 = set.bonus2.entries.single()
            val bonus4 = set.bonus4.entries.single()
            val bonus6 = set.bonus6.entries.single()
            assertEquals("套装 ${set.id} 2 件套本系词条", stat, bonus2.stat)
            assertEquals("套装 ${set.id} 6 件套本系词条", stat, bonus6.stat)
            assertEquals("套装 ${set.id} 4 件套统一暴击率", EquipStat.CRIT_RATE, bonus4.stat)
            assertEquals(0.10, bonus2.value, 1e-12)
            assertEquals(0.12, bonus4.value, 1e-12)
            assertEquals(0.20, bonus6.value, 1e-12)
        }
    }

    @Test
    fun `穿满一套为该系加30类型伤害`() {
        EquipmentSetDatabase.sets.forEach { set ->
            val bonus2 = set.bonus2.entries.single()
            val bonus6 = set.bonus6.entries.single()
            assertEquals("套装 ${set.id} 6 件套应与其 2 件套同通道", bonus2.stat, bonus6.stat)
            assertEquals("套装 ${set.id} 2+6 件套类型伤害合计 +30%", 0.30, bonus2.value + bonus6.value, 1e-12)
            assertTrue("套装 ${set.id} 每档应为单条词条", set.bonus4.entries.size == 1)
        }
    }
}
