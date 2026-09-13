package com.xianxia.sect.ui.game.sect

import com.xianxia.sect.core.render.IslandCliffBridge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 崖壁独立纹理守卫（地图边缘系统）。
 *
 * 锁住三方一致性——任一处漂移都会导致「运行时静默不生效」，故必须自动拦截：
 *
 * 1. **尺寸表 ↔ 真实 WebP**：`IslandCliffTextureSet.TEXTURE_SIZES` 是编译期常量
 *    （布局合成需要它，且必须在纹理上传前可用），必须与磁盘上已烘焙的 WebP 逐张
 *    一致。换素材忘记改常量 → 布局按旧尺寸铺装、纹理按新尺寸上传 → 接缝错位。
 * 2. **4 的倍数**：ASTC 4×4 的硬性尺寸要求（`bake.roundUp4` 保证）。非 4 倍数会被
 *    `KtxLoader` 拒绝 → Vulkan 路径整张回落 RGBA。
 * 3. **KTX ↔ WebP 逐张同尺寸**：`build-edge-ktx.mjs` 从 WebP 生成 KTX；两者尺寸
 *    不一致说明某一路径用了过期素材（KTX 是生成物，必须与源同尺寸）。
 * 4. **清单长度**：尺寸/drawable/KTX 资产三张表长度一致且等于纹理总数。
 *
 * 读取方式：测试工作目录 = **本模块目录**（`android/feature/game`），故
 * 本模块资源用 `src/main/...`、app 模块产物用 `../app/src/main/...`。
 */
class EdgeKtxSyncTest {

    /** 本模块崖壁 WebP（相对本模块根） */
    private val gameDrawableDir = File("src/main/res/drawable-nodpi")

    /** 崖壁 KTX 产物位于 **app 模块** assets（KTX 只 app 侧消费；注意本模块根为
     *  `android/feature/game`，回到 `android/` 需上溯两级） */
    private val edgeKtxDir = File("../../app/src/main/assets/atlas/edge")

    @Test
    fun `纹理尺寸表与真实 WebP 逐张一致且为 4 的倍数`() {
        assertTrue("drawable 目录不存在: ${gameDrawableDir.absolutePath}", gameDrawableDir.exists())
        assertEquals(
            "纹理尺寸表长度应为 ${IslandCliffBridge.TextureIndex.COUNT} 张 × (w,h)",
            IslandCliffBridge.TextureIndex.COUNT * 2,
            IslandCliffTextureSet.TEXTURE_SIZES.size
        )

        val names = listOf(
            "map_edge_left_1", "map_edge_left_2", "map_edge_left_3",
            "map_edge_bottom_1", "map_edge_bottom_2",
            "map_edge_corner_bl", "map_edge_corner_br"
        )
        assertEquals("drawable 清单长度与纹理总数不符", IslandCliffBridge.TextureIndex.COUNT, names.size)

        for ((index, name) in names.withIndex()) {
            val file = File(gameDrawableDir, "$name.webp")
            assertTrue("崖壁素材缺失: ${file.absolutePath}——请运行 node scripts/import-art-assets.mjs", file.exists())
            val (w, h) = readWebpSize(file)
            val expectedW = IslandCliffTextureSet.widthOf(index).toInt()
            val expectedH = IslandCliffTextureSet.heightOf(index).toInt()
            assertEquals(
                "$name 宽与 IslandCliffTextureSet.TEXTURE_SIZES[$index] 不符（实测 $w，常量 $expectedW）" +
                    "——换素材后必须同步尺寸表",
                expectedW, w
            )
            assertEquals("$name 高与尺寸表不符（实测 $h，常量 $expectedH）", expectedH, h)
            assertEquals("$name 宽 $w 非 4 的倍数（ASTC 4×4 要求）", 0, w % 4)
            assertEquals("$name 高 $h 非 4 的倍数（ASTC 4×4 要求）", 0, h % 4)
        }
    }

    @Test
    fun `KTX 产物与 WebP 逐张同尺寸`() {
        assertTrue("KTX 目录不存在: ${edgeKtxDir.absolutePath}", edgeKtxDir.exists())
        val names = listOf(
            "map_edge_left_1", "map_edge_left_2", "map_edge_left_3",
            "map_edge_bottom_1", "map_edge_bottom_2",
            "map_edge_corner_bl", "map_edge_corner_br"
        )
        for (name in names) {
            val webp = File(gameDrawableDir, "$name.webp")
            val ktx = File(edgeKtxDir, "$name.ktx")
            assertTrue("KTX 产物缺失: ${ktx.absolutePath}——请运行 node scripts/build-edge-ktx.mjs", ktx.exists())
            val (kw, kh) = readKtxSize(ktx)
            val (ww, wh) = readWebpSize(webp)
            assertEquals("$name KTX 宽 $kw 与 WebP 宽 $ww 不一致（KTX 为生成物，须同尺寸）", ww, kw)
            assertEquals("$name KTX 高 $kh 与 WebP 高 $wh 不一致", wh, kh)
        }
    }

    @Test
    fun `drawable 与 KTX 资产清单长度与纹理总数一致`() {
        assertEquals(
            "TEXTURE_DRAWABLES 长度应等于纹理总数",
            IslandCliffBridge.TextureIndex.COUNT,
            IslandCliffTextureSet.TEXTURE_DRAWABLES.size
        )
        assertEquals(
            "TEXTURE_KTX_ASSETS 长度应等于纹理总数",
            IslandCliffBridge.TextureIndex.COUNT,
            IslandCliffTextureSet.TEXTURE_KTX_ASSETS.size
        )
    }

    /** 读 WebP 尺寸（RIFF 容器：VP8X / VP8L / VP8 三种块布局） */
    private fun readWebpSize(file: File): Pair<Int, Int> {
        val b = file.readBytes()
        require(b.size > 30) { "${file.name} 过短，非合法 WebP" }
        val riff = String(b, 0, 4, Charsets.US_ASCII)
        val webp = String(b, 8, 4, Charsets.US_ASCII)
        require(riff == "RIFF" && webp == "WEBP") { "${file.name} 非 WebP（magic=$riff/$webp）" }
        // 第一个 chunk 位于 offset 12
        val fourCC = String(b, 12, 4, Charsets.US_ASCII)
        return when (fourCC) {
            // VP8X：扩展格式，画布尺寸在 24..29（24-bit LE，值 = 宽-1 / 高-1）
            "VP8X" -> {
                val w = (b[24].toInt() and 0xFF) or ((b[25].toInt() and 0xFF) shl 8) or
                    ((b[26].toInt() and 0xFF) shl 16)
                val h = (b[27].toInt() and 0xFF) or ((b[28].toInt() and 0xFF) shl 8) or
                    ((b[29].toInt() and 0xFF) shl 16)
                (w + 1) to (h + 1)
            }
            // VP8L：无损，14-bit 宽高打包在 offset 21..24
            "VP8L" -> {
                val bits = (b[21].toInt() and 0xFF) or ((b[22].toInt() and 0xFF) shl 8) or
                    ((b[23].toInt() and 0xFF) shl 16) or ((b[24].toInt() and 0xFF) shl 24)
                (((bits and 0x3FFF) + 1)) to ((((bits shr 14) and 0x3FFF) + 1))
            }
            // VP8：有损，尺寸在 offset 26..29（16-bit LE，取低 14 位）
            "VP8 " -> {
                val w = ((b[26].toInt() and 0xFF) or ((b[27].toInt() and 0xFF) shl 8)) and 0x3FFF
                val h = ((b[28].toInt() and 0xFF) or ((b[29].toInt() and 0xFF) shl 8)) and 0x3FFF
                w to h
            }
            else -> throw AssertionError("${file.name} 未知 WebP 块: $fourCC")
        }
    }

    /** 读 KTX1 头的 mip0 尺寸（pixelWidth @32 / pixelHeight @36，小端） */
    private fun readKtxSize(file: File): Pair<Int, Int> {
        val b = file.readBytes()
        require(b.size > 64) { "${file.name} 过短，非合法 KTX1" }
        val magic = String(b, 1, 3, Charsets.US_ASCII)
        require(magic == "KTX") { "${file.name} 非 KTX1（magic=$magic）" }
        val w = (b[32].toInt() and 0xFF) or ((b[33].toInt() and 0xFF) shl 8) or
            ((b[34].toInt() and 0xFF) shl 16) or ((b[35].toInt() and 0xFF) shl 24)
        val h = (b[36].toInt() and 0xFF) or ((b[37].toInt() and 0xFF) shl 8) or
            ((b[38].toInt() and 0xFF) shl 16) or ((b[39].toInt() and 0xFF) shl 24)
        return w to h
    }
}
