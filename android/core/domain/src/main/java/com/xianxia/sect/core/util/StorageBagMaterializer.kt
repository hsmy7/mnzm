package com.xianxia.sect.core.util

import com.xianxia.sect.core.model.BagStackedData
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.EquipmentInstance
import com.xianxia.sect.core.model.EquipmentStack
import com.xianxia.sect.core.model.Herb
import com.xianxia.sect.core.model.ManualInstance
import com.xianxia.sect.core.model.ManualStack
import com.xianxia.sect.core.model.Material
import com.xianxia.sect.core.model.Pill
import com.xianxia.sect.core.model.Seed
import com.xianxia.sect.core.model.StorageBagItem

/**
 * 物化输入：弟子 + 8 类仓库数据。
 * 聚合为单参数数据类（9 个列表参数超 detekt LongParameterList 阈值 8）。
 */
data class BagMaterializeInput(
    val disciples: List<Disciple>,
    val equipmentStacks: List<EquipmentStack>,
    val equipmentInstances: List<EquipmentInstance>,
    val manualStacks: List<ManualStack>,
    val manualInstances: List<ManualInstance>,
    val pills: List<Pill>,
    val materials: List<Material>,
    val herbs: List<Herb>,
    val seeds: List<Seed>
)

/**
 * 储物袋物化迁移（D-03 独立存储重构）。
 *
 * 老存档的袋条目是引用式（itemId 指向仓库堆叠/实例，物品物理在仓库）。
 * 物化把每条引用式条目转换为**持有数据**的独立条目（payload 非空），并
 * **从仓库扣减对应数量**——否则同一物品在仓库与袋中同时存在（复制）。
 *
 * 规则（对每条 `!isMaterialized` 条目）：
 * - equipment_stack / manual_stack：从对应仓库堆叠扣 1 份 → 铸造 [BagStackedData]
 *   （minRealm/slot/manualType 供取回重建，不依赖模板）
 * - equipment_instance / manual_instance：从实例表取出完整实例（含 nurture）
 *   → 存入袋条目，实例从表删除
 * - pill / material / herb / seed：从对应堆叠扣条目 quantity 份 → 铸造空 [BagStackedData]
 *   标记已物化（effect/grade 等展示数据已在条目顶层）
 * - **悬空条目**（查不到对应堆叠/实例）：直接删除（替代原 C10 悬空清理职责）
 * - 未知 itemType：保留原样（不猜语义，避免误删）
 *
 * 物化是幂等的：物化后的条目 payload 非空，下次跳过；扣减只发生在首次。
 * 调用时机：读档后（StorageEngine.loadFromSnapshot），与旧 fixStorageBagReferences 同位置。
 */
object StorageBagMaterializer {

    /**
     * 执行物化迁移。返回物化后的弟子列表 + 扣减后的仓库列表（纯函数，不修改输入）。
     *
     * @param input 弟子与全部仓库数据
     * @return 物化结果（弟子 + 扣减后仓库 + 计数）
     */
    fun materializeDiscipleBagItems(input: BagMaterializeInput): MaterializedBagResult {
        // 局部可变映射做扣减（函数内安全，不污染输入）
        val maps = BagMaps.from(input)

        var materializedCount = 0
        var droppedCount = 0
        val migratedDisciples = input.disciples.map { disciple ->
            val migratedItems = disciple.equipment.storageBagItems.mapNotNull { item ->
                if (item.isMaterialized) return@mapNotNull item
                val migrated = materializeItem(item, maps)
                if (migrated == null) {
                    // 悬空条目（引用的堆叠/实例不存在）：直接删除（原 C10 悬空清理职责）
                    droppedCount++
                    return@mapNotNull null
                }
                // 未知类型返回原对象（引用相同），不计数；物化条目标记 payload 后必为新对象
                if (migrated !== item) materializedCount++
                migrated
            }
            disciple.copy(
                equipment = disciple.equipment.copy(storageBagItems = migratedItems)
            )
        }

        return MaterializedBagResult(
            disciples = migratedDisciples,
            equipmentStacks = maps.eqStacks.values.toList(),
            manualStacks = maps.mnStacks.values.toList(),
            pills = maps.pills.values.toList(),
            materials = maps.materials.values.toList(),
            herbs = maps.herbs.values.toList(),
            seeds = maps.seeds.values.toList(),
            equipmentInstances = maps.eqInstances.values.toList(),
            manualInstances = maps.mnInstances.values.toList(),
            materializedCount = materializedCount,
            droppedCount = droppedCount
        )
    }

    /**
     * 物化单条引用式条目；返回 null 表示悬空删除。
     *
     * 实例类（equipment_instance / manual_instance）从映射取出完整实例入袋
     * （remove = 实例不再留在仓库）；堆叠类按规则扣减。
     */
    private fun materializeItem(item: StorageBagItem, maps: BagMaps): StorageBagItem? =
        when (item.itemType) {
            "equipment_stack" -> maps.eqStacks[item.itemId]?.let { stack ->
                deductOne(maps.eqStacks, stack)
                item.copy(stackedData = BagStackedData(minRealm = stack.minRealm, slot = stack.slot.name))
            }
            "manual_stack" -> maps.mnStacks[item.itemId]?.let { stack ->
                deductOne(maps.mnStacks, stack)
                item.copy(stackedData = BagStackedData(minRealm = stack.minRealm, manualType = stack.type.name))
            }
            "equipment_instance" -> maps.eqInstances.remove(item.itemId)?.let { item.copy(equipmentInstance = it) }
            "manual_instance" -> maps.mnInstances.remove(item.itemId)?.let { item.copy(manualInstance = it) }
            "pill" -> materializeStacked(item, maps.pills)
            "material" -> materializeStacked(item, maps.materials)
            "herb" -> materializeStacked(item, maps.herbs)
            "seed" -> materializeStacked(item, maps.seeds)
            // 未知类型：不猜语义，保留原样
            else -> item
        }

    /** 丹药/材料/草药/种子合并条目：按 quantity 扣减对应堆叠，铸造空 [BagStackedData] */
    private fun <T : StackableItem> materializeStacked(
        item: StorageBagItem,
        map: MutableMap<String, T>
    ): StorageBagItem? = map[item.itemId]?.let { stack ->
        deductQuantity(map, stack, item.quantity)
        item.copy(stackedData = BagStackedData())
    }

    /** 扣减 1 份（装备/功法堆叠每次赏赐恒 1 份，新堆叠不合并） */
    @Suppress("UNCHECKED_CAST")
    private fun <T> deductOne(map: MutableMap<String, T>, stack: T) where T : StackableItem {
        val newQty = stack.quantity - 1
        if (newQty <= 0) map.remove(stack.id)
        else map[stack.id] = stack.withQuantity(newQty) as T
    }

    /** 扣减 quantity 份（丹药/材料/草药/种子合并条目） */
    @Suppress("UNCHECKED_CAST")
    private fun <T> deductQuantity(map: MutableMap<String, T>, stack: T, quantity: Int) where T : StackableItem {
        val newQty = stack.quantity - quantity
        if (newQty <= 0) map.remove(stack.id)
        else map[stack.id] = stack.withQuantity(newQty) as T
    }

    /** 物化中间态：8 类仓库的可变映射（扣减在此发生，函数内安全，不污染输入） */
    private data class BagMaps(
        val eqStacks: MutableMap<String, EquipmentStack>,
        val mnStacks: MutableMap<String, ManualStack>,
        val eqInstances: MutableMap<String, EquipmentInstance>,
        val mnInstances: MutableMap<String, ManualInstance>,
        val pills: MutableMap<String, Pill>,
        val materials: MutableMap<String, Material>,
        val herbs: MutableMap<String, Herb>,
        val seeds: MutableMap<String, Seed>
    ) {
        companion object {
            /** 从不可变输入构建扣减用可变映射 */
            fun from(input: BagMaterializeInput): BagMaps = BagMaps(
                eqStacks = input.equipmentStacks.associateBy { it.id }.toMutableMap(),
                mnStacks = input.manualStacks.associateBy { it.id }.toMutableMap(),
                eqInstances = input.equipmentInstances.associateBy { it.id }.toMutableMap(),
                mnInstances = input.manualInstances.associateBy { it.id }.toMutableMap(),
                pills = input.pills.associateBy { it.id }.toMutableMap(),
                materials = input.materials.associateBy { it.id }.toMutableMap(),
                herbs = input.herbs.associateBy { it.id }.toMutableMap(),
                seeds = input.seeds.associateBy { it.id }.toMutableMap()
            )
        }
    }
}

/** 物化结果：迁移后的弟子与扣减后的仓库列表 */
data class MaterializedBagResult(
    val disciples: List<Disciple>,
    val equipmentStacks: List<EquipmentStack>,
    val manualStacks: List<ManualStack>,
    val pills: List<Pill>,
    val materials: List<Material>,
    val herbs: List<Herb>,
    val seeds: List<Seed>,
    val equipmentInstances: List<EquipmentInstance>,
    val manualInstances: List<ManualInstance>,
    val materializedCount: Int,
    /** 悬空条目删除数（引用不存在的堆叠/实例，防复制） */
    val droppedCount: Int = 0
)
