package com.xianxia.sect.ui.game.delegate

import com.xianxia.sect.core.engine.domain.gacha.GachaFacade
import com.xianxia.sect.core.engine.domain.gacha.GachaPullResult

/**
 * 寻访 UI 委托：把抽卡请求转发到 [GachaFacade]（事务与双臂在 Facade 内）。
 *
 * G11 承接：主界面/结果页/图鉴入口；状态变更一律经 Facade（Facade 再经 C++ 事务），
 * 禁止直写 GameStateStore。
 */
class GachaDelegate(
    private val gachaFacade: GachaFacade,
) {
    suspend fun pullOnce(poolId: String = "standard"): GachaPullResult =
        gachaFacade.pullOnce(poolId)

    suspend fun pullTen(poolId: String = "standard"): GachaPullResult =
        gachaFacade.pullTen(poolId)
}
