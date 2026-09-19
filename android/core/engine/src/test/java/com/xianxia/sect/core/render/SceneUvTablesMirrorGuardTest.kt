package com.xianxia.sect.core.render

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * SceneUvTablesMirrorGuardTest — 场景 UV 常量表跨语言镜像守卫（R3.2/B10）。
 *
 * 守护目标：`app/src/main/cpp/scene/scene_uv_tables.h`（build-atlas.mjs
 * --codegen 生成的仓库内生成物）与 Kotlin [SpriteAtlasDef]（同一生成器产出）
 * **逐位一致**——五张 UV 表（瓦片/建筑+固定结构/作物/云/道路）、占地尺寸表、
 * 双端共享渲染常量。任一侧漂移（改 LAYOUT 后漏提交 C++ 头 / 手改生成物）
 * 即失败并指引重跑 `node scripts/build-atlas.mjs --codegen`。
 *
 * 双向等价链：LAYOUT（唯一权威）→ 同一生成器 → Kotlin 常量（本测试读取）
 * 与 C++ 头（本测试解析）；R3.2 后 Kotlin 渲染新路径不再每帧传 UV 数组，
 * C++ 消费生成表——本守卫锁定"两份生成物同值"这一渲染像素等价的前提
 * （顶点流级等价另由 C++ SceneEquivalenceTest 锁定）。
 *
 * UV 解析为表达式求值（`136.0f / 4096.0f`）：图集尺寸为 2 的幂，除法在
 * 二进制浮点下精确，双端/测试三侧逐位一致；比较用 [Float.toRawBits]
 * （严格位相等，-0.0/NaN 语义不放宽）。
 */
class SceneUvTablesMirrorGuardTest {

    /** 从测试工作目录向上定位仓库根（含 android/app 的最近父目录）。 */
    private fun repoRoot(): File? {
        var dir: File? = File(System.getProperty("user.dir") ?: ".")
        while (dir != null) {
            if (File(dir, "android/app/src/main/cpp/scene/scene_uv_tables.h").exists() ||
                File(dir, "app/src/main/cpp/scene/scene_uv_tables.h").exists()
            ) {
                return dir
            }
            dir = dir.parentFile
        }
        return null
    }

    private fun generatedHeader(): File {
        val root = repoRoot()
        assumeTrue("仓库根定位失败（工作目录: ${System.getProperty("user.dir")}）", root != null)
        // assumeTrue 已在 null 时跳过测试——此处按 StaticDataSingleSourceGuardTest
        // 先例以 elvis 兜底返回（不可达路径），避免 `!!`
        val base = root ?: return File("scene_uv_tables.h")
        val direct = File(base, "app/src/main/cpp/scene/scene_uv_tables.h")
        if (direct.exists()) return direct
        return File(base, "android/app/src/main/cpp/scene/scene_uv_tables.h")
    }

    // ── 生成头解析 ─────────────────────────────────────────────

    /** 提取 `inline constexpr float <name>[] = { ... };` 花括号内文本 */
    private fun floatArrayBlock(header: String, name: String): String {
        val marker = "inline constexpr float $name[] = {"
        val start = header.indexOf(marker)
        assertTrue("scene_uv_tables.h 缺少 $name（生成物被手改/损坏？重跑 build-atlas.mjs --codegen）", start >= 0)
        val bodyStart = start + marker.length
        val end = header.indexOf("};", bodyStart)
        assertTrue("scene_uv_tables.h 的 $name 未正常闭合", end > bodyStart)
        return header.substring(bodyStart, end)
    }

    /** 提取 `inline constexpr int <name> = <value>;` 标量 */
    private fun intScalar(header: String, name: String): Int {
        val regex = Regex("inline constexpr int $name = (-?\\d+);")
        val m = regex.find(header)
        assertTrue("scene_uv_tables.h 缺少 $name", m != null)
        return m!!.groupValues[1].toInt()
    }

    /** 提取 `inline constexpr float <name> = <literal>;` 标量（浮点字面量） */
    private fun floatScalar(header: String, name: String): Float {
        val regex = Regex("inline constexpr float $name = (-?[\\d.]+f);")
        val m = regex.find(header)
        assertTrue("scene_uv_tables.h 缺少 $name", m != null)
        return m!!.groupValues[1].removeSuffix("f").toFloat()
    }

    /**
     * 解析 UV 表条目为 Float 列表：每项为 `a.0f / 4096.0f` 除法表达式
     * （生成器保证形态；2 的幂除法精确 ⇒ 求值结果与 Kotlin 运行时逐位一致）。
     */
    private fun parseUvTable(header: String, name: String): List<Float> =
        floatArrayBlock(header, name).split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { token ->
                val parts = token.split("/").map { it.trim().removeSuffix("f").trim() }
                assertEquals("UV 表 $name 条目须为 `a / b` 除法表达式: $token", 2, parts.size)
                parts[0].toFloat() / parts[1].toFloat()
            }

    /** 解析整型数组表 */
    private fun parseIntTable(header: String, name: String): List<Int> {
        val marker = "inline constexpr int $name[] = {"
        val start = header.indexOf(marker)
        assertTrue("scene_uv_tables.h 缺少 $name（生成物被手改/损坏？）", start >= 0)
        val bodyStart = start + marker.length
        val end = header.indexOf("};", bodyStart)
        assertTrue("scene_uv_tables.h 的 $name 未正常闭合", end > bodyStart)
        return header.substring(bodyStart, end)
            .split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { it.toInt() }
    }

    private fun assertBitsEqual(what: String, expected: Float, actual: Float) {
        assertEquals(
            "$what 逐位不一致（Kotlin=${expected.toRawBits().toString(16)} " +
                "C++=${actual.toRawBits().toString(16)}）——scene_uv_tables.h 与 " +
                "SpriteAtlasDef 漂移，重跑 node scripts/build-atlas.mjs --codegen",
            expected.toRawBits(),
            actual.toRawBits()
        )
    }

    private fun assertTableBitsEqual(what: String, expected: FloatArray, actual: List<Float>) {
        assertEquals("$what 长度不一致（Kotlin=${expected.size} C++=${actual.size}）", expected.size, actual.size)
        for (i in expected.indices) {
            assertBitsEqual("$what[$i]", expected[i], actual[i])
        }
    }

    // ── 五张 UV 表 ─────────────────────────────────────────────

    @Test
    fun `tile uv table mirrors SpriteAtlasDef bit-exactly`() {
        val header = generatedHeader().readText()
        assertTableBitsEqual("kTileUv", SpriteAtlasDef.TILE_UV_MAP, parseUvTable(header, "kTileUv"))
    }

    @Test
    fun `building uv table mirrors SpriteAtlasDef bit-exactly`() {
        val header = generatedHeader().readText()
        assertTableBitsEqual(
            "kBuildingUv", SpriteAtlasDef.BUILDING_UV_MAP, parseUvTable(header, "kBuildingUv")
        )
    }

    @Test
    fun `crop cloud road uv tables mirror SpriteAtlasDef bit-exactly`() {
        val header = generatedHeader().readText()
        assertTableBitsEqual("kCropUv", SpriteAtlasDef.CROP_UV_MAP, parseUvTable(header, "kCropUv"))
        assertTableBitsEqual("kCloudUv", SpriteAtlasDef.CLOUD_UV_MAP, parseUvTable(header, "kCloudUv"))
        assertTableBitsEqual("kRoadUv", SpriteAtlasDef.ROAD_UV_MAP, parseUvTable(header, "kRoadUv"))
    }

    // ── 占地表 ─────────────────────────────────────────────────

    @Test
    fun `footprint tables mirror SpriteAtlasDef`() {
        val header = generatedHeader().readText()
        val w = parseIntTable(header, "kFootprintW")
        val h = parseIntTable(header, "kFootprintH")
        assertEquals(
            "kFootprintW/H 行数与 FOOTPRINT_BY_NAME_INDEX 不一致",
            SpriteAtlasDef.FOOTPRINT_BY_NAME_INDEX.size,
            w.size
        )
        assertEquals(h.size, w.size)
        for (i in w.indices) {
            val (kw, kh) = SpriteAtlasDef.FOOTPRINT_BY_NAME_INDEX[i]
            assertEquals("kFootprintW[$i] 与 Kotlin 占地宽不一致", kw, w[i])
            assertEquals("kFootprintH[$i] 与 Kotlin 占地深不一致", kh, h[i])
        }
    }

    // ── 双端共享渲染常量 ───────────────────────────────────────

    @Test
    fun `shared render constants mirror SpriteAtlasDef bit-exactly`() {
        val header = generatedHeader().readText()
        assertBitsEqual(
            "kDecorQualityThreshold",
            SpriteAtlasDef.DECOR_QUALITY_THRESHOLD,
            floatScalar(header, "kDecorQualityThreshold")
        )
        assertBitsEqual(
            "kShadowOffsetTiles",
            SpriteAtlasDef.SHADOW_OFFSET_TILES,
            floatScalar(header, "kShadowOffsetTiles")
        )
        assertBitsEqual(
            "kShadowAlpha",
            SpriteAtlasDef.SHADOW_ALPHA,
            floatScalar(header, "kShadowAlpha")
        )
        assertBitsEqual(
            "kTopdownYScale",
            SpriteAtlasDef.TOPDOWN_Y_SCALE,
            floatScalar(header, "kTopdownYScale")
        )
        assertEquals(
            "kStructureNameBase 与 Kotlin 建筑数不一致",
            SpriteAtlasDef.BUILDING_NAMES.size,
            intScalar(header, "kStructureNameBase")
        )
    }
}
