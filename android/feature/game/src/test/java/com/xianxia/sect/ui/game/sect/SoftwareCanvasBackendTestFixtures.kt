package com.xianxia.sect.ui.game.sect

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.xianxia.sect.core.render.NativeRenderConfig
import com.xianxia.sect.core.render.RenderFlags
import com.xianxia.sect.core.render.RenderFrame
import com.xianxia.sect.core.render.SpriteAtlasDef

/**
 * SoftwareCanvasBackend 测试共享 fixtures。
 *
 * WP6 detekt 拆分：从 SoftwareCanvasBackendTest 提取（原文件 1282 行超
 * LargeClass 阈值 800）——全部为 top-level internal，同包测试类直接引用。
 */
internal fun testRenderConfig(renderFlags: RenderFlags = RenderFlags()): NativeRenderConfig {
    return NativeRenderConfig(
        tileSize = 64,
        worldWidthCells = 10,
        worldHeightCells = 10,
        worldPixelWidth = 640,
        worldPixelHeight = 640,
        renderFlags = renderFlags
    )
}

/** 创建纯地面瓦片数据（所有格为 TILE_GROUND） */
internal fun createFlatTileData(cols: Int, rows: Int): IntArray {
    return IntArray(cols * rows) { SpriteAtlasDef.TileType.GROUND.index }
}

/** 创建单个建筑数据 FloatArray */
internal fun createBuildingDataArray(
    gridX: Int, gridY: Int, width: Int, height: Int, nameIdx: Int,
    gridX2: Int = 0, gridY2: Int = 0, width2: Int = 0, height2: Int = 0, nameIdx2: Int = 0
): FloatArray {
    return if (gridX2 == 0 && gridY2 == 0 && width2 == 0) {
        floatArrayOf(
            gridX.toFloat(), gridY.toFloat(),
            width.toFloat(), height.toFloat(), nameIdx.toFloat()
        )
    } else {
        floatArrayOf(
            gridX.toFloat(), gridY.toFloat(),
            width.toFloat(), height.toFloat(), nameIdx.toFloat(),
            gridX2.toFloat(), gridY2.toFloat(),
            width2.toFloat(), height2.toFloat(), nameIdx2.toFloat()
        )
    }
}

/** 迷你图集：左上角 (0,0)-(64,64) 填充白色，作为预览/地面源 */
internal fun createWhiteTileAtlas(): Bitmap {
    val bmp = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    val p = Paint().apply { color = Color.WHITE }
    c.drawRect(0f, 0f, 64f, 64f, p)
    return bmp
}

internal fun assertNear(expected: Int, actual: Int, tolerance: Int = 2) {
    org.junit.Assert.assertTrue(
        "expected $expected ±$tolerance, got $actual",
        kotlin.math.abs(expected - actual) <= tolerance
    )
}

/**
 * 灵田测试帧：nameIdx=2 占地 1×1=64px。
 * - 精灵覆盖 (0,0)-(64,64)
 * - 阴影 (16,16)-(80,80)（右下偏移 0.25 格）
 * - 阴影右/下条带 (64,16)-(80,80) 位于精灵之外——直接落在 chunk 米色底上，
 *   半透明黑混合后可观测（米色 0xF2EDE4 × 0.8 ≈ (194,190,182)）
 */
internal fun spiritFieldFrame(td: IntArray, selectedIndex: Int = -1, scale: Float = 1f): RenderFrame {
    return RenderFrame(
        camX = 0f, camY = 0f, scale = scale,
        tileData = td,
        cols = 10, rows = 10,
        buildingData = createBuildingDataArray(
            gridX = 0, gridY = 0, width = 1, height = 1, nameIdx = 2
        ),
        buildingCount = 1,
        buildingVisible = true,
        selectedBuildingIndex = selectedIndex
    )
}

/** 创建首格为装饰草（tile=1）、其余为地面的瓦片数据 */
internal fun createDecorTileData(cols: Int, rows: Int): IntArray {
    return IntArray(cols * rows) { if (it == 0) 1 else 0 }
}

/**
 * 精灵测试图集：1024×1024，含与 SpriteAtlasDef 一致的真实源矩形——
 * GROUND 源 (0,0,64,64)=灰 100（chunk 底），灵田建筑精灵源
 * (256,128,384,256)=白 255。解决迷你图集（128×128）源矩形越界导致
 * 建筑精灵不绘制的盲区——阴影污染回归测试必须让精灵真实上屏。
 */
internal fun createSpriteAtlas(): Bitmap {
    val bmp = Bitmap.createBitmap(1024, 1024, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    c.drawRect(0f, 0f, 64f, 64f, Paint().apply { color = Color.rgb(100, 100, 100) })
    c.drawRect(256f, 128f, 384f, 256f, Paint().apply { color = Color.WHITE })
    return bmp
}

/**
 * 作物测试图集：tile 源 (0,0,64,64)=灰 100（chunk 底），作物三阶段源
 * (832/896/960, 0, 64, 64)=白 255（与 SpriteAtlasDef.CropStage rect 同坐标）。
 * 灵田建筑精灵源 (256,128) 在 64 高图集范围外 → 建筑不可见，不影响断言。
 */
internal fun createCropAtlas(): Bitmap {
    val bmp = Bitmap.createBitmap(1024, 64, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    c.drawRect(0f, 0f, 64f, 64f, Paint().apply { color = Color.rgb(100, 100, 100) })
    c.drawRect(832f, 0f, 896f, 64f, Paint().apply { color = Color.WHITE })
    c.drawRect(896f, 0f, 960f, 64f, Paint().apply { color = Color.WHITE })
    c.drawRect(960f, 0f, 1024f, 64f, Paint().apply { color = Color.WHITE })
    return bmp
}

/** 作物帧：灵田 (0,0) + 作物数据 */
internal fun cropFrame(td: IntArray, cropData: FloatArray?): RenderFrame {
    return spiritFieldFrame(td).copy(spiritCropData = cropData)
}

/** 拆除高亮帧：spiritFieldFrame + 拆除标记数组（null = 非拆除模式） */
internal fun demolishFrame(td: IntArray, markers: ByteArray?, selectedIndex: Int = -1): RenderFrame {
    return spiritFieldFrame(td, selectedIndex = selectedIndex).copy(demolishHighlightData = markers)
}

/** 双建筑帧：(0,0) 与 (2,0) 各 1×1 占地、nameIdx=2（与 spiritFieldFrame 同几何） */
internal fun twoBuildingFrame(td: IntArray, markers: ByteArray? = null): RenderFrame {
    return RenderFrame(
        camX = 0f, camY = 0f, scale = 1f,
        tileData = td,
        cols = 10, rows = 10,
        buildingData = createBuildingDataArray(
            gridX = 0, gridY = 0, width = 1, height = 1, nameIdx = 2,
            gridX2 = 2, gridY2 = 0, width2 = 1, height2 = 1, nameIdx2 = 2
        ),
        buildingCount = 2,
        buildingVisible = true,
        demolishHighlightData = markers
    )
}

/**
 * 作物采样点 (8,8)：作物 (0,0)-(64,64) 内、灵田建筑阴影矩形 (16,16)-(80,80) 外
 * ——排除 WP3 阴影干扰，纯测作物层
 */
internal const val CROP_SAMPLE = 8
