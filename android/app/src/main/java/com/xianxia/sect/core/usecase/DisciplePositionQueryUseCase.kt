package com.xianxia.sect.core.usecase

import com.xianxia.sect.core.engine.GameEngine
import com.xianxia.sect.ui.game.DisciplePositionHelper
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 弟子职位查询用例
 *
 * 整合了所有弟子职位查询方法，消除跨 ViewModel 的重复代码。
 * 内部委托 [DisciplePositionHelper] 实现具体逻辑。
 */
@Singleton
class DisciplePositionQueryUseCase @Inject constructor(
    private val gameEngine: GameEngine
) {
    fun hasDisciplePosition(discipleId: String): Boolean {
        return DisciplePositionHelper.hasDisciplePosition(discipleId, gameEngine.gameData.value)
    }

    fun getDisciplePosition(discipleId: String): String? {
        return DisciplePositionHelper.getDisciplePosition(discipleId, gameEngine.gameData.value)
    }

    fun isReserveDisciple(discipleId: String): Boolean {
        return DisciplePositionHelper.isReserveDisciple(discipleId, gameEngine.gameData.value)
    }

    fun isPositionWorkStatus(discipleId: String): Boolean {
        return DisciplePositionHelper.isPositionWorkStatus(discipleId, gameEngine.gameData.value)
    }
}
