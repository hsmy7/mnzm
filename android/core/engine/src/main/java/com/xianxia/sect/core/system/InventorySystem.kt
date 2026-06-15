package com.xianxia.sect.core.engine.system

import com.xianxia.sect.core.util.DomainLog
import com.xianxia.sect.core.util.DomainResult
import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.config.InventoryConfig
import com.xianxia.sect.core.registry.ForgeRecipeDatabase.ForgeRecipe
import com.xianxia.sect.core.model.EquipmentInstance
import com.xianxia.sect.core.model.EquipmentStack
import com.xianxia.sect.core.model.Herb
import com.xianxia.sect.core.model.HasId
import com.xianxia.sect.core.state.EntityStore
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
import com.xianxia.sect.core.model.production.BuildingType
import com.xianxia.sect.core.state.GameStateStore
import com.xianxia.sect.core.util.AppError
import com.xianxia.sect.core.util.CoroutineScopeProvider
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

// TickSystem: "InventorySystem"
@com.xianxia.sect.core.engine.annotation.GameService("InventorySystem")
@SystemPriority(order = 50)
@Singleton
class InventorySystem @Inject constructor(
    private val stateStore: GameStateStore,
    private val scopeProvider: CoroutineScopeProvider,
    private val inventoryConfig: InventoryConfig
) : GameSystem, ItemAdder {

    companion object {
        private const val TAG = "InventorySystem"
        const val SYSTEM_NAME = "InventorySystem"
        private val VALID_RARITY_RANGE = 1..6
    }

    override val focusDomain = FocusDomain.WAREHOUSE
    override val settlementPhase = 1  // 上旬：功法熟练度 + 装备孕养

    private fun getMaxSlots(): Int {
        val buildings = stateStore.gameData.value.placedBuildings
        val warehouseCount = buildings.count {
            it.displayName == BuildingType.WAREHOUSE.displayName
        }
        return com.xianxia.sect.core.GameConfig.Warehouse.BASE_CAPACITY +
               warehouseCount * com.xianxia.sect.core.GameConfig.Warehouse.CAPACITY_PER_BUILDING
    }

    private val scope get() = scopeProvider.scope

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

    override suspend fun clear() {
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

    override suspend fun clearForSlot(slotId: Int) {
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
        val ts = stateStore.currentTransactionMutableState()
        if (ts != null) {
            ts.equipmentStacks.replaceAll(equipmentStacksList)
            ts.equipmentInstances.replaceAll(equipmentInstancesList)
            ts.manualStacks.replaceAll(manualStacksList)
            ts.manualInstances.replaceAll(manualInstancesList)
            ts.pills.replaceAll(pillsList)
            ts.materials.replaceAll(materialsList)
            ts.herbs.replaceAll(herbsList)
            ts.seeds.replaceAll(seedsList)
        } else {
            scope.launch { stateStore.update {
                equipmentStacks.replaceAll(equipmentStacksList)
                equipmentInstances.replaceAll(equipmentInstancesList)
                manualStacks.replaceAll(manualStacksList)
                manualInstances.replaceAll(manualInstancesList)
                pills.replaceAll(pillsList)
                materials.replaceAll(materialsList)
                herbs.replaceAll(herbsList)
                seeds.replaceAll(seedsList)
            } }
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

    // 事务外读取独立 StateFlow（同步更新），避免 unifiedState stateIn(Dispatchers.Default) 异步延迟
    private fun currentEquipmentStacks(): List<EquipmentStack> =
        stateStore.currentTransactionMutableState()?.equipmentStacks?.items ?: stateStore.equipmentStacks.value

    private fun currentEquipmentInstances(): List<EquipmentInstance> =
        stateStore.currentTransactionMutableState()?.equipmentInstances?.items ?: stateStore.equipmentInstances.value

    private fun currentManualStacks(): List<ManualStack> =
        stateStore.currentTransactionMutableState()?.manualStacks?.items ?: stateStore.manualStacks.value

    private fun currentManualInstances(): List<ManualInstance> =
        stateStore.currentTransactionMutableState()?.manualInstances?.items ?: stateStore.manualInstances.value

    private fun currentPills(): List<Pill> =
        stateStore.currentTransactionMutableState()?.pills?.items ?: stateStore.pills.value

    private fun currentMaterials(): List<Material> =
        stateStore.currentTransactionMutableState()?.materials?.items ?: stateStore.materials.value

    private fun currentHerbs(): List<Herb> =
        stateStore.currentTransactionMutableState()?.herbs?.items ?: stateStore.herbs.value

    private fun currentSeeds(): List<Seed> =
        stateStore.currentTransactionMutableState()?.seeds?.items ?: stateStore.seeds.value

    private fun getTotalSlotCount(): Int {
        return currentEquipmentStacks().size +
               currentManualStacks().size +
               currentPills().size +
               currentMaterials().size +
               currentHerbs().size +
               currentSeeds().size
    }

    fun getCapacityInfo(): CapacityInfo {
        val current = getTotalSlotCount()
        val maxSlots = getMaxSlots()
        return CapacityInfo(
            currentSlots = current,
            maxSlots = maxSlots,
            remainingSlots = maxSlots - current,
            isFull = current >= maxSlots
        )
    }

    fun canAddItem(): Boolean {
        val full = getTotalSlotCount() >= getMaxSlots()
        if (full) stateStore.warehouseFullEvent.tryEmit(Unit)
        return !full
    }

    fun canAddItems(count: Int): Boolean {
        val full = getTotalSlotCount() + count > getMaxSlots()
        if (full) stateStore.warehouseFullEvent.tryEmit(Unit)
        return !full
    }

    fun canAddEquipment(name: String, rarity: Int, slot: EquipmentSlot): Boolean {
        val ts = stateStore.currentTransactionMutableState()
        val current = ts?.equipmentStacks ?: stateStore.equipmentStacks.value
        val maxStack = getMaxStackForType("equipment_stack")
        val canMerge = current.any { it.name == name && it.rarity == rarity && it.slot == slot && it.quantity < maxStack }
        return canMerge || canAddItem()
    }

    fun canAddPill(name: String, rarity: Int, category: PillCategory, grade: PillGrade = PillGrade.MEDIUM): Boolean {
        val ts = stateStore.currentTransactionMutableState()
        val current = ts?.pills ?: stateStore.pills.value
        val maxStack = getMaxStackForType("pill")
        val canMerge = current.any { it.name == name && it.rarity == rarity && it.category == category && it.grade == grade && it.quantity < maxStack }
        return canMerge || canAddItem()
    }

    fun canAddManual(name: String, rarity: Int, type: ManualType): Boolean {
        val ts = stateStore.currentTransactionMutableState()
        val current = ts?.manualStacks ?: stateStore.manualStacks.value
        val maxStack = getMaxStackForType("manual_stack")
        val canMerge = current.any { it.name == name && it.rarity == rarity && it.type == type && it.quantity < maxStack }
        return canMerge || canAddItem()
    }

    fun canAddMaterial(name: String, rarity: Int, category: MaterialCategory): Boolean {
        val ts = stateStore.currentTransactionMutableState()
        val current = ts?.materials ?: stateStore.materials.value
        val maxStack = getMaxStackForType("material")
        val canMerge = current.any { it.name == name && it.rarity == rarity && it.category == category && it.quantity < maxStack }
        return canMerge || canAddItem()
    }

    fun canAddHerb(name: String, rarity: Int, category: String): Boolean {
        val ts = stateStore.currentTransactionMutableState()
        val current = ts?.herbs ?: stateStore.herbs.value
        val maxStack = getMaxStackForType("herb")
        val canMerge = current.any { it.name == name && it.rarity == rarity && it.category == category && it.quantity < maxStack }
        return canMerge || canAddItem()
    }

    fun canAddSeed(name: String, rarity: Int, growTime: Int): Boolean {
        val ts = stateStore.currentTransactionMutableState()
        val current = ts?.seeds ?: stateStore.seeds.value
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

        val ts = stateStore.currentTransactionMutableState()
        val currentItems: List<EquipmentStack> = ts?.equipmentStacks?.items ?: stateStore.equipmentStacks.value
        val maxStack = getMaxStackForType("equipment_stack")
        if (!canAddItem() && currentItems.none { equipmentStackKey(it) == equipmentStackKey(item) }) {
            return DomainResult.Failure(AppError.Domain.Inventory.Full())
        }

        val result = addWithStore(
            item = item,
            currentItems = currentItems,
            stackKeyOf = ::equipmentStackKey,
            maxStack = maxStack,
            writeItems = { items ->
                val store = EntityStore(items)
                if (ts != null) ts.equipmentStacks = store
                else scope.launch { stateStore.update { equipmentStacks = store } }
            }
        )
        return result
    }

    override fun addEquipmentInstance(item: EquipmentInstance): DomainResult<EquipmentInstance> {
        if (item.id.isBlank()) return DomainResult.Failure(AppError.Domain.Inventory.NotFound(item.id))
        if (item.name.isBlank()) return DomainResult.Failure(AppError.Domain.Inventory.InvalidName())
        if (item.rarity !in VALID_RARITY_RANGE) return DomainResult.Failure(AppError.Domain.Inventory.InvalidRarity(item.rarity))

        val ts = stateStore.currentTransactionMutableState()
        val currentInstances = ts?.equipmentInstances ?: stateStore.equipmentInstances.value
        if (currentInstances.any { it.id == item.id }) return DomainResult.Failure(AppError.Domain.Inventory.DuplicateId(item.id))

        if (ts != null) {
            ts.equipmentInstances = ts.equipmentInstances + item
        } else {
            scope.launch { stateStore.update {
                equipmentInstances = equipmentInstances + item
            } }
        }
        return DomainResult.Success(item)
    }

    override fun addManualStack(item: ManualStack, merge: Boolean): DomainResult<ManualStack> {
        val validation = validateStackableItem(item.name, item.rarity, item.quantity)
        if (validation is DomainResult.Failure) return validation

        val ts = stateStore.currentTransactionMutableState()
        val currentItems: List<ManualStack> = ts?.manualStacks?.items ?: stateStore.manualStacks.value
        val maxStack = getMaxStackForType("manual_stack")
        if (!canAddItem() && (currentItems.none { manualStackKey(it) == manualStackKey(item) } || !merge)) {
            return DomainResult.Failure(AppError.Domain.Inventory.Full())
        }

        val result = addWithStore(
            item = item,
            currentItems = currentItems,
            stackKeyOf = ::manualStackKey,
            maxStack = maxStack,
            writeItems = { items ->
                val store = EntityStore(items)
                if (ts != null) ts.manualStacks = store
                else scope.launch { stateStore.update { manualStacks = store } }
            }
        )
        return result
    }

    override fun addManualInstance(item: ManualInstance): DomainResult<ManualInstance> {
        if (item.id.isBlank()) return DomainResult.Failure(AppError.Domain.Inventory.NotFound(item.id))
        if (item.name.isBlank()) return DomainResult.Failure(AppError.Domain.Inventory.InvalidName())
        if (item.rarity !in VALID_RARITY_RANGE) return DomainResult.Failure(AppError.Domain.Inventory.InvalidRarity(item.rarity))

        val ts = stateStore.currentTransactionMutableState()
        val currentInstances = ts?.manualInstances ?: stateStore.manualInstances.value
        if (currentInstances.any { it.id == item.id }) return DomainResult.Failure(AppError.Domain.Inventory.DuplicateId(item.id))

        if (ts != null) {
            ts.manualInstances = ts.manualInstances + item
        } else {
            scope.launch { stateStore.update {
                manualInstances = manualInstances + item
            } }
        }
        return DomainResult.Success(item)
    }

    fun returnEquipmentToStack(instance: EquipmentInstance): DomainResult<EquipmentStack> {
        val ts = stateStore.currentTransactionMutableState()
        val currentStacks = ts?.equipmentStacks ?: stateStore.equipmentStacks.value
        val maxStack = getMaxStackForType("equipment_stack")

        val existing = currentStacks.find {
            it.name == instance.name && it.rarity == instance.rarity && it.slot == instance.slot
        }
        if (existing != null) {
            val totalQty = existing.quantity + 1
            val newQty = totalQty.coerceAtMost(maxStack)
            val merged = existing.copy(quantity = newQty)
            if (ts != null) {
                ts.equipmentStacks = ts.equipmentStacks.map {
                    if (it.id == existing.id) merged else it
                }
            } else {
                scope.launch { stateStore.update {
                    equipmentStacks = equipmentStacks.map {
                        if (it.id == existing.id) merged else it
                    }
                } }
            }
            return if (totalQty > maxStack) DomainResult.Partial(merged, totalQty - maxStack)
                else DomainResult.Success(merged)
        }
        if (!canAddItem()) return DomainResult.Failure(AppError.Domain.Inventory.Full())
        val newStack = instance.toStack(quantity = 1)
        if (ts != null) {
            ts.equipmentStacks = ts.equipmentStacks + newStack
        } else {
            scope.launch { stateStore.update {
                equipmentStacks = equipmentStacks + newStack
            } }
        }
        return DomainResult.Success(newStack)
    }

    fun returnManualToStack(instance: ManualInstance): DomainResult<ManualStack> {
        val ts = stateStore.currentTransactionMutableState()
        val currentStacks = ts?.manualStacks ?: stateStore.manualStacks.value
        val maxStack = getMaxStackForType("manual_stack")

        val existing = currentStacks.find {
            it.name == instance.name && it.rarity == instance.rarity && it.type == instance.type
        }
        if (existing != null) {
            val totalQty = existing.quantity + 1
            val newQty = totalQty.coerceAtMost(maxStack)
            val merged = existing.copy(quantity = newQty)
            if (ts != null) {
                ts.manualStacks = ts.manualStacks.map {
                    if (it.id == existing.id) merged else it
                }
            } else {
                scope.launch { stateStore.update {
                    manualStacks = manualStacks.map {
                        if (it.id == existing.id) merged else it
                    }
                } }
            }
            return if (totalQty > maxStack) DomainResult.Partial(merged, totalQty - maxStack)
                else DomainResult.Success(merged)
        }
        if (!canAddItem()) return DomainResult.Failure(AppError.Domain.Inventory.Full())
        val newStack = instance.toStack(quantity = 1)
        if (ts != null) {
            ts.manualStacks = ts.manualStacks + newStack
        } else {
            scope.launch { stateStore.update {
                manualStacks = manualStacks + newStack
            } }
        }
        return DomainResult.Success(newStack)
    }

    fun removeEquipment(id: String, quantity: Int = 1, bypassLock: Boolean = false): Boolean {
        val currentItems = currentEquipmentStacks()
        return removeStackable(id, quantity, bypassLock, currentItems,
            ::equipmentStackKey, getMaxStackForType("equipment_stack"), "equipment"
        ) { items -> val ts = stateStore.currentTransactionMutableState()
            val store = EntityStore(items)
            if (ts != null) ts.equipmentStacks = store
            else scope.launch { stateStore.update { equipmentStacks = store } }
        }
    }

    fun removeEquipmentInstance(id: String): Boolean {
        var removed = false
        val ts = stateStore.currentTransactionMutableState()
        if (ts != null) {
            val oldSize = ts.equipmentInstances.size
            ts.equipmentInstances = ts.equipmentInstances.filter { it.id != id }
            removed = ts.equipmentInstances.size < oldSize
        } else {
            scope.launch { stateStore.update {
                val oldSize = equipmentInstances.size
                equipmentInstances = equipmentInstances.filter { it.id != id }
                removed = equipmentInstances.size < oldSize
            } }
        }
        return removed
    }

    fun updateEquipmentStack(id: String, transform: (EquipmentStack) -> EquipmentStack): Boolean {
        var found = false
        val ts = stateStore.currentTransactionMutableState()
        if (ts != null) {
            ts.equipmentStacks = ts.equipmentStacks.map {
                if (it.id == id) {
                    found = true
                    transform(it)
                } else it
            }
        } else {
            scope.launch { stateStore.update {
                equipmentStacks = equipmentStacks.map {
                    if (it.id == id) {
                        found = true
                        transform(it)
                    } else it
                }
            } }
        }
        return found
    }

    fun updateEquipmentInstance(id: String, transform: (EquipmentInstance) -> EquipmentInstance): Boolean {
        var found = false
        val ts = stateStore.currentTransactionMutableState()
        if (ts != null) {
            ts.equipmentInstances = ts.equipmentInstances.map {
                if (it.id == id) {
                    found = true
                    transform(it)
                } else it
            }
        } else {
            scope.launch { stateStore.update {
                equipmentInstances = equipmentInstances.map {
                    if (it.id == id) {
                        found = true
                        transform(it)
                    } else it
                }
            } }
        }
        return found
    }

    fun getEquipmentStackById(id: String): EquipmentStack? = getById(currentEquipmentStacks(), id)
    fun getEquipmentInstanceById(id: String): EquipmentInstance? = getById(currentEquipmentInstances(), id)

    fun removeManual(id: String, quantity: Int = 1, bypassLock: Boolean = false): Boolean {
        val currentItems = currentManualStacks()
        return removeStackable(id, quantity, bypassLock, currentItems,
            ::manualStackKey, getMaxStackForType("manual_stack"), "manual"
        ) { items -> val ts = stateStore.currentTransactionMutableState()
            val store = EntityStore(items)
            if (ts != null) ts.manualStacks = store
            else scope.launch { stateStore.update { manualStacks = store } }
        }
    }

    fun removeManualInstance(id: String): Boolean {
        var removed = false
        val ts = stateStore.currentTransactionMutableState()
        if (ts != null) {
            val oldSize = ts.manualInstances.size
            ts.manualInstances = ts.manualInstances.filter { it.id != id }
            removed = ts.manualInstances.size < oldSize
        } else {
            scope.launch { stateStore.update {
                val oldSize = manualInstances.size
                manualInstances = manualInstances.filter { it.id != id }
                removed = manualInstances.size < oldSize
            } }
        }
        return removed
    }

    fun updateManualStack(id: String, transform: (ManualStack) -> ManualStack): Boolean {
        var found = false
        val ts = stateStore.currentTransactionMutableState()
        if (ts != null) {
            ts.manualStacks = ts.manualStacks.map {
                if (it.id == id) {
                    found = true
                    transform(it)
                } else it
            }
        } else {
            scope.launch { stateStore.update {
                manualStacks = manualStacks.map {
                    if (it.id == id) {
                        found = true
                        transform(it)
                    } else it
                }
            } }
        }
        return found
    }

    fun updateManualInstance(id: String, transform: (ManualInstance) -> ManualInstance): Boolean {
        var found = false
        val ts = stateStore.currentTransactionMutableState()
        if (ts != null) {
            ts.manualInstances = ts.manualInstances.map {
                if (it.id == id) {
                    found = true
                    transform(it)
                } else it
            }
        } else {
            scope.launch { stateStore.update {
                manualInstances = manualInstances.map {
                    if (it.id == id) {
                        found = true
                        transform(it)
                    } else it
                }
            } }
        }
        return found
    }

    fun getManualStackById(id: String): ManualStack? = getById(currentManualStacks(), id)
    fun getManualInstanceById(id: String): ManualInstance? = getById(currentManualInstances(), id)

    fun addPill(item: Pill, merge: Boolean = true): DomainResult<Pill> {
        val validation = validateStackableItem(item.name, item.rarity, item.quantity)
        if (validation is DomainResult.Failure) return validation

        val ts = stateStore.currentTransactionMutableState()
        val currentItems: List<Pill> = ts?.pills?.items ?: stateStore.pills.value
        val maxStack = getMaxStackForType("pill")
        if (!canAddItem() && (currentItems.none { pillKey(it) == pillKey(item) } || !merge)) {
            return DomainResult.Failure(AppError.Domain.Inventory.Full())
        }

        val result = addWithStore(
            item = item,
            currentItems = currentItems,
            stackKeyOf = ::pillKey,
            maxStack = maxStack,
            writeItems = { items ->
                val store = EntityStore(items)
                if (ts != null) ts.pills = store
                else scope.launch { stateStore.update { pills = store } }
            }
        )
        return result
    }

    fun removePill(id: String, quantity: Int = 1, bypassLock: Boolean = false): Boolean {
        val currentItems = currentPills()
        return removeStackable(id, quantity, bypassLock, currentItems,
            ::pillKey, getMaxStackForType("pill"), "pill"
        ) { items -> val ts = stateStore.currentTransactionMutableState()
            val store = EntityStore(items)
            if (ts != null) ts.pills = store
            else scope.launch { stateStore.update { pills = store } }
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

        var removed = false
        val ts = stateStore.currentTransactionMutableState()
        if (ts != null) {
            ts.pills = ts.pills.mapNotNull { pill ->
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
        } else {
            scope.launch { stateStore.update {
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
            } }
        }
        return removed
    }

    fun updatePill(id: String, transform: (Pill) -> Pill): Boolean {
        var found = false
        val ts = stateStore.currentTransactionMutableState()
        if (ts != null) {
            ts.pills = ts.pills.map {
                if (it.id == id) {
                    found = true
                    transform(it)
                } else it
            }
        } else {
            scope.launch { stateStore.update {
                pills = pills.map {
                    if (it.id == id) {
                        found = true
                        transform(it)
                    } else it
                }
            } }
        }
        return found
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

        val ts = stateStore.currentTransactionMutableState()
        val currentItems: List<Material> = ts?.materials?.items ?: stateStore.materials.value
        val maxStack = getMaxStackForType("material")
        if (!canAddItem() && (currentItems.none { materialKey(it) == materialKey(item) } || !merge)) {
            return DomainResult.Failure(AppError.Domain.Inventory.Full())
        }

        val result = addWithStore(
            item = item,
            currentItems = currentItems,
            stackKeyOf = ::materialKey,
            maxStack = maxStack,
            writeItems = { items ->
                val store = EntityStore(items)
                if (ts != null) ts.materials = store
                else scope.launch { stateStore.update { materials = store } }
            }
        )
        return result
    }

    fun removeMaterial(id: String, quantity: Int = 1, bypassLock: Boolean = false): Boolean {
        val currentItems = currentMaterials()
        return removeStackable(id, quantity, bypassLock, currentItems,
            ::materialKey, getMaxStackForType("material"), "material"
        ) { items -> val ts = stateStore.currentTransactionMutableState()
            val store = EntityStore(items)
            if (ts != null) ts.materials = store
            else scope.launch { stateStore.update { materials = store } }
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

        var removed = false
        val ts = stateStore.currentTransactionMutableState()
        if (ts != null) {
            ts.materials = ts.materials.mapNotNull { material ->
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
        } else {
            scope.launch { stateStore.update {
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
            } }
        }
        return removed
    }

    fun updateMaterial(id: String, transform: (Material) -> Material): Boolean {
        var found = false
        val ts = stateStore.currentTransactionMutableState()
        if (ts != null) {
            ts.materials = ts.materials.map {
                if (it.id == id) {
                    found = true
                    transform(it)
                } else it
            }
        } else {
            scope.launch { stateStore.update {
                materials = materials.map {
                    if (it.id == id) {
                        found = true
                        transform(it)
                    } else it
                }
            } }
        }
        return found
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

        val ts = stateStore.currentTransactionMutableState()
        val currentItems: List<Herb> = ts?.herbs?.items ?: stateStore.herbs.value
        val maxStack = getMaxStackForType("herb")
        if (!canAddItem() && (currentItems.none { herbKey(it) == herbKey(item) } || !merge)) {
            return DomainResult.Failure(AppError.Domain.Inventory.Full())
        }

        val result = addWithStore(
            item = item,
            currentItems = currentItems,
            stackKeyOf = ::herbKey,
            maxStack = maxStack,
            writeItems = { items ->
                val store = EntityStore(items)
                if (ts != null) ts.herbs = store
                else scope.launch { stateStore.update { herbs = store } }
            }
        )
        return result
    }

    fun removeHerb(id: String, quantity: Int = 1, bypassLock: Boolean = false): Boolean {
        val currentItems = currentHerbs()
        return removeStackable(id, quantity, bypassLock, currentItems,
            ::herbKey, getMaxStackForType("herb"), "herb"
        ) { items -> val ts = stateStore.currentTransactionMutableState()
            val store = EntityStore(items)
            if (ts != null) ts.herbs = store
            else scope.launch { stateStore.update { herbs = store } }
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

        var removed = false
        val ts = stateStore.currentTransactionMutableState()
        if (ts != null) {
            ts.herbs = ts.herbs.mapNotNull { herb ->
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
        } else {
            scope.launch { stateStore.update {
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
            } }
        }
        return removed
    }

    fun updateHerb(id: String, transform: (Herb) -> Herb): Boolean {
        var found = false
        val ts = stateStore.currentTransactionMutableState()
        if (ts != null) {
            ts.herbs = ts.herbs.map {
                if (it.id == id) {
                    found = true
                    transform(it)
                } else it
            }
        } else {
            scope.launch { stateStore.update {
                herbs = herbs.map {
                    if (it.id == id) {
                        found = true
                        transform(it)
                    } else it
                }
            } }
        }
        return found
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

        val ts = stateStore.currentTransactionMutableState()
        val currentItems: List<Seed> = ts?.seeds?.items ?: stateStore.seeds.value
        val maxStack = getMaxStackForType("seed")
        if (!canAddItem() && (currentItems.none { seedKey(it) == seedKey(item) } || !merge)) {
            return DomainResult.Failure(AppError.Domain.Inventory.Full())
        }

        val result = addWithStore(
            item = item,
            currentItems = currentItems,
            stackKeyOf = ::seedKey,
            maxStack = maxStack,
            writeItems = { items ->
                val store = EntityStore(items)
                if (ts != null) ts.seeds = store
                else scope.launch { stateStore.update { seeds = store } }
            }
        )
        return result
    }

    suspend fun addSeedSync(item: Seed, merge: Boolean = true): DomainResult<Seed> {
        val validation = validateStackableItem(item.name, item.rarity, item.quantity)
        if (validation is DomainResult.Failure) return validation

        val ts = stateStore.currentTransactionMutableState()
        if (ts != null) {
            val currentSeeds = ts.seeds
            if (merge) {
                val existing = currentSeeds.find {
                    it.name == item.name && it.rarity == item.rarity && it.growTime == item.growTime
                }
                if (existing != null) {
                    val maxStack = getMaxStackForType("seed")
                    val totalQty = existing.quantity + item.quantity
                    val newQty = totalQty.coerceAtMost(maxStack)
                    ts.seeds = ts.seeds.map {
                        if (it.id == existing.id) it.copy(quantity = newQty) else it
                    }
                    val merged = existing.copy(quantity = newQty)
            return if (totalQty > maxStack) DomainResult.Partial(merged, totalQty - maxStack) else DomainResult.Success(merged)
                }
            }
            if (!canAddItem()) return DomainResult.Failure(AppError.Domain.Inventory.Full())
            ts.seeds = ts.seeds + item
            return DomainResult.Success(item)
        }

        var overflowResult: DomainResult<Seed> = DomainResult.Success(item)
        stateStore.update {
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
                    overflowResult = if (totalQty > maxStack) DomainResult.Partial(merged, totalQty - maxStack) else DomainResult.Success(merged)
                    return@update
                }
            }
            if (getTotalSlotCount() >= getMaxSlots()) {
                overflowResult = DomainResult.Failure(AppError.Domain.Inventory.Full())
                return@update
            }
            seeds = seeds + item
        }
        return overflowResult
    }

    fun removeSeed(id: String, quantity: Int = 1, bypassLock: Boolean = false): Boolean {
        val currentItems = currentSeeds()
        return removeStackable(id, quantity, bypassLock, currentItems,
            ::seedKey, getMaxStackForType("seed"), "seed"
        ) { items -> val ts = stateStore.currentTransactionMutableState()
            val store = EntityStore(items)
            if (ts != null) ts.seeds = store
            else scope.launch { stateStore.update { seeds = store } }
        }
    }

    suspend fun removeSeedSync(id: String, quantity: Int = 1, bypassLock: Boolean = false): Boolean {
        if (!validateQuantity(quantity, "remove quantity")) return false

        val ts = stateStore.currentTransactionMutableState()
        if (ts != null) {
            val existing = ts.seeds.find { it.id == id }
            if (!bypassLock && existing?.isLocked == true) {
                logWarning("Cannot remove locked seed: ${existing.name}")
                return false
            }
            var removed = false
            ts.seeds = ts.seeds.mapNotNull { seed ->
                if (seed.id == id && !removed) {
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
            return removed
        }

        val existing = currentSeeds().find { it.id == id }
        if (!bypassLock && existing?.isLocked == true) {
            logWarning("Cannot remove locked seed: ${existing.name}")
            return false
        }
        if (existing == null) return false
        if (existing.quantity < quantity) {
            logWarning("Cannot remove $quantity items, only ${existing.quantity} available")
            return false
        }

        var removed = false
        stateStore.update {
            seeds = seeds.mapNotNull { seed ->
                if (seed.id == id && !removed) {
                    val newQty = seed.quantity - quantity
                    when {
                        newQty < 0 -> {
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
        }
        return removed
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

        var removed = false
        val ts = stateStore.currentTransactionMutableState()
        if (ts != null) {
            ts.seeds = ts.seeds.mapNotNull { seed ->
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
        } else {
            scope.launch { stateStore.update {
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
            } }
        }
        return removed
    }

    fun updateSeed(id: String, transform: (Seed) -> Seed): Boolean {
        var found = false
        val ts = stateStore.currentTransactionMutableState()
        if (ts != null) {
            ts.seeds = ts.seeds.map {
                if (it.id == id) {
                    found = true
                    transform(it)
                } else it
            }
        } else {
            scope.launch { stateStore.update {
                seeds = seeds.map {
                    if (it.id == id) {
                        found = true
                        transform(it)
                    } else it
                }
            } }
        }
        return found
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
        val ts = stateStore.currentTransactionMutableState()
        if (ts != null) {
            ts.equipmentStacks.replaceAll(ts.equipmentStacks.items.sortedWith(compareByDescending<EquipmentStack> { it.rarity }.thenBy { it.name }))
            ts.equipmentInstances.replaceAll(ts.equipmentInstances.items.sortedWith(compareByDescending<EquipmentInstance> { it.rarity }.thenBy { it.name }))
            ts.manualStacks.replaceAll(ts.manualStacks.items.sortedWith(compareByDescending<ManualStack> { it.rarity }.thenBy { it.name }))
            ts.manualInstances.replaceAll(ts.manualInstances.items.sortedWith(compareByDescending<ManualInstance> { it.rarity }.thenBy { it.name }))
            ts.pills.replaceAll(ts.pills.items.sortedWith(compareByDescending<Pill> { it.rarity }.thenBy { it.name }))
            ts.materials.replaceAll(ts.materials.all().sortedWith(compareByDescending<Material> { it.rarity }.thenBy { it.name }))
            ts.herbs.replaceAll(ts.herbs.all().sortedWith(compareByDescending<Herb> { it.rarity }.thenBy { it.name }))
            ts.seeds.replaceAll(ts.seeds.all().sortedWith(compareByDescending<Seed> { it.rarity }.thenBy { it.name }))
        } else {
            scope.launch { stateStore.update {
                equipmentStacks.replaceAll(equipmentStacks.items.sortedWith(compareByDescending<EquipmentStack> { it.rarity }.thenBy { it.name }))
                equipmentInstances.replaceAll(equipmentInstances.items.sortedWith(compareByDescending<EquipmentInstance> { it.rarity }.thenBy { it.name }))
                manualStacks.replaceAll(manualStacks.items.sortedWith(compareByDescending<ManualStack> { it.rarity }.thenBy { it.name }))
                manualInstances.replaceAll(manualInstances.items.sortedWith(compareByDescending<ManualInstance> { it.rarity }.thenBy { it.name }))
                pills.replaceAll(pills.items.sortedWith(compareByDescending<Pill> { it.rarity }.thenBy { it.name }))
                materials.replaceAll(materials.all().sortedWith(compareByDescending<Material> { it.rarity }.thenBy { it.name }))
                herbs.replaceAll(herbs.all().sortedWith(compareByDescending<Herb> { it.rarity }.thenBy { it.name }))
                seeds.replaceAll(seeds.all().sortedWith(compareByDescending<Seed> { it.rarity }.thenBy { it.name }))
            } }
        }
    }

    fun hasEnoughSpiritStones(currentStones: Long, required: Long): Boolean {
        return currentStones >= required
    }

    fun deductSpiritStones(amount: Long): Long {
        var result = 0L
        val ts = stateStore.currentTransactionMutableState()
        if (ts != null) {
            val newAmount = (ts.gameData.spiritStones - amount).coerceAtLeast(0L)
            ts.gameData = ts.gameData.copy(spiritStones = newAmount)
            result = newAmount
        } else {
            scope.launch { stateStore.update {
                val newAmount = (gameData.spiritStones - amount).coerceAtLeast(0L)
                gameData = gameData.copy(spiritStones = newAmount)
                result = newAmount
            } }
        }
        return result
    }

    fun addSpiritStones(amount: Long): Long {
        var result = 0L
        val ts = stateStore.currentTransactionMutableState()
        if (ts != null) {
            val newAmount = ts.gameData.spiritStones + amount
            ts.gameData = ts.gameData.copy(spiritStones = newAmount)
            result = newAmount
        } else {
            scope.launch { stateStore.update {
                val newAmount = gameData.spiritStones + amount
                gameData = gameData.copy(spiritStones = newAmount)
                result = newAmount
            } }
        }
        return result
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
