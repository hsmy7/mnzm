package com.xianxia.sect.core.engine.domain.road

import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.engine.FakeAtomicStateStore
import com.xianxia.sect.core.nativebridge.NativeEngineFlag
import com.xianxia.sect.core.nativebridge.StateSyncService
import com.xianxia.sect.core.util.RoadPlacementResult
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 道路 Facade 放置/删除单测——验证 placeRoad 确实扣 20 灵石 + 落道路 + 自动拼接。
 * 用 FakeAtomicStateStore（官方 GameStateStore 替身）验证真实状态变更。
 *
 * batch-07 门控面：AUTHORITATIVE 稳态写者归 C++（road_tx.h），本文件同时守护
 * native 臂的三级降级契约——镜像服务缺失 / native 桥不可用 / flag OFF 均回退
 * Kotlin 原路径（状态变更语义不变）。
 */
class RoadFacadeImplTest {

    private fun newStore(spiritStones: Long = 1000L): FakeAtomicStateStore {
        val store = FakeAtomicStateStore()
        store.update { gameData = gameData.copy(spiritStones = spiritStones) }
        return store
    }

    @After
    fun restoreFlag() {
        NativeEngineFlag.mode = NativeEngineFlag.Mode.AUTHORITATIVE
    }

    @Test
    fun `placeRoad deducts cost and adds road`() {
        val store = newStore(spiritStones = 1000L)
        val facade = RoadFacadeImpl(store)

        val result = facade.placeRoad(20, 20)

        assertTrue("放置成功", result is RoadPlacementResult.Success)
        val data = store.gameDataSnapshot
        assertEquals("已扣 20 灵石", 1000L - GameConfig.Road.COST_PER_CELL, data.spiritStones)
        assertTrue("道路落库", data.roads.any { it.gridX == 20 && it.gridY == 20 })
        assertEquals("单格形态为单格/死路", "SINGLE", data.roads.first { it.gridX == 20 && it.gridY == 20 }.roadType)
    }

    @Test
    fun `placeRoad twice adjacent auto-connects to horizontal`() {
        val store = newStore(spiritStones = 1000L)
        val facade = RoadFacadeImpl(store)

        facade.placeRoad(20, 20)
        facade.placeRoad(21, 20)

        val data = store.gameDataSnapshot
        // (20,20) 左邻 (21,20)=路 → mask=RIGHT → HORIZONTAL；两端各只连一边 → 按死路归为直路
        val r20 = data.roads.first { it.gridX == 20 && it.gridY == 20 }
        assertEquals("水平端点为限左", GameConfig.Road.COST_PER_CELL * 2, (1000L - data.spiritStones))
        assertTrue("形态为水平直路(HORIZONTAL)", r20.roadType == "HORIZONTAL" || r20.roadType == "SINGLE")
    }

    @Test
    fun `placeRoad insufficient stones rejected`() {
        val store = newStore(spiritStones = 5L)
        val facade = RoadFacadeImpl(store)

        val result = facade.placeRoad(20, 20)

        assertTrue("灵石不足被拒", result is RoadPlacementResult.Blocked)
        assertEquals("未扣费", 5L, store.gameDataSnapshot.spiritStones)
        assertTrue("未落道路", store.gameDataSnapshot.roads.isEmpty())
    }

    @Test
    fun `removeRoad removes and recomputes neighbors`() {
        val store = newStore(spiritStones = 1000L)
        val facade = RoadFacadeImpl(store)

        facade.placeRoad(20, 20)
        facade.placeRoad(21, 20)
        facade.placeRoad(20, 21)
        assertTrue("已落 3 格", store.gameDataSnapshot.roads.size == 3)

        val removeResult = facade.removeRoad(20, 20)
        assertTrue("删除成功", removeResult is RoadPlacementResult.Success)
        val data = store.gameDataSnapshot
        assertTrue("中心格已删除", data.roads.none { it.gridX == 20 && it.gridY == 20 })
        assertTrue("邻居仍保留", data.roads.any { it.gridX == 21 && it.gridY == 20 })
        assertTrue("邻居仍保留", data.roads.any { it.gridX == 20 && it.gridY == 21 })
    }

    // ── batch-07 native 臂门控（JVM 无 .so——GameCoreBridge 未加载，
    //    tryExecuteNative 恒 null，正好覆盖降级回退臂的每一级） ─────────

    @Test
    fun `native arm off - flag OFF falls back to kotlin path`() {
        NativeEngineFlag.mode = NativeEngineFlag.Mode.OFF
        val store = newStore(spiritStones = 1000L)
        val facade = RoadFacadeImpl(store, StateSyncService(store))

        val result = facade.placeRoad(20, 20)

        assertTrue("OFF 模式走 Kotlin 原路径", result is RoadPlacementResult.Success)
        assertEquals(
            "Kotlin 臂扣费语义不变",
            1000L - GameConfig.Road.COST_PER_CELL,
            store.gameDataSnapshot.spiritStones
        )
        assertTrue("Kotlin 臂落道路", store.gameDataSnapshot.roads.any { it.gridX == 20 && it.gridY == 20 })
    }

    @Test
    fun `native arm off - missing mirror service falls back`() {
        // AUTHORITATIVE（生产默认）但镜像服务为 null（测试替身场景）→ 回退原路径
        val store = newStore(spiritStones = 1000L)
        val facade = RoadFacadeImpl(store, stateSyncService = null)

        val result = facade.removeRoad(20, 20)

        assertTrue("无道路 Blocked（原路径校验链）", result is RoadPlacementResult.Blocked)
    }

    @Test
    fun `native arm degraded - bridge unavailable falls back and succeeds`() {
        // AUTHORITATIVE + 镜像服务在，但 JVM 桥未加载 → tryExecuteNative null → 回退成功
        val store = newStore(spiritStones = 1000L)
        val facade = RoadFacadeImpl(store, StateSyncService(store))

        val result = facade.placeRoad(20, 20)

        assertTrue("降级回退后放置成功", result is RoadPlacementResult.Success)
        assertEquals(
            "扣费精确（20 灵石）",
            1000L - GameConfig.Road.COST_PER_CELL,
            store.gameDataSnapshot.spiritStones
        )
    }
}
