package com.xianxia.sect.ui.components

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

/**
 * 精灵图图集合并工具。
 *
 * 将多个小尺寸 [ImageBitmap] 打包到一张大图上，减少 GPU 纹理切换次数。
 * 使用 shelf packing 算法（按面积降序排列，逐行放置）。
 *
 * 用法：
 * ```kotlin
 * val packer = AtlasPacker()
 * val result = packer.pack(itemSprites)
 * if (result != null) {
 *     canvas.drawImageRect(result.atlas, ...)
 * }
 * ```
 */

/**
 * 图集中一个精灵的区域信息。
 *
 * @param resId 精灵资源 ID
 * @param u 归一化 UV 左坐标 (0~1)
 * @param v 归一化 UV 顶坐标 (0~1)
 * @param u2 归一化 UV 右坐标 (0~1)
 * @param v2 归一化 UV 底坐标 (0~1)
 * @param x 图集中像素左坐标
 * @param y 图集中像素顶坐标
 * @param w 精灵像素宽度
 * @param h 精灵像素高度
 */
data class AtlasRegion(
    val resId: Int,
    val u: Float, val v: Float,
    val u2: Float, val v2: Float,
    val x: Int, val y: Int,
    val w: Int, val h: Int
)

/**
 * 图集打包结果。
 *
 * @param atlas 合并后的大图 [ImageBitmap]
 * @param regions 精灵 resId 到区域信息的映射
 */
data class AtlasResult(
    val atlas: ImageBitmap,
    val regions: Map<Int, AtlasRegion>
)

/**
 * 精灵图图集打包器。
 *
 * 非线程安全，每次 [pack] 创建新图集。同步方法，需在后台线程调用。
 */
class AtlasPacker {

    /**
     * 将多个精灵打包到一张图集位图中。
     *
     * @param bitmaps 精灵 resId → [ImageBitmap] 映射
     * @param atlasSize 图集宽高（正方形），默认 1024
     * @return 打包结果，若任一精灵超过图集尺寸或图集已满则返回 null
     */
    fun pack(
        bitmaps: Map<Int, ImageBitmap>,
        atlasSize: Int = 1024
    ): AtlasResult? {
        if (bitmaps.isEmpty()) return null

        // 过滤超尺寸精灵（不可能放入图集的）
        val entries = bitmaps.entries
            .filter { it.value.width <= atlasSize && it.value.height <= atlasSize }
            .sortedByDescending { it.value.width * it.value.height }

        if (entries.isEmpty()) return null

        val atlasBmp = Bitmap.createBitmap(
            atlasSize, atlasSize, Bitmap.Config.ARGB_8888
        )
        val canvas = Canvas(atlasBmp)
        val regions = mutableMapOf<Int, AtlasRegion>()

        // Shelf 数据结构：记录每一行已占用的 x 位置
        data class Shelf(val y: Int, val height: Int, var x: Int)
        val shelves = mutableListOf<Shelf>()

        for ((resId, imageBmp) in entries) {
            val srcBmp = imageBmp.toAndroidBitmapCompat()
            val w = imageBmp.width
            val h = imageBmp.height

            var placed = false

            // 尝试放入已有行
            for (shelf in shelves) {
                if (shelf.height >= h && shelf.x + w <= atlasSize) {
                    val px = shelf.x
                    val py = shelf.y
                    canvas.drawBitmap(srcBmp, px.toFloat(), py.toFloat(), null)
                    regions[resId] = makeRegion(resId, px, py, w, h, atlasSize)
                    shelf.x += w
                    placed = true
                    break
                }
            }

            if (placed) continue

            // 新建一行
            val newY = if (shelves.isEmpty()) 0
            else shelves.last().y + shelves.last().height

            if (newY + h > atlasSize) return null // 图集已满

            canvas.drawBitmap(srcBmp, 0f, newY.toFloat(), null)
            regions[resId] = makeRegion(resId, 0, newY, w, h, atlasSize)
            shelves.add(Shelf(y = newY, height = h, x = w))
        }

        return AtlasResult(
            atlas = atlasBmp.asImageBitmap(),
            regions = regions
        )
    }

    private fun makeRegion(
        resId: Int, px: Int, py: Int, w: Int, h: Int, atlasSize: Int
    ): AtlasRegion = AtlasRegion(
        resId = resId,
        u = px.toFloat() / atlasSize,
        v = py.toFloat() / atlasSize,
        u2 = (px + w).toFloat() / atlasSize,
        v2 = (py + h).toFloat() / atlasSize,
        x = px, y = py,
        w = w, h = h
    )

    /**
     * 将 Compose [ImageBitmap] 转为 Android [Bitmap]。
     *
     * 使用 [readPixels] 读取像素数据后创建新的 Android Bitmap，
     * 兼容所有 API 级别（不依赖 [asAndroidBitmap] 的 API 29+ 限制）。
     */
    private fun ImageBitmap.toAndroidBitmapCompat(): Bitmap {
        val pixels = IntArray(this.width * this.height)
        this.readPixels(pixels, 0, 0, this.width, this.height)
        return Bitmap.createBitmap(
            pixels, this.width, this.height, Bitmap.Config.ARGB_8888
        )
    }
}
