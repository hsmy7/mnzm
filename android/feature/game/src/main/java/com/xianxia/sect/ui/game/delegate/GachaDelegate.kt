package com.xianxia.sect.ui.game.delegate

import android.util.Log
import com.xianxia.sect.core.engine.GameEngine
import com.xianxia.sect.core.engine.domain.gacha.GachaFacade
import com.xianxia.sect.core.engine.domain.gacha.GachaPullResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job

/**
 * 寻访 UI 委托：把抽卡请求转发到 [GachaFacade]（事务与双臂在 Facade 内）。
 *
 * 线程口径：[GachaFacade.pullOnce] / [GachaFacade.pullTen] 是 suspend 且要求在引擎线程
 * 上下文内调用——回退臂要在 `GameStateStore` 事务里扣灵石、掷点、写四本账，主线程直调
 * 会被状态存储的架构监护判错。故本类统一经 [GameEngine.launchOnEngine] 派发，
 * UI 层不得自行 `launch`，也不得绕过本委托直调门面。
 *
 * 结果以回调回传（在引擎线程上执行），由 ViewModel 落入只读 `StateFlow` 供 Compose 订阅；
 * 池 id 一律取门面的默认参数（`standard` 常驻池），UI 侧不写第二份字面量。
 */
class GachaDelegate(
    private val gameEngine: GameEngine,
    private val gachaFacade: GachaFacade,
) {
    /** 单抽（一次寻访）。 */
    fun pullOnce(onResult: (GachaPullResult) -> Unit): Job =
        pull(onResult) { facade -> facade.pullOnce() }

    /** 十连（一笔事务内顺序执行 10 次单抽语义，原子扣费与跨十连连续保底）。 */
    fun pullTen(onResult: (GachaPullResult) -> Unit): Job =
        pull(onResult) { facade -> facade.pullTen() }

    /**
     * 引擎线程内执行一次寻访并把结果交回界面。
     *
     * 兜 `Exception` 是为了不让「回调没回来」变成界面级软锁：ViewModel 在请求发出时把
     * 招募按钮置灰，只有这条回调能把它放开（历战/炼丹侧的忙碌态收口是同一手法）。
     * 兜底仍以 [GachaPullResult.Failure] 形态回传——异常只可能发生在门面内部，
     * 而门面的两条臂都「校验全覆盖 ⇒ 中途无失败分支」，不存在扣了灵石没出货的半成品。
     */
    @Suppress("TooGenericExceptionCaught") // 防御兜底：异常源不可枚举，降级为结果码而非静默吞
    private fun pull(
        onResult: (GachaPullResult) -> Unit,
        block: suspend (GachaFacade) -> GachaPullResult,
    ): Job = gameEngine.launchOnEngine {
        val result = try {
            block(gachaFacade)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            Log.w(TAG, "寻访请求在引擎线程异常终止", error)
            GachaPullResult.Failure(REASON_ENGINE_FAULT)
        }
        onResult(result)
    }

    companion object {
        private const val TAG = "GachaDelegate"

        /** 引擎线程异常终止的结果码（界面侧走兜底文案，不外泄内部堆栈） */
        const val REASON_ENGINE_FAULT = "EngineFault"
    }
}
