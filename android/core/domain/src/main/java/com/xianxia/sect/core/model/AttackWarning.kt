package com.xianxia.sect.core.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

/**
 * AI宗门进攻预警，持久化于 GameData 中跨存档保存。
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
 * 预警阶段：谴责（进攻前6个月）→ 战书（进攻前3个月）→ 到期执行战斗
 */
@Serializable
enum class WarningStage {
    /** 谴责阶段：被xx宗门谴责 */
    DENUNCIATION,
    /** 战书阶段：xx宗门对你发起了进攻 */
    WAR_DECLARATION
}
