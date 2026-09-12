package com.xianxia.sect.core.util

import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.render.SpriteAtlasDef

/**
 * 宗门入口固定结构（门楼）——"固定建筑"语义。
 *
 * 不走建筑列表/存档：不可移动、不可拆除、不可点击弹窗、不参与驻军/拆除模式；
 * 随瓦片生成时清除门楼精灵区域装饰（见 [SectMapTileGenerator.placeSectGateway]），
 * 新开存档即存在，每宗地图（主宗/占领宗）一致。门楼左右两侧保留 3 行边界硬装饰树。
 *
 * 渲染走**建筑层**：结构条目追加在建筑数据数组尾部（nameIdx = BUILDING_NAMES.size + index，
 * 图集 UV/占地见 [SpriteAtlasDef.STRUCTURES]），追加在尾部 ⇒ 恒最后绘制 ⇒ 强制置顶，
 * 不会被上方/两侧建筑遮挡。禁建范围 = 占地（门楼 6×2）；不画地砖/地基、不投影。
 */
object FixedSectGateway {

    /** 结构数量（与 [SpriteAtlasDef.STRUCTURES] 同序同量）。 */
    val count: Int get() = SpriteAtlasDef.STRUCTURES.size

    /** 固定结构在建筑层渲染条目 [gridX, gridY, spriteW, spriteH, nameIdx] × count。 */
    fun renderEntries(): FloatArray {
        val base = SpriteAtlasDef.BUILDING_NAMES.size
        val rect = intArrayOf(
            GameConfig.SectMap.GATE_X, GameConfig.SectMap.GATE_Y,
            GameConfig.SectMap.GATE_SPRITE_WIDTH, GameConfig.SectMap.GATE_SPRITE_HEIGHT
        )
        return FloatArray(5) { i ->
            when (i) {
                0 -> rect[0].toFloat()
                1 -> rect[1].toFloat()
                2 -> rect[2].toFloat()
                3 -> rect[3].toFloat()
                else -> base.toFloat()
            }
        }
    }

    /** 禁建格集合（占地范围：门楼 6×2，packed cell）。 */
    val blockedCells: Set<Long> by lazy {
        val cells = mutableSetOf<Long>()
        addRect(
            cells,
            GameConfig.SectMap.GATE_X, GameConfig.SectMap.GATE_Y,
            GameConfig.SectMap.GATE_WIDTH, GameConfig.SectMap.GATE_HEIGHT
        )
        cells
    }

    private fun addRect(cells: MutableSet<Long>, x: Int, y: Int, w: Int, h: Int) {
        for (cy in y until y + h) {
            for (cx in x until x + w) {
                cells.add(GridSystem.packCell(cx, cy))
            }
        }
    }
}
