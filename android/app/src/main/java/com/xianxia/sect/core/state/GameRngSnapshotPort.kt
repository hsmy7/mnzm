package com.xianxia.sect.core.state

import com.xianxia.sect.core.util.GameRngManager
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [RngSnapshotPort] 的 App 层装配实现：委托 [GameRngManager] 的
 * exportStates/restoreStates（8 分区 PRNG 状态快照/恢复）。
 *
 * 依赖方向合规：接口定义于 :core:domain，本实现位于 :app（可依赖
 * :core:engine 的 GameRngManager），经 Hilt 注入到 [GameStateStoreImpl]。
 */
@Singleton
class GameRngSnapshotPort @Inject constructor(
    private val gameRngManager: GameRngManager
) : RngSnapshotPort {

    override fun snapshot(): Map<Int, Long> = gameRngManager.exportStates()

    override fun restore(states: Map<Int, Long>) = gameRngManager.restoreStates(states)
}
