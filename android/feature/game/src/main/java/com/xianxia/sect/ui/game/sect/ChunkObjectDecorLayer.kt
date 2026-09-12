package com.xianxia.sect.ui.game.sect

import android.graphics.Rect
import kotlin.math.roundToInt

/**
 * chunk 烘焙期的**立体层装饰**收集缓冲（树）。
 *
 * 立体层装饰（`SpriteAtlasDef.isObjectDecorTile`）与建筑并入同一画家序绘制：
 * 按地面接触点（[bottomY]）升序归并，同键时建筑在后（建筑压住同接触点装饰）——
 * 契约与 C++ `gamecore/map/draw_order.h` 一致。若树留在地面层，北侧建筑会把
 * 树冠无脑压掉（树在前却消失）。
 *
 * 收集缓冲在 [SoftwareCanvasBackend.ChunkTile] 内按 chunk 复用（容量 = 本 chunk 格数
 * 加越界余量，防逐块堆分配）；[collect] 带越界防御（异常数据丢弃该格，不崩不越界写）。
 *
 * 独立顶层类而非 ChunkTile 内联：控制 `SoftwareCanvasBackend.kt` 文件长度（2000 行上限）。
 */
internal class ChunkObjectDecorLayer(capacity: Int) {

    /** 每项 float 数：[worldX, worldTop, w, h, tileIndex] */
    private val items = FloatArray(capacity * STRIDE)

    /** 已收集项数（收集序 = 行序 = 底边 Y 升序） */
    var count: Int = 0
        private set

    /** 清空（每次 chunk 重烘前调用） */
    fun clear() {
        count = 0
    }

    /** 收集一格立体层装饰（世界像素矩形 + 瓦片索引） */
    fun collect(worldLeft: Int, worldTop: Int, w: Int, h: Int, tile: Int) {
        val base = count * STRIDE
        if (base + STRIDE > items.size) return  // 缓冲用尽：丢弃该格（防御异常数据）
        items[base] = worldLeft.toFloat()
        items[base + 1] = worldTop.toFloat()
        items[base + 2] = w.toFloat()
        items[base + 3] = h.toFloat()
        items[base + 4] = tile.toFloat()
        count++
    }

    /** 归并序键：装饰底边 Y（世界像素）——与建筑底边同域比较 */
    fun bottomY(index: Int): Float = items[index * STRIDE + 1] + items[index * STRIDE + 3]

    /** 该项目的瓦片索引（查图集源矩形用） */
    fun tileAt(index: Int): Int = items[index * STRIDE + 4].toInt()

    /**
     * 世界坐标 → 目标像素矩形（chunk 位图坐标）。
     *
     * @param camX/camY chunk 左上角世界坐标（与建筑绘制同一 [SoftwareCanvasBackend.ViewTransform]）
     * @param scale 视角缩放（chunk 烘焙期恒为 1）
     * @param out 复用的目标矩形（不新建对象）
     */
    fun dstRect(index: Int, camX: Float, camY: Float, scale: Float, out: Rect) {
        val base = index * STRIDE
        out.set(
            ((items[base] - camX) * scale).roundToInt(),
            ((items[base + 1] - camY) * scale).roundToInt(),
            ((items[base] + items[base + 2] - camX) * scale).roundToInt(),
            ((items[base + 1] + items[base + 3] - camY) * scale).roundToInt()
        )
    }

    private companion object {
        const val STRIDE = 5
    }
}
