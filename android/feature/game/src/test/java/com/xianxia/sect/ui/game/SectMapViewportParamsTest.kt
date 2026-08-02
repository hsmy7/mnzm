package com.xianxia.sect.ui.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * SectMapViewport 参数稳定性测试（P-7 验证，2026-08-02）。
 *
 * P-7 的重组优化依赖 Compose 的"参数相等跳过重组"语义：
 * MainGameScreen 每旬 gameData 变化重组时，viewportParams/previewState 的
 * 引用未变（derivedStateOf 依赖未变 → 引用稳定）→ SectMapViewport 跳过重组
 * → AndroidView update 不执行。
 *
 * 本测试守卫参数 data class 的相等语义（引用稳定性依赖的基础）：
 * 1. 同字段参数相等（Compose 跳过重组的判定依据）
 * 2. 任一字段变化不等（预览状态变化触发重组）
 * 3. 列表字段按内容比较（buildingDataArray 等数组为引用比较——由 remember 保证）
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SectMapViewportParamsTest {

    private fun params(
        flatTileData: IntArray = intArrayOf(1, 2, 3),
        cameraState: com.xianxia.sect.ui.game.map.sect.SectCameraState =
            com.xianxia.sect.ui.game.map.sect.SectCameraState(768f, 768f)
    ) = SectMapViewportParams(
        nativeConfig = com.xianxia.sect.ui.game.sect.NativeRenderConfig(
            tileSize = 32,
            worldWidthCells = 24,
            worldHeightCells = 24,
            worldPixelWidth = 768,
            worldPixelHeight = 768
        ),
        cameraState = cameraState,
        flatTileData = flatTileData,
        buildingDataArray = null,
        buildingCount = 0,
        tileSize = 32,
        worldWidthCells = 24,
        worldHeightCells = 24,
        forceSoftwareRendering = false,
        vulkanInitListener = null,
        buildingSpriteSizes = emptyMap()
    )

    @Test
    fun `同字段参数相等`() {
        // cameraState/flatTileData 是引用比较（非 data class/数组）——真实场景由
        // rememberSectCamera/remember(tileData) 保证同一引用（MainGameScreen 每旬
        // 重组时引用稳定 → Compose 跳过 SectMapViewport 重组）
        val cam = com.xianxia.sect.ui.game.map.sect.SectCameraState(768f, 768f)
        val tiles = intArrayOf(1, 2, 3)
        val a = params(flatTileData = tiles, cameraState = cam)
        val b = params(flatTileData = tiles, cameraState = cam)
        assertEquals("同字段同引用参数应相等", a, b)
        assertEquals("同字段参数 hashCode 应相等", a.hashCode(), b.hashCode())
    }

    @Test
    fun `预览状态任一字段变化则不等`() {
        val base = MapPreviewState(
            isPlacingBuilding = false,
            placingBuildingName = "",
            placingWorldX = 0f,
            placingWorldY = 0f,
            placingBuildingSize = com.xianxia.sect.core.util.GridSnapHelper.BuildingSize(2, 3),
            placementValidity = com.xianxia.sect.core.util.GridSnapHelper.PlacementValidity.Valid,
            movingBuilding = null,
            movingWorldX = 0f,
            movingWorldY = 0f,
            movingBuildingSize = com.xianxia.sect.core.util.GridSnapHelper.BuildingSize(2, 3),
            movingValid = com.xianxia.sect.core.util.GridSnapHelper.PlacementValidity.Valid
        )
        assertEquals("同字段预览状态相等", base, base.copy())
        assertNotEquals(
            "移动中的建筑变化应触发不等（重组）",
            base,
            base.copy(movingBuilding = com.xianxia.sect.core.model.GridBuildingData(instanceId = "b1"))
        )
        assertNotEquals(
            "放置坐标变化应触发不等（重组）",
            base,
            base.copy(placingWorldX = 50f)
        )
        assertNotEquals(
            "放置模式切换应触发不等（重组）",
            base,
            base.copy(isPlacingBuilding = true)
        )
    }

    @Test
    fun `渲染参数关键字段变化则不等`() {
        val p = params()
        assertNotEquals("瓦片数据变化（新数组）应不等", p, params(intArrayOf(9, 9, 9)))
        assertNotEquals(
            "相机实例变化（引用不同）应不等",
            p,
            p.copy(cameraState = com.xianxia.sect.ui.game.map.sect.SectCameraState(768f, 768f))
        )
        assertNotEquals("建筑数量变化应不等", p, p.copy(buildingCount = 5))
        assertNotEquals("软件渲染切换应不等", p, p.copy(forceSoftwareRendering = true))
    }
}
