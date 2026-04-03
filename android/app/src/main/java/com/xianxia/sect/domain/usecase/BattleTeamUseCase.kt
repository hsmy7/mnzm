package com.xianxia.sect.domain.usecase

import com.xianxia.sect.core.engine.GameEngine
import com.xianxia.sect.core.model.BattleTeamSlot
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.DiscipleStatus
import com.xianxia.sect.core.model.GameData
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
/**
 * ## BattleTeamUseCase - 战斗队伍用例
 *
 * ### [H-09] 过度工程化评估
 *
 * **总体评价**: 部分有价值，部分为纯代理或简单查询
 *
 * **保留的方法** (有验证逻辑):
 * - `assignDiscipleToSlot()`: 弟子存在性 + 状态检查 + 重复检查
 *
 * **@Deprecated 的方法** (纯代理或简单):
 * - `removeDiscipleFromSlot()**: 无实际逻辑
 * - `getMovableTargetSects()`: 简单的列表过滤
 * - `hasBattleTeam()`: 简单的属性访问
 * - `isTeamAtSect()`: 简单的比较操作
 */
class BattleTeamUseCase @Inject constructor(
    private val gameEngine: GameEngine
) {
    data class BattleTeamSlotData(
        val index: Int,
        val discipleId: String = "",
        val discipleName: String = "",
        val realm: Int = 0
    )
    
    data class CreateTeamParams(
        val leaderId: String,
        val memberIds: List<String>,
        val disciples: List<Disciple>
    )
    
    sealed class TeamResult {
        data class Success(val teamId: String) : TeamResult()
        data class Error(val message: String) : TeamResult()
    }
    
    fun assignDiscipleToSlot(
        slotIndex: Int,
        discipleId: String,
        disciples: List<Disciple>,
        currentSlots: List<BattleTeamSlotData>
    ): TeamResult {
        val disciple = disciples.find { it.id == discipleId }
            ?: return TeamResult.Error("弟子不存在")
        
        if (disciple.status != DiscipleStatus.IDLE) {
            return TeamResult.Error("该弟子当前忙碌")
        }
        
        val isAlreadyInTeam = currentSlots.any { it.discipleId == discipleId }
        if (isAlreadyInTeam) {
            return TeamResult.Error("该弟子已在队伍中")
        }
        
        return TeamResult.Success(discipleId)
    }

    // H-09: 无实际逻辑的方法
    @Deprecated(
        "No actual logic implemented. Consider removing or implementing proper logic.",
        level = DeprecationLevel.WARNING
    )
    fun removeDiscipleFromSlot(slotIndex: Int): TeamResult {
        return TeamResult.Success("")
    }

    // H-09: 简单的列表过滤操作
    @Deprecated(
        "Simple filter operation. Consider inlining to caller.",
        ReplaceWith("gameData.worldMapSects.filter { it.id != playerSectId }.map { it.id }")
    )
    fun getMovableTargetSects(gameData: GameData): List<String> {
        val playerSectId = gameData.worldMapSects.find { it.isPlayerSect }?.id
        return gameData.worldMapSects
            .filter { it.id != playerSectId }
            .map { it.id }
    }

    // H-09: 简单的属性访问
    @Deprecated(
        "Simple property access. Consider using gameData.battleTeam directly.",
        level = DeprecationLevel.WARNING
    )
    fun hasBattleTeam(gameData: GameData): Boolean {
        val team = gameData.battleTeam ?: return false
        return team.slots.any { it.discipleId != null }
    }

    // H-09: 简单的比较操作
    @Deprecated(
        "Simple comparison operation. Consider inlining to caller.",
        level = DeprecationLevel.WARNING
    )
    fun isTeamAtSect(gameData: GameData): Boolean {
        val team = gameData.battleTeam ?: return true
        val playerSectId = gameData.worldMapSects.find { it.isPlayerSect }?.id
        return team.originSectId == playerSectId
    }
}
