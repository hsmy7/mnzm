package com.xianxia.sect.core.engine.domain.diplomacy

import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.PlantSlotData
import com.xianxia.sect.core.model.baseHp
import com.xianxia.sect.core.model.baseMagicAttack
import com.xianxia.sect.core.model.basePhysicalAttack

/**
 * AI 宗门弟子池截断（自 [AISectDiscipleManager] 拆出，**行为零变更**）。
 *
 * 拆出理由（detekt 纪律）：`AISectDiscipleManager` 为 AI 弟子域聚合 object，
 * 函数数触达 `TooManyFunctions`（objects 阈值 12）；本函数为**纯函数聚合口径**
 * （战力 = 物攻 + 法攻 + 生命，降序取前 N），与域内其它成员无耦合，故按
 * "纯函数层外移"惯例（先例：`DiscipleTables` 纯函数层下放、`Generation.kt` /
 * `Gear.kt` / `AISectDiscipleManagerMisc.kt` 三个同包扩展文件）落到同包顶层。
 *
 * @param disciples 目标弟子列表（不修改入参）
 * @return 未超限时原样返回；超限时按战力降序截断到
 *         [PlantSlotData.MAX_AI_DISCIPLES_PER_SECT]
 */
internal fun AISectDiscipleManager.truncateToLimit(disciples: List<Disciple>): List<Disciple> =
    if (disciples.size > PlantSlotData.MAX_AI_DISCIPLES_PER_SECT) {
        disciples.sortedByDescending {
            it.combat.basePhysicalAttack + it.combat.baseMagicAttack + it.combat.baseHp
        }.take(PlantSlotData.MAX_AI_DISCIPLES_PER_SECT)
    } else {
        disciples
    }
