package com.xianxia.sect.core.engine.domain.disciple

import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.EquipmentSlot
import com.xianxia.sect.core.model.EquipmentInstance
import com.xianxia.sect.core.state.GameStateStore
import com.xianxia.sect.core.state.MutableGameState
import com.xianxia.sect.core.util.AppError
import com.xianxia.sect.core.util.DomainLog
import com.xianxia.sect.core.util.DomainResult
import javax.inject.Inject
import javax.inject.Singleton
import com.xianxia.sect.core.model.StorageBagItem
import com.xianxia.sect.core.util.StorageBagUtils



/**
 * 弟子装备穿卸服务（装备重构 B3：六部位、单轨实例、等级随实例）。
 *
 * ## 职责
 * 1. **装备穿戴** — [equipEquipment] 为弟子穿到指定六部位（按实例 [EquipmentInstance.part]），
 *    旧装备自动卸入储物袋
 * 2. **装备卸下** — [unequipEquipment] 从弟子身上卸下装备，完整实例入储物袋
 *
 * 堆叠轨道已随 B3 移除（R6）：装备只存在 `equipmentInstances` 一行一件语义；
 * 等级/词条随实例单点（装卸往返逐位保真，清偿 D2）。
 */
@Singleton
class DiscipleEquipmentService @Inject constructor(
    private val stateStore: GameStateStore,
) {
    companion object {
        private const val TAG = "DiscipleEquipmentService"
    }

    // ==================== 装备管理 ====================

    /**
     * Equip equipment to disciple
     * 设计意图：装备是独占物品，不可共用。一件装备只能给一名弟子穿戴。
     * 装备新装备时，旧装备自动卸下并放入弟子储物袋。
     */
    fun equipEquipment(discipleId: String, equipmentId: String): DomainResult<Unit> {
        var error: AppError.Domain.Disciple? = AppError.Domain.Disciple.NotFound(discipleId)
        stateStore.update {
            error = equipEquipmentInTransaction(
                discipleId = discipleId,
                equipmentId = equipmentId
            )
        }
        val finalError = error
        return if (finalError == null) DomainResult.Success(Unit) else DomainResult.Failure(finalError)
    }

    /** 穿戴事务主体：校验 → 卸旧 → 穿新 → 记录日志，返回错误或 null */
    @Suppress("ReturnCount")
    private fun MutableGameState.equipEquipmentInTransaction(
        discipleId: String,
        equipmentId: String
    ): AppError.Domain.Disciple? {
        // 校验弟子与装备存在性
        val id = discipleId.toIntOrNull()
        if (id == null || !discipleTables.ids.contains(id)) {
            return AppError.Domain.Disciple.NotFound(discipleId)
        }
        val equipmentInstance = equipmentInstances.get(equipmentId)
            ?: return AppError.Domain.Disciple.NotFound(equipmentId)

        // 境界与占用校验
        val realmError = checkEquipRealmRequirement(
            id = id,
            discipleId = discipleId,
            equipmentInstance = equipmentInstance
        )
        if (realmError != null) return realmError

        // 确定装备槽位（按实例部位）
        val slot = equipmentInstance.part
        val equipName = equipmentInstance.name

        // 卸下旧装备（失败则中止穿戴）
        val oldEquipId = currentEquipId(id = id, slot = slot)
        if (oldEquipId.isNotEmpty()) {
            val unequipped = unequipEquipmentLogic(discipleId, oldEquipId)
            if (!unequipped) {
                DomainLog.w(TAG, "equipEquipment: failed to unequip $oldEquipId, aborting equip")
                return AppError.Domain.Disciple.SlotInvalid("卸下旧装备失败 $oldEquipId")
            }
        }

        // 穿戴新装备（等级/词条随实例走，槽位只存 id）
        wearEquipment(
            equipmentId = equipmentId,
            discipleId = discipleId,
            id = id,
            slot = slot
        )

        // 记录装备日志
        recordEquipLog(
            id = id,
            oldEquipId = oldEquipId,
            equipName = equipName
        )

        return null
    }

    /** 境界/占用校验：已穿戴或境界不足时返回对应错误 */
    @Suppress("ReturnCount")
    private fun MutableGameState.checkEquipRealmRequirement(
        id: Int,
        discipleId: String,
        equipmentInstance: EquipmentInstance
    ): AppError.Domain.Disciple? {
        val discipleRealm = discipleTables.realms[id]
        if (equipmentInstance.isEquipped) {
            return AppError.Domain.Disciple.AlreadyEquipped(
                slot = equipmentInstance.part.name
            )
        }
        if (!GameConfig.Realm.meetsRealmRequirement(discipleRealm, equipmentInstance.minRealm)) {
            return AppError.Domain.Disciple.RealmTooLow(
                discipleId = discipleId,
                need = "境界${equipmentInstance.minRealm}"
            )
        }
        return null
    }

    /** 当前槽位已装备的装备 id */
    private fun MutableGameState.currentEquipId(id: Int, slot: EquipmentSlot): String = when (slot) {
        EquipmentSlot.HEAD -> discipleTables.headIds[id]
        EquipmentSlot.BODY -> discipleTables.bodyIds[id]
        EquipmentSlot.HANDS -> discipleTables.handsIds[id]
        EquipmentSlot.FEET -> discipleTables.feetIds[id]
        EquipmentSlot.WEAPON -> discipleTables.weaponIds[id]
        EquipmentSlot.LEGS -> discipleTables.legsIds[id]
    }

    /** 穿戴装备入槽：单轨实例标记（等级/词条随实例，槽位只存 id） */
    private fun MutableGameState.wearEquipment(
        equipmentId: String,
        discipleId: String,
        id: Int,
        slot: EquipmentSlot
    ) {
        when (slot) {
            EquipmentSlot.HEAD -> discipleTables.headIds[id] = equipmentId
            EquipmentSlot.BODY -> discipleTables.bodyIds[id] = equipmentId
            EquipmentSlot.HANDS -> discipleTables.handsIds[id] = equipmentId
            EquipmentSlot.FEET -> discipleTables.feetIds[id] = equipmentId
            EquipmentSlot.WEAPON -> discipleTables.weaponIds[id] = equipmentId
            EquipmentSlot.LEGS -> discipleTables.legsIds[id] = equipmentId
        }
        equipmentInstances.update(equipmentId) { it.copy(isEquipped = true, ownerId = discipleId) }
    }

    /** 记录装备日志：替换 / 首次穿戴 */
    private fun MutableGameState.recordEquipLog(
        id: Int,
        oldEquipId: String,
        equipName: String
    ) {
        val equipEvents = discipleTables.lifeEvents.getOrDefault(id, emptyList())
        if (oldEquipId.isNotEmpty()) {
            val oldName = equipmentInstances.get(oldEquipId)?.name ?: "旧装备"
            discipleTables.lifeEvents[id] = equipEvents +
                "将${oldName}替换为${equipName}"
        } else {
            discipleTables.lifeEvents[id] = equipEvents +
                "装备了${equipName}"
        }
    }

    /**
     * Unequip equipment from disciple
     * 设计意图：装备是独占物品，卸下后放入弟子储物袋，而非归还宗门仓库。
     *
     * 验证和卸下操作全部在 stateStore.update 事务内原子执行，返回实际操作结果。
     */
    fun unequipEquipment(discipleId: String, equipmentId: String): DomainResult<Unit> {
        var error: AppError.Domain.Disciple? = AppError.Domain.Disciple.NotFound(discipleId)
        stateStore.update {
            val id = discipleId.toIntOrNull()
            if (id == null || !discipleTables.ids.contains(id)) {
                error = AppError.Domain.Disciple.NotFound(discipleId); return@update
            }
            val isEquipped = EquipmentSlot.displayOrder.any { discipleTables.slotIdOf(id, it) == equipmentId }
            if (!isEquipped) {
                error = AppError.Domain.Disciple.SlotInvalid("装备未穿戴在弟子身上")
                return@update
            }

            val unequipped = unequipEquipmentLogic(discipleId, equipmentId)
            if (unequipped) error = null
        }
        val finalError = error
        return if (finalError == null) DomainResult.Success(Unit) else DomainResult.Failure(finalError)
    }

    // ==================== 辅助方法 ====================

    private fun MutableGameState.unequipEquipmentLogic(discipleId: String, equipmentId: String): Boolean {
        val id = discipleId.toIntOrNull() ?: return false
        if (!discipleTables.ids.contains(id)) return false

        // 仅判断装备所属槽位（槽位清空移到入袋成功后，失败时保留槽位不悬空）
        val slotToClear = EquipmentSlot.displayOrder.firstOrNull {
            discipleTables.slotIdOf(id, it) == equipmentId
        } ?: return false

        val eq = equipmentInstances.get(equipmentId)

        if (eq != null) {
            // 卸下装备实例直接铸造入袋（完整保真：等级/经验/词条/强化次数全在
            // instance 内——R5 装卸不改等级），不再转仓库堆叠
            val currentDisciple = discipleTables.assemble(id)
            discipleTables.storageBagItems[id] = StorageBagUtils.increaseItemQuantity(
                currentDisciple.equipment.storageBagItems,
                StorageBagItem(
                    itemId = equipmentId, itemType = ITEM_TYPE_EQUIPMENT_INSTANCE,
                    name = eq.name, rarity = eq.rarity, quantity = 1,
                    obtainedYear = gameData.gameYear, obtainedMonth = gameData.gameMonth,
                    equipmentInstance = eq
                )
            )
            discipleTables.storageBagSpiritStones[id] = currentDisciple.equipment.storageBagSpiritStones
            discipleTables.discipleSpiritStones[id] = currentDisciple.equipment.spiritStones
            // 实例保留在实例表（ownerId 清空、isEquipped=false），等级/词条原样
            equipmentInstances.update(equipmentId) { it.copy(isEquipped = false, ownerId = null) }
            clearEquipmentSlot(id, slotToClear)
        } else {
            DomainLog.w(TAG, "unequipEquipmentLogic: equipment instance $equipmentId not found for disciple " +
                "$discipleId, clearing slot only")
            clearEquipmentSlot(id, slotToClear)
        }

        return true
    }

    /** 清空弟子指定装备槽位 */
    private fun MutableGameState.clearEquipmentSlot(id: Int, slot: EquipmentSlot) {
        when (slot) {
            EquipmentSlot.HEAD -> discipleTables.headIds[id] = ""
            EquipmentSlot.BODY -> discipleTables.bodyIds[id] = ""
            EquipmentSlot.HANDS -> discipleTables.handsIds[id] = ""
            EquipmentSlot.FEET -> discipleTables.feetIds[id] = ""
            EquipmentSlot.WEAPON -> discipleTables.weaponIds[id] = ""
            EquipmentSlot.LEGS -> discipleTables.legsIds[id] = ""
        }
    }
}

/** DiscipleTables 六部位槽位 id 读取扩展（声明序=显示序单一真源） */
private fun com.xianxia.sect.core.state.DiscipleTables.slotIdOf(id: Int, slot: EquipmentSlot): String =
    when (slot) {
        EquipmentSlot.HEAD -> headIds[id]
        EquipmentSlot.BODY -> bodyIds[id]
        EquipmentSlot.HANDS -> handsIds[id]
        EquipmentSlot.FEET -> feetIds[id]
        EquipmentSlot.WEAPON -> weaponIds[id]
        EquipmentSlot.LEGS -> legsIds[id]
    }
