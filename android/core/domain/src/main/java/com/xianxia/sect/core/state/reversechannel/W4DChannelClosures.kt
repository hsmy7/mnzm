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
 *   关注列表，以及 PATROL 域的灵矿月结水位（原 W4-B retained，随 W4-D/D3
 *   harness 对齐生产后转入关闭）。
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
    // BOUNDARY（W4-D/D3 · harness 对齐生产——原"harness 覆写"retained 三项。
    // 稳态写者重评：年报快照/年度计数重置 = C++ runYearSettlement（T1 在位，
    // §2.73）；任务刷新/清理 = C++ 月结子事件 13；Kotlin 残余 = YearSettlement
    // Executor/CultivationEventMissionOps 回退臂（native 未就绪时反向通道本就
    // 不活跃）+ 读档归一化（基线建立前，detectClosedFieldWrites 不误报）
    gameDataField(Domain.BOUNDARY, "annualAlchemyCount"),
    gameDataField(Domain.BOUNDARY, "yearlyReports"),
    gameDataField(Domain.RECRUIT, "availableMissions"),
    // PATROL（W4-D/D3 · 同上——灵矿月结水位由 C++ 月结灵矿步无条件推进；
    // Kotlin 残余 = CultivationSettlement 回退臂 + 读档归一化（LOAD_BOOT 族）+
    // SectPolicyToggleUseCase.toggleSpiritMineBoost 回退臂（native 臂 1682 在位，
    // §2.63 batch-18b）——原 W4-B retained 条目同批转出）
    gameDataField(Domain.PATROL, "spiritMineLastSettledMonth"),
    // RECRUIT（W4-D 续·任务域收口——startMission sunk 1861 MISSION_START_TX；
    // 周期刷新/完成 = C++ 月结子事件 13/5；Kotlin checkAndProcessCompletedMissions
    // 在 AUTHORITATIVE 为防御性 no-op（C++ 完成经前向镜像后无可完成项）+
    // 读档归一化（LOAD_BOOT 族）+ flag-OFF 回退臂（检测 AUTHORITATIVE 门控不计数））
    gameDataField(Domain.RECRUIT, "activeMissions"),
    // AI_SECT（W4-D 续·存档自愈收口——§2.75④ 第 3 项）：
    // 写者全部获得回导替代路径——存档自愈/完整性修复/攻宗占领/升级回退臂四处
    // 均已接线"写入后 native 基线重建"（rebaselineNativeMirror / importToNative，
    // ADR 保留面 sanctioned 复用）；boot 归一化（LOAD_BOOT 族）由首旬全量导入吸收；
    // removeDeadDefenders native 臂就位（batch-20b）。遭遇战/好感事件写者挂
    // 月结子事件表（flag-OFF 回退臂，检测门控不计数）。
    topLevelSection(Domain.AI_SECT, ReverseChannelPolicy.SECTION_AI_SECT_DISCIPLES),
    gameDataField(Domain.AI_SECT, "worldMapSects"),
    // DISCIPLE（W4-D 续·弟子通道关闭——w3-13 删除批硬前置达成判定）：
    // 通道的 AUTHORITATIVE 稳态协议列写者已全部获得 C++ 真相先行臂——
    // 交谈效果 1860（chat_effect_tx.h）/ 任务派遣 1861（mission_start_tx.h）/
    // 改名·类型·关注·赏赐·服药·状态派生·血炼·功法·婚姻（1740–1759，W4-A）。
    // lifeEvents 协议外列投影（购买日志/丧亲）随残留执行器事务转 updateMirror
    // 非捕获路径（C++ 事实的 Kotlin 显示投影，无需回导）；检测已 AUTHORITATIVE
    // 门控（flag-OFF 回退臂写入 = 写入即真相，不存在回导缺口）。
    discipleChannel(Domain.DISCIPLE),
    // ═══ §2.79 retained 字段族逐域判定·第一段（27 项转关闭，w3-13 删除步继续收口）═══
    // 写者穷尽审计（三路并行 grep + 逐点核对）后的判定：以下字段在 AUTHORITATIVE
    // 稳态下的 Kotlin 写者全部属于"native 臂就位后的回退臂 / LOAD_BOOT 族 /
    // flag-OFF 结算臂 / 已接线基线重建"四类合法形态。
    // RECRUIT（§2.79）：招募列表——占领写者 rebaseline 吸收（§2.78），余者回退臂/
    // 月结旗臂/读档归一化
    gameDataField(Domain.RECRUIT, "recruitList"),
    // AI_SECT（§2.79）：宗门标识——enterSect 收敛/宗门改名两写者已接线
    // rebaselineNativeMirror（GameEngineLifecycleOps），余者 boot 归一化
    gameDataField(Domain.AI_SECT, "activeSectId"),
    gameDataField(Domain.AI_SECT, "sectName"),
    // DIPLOMACY（§2.79）：预警去重 = 1843 native 臂就位（回退臂-only）；
    // 附庸契约/宗主 = 攻宗占领 rebaseline 吸收（§2.78）+ VASSAL_TX native 臂 +
    // 年贡/脱离 flag-OFF 旗臂；establishVassalage 死 API（零调用方）
    gameDataField(Domain.DIPLOMACY, "shownWarningStageIds"),
    gameDataField(Domain.DIPLOMACY, "vassalContracts"),
    gameDataField(Domain.DIPLOMACY, "suzerainSectId"),
    // INVENTORY（§2.79）：玉符四字段——JadeSymbolService 全部写入点 native 臂
    // 先行（CHECKPOINT/GRANT_AD/SETTLE/DAY_RESET 1766–1769 + 购买 1692/1693），
    // Kotlin 写入仅空回复回退臂；SaveData 修复 = LOAD_BOOT
    gameDataField(Domain.INVENTORY, "jadeSymbols"),
    gameDataField(Domain.INVENTORY, "jadeSymbolsToday"),
    gameDataField(Domain.INVENTORY, "jadeAccumMs"),
    gameDataField(Domain.INVENTORY, "jadeDayAnchorMs"),
    // PATROL（§2.79）：巡逻/灵矿/住所槽位与配置——槽位事务族 native 臂在位
    // （patrol_tx/assignment_tx/batch-12/15/17），残余稳态写者两处已接线
    // rebaseline（设置重置/enterSect 收敛）；WarehouseDialog 卸任迁入引擎层
    // GameEngine.removeWarehouseGarrison + rebaseline
    gameDataField(Domain.PATROL, "patrolConfigs"),
    gameDataField(Domain.PATROL, "spiritMineSlots"),
    gameDataField(Domain.PATROL, "patrolSlots"),
    gameDataField(Domain.PATROL, "residenceSlots"),
    // SAVE_LOAD（§2.79）：地形段与版本戳——唯一 Kotlin 写者 = boot 回填
    // （ensureSectTerrainBackfilled，仅 mapSeed≠0 触发），与 C++
    // importStateInternal ensureTerrainGenerated 同源生成恒等（D3 跨语言等价
    // 已证）⇒ 信封侧值比较零命中；mapSeed=0 老档两端恒空，无数据丢失面
    gameDataField(Domain.SAVE_LOAD, "terrainTiles"),
    gameDataField(Domain.SAVE_LOAD, "mapGenVersion"),
    // SECRET_REALM（§2.79）：秘境会话族 + 洞府探索队——START/CHOOSE/END/continue
    // native 臂在位（1800/1801/1442 族），到期关闭 = 月结子事件 15 旗臂；
    // 残余稳态写者两处已接线 rebaseline（设置重置的 endSession / 内存裁剪）
    gameDataField(Domain.SECRET_REALM, "secretRealmState"),
    gameDataField(Domain.SECRET_REALM, "secretRealmSession"),
    gameDataField(Domain.SECRET_REALM, "secretRealmAITeams"),
    gameDataField(Domain.SECRET_REALM, "secretRealmCooldownYear"),
    gameDataField(Domain.SECRET_REALM, "caveExplorationTeams"),
    gameDataField(Domain.SECRET_REALM, "aiCaveTeams"),
    // DISCIPLE（§2.79）：弟子槽位族 + 血炼运行态——槽位事务 native 臂在位
    // （1480–1485/1746/1780/1861），三处合法稳态写者已改非捕获 + rebaseline：
    // 设置重置（resetAllDisciplesStatus）/ 槽位释放（releaseDiscipleFromAllSlots
    // Atomic）/ 取消血炼（cancelBloodRefinement）；BuildingService.assign
    // DiscipleToBuilding 链零生产调用方（死链，D5 清单登记）
    gameDataField(Domain.DISCIPLE, "elderSlots"),
    gameDataField(Domain.DISCIPLE, "librarySlots"),
    gameDataField(Domain.DISCIPLE, "warehouseGarrisons"),
    gameDataField(Domain.DISCIPLE, "battleTeams"),
    gameDataField(Domain.DISCIPLE, "activeBloodRefinements"),
    // BUILDING / PRODUCTION（§2.79）：placedBuildings 唯一非 boot 稳态写者 = enterSect
    // 孤儿建筑归一化（已接线 rebaseline）；place/move/upgrade/demolish 全部 native 臂
    // 先行（1450-1454/1810/1811）。spiritFieldPlants 种植/移除/收获全部 native 臂先行
    // 或 flag-OFF 旗臂（spirit_field.h），零会话中途稳态 Kotlin 写者
    gameDataField(Domain.BUILDING, "placedBuildings"),
    gameDataField(Domain.PRODUCTION, "spiritFieldPlants"),
)

/** 本批的**在册保留**gameData 字段（不可关闭；口径见 `ReverseChannelPolicy.transportedGameDataFields`）。 */
internal val w4DRetainedGameDataFields: Set<String> = linkedSetOf(
    // 经济：钱包三阶与灵草（§2.79 审计确认 LIVE 写者族 = 邮件附件领取/宗门交易/
    // 天劫结算/妖兽突袭奖励/开袋入库/兑换码——统一属"第 4 项第二段"下沉面，
    // 与"登记不下撤"项同批裁决；详见 w4DDomainEvidence BOUNDARY 条目）
    "spiritStones", "midGradeSpiritStones", "highGradeSpiritStones", "spiritHerbs",
    // 执法堂（§2.79 审计修正：赏赐/服药 native 臂成功后的偷盗判定钩子在
    // AUTHORITATIVE 仍以 Kotlin 原序执行完整执法链（判定标记 + 偷盗所得），
    // "执法域不下沉"（batch-14/A1 登记）——钩子写入面已改 updateMirror 非捕获 +
    // 尾部 rebaseline 回导（DiscipleOpsNativeTxForward.applyTheftHookResidual），
    // 但写者本体在位 ⇒ 字段保持传输）
    "theftJudgementsThisMonth", "annualTheftCount", "annualDesertedDisciples",
    // 年度收支账（§2.79 审计：wallet recordAndEmit 与钱包三阶同一 LIVE 调用者集；
    // combat 胜利奖励/邮件/宗门交易/天劫/妖兽突袭/开袋/兑换族 = 第 4 项第二段）
    "annualIncomeBySource", "annualExpenditureByReason", "annualTotalIncome",
    "annualTotalExpenditure", "annualNewDisciples", "annualDeceasedDisciples",
    "annualEquipmentBySource", "annualPillBySource", "annualHerbBySource",
    // 兑换码 / 关注列表（§2.79 审计确认 LIVE：兑换码 = RedeemCodeService 用户动作
    // 登记不下沉（RNG 红线）；关注列表 = GameEngineLifecycleOps:70 用户动作）
    "usedRedeemCodes", "watchedItemIds",
    // （§2.79 第一段转关闭 29 项：recruitList/activeSectId/sectName/shownWarning
    //   StageIds/vassalContracts/suzerainSectId/jade×4/patrolConfigs/spiritMineSlots/
    //   patrolSlots/residenceSlots/terrainTiles/mapGenVersion/secretRealm×4/cave×2/
    //   elderSlots/librarySlots/warehouseGarrisons/battleTeams/activeBloodRefinements/
    //   placedBuildings/spiritFieldPlants——关闭单元与逐域证据见 closedUnits 与
    //   w4DDomainEvidence §2.79 条目）
)

/** 本批域的**域级审计结论证据**（`文件:行 函数` 形式；CLOSED 域必须为空）。 */
internal val w4DDomainEvidence: Map<Domain, List<String>> = mapOf(
    Domain.RECRUIT to listOf(
        "DiscipleFacadeImpl功法Ops1.kt:71 mirrorAppendJoinSectLifeEvent — 入宗 lifeEvent 补写（C++ 无该列）",
        "DiscipleService.kt:142 recruitDisciple / RecruitService.kt:398 refreshRecruitList — 招募族写者",
        "W4-D/D3（2026-09-15）：availableMissions 转入关闭——harness 对齐生产后稳态写者重评：" +
            "任务刷新/清理 = C++ 月结子事件 13（month_settlement.h）；Kotlin 残余 = " +
            "CultivationEventMissionOps.kt:132 回退臂（native 未就绪时反向通道不活跃）+ " +
            "GameEngineLoadDataOps.kt:425 读档归一化（LOAD_BOOT 族，基线建立前写入，" +
            "detectClosedFieldWrites 不误报）；任务接取 startMission（GameEngineMissionOps.kt:37）" +
            "只写 activeMissions 不写本字段",
        "W4-D/D4 续（2026-09-15，§2.79 第一段）：recruitList 转入关闭——唯一会话中途稳态" +
            "写者 = 攻宗占领俘虏入池（GameEngineBattleOps.kt:350，rebaseline 吸收 §2.78）；" +
            "余者全为合法形态：手动招募/移除 native 臂回退臂（GameEngineRecruitOps.kt:27/" +
            ":127、DiscipleFacadeImpl.kt:234）、月度自动招募/老化/清理旗臂" +
            "（RecruitService.kt:231/:257/:302 + ChildBirthSystem.kt:118）、读档归一化" +
            "（RecruitListCleanupRule.kt:32 等 LOAD_BOOT 族）",
    ),
    Domain.PATROL to listOf(
        "W4-D/D3（2026-09-15）：spiritMineLastSettledMonth 转入关闭（原 W4-B retained" +
            "——\"harness 对拍把 Kotlin 月变编排纳入稳态\"的理由随 harness 对齐失效）：" +
            "月结水位由 C++ runMonthSettlement 灵矿步无条件推进；Kotlin 残余 = " +
            "CultivationSettlement.kt:485 回退臂 + GameEngineLoadDataOps.kt:245/:295/:363 " +
            "读档/新档归一化（LOAD_BOOT 族）+ SectPolicyToggleUseCase.kt:230 回退臂" +
            "（native 臂 GOV_SPIRIT_MINE_BOOST_TOGGLE_TX=1682 在位，batch-18b）",
        "W4-D/D4 续（2026-09-15，§2.79 第一段）：patrolConfigs/spiritMineSlots/patrolSlots/" +
            "residenceSlots 转入关闭——槽位事务族 native 臂在位（patrol_tx 7/8/9、" +
            "PATROL_UPDATE_SPIRIT_MINE_SLOTS、batch-12 原子族、batch-15 仓库驻守、" +
            "batch-17 生产槽）；残余稳态写者两处已接线 rebaselineNativeMirror：" +
            "设置重置（DiscipleStatusService.kt:485）与 enterSect 收敛" +
            "（GameEngineLifecycleOps.kt:243）；WarehouseDialog 卸任迁入引擎层 " +
            "GameEngineWarehouseOps.kt:104 + rebaseline；CombatService.kt:137 伤亡清理 = " +
            "1780 native 臂回退臂；月度自动分配/忠诚衰减 = flag-OFF 旗臂",
    ),
    Domain.AI_SECT to listOf(
        "SaveFacadeImpl.kt:56 regenerateSectsBeforeSave — 存档前世界/AI 池自愈（会话中途稳态）",
        "GameEngineBattleOps.kt:176/:339 — 攻宗阵亡守军清理/吞并（剩余写者）",
        "GameEngineLifecycleOps.kt:177/:196 — 自愈同步族（经 upgradeSectLevel 稳态可达）",
        "W4-D/D4 续（2026-09-15，§2.79 第一段）：activeSectId/sectName 转入关闭——" +
            "①activeSectId 唯一非 boot 写者 = enterSect 净化收敛（GameEngineLifecycleOps" +
            ".kt:241，条件性幂等归一化），已改写入后 rebaselineNativeMirror（§2.78 原语，" +
            "未发生收敛时零成本）；②sectName 原登记 LOAD_BOOT 族经审计证伪——" +
            "SectDelegate.kt:38 renameSect 为稳态写者（§2.78 worldMapSects 关闭的漏网写者，" +
            "同批补漏）：改名写入迁入引擎层 GameEngine.renameSect（GameEngineLifecycleOps" +
            ".kt）+ rebaseline；余者 = BootSequenceController.kt:186 净化 + " +
            "GameEngineLoadDataOps.kt:420 新档（LOAD_BOOT 族）",
    ),
    // W4-D/D4 续（§2.79 第一段）——第二段（未关闭项）的登记面：钱包/年度账/执法堂/
    // 兑换码/关注列表与 9 类集合、战斗世界域（BATTLE/外交 sectRelations）稳态写者
    // 详见 w4DRetainedGameDataFields 注释与 handover §2.79"第 4 项第二段"工作清单
    // W4-D/D2（2026-09-15）w3-11 月年编排残差——扇出项逐条判定与宿主族解冻核对
    Domain.BOUNDARY to listOf(
        "W4-D/D3（2026-09-15）：annualAlchemyCount / yearlyReports 转入关闭" +
            "——harness 对齐生产后稳态写者重评：年报快照与年度计数重置 = " +
            "C++ runYearSettlement（T1 全部 11 项在位，§2.73 宿主族解冻核对）；" +
            "Kotlin 残余 = CultivationEventMonthlyOps.kt:236/:245 与 " +
            "YearSettlementExecutor 回退臂 + ProductionSettlement.kt:52 回退臂" +
            "（4a/4b 炼丹完成结算已入 C++，S4 口径）；原\"DiffAuthoritativeTickTest " +
            "把 Kotlin 月/年编排纳入 AUTHORITATIVE 稳态\"的覆写理由随 D3 对齐失效",
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
        "W4-D/D4 续（2026-09-15，§2.79 第一段）：shownWarningStageIds/vassalContracts/" +
            "suzerainSectId 转入关闭——①预警去重唯一写者 GameEngineDiplomacyOps.kt:43 " +
            "为 1843 DIPLOMACY_WARNING_STAGE_TX 回退臂（native 臂先行 :30-42）；" +
            "②vassalContracts/suzerainSectId 写者 = request/dissolveVassalContract native 臂" +
            "（VassalService.kt:178/:264 VASSAL_TX）回退臂 + 年贡/脱离 flag-OFF 旗臂 + " +
            "攻宗占领（GameEngineBattleOps.kt:353/:354，rebaseline 吸收 §2.78）+ " +
            "establishVassalage 死 API（VassalService.kt:76 零生产调用方，D5 清单登记）",
    ),
    // ═══ §2.79 第一段逐域判定证据（W4-D/D4 续·第 4 项；groupBy 聚合与既有分片并存）═══
    Domain.INVENTORY to listOf(
        "W4-D/D4 续（2026-09-15，§2.79 第一段）：jadeSymbols/jadeSymbolsToday/jadeAccumMs/" +
            "jadeDayAnchorMs 转入关闭——JadeSymbolService.kt 全部写入点 native 臂先行" +
            "（CHECKPOINT_TX :245/:284、GRANT_AD_TX :355、SETTLE_TX :410、DAY_RESET_TX :473、" +
            "购买 1692/1693、洗炼/灵根 1613-1616/1732-1733），Kotlin 写入仅空回复/降级回退臂；" +
            "JadeSymbolNonNegativeRule.kt:51 = SaveData 修复（LOAD_BOOT 族）",
    ),
    Domain.SAVE_LOAD to listOf(
        "W4-D/D4 续（2026-09-15，§2.79 第一段）：terrainTiles/mapGenVersion 转入关闭" +
            "（原 W4-C retained 理由失效）——唯一 Kotlin 写者 = GameEngineSaveOps.kt:24 " +
            "ensureSectTerrainBackfilled（BootSequenceController.kt:423 仅 mapSeed≠0 触发），" +
            "与 C++ importStateInternal ensureTerrainGenerated 同源生成恒等" +
            "（terrain_freeze_test.cpp + DiffSectTerrainTest 跨语言等价已证）⇒ 信封侧" +
            "值比较零命中；mapSeed=0 老档两端恒无段（BootSequenceController.kt:423 " +
            "门控），无数据丢失面。W4-C 当初的误报担忧系值不等场景，已随等价性证明消解",
    ),
    Domain.SECRET_REALM to listOf(
        "W4-D/D4 续（2026-09-15，§2.79 第一段）：secretRealmState/secretRealmSession/" +
            "secretRealmAITeams/secretRealmCooldownYear/caveExplorationTeams/aiCaveTeams " +
            "转入关闭——native 臂在位（START/CHOOSE/END/continue = 1800/1801/1442 族，" +
            "SecretRealmService.kt:216/:273/:1188 均回退臂-only；到期关闭 = 月结子事件 15 " +
            "旗臂，Kotlin 仅 applyExpiryCloseDraft 平台草稿）；残余稳态写者两处已接线 " +
            "rebaselineNativeMirror：设置重置 endSession（DiscipleStatusService.kt:505 → " +
            "GameEngineDiscipleOps.kt:22 包装层）与内存裁剪（GameEngineServiceOps.kt:96 " +
            "releaseMemory CRITICAL 分支）；死亡槽位清理（DiscipleSlotManager.kt:150）为 " +
            "native 死亡事务后镜像已刷新的幂等 no-op（batch-11 口径）",
    ),
    Domain.DISCIPLE to listOf(
        "W4-D/D4 续（2026-09-15，§2.79 第一段）：elderSlots/librarySlots/warehouseGarrisons/" +
            "battleTeams/activeBloodRefinements 转入关闭——槽位事务族 native 臂在位" +
            "（1480-1485/1746/1780/1861 + patrol/warehouse/garrison/residence 原子族）；" +
            "三处合法稳态写者改非捕获 + rebaseline：设置重置（DiscipleStatusService.kt:485 " +
            "clearSlotsForReset，GameEngineDiscipleOps.kt:22 包装层重建基线）、槽位释放" +
            "（GameEngineDiscipleSlotOps.kt:146 releaseDiscipleFromAllSlotsAtomic，" +
            "ElderManagementUseCase.kt:121 分配前清理/SpiritMineViewModel.kt:287/" +
            "DiscipleDelegateLifecycleOps.kt:27 同入口）、取消血炼（GameEngineBlood" +
            "RefinementOps.kt:113）；仓库驻守卸任迁入引擎层 GameEngineWarehouseOps.kt:104 " +
            "removeWarehouseGarrison + rebaseline；BuildingService.kt:111 assignDiscipleTo" +
            "Building 链零生产调用方（DiscipleDelegate.kt:94 无界面调用——死链，D5 清单登记）",
    ),
    Domain.BUILDING to listOf(
        "W4-D/D4 续（2026-09-15，§2.79 第一段）：placedBuildings 转入关闭——唯一非 boot " +
            "稳态写者 = enterSect 孤儿建筑归一化（GameEngineLifecycleOps.kt:226 " +
            "normalizeOrphanBuildingSectIds，条件性幂等），已接线写入后 rebaselineNative" +
            "Mirror；place/move/upgrade/demolish/seize 全部 native 臂先行（1450-1454/" +
            "1810/1811）回退臂-only；BootSequenceController.kt:185 + GameEngineLoadDataOps" +
            ".kt:293 + GameEngineServiceOps.kt:190 = LOAD_BOOT 族",
    ),
    Domain.PRODUCTION to listOf(
        "W4-D/D4 续（2026-09-15，§2.79 第一段）：spiritFieldPlants 转入关闭——种植/移除/" +
            "收获全部 native 臂先行（BuildingFacadeImpl.kt:381/:419/:447/:472 灵田事务族）" +
            "或 flag-OFF 旗臂（ProductionProcessor处理Ops1.kt:312 收获，spirit_field.h " +
            "C++ 直辖）；BuildingFeature.kt:184 拆除清理随 1810 回退臂；灵田重置 = " +
            "GameEngineLoadDataOps.kt:304（LOAD_BOOT 族）",
    ),
)
