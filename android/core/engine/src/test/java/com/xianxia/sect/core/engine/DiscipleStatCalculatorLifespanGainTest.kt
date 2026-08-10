package com.xianxia.sect.core.engine

import com.xianxia.sect.core.engine.domain.disciple.DiscipleStatCalculator
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 验证 [DiscipleStatCalculator.calculateBreakthroughLifespanGain]。
 *
 * 2026-08-10 修复：突破寿元增益并入词条加成（原实现只算天赋——
 * 带"延年"词条弟子 lifespan 恒落后于特质加成水平，触发 AgeLifespanRule
 * 截断死循环导致永生）。
 *
 * 基准增益（lifespanGainForRealm）：筑基(realm 8)=50、金丹(realm 7)=100、
 * 元婴(realm 6)=200、化神(realm 5)=400、仙人(realm 0)=10000。
 * 加成：r5_lifespan 天赋 +45%、r3_aff_lifespan 词条 +28%、neg_aff_lifespan 词条 -15%。
 */
class DiscipleStatCalculatorLifespanGainTest {

    @Test
    fun `无特质时筑基突破增益为基准50`() {
        assertEquals(
            50,
            DiscipleStatCalculator.calculateBreakthroughLifespanGain(8, emptyList(), emptyList())
        )
    }

    @Test
    fun `天赋寿命加成45pc使筑基增益提升至72`() {
        assertEquals(
            72,
            DiscipleStatCalculator.calculateBreakthroughLifespanGain(8, listOf("r5_lifespan"), emptyList())
        )
    }

    @Test
    fun `天赋45pc与词条28pc叠加使筑基增益提升至86`() {
        assertEquals(
            86,
            DiscipleStatCalculator.calculateBreakthroughLifespanGain(
                8, listOf("r5_lifespan"), listOf("r3_aff_lifespan")
            )
        )
    }

    @Test
    fun `夭折词条15pc压低筑基增益至43`() {
        assertEquals(
            43,
            DiscipleStatCalculator.calculateBreakthroughLifespanGain(8, emptyList(), listOf("neg_aff_lifespan"))
        )
    }

    @Test
    fun `仙人境界无特质增益为10000`() {
        assertEquals(
            10000,
            DiscipleStatCalculator.calculateBreakthroughLifespanGain(0, emptyList(), emptyList())
        )
    }
}
