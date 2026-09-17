package com.xianxia.sect.core

import android.content.Context
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import com.xianxia.sect.core.render.RenderMetrics
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * CrashHandlerCrashLogTest — 崩溃日志内容段守卫。
 *
 * 守护目标：CrashHandler 落盘的崩溃日志必须携带渲染健康段
 * （[RenderMetrics.formatForCrashReport]，Render Metrics Section 接线）——
 * 本地落盘与远程上传共用同一内容，崩溃归因依赖"崩溃前渲染是否已异常"。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.S]) // API 31（targetSdk 35 超 Robolectric 上限 34，需固定）
class CrashHandlerCrashLogTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var handler: CrashHandler

    @Before
    fun setUp() {
        handler = CrashHandler(context)
        handler.clearAllCrashLogs()
        RenderMetrics.resetForTest()
    }

    @Test
    fun `recordCaughtException - crash log contains render metrics section`() {
        RenderMetrics.totalFrames.set(240)
        RenderMetrics.renderFrameNull.set(2)
        RenderMetrics.foldSpriteOverflowStats(droppedTotal = 7, overflowFrames = 1, degradeFrames = 1)

        handler.recordCaughtException(IllegalStateException("probe-failure"))

        val content = handler.getCrashLogFiles().first().readText()
        assertTrue("崩溃日志必须包含渲染健康段标题", content.contains("=== Render Metrics ==="))
        assertTrue("渲染段必须包含总帧数", content.contains("TotalFrames: 240"))
        assertTrue("渲染段必须包含无效帧输出计数", content.contains("RenderFrameNull: 2"))
        assertTrue("渲染段必须包含精灵溢出计数（R0.3 遥测随携）", content.contains("SpriteOverflowDropped: 7"))
    }

    @Test
    fun `recordCaughtException - zeroed metrics still render the section`() {
        handler.recordCaughtException(IllegalStateException("probe-failure"))

        val content = handler.getCrashLogFiles().first().readText()
        assertTrue(content.contains("=== Render Metrics ==="))
        assertFalse("渲染段不得是失败占位（计数器纯内存读取不应失败）", content.contains("Render Metrics: unavailable"))
    }
}
