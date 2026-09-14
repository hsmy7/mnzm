package com.xianxia.sect.core.engine.domain.battle

import com.xianxia.sect.core.model.AttackWarning

/**
 * 预警弹窗去重键（审计 P2-9 / 方案 D3 派生）：attackerSectId + stage——
 * 键空间 ≤ 宗门数×阶段数（有界），替代原 warningId(UUID)+stage（每条新预警
 * 新 UUID → 集合只增不减）。
 *
 * 预警生命周期（生成/收敛/到期战书结算/冷却写点）已于 P2-18 Stage 1 下沉
 * C++ 月结子事件 6b（sect_defense_battle.h）——原 AttackWarningService
 * （createImminentAttackWarning/normalizeImminentWarningsSync/addWarningSync）
 * 失去全部生产调用方随 PlayerDefenseProcessor 一并删除，仅保留本扩展；
 * 预警过期/结算移除时经 C++ 结果应用同步清键（shownWarningStageIds 镜像面）。
 */
fun AttackWarning.shownStageKey(): String = "$attackerSectId:${stage.name}"
