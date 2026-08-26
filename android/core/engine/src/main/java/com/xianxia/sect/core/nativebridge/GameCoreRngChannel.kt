package com.xianxia.sect.core.nativebridge

import com.xianxia.sect.core.util.NativeRngChannel

/**
 * GameCoreRngChannel — [NativeRngChannel] 的生产实现
 * （绑定 [GameCoreBridge] RNG 分区标量通道，T2.4 AUTHORITATIVE 模式）。
 *
 * 降级契约：引擎未初始化/库未加载时 native 侧返回 0/忽略写——与
 * 双实现并行期的静默降级语义一致（调用方由 NativeEngineFlag.authoritative
 * && isLoaded 门控，正常不会触达降级分支）。
 */
object GameCoreRngChannel : NativeRngChannel {

    override fun nextInt(partitionId: Int): Int =
        GameCoreBridge.nativeRngNextInt(partitionId)

    override fun snapshot(partitionId: Int): Long =
        GameCoreBridge.nativeRngSnapshotPartition(partitionId)

    override fun restore(partitionId: Int, state: Long) {
        GameCoreBridge.nativeRngRestorePartition(partitionId, state)
    }

    override fun initSystemSeed(seed: Long) {
        GameCoreBridge.nativeRngInitSeed(seed)
    }
}
