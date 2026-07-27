package com.xianxia.sect.core.util

/**
 * 使用指定游戏分区 PRNG 打乱集合顺序。
 *
 * 替代 [Iterable.shuffled]（底层使用 [kotlin.random.Random]），
 * 确保随机序列经过 [GameRngManager] 的分区 PRNG，支持存档确定性恢复。
 *
 * @param rng 游戏分区 PRNG 实例（通过 [GameRngManager.getRng] 获取）
 * @return 打乱后的新列表
 */
fun <T> Iterable<T>.shuffled(rng: DeterministicRng): List<T> {
    return map { it to rng.nextInt() }.sortedBy { it.second }.map { it.first }
}
