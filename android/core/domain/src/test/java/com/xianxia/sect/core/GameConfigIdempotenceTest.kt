package com.xianxia.sect.core

import com.xianxia.sect.core.config.GameConfigData
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `GameConfig.initialize` 幂等守卫测试（docs/architecture.md 待办 D-30）。
 *
 * 背景：每次游戏内读档/重开（boot）经 `ResourcePreloader.preloadGameResources` 重复调用
 * `initialize`。配置内容在进程生命周期内不变，重复覆盖赋值属无谓开销。
 * 守卫语义：进程级仅首次真正执行，后续调用跳过并保持首次配置。
 */
class GameConfigIdempotenceTest {

    @After
    fun tearDown() {
        GameConfig.resetForTest()
    }

    @Test
    fun `initialize - 首次调用生效`() {
        val config = GameConfigData().copy(
            warehouse = GameConfigData().warehouse.copy(baseCapacity = 77)
        )
        GameConfig.initialize(config)
        assertEquals(77, GameConfig.Warehouse.BASE_CAPACITY)
    }

    @Test
    fun `initialize - 第二次调用不覆盖首次配置`() {
        val first = GameConfigData().copy(
            production = GameConfigData().production.copy(spiritMineBaseOutputPerMiner = 161)
        )
        val second = GameConfigData().copy(
            production = GameConfigData().production.copy(spiritMineBaseOutputPerMiner = 162)
        )
        GameConfig.initialize(first)
        GameConfig.initialize(second)
        assertEquals(161, GameConfig.Production.SPIRIT_MINE_BASE_OUTPUT_PER_MINER)
    }

    @Test
    fun `initialize - 相同配置重复调用无副作用`() {
        val config = GameConfigData().copy(
            warehouse = GameConfigData().warehouse.copy(capacityPerBuilding = 88)
        )
        GameConfig.initialize(config)
        GameConfig.initialize(config)
        assertEquals(88, GameConfig.Warehouse.CAPACITY_PER_BUILDING)
    }
}
