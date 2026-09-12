package com.xianxia.sect.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.UninitializedPropertyAccessException

/**
 * TapTapCrashGuard.isSuppressible 判定逻辑单元测试。
 *
 * 覆盖：TapTap lateinit 命中 / 混淆变体 / 无 taptap 帧不误吞 /
 * 非 lateinit 不误吞 / 包装 cause 链命中 / 普通异常放行。
 * 纯 JVM，不触碰 android.util.Log（isSuppressible 无 Android 依赖）。
 */
class TapTapCrashGuardTest {

    /** 构造带指定栈帧的异常（模拟对应调用链）。 */
    private fun withFrames(
        throwable: Throwable,
        vararg classNames: String
    ): Throwable {
        throwable.stackTrace = classNames.mapIndexed { i, cls ->
            StackTraceElement(cls, "m$i", "${cls.substringAfterLast('.')}.kt", i + 1)
        }.toTypedArray()
        return throwable
    }

    private val tapTapFrames = arrayOf(
        "com.taptap.sdk.kit.internal.TapTapKit",
        "com.taptap.sdk.kit.internal.utils.TapToast",
        "com.taptap.sdk.kit.internal.extensions.SysExtKt"
    )

    @Test
    fun `TapTap lateinit 崩溃 - 应抑制`() {
        val t = withFrames(
            UninitializedPropertyAccessException("lateinit property context has not been initialized"),
            *tapTapFrames
        )
        assertTrue("lateinit + taptap 栈帧应命中", TapTapCrashGuard.isSuppressible(t))
    }

    @Test
    fun `混淆后变体 - 应抑制`() {
        val t = withFrames(
            UninitializedPropertyAccessException("lateinit property context has not been initialized"),
            "com.taptap.sdk.kit.internal.TapTapKit"
        )
        assertTrue("混淆包名仍含 taptap 应命中", TapTapCrashGuard.isSuppressible(t))
    }

    @Test
    fun `lateinit 异常但无 taptap 栈帧 - 不抑制`() {
        val t = withFrames(
            UninitializedPropertyAccessException("lateinit property context has not been initialized"),
            "com.xianxia.sect.ui.MainActivity"
        )
        assertFalse("无 taptap 栈帧不应误吞", TapTapCrashGuard.isSuppressible(t))
    }

    @Test
    fun `taptap 栈帧但非 lateinit 异常 - 不抑制`() {
        val t = withFrames(NullPointerException("something else"), *tapTapFrames)
        assertFalse("taptap 帧但非 lateinit 不应抑制", TapTapCrashGuard.isSuppressible(t))
    }

    @Test
    fun `包装 cause 链中的 lateinit - 应抑制`() {
        val cause = withFrames(
            UninitializedPropertyAccessException("lateinit property context has not been initialized"),
            *tapTapFrames
        )
        val wrapped = RuntimeException("wrapped by coroutine", cause)
        assertTrue("cause 链命中应抑制", TapTapCrashGuard.isSuppressible(wrapped))
    }

    @Test
    fun `普通异常 - 不抑制`() {
        val t = withFrames(RuntimeException("boom"), "com.xianxia.sect.core.SomeClass")
        assertFalse("普通异常应放行", TapTapCrashGuard.isSuppressible(t))
    }

    @Test
    fun `仅消息含 lateinit 且 taptap 帧 - 应抑制`() {
        // 兜底：异常类型非 UninitializedPropertyAccessException（如混淆/代理包装）
        // 但消息含 lateinit 且栈帧含 taptap 也应命中。
        val t = withFrames(IllegalStateException("lateinit property context has not been initialized"), *tapTapFrames)
        assertTrue("消息含 lateinit 应命中", TapTapCrashGuard.isSuppressible(t))
    }
}
