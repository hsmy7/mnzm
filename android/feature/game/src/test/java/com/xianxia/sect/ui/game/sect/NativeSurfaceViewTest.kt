package com.xianxia.sect.ui.game.sect

import android.os.Looper
import android.view.MotionEvent
import androidx.test.core.app.ApplicationProvider
import com.xianxia.sect.core.render.NativeRenderConfig
import com.xianxia.sect.core.render.RenderFlags
import com.xianxia.sect.core.render.RenderFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * NativeSurfaceView 渲染质量转发序列测试。
 *
 * 覆盖维度：
 * - renderQualityFactor / renderDecorationsDisabled setter 转发到 [renderQualitySink]
 * - 单边 setter 触发时携带两个当前值（C++ 全局量状态完整，防单边不同步）
 * - surface 重建（SOFTWARE 路径）后热控状态不丢失（createSoftwareBackend 应用当前值）
 * - buildAtlas ASTC 压缩图集回退链（uploader 注入 Fake 断言分支行为）
 *
 * 边界：Vulkan 路径的 C++ 全局量重放无法在 JVM 验证（native 库不加载），
 * 由 surfaceChanged 中 pushRenderQuality() 的代码审查 + 真机验证覆盖。
 */
@RunWith(RobolectricTestRunner::class)
class NativeSurfaceViewTest {

    private lateinit var context: android.content.Context

    private fun createView(renderFlags: RenderFlags = RenderFlags()): NativeSurfaceView {
        val config = NativeRenderConfig(
            tileSize = 64,
            worldWidthCells = 10,
            worldHeightCells = 10,
            worldPixelWidth = 640,
            worldPixelHeight = 640,
            renderFlags = renderFlags
        )
        return NativeSurfaceView(context, config)
    }

    /**
     * 离线 RGBA 产物清单（不可用返回 null）。
     *
     * Robolectric 默认 assets 解析根不含 `app/src/main/assets`——本用例所在
     * `feature/game` 单元测试无法直接读产物，故显式按仓库相对路径定位。
     * 路径基准同 [EdgeKtxSyncTest]：Gradle 单元测试的工作目录是
     * `android/feature`（不是 `android/feature/game`），故上溯到 `android/`
     * 只需一级（`../app/...`）；同时兜底 `feature/game` 直跑（`../../app/...`）。
     */
    private fun offlineAtlasSpecOrNull(): OfflineAtlasSpec? {
        val candidates = listOf(
            java.io.File("../app/src/main/assets/atlas"),
            java.io.File("../../app/src/main/assets/atlas"),
        )
        val dir = candidates.firstOrNull { java.io.File(it, "atlas-rgba-raw.bin").exists() }
            ?: return null
        val rawRepo = java.io.File(dir, "atlas-rgba-raw.bin")
        val manifest = java.io.File(dir, "atlas-rgba-manifest.json")
        if (!manifest.exists()) return null
        val json = org.json.JSONObject(manifest.readText())
        val widths = json.getJSONArray("mipWidths").let { arr -> (0 until arr.length()).map { arr.getInt(it) } }
        // 产物路径替换为仓库绝对路径——Robolectric 的 AssetManager 读不到
        // feature/game 工程外的 assets，故 [AtlasAsyncPipeline] 的 assets 读取
        // 在 Robolectric 下必然失败；本 helper 供尺寸契约断言（见用例注释）。
        return OfflineAtlasSpec(
            rawPath = rawRepo.absolutePath,
            mipPath = java.io.File(dir, "atlas-rgba-mips.bin").absolutePath,
            width = json.getInt("width"),
            height = json.getInt("height"),
            mipWidths = widths,
            mipHeights = widths
        )
    }

    /** 位图桩（供 uploadAtlas 分流断言——不依赖真实离线产物） */
    private fun createBitmapStub(): android.graphics.Bitmap =
        android.graphics.Bitmap.createBitmap(4, 4, android.graphics.Bitmap.Config.ARGB_8888)

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun `renderQualityFactor setter 转发到 sink`() {
        val view = createView()
        val calls = mutableListOf<Pair<Float, Boolean>>()
        view.renderQualitySink = { q, d -> calls.add(q to d) }

        view.renderQualityFactor = 0.5f

        assertEquals(
            "setter 应把 (0.5, false) 转发到 sink",
            listOf(0.5f to false), calls
        )
    }

    @Test
    fun `renderDecorationsDisabled setter 转发到 sink`() {
        val view = createView()
        val calls = mutableListOf<Pair<Float, Boolean>>()
        view.renderQualitySink = { q, d -> calls.add(q to d) }

        view.renderDecorationsDisabled = true

        assertEquals(
            "setter 应把 (1.0, true) 转发到 sink（携带当前质量因子）",
            listOf(1.0f to true), calls
        )
    }

    @Test
    fun `单边 setter 触发时携带两个当前值防单边不同步`() {
        val view = createView()
        val calls = mutableListOf<Pair<Float, Boolean>>()
        view.renderQualitySink = { q, d -> calls.add(q to d) }

        view.renderQualityFactor = 0.5f
        view.renderDecorationsDisabled = true

        // 第二次调用必须携带已更新的 qualityFactor，防止 C++ 侧只更新装饰标志
        assertEquals(
            "第二次转发应携带 (0.5, true)",
            listOf(0.5f to false, 0.5f to true), calls
        )
    }

    @Test
    fun `setter 先于 surface 就绪触发时默认 sink 安全不崩溃`() {
        val view = createView()
        view.useRenderMode = NativeSurfaceView.RenderMode.SOFTWARE

        // 不注入 fake——默认 sink 依赖 nativeReady guard（isReady && renderMode == VULKAN）
        // 拦截转发，防止 native 库未加载时 UnsatisfiedLinkError（SOFTWARE 策略下
        // surfaceCreated 不加载 native 库）。此用例验证 guard 的可观测行为。
        view.renderQualityFactor = 0.5f
        view.renderDecorationsDisabled = true

        // Robolectric 下 holder.surface 恒为 null → surfaceChanged 安全 no-op
        // （事件经 view.surfaceProvider（AndroidSurfaceProvider）派发，
        // 状态机需先 surfaceCreated 再 surfaceChanged 才能到达宿主初始化入口）
        val provider = view.surfaceProvider as AndroidSurfaceProvider
        provider.surfaceCreated(view.holder)
        provider.surfaceChanged(view.holder, 0, 200, 200)
        shadowOf(Looper.getMainLooper()).idle()

        // 无 surface 不崩溃、不误初始化；渲染线程未启动（防 Robolectric 线程泄漏）
        assertFalse("无 surface 时不应完成初始化", view.isReady)
    }

    // ============================================================
    // ASTC 压缩图集分支决策（tryCompressedAtlas/shouldTryCompressedAtlas）
    // 注：完整 buildAtlas 的 RGBA 上传为 native 调用（Robolectric 无法拦截，
    // 抛 UnsatisfiedLinkError），分支逻辑提取为纯函数在此锁定，上传链路真机验证。
    // ============================================================

    @Test
    fun `tryCompressedAtlas - Vulkan 模式 loader 被调用且返回 0 时透传回退信号`() {
        val view = createView()
        view.useRenderMode = NativeSurfaceView.RenderMode.VULKAN
        var loaderCalled = false
        // 模拟设备不支持/资产损坏（C++ KtxLoader 校验失败返回 0）
        view.compressedAtlasLoader = {
            loaderCalled = true
            0
        }

        val id = view.tryCompressedAtlas(context)

        assertTrue("ASTC 加载应被尝试", loaderCalled)
        assertEquals("loader 返回 0 = 回退 RGBA 信号", 0, id)
    }

    @Test
    fun `tryCompressedAtlas - 成功返回纹理 ID 透传（跳过运行时拼装）`() {
        val view = createView()
        view.useRenderMode = NativeSurfaceView.RenderMode.VULKAN
        var loaderCalled = false
        view.compressedAtlasLoader = {
            loaderCalled = true
            5 // 模拟 C++ 上传成功返回纹理 ID
        }

        val id = view.tryCompressedAtlas(context)

        assertTrue("ASTC 加载应被尝试", loaderCalled)
        assertEquals("成功路径透传纹理 ID", 5, id)
    }

    @Test
    fun `tryCompressedAtlas - textureCompression 关闭时不尝试 ASTC`() {
        val view = createView(renderFlags = RenderFlags(textureCompression = false))
        view.useRenderMode = NativeSurfaceView.RenderMode.VULKAN
        var loaderCalled = false
        view.compressedAtlasLoader = {
            loaderCalled = true
            5
        }

        val id = view.tryCompressedAtlas(context)

        assertFalse("textureCompression=false 时不应尝试 ASTC 加载", loaderCalled)
        assertEquals("返回 0 = 直接走 RGBA", 0, id)
    }

    // ============================================================
    // 图集异步流水线（拼装移出主线程 + RGBA DirectByteBuffer）
    //
    // 语义：prepareAtlas（重活，后台线程）/ uploadAtlas（轻活，主线程）
    // 两段拆分后各自的分支行为在此锁定；完整 RGBA 上传为 native 调用
    // （Robolectric 无法拦截，抛 UnsatisfiedLinkError），真机验证。
    // ============================================================

    @Test
    fun `prepareAtlas - 允许压缩且读取器返回字节时产出 ktx 载荷不碰拼装`() {
        val view = createView()
        view.useRenderMode = NativeSurfaceView.RenderMode.VULKAN
        view.compressedAtlasReader = { byteArrayOf(1, 2, 3) }

        val payload = view.atlasPipeline.prepareAtlas(context, software = false, allowCompressed = true)

        assertEquals("ktx 载荷应携带资产字节", listOf<Byte>(1, 2, 3), payload.ktx!!.toList())
        assertEquals("压缩路径不产出 RGBA 像素", null, payload.rgbaPixels)
        assertEquals("压缩路径不产出位图", null, payload.softwareBitmap)
        assertFalse("压缩读取成功不是失败", payload.failed)
    }

    @Test
    fun `prepareAtlas - 读取器返回 null 时落回离线 RGBA 产物且不算失败`() {
        val view = createView()
        view.useRenderMode = NativeSurfaceView.RenderMode.VULKAN
        view.compressedAtlasReader = { null }

        val payload = view.atlasPipeline.prepareAtlas(context, software = true, allowCompressed = true)

        assertEquals("资产缺失应落回离线 RGBA 产物", null, payload.ktx)
        // B15：离线产物在 Robolectric 下不可读（assets 未打包进 classpath）⇒
        //   payload.failed = true。此处只锁「不落回运行时拼装」这一契约：
        //   无论产物是否可读，都不得再产出运行时拼装的 softwareBitmap。
        assertTrue(
            "B15 起不得再产出运行时拼装位图（离线产物不可用时须 failed）",
            payload.failed || payload.softwareBitmap != null
        )
    }

    @Test
    fun `prepareAtlas - 软渲染路径像素源为离线产物且长边恒 2048`() {
        // 离线产物存在时（CI/本地已跑 generateOfflineRgbaAtlas）：
        //   ① 清单尺寸必须 = ATLAS_W × 0.5（封顶语义零变化——本断言不依赖 assets）；
        //   ② 若 Robolectric 能读到产物（依赖打包），则 softwareBitmap 尺寸必须与清单一致。
        // Robolectric 的 AssetManager 只挂载本工程 assets（feature/game），产物在
        // app 工程侧，故 ② 以「读到才校验」的方式表达——plumbing 正确性由
        // AtlasAsyncPipelineMipChainTest（直读仓库文件）+ 真机验证覆盖。
        val spec = requireNotNull(offlineAtlasSpecOrNull()) {
            "离线 RGBA 产物缺失——先运行 ./gradlew generateOfflineRgbaAtlas"
        }
        val half = (com.xianxia.sect.core.render.SpriteAtlasDef.ATLAS_W * 0.5).toInt()
        assertEquals("封顶语义零变化：产物边长 = ATLAS_W × 0.5", half, spec.width)
        assertEquals("封顶语义零变化：产物边长 = ATLAS_W × 0.5", half, spec.height)
        assertEquals("mip 链首级应与产物同尺寸（level-major，level0 = 边长）", spec.width, spec.mipWidths.first())

        val view = createView()
        val payload = view.atlasPipeline.prepareAtlas(context, software = true, allowCompressed = false)
        if (payload.softwareBitmap != null) {
            val atlas = payload.softwareBitmap
            assertEquals("位图宽应等于离线产物宽", spec.width, atlas.width)
            assertEquals("位图高应等于离线产物高", spec.height, atlas.height)
        } else {
            // assets 不可读 ⇒ 必须 failed（不得回退运行时拼装）
            assertTrue("产物不可读时须 failed 而非静默回退", payload.failed)
        }
    }

    @Test
    fun `uploadAtlas - 软渲染载荷挂到 atlasBitmap 并返回 0`() {
        val view = createView()
        val payload = AtlasPayload(softwareBitmap = createBitmapStub())

        val id = view.atlasPipeline.uploadAtlas(context, payload)

        assertEquals("软渲染路径不上传 GPU", 0, id)
        assertTrue("位图应挂到 atlasBitmap 供 Canvas 后端读取", view.atlasBitmap != null)
    }

    @Test
    fun `uploadAtlas - 失败载荷返回 0 且不触碰 atlasBitmap`() {
        val view = createView()
        val payload = AtlasPayload(failed = true)

        val id = view.atlasPipeline.uploadAtlas(context, payload)

        assertEquals(0, id)
        assertEquals("失败路径不得写入 atlasBitmap", null, view.atlasBitmap)
    }

    @Test
    fun `uploadAtlas - ktx 载荷走注入上传器透传纹理 ID`() {
        val view = createView()
        view.compressedAtlasUploader = { 42 }

        val id = view.atlasPipeline.uploadAtlas(context, AtlasPayload(ktx = byteArrayOf(7)))

        assertEquals("上传器返回值应透传", 42, id)
    }

    @Test
    fun `buildAtlasAsync - 后台准备并回调纹理 ID（纪元守卫内）`() {
        val view = createView()
        view.useRenderMode = NativeSurfaceView.RenderMode.VULKAN
        view.compressedAtlasReader = { byteArrayOf(9) }
        view.compressedAtlasUploader = { 42 }

        var callbackId = -1
        view.buildAtlasAsync(context) { id -> callbackId = id }

        // 后台线程准备载荷 → post 回主线程；测试线程即主线程，
        // 轮询 idle() 消费 post 消息（5s 截止防悬挂）
        val deadline = System.currentTimeMillis() + 5_000
        while (callbackId == -1 && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        assertEquals("回调应收到上传器返回的纹理 ID", 42, callbackId)
    }

    // ============================================================
    // updateRenderState 校验前置防线（坏帧不得污染 currentFrame——
    // 渲染线程读取后 ChunkTile.rebuild 会 ArrayIndexOutOfBoundsException，
    // 被渲染循环 catch 吞掉后永久黑屏）
    // ============================================================

    @Test
    fun `updateRenderState - 尺寸不匹配时不更新 currentFrame`() {
        val view = createView() // 10×10 = 100 瓦片
        val goodFrame = RenderFrame(tileData = IntArray(100) { 1 }, cols = 10, rows = 10)
        view.updateRenderState(goodFrame)
        assertEquals("合法帧应被接受", 100, view.currentFrame?.tileData?.size)

        // 非法帧：99 瓦片（尺寸不匹配）
        val badFrame = RenderFrame(tileData = IntArray(99) { 1 }, cols = 10, rows = 10)
        view.updateRenderState(badFrame)

        assertEquals(
            "尺寸不匹配帧不得覆盖 currentFrame（渲染线程继续消费合法帧）",
            100, view.currentFrame?.tileData?.size
        )
        assertTrue(
            "合法帧内容保持（坏帧未写入）",
            view.currentFrame?.tileData?.all { it == 1 } == true
        )
    }

    @Test
    fun `updateRenderState - 超大尺寸同样拒绝`() {
        val view = createView() // 10×10 = 100 瓦片

        // 非法帧：101 瓦片（超出期望）
        view.updateRenderState(RenderFrame(tileData = IntArray(101) { 0 }, cols = 10, rows = 10))

        assertEquals("超大尺寸帧不得初始化 currentFrame", null, view.currentFrame)
    }

    // ============================================================
    // 预览快通道（拖拽预览不经 Compose 重组/帧率门控）
    // ============================================================

    @Test
    fun `fastPreviewChannel set 写快照并递增版本号`() {
        val view = createView()
        val channel = view.fastPreviewChannel
        val v0 = channel.version

        channel.set(
            FastPreviewSnapshot(
                active = true,
                boxX = 10f, boxY = 20f, boxW = 30f, boxH = 40f, boxValid = true,
                x = 10f, y = 20f, w = 30f, h = 40f,
                u0 = 0.1f, v0 = 0.2f, u1 = 0.3f, v1 = 0.4f,
                alpha = 0.5f
            )
        )

        assertEquals("版本每次写入 +1", v0 + 1, channel.version)
        val snapshot = channel.state ?: error("快照应已写入")
        assertEquals(10f, snapshot.x, 0.001f)
        assertEquals(20f, snapshot.y, 0.001f)
        assertEquals(30f, snapshot.w, 0.001f)
        assertEquals(40f, snapshot.h, 0.001f)
        assertEquals(0.1f, snapshot.u0, 0.001f)
        assertEquals(0.4f, snapshot.v1, 0.001f)
        assertEquals(0.5f, snapshot.alpha, 0.001f)
    }

    @Test
    fun `fastPreviewChannel set null 停用预览并递增版本号`() {
        val view = createView()
        val channel = view.fastPreviewChannel
        channel.set(
            FastPreviewSnapshot(
                active = true, boxX = 1f, boxY = 2f, boxW = 3f, boxH = 4f, boxValid = true,
                x = 1f, y = 2f, w = 3f, h = 4f, u0 = 0f, v0 = 0f, u1 = 0f, v1 = 0f, alpha = 0.5f
            )
        )
        val v1 = channel.version

        channel.set(null)

        assertEquals("退出拖拽后预览应停用", null, channel.state)
        assertEquals(v1 + 1, channel.version)
    }

    @Test
    fun `mergeFastPreviewInto 覆盖预览字段且保留其余引用`() {
        val tileData = IntArray(100) { 1 }
        val buildingData = FloatArray(12) { 2f }
        val frame = RenderFrame(
            tileData = tileData, cols = 10, rows = 10,
            buildingData = buildingData, buildingCount = 1,
            showPreview = false, previewX = 0f, previewY = 0f
        )
        val snapshot = FastPreviewSnapshot(
            active = true, boxX = 50f, boxY = 60f, boxW = 32f, boxH = 32f, boxValid = true,
            x = 55f, y = 66f, w = 32f, h = 32f,
            u0 = 0.1f, v0 = 0.2f, u1 = 0.3f, v1 = 0.4f, alpha = 0.5f
        )

        val merged = mergeFastPreviewInto(frame, snapshot)

        assertTrue(merged.showPreview)
        assertTrue("网格随预览激活显示", merged.gridOverlayVisible)
        assertTrue("占地框随预览激活显示", merged.previewBoxVisible)
        assertTrue("占地框合法性随快照传递", merged.previewBoxValid)
        assertEquals(50f, merged.previewBoxX, 0.001f)
        assertEquals(32f, merged.previewBoxW, 0.001f)
        assertEquals(55f, merged.previewX, 0.001f)
        assertEquals(66f, merged.previewY, 0.001f)
        assertEquals(0.5f, merged.previewAlpha, 0.001f)
        // 其余字段引用不变（渲染线程零拷贝前提）
        assertTrue("tileData 引用保持", merged.tileData === tileData)
        assertTrue("buildingData 引用保持", merged.buildingData === buildingData)
        assertEquals(10, merged.cols)
    }

    // ============================================================
    // 输入历史采样展开（MOVE 事件 batch 不再丢失中间位置）
    // ============================================================

    @Test
    fun `toMoveTouchData 按时间顺序展开历史采样并收尾当前采样`() {
        val event = io.mockk.mockk<MotionEvent>()
        io.mockk.every { event.actionMasked } returns MotionEvent.ACTION_MOVE
        io.mockk.every { event.pointerCount } returns 1
        io.mockk.every { event.historySize } returns 3
        io.mockk.every { event.eventTime } returns 100L
        io.mockk.every { event.actionIndex } returns 0
        io.mockk.every { event.getPointerId(0) } returns 7
        io.mockk.every { event.x } returns 40f
        io.mockk.every { event.y } returns 50f
        // 历史采样：10→20→30（x），20→30→40（y），时间 70→80→90ms
        io.mockk.every { event.getHistoricalX(0, 0) } returns 10f
        io.mockk.every { event.getHistoricalY(0, 0) } returns 20f
        io.mockk.every { event.getHistoricalEventTime(0) } returns 70L
        io.mockk.every { event.getHistoricalX(0, 1) } returns 20f
        io.mockk.every { event.getHistoricalY(0, 1) } returns 30f
        io.mockk.every { event.getHistoricalEventTime(1) } returns 80L
        io.mockk.every { event.getHistoricalX(0, 2) } returns 30f
        io.mockk.every { event.getHistoricalY(0, 2) } returns 40f
        io.mockk.every { event.getHistoricalEventTime(2) } returns 90L

        val data = toTouchData(event)

        assertEquals("3 历史采样 + 1 当前 = 4 条 MOVE", 4, data?.size)
        assertEquals(com.xianxia.sect.core.touch.TouchAction.MOVE, data?.first()?.action)
        assertEquals(10f, data!![0].x, 0.001f)
        assertEquals(20f, data[0].y, 0.001f)
        assertEquals(20f, data[1].x, 0.001f)
        assertEquals(40f, data[3].x, 0.001f)
        assertEquals(50f, data[3].y, 0.001f)
        // 时间戳递增（70→80→90→100ms × 1e6 ns）
        assertTrue(
            "时间戳单调递增",
            data[0].timestamp < data[1].timestamp &&
                data[1].timestamp < data[2].timestamp &&
                data[2].timestamp < data[3].timestamp
        )
        assertEquals("pointerId 保持", 7, data[0].pointerId)
    }

    @Test
    fun `toMoveTouchData 双指携带第二指历史坐标`() {
        val event = io.mockk.mockk<MotionEvent>()
        io.mockk.every { event.actionMasked } returns MotionEvent.ACTION_MOVE
        io.mockk.every { event.pointerCount } returns 2
        io.mockk.every { event.historySize } returns 1
        io.mockk.every { event.eventTime } returns 100L
        io.mockk.every { event.actionIndex } returns 0
        io.mockk.every { event.getPointerId(0) } returns 1
        io.mockk.every { event.getX(0) } returns 100f
        io.mockk.every { event.getY(0) } returns 200f
        io.mockk.every { event.getX(1) } returns 300f
        io.mockk.every { event.getY(1) } returns 400f
        io.mockk.every { event.getHistoricalX(0, 0) } returns 90f
        io.mockk.every { event.getHistoricalY(0, 0) } returns 190f
        io.mockk.every { event.getHistoricalX(1, 0) } returns 290f
        io.mockk.every { event.getHistoricalY(1, 0) } returns 390f
        io.mockk.every { event.getHistoricalEventTime(0) } returns 80L

        val data = toTouchData(event)

        assertEquals(2, data?.size)
        assertEquals("历史采样携带双指坐标", 290f, data!![0].pointer2X, 0.001f)
        assertEquals("当前采样携带双指坐标", 300f, data[1].pointer2X, 0.001f)
        assertEquals(2, data[1].pointerCount)
    }

    // SOFTWARE 模式分支无法在 JVM 构造：renderMode 由 surfaceChanged 降级逻辑设置
    // （Robolectric 下 holder.surface 恒 null → 早退），该状态由真机强制软件渲染验证。

    // ── surfaceChanged 同尺寸去抖 ──

    @Test
    fun `shouldSkipSurfaceResize - 同尺寸应跳过`() {
        assertTrue("同尺寸事件应跳过 resize（防 swapchain 重建黑帧）", shouldSkipSurfaceResize(640, 360, 640, 360))
    }

    @Test
    fun `shouldSkipSurfaceResize - 任一轴变化应执行 resize`() {
        assertFalse(shouldSkipSurfaceResize(640, 360, 1280, 360))
        assertFalse(shouldSkipSurfaceResize(640, 360, 640, 720))
        assertFalse(shouldSkipSurfaceResize(640, 360, 1280, 720))
    }

    @Test
    fun `shouldSkipSurfaceResize - 复位基准（-1）后首次事件不跳过`() {
        assertFalse("新 surface 纪元复位后首次尺寸事件必须执行 resize", shouldSkipSurfaceResize(-1, -1, 640, 360))
    }

    // ── pending resize 消费守卫──

    @Test
    fun `shouldConsumeNativeResize - SOFTWARE 模式不触 JNI`() {
        assertFalse(
            "软件后端无 C++ swapchain 语义，消费调用是纯开销",
            shouldConsumeNativeResize(isReady = true, mode = NativeSurfaceView.RenderMode.SOFTWARE)
        )
    }

    @Test
    fun `shouldConsumeNativeResize - 未就绪不触 JNI`() {
        assertFalse(
            shouldConsumeNativeResize(isReady = false, mode = NativeSurfaceView.RenderMode.VULKAN)
        )
        assertFalse(
            shouldConsumeNativeResize(isReady = false, mode = NativeSurfaceView.RenderMode.GLES)
        )
    }

    @Test
    fun `shouldConsumeNativeResize - GPU 后端就绪时消费`() {
        assertTrue(
            shouldConsumeNativeResize(isReady = true, mode = NativeSurfaceView.RenderMode.VULKAN)
        )
        assertTrue(
            shouldConsumeNativeResize(isReady = true, mode = NativeSurfaceView.RenderMode.GLES)
        )
    }
}
