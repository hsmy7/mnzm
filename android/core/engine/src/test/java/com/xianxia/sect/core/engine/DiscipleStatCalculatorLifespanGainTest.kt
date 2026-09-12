package com.xianxia.sect.core.engine

import com.xianxia.sect.core.engine.domain.disciple.DiscipleStatCalculator
import org.junit.Assert.assertEquals
import org.junit.Test
import com.xianxia.sect.core.engine.domain.disciple.calculateBreakthroughLifespanGain

/**
 * 验证 [DiscipleStatCalculator.calculateBreakthroughLifespanGain]。
 *
 * 突破寿元增益并入天赋/词条加成，保证带"延年"词条弟子的 lifespan 与
 * 特质加成水平一致（否则 AgeLifespanRule 截断会形成死循环）。
 *
 * 基准增益（lifespanGainForRealm）：筑基(realm 8)=40、金丹(realm 7)=95、
 * 元婴(realm 6)=255、化神(realm 5)=500、仙人(realm 0)=10000。
 * 加成：r5_lifespan 天赋 +45%、r3_aff_lifespan 词条 +28%、neg_aff_lifespan 词条 -15%。
 */
class DiscipleStatCalculatorLifespanGainTest {

    @Test
    fun `无特质时筑基突破增益为基准40`() {
        assertEquals(
            40,
            DiscipleStatCalculator.calculateBreakthroughLifespanGain(8, emptyList(), emptyList())
        )
    }

    @Test
    fun `天赋寿命加成45pc使筑基增益提升至58`() {
        assertEquals(
            58,
            DiscipleStatCalculator.calculateBreakthroughLifespanGain(8, listOf("r5_lifespan"), emptyList())
        )
    }

    @Test
    fun `天赋45pc与词条28pc叠加使筑基增益提升至69`() {
        assertEquals(
            69,
            DiscipleStatCalculator.calculateBreakthroughLifespanGain(
                8, listOf("r5_lifespan"), listOf("r3_aff_lifespan")
            )
        )
    }

    @Test
    fun `夭折词条15pc压低筑基增益至34`() {
        assertEquals(
            34,
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
