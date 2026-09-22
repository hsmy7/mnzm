package com.xianxia.sect.core.config

import com.xianxia.sect.core.model.SectLevelClaimRecord

/**
 * 宗门等级周奖励冷却的**唯一**判据（SR-5 C3）。
 *
 * 收敛前三处各写一份、可比对结果分歧：
 * - `GameEngineSectLevelOps.claimSectLevelReward`（领取闸门，`nowMs` 已形参化）；
 * - `GameEngineSectLevelOps.canClaimSectLevelReward`（独立裸读墙钟）；
 * - `GameViewModel.sectLevelRewardClaimable`（UI 徽章，内联硬编码 7 天字面量）。
 *
 * 本对象**零时钟读取**：`nowMs` 一律由调用方注入（SR-5 `WallClock`），
 * 因此"徽章可领但闸门拒领"这类漂移在结构上不可能再出现。
 */
object SectLevelRewardCooldown {

    /** 7 天 = 604,800,000 毫秒 */
    const val WEEK_MS = 7L * 24 * 60 * 60 * 1000

    /**
     * 是否可领：从未领取（null）即可领；否则距上次领取 ≥ [WEEK_MS]。
     *
     * 与收敛前三个消费点的判据逐位一致（`elapsed >= WEEK_MS`）。
     */
    fun isClaimable(lastClaimedAtEpochMs: Long?, nowMs: Long): Boolean =
        lastClaimedAtEpochMs == null || nowMs - lastClaimedAtEpochMs >= WEEK_MS

    /** 下次可领的 epoch 毫秒；从未领取返回 null（无冷却） */
    fun nextClaimableAt(lastClaimedAtEpochMs: Long?): Long? =
        lastClaimedAtEpochMs?.let { it + WEEK_MS }

    /** 从领取记录里取 [level] 的上次领取时刻；无记录返回 null */
    fun lastClaimedAt(records: List<SectLevelClaimRecord>, level: Int): Long? =
        records.find { it.level == level }?.claimedAtEpochMs
}
