package com.xianxia.sect.ui.game.sect

import android.os.Looper
import android.view.SurfaceHolder
import com.xianxia.sect.core.platform.SurfaceEventListener
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

/**
 * AndroidSurfaceProvider 防御与事件翻译测试（2026-08-13 平台抽象）。
 *
 * 覆盖 SurfaceProvider 契约状态机：创建+初始尺寸合并 / 尺寸变化 / 销毁 /
 * 重创建序列 / 纪元防 stale（destroy 后旧事件拒绝）/ 10s 初始化超时安全网
 * （触发 / notifyInitCompleted 取消 / destroy 后 stale 超时不触发 / 重置计时）。
 *
 * 平台回调（surfaceCreated/Changed/Destroyed）由测试直接调用模拟——
 * Android 系统回调不可注入，行为契约在 provider 层锁定。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AndroidSurfaceProviderTest {

    /** 事件序列记录监听器（Fake，优先于 mock——可读可断言） */
    private class RecordingListener : SurfaceEventListener {
        val events = mutableListOf<String>()

        override fun onSurfaceAvailable(width: Int, height: Int) {
            events.add("available($width,$height)")
        }

        override fun onSurfaceSizeChanged(width: Int, height: Int) {
            events.add("sizeChanged($width,$height)")
        }

        override fun onSurfaceDestroyed() {
            events.add("destroyed")
        }

        override fun onSurfaceInitTimeout() {
            events.add("initTimeout")
        }
    }

    /** relaxed mock holder（lockCanvas 返回 null → clearSurface 内部跳过，防御吞异常路径） */
    private fun mockHolder(): SurfaceHolder = mockk(relaxed = true)

    /** 完整生命周期激活：created + 首次 changed（可用 800x480） */
    private fun activate(provider: AndroidSurfaceProvider, holder: SurfaceHolder) {
        provider.surfaceCreated(holder)
        provider.surfaceChanged(holder, 0, 800, 480)
    }

    /** 推进主线程虚拟时间（执行到期的 postDelayed 消息） */
    private fun advanceMainLooper(durationSeconds: Long) {
        shadowOf(Looper.getMainLooper()).idleFor(durationSeconds, TimeUnit.SECONDS)
    }

    // ══════════════════════════════
    // 事件翻译状态机
    // ══════════════════════════════

    @Test
    fun `创建后首次 changed - 合并派发 onSurfaceAvailable 并开启纪元`() {
        val holder = mockHolder()
        val listener = RecordingListener()
        val provider = AndroidSurfaceProvider(holder)
        provider.setEventListener(listener)

        provider.surfaceCreated(holder)
        provider.surfaceChanged(holder, 0, 800, 480)

        assertEquals("创建+初始尺寸应合并为单事件", listOf("available(800,480)"), listener.events)
        assertTrue("可用后 surface 应有效", provider.isSurfaceValid)
        assertEquals("宽度应同步", 800, provider.surfaceWidth)
        assertEquals("高度应同步", 480, provider.surfaceHeight)
    }

    @Test
    fun `可用后再次 changed - 派发 onSurfaceSizeChanged`() {
        val holder = mockHolder()
        val listener = RecordingListener()
        val provider = AndroidSurfaceProvider(holder)
        provider.setEventListener(listener)

        activate(provider, holder)
        provider.surfaceChanged(holder, 0, 600, 400)

        assertEquals(
            "后续尺寸变化应派发 sizeChanged（不重复 available）",
            listOf("available(800,480)", "sizeChanged(600,400)"), listener.events
        )
        assertEquals(600, provider.surfaceWidth)
        assertEquals(400, provider.surfaceHeight)
    }

    @Test
    fun `销毁 - 派发 onSurfaceDestroyed 且 surface 失效尺寸清零`() {
        val holder = mockHolder()
        val listener = RecordingListener()
        val provider = AndroidSurfaceProvider(holder)
        provider.setEventListener(listener)

        activate(provider, holder)
        provider.surfaceDestroyed(holder)

        assertEquals(
            listOf("available(800,480)", "destroyed"), listener.events
        )
        assertFalse("销毁后 surface 应失效", provider.isSurfaceValid)
        assertEquals("销毁后宽度清零", 0, provider.surfaceWidth)
        assertEquals("销毁后高度清零", 0, provider.surfaceHeight)
    }

    @Test
    fun `销毁后到达的 stale changed - 拒绝且不派发事件`() {
        val holder = mockHolder()
        val listener = RecordingListener()
        val provider = AndroidSurfaceProvider(holder)
        provider.setEventListener(listener)

        activate(provider, holder)
        provider.surfaceDestroyed(holder)
        provider.surfaceChanged(holder, 0, 999, 999)

        assertEquals(
            "destroy 后无新 surfaceCreated 的 stale changed 必须拒绝",
            listOf("available(800,480)", "destroyed"), listener.events
        )
        assertEquals("stale changed 不得改尺寸", 0, provider.surfaceWidth)
        assertFalse("stale changed 不得恢复有效性", provider.isSurfaceValid)
    }

    @Test
    fun `重新创建序列 - 全新纪元重复可用事件`() {
        val holder = mockHolder()
        val listener = RecordingListener()
        val provider = AndroidSurfaceProvider(holder)
        provider.setEventListener(listener)

        activate(provider, holder)
        val genAfterFirst = provider.generation
        provider.surfaceDestroyed(holder)
        val genAfterDestroy = provider.generation

        provider.surfaceCreated(holder)
        provider.surfaceChanged(holder, 0, 600, 400)

        assertEquals(
            "重创建应重复可用序列（新尺寸）",
            listOf("available(800,480)", "destroyed", "available(600,400)"), listener.events
        )
        assertTrue(provider.isSurfaceValid)
        assertEquals("每次可用递增纪元", genAfterFirst + 1, genAfterDestroy)
        assertEquals("重创建再递增纪元", genAfterDestroy + 1, provider.generation)
    }

    @Test
    fun `纪元 - 首次 changed 与销毁时递增 created 不递增`() {
        val holder = mockHolder()
        val provider = AndroidSurfaceProvider(holder)

        val gen0 = provider.generation
        provider.surfaceCreated(holder)
        assertEquals("created 不递增纪元（尺寸未知，宿主未进入新纪元）", gen0, provider.generation)

        provider.surfaceChanged(holder, 0, 800, 480)
        assertEquals("首次 changed 递增纪元（宿主捕获 currentGen）", gen0 + 1, provider.generation)

        provider.surfaceDestroyed(holder)
        assertEquals("销毁递增纪元（旧纪元异步回调全部失效）", gen0 + 2, provider.generation)
    }

    @Test
    fun `setEventListener null - 解绑后不再派发事件`() {
        val holder = mockHolder()
        val listener = RecordingListener()
        val provider = AndroidSurfaceProvider(holder)
        provider.setEventListener(listener)

        activate(provider, holder)
        provider.setEventListener(null)
        provider.surfaceChanged(holder, 0, 500, 500)
        provider.surfaceDestroyed(holder)

        assertEquals(
            "解绑后平台事件不得派发",
            listOf("available(800,480)"), listener.events
        )
    }

    // ══════════════════════════════
    // 10s 初始化超时安全网
    // ══════════════════════════════

    @Test
    fun `超时 10 秒未完成 - 触发 onSurfaceInitTimeout`() {
        val holder = mockHolder()
        val listener = RecordingListener()
        val provider = AndroidSurfaceProvider(holder)
        provider.setEventListener(listener)

        provider.startInitTimeout()
        advanceMainLooper(11)

        assertEquals(listOf("initTimeout"), listener.events)
    }

    @Test
    fun `notifyInitCompleted - 取消超时不再触发`() {
        val holder = mockHolder()
        val listener = RecordingListener()
        val provider = AndroidSurfaceProvider(holder)
        provider.setEventListener(listener)

        provider.startInitTimeout()
        provider.notifyInitCompleted()
        advanceMainLooper(11)

        assertEquals("完成声明后超时不得触发", emptyList<String>(), listener.events)
    }

    @Test
    fun `销毁后 stale 超时 - 不触发`() {
        val holder = mockHolder()
        val listener = RecordingListener()
        val provider = AndroidSurfaceProvider(holder)
        provider.setEventListener(listener)

        provider.startInitTimeout()
        provider.surfaceDestroyed(holder)
        advanceMainLooper(11)

        // destroy 本身是合法事件；stale 超时不得额外触发（仅 destroy 一条事件）
        assertEquals("跨 surface 纪元的 stale 超时不得触发", listOf("destroyed"), listener.events)
    }

    @Test
    fun `startInitTimeout 重复调用 - 重置计时`() {
        val holder = mockHolder()
        val listener = RecordingListener()
        val provider = AndroidSurfaceProvider(holder)
        provider.setEventListener(listener)

        provider.startInitTimeout()
        advanceMainLooper(6)
        assertEquals("6s 未到 10s 不得触发", emptyList<String>(), listener.events)

        provider.startInitTimeout()
        advanceMainLooper(6)
        assertEquals("重置后 6s 仍未到新 10s", emptyList<String>(), listener.events)

        advanceMainLooper(5)
        assertEquals(
            "重置后累计 11s 触发一次",
            listOf("initTimeout"), listener.events
        )
    }

    @Test
    fun `多次超时 - 仅触发一次`() {
        val holder = mockHolder()
        val listener = RecordingListener()
        val provider = AndroidSurfaceProvider(holder)
        provider.setEventListener(listener)

        provider.startInitTimeout()
        advanceMainLooper(11)
        advanceMainLooper(20)

        assertEquals("超时回调仅触发一次", listOf("initTimeout"), listener.events)
    }
}
