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
    // BOUNDARY（W4-D/D2 · w3-11 引导领奖下沉——claimGuideReward 写面归 C++，
    // Kotlin 残余 = 回退臂-only；原 W4-B retained 条目同批转出）
    gameDataField(Domain.BOUNDARY, "guideClaimedRewardIds"),
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
    // W4-D/D2（2026-09-15）w3-11 月年编排残差——扇出项逐条判定与宿主族解冻核对
    Domain.BOUNDARY to listOf(
        "W4-D/D2（2026-09-15）：GameEngineGuideOps.kt:52 claimGuideReward — 引导领奖已下沉" +
            "（GUIDE_REWARD_CLAIM_TX=1830 + guide_reward_tx.h：任务注册表 25 条/9 类条件求值/" +
            "可行性预检/SYSTEM 2×nextLong UUID 复刻/凭据溢出抑制）；guideClaimedRewardIds 转入关闭" +
           "（Kotlin 残余 = 回退臂-only）；UI 奖励卡片两臂同形留 Kotlin",
        "W4-D/D2（2026-09-15）：GameEngineCoreMonthOps.kt:90 / GameEngineCoreYearOps.kt:126 " +
            "残留执行器逐条判定收口——purchaseLogs 与丧亲 = lifeEvents 瞬态列（@Ignore 非协议字段，" +
            "DiscipleSerializer.kt:28）⇒ Kotlin 日志；秘境关闭邮件 = MailService DAO 通知；" +
            "死亡链袋物化 = 平台效应链（InventoryFacadeImpl.kt:678 openStorageBag 逐件入库仍为" +
            "两臂共用 Kotlin 稳态写者 ⇒ 物化下沉对关闭无收益，不迁）；" +
            "RedeemCodeService.kt:153/:402 兑换码登记不下沉（C++ 无物品随机生成器，RNG 红线，" +
            "§2.50/B3 同先例）",
        "W4-D/D2（2026-09-15）：宿主族解冻核对（month_settlement.h:1012 / year_settlement.h:1805）" +
            "——C++ runMonthSettlement 16 子事件全在位（含子事件 12 附庸脱离/13 任务刷新/" +
            "15 秘境期满/16 秘境 AI 队）；runYearSettlement T1 全部 11 项 + T2 主要子项在位，" +
            "partnerMatching/aiAlliances 双侧均为空扩展点（DiplomacyEventProcessor.kt:60/:65）平价；" +
            "6 个冻结宿主调用点核对完毕，KDoc 陈旧面（S4/W4 时代扇出描述）同批修正",
    ),
    Domain.DIPLOMACY to listOf(
        "W4-D/D2（2026-09-15）：VassalService.kt:99/:323 年贡/附属年贡/月度脱离判定收口——" +
            "C++ 逻辑已在位（year_settlement.h detail::processYearlyTribute/" +
            "processYearlyVassalTribute + month_settlement.h detail::processVassalBreakaway，" +
            "AUTHORITATIVE 管线原生执行）；Kotlin 调用点保留为 flag-OFF 回退臂" +
            "（CultivationEventMonthlyOps.kt:73/:105/:125/:126）——开 native 臂即双重扣贡/" +
            "双重抽取 ⇒ 不占号（B4 预判实裁）；sectRelations/vassalContracts/suzerainSectId " +
            "保持 in-flight（外交赠礼/自愈等写者面另行评估）",
    ),
)
