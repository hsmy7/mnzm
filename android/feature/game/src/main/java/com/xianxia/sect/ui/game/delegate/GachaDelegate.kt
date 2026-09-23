package com.xianxia.sect.ui.game.delegate

import com.xianxia.sect.core.engine.GameEngine
import com.xianxia.sect.core.engine.domain.gacha.GachaFacade
import com.xianxia.sect.core.engine.domain.gacha.GachaPullResult

/**
 * 寻访 UI 委托（G01 空壳）。
 *
 * G11 承接：主界面/结果页/图鉴入口；状态变更一律经 GameEngine/Facade，
 * 禁止直写 GameStateStore。
 */
class GachaDelegate(
    private val gameEngine: GameEngine,
    private val gachaFacade: GachaFacade,
) {
    /** 占位：G09 前恒 NotReady；G11 接到寻访按钮。 */
    suspend fun pullOnce(poolId: String = "standard"): GachaPullResult =
        gachaFacade.pullOnce(poolId)

    suspend fun pullTen(poolId: String = "standard"): GachaPullResult =
        gachaFacade.pullTen(poolId)
}
