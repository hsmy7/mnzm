package com.xianxia.sect.core.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

/**
 * AI宗门进攻预警，持久化于 GameData 中跨存档保存。
 *
 * 单级语义：生成预警后下个月直接进攻（stage 恒为 [WarningStage.WAR_DECLARATION]）。
 */
@Serializable
data class AttackWarning(
    @ProtoNumber(1) val warningId: String,
    @ProtoNumber(2) val attackerSectId: String,
    @ProtoNumber(3) val attackerSectName: String,
    @ProtoNumber(4) val stage: WarningStage,
    /** 正式进攻的游戏绝对月份（gameYear * 12 + gameMonth） */
    @ProtoNumber(5) val attackMonth: Int,
    @ProtoNumber(6) val createdAtMonth: Int
)

/**
 * 预警阶段：单级"即将进攻"（生成后下月直接进攻 → 到期执行战斗）。
 *
 * [DENUNCIATION] 为历史阶段（旧档兼容，protobuf 持久化需保留枚举值），
 * 新代码不再生成；旧档残留预警由结算收敛为 [WAR_DECLARATION]。
 */
@Serializable
enum class WarningStage {
    /** 谴责阶段（历史遗留）：旧档兼容保留，新代码不再生成 */
    DENUNCIATION,
    /** 战书阶段：即将进攻，到期执行战斗 */
    WAR_DECLARATION
}
