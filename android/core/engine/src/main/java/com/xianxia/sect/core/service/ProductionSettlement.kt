package com.xianxia.sect.core.engine.service

import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.DiscipleStatus
import com.xianxia.sect.core.model.GameEventCategory
import com.xianxia.sect.core.model.GameEventType
import com.xianxia.sect.core.model.guide.GuideCounterKeys
import com.xianxia.sect.core.model.production.ProductionSlot
import com.xianxia.sect.core.profession.ProfessionRules
import com.xianxia.sect.core.profession.applyPromotionProgress
import com.xianxia.sect.core.registry.ForgeRecipeDatabase
import com.xianxia.sect.core.registry.PillRecipeDatabase
import com.xianxia.sect.core.state.GameStateStore
import com.xianxia.sect.core.state.MutableGameState
import com.xianxia.sect.core.state.recordGameEvent

/**
 * 生产完成结算共享扩展（2026-08-09 职业系统）。
 *
 * 手动月变路径（[ProductionProcessor]）与读档/惰性收获路径（BuildingService）
 * 共用同一结算入口，确保职业晋升进度、引导统计、年度统计在两条路径上行为一致
 * （对抗性审查发现：读档路径此前缺晋升/统计，正常玩家读档即丢一次晋升计数）。
 */

/**
 * 完成结算（事务内）：引导计数 + 年度计数 + 弟子回 IDLE + 职业晋升。
 *
 * 手动路径 [ProductionProcessor.completeAlchemySlot]/[completeForgeSlot] 与
 * BuildingService 读档收获路径共用。
 *
 * @param slot 已完成待结算的生产槽位（recipeId 用于查配方品阶）
 * @param discipleId 工作弟子（槽位可能已无弟子，此时仅统计计数）
 * @param success 本次炼制是否成功（成功才结算职业晋升进度）
 * @param isAlchemy true=炼丹，false=锻造（炼器）
 */
internal fun GameStateStore.settleProductionCompletion(
    slot: ProductionSlot,
    discipleId: String,
    success: Boolean,
    isAlchemy: Boolean
) {
    update {
        val counterKey = if (isAlchemy) GuideCounterKeys.ALCHEMY_COMPLETED
        else GuideCounterKeys.FORGE_COMPLETED
        val currentCount = gameData.guideCounters[counterKey] ?: 0L
        gameData = if (isAlchemy) {
            gameData.copy(
                guideCounters = gameData.guideCounters + (counterKey to currentCount + 1),
                annualAlchemyCount = gameData.annualAlchemyCount + 1
            )
        } else {
            gameData.copy(
                guideCounters = gameData.guideCounters + (counterKey to currentCount + 1),
                annualForgeCount = gameData.annualForgeCount + 1
            )
        }
        // 配方无效（数据损坏）时 recipeTier=0：低阶不充数规则下无法匹配任何最高阶，
        // 弟子仅回 IDLE 不结算晋升（对抗性审查 A2：原 `?: 1` 会把无效配方按凡品计数，
        // 无职业弟子白得晋升）
        val recipeTier = slot.recipeId?.let { rid ->
            if (isAlchemy) PillRecipeDatabase.getRecipeById(rid)?.tier
            else ForgeRecipeDatabase.getRecipeById(rid)?.tier
        } ?: 0
        val currentList = discipleTables.assembleAll()
        val updated = currentList.map {
            if (it.id == discipleId && it.isAlive) {
                settleDiscipleProduction(it, recipeTier, success, isAlchemy)
            } else it
        }
        discipleTables.replaceAll(updated)
    }
}

/**
 * 炼制结算：弟子回 IDLE + 成功时结算职业晋升进度（MutableGameState 扩展，
 * 须在 stateStore.update 或影子事务内调用，与晋升事件同域）。
 */
internal fun MutableGameState.settleDiscipleProduction(
    disciple: Disciple,
    recipeTier: Int,
    success: Boolean,
    isAlchemy: Boolean
): Disciple {
    var updated = disciple.copy(status = DiscipleStatus.IDLE)
    if (success) {
        val progress = updated.applyPromotionProgress(recipeTier, isAlchemy)
        if (progress.promoted) {
            recordPromotionEvent(updated, progress.newLevel, isAlchemy)
        }
        updated = progress.disciple
    }
    return updated
}

/** 记录职业晋升事件（须在 stateStore.update 或影子事务内调用）。 */
internal fun MutableGameState.recordPromotionEvent(
    disciple: Disciple,
    newLevel: Int,
    isAlchemy: Boolean
) {
    recordGameEvent(
        GameEventCategory.SECT,
        if (isAlchemy) GameEventType.ALCHEMIST_PROMOTED else GameEventType.FORGEMASTER_PROMOTED,
        "${disciple.name}晋升为${ProfessionRules.displayName(newLevel, isAlchemy)}",
        disciple.id,
        disciple.name
    )
}
