package com.xianxia.sect.ui.game.sect

import com.xianxia.sect.core.render.DemolishHighlightMark
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 叠加层双端协议守卫（重构方案 2026-09-17 R3.3/B11）。
 *
 * 锁定 Kotlin 侧（[VulkanRenderBackend] 装配 overlayFlags / 推送叠加层状态）与
 * C++ 侧（`scene_draw.h` 位定义 + `scene_store.h` 协议步长/标记取值）五个
 * **不可漂移**的接缝——任一侧改动而另一侧漏改即红，且失败信息直接点名去处：
 *
 * 1. overlayFlags 位值（bit0–bit6）；
 * 2. 预览数据步长（16 浮点的顺序契约）；
 * 3. 拆除标记取值（Kotlin [DemolishHighlightMark] ↔ C++ `kDemolishMark*`）；
 * 4. 新路径零逐 rect 跨线（`drawRect`/`drawSprite` 不得出现在 SceneStore 路径体内；
 *    B18 已把旧回滚臂及其逐 rect 函数**全部删除**，本项转为单向 + 反向断言）;
 * 5. 网格线行范围的俯视 Y 压缩口径（Canvas 兜底 / C++ 两路同源）。
 *
 * 视觉常量（颜色/不透明度/线宽）的双端逐位对照在
 * `SceneUvTablesMirrorGuardTest`（:core:engine，生成物 ↔ SpriteAtlasDef）；
 * 两路几何等价在 C++ `SceneOverlayEquivalenceTest`（顶点流逐位对照）。
 *
 * 采用源码文本解析（沿 `MirrorConsumerSurfaceGuardTest` 先例）：这些常量是
 * 跨语言 ABI 的一部分，锁"源码字面写法"比放宽可见性更严格也不引入反射。
 */
class SceneOverlayProtocolGuardTest {

    /** 仓库文件定位（测试工作目录 = android/feature/game，向上找含 app/feature 的根） */
    private fun repoFile(relative: String): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".")
        while (dir != null) {
            for (candidate in listOf(File(dir, relative), File(dir, "android/$relative"))) {
                if (candidate.exists()) return candidate
            }
            dir = dir.parentFile
        }
        error("仓库文件定位失败：$relative（user.dir=${System.getProperty("user.dir")}）")
    }

    private fun sourceOf(relative: String): String = repoFile(relative).readText()

    private val backendSource: String
        get() = sourceOf("feature/game/src/main/java/com/xianxia/sect/ui/game/sect/VulkanRenderBackend.kt")

    private val sceneDrawSource: String
        get() = sourceOf("app/src/main/cpp/scene/scene_draw.h")

    private val sceneStoreSource: String
        get() = sourceOf("app/src/main/cpp/scene/scene_store.h")

    private val sceneUpdateSource: String
        get() = sourceOf("feature/game/src/main/java/com/xianxia/sect/ui/game/sect/SceneUpdateChannel.kt")

    /** 解析 `const val NAME = <整数字面量>`（十六进制或十进制） */
    private fun kotlinIntConst(source: String, name: String): Int {
        val regex = Regex("const val $name = (0x[0-9A-Fa-f]+|\\d+)")
        val match = regex.find(source)
            ?: error("$name 未在预期源文件中声明（叠加层/脏更新协议面被改动？）")
        return match.groupValues[1].let { if (it.startsWith("0x")) it.substring(2).toInt(16) else it.toInt() }
    }

    /** 解析 C++ `inline constexpr <整型> NAME = 1 << n;` 或 `= n;` */
    private fun cppIntConst(source: String, name: String): Int {
        val regex = Regex("inline constexpr (?:int32_t|int|uint8_t) $name = (?:1 << (\\d+)|(\\d+));")
        val match = regex.find(source)
            ?: error("scene_draw.h/scene_store.h 缺少 $name（双端协议漂移）")
        val shift = match.groupValues[1]
        return if (shift.isNotEmpty()) 1 shl shift.toInt() else match.groupValues[2].toInt()
    }

    @Test
    fun `overlayFlags bit values match C++ kOverlayBit definitions`() {
        val drawSource = sceneDrawSource
        val pairs = listOf(
            "OVERLAY_FLAG_BUILDING_VISIBLE" to "kOverlayBitBuildingVisible",
            "OVERLAY_FLAG_GRID_VISIBLE" to "kOverlayBitGridVisible",
            "OVERLAY_FLAG_PREVIEW_SPRITE" to "kOverlayBitPreviewSprite",
            "OVERLAY_FLAG_PREVIEW_BOX" to "kOverlayBitPreviewBox",
            "OVERLAY_FLAG_PREVIEW_VALID" to "kOverlayBitPreviewValid",
            "OVERLAY_FLAG_SELECTION" to "kOverlayBitSelection",
            "OVERLAY_FLAG_DEMOLISH" to "kOverlayBitDemolish"
        )
        val seenBits = mutableSetOf<Int>()
        for ((kotlinName, cppName) in pairs) {
            val kotlinValue = kotlinIntConst(backendSource, kotlinName)
            val cppValue = cppIntConst(drawSource, cppName)
            assertEquals("$kotlinName ↔ $cppName 位值不一致", cppValue, kotlinValue)
            assertTrue("$kotlinName 必须是单一位", kotlinValue > 0 && kotlinValue and (kotlinValue - 1) == 0)
            assertTrue("$kotlinName 位重复", seenBits.add(kotlinValue))
        }
        assertEquals("叠加层位总数（含 bit0 建筑层）", 7, seenBits.size)
    }

    @Test
    fun `preview data stride matches C++ kPreviewStride protocol`() {
        assertEquals(
            "预览数据步长双端不一致（Kotlin SceneUpdateChannel.PREVIEW_DATA_STRIDE ↔ C++ " +
                "kPreviewStride；字段序 = box×4 + sprite×4 + uv×4 + tint×4）",
            cppIntConst(sceneStoreSource, "kPreviewStride"),
            kotlinIntConst(sceneUpdateSource, "PREVIEW_DATA_STRIDE")
        )
    }

    @Test
    fun `demolish mark values match C++ kDemolishMark constants`() {
        val storeSource = sceneStoreSource
        assertEquals(DemolishHighlightMark.NONE, cppIntConst(storeSource, "kDemolishMarkNone"))
        assertEquals(DemolishHighlightMark.GREEN, cppIntConst(storeSource, "kDemolishMarkGreen"))
        assertEquals(DemolishHighlightMark.SELECTED, cppIntConst(storeSource, "kDemolishMarkSelected"))
    }

    /**
     * 新路径零逐 rect 跨线（G4 的源码面证明）：`renderSceneStorePath` 体内
     * 不得出现任何逐矩形调用；B18 已把旧回滚臂（`renderLegacyDrawAllTilesPath` /
     * `renderLegacyOverlayPath`）连同其四个逐 rect 辅助函数**整体删除**
     * （detekt `UnusedPrivateMember` 确认它们确无消费者），故本项转为
     * **单向 + 反向断言**：新路径不得混用，旧臂不得回流。
     *
     * 守住的失效形状：B10 曾出现"崖壁层双路重复绘制"缺陷（同一层在两条通道
     * 各画一次），同一形状在此被静态拦截。
     */
    @Test
    fun `per-rect draw calls never appear in the scene store path`() {
        val scenePath = functionBody(backendSource, "renderSceneStorePath")
        assertTrue("新路径函数体缺失（改名即断言失效，请同步本守卫）", scenePath.isNotEmpty())
        for (call in listOf("NativeBridge.drawRect(", "NativeBridge.drawSprite(")) {
            assertTrue(
                "新路径不得逐 rect 跨线：$call 出现在 renderSceneStorePath 体内",
                !scenePath.contains(call)
            )
        }
        // B18：旧回滚臂与其逐 rect 辅助函数已删除，且不得以任何形式回流
        // （灰度回退须走旗标重启，而非双路共存）
        for (fn in listOf(
            "renderLegacyOverlayPath",
            "renderLegacyDrawAllTilesPath",
            "drawSelectionHighlight",
            "drawDemolishMarker",
            "drawPreviewHighlight",
            "drawGridOverlay"
        )) {
            assertTrue(
                "旧路径函数 $fn 已被 B18 删除，不得回流",
                functionBody(backendSource, fn).isEmpty()
            )
        }
        assertTrue(
            "旧旗标 sceneStoreRender 不得回流（场景绘制已是单一路径）",
            !backendSource.contains("sceneStoreRender")
        )
    }

    /**
     * 前置缺陷 A 的防复发锁：Canvas 兜底路径的网格线**行上限**必须按俯视 Y 轴
     * 压缩系数换算（`视口高 / (scale × TOPDOWN_Y_SCALE)`），并与 C++ `scene_draw.h`
     * 的行范围同式。
     *
     * 缺陷本体 = 旧 Vulkan 写法 `视口高 / scale` 漏乘压缩系数 ⇒ 放置模式视口
     * 底部缺横线、与 Canvas 不一致（B11 修复）。B18 后 Vulkan 侧该实现已删除
     * （几何统一由 C++ 生成），故守卫锁 **Canvas + C++ 两处**口径不再分叉。
     */
    @Test
    fun `grid row range uses the topdown Y-compressed viewport band on remaining arms`() {
        val canvasSource =
            sourceOf("feature/game/src/main/java/com/xianxia/sect/ui/game/sect/SoftwareCanvasBackend.kt")
        val canvasCompressed =
            Regex("fbH / \\(drawScale \\* TOPDOWN_Y_SCALE\\)")
        assertTrue(
            "Canvas 兜底路径网格行范围不再按俯视 Y 压缩换算（双端口径漂移）",
            canvasCompressed.containsMatchIn(canvasSource)
        )
        val drawSource = sceneDrawSource
        assertTrue(
            "C++ 叠加层行范围未按投影可见带换算（与 Kotlin 侧漂移）",
            Regex("p\\.viewportH\\) / \\(scaleSafe \\* kTopdownYScale\\)").containsMatchIn(drawSource)
        )
    }

    /** 提取 `private fun <name>(...)` 到匹配的收尾大括号之间的函数体文本 */
    private fun functionBody(source: String, name: String): String {
        val marker = "fun $name("
        val start = source.indexOf(marker)
        if (start < 0) return ""
        val bodyStart = source.indexOf('{', start)
        if (bodyStart < 0) return ""
        var depth = 0
        for (i in bodyStart until source.length) {
            when (source[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(bodyStart + 1, i)
                }
            }
        }
        return ""
    }
}
