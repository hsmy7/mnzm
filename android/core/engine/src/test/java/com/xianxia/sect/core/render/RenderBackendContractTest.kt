package com.xianxia.sect.core.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RenderBackend 接口契约测试（2026-08-10 新增，WP2）。
 *
 * FakeBackend 记录调用序列，锁定渲染循环（RenderThread）的标准用法模式：
 * - 相机变化时 setCamera 先于 renderFrame（脏标记驱动，非每帧）
 * - 非脏帧直接 renderFrame——实现类必须缓存相机值
 * - renderFrame 失败返回 false 且循环可继续
 * - release 收尾，之后循环不再调用
 *
 * 双后端实现（Vulkan/Canvas）与未来的 iOS Metal 后端都必须遵守此契约。
 */
class RenderBackendContractTest {

    /** 记录调用序列的假后端 */
    private class RecordingBackend : RenderBackend {
        val calls = mutableListOf<String>()
        var failRender = false

        override fun resize(width: Int, height: Int) {
            calls.add("resize($width,$height)")
        }

        override fun setCamera(camX: Float, camY: Float, scale: Float, viewportW: Int, viewportH: Int) {
            calls.add("setCamera($camX,$camY,$scale,$viewportW,$viewportH)")
        }

        override fun renderFrame(frame: RenderFrame, viewportW: Int, viewportH: Int): Boolean {
            calls.add("renderFrame($viewportW,$viewportH)")
            return !failRender
        }

        override fun release() {
            calls.add("release")
        }
    }

    @Test
    fun `标准循环序列 - setCamera 先于 renderFrame 且 release 收尾`() {
        val backend = RecordingBackend()
        val frame = RenderFrame(IntArray(64) { 0 }, cols = 8, rows = 8)

        backend.resize(800, 600)
        backend.setCamera(10f, 20f, 1.5f, 800, 600)
        backend.renderFrame(frame, 800, 600)
        backend.release()

        assertEquals(
            "标准序列: resize → setCamera → renderFrame → release",
            listOf(
                "resize(800,600)",
                "setCamera(10.0,20.0,1.5,800,600)",
                "renderFrame(800,600)",
                "release"
            ),
            backend.calls
        )
    }

    @Test
    fun `非脏帧 - 相机未变化时直接 renderFrame 不重复推送`() {
        // 渲染循环仅在 cameraDirty 标记时调用 setCamera；非脏帧直接 renderFrame——
        // 实现类必须缓存相机值（Canvas 合并 / Vulkan JNI 状态持久）
        val backend = RecordingBackend()
        val frame = RenderFrame(IntArray(4) { 0 }, cols = 2, rows = 2)

        backend.setCamera(1f, 2f, 1f, 640, 640)
        backend.renderFrame(frame, 640, 640)
        backend.renderFrame(frame, 640, 640)

        assertEquals(
            "非脏帧不得重复推送 setCamera",
            listOf(
                "setCamera(1.0,2.0,1.0,640,640)",
                "renderFrame(640,640)",
                "renderFrame(640,640)"
            ),
            backend.calls
        )
    }

    @Test
    fun `renderFrame 失败 - 返回 false 且循环可继续下一帧`() {
        val backend = RecordingBackend().apply { failRender = true }
        val frame = RenderFrame(IntArray(9) { 0 }, cols = 3, rows = 3)

        assertFalse("失败帧返回 false", backend.renderFrame(frame, 400, 400))

        // 渲染循环记录指标后进入下一帧——失败不得污染后续调用
        backend.failRender = false
        assertTrue("恢复后可继续渲染", backend.renderFrame(frame, 400, 400))
        assertEquals(
            listOf("renderFrame(400,400)", "renderFrame(400,400)"),
            backend.calls
        )
    }

    @Test
    fun `RenderFrame 默认值 - 双后端消费同一契约`() {
        val frame = RenderFrame(IntArray(16) { 1 }, cols = 4, rows = 4)

        assertEquals(0f, frame.camX, 0.001f)
        assertEquals(0f, frame.camY, 0.001f)
        assertEquals(1f, frame.scale, 0.001f)
        assertNull(frame.buildingData)
        assertEquals(0, frame.buildingCount)
        assertTrue(frame.buildingVisible)
        assertFalse(frame.showPreview)
        // 预览调色默认值（建造模式绿色调色）——双后端共享，变更需两端同步
        assertEquals(0.25f, frame.previewTintRed, 0.001f)
        assertEquals(1.0f, frame.previewTintGreen, 0.001f)
        assertEquals(0.25f, frame.previewTintBlue, 0.001f)
        assertEquals(0.5f, frame.previewAlpha, 0.001f)
    }

    @Test
    fun `渲染缩放契约 - 接口保持物理像素参数语义`() {
        // 2026-08-14 平板省电：render scale 是后端内部像素密度参数（Vulkan 离屏
        // 目标 / Canvas 降采样帧缓冲），RenderBackend 接口继续以物理视口像素
        // 为契约——相机/命中测试/世界可视范围全部不受缩放影响。
        // 本测试锁定：viewportW/H 语义恒为物理像素（实现类内部自行缩放）。
        val backend = RecordingBackend()
        val frame = RenderFrame(IntArray(64) { 0 }, cols = 8, rows = 8)

        backend.setCamera(0f, 0f, 1f, 2560, 1600)
        backend.renderFrame(frame, 2560, 1600)

        assertEquals(
            "平板物理视口 2560×1600 必须原样传入接口（缩放由实现内部处理）",
            listOf(
                "setCamera(0.0,0.0,1.0,2560,1600)",
                "renderFrame(2560,1600)"
            ),
            backend.calls
        )
    }
}
