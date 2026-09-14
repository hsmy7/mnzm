package com.xianxia.sect.ui.game.delegate

import com.xianxia.sect.core.engine.GameEngine
import com.xianxia.sect.core.engine.domain.disciple.DiscipleFacade

/**
 * 弟子生平事件委托（自 GameViewModel 拆出，行为零变更）。
 *
 * 生平事件读取与初始化：写操作派发到引擎线程（对齐 enterSect 模式），
 * 否则主线程直调 stateStore.update 触发架构违规守卫。
 */
class LifeEventsDelegate(
    private val gameEngine: GameEngine,
    private val discipleFacade: DiscipleFacade
) {

    fun getLifeEvents(discipleId: String): List<String> = discipleFacade.getLifeEvents(discipleId)

    fun initializeLifeEvents(discipleId: String) {
        gameEngine.launchOnEngine { discipleFacade.initializeLifeEvents(discipleId) }
    }
}
