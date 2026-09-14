package com.xianxia.sect.core.engine

import com.xianxia.sect.core.util.RngPartition

/**
 * 弟子交谈决策类随机抽取原语（W4-A·A5——RNG 阶段 3·弟子侧）。
 *
 * ## 为什么引擎侧签发
 * `GameRngManager` 线程契约：委托模式下全部状态读写直达 C++ PCG 分区，
 * **必须在引擎线程调用**（jniRequireEngineThread 断言）。`DiscipleChatDialog`
 * 的决策类抽取（交谈树/结果分支/效果增量——结果经 `updateDisciple` 写入弟子
 * `cultivation`/`skills` ⇒ ADR §8 判定为决策类，R3 违规点）原在 UI 线程消费
 * 全局 `Random.Default`；本文件把抽取移入引擎上下文、源改为专用 [RngPartition.CHAT]
 * 分区（用户时序独立流，不扰动任何既有分区抽取序——红线 1），UI 只拿到抽取结果。
 *
 * ## 形参化路线（ADR 阶段 3 口径修正）
 * ADR 并未要求 ActionId + C++ 事务——落地形态是"按调用点粒度下沉"，硬要求
 * 只有 R1（影响状态的随机必须取自 GameRngManager 分区流）。本原语即
 * "形参化/分区化"路线的落地：零协议改动，`RngPartition.CHAT` 经
 * exportStates/restoreStates 随档持久化（rngStates 10 号键；旧档缺失按
 * systemSeed+10 播种，MISSION 同款恢复语义）。
 */

/** 抽取 `[0, bound)` 内一个整数（交谈决策类；引擎上下文 + CHAT 分区）。 */
suspend fun GameEngine.chatDraw(bound: Int): Int =
    engineContextDispatcher.withEngineContext {
        gameRngManager.getRng(RngPartition.CHAT).nextInt(bound)
    }

/** 抽取 `[from, until)` 内一个整数（交谈决策类；引擎上下文 + CHAT 分区）。 */
suspend fun GameEngine.chatDraw(from: Int, until: Int): Int =
    engineContextDispatcher.withEngineContext {
        // DeterministicRng 无区间变体——from + [0, until-from)（kotlin.random.Random 同式）
        from + gameRngManager.getRng(RngPartition.CHAT).nextInt(until - from)
    }

/** 抽取 `[from, until)` 内一个 Double（交谈决策类；引擎上下文 + CHAT 分区）。 */
suspend fun GameEngine.chatDrawDouble(from: Double, until: Double): Double =
    engineContextDispatcher.withEngineContext {
        from + gameRngManager.getRng(RngPartition.CHAT).nextDouble() * (until - from)
    }
