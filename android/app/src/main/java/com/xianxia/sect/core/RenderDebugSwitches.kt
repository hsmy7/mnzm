package com.xianxia.sect.core

import android.content.Context
import android.util.Log

/**
 * 渲染后端调试开关（第 6 步：仅 DEBUG 构建生效——发布构建调用方
 * 编译期不引用本对象，且 [forceBackend] 内含 BuildConfig.DEBUG 双保险）。
 *
 * 为 GLES 线程契约/黑名单遥测化的真机验证提供强制路径：
 * 覆写 VulkanPolicy 策略决策，强制会话走指定后端。
 *
 * 用法：`adb shell am start ... --es force_backend gles` 不适用（跨进程）——
 * 调试时在设备上执行：
 * ```
 * adb shell "run-as <pkg> sh -c 'mkdir -p shared_prefs && cat > shared_prefs/render_debug.xml <<EOF ...'"
 * ```
 * 或直接在 debug 变体首次启动后用调试器/prefs 编辑器写入 `force_backend` 键：
 * `0` = 强制 Vulkan、`1` = 强制 GPU GLES、`2` = 强制软件渲染、`-1`/缺省 = 跟随策略。
 */
object RenderDebugSwitches {

    private const val TAG = "RenderDebugSwitches"
    private const val PREFS_NAME = "render_debug"
    private const val KEY_FORCE_BACKEND = "force_backend"

    /** 强制后端值语义（与 NativeBridge.BACKEND_* 对齐 + 2=软件） */
    const val FORCE_VULKAN = 0
    const val FORCE_GLES = 1
    const val FORCE_SOFTWARE = 2
    const val FORCE_NONE = -1

    /**
     * 读取强制后端设置。非 DEBUG 构建/未设置/非法值一律返回 [FORCE_NONE]
     * （跟随 VulkanPolicy 策略——行为 = 开关不存在）。
     */
    // 防御兜底: prefs 读取失败不阻断启动，退回跟随策略（带日志非静默）
    @Suppress("TooGenericExceptionCaught")
    fun forceBackend(context: Context): Int {
        if (!com.xianxia.sect.BuildConfig.DEBUG) return FORCE_NONE
        return try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val value = prefs.getInt(KEY_FORCE_BACKEND, FORCE_NONE)
            if (value in FORCE_VULKAN..FORCE_SOFTWARE) value else FORCE_NONE
        } catch (e: Exception) {
            Log.w(TAG, "forceBackend read failed — following policy: ${e.message}")
            FORCE_NONE
        }
    }
}
