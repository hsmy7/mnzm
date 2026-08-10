package com.xianxia.sect.core.render

/**
 * 建筑渲染共享几何 — 双后端（Vulkan C++ / Canvas Kotlin）同一数学来源。
 *
 * C++ 侧 `NativeBridge.cpp` 的对应实现（offsetX/offsetY 底部对齐公式、
 * 阴影偏移）必须与本文件逐项一致——修改任一侧必须同步另一侧
 * （双端一致性由 SoftwareCanvasBackendTest 像素断言 + 代码审查覆盖）。
 *
 * ## 已知对齐项
 * - [spriteOffset] ↔ C++ `offsetX = (fpW - sw) * tileSize * 0.5f; offsetY = (fpH - sh) * tileSize`
 * - [shadowRect] ↔ C++ `shx = ftPx + tileSize * 0.25f`（SHADOW_OFFSET_TILES）
 */
object BuildingRenderGeometry {

    /** 阴影偏移量（格数）：阴影相对占地右下偏移 0.25 格 */
    const val SHADOW_OFFSET_TILES = 0.25f

    /** 阴影不透明度（0-1）：半透明黑（C++ SHADOW_ALPHA 同值） */
    const val SHADOW_ALPHA = 0.2f

    /**
     * 建筑精灵底部对齐偏移（世界像素）。
     *
     * @param fpW 占地宽度（格数）
     * @param fpH 占地高度（格数）
     * @param spriteW 精灵宽度（格数比例，可能大于占地）
     * @param spriteH 精灵高度（格数比例）
     * @param tileSize 瓦片像素尺寸
     * @return Pair(offsetX, offsetY)：offsetX 水平居中，offsetY 底部对齐
     */
    fun spriteOffset(fpW: Int, fpH: Int, spriteW: Float, spriteH: Float, tileSize: Int): Pair<Float, Float> {
        val offsetX = (fpW - spriteW) * tileSize * 0.5f
        val offsetY = (fpH - spriteH) * tileSize
        return offsetX to offsetY
    }

    /**
     * 建筑阴影矩形（世界像素，右下偏移 [SHADOW_OFFSET_TILES] 格）。
     *
     * @param gx 建筑格 X
     * @param gy 建筑格 Y
     * @param fpW 占地宽度（格数）
     * @param fpH 占地高度（格数）
     * @param tileSize 瓦片像素尺寸
     * @return [x, y, w, h] 世界像素矩形
     */
    fun shadowRect(gx: Int, gy: Int, fpW: Int, fpH: Int, tileSize: Int): FloatArray {
        val offset = tileSize * SHADOW_OFFSET_TILES
        return floatArrayOf(
            gx * tileSize + offset,
            gy * tileSize + offset,
            fpW * tileSize.toFloat(),
            fpH * tileSize.toFloat()
        )
    }

    /**
     * 查找包含指定格坐标的建筑索引（纯函数，双端共享的命中判定）。
     *
     * 以**占地尺寸**（footprint）判定命中（精灵可能超出占地，超出的
     * 透明像素不应算命中）。建筑数据格式 [gx, gy, sw, sh, nameIdx] × N。
     *
     * @param gx 选中格 X
     * @param gy 选中格 Y
     * @param buildingData 建筑数据数组（可为 null）
     * @param count 建筑数量（防御：coerce 到数组可容纳的最大值）
     * @param footprintOf nameIdx → 占地尺寸 (w, h)，默认 [SpriteAtlasDef.FOOTPRINT_BY_NAME_INDEX]
     * @return 命中的建筑索引；-1 = 未命中（无建筑/越界/数组空）
     */
    fun findBuildingIndex(
        gx: Int,
        gy: Int,
        buildingData: FloatArray?,
        count: Int,
        footprintOf: (Int) -> Pair<Int, Int> = { nameIdx ->
            SpriteAtlasDef.FOOTPRINT_BY_NAME_INDEX.getOrElse(nameIdx) { 2 to 2 }
        }
    ): Int {
        var hit = -1
        if (buildingData != null && count > 0) {
            val n = count.coerceAtMost(buildingData.size / 5)
            for (i in 0 until n) {
                val idx = i * 5
                val bgx = buildingData[idx].toInt()
                val bgy = buildingData[idx + 1].toInt()
                val nameIdx = buildingData[idx + 4].toInt()
                val (fpW, fpH) = footprintOf(nameIdx)
                if (gx in bgx until bgx + fpW && gy in bgy until bgy + fpH) {
                    hit = i
                    break
                }
            }
        }
        return hit
    }
}
