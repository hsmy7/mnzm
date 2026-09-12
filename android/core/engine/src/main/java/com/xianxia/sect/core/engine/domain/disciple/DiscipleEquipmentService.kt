package com.xianxia.sect.core.engine.domain.disciple

import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.EquipmentSlot
import com.xianxia.sect.core.model.EquipmentStack
import com.xianxia.sect.core.model.EquipmentInstance
import com.xianxia.sect.core.model.accessoryId
import com.xianxia.sect.core.model.armorId
import com.xianxia.sect.core.model.bootsId
import com.xianxia.sect.core.model.weaponId
import com.xianxia.sect.core.state.GameStateStore
import com.xianxia.sect.core.state.MutableGameState
import com.xianxia.sect.core.util.AppError
import com.xianxia.sect.core.util.DomainLog
import com.xianxia.sect.core.util.DomainResult
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import com.xianxia.sect.core.model.StorageBagItem
import com.xianxia.sect.core.util.StorageBagUtils



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
        val equipmentStack = equipmentStacks.get(equipmentId)
        val equipmentInstance = equipmentInstances.get(equipmentId)
        if (equipmentStack == null && equipmentInstance == null) {
            return AppError.Domain.Disciple.NotFound(discipleId)
        }

        // 境界与占用校验
        val realmError = checkEquipRealmRequirement(
            id = id,
            discipleId = discipleId,
            equipmentStack = equipmentStack,
            equipmentInstance = equipmentInstance
        )
        if (realmError != null) return realmError

        // 确定装备槽位与名称
        val slot = equipmentInstance?.slot ?: equipmentStack?.slot ?: run {
            return AppError.Domain.Disciple.SlotInvalid("无法确定装备槽位")
        }
        val equipName = equipmentStack?.name ?: equipmentInstance?.name ?: ""

        // 卸下旧装备（失败则中止穿戴）
        val oldEquipId = currentEquipId(id = id, slot = slot)
        if (oldEquipId.isNotEmpty()) {
            val unequipped = unequipEquipmentLogic(discipleId, oldEquipId)
            if (!unequipped) {
                DomainLog.w(TAG, "equipEquipment: failed to unequip $oldEquipId, aborting equip")
                return AppError.Domain.Disciple.SlotInvalid("卸下旧装备失败 $oldEquipId")
            }
        }

        // 穿戴新装备
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
        equipmentStack: EquipmentStack?,
        equipmentInstance: EquipmentInstance?
    ): AppError.Domain.Disciple? {
        val discipleRealm = discipleTables.realms[id]
        if (equipmentInstance != null) {
            if (equipmentInstance.isEquipped) {
                return AppError.Domain.Disciple.AlreadyEquipped(
                    slot = equipmentInstance.slot.name
                )
            }
            if (!GameConfig.Realm.meetsRealmRequirement(discipleRealm, equipmentInstance.minRealm)) {
                return AppError.Domain.Disciple.RealmTooLow(
                    discipleId = discipleId,
                    need = "境界${equipmentInstance.minRealm}"
                )
            }
        } else if (equipmentStack != null) {
            if (!GameConfig.Realm.meetsRealmRequirement(discipleRealm, equipmentStack.minRealm)) {
                return AppError.Domain.Disciple.RealmTooLow(
                    discipleId = discipleId,
                    need = "境界${equipmentStack.minRealm}"
                )
            }
        }
        return null
    }

    /** 当前槽位已装备的装备 id */
    private fun MutableGameState.currentEquipId(id: Int, slot: EquipmentSlot): String = when (slot) {
        EquipmentSlot.WEAPON -> discipleTables.weaponIds[id]
        EquipmentSlot.ARMOR -> discipleTables.armorIds[id]
        EquipmentSlot.BOOTS -> discipleTables.bootsIds[id]
        EquipmentSlot.ACCESSORY -> discipleTables.accessoryIds[id]
        else -> ""
    }

    /** 穿戴装备入槽：堆叠扣减/移除或实例标记 */
    private fun MutableGameState.wearEquipment(
        equipmentId: String,
        discipleId: String,
        id: Int,
        slot: EquipmentSlot
    ) {
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
    }

    /** 记录装备日志：替换 / 首次穿戴 */
    private fun MutableGameState.recordEquipLog(
        id: Int,
        oldEquipId: String,
        equipName: String
    ) {
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
                // 卸下装备实例直接铸造入袋（容量无上限，永不失败），
                // 不再转仓库堆叠（不占仓库槽位、无溢出邮件路径，防复制逻辑不再需要）
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
                equipmentInstances = equipmentInstances.filter { it.id != equipmentId }
                clearEquipmentSlot(id, slotToClear)
            } else {
                DomainLog.w(TAG, "unequipEquipmentLogic: equipment instance $equipmentId not found for disciple " +
                    "$discipleId, clearing slot only")
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
