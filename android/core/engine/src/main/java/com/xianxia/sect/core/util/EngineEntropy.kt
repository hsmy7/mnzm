package com.xianxia.sect.core.util

import java.security.SecureRandom

/**
 * EngineEntropy — **新世界熵源**（`mapSeed` 生成专用，非游戏随机流）。
 *
 * ## 存在理由（为什么不是分区 PRNG）
 * `mapSeed` 是整个世界生成的**根种子**：它落盘到 `gameData.mapSeed`，并用于播种
 * 全部游戏分区（`GameRngManager.initSystemSeed`）与 C++ AI 流（`seed + 6×31337`）。
 * 若改用 `GameRngManager` 分区抽取 `mapSeed`，将形成
 * **「分区需要 systemSeed 播种」↔「systemSeed 来自分区抽取」的循环依赖**——
 * `GameRngManager` 构造期即以 `System.currentTimeMillis()` 兜底播种，
 * 该兜底本身就是熵源，把它包装成分区抽取不会增加任何确定性，只会掩盖依赖方向。
 *
 * 而新档/重启的语义要求就是**"新世界"**：不存在"从存档重放 mapSeed"的场景
 * （读档路径的 `mapSeed` 来自存档，绝不在此重新生成）。⇒ 正确的抽象是
 * **显式的会话熵源**，而非伪装成确定性的分区流。
 *
 * ## 纪律
 * 本对象**只允许**用于"创建全新世界"的根种子生成。任何需要"可复现/可归档"的
 * 随机都必须走 [GameRngManager.getRng]——本条由 `RngSourceGuardTest` 与
 * [docs/rng-source-inventory.md] 共同守护。
 *
 * ## 与已删除的 `GameRandom` 的区别
 * `GameRandom`（自建 XorShift128Plus + `@ThreadLocal` 每线程独立流 + 种子 = 挂钟时间、
 * `setSeed()` 生产零调用）自称"支持种子以实现确定性存档"，该承诺从未实现——
 * 属**死抽象**且被误用于弟子属性方差/灵根洗牌/天劫立绘等**决策路径**。
 * 本对象职责单一（根种子熵源）、命名即语义（调用方无法误以为它可复现）、
 * 且不提供任何"看似可播种"的 API 表面。
 */
object EngineEntropy {

    /**
     * 生成新世界的 `mapSeed`。
     *
     * 熵源 = `SecureRandom` 64 位值去高位后与挂钟时间纳秒低 32 位混合：
     * - `SecureRandom` 保证同一纳秒内连续创建两个世界也不会撞种子
     *   （纯挂钟时间在快速"重开档"下可能同值，导致两个新档世界完全相同）
     * - 纳秒分量保证跨进程重启的分布仍随真实时间推进
     *
     * @return 取值范围 `[0, Int.MAX_VALUE)` 的正整数种子（与
     *         `GameData.mapSeed` 的既有值域一致，避免下游取模行为变化）
     */
    fun nextWorldSeed(): Int {
        val mixed = secureRandom.nextLong() xor System.nanoTime()
        return ((mixed and Long.MAX_VALUE) % Int.MAX_VALUE.toLong()).toInt()
    }

    /** 进程级单例（`SecureRandom` 构造有一次性开销，且内部状态需复用） */
    private val secureRandom: SecureRandom by lazy { SecureRandom() }
}
