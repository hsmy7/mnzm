package com.xianxia.sect.ui.game.map

import android.graphics.Bitmap
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlin.math.ceil
import kotlin.math.min

class MapTileCache(
    private val fullBitmap: Bitmap,
    private val tileSize: Int = 512
) {
    private val tilesX = ceil(fullBitmap.width.toFloat() / tileSize).toInt()
    private val tilesY = ceil(fullBitmap.height.toFloat() / tileSize).toInt()

    private val cache = LruCache<Long, ImageBitmap>(36)

    fun getVisibleTiles(
        cameraX: Float, cameraY: Float,
        scale: Float,
        viewportWidth: Int, viewportHeight: Int
    ): List<TileInfo> {
        val startTileX = (cameraX / tileSize).toInt().coerceIn(0, tilesX - 1)
        val startTileY = (cameraY / tileSize).toInt().coerceIn(0, tilesY - 1)
        val endTileX = ((cameraX + viewportWidth / scale) / tileSize).toInt().coerceIn(0, tilesX - 1)
        val endTileY = ((cameraY + viewportHeight / scale) / tileSize).toInt().coerceIn(0, tilesY - 1)

        return (startTileX..endTileX).flatMap { tx ->
            (startTileY..endTileY).map { ty ->
                val key = (tx.toLong() shl 32) or ty.toLong()
                val bmp = cache.get(key) ?: createTile(tx, ty).also { cache.put(key, it) }
                TileInfo(worldX = tx * tileSize, worldY = ty * tileSize, bitmap = bmp)
            }
        }
    }

    private fun createTile(tileX: Int, tileY: Int): ImageBitmap {
        val x = tileX * tileSize
        val y = tileY * tileSize
        val w = minOf(tileSize, fullBitmap.width - x)
        val h = minOf(tileSize, fullBitmap.height - y)
        return Bitmap.createBitmap(fullBitmap, x, y, w, h).asImageBitmap()
    }

    fun recycle() {
        cache.evictAll()
        // fullBitmap 可能正被 Compose Canvas 渲染线程使用（作为 ImageBitmap
        // 的后备缓冲），显式回收会导致 libhwui.so SIGSEGV（use-after-free）。
        // 生命周期由 Compose 托管，不再手动回收。
    }

    data class TileInfo(val worldX: Int, val worldY: Int, val bitmap: ImageBitmap)
}
