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
    // 🔴 §2.80 起为空——worldLevels（世界胜利接线）/sectBattleRecords（攻宗三分支
    // 接线）/sectDetails/scoutInfo（侦查 + 交易懒刷新接线）已随 W4-D/§2.80
    // 第二段转关闭（见 W4DChannelClosures.kt）
)
/** 本批域的**域级审计结论证据**（`文件:行 函数` 形式；CLOSED 域必须为空）。 */
internal val w4CDomainEvidence: Map<Domain, List<String>> = mapOf(
    Domain.BATTLE to listOf(
        // C1 实裁（2026-09-15，w4c/07）：伤亡残差/胜利事务/战前结算三事务下沉
        "CombatService.kt:78 processBattleCasualties — 状态段已下沉 C++ 1780 " +
            "（battle_residual_tx.h ①；Kotlin 臂 = 降级回退 + DeathEvent/丧亲日志/Room 残差平台面）",
        "GameEngineWorldBattleOps.kt:188 applyWorldLevelVictoryTransaction — 魂力/winAttr 已下沉 1781 " +
            "（C++ 不写 defeated，batch-13 TOCTOU 口径；defeated + battleLogs 残差留 Kotlin 臂）；" +
            ":288 失败事务 = battleLogs 显示域 + UI 通道，无状态可下沉（登记不下沉）",
        "GameEngineBattleOps.kt:66 战前结算已下沉 1782（与 :134 同一事务界面）；" +
            ":274 recordSectBattleRecord（与 battleLogs 同事务撕裂）/ :339 occupySectRewards / " +
            ":366 crushSectRewards（与 grantWarRewardsInside 同一 update 原子事务，" +
            "奖励段不可复刻）——登记不下沉（sect_attack_tx.h KDoc 同证）",
        "GameEngineExplorationNativeOps.kt:134 战前结算已下沉 1782（battle_residual_tx.h ③，" +
            "候选限定队伍 id 集，抽取集不变红线）",
    ),
    Domain.SECRET_REALM to listOf(
        // C3 实裁（2026-09-15，w4c/07）：出发换岗/到期兜底两事务下沉
        "GameEngineSecretRealmOps.kt:57 出发换岗已下沉 1800（secret_realm_residual_tx.h ①，" +
            "11 类槽位清理 + 状态重置；gate 释放/Room 清槽为 finalizeSecretRealmTeam 平台段）",
        "GameEngineSecretRealmNativeOps.kt:100 rejectIfSecretRealmExpired — 到期兜底已下沉 1801 " +
            "（关闭状态段归 C++ secret_realm_settle 复用；关闭邮件/gate 释放留 " +
            "applyExpiryCloseDraft 通道）；:263 recordSecretRealmBattleReport — 战报写回，" +
            "battleLogs 显示域登记不下沉（S6 决策③既有口径）",
    ),
)
