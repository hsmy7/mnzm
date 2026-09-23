package com.xianxia.sect.core.memory

/**
 * MemoryBudgetView — 内存子系统只读预算快照（MR4-P4.4/D3 可观测面）。
 *
 * 合并三源只读数据，**不写第二套分级/阈值**（阈值轴仍归 `GCOptimizer`/
 * `DynamicMemoryManager`）：
 * 1. `GpuAllocator.stats()`（GPU 分配层；经 JNI `nativeGetMemoryStats`）
 * 2. `TextureCache.stats()`（键控纹理条目）
 * 3. JVM 堆 / Native Heap（`MemoryMonitor` / `Debug`）
 *
 * ## 线程面（docs/threading-contract.md 表四 MemoryStats 读通道）
 * 渲染线程在 beginFrame 帧边界发布不可变快照（原子/互斥替换）；本对象
 * 只读 JNI 返回的拷贝数组，**禁止**同步回读渲染后端（表三红线）。
 * Debug UI 从任意线程调用 [snapshot] 均安全。
 */
data class MemoryBudgetView(
    /** GPU 已用字节（VMA/VkDeviceMemory 层；gate OFF 或未 init 时为 0） */
    val gpuUsedBytes: Long,
    /** GPU 预算字节（budget 扩展或堆容量估算） */
    val gpuBudgetBytes: Long,
    /** GPU 存活 block 数 */
    val gpuBlockCount: Int,
    /** GPU 存活分配数 */
    val gpuAllocCount: Int,
    /** TextureCache 表内条目（含 pendingDestroy 滞留） */
    val textureEntries: Int,
    /** TextureCache pinned 条目 */
    val texturePinned: Int,
    /** TextureCache 退役未完成条目 */
    val texturePendingDestroy: Int,
    /** TextureCache 累计真实上传次数 */
    val textureUploads: Long,
    /** TextureCache 累计命中次数 */
    val textureHits: Long,
) {
    companion object {
        /**
         * 读 JNI 快照（[com.xianxia.sect.core.nativebridge.NativeBridge.nativeGetMemoryStats]）。
         * 数组布局：[gpuUsed, gpuBudget, gpuBlocks, gpuAllocs, texEntries, texPinned,
         * texPending, texUploads, texHits]（9 槽；短数组按 0 填充 = 未初始化）。
         */
        fun snapshot(): MemoryBudgetView {
            val raw = com.xianxia.sect.core.nativebridge.NativeBridge.nativeGetMemoryStats()
            fun at(i: Int): Long = if (i < raw.size) raw[i] else 0L
            return MemoryBudgetView(
                gpuUsedBytes = at(0),
                gpuBudgetBytes = at(1),
                gpuBlockCount = at(2).toInt(),
                gpuAllocCount = at(3).toInt(),
                textureEntries = at(4).toInt(),
                texturePinned = at(5).toInt(),
                texturePendingDestroy = at(6).toInt(),
                textureUploads = at(7),
                textureHits = at(8),
            )
        }

        private fun mb(bytes: Long): String = "%.1f MB".format(bytes / (1024.0 * 1024.0))

        /** Debug 页分类 MB 文本（P4.4 acceptance：可见 GPU/纹理分类） */
        fun formatDebugLines(v: MemoryBudgetView = snapshot()): List<String> = listOf(
            "GPU: ${mb(v.gpuUsedBytes)} / ${mb(v.gpuBudgetBytes)}" +
                " (blk=${v.gpuBlockCount} alloc=${v.gpuAllocCount})",
            "Tex: ${v.textureEntries} entries pinned=${v.texturePinned}" +
                " pending=${v.texturePendingDestroy}",
            "Tex IO: uploads=${v.textureUploads} hits=${v.textureHits}",
        )
    }
}
