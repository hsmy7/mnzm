package com.xianxia.sect.core.util

import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * RNG 分区管理器 — 不同游戏子系统使用独立 PRNG，防止随机序列相互污染。
 *
 * 存档时调用 [exportStates] 导出所有分区状态到 GameData.rngStates，
 * 读档时调用 [restoreStates] 从存档恢复各分区 PRNG 状态。
 *
 * 分区策略参考 DCSS (Dungeon Crawl Stone Soup) 的 RNG 分区设计。
 */
@Singleton
class GameRngManager @Inject constructor() {

    /** 系统级种子（由世界创建时生成），各分区在此基础上偏移 */
    @Volatile
    private var systemSeed: Long = System.currentTimeMillis()

    /** native 委托通道（T2.4 AUTHORITATIVE；null = 本地 PCG 实现） */
    @Volatile
    private var rngChannel: NativeRngChannel? = null

    // F6 对抗性审查加固：ConcurrentHashMap——initSystemSeed 的结构修改
    //（替换分区实例）与引擎线程 getRng/exportStates 的并发读无锁安全
    private val rngMap = ConcurrentHashMap<RngPartition, DeterministicRng>().apply {
        RngPartition.values().forEach { partition ->
            put(partition, DeterministicRng.fromSeed(partition.id.toLong() + systemSeed))
        }
    }

    /** 初始化系统种子（创建新世界时调用） */
    fun initSystemSeed(seed: Long) {
        systemSeed = seed
        val channel = rngChannel
        if (channel != null) {
            // 委托模式：native 侧重播种子，本地实例重建为委托式（无本地态）
            channel.initSystemSeed(seed)
            rebuildPartitions()
        } else {
            rngMap.replaceAll { partition, _ ->
                DeterministicRng.fromSeed(seed + partition.id)
            }
        }
    }

    /**
     * 启用/停用 native 委托（T2.4 AUTHORITATIVE 模式切换时由引擎线程调用）。
     *
     * @param channel 委托通道；传 null 回退本地 PCG 实现（分区按当前
     *        [systemSeed] 重播——回退点两侧状态已由 import/export 对齐）
     */
    fun attachNativeChannel(channel: NativeRngChannel?) {
        rngChannel = channel
        rebuildPartitions()
    }

    /**
     * 停用 native 委托并回退本地 PCG（模式切回 OFF/SHADOW 时调用）。
     *
     * 先经委托通道导出 native 真相源状态，再以该状态播种本地分区——
     * 回退点两侧序列完全对齐，后续本地抽取无缝续接。
     */
    fun detachNativeChannel() {
        if (rngChannel == null) return
        val truthStates = exportStates()
        rngChannel = null
        rebuildPartitions()
        restoreStates(truthStates)
    }

    /** 当前是否处于委托模式（诊断/测试断言用） */
    fun isDelegatingToNative(): Boolean = rngChannel != null

    /** 按当前模式重建分区实例（委托式 / 本地播种） */
    private fun rebuildPartitions() {
        val channel = rngChannel
        RngPartition.values().forEach { partition ->
            rngMap[partition] = if (channel != null) {
                NativeBackedRng(partition.id, channel)
            } else {
                DeterministicRng.fromSeed(systemSeed + partition.id)
            }
        }
    }

    /** 获取指定分区的 PRNG */
    fun getRng(partition: RngPartition): DeterministicRng {
        return rngMap[partition] ?: error("RNG partition $partition not initialized")
    }

    /** 导出所有分区 PRNG 状态到存档 */
    fun exportStates(): Map<Int, Long> {
        return RngPartition.values().associate { it.id to (rngMap[it] ?: error("RNG ${it.name} not found")).snapshot() }
    }

    /** 从存档恢复所有分区 PRNG 状态 */
    fun restoreStates(states: Map<Int, Long>) {
        for ((partitionId, savedState) in states) {
            val partition = RngPartition.values().find { it.id == partitionId } ?: continue
            (rngMap[partition] ?: error("RNG $partition not found")).restore(savedState)
        }
    }
}
