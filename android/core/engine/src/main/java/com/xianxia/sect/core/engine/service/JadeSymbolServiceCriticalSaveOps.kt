package com.xianxia.sect.core.engine.service

import com.xianxia.sect.core.state.CriticalSaveKind

// ── 玉符涉钱事件面（自 JadeSymbolService 拆出：detekt TooManyFunctions 收敛外移，同包扩展）──

/**
 * 玉符流水 append（涉钱事件，方案 §2.5）已发生——请求立即落盘。
 *
 * [JadeSymbolService] 全部余额变动出口（[JadeSymbolService.deduct]/
 * [JadeSymbolService.settleGrants]/[JadeSymbolService.grantFromAd] 与
 * `GameEngineJadePurchaseOps` 的 native 臂）在落账成功后调用；
 * 零余额变化路径（checkpoint/跨天重置/期初开账）不调用。
 * 非挂起、任意线程可调：落盘编排（合并窗/立即冲刷）由 SaveOrchestrator 承担。
 */
internal fun JadeSymbolService.notifyMoneyLedgerChanged() {
    criticalSaveEvents.notify(CriticalSaveKind.MONEY)
}
