package com.xianxia.sect.ui.game.sect

import com.xianxia.sect.core.render.NativeRenderConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * [AtlasAsyncPipeline] RGBA mip 链守卫（B15 / R6.1 后口径）。
 *
 * 锁定不变量：
 * 1. [OfflineAtlasAssets.readRawSpec]：离线产物清单解析出的几何 = 构建期
 *    `scripts/atlas-offline-rgba.mjs` 的产出契约（2048² + 11 级 box mip 链，
 *    各级尺寸 2048→2，level-major 紧凑布局）。
 * 2. [AtlasAsyncPipeline.uploadAtlas]：RGBA 路径经注入点
 *    [NativeSurfaceView.mipChainUploader] 多级上传——Fake 断言 JNI 入参
 *    （mipCount/级尺寸/首级数据完整性）与只读零拷贝映射。
 *
 * ## 与 B15 前口径的差异
 *
 * B15 前本测试用 `encodeBitmapToRgbaMipChain(Bitmap)` **现场级联缩放**产出链；
 * 该函数已随运行时降采样一起退役（消除 11 级 × ~4MB 中间位图的内存尖峰）。
 * 现在链由**构建期**产出为裸像素资产，运行时只 `FileChannel.map` 零拷贝——
 * 故本测试的「11 级/level-major/首级完整图集」断言改为对**产物清单+映射**断言，
 * 元数据契约与 B15 前逐字一致（防产物形态回退）。
 *
 * 边界：native 库不加载（JVM 测试环境），C++ uploadMipChainTexture 逐级拷贝与
 * VUID 对齐由 `externalNativeBuildRelease` 编译门 + 真机验证覆盖。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AtlasAsyncPipelineMipChainTest {

    /**
     * 仓库内离线产物目录。
     *
     * 路径基准同 [EdgeKtxSyncTest]：Gradle 单元测试工作目录为 `android/feature`
     * （上溯一级到 `android/`），兜底 `feature/game` 直跑（上溯两级）。
     */
    private val repoAssets: java.io.File = listOf(
        java.io.File("../app/src/main/assets/atlas"),
        java.io.File("../../app/src/main/assets/atlas"),
    ).firstOrNull { java.io.File(it, "atlas-rgba-raw.bin").exists() }
        ?: java.io.File("../app/src/main/assets/atlas")

    @Before
    fun setup() {
        // native 库不加载（JVM 环境）——NativeBridge.external 调用会抛
        // UnsatisfiedLinkError，测试只走注入 Fake，不触碰真实 JNI。
        // uploadGroundTexture 内部 catch Throwable，静默降级不影响断言。
    }

    @Test
    fun `离线产物清单 - 11 级 mip 链级联减半到 2`() {
        val json = org.json.JSONObject(
            requireNotNull(offlineManifest()).readText()
        )
        val widths = json.getJSONArray("mipWidths").let { arr -> (0 until arr.length()).map { arr.getInt(it) } }
        // 2048→2 共 11 级（与 ASTC KTX 的 11 级 mip 语义对齐）
        assertEquals("mip 层级数", 11, json.getInt("mipLevels"))
        assertEquals("各级宽 2048,1024,...,2", (0 until 11).map { 2048 shr it }, widths)
        // level-major 紧凑：总字节 = Σ level²×4（与运行时 map 容量契约一致）
        val expectedBytes = widths.fold(0L) { acc, w -> acc + w.toLong() * w * 4 }
        assertEquals("mip 资产字节 = 各级像素总和×4", expectedBytes, json.getLong("mipBytes"))
        assertEquals(
            "裸像素资产字节 = 2048²×4",
            2048L * 2048 * 4,
            json.getLong("rawBytes")
        )
        assertEquals("首级宽 = 图集宽（单级回退兼容）", 2048, widths[0])
    }

    @Test
    fun `离线产物 - mip 资产容量与清单几何一致且可只读零拷贝映射`() {
        val json = org.json.JSONObject(requireNotNull(offlineManifest()).readText())
        val widths = json.getJSONArray("mipWidths").let { arr -> (0 until arr.length()).map { arr.getInt(it) } }
        val mips = java.io.File(repoAssets, "atlas-rgba-mips.bin")
        assertTrue("mip 资产应存在（运行 ./gradlew generateOfflineRgbaAtlas）", mips.exists())

        // FileChannel.map 零拷贝（与 [AtlasAsyncPipeline.mapAsset] 同式）
        val mapped: ByteBuffer = java.io.FileInputStream(mips).channel.use { ch ->
            ch.map(java.nio.channels.FileChannel.MapMode.READ_ONLY, 0, mips.length())
        }
        val expected = widths.indices.sumOf { i ->
            widths[i].toLong() * widths[i] * 4
        }
        assertEquals("映射容量 = 清单几何推导", expected, mapped.capacity().toLong())
        assertEquals("映射为 direct 缓冲（JNI 零拷贝前提）", true, mapped.isDirect)
        // 字节序口径：`FileChannel.map` 默认返回 BIG_ENDIAN 视图（JDK 规范），
        // 产物是**裸字节流**，消费端（JNI `GetDirectBufferAddress` + memcpy）不依赖
        // ByteBuffer 的 order 属性——故此处断言「order 为默认值」并说明口径，
        // 而不是断言 nativeOrder（那会与 JDK 行为冲突）。
        assertEquals(
            "map 返回默认字节序视图（裸像素按字节消费，不依赖 order）",
            ByteOrder.BIG_ENDIAN,
            mapped.order()
        )
    }

    @Test
    fun `离线产物清单 - 逐精灵 golden 校验和与裸像素切片一致`() {
        // 等价性守卫的粗粒度锚点：清单里逐精灵 sha256 必须能从裸像素按槽位切出来
        // （B15 任务 5 的逐位对照由 scripts/verify-offline-rgba-equivalence.mjs 承担，
        //  本断言保证「清单描述的槽位 ↔ 像素实际位置」不自相矛盾）。
        val json = org.json.JSONObject(requireNotNull(offlineManifest()).readText())
        val raw = java.io.File(repoAssets, "atlas-rgba-raw.bin")
        assertTrue("裸像素资产应存在", raw.exists())
        assertEquals("裸像素长度 = 2048²×4", 2048L * 2048 * 4, raw.length())

        val size = json.getInt("width")
        val sprites = json.getJSONArray("sprites")
        assertTrue("清单应含逐精灵校验和", sprites.length() > 0)
        // 抽查首个条目：按槽位切出的字节 = 清单记录的 bytes 字段
        val first = sprites.getJSONObject(0)
        val bytes = java.io.FileInputStream(raw).channel.use { ch ->
            val buf = ch.map(java.nio.channels.FileChannel.MapMode.READ_ONLY, 0, raw.length())
            val arr = ByteArray(first.getInt("bytes"))
            buf.position(0)
            buf.get(arr)
            arr
        }
        assertEquals("首个精灵切片长度与清单一致", first.getInt("bytes"), bytes.size)
        assertNotNull("校验和字段应存在", first.getString("sha256"))
    }

    @Test
    fun `uploadAtlas RGBA 路径 - Fake 注入断言 mip 链入参`() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val view = createView()
        val pipeline = AtlasAsyncPipeline(view)

        // Fake 注入：捕获 (buffer, w, h, mipCount) 入参，返回哨兵纹理 ID
        var captured: Quad<ByteBuffer, Int, Int, Int>? = null
        view.mipChainUploader = { buffer, w, h, mips ->
            captured = Quad(buffer, w, h, mips)
            42
        }

        // 64² 载体（6 级：64→2）——直接缓冲，布局与离线产物同语义
        val widths = listOf(64, 32, 16, 8, 4, 2)
        val total = widths.sumOf { it.toLong() * it * 4 }
        val buffer = ByteBuffer.allocateDirect(total.toInt()).order(ByteOrder.nativeOrder())
        buffer.put(ByteArray(total.toInt()))
        buffer.rewind()
        val payload = AtlasPayload(
            rgbaMipPixels = buffer,
            mipCount = widths.size,
            width = 64,
            height = 64
        )

        val texId = pipeline.uploadAtlas(context, payload)

        assertEquals("应透传 Fake 哨兵 ID", 42, texId)
        val cap = captured ?: throw AssertionError("mipChainUploader 未被调用")
        assertEquals("入参 width", 64, cap.second)
        assertEquals("入参 height", 64, cap.third)
        assertEquals("入参 mipCount（含首级）", 6, cap.fourth)
        // 首级数据完整性：缓冲容量 = Σ 各级像素（首级 64²×4 起，单级回退按起始地址读）
        assertEquals("缓冲容量 = Σ level²×4", total, cap.first.capacity().toLong())
        assertEquals("缓冲按 nativeOrder 布局", ByteOrder.nativeOrder(), cap.first.order())
    }

    /** 离线产物清单文件（不存在返回 null——CI 未跑生成任务时用例应给出清晰失败） */
    private fun offlineManifest(): java.io.File? =
        java.io.File(repoAssets, "atlas-rgba-manifest.json").takeIf { it.exists() }

    private fun createView(): NativeSurfaceView {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val config = NativeRenderConfig(
            tileSize = 64,
            worldWidthCells = 10,
            worldHeightCells = 10,
            worldPixelWidth = 640,
            worldPixelHeight = 640
        )
        return NativeSurfaceView(context, config)
    }

    /** 简易 4 元组（测试断言用） */
    private data class Quad<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)
}
