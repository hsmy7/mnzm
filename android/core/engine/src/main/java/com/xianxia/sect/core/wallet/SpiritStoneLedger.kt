package com.xianxia.sect.core.wallet

import com.xianxia.sect.core.util.DomainLog
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 灵石流水账本 — 内存环形缓冲区。
 *
 * 保留最近的 [MAX_RECORDS] 条流水记录，用于调试、审计追踪和数据分析。
 * 不上 UI、不持久化，零侵入。
 *
 * ## 线程安全
 * 该账本仅在 GameEngine-Thread（单线程）上访问，不需要额外同步。
 * 所有记录通过 [SpiritStoneWallet] 写入，确保顺序一致性。
 */
@Singleton
class SpiritStoneLedger @Inject constructor() {
    companion object {
        private const val MAX_RECORDS = 1000
    }

    private val buffer = arrayOfNulls<SpiritStoneTransaction>(MAX_RECORDS)
    private var head = 0
    private var count = 0

    val recentTransactions: List<SpiritStoneTransaction>
        get() = (0 until count).map { requireNotNull(buffer[(head + it) % MAX_RECORDS]) }

    fun record(tx: SpiritStoneTransaction) {
        buffer[(head + count) % MAX_RECORDS] = tx
        if (count < MAX_RECORDS) count++
        else head = (head + 1) % MAX_RECORDS
    }

    fun queryBySource(source: String): List<SpiritStoneTransaction> = recentTransactions.filter { it.source == source }
    fun queryByReason(reason: String): List<SpiritStoneTransaction> = recentTransactions.filter { it.reason == reason }
    fun queryByTimeRange(from: Long, to: Long): List<SpiritStoneTransaction> = recentTransactions.filter { it.timestamp in from..to }
    fun queryByGrade(grade: String): List<SpiritStoneTransaction> = recentTransactions.filter { it.grade.name == grade }
    fun clear() { head = 0; count = 0 }
    val size: Int get() = count
}
