package com.xianxia.sect.core.engine.domain.disciple

import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.config.InventoryConfig
import com.xianxia.sect.core.model.*
import com.xianxia.sect.core.state.GameStateStore
import com.xianxia.sect.core.state.MutableGameState
import com.xianxia.sect.core.util.AppError
import com.xianxia.sect.core.util.DomainLog
import com.xianxia.sect.core.util.DomainResult
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 弟子装备穿卸服务。
 *
 * ## 职责
 * 1. **装备穿戴** — [equipEquipment] 为弟子穿戴装备，旧装备自动卸入储物袋
 * 2. **装备卸下** — [unequipEquipment] 从弟子身上卸下装备，自动放入储物袋
 */
@Singleton
class DiscipleEquipmentService @Inject constructor(
    private val stateStore: GameStateStore,
    private val inventoryConfig: InventoryConfig,
    private val inventorySystem: com.xianxia.sect.core.engine.system.InventorySystem,
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
            val id = discipleId.toIntOrNull()
            if (id == null || !discipleTables.ids.contains(id)) {
                error = AppError.Domain.Disciple.NotFound(discipleId); return@update
            }

            val equipmentStack = equipmentStacks.get(equipmentId)
            val equipmentInstance = equipmentInstances.get(equipmentId)

            if (equipmentStack == null && equipmentInstance == null) {
                error = AppError.Domain.Disciple.NotFound(discipleId); return@update
            }

            val discipleRealm = discipleTables.realms[id]

            if (equipmentInstance != null) {
                if (equipmentInstance.isEquipped) {
                    if (equipmentInstance.ownerId == discipleId) {
                        error = AppError.Domain.Disciple.AlreadyEquipped(
                            slot = equipmentInstance.slot.name
                        ); return@update
                    }
                    error = AppError.Domain.Disciple.AlreadyEquipped(
                        slot = equipmentInstance.slot.name
                    ); return@update
                }
                if (!GameConfig.Realm.meetsRealmRequirement(discipleRealm, equipmentInstance.minRealm)) {
                    error = AppError.Domain.Disciple.RealmTooLow(
                        discipleId = discipleId,
                        need = "境界${equipmentInstance.minRealm}"
                    ); return@update
                }
            } else if (equipmentStack != null) {
                if (!GameConfig.Realm.meetsRealmRequirement(discipleRealm, equipmentStack.minRealm)) {
                    error = AppError.Domain.Disciple.RealmTooLow(
                        discipleId = discipleId,
                        need = "境界${equipmentStack.minRealm}"
                    ); return@update
                }
            }

            val slot = equipmentInstance?.slot ?: equipmentStack?.slot ?: run {
                error = AppError.Domain.Disciple.SlotInvalid("无法确定装备槽位"); return@update
            }
            val equipName = equipmentStack?.name ?: equipmentInstance?.name ?: ""

            val oldEquipId = when (slot) {
                EquipmentSlot.WEAPON -> discipleTables.weaponIds[id]
                EquipmentSlot.ARMOR -> discipleTables.armorIds[id]
                EquipmentSlot.BOOTS -> discipleTables.bootsIds[id]
                EquipmentSlot.ACCESSORY -> discipleTables.accessoryIds[id]
                else -> ""
            }
            if (oldEquipId.isNotEmpty()) {
                val unequipped = unequipEquipmentLogic(discipleId, oldEquipId)
                if (!unequipped) {
                    DomainLog.w(TAG, "equipEquipment: failed to unequip $oldEquipId, aborting equip")
                    error = AppError.Domain.Disciple.SlotInvalid("卸下旧装备失败 $oldEquipId")
                    return@update
                }
            }

            val stack = equipmentStacks.get(equipmentId)
            val instance = equipmentInstances.get(equipmentId)

            if (stack != null) {
                val equippedId = UUID.randomUUID().toString()
                val equippedItem = stack.toInstance(id = equippedId, ownerId = discipleId, isEquipped = true)
                if (stack.quantity > 1) {
                    equipmentStacks.update(equipmentId) { it.copy(quantity = it.quantity - 1) }
                } else {
                    equipmentStacks.remove(equipmentId)
                }
                equipmentInstances = equipmentInstances + equippedItem
                when (slot) {
                    EquipmentSlot.WEAPON -> discipleTables.weaponIds[id] = equippedId
                    EquipmentSlot.ARMOR -> discipleTables.armorIds[id] = equippedId
                    EquipmentSlot.BOOTS -> discipleTables.bootsIds[id] = equippedId
                    EquipmentSlot.ACCESSORY -> discipleTables.accessoryIds[id] = equippedId
                    else -> {}
                }
            } else if (instance != null) {
                when (slot) {
                    EquipmentSlot.WEAPON -> discipleTables.weaponIds[id] = equipmentId
                    EquipmentSlot.ARMOR -> discipleTables.armorIds[id] = equipmentId
                    EquipmentSlot.BOOTS -> discipleTables.bootsIds[id] = equipmentId
                    EquipmentSlot.ACCESSORY -> discipleTables.accessoryIds[id] = equipmentId
                    else -> {}
                }
                equipmentInstances.update(equipmentId) { it.copy(isEquipped = true, ownerId = discipleId) }
            }

            // 记录装备日志
            val equipAge = discipleTables.ages[id]
            val equipEvents = discipleTables.lifeEvents.getOrDefault(id, emptyList())
            if (oldEquipId.isNotEmpty()) {
                val oldName = equipmentInstances.get(oldEquipId)?.name ?: "旧装备"
                discipleTables.lifeEvents[id] = equipEvents +
                    "${equipAge}岁：将${oldName}替换为${equipName}"
            } else {
                discipleTables.lifeEvents[id] = equipEvents +
                    "${equipAge}岁：装备了${equipName}"
            }

            error = null
        }
        val finalError = error
        return if (finalError == null) DomainResult.Success(Unit) else DomainResult.Failure(finalError)
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
            val isEquipped = discipleTables.weaponIds[id] == equipmentId ||
                discipleTables.armorIds[id] == equipmentId ||
                discipleTables.bootsIds[id] == equipmentId ||
                discipleTables.accessoryIds[id] == equipmentId
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

        val weaponId = discipleTables.weaponIds[id]
        val armorId = discipleTables.armorIds[id]
        val bootsId = discipleTables.bootsIds[id]
        val accessoryId = discipleTables.accessoryIds[id]

        // 仅判断装备所属槽位（槽位清空移到入仓成功后，失败时保留槽位不悬空）
        val slotToClear = when {
            weaponId == equipmentId -> { "weapon" }
            armorId == equipmentId -> { "armor" }
            bootsId == equipmentId -> { "boots" }
            accessoryId == equipmentId -> { "accessory" }
            else -> null
        }

        if (slotToClear != null) {
            val eq = equipmentInstances.get(equipmentId)

            if (eq != null) {
                // 凭据类路径：抑制溢出转邮件——仅全部入仓成功才清槽位+删实例；
                // Partial/Failure 保留槽位与实例，玩家清理后重试补齐，
                // 避免"邮件已发 + 槽位悬空/实例孤儿"（对抗性审查 H1 修复）
                val result = inventorySystem.withOverflowMailSuppressed {
                    inventorySystem.addEquipmentStack(eq.toStack(quantity = 1))
                }
                if (result is DomainResult.Success) {
                    clearEquipmentSlot(id, slotToClear)
                    equipmentInstances = equipmentInstances.filter { it.id != equipmentId }
                } else {
                    DomainLog.w(TAG, "卸下装备失败：${eq.name} 仓库空间不足，槽位与装备保留，清理后可重试")
                    return false
                }
            } else {
                DomainLog.w(TAG, "unequipEquipmentLogic: equipment instance $equipmentId not found for disciple $discipleId, clearing slot only")
                clearEquipmentSlot(id, slotToClear)
            }

            return true
        }
        return false
    }

    /** 清空弟子指定装备槽位 */
    private fun MutableGameState.clearEquipmentSlot(id: Int, slot: String) {
        when (slot) {
            "weapon" -> discipleTables.weaponIds[id] = ""
            "armor" -> discipleTables.armorIds[id] = ""
            "boots" -> discipleTables.bootsIds[id] = ""
            "accessory" -> discipleTables.accessoryIds[id] = ""
        }
    }
}
