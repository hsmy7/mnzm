package com.xianxia.sect.ui.game.sect

import com.xianxia.sect.core.render.SpriteAtlasDef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 瓦片/装饰精灵图守卫测试。
 *
 * 锁住不变量：每个 [SpriteAtlasDef.TileType]（建筑占位除外）在运行时图集组装器
 * [SectAtlasAssembler] 中都必须有非零 drawable——新增瓦片类型（草/石/树变体）时
 * 只加 LAYOUT.tiles 而漏配 drawable，槽位会静默为空（装饰不显示且无运行时错误），
 * 本测试直接拦截并指出缺哪一项。
 */
class SectAtlasTileDrawableGuardTest {

    @Test
    fun `每个瓦片类型都能映射到非零 drawable`() {
        for (tile in SpriteAtlasDef.TileType.values()) {
            val resId = SectAtlasAssembler.tileDrawableRes(tile)
            if (tile == SpriteAtlasDef.TileType.TILE_BUILDING) {
                assertEquals(
                    "建筑占位瓦片不应有精灵（与 GROUND 同 rect 的占位语义）",
                    0, resId
                )
            } else {
                assertTrue(
                    "瓦片 ${tile.name} 无 drawable——新增瓦片必须同步 LAYOUT.tiles、" +
                        "TILE_DRAWABLE（build-atlas.mjs）与 SectAtlasAssembler 映射",
                    resId != 0
                )
            }
        }
    }

    @Test
    fun `全部装饰瓦片都有 drawable（草石树渲染叠加层）`() {
        val decorIndices = SpriteAtlasDef.DECOR_TILE_MIN_INDEX..SpriteAtlasDef.DECOR_TILE_MAX_INDEX
        for (index in decorIndices) {
            val tile = SpriteAtlasDef.TileType.fromIndex(index)
            assertTrue(
                "装饰瓦片 index=$index (${tile.name}) 无 drawable——装饰叠加层将绘制空白",
                SectAtlasAssembler.tileDrawableRes(tile) != 0
            )
        }
    }
}
