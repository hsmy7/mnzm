package com.xianxia.sect

import com.xianxia.sect.core.render.SpriteAtlasDef
import com.xianxia.sect.core.render.SpriteRect
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/**
 * 离线 RGBA 产物守卫测试（B15 / R6.1）。
 *
 * B15 把 Canvas 软渲染 + RGBA 回退臂的**像素来源**从「设备上运行时拼装
 * （`SectAtlasAssembler.buildAtlasBitmap`）」切换为「构建期离线产物」：
 *
 * - `assets/atlas/atlas-rgba-raw.bin`：2048² 直通 alpha RGBA8888 裸像素（16 MiB）
 * - `assets/atlas/atlas-rgba-mips.bin`：11 级 level-major mip 裸像素链（至 2²）
 * - `assets/atlas/atlas-rgba-manifest.json`：几何 / golden 校验和 / 逐精灵 sha256
 *
 * 本测试锁住四件事：
 *
 * 1. **产物体积与几何**：`rawBytes = (ATLAS_W/2)² × 4`、11 级 mip 级联到 2、
 *    `rawFile` 实际字节数一致——几何漂移立即变红；
 * 2. **`sourceScale` 契约零变化**：产物边长恒 `ATLAS_W / 2`（= 2048），
 *    与 B15 前 Canvas 封顶位图 + RGBA 回退臂同尺寸 ⇒ UV / 分辨率契约无影响；
 * 3. **golden 校验和**：整图 sha256 + 逐槽位切片 sha256 与清单一致——
 *    产物被手工替换或构建中断留下半截文件时立即变红；
 * 4. **静态门禁（B15 任务 2）**：生产源码**零** `SectAtlasAssembler.buildAtlasBitmap`
 *    引用、零 `SectAtlasAssembler` 的 Canvas 拼装入口引用——运行时拼装退役。
 *
 * 权威源变更后运行 `./gradlew :app:generateOfflineRgbaAtlas` 重新生成产物。
 */
class AtlasOfflineRgbaSyncTest {

    private val assetDir = File("src/main/assets/atlas")
    private val manifestFile = File(assetDir, "atlas-rgba-manifest.json")
    private val rawFile = File(assetDir, "atlas-rgba-raw.bin")
    private val mipFile = File(assetDir, "atlas-rgba-mips.bin")

    // ============================================================
    // 1. 产物几何与体积
    // ============================================================

    @Test
    fun `离线产物几何与 ATLAS_W 的输入契约一致`() {
        val m = parseManifest()
        val half = SpriteAtlasDef.ATLAS_W / 2

        assertEquals("产物宽应为 ATLAS_W/2（sourceScale 0.5）", half, m.width)
        assertEquals("产物高应为 ATLAS_W/2（sourceScale 0.5）", half, m.height)
        assertEquals("kind 应为 offline-rgba", "offline-rgba", m.kind)
        assertEquals("format 应为 RGBA8888（直通 alpha 裸像素）", "RGBA8888", m.format)
        assertEquals("sourceSize 应为 ATLAS_W（4096 坐标系）", SpriteAtlasDef.ATLAS_W, m.sourceSize)
        assertEquals("scale 应为 0.5", 0.5, m.scale, 1e-9)

        assertEquals(
            "rawBytes 应为 width × height × 4",
            m.width.toLong() * m.height.toLong() * 4L,
            m.rawBytes
        )
        assertTrue("raw 资产不存在（运行 ./gradlew :app:generateOfflineRgbaAtlas）", rawFile.exists())
        assertEquals("raw 文件实际字节数与清单不符", m.rawBytes, rawFile.length())
    }

    @Test
    fun `离线产物 mip 链级联减半到 2`() {
        val m = parseManifest()
        assertEquals("mipLevels 应为 11（2048 → 2）", 11, m.mipLevels)
        assertEquals(
            "mipWidths 应为逐级减半序列",
            (0 until 11).map { SpriteAtlasDef.ATLAS_W / 2 shr it },
            m.mipWidths
        )
        val expectedBytes = m.mipWidths.sumOf { it.toLong() * it.toLong() * 4L }
        assertEquals("mipBytes 应为逐级 width² × 4 之和", expectedBytes, m.mipBytes)
        assertTrue("mip 资产不存在（运行 ./gradlew :app:generateOfflineRgbaAtlas）", mipFile.exists())
        assertEquals("mip 文件实际字节数与清单不符", m.mipBytes, mipFile.length())
        // level-major 布局：raw 为 level0，mip 链自 level1 起
        assertEquals("mipWidths[0] 应与产物边长同值", m.width, m.mipWidths[0])
    }

    @Test
    fun `离线产物精灵清单与 SpriteAtlasDef 布局一致`() {
        val m = parseManifest()
        assertTrue("spriteCount 应为正数", m.spriteCount > 0)
        assertEquals("spriteCount 应与 sprites 数组长度一致", m.sprites.size, m.spriteCount)

        // 落位几何由 SpriteAtlasDef 权威推导（与 writeOfflineRgbaArtifacts 同式：
        //   dx = round(x × 0.5)、dy = round(y × 0.5)、dw = w >> 1、dh = h >> 1）
        val placements = spritePlacements()
        assertEquals(
            "清单精灵数应为 SpriteAtlasDef 可绘制精灵数",
            placements.size,
            m.sprites.size
        )
        for (i in placements.indices) {
            val p = placements[i]
            val rec = m.sprites[i]
            assertEquals("精灵[$i] 名称顺序不一致", p.name, rec.name)
            assertEquals("${rec.name} 槽位字节数应为 dw × dh × 4", p.dw * p.dh * 4, rec.bytes)
            assertTrue("${rec.name} dx 越界", p.dx >= 0 && p.dx + p.dw <= m.width)
            assertTrue("${rec.name} dy 越界", p.dy >= 0 && p.dy + p.dh <= m.height)
            assertTrue("${rec.name} 槽位尺寸应为正", p.dw > 0 && p.dh > 0)
        }

        // 无重叠——Canvas `over` 覆盖顺序无歧义的前提
        for (i in placements.indices) {
            for (j in i + 1 until placements.size) {
                val a = placements[i]
                val b = placements[j]
                assertFalse(
                    "槽位重叠: ${a.name} × ${b.name}（over 覆盖顺序会改变像素）",
                    overlaps(a, b)
                )
            }
        }
    }

    /** AABB 相交判定（拆出以满足方法圈复杂度阈值） */
    private fun overlaps(a: Placement, b: Placement): Boolean =
        overlapsAxis(a.dx, a.dw, b.dx, b.dw) && overlapsAxis(a.dy, a.dh, b.dy, b.dh)

    private fun overlapsAxis(aStart: Int, aLen: Int, bStart: Int, bLen: Int): Boolean =
        aStart < bStart + bLen && bStart < aStart + aLen

    // ============================================================
    // 2. golden 校验和（整图 + 逐槽位）
    // ============================================================

    @Test
    fun `离线产物整图 golden 校验和一致`() {
        val m = parseManifest()
        if (!rawFile.exists()) return // 与几何用例同源，缺失已被上一用例报告
        val frame = rawFile.readBytes()
        assertEquals("整图 sha256 不符——产物被改写", m.frameSha256, sha256hex(frame))
    }

    @Test
    fun `离线产物逐槽位 golden 校验和与裸像素切片一致`() {
        val m = parseManifest()
        if (!rawFile.exists()) return
        val frame = rawFile.readBytes()
        val placements = spritePlacements()
        var checked = 0
        for (i in placements.indices) {
            val p = placements[i]
            val rec = m.sprites[i]
            val slot = sliceSlot(frame, p.dx, p.dy, p.dw, p.dh, m.width)
            assertEquals("${rec.name} 切片长度与清单不符", rec.bytes, slot.size)
            assertEquals("${rec.name} 槽位 sha256 不符", rec.sha256, sha256hex(slot))
            checked++
        }
        assertEquals("应逐槽位校验全部精灵", placements.size, checked)
        assertEquals("应逐槽位校验全部精灵", m.sprites.size, checked)
    }

    // ============================================================
    // 3. 静态门禁（B15 任务 2：运行时 Canvas 拼装退役）
    // ============================================================

    @Test
    fun `生产源码零运行时图集拼装引用`() {
        // 生产源码根：跳过 test / androidTest（守卫测试自身会提及历史名）
        val mainRoots = listOf(
            File("src/main/java"),
            File("../feature/game/src/main/java"),
            File("../core/engine/src/main/java")
        )
        // 已退役的拼装入口（B15 前由 prepareAtlas → assembleAtlasBitmap → 此函数调用）
        val banned = listOf(
            "SectAtlasAssembler.buildAtlasBitmap",
            "SectAtlasAssembler.buildSpriteSlots",
            "SectAtlasAssembler.drawSlotsToAtlas"
        )
        val offenders = mainRoots
            .filter { it.exists() }
            .flatMap { root -> scanRootForBanned(root, banned) }
        assertTrue(
            "运行时图集拼装入口已退役（B15 / R6.1），生产源码不得再引用：\n" +
                offenders.joinToString("\n"),
            offenders.isEmpty()
        )
    }

    /** 扫描单个源码根，返回违规命中（`路径 → 禁止串`） */
    private fun scanRootForBanned(root: File, banned: List<String>): List<String> =
        root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { f ->
                // 去注释后扫描：退役后的 KDoc「历史落点」段会**刻意**提及旧函数名
                // （记录迁移来源），那不是引用，不得计入违规。
                val code = stripKotlinComments(f.readText())
                banned.filter { code.contains(it) }.map { "${f.path} → $it" }
            }
            .toList()

    /**
     * 去 Kotlin 注释（块注释 + 行注释）——保留字符串字面量，避免误伤。
     *
     * 逐位置分派到三个小函数，刻意保持本函数低圈复杂度。
     */
    private fun stripKotlinComments(src: String): String {
        val out = StringBuilder(src.length)
        var i = 0
        while (i < src.length) {
            i = when {
                startsWith(src, i, "//") -> skipLineComment(src, i, out)
                startsWith(src, i, "/*") -> skipBlockComment(src, i)
                startsWith(src, i, "\"\"\"") -> copyRawString(src, i, out)
                src[i] == '"' -> copyQuotedString(src, i, out)
                else -> appendOne(src, i, out)
            }
        }
        return out.toString()
    }

    private fun startsWith(src: String, i: Int, token: String): Boolean =
        src.startsWith(token, i)

    private fun appendOne(src: String, i: Int, out: StringBuilder): Int {
        out.append(src[i])
        return i + 1
    }

    /** 行注释：吞到行尾（保留换行符本身，便于定位） */
    private fun skipLineComment(src: String, start: Int, out: StringBuilder): Int {
        var i = start
        while (i < src.length && src[i] != '\n') i++
        if (i < src.length) out.append('\n')
        return i + 1
    }

    /** 块注释：吞到块注释终止符（Kotlin 块注释可嵌套，但生产码极少；此处按平铺处理） */
    private fun skipBlockComment(src: String, start: Int): Int {
        var i = start + 2
        while (i + 1 < src.length && !(src[i] == '*' && src[i + 1] == '/')) i++
        return i + 2
    }

    /** 原始字符串 `"""..."""` 整体保留 */
    private fun copyRawString(src: String, start: Int, out: StringBuilder): Int {
        out.append(src, start, start + 3)
        var i = start + 3
        while (i + 2 < src.length && !startsWith(src, i, "\"\"\"")) i++
        val end = minOf(i + 3, src.length)
        out.append(src, i, end)
        return end
    }

    /** 普通字符串：整体保留（注释符在字符串内无意义），处理 `\` 转义 */
    private fun copyQuotedString(src: String, start: Int, out: StringBuilder): Int {
        out.append('"')
        var i = start + 1
        while (i < src.length && src[i] != '"') {
            if (src[i] == '\\' && i + 1 < src.length) {
                out.append(src, i, i + 2)
                i += 2
            } else {
                out.append(src[i])
                i++
            }
        }
        return if (i < src.length) appendOne(src, i, out) else i
    }

    @Test
    fun `SectAtlasAssembler 保留职责仍可用（软渲深缩放兜底）`() {
        // 退役后类本体转测试夹具：drawable 映射与深缩放兜底必须仍在（红线路径依赖）。
        val src = File("../feature/game/src/main/java/com/xianxia/sect/ui/game/sect/SectAtlasAssembler.kt")
        assertTrue("SectAtlasAssembler.kt 应存在（退役后保留残余职责）", src.exists())
        val text = src.readText()
        assertTrue("downscaleWithBilinearChain 不得删除（软渲深缩放兜底单一实现）", text.contains("downscaleWithBilinearChain"))
        assertTrue("buildingAtlasDrawableMap 不得删除（守卫测试入口）", text.contains("fun buildingAtlasDrawableMap"))
        assertTrue("tileDrawableRes 不得删除（守卫测试入口）", text.contains("fun tileDrawableRes"))
        // 拼装路径必须已删除
        assertFalse("buildAtlasBitmap 应已删除（Canvas 拼装退役）", text.contains("fun buildAtlasBitmap"))
    }

    // ============================================================
    // 工具
    // ============================================================

    private fun sha256hex(buf: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(buf).joinToString("") { "%02x".format(it) }

    /** 按清单几何从 2048² 裸像素切出单精灵缓冲（行拼接，与清单写出侧同式） */
    private fun sliceSlot(frame: ByteArray, dx: Int, dy: Int, dw: Int, dh: Int, stride: Int): ByteArray {
        val out = ByteArray(dw * dh * 4)
        for (r in 0 until dh) {
            val srcOff = ((dy + r) * stride + dx) * 4
            System.arraycopy(frame, srcOff, out, r * dw * 4, dw * 4)
        }
        return out
    }

    private data class SpriteRec(
        val name: String,
        val bytes: Int,
        val sha256: String
    )

    /** 落位几何（由 SpriteAtlasDef 权威推导，与 writeOfflineRgbaArtifacts 同式） */
    private data class Placement(
        val name: String,
        val dx: Int,
        val dy: Int,
        val dw: Int,
        val dh: Int
    )

    /**
     * 复现生成脚本的可绘制精灵落位（顺序 = tile → crops → buildings → structures
     * → clouds → roads，与 `build-atlas.mjs buildSpriteList` / 离线库
     * `buildOfflineSpriteList` 完全同式——任一侧改序本测试立即变红）。
     */
    private fun spritePlacements(): List<Placement> {
        val half = SpriteAtlasDef.ATLAS_W / 2
        val items = mutableListOf<Pair<String, SpriteRect>>()
        for (tile in SpriteAtlasDef.TileType.values()) {
            // TILE_BUILDING 为占位（drawable 缺失不产出），离线清单同样跳过
            if (tile.name == "TILE_BUILDING") continue
            items += tile.name to tile.rect
        }
        for (crop in SpriteAtlasDef.CropStage.values()) items += crop.name to crop.rect
        for (i in SpriteAtlasDef.BUILDING_NAMES.indices) {
            items += SpriteAtlasDef.BUILDING_NAMES[i] to SpriteAtlasDef.buildingRect(i)
        }
        for (s in SpriteAtlasDef.STRUCTURES) items += s.key to s.rect
        for ((name, r) in SpriteAtlasDef.CLOUD_RECTS) items += name to r
        for ((name, r) in SpriteAtlasDef.ROAD_RECTS) items += name to r
        // 浮空岛崖壁不在图集内（独立纹理），不追加
        return items.map { (name, r) ->
            Placement(
                name = name,
                dx = Math.round(r.x * 0.5f),
                dy = Math.round(r.y * 0.5f),
                dw = r.w shr 1,
                dh = r.h shr 1
            )
        }.also { require(it.all { p -> p.dx + p.dw <= half && p.dy + p.dh <= half }) }
    }

    private data class OfflineManifest(
        val version: Int,
        val kind: String,
        val format: String,
        val sourceSize: Int,
        val width: Int,
        val height: Int,
        val scale: Double,
        val rawFile: String,
        val rawBytes: Long,
        val mipLevels: Int,
        val mipWidths: List<Int>,
        val mipFile: String,
        val mipBytes: Long,
        val spriteCount: Int,
        val frameSha256: String,
        val sprites: List<SpriteRec>
    )

    private fun parseManifest(): OfflineManifest {
        assertTrue(
            "atlas-rgba-manifest.json 不存在（运行 ./gradlew :app:generateOfflineRgbaAtlas 生成）",
            manifestFile.exists()
        )
        val json = Json.parseToJsonElement(manifestFile.readText()).jsonObject
        return OfflineManifest(
            version = json.getValue("version").jsonPrimitive.int,
            kind = json.getValue("kind").jsonPrimitive.content,
            format = json.getValue("format").jsonPrimitive.content,
            sourceSize = json.getValue("sourceSize").jsonPrimitive.int,
            width = json.getValue("width").jsonPrimitive.int,
            height = json.getValue("height").jsonPrimitive.int,
            scale = json.getValue("scale").jsonPrimitive.content.toDouble(),
            rawFile = json.getValue("rawFile").jsonPrimitive.content,
            rawBytes = json.getValue("rawBytes").jsonPrimitive.content.toLong(),
            mipLevels = json.getValue("mipLevels").jsonPrimitive.int,
            mipWidths = json.getValue("mipWidths").jsonArray.map { it.jsonPrimitive.int },
            mipFile = json.getValue("mipFile").jsonPrimitive.content,
            mipBytes = json.getValue("mipBytes").jsonPrimitive.content.toLong(),
            spriteCount = json.getValue("spriteCount").jsonPrimitive.int,
            frameSha256 = json.getValue("frameSha256").jsonPrimitive.content,
            sprites = json.getValue("sprites").jsonArray.map { s ->
                val o = s.jsonObject
                SpriteRec(
                    name = o.getValue("name").jsonPrimitive.content,
                    bytes = o.getValue("bytes").jsonPrimitive.int,
                    sha256 = o.getValue("sha256").jsonPrimitive.content
                )
            }
        )
    }
}
