package com.xianxia.sect.core.state.reversechannel

import com.xianxia.sect.core.state.ReverseChannelPolicy
import com.xianxia.sect.core.state.ReverseChannelPolicy.Domain

/**
 * W4-C 批次（战斗与世界协议轴）的反向通道关闭数据。
 *
 * ## 为什么按批切文件（W4-00 并行前置批）
 * 三个并行批次在收尾各自域时都需要「新增关闭单元 + 摘除在册保留字段 + 改写域级结论」。
 * 原始形态是 `ReverseChannelPolicy` 内三处**单一列表字面量** ⇒ 三批必然编辑同一段文本。
 * 切分后每批只改自己这一个文件，`ReverseChannelPolicy` 只做聚合、此后冻结。
 * 见 `docs/parallel-batches-w4/README.md` §3.1 项 5 与 §5.3。
 *
 * 🔴 **只有 W4-C 可写本文件**（域：BATTLE / SECRET_REALM）。
 */

/** 本批域的**已关闭**传输单元（逐域关闭清单的 W4-C 分片）。 */
internal val w4CClosedUnits: List<ReverseChannelPolicy.ClosedUnit> = listOf(
    // SECRET_REALM（洞府探索整族死链）
    gameDataField(Domain.SECRET_REALM, "cultivatorCaves"),
    // BATTLE
    gameDataField(Domain.BATTLE, "worldLevelLastRefreshMonth"),
    gameDataField(Domain.BATTLE, "exploredSects"),
    gameDataField(Domain.BATTLE, "usedTeamNumbers"),
    gameDataField(Domain.BATTLE, "activeAttackWarnings"),
    gameDataField(Domain.BATTLE, "sectAttackCooldowns"),
    gameDataField(Domain.BATTLE, "signInState"),
    topLevelSection(Domain.BATTLE, ReverseChannelPolicy.SECTION_LOCKED_BEAST_IDS),
)

/** 本批域的**在册保留**gameData 字段（不可关闭；口径见 `ReverseChannelPolicy.transportedGameDataFields`）。 */
internal val w4CRetainedGameDataFields: Set<String> = linkedSetOf(
    // 战斗/探索残差与运行态
    "worldLevels", "sectBattleRecords", "sectDetails", "scoutInfo",
    // 秘境残差与运行态
    "secretRealmState", "secretRealmSession", "secretRealmAITeams",
    "secretRealmCooldownYear", "caveExplorationTeams", "aiCaveTeams",
)

/** 本批域的**域级审计结论证据**（`文件:行 函数` 形式；CLOSED 域必须为空）。 */
internal val w4CDomainEvidence: Map<Domain, List<String>> = mapOf(
    Domain.BATTLE to listOf(
        "CombatService.kt:78 — native 战斗后伤亡残差 + 宗门战纯 Kotlin",
        "GameEngineWorldBattleOps.kt:188/:288 — 关卡胜利/失败事务（魂力与属性增长在 Kotlin）",
        "GameEngineBattleOps.kt:66/:274/:339/:366 — 宗门战战后段（奖励入账/战史不下沉）",
        "GameEngineExplorationNativeOps.kt:134 — native 转发前的战前结算（无条件执行）",
    ),
    Domain.SECRET_REALM to listOf(
        "GameEngineSecretRealmOps.kt:57 — 出发换岗清理与弟子槽位状态",
        "GameEngineSecretRealmNativeOps.kt:100 rejectIfSecretRealmExpired — 到期兜底关闭（无门控）",
        "GameEngineSecretRealmNativeOps.kt:263 recordSecretRealmBattleReport — 战报写回",
    ),
)
