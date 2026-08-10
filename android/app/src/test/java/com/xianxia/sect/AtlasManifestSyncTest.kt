package com.xianxia.sect

import com.xianxia.sect.core.render.SpriteAtlasDef
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/**
 * WP7 图集 ASTC 产物守卫测试（2026-08-10）。
 *
 * `scripts/build-atlas.mjs` 从 SpriteAtlasDef.kt 解析布局 → sharp 拼装 → astcenc
 * 压缩 → KTX1 封装，产物提交入库（assets/atlas/）。本测试锁住三向一致性：
 *
 * 1. **manifest ↔ SpriteAtlasDef**：manifest.sprites 与 Kotlin 复现清单逐项一致
 *    （顺序 = tile → building → floor → crop，与生成脚本 buildSpriteList 同式）
 * 2. **布局哈希**：Kotlin 重算 canonical 拼接的 sha256 前 16 位 == manifest.layoutHash
 *    ——权威源布局变更而产物未重新生成时立即变红
 * 3. **KTX 容器**：magic/dataSize/尺寸字段与几何推导一致（防手工破坏/截断）
 *
 * 权威源（SpriteAtlasDef / TextureAtlas.h / BuildingFeatureBoot.kt）变更后本测试
 * 变红，提示运行 `./gradlew generateAstcAtlas` 重新生成产物。
 */
class AtlasManifestSyncTest {

    private data class SpriteEntry(val name: String, val x: Int, val y: Int, val w: Int, val h: Int)

    private val manifestFile = File("src/main/assets/atlas/atlas-manifest.json")
    private val ktxFile = File("src/main/assets/atlas/atlas_astc.ktx")

    // ============================================================
    // manifest ↔ SpriteAtlasDef 双向一致
    // ============================================================

    @Test
    fun `manifest sprites match SpriteAtlasDef reproduction`() {
        val manifest = parseManifest()
        val expected = reproduceSpriteEntries()

        assertEquals(
            "manifest spriteCount 与权威源不一致——运行 ./gradlew generateAstcAtlas 重新生成",
            expected.size,
            manifest.sprites.size
        )
        for (i in expected.indices) {
            val exp = expected[i]
            val act = manifest.sprites[i]
            assertEquals("精灵[$i] 名称不一致: 期望 ${exp.name}", exp.name, act.name)
            assertEquals("精灵[${exp.name}] x 不一致", exp.x, act.x)
            assertEquals("精灵[${exp.name}] y 不一致", exp.y, act.y)
            assertEquals("精灵[${exp.name}] w 不一致", exp.w, act.w)
            assertEquals("精灵[${exp.name}] h 不一致", exp.h, act.h)
        }
    }

    @Test
    fun `layoutHash matches SpriteAtlasDef reproduction`() {
        val manifest = parseManifest()
        val recomputed = layoutHashOf(reproduceSpriteEntries())

        assertEquals(
            "layoutHash 不一致——权威源布局变更后未重新生成产物，" +
                "运行 ./gradlew generateAstcAtlas 重新生成 atlas_astc.ktx + atlas-manifest.json",
            manifest.layoutHash,
            recomputed
        )
    }

    @Test
    fun `manifest metadata matches atlas constants`() {
        val manifest = parseManifest()
        assertEquals(SpriteAtlasDef.ATLAS_W, manifest.width)
        assertEquals(SpriteAtlasDef.ATLAS_H, manifest.height)
        assertEquals("ASTC_4x4_LDR", manifest.format)
        assertEquals(manifest.sprites.size, manifest.spriteCount)
    }

    // ============================================================
    // KTX 容器校验（几何推导：64 头 + 4 dataSize + 块数×16）
    // ============================================================

    @Test
    fun `ktx file has valid header and expected size`() {
        assertTrue(
            "atlas_astc.ktx 不存在（运行 ./gradlew generateAstcAtlas 生成）",
            ktxFile.exists()
        )
        val bytes = ktxFile.readBytes()

        // magic "«KTX 11»"（AB 4B 54 58 20 31 31 BB）
        val magic = byteArrayOf(
            0xAB.toByte(), 0x4B, 0x54, 0x58, 0x20, 0x31, 0x31, 0xBB.toByte()
        )
        assertTrue(
            "KTX magic 校验失败",
            bytes.copyOfRange(KTX_MAGIC_OFFSET, KTX_MAGIC_OFFSET + magic.size).contentEquals(magic)
        )

        // 宽高字段（2048/2048）与 dataSize（512×512 块 × 16 字节）几何推导一致
        val blocksPerRow = SpriteAtlasDef.ATLAS_W / ASTC_BLOCK
        val blocksPerCol = SpriteAtlasDef.ATLAS_H / ASTC_BLOCK
        val expectedDataSize = (blocksPerRow * blocksPerCol).toLong() * ASTC_BLOCK_BYTES
        assertEquals(2048L, readU32LE(bytes, KTX_WIDTH_OFFSET))
        assertEquals(2048L, readU32LE(bytes, KTX_HEIGHT_OFFSET))
        assertEquals(expectedDataSize, readU32LE(bytes, KTX_DATA_SIZE_OFFSET))

        // 总尺寸 = 64 头 + 4 dataSize 字段 + 数据段（防多余/缺失字节）
        assertEquals(KTX_HEADER_SIZE + 4 + expectedDataSize, bytes.size.toLong())
    }

    // ============================================================
    // 工具
    // ============================================================

    /**
     * 从 SpriteAtlasDef 复现生成脚本的精灵清单（tile → building → floor → crop 顺序，
     * 与 build-atlas.mjs buildSpriteList 完全同式——任一侧改序本测试立即变红）。
     */
    private fun reproduceSpriteEntries(): List<SpriteEntry> {
        val list = mutableListOf<SpriteEntry>()
        for (tile in SpriteAtlasDef.TileType.values()) {
            val r = tile.rect
            list += SpriteEntry(tile.name, r.x, r.y, r.w, r.h)
        }
        for (i in SpriteAtlasDef.BUILDING_NAMES.indices) {
            val r = SpriteAtlasDef.buildingRect(i)
            list += SpriteEntry(SpriteAtlasDef.BUILDING_NAMES[i], r.x, r.y, r.w, r.h)
        }
        for (ft in SpriteAtlasDef.FloorTileType.values()) {
            val r = ft.pixelRect
            list += SpriteEntry(ft.name, r.x, r.y, r.w, r.h)
        }
        for (crop in SpriteAtlasDef.CropStage.values()) {
            val r = crop.rect
            list += SpriteEntry(crop.name, r.x, r.y, r.w, r.h)
        }
        return list
    }

    /** 布局哈希复现：canonical 拼接的 sha256 前 16 位（与 build-atlas.mjs layoutHashOf 同式） */
    private fun layoutHashOf(entries: List<SpriteEntry>): String {
        val canonical = entries.joinToString("|") { "${it.name}:${it.x},${it.y},${it.w},${it.h}" }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }.take(LAYOUT_HASH_LENGTH)
    }

    /** 小端读取 uint32（KTX1 规范强制小端，头已由 C++ KtxLoader 校验 endianness） */
    private fun readU32LE(bytes: ByteArray, offset: Int): Long =
        (bytes[offset].toLong() and 0xFF) or
            ((bytes[offset + 1].toLong() and 0xFF) shl 8) or
            ((bytes[offset + 2].toLong() and 0xFF) shl 16) or
            ((bytes[offset + 3].toLong() and 0xFF) shl 24)

    private data class Manifest(
        val width: Int,
        val height: Int,
        val format: String,
        val layoutHash: String,
        val spriteCount: Int,
        val sprites: List<SpriteEntry>
    )

    private fun parseManifest(): Manifest {
        assertTrue(
            "atlas-manifest.json 不存在（运行 ./gradlew generateAstcAtlas 生成）",
            manifestFile.exists()
        )
        val json = Json.parseToJsonElement(manifestFile.readText()).jsonObject
        return Manifest(
            width = json.getValue("width").jsonPrimitive.int,
            height = json.getValue("height").jsonPrimitive.int,
            format = json.getValue("format").jsonPrimitive.content,
            layoutHash = json.getValue("layoutHash").jsonPrimitive.content,
            spriteCount = json.getValue("spriteCount").jsonPrimitive.int,
            sprites = json.getValue("sprites").jsonArray.map { s ->
                val o = s.jsonObject
                SpriteEntry(
                    name = o.getValue("name").jsonPrimitive.content,
                    x = o.getValue("x").jsonPrimitive.int,
                    y = o.getValue("y").jsonPrimitive.int,
                    w = o.getValue("w").jsonPrimitive.int,
                    h = o.getValue("h").jsonPrimitive.int
                )
            }
        )
    }

    private companion object {
        const val KTX_HEADER_SIZE = 64
        const val KTX_MAGIC_OFFSET = 0
        const val KTX_WIDTH_OFFSET = 32
        const val KTX_HEIGHT_OFFSET = 36
        const val KTX_DATA_SIZE_OFFSET = 64
        const val ASTC_BLOCK = 4
        const val ASTC_BLOCK_BYTES = 16
        const val LAYOUT_HASH_LENGTH = 16
    }
}
