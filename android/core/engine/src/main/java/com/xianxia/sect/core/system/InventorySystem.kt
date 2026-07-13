package com.xianxia.sect.core.engine.system

import com.xianxia.sect.core.util.DomainLog
import com.xianxia.sect.core.util.DomainResult
import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.config.InventoryConfig
import com.xianxia.sect.core.registry.ForgeRecipeDatabase.ForgeRecipe
import com.xianxia.sect.core.model.EquipmentInstance
import com.xianxia.sect.core.model.EquipmentStack
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.Herb
import com.xianxia.sect.core.model.HasId
import com.xianxia.sect.core.state.EntityStore
import com.xianxia.sect.core.state.MutableGameState
import com.xianxia.sect.core.state.StackKey
import com.xianxia.sect.core.state.StackableItemStore
import com.xianxia.sect.core.util.StackableItem
import com.xianxia.sect.core.model.ManualInstance
import com.xianxia.sect.core.model.ManualStack
import com.xianxia.sect.core.model.EquipmentSlot
import com.xianxia.sect.core.model.ManualType
import com.xianxia.sect.core.model.Material
import com.xianxia.sect.core.model.MaterialCategory
import com.xianxia.sect.core.model.MerchantItem
import com.xianxia.sect.core.model.Pill
import com.xianxia.sect.core.model.PillCategory
import com.xianxia.sect.core.model.PillGrade
import com.xianxia.sect.core.model.Seed
import com.xianxia.sect.core.model.SpiritStoneExchange
import com.xianxia.sect.core.model.SpiritStoneGrade
import com.xianxia.sect.core.wallet.SpiritStoneWallet
import com.xianxia.sect.core.wallet.SpiritStoneSource
import com.xianxia.sect.core.wallet.SpiritStoneReason
import com.xianxia.sect.core.wallet.DeductResult
import com.xianxia.sect.core.wallet.SpiritStoneOperation
import com.xianxia.sect.core.model.production.BuildingType
import com.xianxia.sect.core.state.GameStateStore
import com.xianxia.sect.core.util.AppError
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

// TickSystem: "InventorySystem"
@com.xianxia.sect.core.engine.annotation.GameService("InventorySystem")
@SystemPriority(order = 50)
@Singleton
class InventorySystem @Inject constructor(
    private val stateStore: GameStateStore,
    private val inventoryConfig: InventoryConfig,
    private val spiritStoneWallet: SpiritStoneWallet
) : GameSystem, ItemAdder {

    companion object {
        private const val TAG = "InventorySystem"
        const val SYSTEM_NAME = "InventorySystem"
        private val VALID_RARITY_RANGE = 1..6
    }


    private fun getMaxSlots(): Int {
        val buildings = stateStore.gameData.value.placedBuildings
        val warehouseCount = buildings.count {
            it.displayName == BuildingType.WAREHOUSE.displayName
        }
        return com.xianxia.sect.core.GameConfig.Warehouse.BASE_CAPACITY +
               warehouseCount * com.xianxia.sect.core.GameConfig.Warehouse.CAPACITY_PER_BUILDING
    }

    val equipmentStacks: StateFlow<List<EquipmentStack>> get() = stateStore.equipmentStacks
    val equipmentInstances: StateFlow<List<EquipmentInstance>> get() = stateStore.equipmentInstances
    val manualStacks: StateFlow<List<ManualStack>> get() = stateStore.manualStacks
    val manualInstances: StateFlow<List<ManualInstance>> get() = stateStore.manualInstances
    val pills: StateFlow<List<Pill>> get() = stateStore.pills
    val materials: StateFlow<List<Material>> get() = stateStore.materials
    val herbs: StateFlow<List<Herb>> get() = stateStore.herbs
    val seeds: StateFlow<List<Seed>> get() = stateStore.seeds

    override val systemName: String = SYSTEM_NAME

    override fun initialize() {
        DomainLog.d(TAG, "InventorySystem initialized")
    }

    override fun release() {
        DomainLog.d(TAG, "InventorySystem released")
    }

    override fun clear() {
        stateStore.update {
            equipmentStacks = EntityStore(emptyList())
            equipmentInstances = EntityStore(emptyList())
            manualStacks = EntityStore(emptyList())
            manualInstances = EntityStore(emptyList())
            pills = EntityStore(emptyList())
            materials = EntityStore(emptyList())
            herbs = EntityStore(emptyList())
            seeds = EntityStore(emptyList())
        }
    }

    override fun clearForSlot(slotId: Int) {
        clear()
    }

    fun loadInventory(
        equipmentStacksList: List<EquipmentStack>,
        equipmentInstancesList: List<EquipmentInstance>,
        manualStacksList: List<ManualStack>,
        manualInstancesList: List<ManualInstance>,
        pillsList: List<Pill>,
        materialsList: List<Material>,
        herbsList: List<Herb>,
        seedsList: List<Seed>
    ) {
        stateStore.update {
            equipmentStacks.replaceAll(equipmentStacksList)
            equipmentInstances.replaceAll(equipmentInstancesList)
            manualStacks.replaceAll(manualStacksList)
            manualInstances.replaceAll(manualInstancesList)
            pills.replaceAll(pillsList)
            materials.replaceAll(materialsList)
            herbs.replaceAll(herbsList)
            seeds.replaceAll(seedsList)
        }
    }

    private fun validateQuantity(quantity: Int, name: String = "quantity"): Boolean {
        if (quantity <= 0) {
            DomainLog.w(TAG, "Invalid $name: $quantity, must be positive")
            return false
        }
        return true
    }

    private fun logWarning(msg: String) = DomainLog.w(TAG, msg)

    private fun getMaxStackForType(type: String): Int = inventoryConfig.getMaxStackSize(type)

    // ── StackableItemStore 统一合并键（单一事实来源，根除 6 套不一致）──

    private fun equipmentStackKey(item: EquipmentStack) =
        StackKey.of(item.name, item.rarity, item.slot.name)

    private fun manualStackKey(item: ManualStack) =
        StackKey.of(item.name, item.rarity, item.type.name)

    private fun pillKey(item: Pill) =
        StackKey.of(item.name, item.rarity, item.category.name, item.grade.name)

    private fun materialKey(item: Material) =
        StackKey.of(item.name, item.rarity, item.category.name)

    private fun herbKey(item: Herb) =
        StackKey.of(item.name, item.rarity, item.category)

    private fun seedKey(item: Seed) =
        StackKey.of(item.name, item.rarity, item.growTime)

    // ── 泛型添加辅助：委托 StackableItemStore 做合并/新增，消除 ~48 同构方法 ──

    private inline fun <reified T> addWithStore(
        item: T,
        currentItems: List<T>,
        noinline stackKeyOf: (T) -> StackKey,
        maxStack: Int,
        noinline writeItems: (List<T>) -> Unit
    ): DomainResult<T> where T : HasId, T : StackableItem {
        // 禁用槽位检查（canAddItem 在外层单独做），仅用 Store 的合并逻辑
        val store = StackableItemStore(
            initialItems = currentItems,
            stackKeyOf = stackKeyOf,
            maxStack = maxStack,
            maxSlots = { Int.MAX_VALUE },
            notFound = { AppError.Domain.Inventory.NotFound(it) }
        )
        val result = store.add(item)
        if (result.isSuccess) {
            writeItems(store.all())
        }
        return result
    }

    // ── 泛型删除 / 查询辅助：消除 ~30 同构方法 ──

    /** 通用删除：委托 StackableItemStore，保留现有行为语义 */
    private fun <T> removeStackable(
        id: String, count: Int, bypassLock: Boolean,
        currentItems: List<T>,
        stackKeyOf: (T) -> StackKey,
        maxStack: Int,
        logType: String,
        writeItems: (List<T>) -> Unit
    ): Boolean where T : HasId, T : StackableItem {
        if (count <= 0) return false
        val existing = currentItems.find { it.id == id } ?: return false
        if (!bypassLock && existing.isLocked) {
            DomainLog.w(TAG, "Cannot remove locked $logType: ${existing.hashCode()}")
            return false
        }
        if (existing.quantity < count) {
            DomainLog.w(TAG, "Cannot remove $count $logType, only ${existing.quantity} available")
            return false
        }
        val store = StackableItemStore(
            initialItems = currentItems, stackKeyOf = stackKeyOf, maxStack = maxStack,
            maxSlots = { Int.MAX_VALUE },
            notFound = { AppError.Domain.Inventory.NotFound(it) }
        )
        val result = store.remove(id, count)
        if (result.isSuccess) { writeItems(store.all()); return true }
        return false
    }

    /** 通用 getById */
    private fun <T : HasId> getById(items: List<T>, id: String): T? = items.find { it.id == id }

    /** 通用 getQuantity */
    private fun <T> getQuantity(items: List<T>, id: String): Int where T : StackableItem =
        (items.find { (it as HasId).id == id })?.quantity ?: 0

    private fun currentEquipmentStacks(): List<EquipmentStack> = stateStore.equipmentStacks.value

    private fun currentEquipmentInstances(): List<EquipmentInstance> = stateStore.equipmentInstances.value

    private fun currentManualStacks(): List<ManualStack> = stateStore.manualStacks.value

    private fun currentManualInstances(): List<ManualInstance> = stateStore.manualInstances.value

    private fun currentPills(): List<Pill> = stateStore.pills.value

    private fun currentMaterials(): List<Material> = stateStore.materials.value

    private fun currentHerbs(): List<Herb> = stateStore.herbs.value

    private fun currentSeeds(): List<Seed> = stateStore.seeds.value

    fun getCapacityInfo(): CapacityInfo = inventoryCapacityInfo(stateStore)

    fun canAddItem(): Boolean = inventoryCanAddItem(stateStore)

    fun canAddItems(count: Int): Boolean = inventoryCanAddItems(stateStore, count)

    fun canAddEquipment(name: String, rarity: Int, slot: EquipmentSlot): Boolean {
        val current = stateStore.equipmentStacks.value
        val maxStack = getMaxStackForType("equipment_stack")
        val canMerge = current.any { it.name == name && it.rarity == rarity && it.slot == slot && it.quantity < maxStack }
        return canMerge || canAddItem()
    }

    fun canAddPill(name: String, rarity: Int, category: PillCategory, grade: PillGrade = PillGrade.MEDIUM): Boolean {
        val current = stateStore.pills.value
        val maxStack = getMaxStackForType("pill")
        val canMerge = current.any { it.name == name && it.rarity == rarity && it.category == category && it.grade == grade && it.quantity < maxStack }
        return canMerge || canAddItem()
    }

    fun canAddManual(name: String, rarity: Int, type: ManualType): Boolean {
        val current = stateStore.manualStacks.value
        val maxStack = getMaxStackForType("manual_stack")
        val canMerge = current.any { it.name == name && it.rarity == rarity && it.type == type && it.quantity < maxStack }
        return canMerge || canAddItem()
    }

    fun canAddMaterial(name: String, rarity: Int, category: MaterialCategory): Boolean {
        val current = stateStore.materials.value
        val maxStack = getMaxStackForType("material")
        val canMerge = current.any { it.name == name && it.rarity == rarity && it.category == category && it.quantity < maxStack }
        return canMerge || canAddItem()
    }

    fun canAddHerb(name: String, rarity: Int, category: String): Boolean {
        val current = stateStore.herbs.value
        val maxStack = getMaxStackForType("herb")
        val canMerge = current.any { it.name == name && it.rarity == rarity && it.category == category && it.quantity < maxStack }
        return canMerge || canAddItem()
    }

    fun canAddSeed(name: String, rarity: Int, growTime: Int): Boolean {
        val current = stateStore.seeds.value
        val maxStack = getMaxStackForType("seed")
        val canMerge = current.any { it.name == name && it.rarity == rarity && it.growTime == growTime && it.quantity < maxStack }
        return canMerge || canAddItem()
    }

    private fun validateStackableItem(name: String, rarity: Int, quantity: Int): DomainResult<Unit> {
        if (name.isBlank()) return DomainResult.Failure(AppError.Domain.Inventory.InvalidName())
        if (rarity !in VALID_RARITY_RANGE) return DomainResult.Failure(AppError.Domain.Inventory.InvalidRarity(rarity))
        if (quantity <= 0) return DomainResult.Failure(AppError.Domain.Inventory.InvalidQuantity(quantity))
        return DomainResult.Success(Unit)
    }

    override fun addEquipmentStack(item: EquipmentStack): DomainResult<EquipmentStack> {
        val validation = validateStackableItem(item.name, item.rarity, item.quantity)
        if (validation is DomainResult.Failure) return validation

        return stateStore.updateAndReturn {
            val maxStack = getMaxStackForType("equipment_stack")

            // Try merge with existing stack
            val existing = equipmentStacks.find { equipmentStackKey(it) == equipmentStackKey(item) }
            if (existing != null) {
                val totalQty = existing.quantity + item.quantity
                val newQty = totalQty.coerceAtMost(maxStack)
                val merged = existing.copy(quantity = newQty)
                equipmentStacks = equipmentStacks.map {
                    if (it.id == existing.id) merged else it
                }
                return@updateAndReturn if (totalQty > maxStack) DomainResult.Partial(merged, totalQty - maxStack)
                    else DomainResult.Success(merged)
            }

            // No merge possible — check capacity
            if (computeSlotCount() >= computeMaxSlots()) {
                return@updateAndReturn DomainResult.Failure(AppError.Domain.Inventory.Full())
            }

            equipmentStacks = equipmentStacks + item
            DomainResult.Success(item)
        }
    }

    override fun addEquipmentInstance(item: EquipmentInstance): DomainResult<EquipmentInstance> {
        if (item.id.isBlank()) return DomainResult.Failure(AppError.Domain.Inventory.NotFound(item.id))
        if (item.name.isBlank()) return DomainResult.Failure(AppError.Domain.Inventory.InvalidName())
        if (item.rarity !in VALID_RARITY_RANGE) return DomainResult.Failure(AppError.Domain.Inventory.InvalidRarity(item.rarity))

        return stateStore.updateAndReturn {
            if (equipmentInstances.any { it.id == item.id }) {
                return@updateAndReturn DomainResult.Failure(AppError.Domain.Inventory.DuplicateId(item.id))
            }
            equipmentInstances = equipmentInstances + item
            DomainResult.Success(item)
        }
    }

    override fun addManualStack(item: ManualStack, merge: Boolean): DomainResult<ManualStack> {
        val validation = validateStackableItem(item.name, item.rarity, item.quantity)
        if (validation is DomainResult.Failure) return validation

        return stateStore.updateAndReturn {
            val maxStack = getMaxStackForType("manual_stack")

            if (merge) {
                val existing = manualStacks.find { manualStackKey(it) == manualStackKey(item) }
                if (existing != null) {
                    val totalQty = existing.quantity + item.quantity
                    val newQty = totalQty.coerceAtMost(maxStack)
                    val merged = existing.copy(quantity = newQty)
                    manualStacks = manualStacks.map {
                        if (it.id == existing.id) merged else it
                    }
                    return@updateAndReturn if (totalQty > maxStack) DomainResult.Partial(merged, totalQty - maxStack)
                        else DomainResult.Success(merged)
                }
            }

            // No merge possible — check capacity
            if (computeSlotCount() >= computeMaxSlots()) {
                return@updateAndReturn DomainResult.Failure(AppError.Domain.Inventory.Full())
            }

            manualStacks = manualStacks + item
            DomainResult.Success(item)
        }
    }

    override fun addManualInstance(item: ManualInstance): DomainResult<ManualInstance> {
        if (item.id.isBlank()) return DomainResult.Failure(AppError.Domain.Inventory.NotFound(item.id))
        if (item.name.isBlank()) return DomainResult.Failure(AppError.Domain.Inventory.InvalidName())
        if (item.rarity !in VALID_RARITY_RANGE) return DomainResult.Failure(AppError.Domain.Inventory.InvalidRarity(item.rarity))

        return stateStore.updateAndReturn {
            if (manualInstances.any { it.id == item.id }) {
                return@updateAndReturn DomainResult.Failure(AppError.Domain.Inventory.DuplicateId(item.id))
            }
            manualInstances = manualInstances + item
            DomainResult.Success(item)
        }
    }

    fun returnEquipmentToStack(instance: EquipmentInstance): DomainResult<EquipmentStack> {
        return stateStore.updateAndReturn {
            val maxStack = getMaxStackForType("equipment_stack")

            val existing = equipmentStacks.find {
                it.name == instance.name && it.rarity == instance.rarity && it.slot == instance.slot
            }
            if (existing != null) {
                val totalQty = existing.quantity + 1
                val newQty = totalQty.coerceAtMost(maxStack)
                val merged = existing.copy(quantity = newQty)
                equipmentStacks = equipmentStacks.map {
                    if (it.id == existing.id) merged else it
                }
                return@updateAndReturn if (totalQty > maxStack) DomainResult.Partial(merged, totalQty - maxStack)
                    else DomainResult.Success(merged)
            }

            if (computeSlotCount() >= computeMaxSlots()) {
                return@updateAndReturn DomainResult.Failure(AppError.Domain.Inventory.Full())
            }
            val newStack = instance.toStack(quantity = 1)
            equipmentStacks = equipmentStacks + newStack
            DomainResult.Success(newStack)
        }
    }

    fun returnManualToStack(instance: ManualInstance): DomainResult<ManualStack> {
        return stateStore.updateAndReturn {
            val maxStack = getMaxStackForType("manual_stack")

            val existing = manualStacks.find {
                it.name == instance.name && it.rarity == instance.rarity && it.type == instance.type
            }
            if (existing != null) {
                val totalQty = existing.quantity + 1
                val newQty = totalQty.coerceAtMost(maxStack)
                val merged = existing.copy(quantity = newQty)
                manualStacks = manualStacks.map {
                    if (it.id == existing.id) merged else it
                }
                return@updateAndReturn if (totalQty > maxStack) DomainResult.Partial(merged, totalQty - maxStack)
                    else DomainResult.Success(merged)
            }

            if (computeSlotCount() >= computeMaxSlots()) {
                return@updateAndReturn DomainResult.Failure(AppError.Domain.Inventory.Full())
            }
            val newStack = instance.toStack(quantity = 1)
            manualStacks = manualStacks + newStack
            DomainResult.Success(newStack)
        }
    }

    fun removeEquipment(id: String, quantity: Int = 1, bypassLock: Boolean = false): Boolean {
        if (quantity <= 0) return false
        return stateStore.updateAndReturn {
            val existing = equipmentStacks.find { it.id == id } ?: return@updateAndReturn false
            if (!bypassLock && existing.isLocked) {
                logWarning("Cannot remove locked equipment: ${existing.hashCode()}")
                return@updateAndReturn false
            }
            if (existing.quantity < quantity) {
                logWarning("Cannot remove $quantity equipment, only ${existing.quantity} available")
                return@updateAndReturn false
            }
            var removed = false
            equipmentStacks = equipmentStacks.mapNotNull { item ->
                if (item.id == id && !removed) {
                    val newQty = item.quantity - quantity
                    when {
                        newQty < 0 -> item
                        newQty == 0 -> { removed = true; null }
                        else -> { removed = true; item.copy(quantity = newQty) }
                    }
                } else item
            }
            true
        }
    }

    fun removeEquipmentInstance(id: String): Boolean {
        return stateStore.updateAndReturn {
            val oldSize = equipmentInstances.size
            equipmentInstances = equipmentInstances.filter { it.id != id }
            equipmentInstances.size < oldSize
        }
    }

    fun updateEquipmentStack(id: String, transform: (EquipmentStack) -> EquipmentStack): Boolean {
        return stateStore.updateAndReturn {
            var found = false
            equipmentStacks = equipmentStacks.map {
                if (it.id == id) {
                    found = true
                    transform(it)
                } else it
            }
            found
        }
    }

    fun updateEquipmentInstance(id: String, transform: (EquipmentInstance) -> EquipmentInstance): Boolean {
        return stateStore.updateAndReturn {
            var found = false
            equipmentInstances = equipmentInstances.map {
                if (it.id == id) {
                    found = true
                    transform(it)
                } else it
            }
            found
        }
    }

    fun getEquipmentStackById(id: String): EquipmentStack? = getById(currentEquipmentStacks(), id)
    fun getEquipmentInstanceById(id: String): EquipmentInstance? = getById(currentEquipmentInstances(), id)

    fun removeManual(id: String, quantity: Int = 1, bypassLock: Boolean = false): Boolean {
        if (quantity <= 0) return false
        return stateStore.updateAndReturn {
            val existing = manualStacks.find { it.id == id } ?: return@updateAndReturn false
            if (!bypassLock && existing.isLocked) {
                logWarning("Cannot remove locked manual: ${existing.hashCode()}")
                return@updateAndReturn false
            }
            if (existing.quantity < quantity) {
                logWarning("Cannot remove $quantity manual, only ${existing.quantity} available")
                return@updateAndReturn false
            }
            var removed = false
            manualStacks = manualStacks.mapNotNull { item ->
                if (item.id == id && !removed) {
                    val newQty = item.quantity - quantity
                    when {
                        newQty < 0 -> item
                        newQty == 0 -> { removed = true; null }
                        else -> { removed = true; item.copy(quantity = newQty) }
                    }
                } else item
            }
            true
        }
    }

    fun removeManualInstance(id: String): Boolean {
        return stateStore.updateAndReturn {
            val oldSize = manualInstances.size
            manualInstances = manualInstances.filter { it.id != id }
            manualInstances.size < oldSize
        }
    }

    fun updateManualStack(id: String, transform: (ManualStack) -> ManualStack): Boolean {
        return stateStore.updateAndReturn {
            var found = false
            manualStacks = manualStacks.map {
                if (it.id == id) {
                    found = true
                    transform(it)
                } else it
            }
            found
        }
    }

    fun updateManualInstance(id: String, transform: (ManualInstance) -> ManualInstance): Boolean {
        return stateStore.updateAndReturn {
            var found = false
            manualInstances = manualInstances.map {
                if (it.id == id) {
                    found = true
                    transform(it)
                } else it
            }
            found
        }
    }

    fun getManualStackById(id: String): ManualStack? = getById(currentManualStacks(), id)
    fun getManualInstanceById(id: String): ManualInstance? = getById(currentManualInstances(), id)

    fun addPill(item: Pill, merge: Boolean = true): DomainResult<Pill> {
        val validation = validateStackableItem(item.name, item.rarity, item.quantity)
        if (validation is DomainResult.Failure) return validation

        return stateStore.updateAndReturn {
            val maxStack = getMaxStackForType("pill")

            if (merge) {
                val existing = pills.find { pillKey(it) == pillKey(item) }
                if (existing != null) {
                    val totalQty = existing.quantity + item.quantity
                    val newQty = totalQty.coerceAtMost(maxStack)
                    val merged = existing.copy(quantity = newQty)
                    pills = pills.map {
                        if (it.id == existing.id) merged else it
                    }
                    return@updateAndReturn if (totalQty > maxStack) DomainResult.Partial(merged, totalQty - maxStack)
                        else DomainResult.Success(merged)
                }
            }

            if (computeSlotCount() >= computeMaxSlots()) {
                return@updateAndReturn DomainResult.Failure(AppError.Domain.Inventory.Full())
            }

            pills = pills + item
            DomainResult.Success(item)
        }
    }

    fun removePill(id: String, quantity: Int = 1, bypassLock: Boolean = false): Boolean {
        if (quantity <= 0) return false
        return stateStore.updateAndReturn {
            val existing = pills.find { it.id == id } ?: return@updateAndReturn false
            if (!bypassLock && existing.isLocked) {
                logWarning("Cannot remove locked pill: ${existing.hashCode()}")
                return@updateAndReturn false
            }
            if (existing.quantity < quantity) {
                logWarning("Cannot remove $quantity pill, only ${existing.quantity} available")
                return@updateAndReturn false
            }
            var removed = false
            pills = pills.mapNotNull { item ->
                if (item.id == id && !removed) {
                    val newQty = item.quantity - quantity
                    when {
                        newQty < 0 -> item
                        newQty == 0 -> { removed = true; null }
                        else -> { removed = true; item.copy(quantity = newQty) }
                    }
                } else item
            }
            true
        }
    }

    fun removePillByName(name: String, rarity: Int, quantity: Int = 1, bypassLock: Boolean = false, grade: PillGrade? = null): Boolean {
        if (!validateQuantity(quantity, "remove quantity")) return false
        val existing = currentPills().find {
            it.name == name && it.rarity == rarity && (grade == null || it.grade == grade)
        }
            ?: return false
        if (!bypassLock && existing.isLocked) {
            logWarning("Cannot remove locked pill: ${existing.name}")
            return false
        }
        if (existing.quantity < quantity) {
            logWarning("Cannot remove $quantity items '$name', only ${existing.quantity} available")
            return false
        }

        return stateStore.updateAndReturn {
            var removed = false
            pills = pills.mapNotNull { pill ->
                if (pill.id == existing.id && !removed) {
                    val newQty = pill.quantity - quantity
                    when {
                        newQty < 0 -> {
                            logWarning("Cannot remove $quantity items, only ${pill.quantity} available")
                            pill
                        }
                        newQty == 0 -> {
                            removed = true
                            null
                        }
                        else -> {
                            removed = true
                            pill.copy(quantity = newQty)
                        }
                    }
                } else pill
            }
            removed
        }
    }

    fun updatePill(id: String, transform: (Pill) -> Pill): Boolean {
        return stateStore.updateAndReturn {
            var found = false
            pills = pills.map {
                if (it.id == id) {
                    found = true
                    transform(it)
                } else it
            }
            found
        }
    }

    fun getPillById(id: String): Pill? = getById(currentPills(), id)
    fun getPillQuantity(id: String): Int = getQuantity(currentPills(), id)

    fun hasPill(name: String, rarity: Int, quantity: Int = 1, grade: PillGrade? = null): Boolean {
        val item = currentPills().find {
            it.name == name && it.rarity == rarity && (grade == null || it.grade == grade)
        } ?: return false
        return item.quantity >= quantity
    }

    fun addMaterial(item: Material, merge: Boolean = true): DomainResult<Material> {
        val validation = validateStackableItem(item.name, item.rarity, item.quantity)
        if (validation is DomainResult.Failure) return validation

        return stateStore.updateAndReturn {
            val maxStack = getMaxStackForType("material")

            if (merge) {
                val existing = materials.find { materialKey(it) == materialKey(item) }
                if (existing != null) {
                    val totalQty = existing.quantity + item.quantity
                    val newQty = totalQty.coerceAtMost(maxStack)
                    val merged = existing.copy(quantity = newQty)
                    materials = materials.map {
                        if (it.id == existing.id) merged else it
                    }
                    return@updateAndReturn if (totalQty > maxStack) DomainResult.Partial(merged, totalQty - maxStack)
                        else DomainResult.Success(merged)
                }
            }

            if (computeSlotCount() >= computeMaxSlots()) {
                return@updateAndReturn DomainResult.Failure(AppError.Domain.Inventory.Full())
            }

            materials = materials + item
            DomainResult.Success(item)
        }
    }

    fun removeMaterial(id: String, quantity: Int = 1, bypassLock: Boolean = false): Boolean {
        if (quantity <= 0) return false
        return stateStore.updateAndReturn {
            val existing = materials.find { it.id == id } ?: return@updateAndReturn false
            if (!bypassLock && existing.isLocked) {
                logWarning("Cannot remove locked material: ${existing.hashCode()}")
                return@updateAndReturn false
            }
            if (existing.quantity < quantity) {
                logWarning("Cannot remove $quantity material, only ${existing.quantity} available")
                return@updateAndReturn false
            }
            var removed = false
            materials = materials.mapNotNull { item ->
                if (item.id == id && !removed) {
                    val newQty = item.quantity - quantity
                    when {
                        newQty < 0 -> item
                        newQty == 0 -> { removed = true; null }
                        else -> { removed = true; item.copy(quantity = newQty) }
                    }
                } else item
            }
            true
        }
    }

    fun removeMaterialByName(name: String, rarity: Int, quantity: Int = 1, bypassLock: Boolean = false): Boolean {
        if (!validateQuantity(quantity, "remove quantity")) return false
        val existing = currentMaterials().find { it.name == name && it.rarity == rarity }
            ?: return false
        if (!bypassLock && existing.isLocked) {
            logWarning("Cannot remove locked material: ${existing.name}")
            return false
        }
        if (existing.quantity < quantity) {
            logWarning("Cannot remove $quantity items '$name', only ${existing.quantity} available")
            return false
        }

        return stateStore.updateAndReturn {
            var removed = false
            materials = materials.mapNotNull { material ->
                if (material.id == existing.id && !removed) {
                    val newQty = material.quantity - quantity
                    when {
                        newQty < 0 -> {
                            logWarning("Cannot remove $quantity items, only ${material.quantity} available")
                            material
                        }
                        newQty == 0 -> {
                            removed = true
                            null
                        }
                        else -> {
                            removed = true
                            material.copy(quantity = newQty)
                        }
                    }
                } else material
            }
            removed
        }
    }

    fun updateMaterial(id: String, transform: (Material) -> Material): Boolean {
        return stateStore.updateAndReturn {
            var found = false
            materials = materials.map {
                if (it.id == id) {
                    found = true
                    transform(it)
                } else it
            }
            found
        }
    }

    fun getMaterialById(id: String): Material? = getById(currentMaterials(), id)
    fun getMaterialQuantity(id: String): Int = getQuantity(currentMaterials(), id)

    fun hasMaterial(name: String, rarity: Int, quantity: Int = 1): Boolean {
        val item = currentMaterials().find { it.name == name && it.rarity == rarity } ?: return false
        return item.quantity >= quantity
    }

    fun addHerb(item: Herb, merge: Boolean = true): DomainResult<Herb> {
        val validation = validateStackableItem(item.name, item.rarity, item.quantity)
        if (validation is DomainResult.Failure) return validation

        return stateStore.updateAndReturn {
            val maxStack = getMaxStackForType("herb")

            if (merge) {
                val existing = herbs.find { herbKey(it) == herbKey(item) }
                if (existing != null) {
                    val totalQty = existing.quantity + item.quantity
                    val newQty = totalQty.coerceAtMost(maxStack)
                    val merged = existing.copy(quantity = newQty)
                    herbs = herbs.map {
                        if (it.id == existing.id) merged else it
                    }
                    return@updateAndReturn if (totalQty > maxStack) DomainResult.Partial(merged, totalQty - maxStack)
                        else DomainResult.Success(merged)
                }
            }

            if (computeSlotCount() >= computeMaxSlots()) {
                return@updateAndReturn DomainResult.Failure(AppError.Domain.Inventory.Full())
            }

            herbs = herbs + item
            DomainResult.Success(item)
        }
    }

    fun removeHerb(id: String, quantity: Int = 1, bypassLock: Boolean = false): Boolean {
        if (quantity <= 0) return false
        return stateStore.updateAndReturn {
            val existing = herbs.find { it.id == id } ?: return@updateAndReturn false
            if (!bypassLock && existing.isLocked) {
                logWarning("Cannot remove locked herb: ${existing.hashCode()}")
                return@updateAndReturn false
            }
            if (existing.quantity < quantity) {
                logWarning("Cannot remove $quantity herb, only ${existing.quantity} available")
                return@updateAndReturn false
            }
            var removed = false
            herbs = herbs.mapNotNull { item ->
                if (item.id == id && !removed) {
                    val newQty = item.quantity - quantity
                    when {
                        newQty < 0 -> item
                        newQty == 0 -> { removed = true; null }
                        else -> { removed = true; item.copy(quantity = newQty) }
                    }
                } else item
            }
            true
        }
    }

    fun removeHerbByName(name: String, rarity: Int, quantity: Int = 1, bypassLock: Boolean = false): Boolean {
        if (!validateQuantity(quantity, "remove quantity")) return false
        val existing = currentHerbs().find { it.name == name && it.rarity == rarity }
            ?: return false
        if (!bypassLock && existing.isLocked) {
            logWarning("Cannot remove locked herb: ${existing.name}")
            return false
        }
        if (existing.quantity < quantity) {
            logWarning("Cannot remove $quantity items '$name', only ${existing.quantity} available")
            return false
        }

        return stateStore.updateAndReturn {
            var removed = false
            herbs = herbs.mapNotNull { herb ->
                if (herb.id == existing.id && !removed) {
                    val newQty = herb.quantity - quantity
                    when {
                        newQty < 0 -> {
                            logWarning("Cannot remove $quantity items, only ${herb.quantity} available")
                            herb
                        }
                        newQty == 0 -> {
                            removed = true
                            null
                        }
                        else -> {
                            removed = true
                            herb.copy(quantity = newQty)
                        }
                    }
                } else herb
            }
            removed
        }
    }

    fun updateHerb(id: String, transform: (Herb) -> Herb): Boolean {
        return stateStore.updateAndReturn {
            var found = false
            herbs = herbs.map {
                if (it.id == id) {
                    found = true
                    transform(it)
                } else it
            }
            found
        }
    }

    fun getHerbById(id: String): Herb? = getById(currentHerbs(), id)
    fun getHerbQuantity(id: String): Int = getQuantity(currentHerbs(), id)

    fun hasHerb(name: String, rarity: Int, quantity: Int = 1): Boolean {
        val item = currentHerbs().find { it.name == name && it.rarity == rarity } ?: return false
        return item.quantity >= quantity
    }

    fun addSeed(item: Seed, merge: Boolean = true): DomainResult<Seed> {
        val validation = validateStackableItem(item.name, item.rarity, item.quantity)
        if (validation is DomainResult.Failure) return validation

        return stateStore.updateAndReturn {
            val maxStack = getMaxStackForType("seed")

            if (merge) {
                val existing = seeds.find {
                    it.name == item.name && it.rarity == item.rarity && it.growTime == item.growTime
                }
                if (existing != null) {
                    val totalQty = existing.quantity + item.quantity
                    val newQty = totalQty.coerceAtMost(maxStack)
                    val merged = existing.copy(quantity = newQty)
                    seeds = seeds.map {
                        if (it.id == existing.id) it.copy(quantity = newQty) else it
                    }
                    return@updateAndReturn if (totalQty > maxStack) DomainResult.Partial(merged, totalQty - maxStack) else DomainResult.Success(merged)
                }
            }

            if (computeSlotCount() >= computeMaxSlots()) {
                return@updateAndReturn DomainResult.Failure(AppError.Domain.Inventory.Full())
            }
            seeds = seeds + item
            DomainResult.Success(item)
        }
    }

    fun removeSeed(id: String, quantity: Int = 1, bypassLock: Boolean = false): Boolean {
        if (quantity <= 0) return false
        return stateStore.updateAndReturn {
            val existing = seeds.find { it.id == id } ?: return@updateAndReturn false
            if (!bypassLock && existing.isLocked) {
                logWarning("Cannot remove locked seed: ${existing.name}")
                return@updateAndReturn false
            }
            if (existing.quantity < quantity) {
                logWarning("Cannot remove $quantity seed, only ${existing.quantity} available")
                return@updateAndReturn false
            }
            var removed = false
            seeds = seeds.mapNotNull { seed ->
                if (seed.id == id && !removed) {
                    val newQty = seed.quantity - quantity
                    when {
                        newQty < 0 -> seed
                        newQty == 0 -> { removed = true; null }
                        else -> { removed = true; seed.copy(quantity = newQty) }
                    }
                } else seed
            }
            true
        }
    }

    fun addSeedSync(item: Seed, merge: Boolean = true): DomainResult<Seed> {
        val validation = validateStackableItem(item.name, item.rarity, item.quantity)
        if (validation is DomainResult.Failure) return validation

        return stateStore.updateAndReturn {
            if (merge) {
                val existing = seeds.find {
                    it.name == item.name && it.rarity == item.rarity && it.growTime == item.growTime
                }
                if (existing != null) {
                    val maxStack = getMaxStackForType("seed")
                    val totalQty = existing.quantity + item.quantity
                    val newQty = totalQty.coerceAtMost(maxStack)
                    seeds = seeds.map {
                        if (it.id == existing.id) it.copy(quantity = newQty) else it
                    }
                    val merged = existing.copy(quantity = newQty)
                    return@updateAndReturn if (totalQty > maxStack) DomainResult.Partial(merged, totalQty - maxStack) else DomainResult.Success(merged)
                }
            }
            if (computeSlotCount() >= computeMaxSlots()) {
                return@updateAndReturn DomainResult.Failure(AppError.Domain.Inventory.Full())
            }
            seeds = seeds + item
            DomainResult.Success(item)
        }
    }

    fun removeSeedSync(id: String, quantity: Int = 1, bypassLock: Boolean = false): Boolean {
        if (quantity <= 0) return false
        return stateStore.updateAndReturn {
            val existing = seeds.find { it.id == id } ?: return@updateAndReturn false
            if (!bypassLock && existing.isLocked) {
                logWarning("Cannot remove locked seed: ${existing.name}")
                return@updateAndReturn false
            }
            if (existing.quantity < quantity) {
                logWarning("Cannot remove $quantity items, only ${existing.quantity} available")
                return@updateAndReturn false
            }
            var removed = false
            seeds = seeds.mapNotNull { seed ->
                if (seed.id == id && !removed) {
                    val newQty = seed.quantity - quantity
                    when {
                        newQty < 0 -> seed
                        newQty == 0 -> { removed = true; null }
                        else -> { removed = true; seed.copy(quantity = newQty) }
                    }
                } else seed
            }
            removed
        }
    }

    fun removeSeedByName(name: String, rarity: Int, quantity: Int = 1, bypassLock: Boolean = false): Boolean {
        if (!validateQuantity(quantity, "remove quantity")) return false
        val existing = currentSeeds().find { it.name == name && it.rarity == rarity }
            ?: return false
        if (!bypassLock && existing.isLocked) {
            logWarning("Cannot remove locked seed: ${existing.name}")
            return false
        }
        if (existing.quantity < quantity) {
            logWarning("Cannot remove $quantity items '$name', only ${existing.quantity} available")
            return false
        }

        return stateStore.updateAndReturn {
            var removed = false
            seeds = seeds.mapNotNull { seed ->
                if (seed.id == existing.id && !removed) {
                    val newQty = seed.quantity - quantity
                    when {
                        newQty < 0 -> {
                            logWarning("Cannot remove $quantity items, only ${seed.quantity} available")
                            seed
                        }
                        newQty == 0 -> {
                            removed = true
                            null
                        }
                        else -> {
                            removed = true
                            seed.copy(quantity = newQty)
                        }
                    }
                } else seed
            }
            removed
        }
    }

    fun updateSeed(id: String, transform: (Seed) -> Seed): Boolean {
        return stateStore.updateAndReturn {
            var found = false
            seeds = seeds.map {
                if (it.id == id) {
                    found = true
                    transform(it)
                } else it
            }
            found
        }
    }

    fun getSeedById(id: String): Seed? = getById(currentSeeds(), id)
    fun getSeedQuantity(id: String): Int = getQuantity(currentSeeds(), id)

    fun hasSeed(name: String, rarity: Int, quantity: Int = 1): Boolean {
        val item = currentSeeds().find { it.name == name && it.rarity == rarity } ?: return false
        return item.quantity >= quantity
    }

    fun getItemCountByType(type: String): Int {
        return when (type.lowercase(java.util.Locale.getDefault())) {
            "equipment_stack" -> currentEquipmentStacks().size
            "equipment_instance" -> currentEquipmentInstances().size
            "manual_stack" -> currentManualStacks().size
            "manual_instance" -> currentManualInstances().size
            "equipment" -> currentEquipmentStacks().size + currentEquipmentInstances().size
            "manual" -> currentManualStacks().size + currentManualInstances().size
            "pill" -> currentPills().size
            "material" -> currentMaterials().size
            "herb" -> currentHerbs().size
            "seed" -> currentSeeds().size
            else -> 0
        }
    }

    fun sortWarehouse() {
        stateStore.update {
            equipmentStacks.replaceAll(equipmentStacks.items.sortedWith(compareByDescending<EquipmentStack> { it.rarity }.thenBy { it.name }))
            equipmentInstances.replaceAll(equipmentInstances.items.sortedWith(compareByDescending<EquipmentInstance> { it.rarity }.thenBy { it.name }))
            manualStacks.replaceAll(manualStacks.items.sortedWith(compareByDescending<ManualStack> { it.rarity }.thenBy { it.name }))
            manualInstances.replaceAll(manualInstances.items.sortedWith(compareByDescending<ManualInstance> { it.rarity }.thenBy { it.name }))
            pills.replaceAll(pills.items.sortedWith(compareByDescending<Pill> { it.rarity }.thenBy { it.name }))
            materials.replaceAll(materials.all().sortedWith(compareByDescending<Material> { it.rarity }.thenBy { it.name }))
            herbs.replaceAll(herbs.all().sortedWith(compareByDescending<Herb> { it.rarity }.thenBy { it.name }))
            seeds.replaceAll(seeds.all().sortedWith(compareByDescending<Seed> { it.rarity }.thenBy { it.name }))
        }
    }

    fun hasEnoughSpiritStones(currentStones: Long, required: Long): Boolean {
        return currentStones >= required
    }

    // ── Spirit Stone operations (delegated to SpiritStoneWallet) ──────────

    /** @deprecated 使用 [SpiritStoneWallet.balance] 替代 */
    @Deprecated("Use spiritStoneWallet.balance()")
    fun getSpiritStones(grade: SpiritStoneGrade): Long {
        return spiritStoneWallet.balance(grade)
    }

    /** @deprecated 使用 [SpiritStoneWallet.canAfford] 替代 */
    @Deprecated("Use spiritStoneWallet.canAfford()")
    fun canAfford(amount: Long): Boolean = spiritStoneWallet.canAfford(amount)

    /** @deprecated 使用 [SpiritStoneWallet.canAfford] 替代 */
    @Deprecated("Use spiritStoneWallet.canAfford()")
    fun canAfford(amount: Long, grade: SpiritStoneGrade): Boolean {
        return spiritStoneWallet.canAfford(amount, grade)
    }

    /** @deprecated 使用 [SpiritStoneWallet.deduct] 替代 */
    @Deprecated("Use spiritStoneWallet.deduct()")
    fun deductSpiritStones(amount: Long): Long =
        deductSpiritStones(amount, SpiritStoneGrade.LOW)

    /** @deprecated 使用 [SpiritStoneWallet.deduct] 替代 */
    @Deprecated("Use spiritStoneWallet.deduct()")
    fun deductSpiritStones(amount: Long, grade: SpiritStoneGrade): Long {
        val result = stateStore.updateAndReturn {
            spiritStoneWallet.deduct(this, amount, grade, SpiritStoneReason.Internal, SpiritStoneSource.Internal, true)
        }
        return when (result) {
            is DeductResult.Success -> result.balanceAfter
            is DeductResult.Insufficient -> result.balance
            DeductResult.Invalid -> spiritStoneWallet.balance(grade)
        }
    }

    /** @deprecated 使用 [SpiritStoneWallet.add] 替代 */
    @Deprecated("Use spiritStoneWallet.add()")
    fun addSpiritStones(amount: Long): Long =
        addSpiritStones(amount, SpiritStoneGrade.LOW)

    /** @deprecated 使用 [SpiritStoneWallet.add] 替代 */
    @Deprecated("Use spiritStoneWallet.add()")
    fun addSpiritStones(amount: Long, grade: SpiritStoneGrade): Long {
        return stateStore.updateAndReturn {
            spiritStoneWallet.add(this, amount, grade, SpiritStoneSource.Internal)
        }
    }

    /** @deprecated 使用 [SpiritStoneWallet.batch] 替代 */
    @Deprecated("Use spiritStoneWallet.batch()")
    fun exchangeSpiritStones(
        quantity: Long,
        source: SpiritStoneGrade,
        target: SpiritStoneGrade
    ): Boolean {
        if (quantity <= 0 || source == target) return false

        val (converted, remaining) = SpiritStoneExchange.exchange(quantity, source, target)
        if (converted <= 0) return false

        val result = stateStore.updateAndReturn {
            spiritStoneWallet.batch(this, listOf(
                SpiritStoneOperation(
                    delta = -(quantity - remaining), grade = source,
                    reason = SpiritStoneReason.Exchange, source = SpiritStoneSource.Internal
                ),
                SpiritStoneOperation(
                    delta = converted, grade = target,
                    reason = SpiritStoneReason.Exchange, source = SpiritStoneSource.Internal
                )
            ), autoConvert = true)
        }
        return result.failedCount == 0
    }

    fun createEquipmentFromRecipe(recipe: ForgeRecipe): EquipmentStack =
        InventoryFactories.createEquipmentFromRecipe(recipe)

    fun createEquipmentFromMerchantItem(item: MerchantItem): EquipmentStack =
        InventoryFactories.createEquipmentFromMerchantItem(item)

    fun createManualFromMerchantItem(item: MerchantItem): ManualStack =
        InventoryFactories.createManualFromMerchantItem(item)

    fun createPillFromMerchantItem(item: MerchantItem): Pill =
        InventoryFactories.createPillFromMerchantItem(item)

    fun createMaterialFromMerchantItem(item: MerchantItem): Material =
        InventoryFactories.createMaterialFromMerchantItem(item)

    fun createHerbFromMerchantItem(item: MerchantItem): Herb =
        InventoryFactories.createHerbFromMerchantItem(item)

    fun createSeedFromMerchantItem(item: MerchantItem): Seed =
        InventoryFactories.createSeedFromMerchantItem(item)

    override fun addPill(item: Pill): DomainResult<Pill> = addPill(item, merge = true)
    override fun addMaterial(item: Material): DomainResult<Material> = addMaterial(item, merge = true)
    override fun addHerb(item: Herb): DomainResult<Herb> = addHerb(item, merge = true)
    override fun addSeed(item: Seed): DomainResult<Seed> = addSeed(item, merge = true)
}
