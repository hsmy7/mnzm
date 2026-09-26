package com.xianxia.sect.core.engine.domain.gacha

import com.xianxia.sect.core.engine.GameEngine
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.StarZone
import com.xianxia.sect.core.util.DomainLog
import com.xianxia.sect.core.util.onFailure

/**
 * 寻访解锁名册补齐（读档后一次，幂等）。
 *
 * 判据只读两张既有账本与名册，不新增持久化字段：`gachaStarMap[templateId] >= 1`
 * 而名册中无该模板的弟子 ⇒ 补一次 `DiscipleService.instantiateTemplate`
 * （限持判定天然幂等，已持有则返回 `TemplateAlreadyOwned` 而不重复入册）。
 * 覆盖的是"星级账本已落账、弟子入册未随事务持久化"的中断态存档。
 *
 * 存量旧弟子 `templateId` 为空串，不参与比对（星级恒按 0 处理）。
 */
internal fun GameEngine.syncGachaUnlockedRoster(disciples: List<Disciple>) {
    val ownedTemplateIds = disciples.map { it.templateId }.toSet()
    val starMap = stateStore.gameDataSnapshot.gachaStarMap
    starMap.forEach { (templateId, star) ->
        if (star < StarZone.BASE_STAR || templateId.isEmpty() || templateId in ownedTemplateIds) {
            return@forEach
        }
        discipleService.instantiateTemplate(templateId).onFailure { error ->
            DomainLog.e(
                "GameEngine",
                "loadData: 寻访解锁弟子补齐失败 templateId=$templateId code=${error.code}"
            )
        }
    }
}
