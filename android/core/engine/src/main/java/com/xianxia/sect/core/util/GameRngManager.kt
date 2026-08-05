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
        rngMap.replaceAll { partition, _ ->
            DeterministicRng.fromSeed(seed + partition.id)
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
