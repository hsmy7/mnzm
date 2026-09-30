package com.xianxia.sect.core.registry

import com.xianxia.sect.core.model.EquipStat
import com.xianxia.sect.core.model.EquipmentSlot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * 部位主词条池守卫（装备重构 B3，方案 §3.4.2/§6.1）。
 *
 * 主词条从部位候选池等权抽取（`nextInt(size)` 索引抽取），**池的声明序参与
 * 抽取序列**——重排即改写同种子抽取结果，破坏跨端对拍与存档确定性。
 * 本测试同时钉死：池容量、池内容、声明序、部位系数、品阶基数表与抽取确定性。
 */
class EquipMainStatPoolTest {

    @Test
    fun `六部位池容量与方案拍板表一致`() {
        // 头 2 / 身 4 / 手 3 / 脚 5 / 武 3 / 腿 5（2026-09-29 拍板：武器输出向、腿部 R9 原池）
        val expectedSizes = mapOf(
            EquipmentSlot.HEAD to 2,
            EquipmentSlot.BODY to 4,
            EquipmentSlot.HANDS to 3,
            EquipmentSlot.FEET to 5,
            EquipmentSlot.WEAPON to 3,
            EquipmentSlot.LEGS to 5
        )
        expectedSizes.forEach { (part, size) ->
            assertEquals("部位 $part 的主词条候选池容量", size, EquipMainStatPool.poolFor(part).size)
        }
    }

    @Test
    fun `逐部位抽取结果恒落在该部位池内`() {
        EquipmentSlot.entries.forEach { part ->
            val pool = EquipMainStatPool.poolFor(part)
            repeat(200) { seed ->
                val rolled = EquipMainStatPool.rollMainStat(part, Random(seed))
                assertTrue(
                    "部位 $part 抽出 $rolled 不在候选池 $pool 内",
                    rolled in pool
                )
            }
        }
    }

    @Test
    fun `同种子抽取序列逐位稳定`() {
        EquipmentSlot.entries.forEach { part ->
            val sequenceA = List(64) { EquipMainStatPool.rollMainStat(part, Random(20260930)) }
            val sequenceB = List(64) { EquipMainStatPool.rollMainStat(part, Random(20260930)) }
            assertEquals("部位 $part 同种子抽取序列漂移", sequenceA, sequenceB)
        }
    }

    @Test
    fun `池内容与声明序钉死（禁重排）`() {
        val expected = mapOf(
            EquipmentSlot.HEAD to listOf(EquipStat.HP, EquipStat.DEFENSE),
            EquipmentSlot.BODY to listOf(
                EquipStat.DEFENSE, EquipStat.ATTACK, EquipStat.CRIT_RATE, EquipStat.CRIT_DAMAGE
            ),
            EquipmentSlot.HANDS to listOf(EquipStat.ATTACK, EquipStat.CRIT_RATE, EquipStat.CRIT_DAMAGE),
            EquipmentSlot.FEET to listOf(
                EquipStat.DEFENSE, EquipStat.ATTACK, EquipStat.CRIT_RATE, EquipStat.CRIT_DAMAGE, EquipStat.HP
            ),
            EquipmentSlot.WEAPON to listOf(EquipStat.ATTACK, EquipStat.CRIT_RATE, EquipStat.CRIT_DAMAGE),
            EquipmentSlot.LEGS to listOf(
                EquipStat.DEFENSE, EquipStat.ATTACK, EquipStat.CRIT_RATE, EquipStat.CRIT_DAMAGE, EquipStat.HP
            )
        )
        expected.forEach { (part, pool) ->
            assertEquals(
                "部位 $part 池内容或声明序被改动（重排会破坏同种子抽取序列）",
                pool, EquipMainStatPool.poolFor(part)
            )
        }
    }

    @Test
    fun `部位系数与方案表一致`() {
        val expected = mapOf(
            EquipmentSlot.HEAD to 1.00,
            EquipmentSlot.BODY to 1.00,
            EquipmentSlot.HANDS to 1.15,
            EquipmentSlot.FEET to 0.95,
            EquipmentSlot.WEAPON to 1.15,
            EquipmentSlot.LEGS to 0.95
        )
        expected.forEach { (part, coefficient) ->
            assertEquals("部位 $part 数值系数", coefficient, EquipMainStatPool.partCoefficient(part), 1e-12)
        }
    }

    @Test
    fun `品阶基数随品阶单调不减且越界收敛`() {
        listOf(EquipStat.ATTACK, EquipStat.DEFENSE, EquipStat.HP, EquipStat.CRIT_RATE).forEach { stat ->
            val bases = (1..6).map { EquipMainStatPool.baseValue(stat, it) }
            assertEquals(
                "$stat 品阶基数表长度",
                6, bases.size
            )
            bases.zipWithNext().forEach { (low, high) ->
                assertTrue("$stat 品阶基数须单调不减：$low -> $high", high >= low)
            }
            // 越界收敛：非法品阶按最高/最低档收敛而非抛异常
            assertEquals("rarity=0 应收敛到最低档", bases.first(), EquipMainStatPool.baseValue(stat, 0), 1e-12)
            assertEquals("rarity=7 应收敛到最高档", bases.last(), EquipMainStatPool.baseValue(stat, 7), 1e-12)
        }
    }

    @Test
    fun `暴击伤害主词条基数为暴击率同档两倍`() {
        (1..6).forEach { rarity ->
            val critRate = EquipMainStatPool.baseValue(EquipStat.CRIT_RATE, rarity)
            val critDamage = EquipMainStatPool.baseValue(EquipStat.CRIT_DAMAGE, rarity)
            assertEquals("品阶 $rarity 暴伤基数应为暴率两倍", critRate * 2.0, critDamage, 1e-12)
        }
    }

    @Test
    fun `主词条完整值=品阶基数x部位系数`() {
        EquipmentSlot.entries.forEach { part ->
            EquipMainStatPool.poolFor(part).forEach { stat ->
                (1..6).forEach { rarity ->
                    val expected = EquipMainStatPool.baseValue(stat, rarity) *
                        EquipMainStatPool.partCoefficient(part)
                    val actual = EquipMainStatPool.mainStatValue(stat, part, rarity)
                    assertEquals("stat=$stat part=$part rarity=$rarity", expected, actual.value, 1e-12)
                    assertEquals(stat, actual.stat)
                }
            }
        }
    }
}
