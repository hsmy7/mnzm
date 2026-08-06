package com.xianxia.sect.core.model

import androidx.annotation.Keep
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber
import kotlinx.serialization.protobuf.ProtoPacked

/**
 * 每日签到存档状态（功能已于 2026-08-07 移除）。
 *
 * 该字段仅保留用于旧存档兼容——禁止任何新代码读写，
 * 如需移除须走数据库 Migration 流程。
 */
@Keep
@Serializable
data class SignInState(
    @ProtoPacked @ProtoNumber(1) val claimedDays: List<Int> = emptyList(),
    @ProtoNumber(2) val currentMonth: Int = 0,
    @ProtoNumber(3) val currentYear: Int = 0,
    @ProtoPacked @ProtoNumber(4) val claimedMilestones: List<Int> = emptyList()
)
