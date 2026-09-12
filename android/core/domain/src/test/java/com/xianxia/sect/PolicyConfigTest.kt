package com.xianxia.sect

import com.xianxia.sect.core.GameConfig
import org.junit.Assert.*
import org.junit.Test

class PolicyConfigTest {
    // ============================================================
    // PolicyConfig 对象 - 各种常量值
    // ============================================================

    @Test
    fun `灵矿增产消耗应为0`() {
        assertEquals(0L, GameConfig.PolicyConfig.SPIRIT_MINE_BOOST_MONTHLY)
    }

    @Test
    fun `增强治安消耗应为3000`() {
        assertEquals(3000L, GameConfig.PolicyConfig.ENHANCED_SECURITY_MONTHLY)
    }

    @Test
    fun `丹道激励消耗应为3000`() {
        assertEquals(3000L, GameConfig.PolicyConfig.ALCHEMY_INCENTIVE_MONTHLY)
    }

    @Test
    fun `锻造激励消耗应为3000`() {
        assertEquals(3000L, GameConfig.PolicyConfig.FORGE_INCENTIVE_MONTHLY)
    }

    @Test
    fun `灵药培育消耗应为3000`() {
        assertEquals(3000L, GameConfig.PolicyConfig.HERB_CULTIVATION_MONTHLY)
    }

    @Test
    fun `修行津贴消耗应为300`() {
        assertEquals(300L, GameConfig.PolicyConfig.CULTIVATION_SUBSIDY_PER_DISCIPLE)
    }

    @Test
    fun `功法研习消耗应为4000`() {
        assertEquals(4000L, GameConfig.PolicyConfig.MANUAL_RESEARCH_MONTHLY)
    }

    @Test
    fun `灵矿增产名称应为灵矿增产`() {
        assertEquals("灵矿增产", GameConfig.PolicyConfig.SPIRIT_MINE_BOOST_NAME)
    }

    @Test
    fun `增强治安名称应为增强治安`() {
        assertEquals("增强治安", GameConfig.PolicyConfig.ENHANCED_SECURITY_NAME)
    }

    @Test
    fun `丹道激励名称应为丹道激励`() {
        assertEquals("丹道激励", GameConfig.PolicyConfig.ALCHEMY_INCENTIVE_NAME)
    }

    @Test
    fun `锻造激励名称应为锻造激励`() {
        assertEquals("锻造激励", GameConfig.PolicyConfig.FORGE_INCENTIVE_NAME)
    }

    @Test
    fun `灵药培育名称应为灵药培育`() {
        assertEquals("灵药培育", GameConfig.PolicyConfig.HERB_CULTIVATION_NAME)
    }

    @Test
    fun `修行津贴名称应为修行津贴`() {
        assertEquals("修行津贴", GameConfig.PolicyConfig.CULTIVATION_SUBSIDY_NAME)
    }

    @Test
    fun `功法研习名称应为功法研习`() {
        assertEquals("功法研习", GameConfig.PolicyConfig.MANUAL_RESEARCH_NAME)
    }

    @Test
    fun `灵矿增产效果应为20百分比`() {
        assertEquals(0.20, GameConfig.PolicyConfig.SPIRIT_MINE_BOOST_EFFECT, 0.001)
    }

    @Test
    fun `增强治安效果应为20百分比`() {
        assertEquals(0.20, GameConfig.PolicyConfig.ENHANCED_SECURITY_EFFECT, 0.001)
    }

    @Test
    fun `丹道激励效果应为10百分比`() {
        assertEquals(0.10, GameConfig.PolicyConfig.ALCHEMY_INCENTIVE_EFFECT, 0.001)
    }

    @Test
    fun `锻造激励效果应为10百分比`() {
        assertEquals(0.10, GameConfig.PolicyConfig.FORGE_INCENTIVE_EFFECT, 0.001)
    }

    @Test
    fun `灵药培育效果应为20百分比`() {
        assertEquals(0.20, GameConfig.PolicyConfig.HERB_CULTIVATION_EFFECT, 0.001)
    }

    @Test
    fun `修行津贴效果应为15百分比`() {
        assertEquals(0.15, GameConfig.PolicyConfig.CULTIVATION_SUBSIDY_EFFECT, 0.001)
    }

    @Test
    fun `功法研习效果应为20百分比`() {
        assertEquals(0.20, GameConfig.PolicyConfig.MANUAL_RESEARCH_EFFECT, 0.001)
    }

    @Test
    fun `副宗主智力基准值应为50`() {
        assertEquals(50, GameConfig.PolicyConfig.VICE_SECT_MASTER_INTELLIGENCE_BASE)
    }

    @Test
    fun `副宗主智力步长应为5`() {
        assertEquals(5, GameConfig.PolicyConfig.VICE_SECT_MASTER_INTELLIGENCE_STEP)
    }

    @Test
    fun `副宗主智力每步加成应为0点01`() {
        assertEquals(0.01, GameConfig.PolicyConfig.VICE_SECT_MASTER_INTELLIGENCE_BONUS_PER_STEP, 0.001)
    }
}
