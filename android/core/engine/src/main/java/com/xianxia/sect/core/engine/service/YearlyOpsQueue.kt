package com.xianxia.sect.core.engine.service

import com.xianxia.sect.core.state.MutableGameState
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.locks.ReentrantLock

/**
 * L3a 年变延迟队列 — 引擎内存态 FIFO 操作队列。
 *
 * 年变结算拆分"立即组/延迟组"：延迟组操作入队，由 [drain] 在后续 tick
 * 按时间预算分摊执行（引擎线程消费；flush-on-save 从存档线程调用 forceDrain）。
 *
 * 并发语义（对抗性审查 F2/发现3 修复）：[drain] 与 [forceDrain] 经 [consumerLock]
 * 互斥——**同一时刻至多一个消费者**，FIFO 顺序恒成立（不存在 op2 先于 op1 执行的
 * 交错），且 forceDrain 返回时队列必空（它独占消费到空，无 in-flight op）。
 * 入队/清空走 CLQ 无锁路径：enqueue 在年变 T1 事务内（持 transactionLock）调用，
 * 不取 consumerLock——避免"stateStore → consumer"与"consumer → stateStore"
 * 锁序环（死锁）。"快照 ⇒ 队列已空"不变量由 [CultivationEventProcessor.flushYearlyOpsQueue]
 * 的空事务屏障（确保 T1 含入队已完成）+ 本队列独占清空共同闭合。
 *
 * 崩溃语义：队列为进程内态，崩溃即丢。延迟组全部有差值判据自愈语义
 * （下年自动补跑），且 flush-on-save 保证"存档快照 ⇒ 队列已空"。
 */
internal class YearlyOpsQueue {

    private val deque = ConcurrentLinkedQueue<MutableGameState.() -> Unit>()

    /** 消费者互斥锁：同刻至多一个 drain/forceDrain 持有（引擎 tick vs 存档线程）。 */
    private val consumerLock = ReentrantLock()

    /** 入队一个延迟操作（FIFO，保持年变原相对序）。 */
    fun enqueue(op: MutableGameState.() -> Unit) {
        deque.add(op)
    }

    /** 清空队列（年变双触发防重复执行的防御入口）。 */
    fun clear() {
        deque.clear()
    }

    /** 队列中待执行操作数。 */
    val size: Int get() = deque.size

    /**
     * 预算内 drain：FIFO 逐 op 执行，**至少执行 1 个 op**（首个 op 超预算也执行），
     * 之后若累计超预算则停止，剩余留在队列下个 tick 继续。
     *
     * @param timeBudgetMs 时间预算（ms）
     * @param runOp 执行单个 op 的方式（生产：`{ op -> stateStore.update { op() } }`）
     * @return true = 队列已清空；false = 预算耗尽仍有剩余
     */
    fun drain(timeBudgetMs: Long, runOp: (MutableGameState.() -> Unit) -> Unit): Boolean {
        consumerLock.lock()
        try {
            return drainUnlocked(timeBudgetMs, runOp)
        } finally {
            consumerLock.unlock()
        }
    }

    /**
     * 无预算全量清空（month!=1 跨月兜底 / 存档前 flush）。
     *
     * 与 [drain] 经 [consumerLock] 互斥：等待对方完成全部 in-flight op 后才
     * 独占消费到空——返回时队列必空且无 in-flight，FIFO 顺序恒成立。
     *
     * @param runOp 执行单个 op 的方式（同 [drain]）
     * @return true = 队列已清空
     */
    fun forceDrain(runOp: (MutableGameState.() -> Unit) -> Unit): Boolean {
        consumerLock.lock()
        try {
            return forceDrainUnlocked(runOp)
        } finally {
            consumerLock.unlock()
        }
    }

    /** [drain] 锁内实现（调用方已持有 [consumerLock]）。 */
    @Suppress("ReturnCount") // 守卫风格：队列空早退 + 预算耗尽停止 + poll 空早退，多 return 为预算判定守卫
    private fun drainUnlocked(timeBudgetMs: Long, runOp: (MutableGameState.() -> Unit) -> Unit): Boolean {
        var first = true
        val deadline = System.currentTimeMillis() + timeBudgetMs
        while (true) {
            if (deque.isEmpty()) return true
            // 预算检查先于 poll：首个 op 无条件执行（预算不足也执行），
            // 之后若已超预算则停止，剩余 op 留在队列下个 tick 继续
            if (!first && System.currentTimeMillis() >= deadline) return false
            val op = deque.poll() ?: return true
            runOp(op)
            first = false
        }
    }

    /** [forceDrain] 锁内实现（调用方已持有 [consumerLock]）。 */
    private fun forceDrainUnlocked(runOp: (MutableGameState.() -> Unit) -> Unit): Boolean {
        while (true) {
            val op = deque.poll() ?: return true
            runOp(op)
        }
    }
}
