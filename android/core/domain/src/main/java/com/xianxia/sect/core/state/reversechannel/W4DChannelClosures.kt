package com.xianxia.sect.core.state.reversechannel

import com.xianxia.sect.core.state.ReverseChannelPolicy
import com.xianxia.sect.core.state.ReverseChannelPolicy.Domain

/**
 * W4-D 汇流波（串行收口）的反向通道关闭数据。
 *
 * ## 为什么单独一个文件而不是塞进 A/B/C
 * 三个并行批次在各自工作树上施工；本文件承载的是**没有并行批次归属**的域与项：
 * - `RECRUIT`（招募/派遣/俘虏）：batch-16 交付后无新的域级批次，残余写者属收口范畴；
 * - `AI_SECT`（AI 宗门）：自愈族与存档前世界重生，属收口范畴；
 * - **未分配的经济/世界/运营字段**：钱包、世界地图、宗门标识、年度收支账、兑换码、
 *   关注列表，以及 `DiffAuthoritativeTickTest` harness 覆写的 3 个字段
 *   （harness 对齐生产后需**重评**关闭结论，见 `docs/parallel-batches-w4/README.md` §8 D3）。
 *
 * 把"无人认领"的项集中在收口文件里，可保证 A/B/C 三批**永不因非本批内容产生冲突**。
 *
 * 🔴 **只有 W4-D（收口人）可写本文件**。
 */

/** 本批域的**已关闭**传输单元（逐域关闭清单的 W4-D 分片）。 */
internal val w4DClosedUnits: List<ReverseChannelPolicy.ClosedUnit> = listOf(
    // RECRUIT
    gameDataField(Domain.RECRUIT, "recruitCountThisMonth"),
    gameDataField(Domain.RECRUIT, "lastRecruitYear"),
    gameDataField(Domain.RECRUIT, "lastAiSectRecruitYear"),
    // AI_SECT
    gameDataField(Domain.AI_SECT, "aiSectPersonalities"),
)

/** 本批的**在册保留**gameData 字段（不可关闭；口径见 `ReverseChannelPolicy.transportedGameDataFields`）。 */
internal val w4DRetainedGameDataFields: Set<String> = linkedSetOf(
    // 招募列表 / 任务（RECRUIT 域残余）
    "recruitList", "activeMissions",
    // 经济：钱包三阶与灵草（Kotlin 钱包与统一入库入口为稳态写者）
    "spiritStones", "midGradeSpiritStones", "highGradeSpiritStones", "spiritHerbs",
    // 世界与宗门标识
    "worldMapSects", "activeSectId", "sectName",
    // 执法堂月账
    "theftJudgementsThisMonth",
    // 年度收支账（Kotlin 为稳态写者）
    "annualIncomeBySource", "annualExpenditureByReason", "annualTotalIncome",
    "annualTotalExpenditure", "annualNewDisciples", "annualDeceasedDisciples",
    "annualDesertedDisciples", "annualTheftCount", "annualEquipmentBySource",
    "annualPillBySource", "annualHerbBySource",
    // 兑换码 / 关注列表 / 预警去重
    "usedRedeemCodes", "watchedItemIds", "shownWarningStageIds",
    // 对拍 harness 覆写（DiffAuthoritativeTickTest 把 Kotlin 月/年编排纳入
    // AUTHORITATIVE 管线 ⇒ 这些字段在测试面为稳态写者，关闭即对拍红；
    // harness 对齐生产后重评——见 docs/parallel-batches-w4/README.md §8 D3）
    "annualAlchemyCount",
    "availableMissions",
    "yearlyReports",
)

/** 本批域的**域级审计结论证据**（`文件:行 函数` 形式；CLOSED 域必须为空）。 */
internal val w4DDomainEvidence: Map<Domain, List<String>> = mapOf(
    Domain.RECRUIT to listOf(
        "DiscipleFacadeImpl功法Ops1.kt:71 mirrorAppendJoinSectLifeEvent — 入宗 lifeEvent 补写（C++ 无该列）",
        "DiscipleService.kt:142 recruitDisciple / RecruitService.kt:398 refreshRecruitList — 招募族写者",
    ),
    Domain.AI_SECT to listOf(
        "SaveFacadeImpl.kt:56 regenerateSectsBeforeSave — 存档前世界/AI 池自愈（会话中途稳态）",
        "GameEngineBattleOps.kt:176/:339 — 攻宗阵亡守军清理/吞并（剩余写者）",
        "GameEngineLifecycleOps.kt:177/:196 — 自愈同步族（经 upgradeSectLevel 稳态可达）",
    ),
)
