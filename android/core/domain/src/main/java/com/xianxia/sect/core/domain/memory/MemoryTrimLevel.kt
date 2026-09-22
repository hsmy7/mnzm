package com.xianxia.sect.core.domain.memory

/**
 * 内存 trim 档位（MR1-P1.3/D3 统一压力协议——**全仓单一档位面**）。
 *
 * 四档映射 Android 系统级别（`ComponentCallbacks2.TRIM_MEMORY_*` / `onLowMemory`），
 * 归一表唯一落点 = `TrimMemoryBridge.normalize`（app 模块）；本枚举放 `:core:domain`
 * 使 app / core:data（CacheLayer）/ core:engine / feature:game 各消费方共享同一
 * 枚举，禁止任何模块自建第二套档位面（对照 D3「不写第二套分级」）。
 *
 * 档位语义（方案 D3 映射表）：
 * - [NONE]：无压力（C++ 侧水位零值）
 * - [SOFT]：UI_HIDDEN / RUNNING_*（轻）/ MODERATE —— 丢可驱逐缓存与非当前场景引用
 * - [AGGRESSIVE]：RUNNING_CRITICAL / BACKGROUND —— + 按比例逐出、引擎重列表裁剪
 * - [CRITICAL]：COMPLETE / onLowMemory —— + 强制可驱逐全清、账本 shrink 归还 OS
 *
 * 序数即 JNI `nativeMemoryTrim(level)` 的线协议值（0–3），**禁止重排枚举项**。
 */
enum class MemoryTrimLevel {
    NONE,
    SOFT,
    AGGRESSIVE,
    CRITICAL,
}
