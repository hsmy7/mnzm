package com.xianxia.sect.domain.usecase

import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.engine.GameEngine
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.DiscipleStatus
import com.xianxia.sect.core.model.EquipmentSlot
import com.xianxia.sect.core.model.GameData
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
/**
 * ## DiscipleManagementUseCase - 弟子管理用例
 *
 * ### [H-09] 过度工程化评估
 *
 * **总体评价**: 部分有价值，部分为纯代理
 *
 * **保留的方法** (有实际业务逻辑):
 * - `recruitDisciple()`: 包含数量上限验证、数据校验
 * - `recruitAllDisciples()`: 批量招募 + 数量限制
 * - `expelDisciple()`: 存在性检查
 * - `validateDiscipleData()`: 复杂的数据验证规则
 * - `getAvailableDisciplesForPosition()`: 多条件过滤
 *
 * **@Deprecated 的方法** (纯一行代理):
 * - `equipItem()`: 直接调用 gameEngine.equipItem()
 * - `unequipItem()`: 直接调用 gameEngine.unequipItem()
 * - `learnManual()`: 直接调用 gameEngine.learnManual()
 * - `forgetManual()`: 直接调用 gameEngine.forgetManual()
 * - `usePill()`: 直接调用 gameEngine.usePill()
 * - `getDiscipleId()`: 简单的列表查找
 *
 * **迁移建议**: 废弃的方法应直接在 ViewModel 中调用 gameEngine.xxx()
 */
class DiscipleManagementUseCase @Inject constructor(
    private val gameEngine: GameEngine
) {
    data class RecruitResult(
        val success: Boolean,
        val disciple: Disciple? = null,
        val message: String? = null
    )
    
    data class EquipResult(
        val success: Boolean,
        val message: String? = null
    )
    
    fun recruitDisciple(discipleId: String, recruitList: List<Disciple>, currentDiscipleCount: Int): RecruitResult {
        val disciple = recruitList.find { it.id == discipleId }
            ?: return RecruitResult(false, message = "弟子不存在")
        
        if (currentDiscipleCount >= GameConfig.Disciple.MAX_DISCIPLES) {
            return RecruitResult(false, message = "弟子数量已达上限(${GameConfig.Disciple.MAX_DISCIPLES}人)")
        }
        
        if (!validateDiscipleData(disciple)) {
            return RecruitResult(false, message = "弟子数据异常，无法招募")
        }
        
        gameEngine.recruitDiscipleFromList(disciple)
        return RecruitResult(true, disciple = disciple)
    }
    
    fun recruitAllDisciples(recruitList: List<Disciple>, currentSpiritStones: Long, currentDiscipleCount: Int): RecruitResult {
        val availableSlots = GameConfig.Disciple.MAX_DISCIPLES - currentDiscipleCount
        if (availableSlots <= 0) {
            return RecruitResult(false, message = "弟子数量已达上限(${GameConfig.Disciple.MAX_DISCIPLES}人)")
        }
        
        val actualRecruitCount = minOf(recruitList.size, availableSlots)
        
        val validDisciples = recruitList.take(actualRecruitCount).filter { validateDiscipleData(it) }
        validDisciples.forEach { disciple ->
            gameEngine.recruitDiscipleFromList(disciple)
        }
        return RecruitResult(true)
    }
    
    private fun validateDiscipleData(disciple: Disciple): Boolean {
        if (disciple.id.isBlank()) return false
        if (disciple.name.isBlank()) return false
        if (disciple.age < GameConfig.Disciple.MIN_AGE || disciple.age > GameConfig.Disciple.MAX_AGE) return false
        if (disciple.lifespan <= 0) return false
        if (disciple.loyalty < GameConfig.Disciple.MIN_LOYALTY || disciple.loyalty > GameConfig.Disciple.MAX_LOYALTY) return false
        if (disciple.realm < 0 || disciple.realm > 9) return false
        return true
    }
    
    fun expelDisciple(discipleId: String, disciples: List<Disciple>): RecruitResult {
        val disciple = disciples.find { it.id == discipleId }
            ?: return RecruitResult(false, message = "弟子不存在")

        gameEngine.expelDisciple(discipleId)
        return RecruitResult(true, message = "${disciple.name}已被驱逐")
    }

    // H-09: 纯代理方法，无额外业务逻辑，建议直接调用 gameEngine.equipItem()
    @Deprecated(
        "Pure proxy with no business logic. Use gameEngine.equipItem() directly.",
        ReplaceWith("gameEngine.equipItem(discipleId, equipmentId)", "com.xianxia.sect.core.engine.GameEngine")
    )
    fun equipItem(discipleId: String, equipmentId: String): EquipResult {
        gameEngine.equipItem(discipleId, equipmentId)
        return EquipResult(true)
    }

    // H-09: 纯代理方法
    @Deprecated(
        "Pure proxy with no business logic. Use gameEngine.unequipItem() directly.",
        ReplaceWith("gameEngine.unequipItem(discipleId, slot)", "com.xianxia.sect.core.engine.GameEngine")
    )
    fun unequipItem(discipleId: String, slot: EquipmentSlot): EquipResult {
        gameEngine.unequipItem(discipleId, slot)
        return EquipResult(true)
    }

    // H-09: 纯代理方法
    @Deprecated(
        "Pure proxy with no business logic. Use gameEngine.learnManual() directly.",
        ReplaceWith("gameEngine.learnManual(discipleId, manualId)", "com.xianxia.sect.core.engine.GameEngine")
    )
    fun learnManual(discipleId: String, manualId: String): EquipResult {
        gameEngine.learnManual(discipleId, manualId)
        return EquipResult(true)
    }

    // H-09: 纯代理方法
    @Deprecated(
        "Pure proxy with no business logic. Use gameEngine.forgetManual() directly.",
        ReplaceWith("gameEngine.forgetManual(discipleId, manualId)", "com.xianxia.sect.core.engine.GameEngine")
    )
    fun forgetManual(discipleId: String, manualId: String): EquipResult {
        gameEngine.forgetManual(discipleId, manualId)
        return EquipResult(true)
    }

    // H-09: 纯代理方法
    @Deprecated(
        "Pure proxy with no business logic. Use gameEngine.usePill() directly.",
        ReplaceWith("gameEngine.usePill(discipleId, pillId)", "com.xianxia.sect.core.engine.GameEngine")
    )
    fun usePill(discipleId: String, pillId: String): EquipResult {
        gameEngine.usePill(discipleId, pillId)
        return EquipResult(true)
    }

    // H-09: 简单查找，可考虑内联到调用方
    @Deprecated(
        "Simple list find operation. Consider inlining or using a utility function.",
        ReplaceWith("gameEngine.disciples.value.find { it.id == discipleId }", "com.xianxia.sect.core.engine.GameEngine")
    )
    fun getDiscipleById(discipleId: String): Disciple? {
        return gameEngine.disciples.value.find { it.id == discipleId }
    }
    
    fun getAvailableDisciplesForPosition(
        disciples: List<Disciple>,
        minRealm: Int,
        excludeIds: Set<String> = emptySet()
    ): List<Disciple> {
        return disciples.filter { disciple ->
            disciple.realm <= minRealm &&
            disciple.status == DiscipleStatus.IDLE &&
            disciple.id !in excludeIds
        }
    }
}
