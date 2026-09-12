package com.xianxia.sect

import com.xianxia.sect.core.GameConfig
import org.junit.Assert.*
import org.junit.Test

class RealmConfigTest {
    // ============================================================
    // Realm 对象 - CONFIGS 基本结构
    // ============================================================

    @Test
    fun `境界配置应包含10个条目`() {
        assertEquals(10, GameConfig.Realm.CONFIGS.size)
    }

    @Test
    fun `境界配置的key应为0到9`() {
        val keys = GameConfig.Realm.CONFIGS.keys
        for (i in 0..9) {
            assertTrue("缺少境界key $i", keys.contains(i))
        }
    }

    // ============================================================
    // Realm 对象 - get 方法
    // ============================================================

    @Test
    fun `传入有效境界9应返回炼气配置`() {
        val config = GameConfig.Realm.get(9)
        assertEquals(9, config.level)
        assertEquals("炼气", config.name)
    }

    @Test
    fun `传入有效境界8应返回筑基配置`() {
        val config = GameConfig.Realm.get(8)
        assertEquals(8, config.level)
        assertEquals("筑基", config.name)
    }

    @Test
    fun `传入有效境界7应返回金丹配置`() {
        val config = GameConfig.Realm.get(7)
        assertEquals(7, config.level)
        assertEquals("金丹", config.name)
    }

    @Test
    fun `传入有效境界6应返回元婴配置`() {
        val config = GameConfig.Realm.get(6)
        assertEquals(6, config.level)
        assertEquals("元婴", config.name)
    }

    @Test
    fun `传入有效境界5应返回化神配置`() {
        val config = GameConfig.Realm.get(5)
        assertEquals(5, config.level)
        assertEquals("化神", config.name)
    }

    @Test
    fun `传入有效境界4应返回炼虚配置`() {
        val config = GameConfig.Realm.get(4)
        assertEquals(4, config.level)
        assertEquals("炼虚", config.name)
    }

    @Test
    fun `传入有效境界3应返回合体配置`() {
        val config = GameConfig.Realm.get(3)
        assertEquals(3, config.level)
        assertEquals("合体", config.name)
    }

    @Test
    fun `传入有效境界2应返回大乘配置`() {
        val config = GameConfig.Realm.get(2)
        assertEquals(2, config.level)
        assertEquals("大乘", config.name)
    }

    @Test
    fun `传入有效境界1应返回渡劫配置`() {
        val config = GameConfig.Realm.get(1)
        assertEquals(1, config.level)
        assertEquals("渡劫", config.name)
    }

    @Test
    fun `传入有效境界0应返回仙人配置`() {
        val config = GameConfig.Realm.get(0)
        assertEquals(0, config.level)
        assertEquals("仙人", config.name)
    }

    @Test
    fun `传入无效境界99应返回默认炼气配置`() {
        val config = GameConfig.Realm.get(99)
        assertEquals("炼气", config.name)
        assertEquals(9, config.level)
    }

    @Test
    fun `传入无效境界负数应返回默认炼气配置`() {
        val config = GameConfig.Realm.get(-1)
        assertEquals("炼气", config.name)
    }

    // ============================================================
    // Realm 对象 - getName 方法
    // ============================================================

    @Test
    fun `getName传入9应返回炼气`() {
        assertEquals("炼气", GameConfig.Realm.getName(9))
    }

    @Test
    fun `getName传入8应返回筑基`() {
        assertEquals("筑基", GameConfig.Realm.getName(8))
    }

    @Test
    fun `getName传入7应返回金丹`() {
        assertEquals("金丹", GameConfig.Realm.getName(7))
    }

    @Test
    fun `getName传入6应返回元婴`() {
        assertEquals("元婴", GameConfig.Realm.getName(6))
    }

    @Test
    fun `getName传入5应返回化神`() {
        assertEquals("化神", GameConfig.Realm.getName(5))
    }

    @Test
    fun `getName传入4应返回炼虚`() {
        assertEquals("炼虚", GameConfig.Realm.getName(4))
    }

    @Test
    fun `getName传入3应返回合体`() {
        assertEquals("合体", GameConfig.Realm.getName(3))
    }

    @Test
    fun `getName传入2应返回大乘`() {
        assertEquals("大乘", GameConfig.Realm.getName(2))
    }

    @Test
    fun `getName传入1应返回渡劫`() {
        assertEquals("渡劫", GameConfig.Realm.getName(1))
    }

    @Test
    fun `getName传入0应返回仙人`() {
        assertEquals("仙人", GameConfig.Realm.getName(0))
    }

    // ============================================================
    // Realm 对象 - cultivationBase 随境界提升而增大
    // ============================================================

    @Test
    fun `炼气的修炼基础值应为490`() {
        assertEquals(490, GameConfig.Realm.getCultivationBase(9))
    }

    @Test
    fun `筑基的修炼基础值应为1950`() {
        assertEquals(1950, GameConfig.Realm.getCultivationBase(8))
    }

    @Test
    fun `金丹的修炼基础值应为7800`() {
        assertEquals(7800, GameConfig.Realm.getCultivationBase(7))
    }

    @Test
    fun `元婴的修炼基础值应为29250`() {
        assertEquals(29250, GameConfig.Realm.getCultivationBase(6))
    }

    @Test
    fun `化神的修炼基础值应为97500`() {
        assertEquals(97500, GameConfig.Realm.getCultivationBase(5))
    }

    @Test
    fun `炼虚的修炼基础值应为292500`() {
        assertEquals(292500, GameConfig.Realm.getCultivationBase(4))
    }

    @Test
    fun `合体的修炼基础值应为975000`() {
        assertEquals(975000, GameConfig.Realm.getCultivationBase(3))
    }

    @Test
    fun `大乘的修炼基础值应为2925000`() {
        assertEquals(2925000, GameConfig.Realm.getCultivationBase(2))
    }

    @Test
    fun `渡劫的修炼基础值应为9750000`() {
        assertEquals(9750000, GameConfig.Realm.getCultivationBase(1))
    }

    @Test
    fun `仙人的修炼基础值应为29250000`() {
        assertEquals(29250000, GameConfig.Realm.getCultivationBase(0))
    }

    @Test
    fun `修炼基础值应随境界降低而增大`() {
        val bases = (9 downTo 0).map { GameConfig.Realm.getCultivationBase(it) }
        for (i in 0 until bases.size - 1) {
            assertTrue(
                "cultivationBase 在境界 ${9 - i} 到 ${9 - i - 1} 未增大",
                bases[i] < bases[i + 1]
            )
        }
    }

    // ============================================================
    // Realm 对象 - getBreakthroughChance 方法（含灵根和小境界）
    // ============================================================

    @Test
    fun `炼气单灵根1层突破概率应为0点9`() {
        assertEquals(0.90, GameConfig.Realm.getBreakthroughChance(9, 1, 1), 0.001)
    }

    @Test
    fun `筑基双灵根1层突破概率应为0点6`() {
        assertEquals(0.60, GameConfig.Realm.getBreakthroughChance(8, 2, 1), 0.001)
    }

    @Test
    fun `金丹三灵根1层突破概率应为0点3`() {
        assertEquals(0.30, GameConfig.Realm.getBreakthroughChance(7, 3, 1), 0.001)
    }

    @Test
    fun `元婴单灵根1层突破概率应为0点42`() {
        assertEquals(0.42, GameConfig.Realm.getBreakthroughChance(6, 1, 1), 0.001)
    }

    @Test
    fun `化神五灵根1层突破概率应为0`() {
        assertEquals(0.00, GameConfig.Realm.getBreakthroughChance(5, 5, 1), 0.001)
    }

    @Test
    fun `炼虚五灵根1层突破概率应为0`() {
        assertEquals(0.00, GameConfig.Realm.getBreakthroughChance(4, 5, 1), 0.001)
    }

    @Test
    fun `渡劫三灵根1层突破概率应为0`() {
        assertEquals(0.00, GameConfig.Realm.getBreakthroughChance(1, 3, 1), 0.001)
    }

    @Test
    fun `仙人单灵根1层突破概率应为0点02`() {
        assertEquals(0.02, GameConfig.Realm.getBreakthroughChance(0, 1, 1), 0.001)
    }

    @Test
    fun `同境界灵根越少突破概率越高`() {
        val chances = (1..5).map { GameConfig.Realm.getBreakthroughChance(7, it, 1) }
        for (i in 0 until chances.size - 1) {
            assertTrue(
                "金丹${i + 1}灵根突破概率应>${i + 2}灵根",
                chances[i] > chances[i + 1]
            )
        }
    }

    @Test
    fun `同境界同灵根层数越高突破概率越低或相等`() {
        val chances = (1..9).map { GameConfig.Realm.getBreakthroughChance(8, 2, it) }
        for (i in 0 until chances.size - 1) {
            assertTrue(
                "筑基双灵根${i + 1}层突破概率应>=${i + 2}层",
                chances[i] >= chances[i + 1]
            )
        }
    }

    @Test
    fun `9层突破概率等于下一大境界1层突破概率`() {
        assertEquals(
            GameConfig.Realm.getBreakthroughChance(7, 1, 1),
            GameConfig.Realm.getBreakthroughChance(8, 1, 9),
            0.001
        )
    }

    @Test
    fun `1层突破概率等于当前大境界基础概率`() {
        assertEquals(
            GameConfig.Realm.getBreakthroughChance(7, 3),
            GameConfig.Realm.getBreakthroughChance(7, 3, 1),
            0.001
        )
    }

    @Test
    fun `突破概率为整数百分比`() {
        for (realm in 0..9) {
            for (rootCount in 1..5) {
                for (layer in 1..9) {
                    val chance = GameConfig.Realm.getBreakthroughChance(realm, rootCount, layer)
                    assertEquals(
                        "realm=$realm, rootCount=$rootCount, layer=$layer 的突破概率应为整数百分比",
                        kotlin.math.round(chance * 100.0) / 100.0,
                        chance,
                        0.0001
                    )
                }
            }
        }
    }

    @Test
    fun `realmLayer为0时突破概率应为0`() {
        assertEquals(0.0, GameConfig.Realm.getBreakthroughChance(9, 1, 0), 0.001)
    }

    @Test
    fun `筑基双灵根5层突破概率应为0点5`() {
        assertEquals(0.50, GameConfig.Realm.getBreakthroughChance(8, 2, 5), 0.001)
    }

    // ============================================================
    // Realm 对象 - getMaxRarity 方法
    // ============================================================

    @Test
    fun `炼气境界的最大稀有度应为1`() {
        assertEquals(1, GameConfig.Realm.getMaxRarity(9))
    }

    @Test
    fun `筑基境界的最大稀有度应为1`() {
        assertEquals(1, GameConfig.Realm.getMaxRarity(8))
    }

    @Test
    fun `金丹境界的最大稀有度应为2`() {
        assertEquals(2, GameConfig.Realm.getMaxRarity(7))
    }

    @Test
    fun `元婴境界的最大稀有度应为3`() {
        assertEquals(3, GameConfig.Realm.getMaxRarity(6))
    }

    @Test
    fun `化神境界的最大稀有度应为4`() {
        assertEquals(4, GameConfig.Realm.getMaxRarity(5))
    }

    @Test
    fun `炼虚境界的最大稀有度应为5`() {
        assertEquals(5, GameConfig.Realm.getMaxRarity(4))
    }

    @Test
    fun `合体境界的最大稀有度应为5`() {
        assertEquals(5, GameConfig.Realm.getMaxRarity(3))
    }

    @Test
    fun `大乘境界的最大稀有度应为6`() {
        assertEquals(6, GameConfig.Realm.getMaxRarity(2))
    }

    @Test
    fun `渡劫境界的最大稀有度应为6`() {
        assertEquals(6, GameConfig.Realm.getMaxRarity(1))
    }

    @Test
    fun `仙人境界的最大稀有度应为6`() {
        assertEquals(6, GameConfig.Realm.getMaxRarity(0))
    }

    @Test
    fun `无效境界的最大稀有度应默认为1`() {
        assertEquals(1, GameConfig.Realm.getMaxRarity(99))
    }

    // ============================================================
    // Realm 对象 - getMinRealmForRarity 方法
    // ============================================================

    @Test
    fun `凡品对应的最小境界应为炼气9`() {
        assertEquals(9, GameConfig.Realm.getMinRealmForRarity(1))
    }

    @Test
    fun `灵品对应的最小境界应为金丹7`() {
        assertEquals(7, GameConfig.Realm.getMinRealmForRarity(2))
    }

    @Test
    fun `宝品对应的最小境界应为元婴6`() {
        assertEquals(6, GameConfig.Realm.getMinRealmForRarity(3))
    }

    @Test
    fun `玄品对应的最小境界应为化神5`() {
        assertEquals(5, GameConfig.Realm.getMinRealmForRarity(4))
    }

    @Test
    fun `地品对应的最小境界应为炼虚4`() {
        assertEquals(4, GameConfig.Realm.getMinRealmForRarity(5))
    }

    @Test
    fun `天品对应的最小境界应为大乘2`() {
        assertEquals(2, GameConfig.Realm.getMinRealmForRarity(6))
    }

    @Test
    fun `无效稀有度对应的最小境界应默认为炼气9`() {
        assertEquals(9, GameConfig.Realm.getMinRealmForRarity(99))
    }

    @Test
    fun `零稀有度对应的最小境界应默认为炼气9`() {
        assertEquals(9, GameConfig.Realm.getMinRealmForRarity(0))
    }

    // Realm 对象 - minReasonableAge 方法

    @Test
    fun `最小合理年龄表应覆盖全部10个境界`() {
        assertEquals(10, GameConfig.Realm.REALM_MIN_REASONABLE_AGE.size)
        assertEquals(GameConfig.Realm.CONFIGS.keys,
            GameConfig.Realm.REALM_MIN_REASONABLE_AGE.keys)
    }

    @Test
    fun `最小合理年龄应低于对应境界寿元上限`() {
        for ((realm, minAge) in GameConfig.Realm.REALM_MIN_REASONABLE_AGE) {
            val maxAge = GameConfig.Realm.get(realm).maxAge
            assertTrue("境界 $realm 最小年龄 $minAge 应小于寿元 $maxAge",
                minAge < maxAge)
        }
    }

    @Test
    fun `未知境界的最小合理年龄应回退炼气标准`() {
        assertEquals(10, GameConfig.Realm.minReasonableAge(99))
        assertEquals(10, GameConfig.Realm.minReasonableAge(-1))
    }

    @Test
    fun `炼气最小合理年龄应为10岁`() {
        assertEquals(10, GameConfig.Realm.minReasonableAge(9))
    }

    @Test
    fun `炼虚最小合理年龄应为300岁`() {
        assertEquals(300, GameConfig.Realm.minReasonableAge(4))
    }
}
