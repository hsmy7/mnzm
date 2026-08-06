package com.xianxia.sect.core.config

import android.content.Context
import android.content.res.AssetManager
import com.xianxia.sect.core.model.GridBuildingData
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.io.IOException

/**
 * fixupBuildingSizes 越界钳制测试（D-14，2026-08-06）。
 *
 * 尺寸变化时把坐标钳回地图界内（默认世界 128×128）：
 * - 旧档 2×2 矿场位于边缘，撑大到 4×4 时钳回界内（gridX=126 → 124）
 * - 负坐标钳到 0
 * - 尺寸不变的健康数据零副作用（交溢出迁移处理）
 * - 未知显示名回退 2×2
 *
 * 独立文件：BuildingConfigService 实例化需要 mock Context（assets 加载失败 → fallback 配置），
 * 若与既有数据类测试同文件共享 @Before 会影响全类。
 */
class BuildingConfigServiceFixupTest {

    private fun newService(): BuildingConfigService {
        val context = mock<Context>()
        val assetManager = mock<AssetManager>()
        whenever(context.assets).thenReturn(assetManager)
        // assets 加载失败 → 走 createDefaultConfig fallback（灵矿场 4×4 等）
        whenever(assetManager.open(any())).thenThrow(IOException("no assets in unit test"))
        return BuildingConfigService(context)
    }

    @Test
    fun `fixupBuildingSizes_sizeExpandAtEdge_clampCoordinatesIntoBounds`() {
        // 旧档 2×2 矿场位于 gridX=126（×2 时代前数据），当前配置 4×4 → 撑大后越界，钳回 124
        val service = newService()
        val buildings = listOf(
            GridBuildingData(displayName = "灵矿场", gridX = 126, gridY = 100, width = 2, height = 2)
        )
        val fixed = service.fixupBuildingSizes(buildings)
        assertEquals(4, fixed[0].width)
        assertEquals(4, fixed[0].height)
        assertEquals(124, fixed[0].gridX)
        assertEquals(100, fixed[0].gridY)
    }

    @Test
    fun `fixupBuildingSizes_sizeExpandAtBottomEdge_clampYIntoBounds`() {
        val service = newService()
        val buildings = listOf(
            GridBuildingData(displayName = "灵矿场", gridX = 100, gridY = 126, width = 2, height = 2)
        )
        val fixed = service.fixupBuildingSizes(buildings)
        assertEquals(124, fixed[0].gridY)
        assertEquals(100, fixed[0].gridX)
    }

    @Test
    fun `fixupBuildingSizes_negativeClampResult_clampToZero`() {
        val service = newService()
        val buildings = listOf(
            GridBuildingData(displayName = "灵矿场", gridX = -2, gridY = -5, width = 2, height = 2)
        )
        val fixed = service.fixupBuildingSizes(buildings)
        assertEquals(4, fixed[0].width)
        assertEquals(0, fixed[0].gridX)
        assertEquals(0, fixed[0].gridY)
    }

    @Test
    fun `fixupBuildingSizes_unchangedSize_noCoordinateChange`() {
        // 健康数据（尺寸已匹配）零副作用——即使坐标接近边缘也不动（交溢出迁移处理）
        val service = newService()
        val buildings = listOf(
            GridBuildingData(displayName = "灵矿场", gridX = 124, gridY = 124, width = 4, height = 4)
        )
        val fixed = service.fixupBuildingSizes(buildings)
        assertEquals(124, fixed[0].gridX)
        assertEquals(124, fixed[0].gridY)
    }

    @Test
    fun `fixupBuildingSizes_unknownDisplayName_defaultSizeNoClamp`() {
        // 未知显示名 → 回退 2×2 默认（既有语义不变）
        val service = newService()
        val buildings = listOf(
            GridBuildingData(displayName = "未知建筑", gridX = 126, gridY = 100, width = 6, height = 4)
        )
        val fixed = service.fixupBuildingSizes(buildings)
        assertEquals(2, fixed[0].width)
        assertEquals(2, fixed[0].height)
        assertEquals(126, fixed[0].gridX)
        assertEquals(100, fixed[0].gridY)
    }
}
