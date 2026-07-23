package com.xianxia.sect.core.engine.domain.building

import com.xianxia.sect.core.model.*
import com.xianxia.sect.core.model.production.BuildingType
import com.xianxia.sect.core.model.production.ProductionSlot

/**
 * 建筑特征定义：涵盖建筑类型、槽位组、建造限制、外观描述等所有属性。
 *
 * 替代 BuildingType/BuildingDef/BuildingNames/BuildingConfigModel 四套分散系统，
 * 作为建筑信息的单一事实源。
 */
data class BuildingFeature(
    val key: String,
    val displayName: String,
    val buildingType: BuildingType,
    val slotGroups: List<SlotGroup>,
    val isResidence: Boolean = false,
    /** 是否可直接建造（false 表示不可直接建造） */
    val isConstructible: Boolean = true,
    val unlimitedBuild: Boolean = false,
    /** 全局唯一（跨宗门）：若为 true，则全地图仅允许建造 1 座 */
    val isGloballyUnique: Boolean = false,
    /** 最低宗门等级要求（SectLevel 常量，0=小型/1=中型/2=大型/3=顶级），默认 0=无限制 */
    val requiredSectLevel: Int = 0,
    val drawableRes: Int = 0,
    val color: Long = 0xFFEEEEEE,
    val cost: Long = 1000,
    val gridWidth: Int = 2,
    val gridHeight: Int = 2,
    val spriteWidth: Int = 0,
    val spriteHeight: Int = 0,
    val description: String = "",
    val baseSuccessRate: Double = 1.0,
    val maxQueueLength: Int = 1,
    val autoRestartEnabled: Boolean = false,
    val residenceSpeedBonus: String = ""   // 住所修炼速度加成描述
) {
    init {
        require(!(unlimitedBuild && isGloballyUnique)) {
            "Building '$key': isGloballyUnique=true 与 unlimitedBuild=true 语义冲突，全局唯一建筑不能无限建造"
        }
    }
    val slotCount: Int get() = slotGroups.sumOf { it.slotsPerInstance }
    fun effectiveSpriteWidth(): Int = if (spriteWidth > 0) spriteWidth else gridWidth
    fun effectiveSpriteHeight(): Int = if (spriteHeight > 0) spriteHeight else gridHeight
}

/**
 * 槽位组：每种建筑类型包含的槽位类型和数量。
 *
 * 每个槽位组自管理：
 * - [filterFromGameData] — 移除时过滤关联槽位
 * - [collectDiscipleIds] — 收集已分配弟子 ID
 * - [createSlots] — 建造时创建槽位实例
 */
sealed interface SlotGroup {
    val slotsPerInstance: Int

    fun filterFromGameData(data: GameData, instanceId: String, feature: BuildingFeature? = null): GameData
    fun collectDiscipleIds(data: GameData, instanceId: String, feature: BuildingFeature? = null): Set<String>
    fun createSlots(
        instanceId: String, existingData: GameData, activeId: String,
        feature: BuildingFeature, startIndex: Int = 0
    ): SlotCreationResult = SlotCreationResult()

    /** 灵矿场：3 个采矿槽位 */
    data class SpiritMine(override val slotsPerInstance: Int = 3) : SlotGroup {
        override fun filterFromGameData(data: GameData, instanceId: String, feature: BuildingFeature?) =
            data.copy(spiritMineSlots = data.spiritMineSlots.filterNot { it.buildingInstanceId == instanceId })

        override fun collectDiscipleIds(data: GameData, instanceId: String, feature: BuildingFeature?) =
            data.spiritMineSlots.filter { it.buildingInstanceId == instanceId && it.discipleId.isNotEmpty() }
                .map { it.discipleId }.toSet()

        override fun createSlots(instanceId: String, existingData: GameData, activeId: String, feature: BuildingFeature, startIndex: Int) =
            SlotCreationResult(
                spiritMineSlots = (0 until slotsPerInstance).map { offset ->
                    SpiritMineSlot(index = existingData.spiritMineSlots.size + offset, buildingInstanceId = instanceId, sectId = activeId)
                }
            )
    }

    /** 巡视楼：8 个巡逻槽位 + 1 个配置 */
    data class PatrolTower(override val slotsPerInstance: Int = 8) : SlotGroup {
        override fun filterFromGameData(data: GameData, instanceId: String, feature: BuildingFeature?): GameData {
            val towerIdx = data.placedBuildings
                .filter { it.displayName in buildingFeatureDisplayNames { sg -> sg is PatrolTower } }
                .indexOfFirst { it.instanceId == instanceId }
            return data.copy(
                patrolSlots = data.patrolSlots.filterNot { it.buildingInstanceId == instanceId },
                patrolConfigs = if (towerIdx >= 0)
                    data.patrolConfigs.filterIndexed { idx, _ -> idx != towerIdx }
                else data.patrolConfigs
            )
        }

        override fun collectDiscipleIds(data: GameData, instanceId: String, feature: BuildingFeature?) =
            data.patrolSlots.filter { it.buildingInstanceId == instanceId && it.discipleId.isNotEmpty() }
                .map { it.discipleId }.toSet()

        override fun createSlots(instanceId: String, existingData: GameData, activeId: String, feature: BuildingFeature, startIndex: Int) =
            SlotCreationResult(
                patrolSlots = (0 until slotsPerInstance).map { offset ->
                    PatrolSlot(index = existingData.patrolSlots.size + offset, buildingInstanceId = instanceId)
                },
                patrolConfigs = listOf(PatrolConfig())
            )
    }

    /** 生产槽位组：炼丹炉/锻造坊/灵植阁等，用统一的 ProductionSlot 管理 */
    data class ProductionSlotGroup(override val slotsPerInstance: Int = 1) : SlotGroup {
        override fun filterFromGameData(data: GameData, instanceId: String, feature: BuildingFeature?) =
            data.copy(productionSlots = data.productionSlots.filterNot { it.buildingInstanceId == instanceId })

        override fun collectDiscipleIds(data: GameData, instanceId: String, feature: BuildingFeature?): Set<String> {
            val buildingId = feature?.key ?: return emptySet()
            return data.productionSlots
                .filter { it.buildingInstanceId == instanceId && it.buildingId == buildingId && !it.assignedDiscipleId.isNullOrEmpty() }
                .mapNotNull { it.assignedDiscipleId }.filter { it.isNotEmpty() }.toSet()
        }

        override fun createSlots(instanceId: String, existingData: GameData, activeId: String, feature: BuildingFeature, startIndex: Int): SlotCreationResult {
            val existingCount = existingData.placedBuildings.count {
                BuildingFeatureRegistry.findByDisplayName(it.displayName)?.buildingType == feature.buildingType
            }
            val slot = ProductionSlot.createIdle(
                slotIndex = existingCount,
                buildingType = feature.buildingType,
                buildingId = feature.key
            ).copy(buildingInstanceId = instanceId)
            return SlotCreationResult(productionSlots = listOf(slot))
        }
    }

    /** 住所：单人/多人住所的居住槽位 */
    data class Residence(override val slotsPerInstance: Int) : SlotGroup {
        override fun filterFromGameData(data: GameData, instanceId: String, feature: BuildingFeature?) =
            data.copy(residenceSlots = data.residenceSlots.filterNot { it.buildingInstanceId == instanceId })

        override fun collectDiscipleIds(data: GameData, instanceId: String, feature: BuildingFeature?) =
            data.residenceSlots.filter { it.buildingInstanceId == instanceId && it.discipleId.isNotEmpty() }
                .map { it.discipleId }.toSet()

        override fun createSlots(instanceId: String, existingData: GameData, activeId: String, feature: BuildingFeature, startIndex: Int) =
            SlotCreationResult(
                residenceSlots = (0 until slotsPerInstance).map { offset ->
                    ResidenceSlot(buildingInstanceId = instanceId, slotIndex = offset)
                }
            )
    }

    /** 灵田：灵植种植槽位 */
    data class SpiritField(override val slotsPerInstance: Int = 1) : SlotGroup {
        override fun filterFromGameData(data: GameData, instanceId: String, feature: BuildingFeature?) =
            data.copy(spiritFieldPlants = data.spiritFieldPlants.filterNot { it.buildingInstanceId == instanceId })

        override fun collectDiscipleIds(data: GameData, instanceId: String, feature: BuildingFeature?) = emptySet<String>()

        override fun createSlots(instanceId: String, existingData: GameData, activeId: String, feature: BuildingFeature, startIndex: Int) =
            SlotCreationResult(
                spiritFieldPlants = listOf(SpiritFieldPlant(buildingInstanceId = instanceId, sectId = activeId))
            )
    }

    /** 仓库：驻守槽位 */
    data class Warehouse(override val slotsPerInstance: Int = 1) : SlotGroup {
        override fun filterFromGameData(data: GameData, instanceId: String, feature: BuildingFeature?) =
            data.copy(warehouseGarrisons = data.warehouseGarrisons.filterNot { it.buildingInstanceId == instanceId })

        override fun collectDiscipleIds(data: GameData, instanceId: String, feature: BuildingFeature?) =
            data.warehouseGarrisons.filter { it.buildingInstanceId == instanceId && it.discipleId.isNotEmpty() }
                .map { it.discipleId }.toSet()

        override fun createSlots(instanceId: String, existingData: GameData, activeId: String, feature: BuildingFeature, startIndex: Int): SlotCreationResult {
            val existingCount = existingData.warehouseGarrisons.count { it.buildingInstanceId == instanceId }
            val slots = (0 until slotsPerInstance).map { offset ->
                WarehouseGarrisonSlot(
                    buildingInstanceId = instanceId, slotIndex = existingCount + offset,
                    sectId = activeId
                )
            }
            return SlotCreationResult(warehouseGarrisons = slots)
        }
    }

    /** 血炼池：炼化进度槽位 */
    data class BloodRefining(override val slotsPerInstance: Int = 1) : SlotGroup {
        override fun filterFromGameData(data: GameData, instanceId: String, feature: BuildingFeature?) =
            data.copy(activeBloodRefinements = data.activeBloodRefinements - instanceId)

        override fun collectDiscipleIds(data: GameData, instanceId: String, feature: BuildingFeature?): Set<String> {
            val progress = data.activeBloodRefinements[instanceId] ?: return emptySet()
            return if (progress.discipleId.isNotEmpty()) setOf(progress.discipleId) else emptySet()
        }
    }

    /** 藏经阁：研读槽位 */
    data class Library(override val slotsPerInstance: Int = 3) : SlotGroup {
        override fun filterFromGameData(data: GameData, instanceId: String, feature: BuildingFeature?) =
            data.copy(librarySlots = data.librarySlots.filterNot { it.buildingInstanceId == instanceId })

        override fun collectDiscipleIds(data: GameData, instanceId: String, feature: BuildingFeature?) =
            data.librarySlots.filter { it.buildingInstanceId == instanceId && it.discipleId.isNotEmpty() }
                .map { it.discipleId }.toSet()

        override fun createSlots(instanceId: String, existingData: GameData, activeId: String, feature: BuildingFeature, startIndex: Int): SlotCreationResult {
            val existingCount = existingData.librarySlots.count { it.buildingInstanceId == instanceId }
            val slots = (0 until slotsPerInstance).map { offset ->
                LibrarySlot(index = existingCount + offset, buildingInstanceId = instanceId)
            }
            return SlotCreationResult(librarySlots = slots)
        }
    }
}

/**
 * 建造时槽位创建结果。
 * 各 SlotGroup 的 createSlots() 按需填充对应槽位列表，其余为 emptyList()。
 */
data class SlotCreationResult(
    val spiritMineSlots: List<SpiritMineSlot> = emptyList(),
    val patrolSlots: List<PatrolSlot> = emptyList(),
    val patrolConfigs: List<PatrolConfig> = emptyList(),
    val residenceSlots: List<ResidenceSlot> = emptyList(),
    val spiritFieldPlants: List<SpiritFieldPlant> = emptyList(),
    val productionSlots: List<ProductionSlot> = emptyList(),
    val warehouseGarrisons: List<WarehouseGarrisonSlot> = emptyList(),
    val librarySlots: List<LibrarySlot> = emptyList()
)

/** 工具函数：查找所有匹配某 SlotGroup 类型的建筑 displayName */
fun buildingFeatureDisplayNames(predicate: (SlotGroup) -> Boolean): Set<String> =
    BuildingFeatureRegistry.all.filter { f -> f.slotGroups.any(predicate) }.map { it.displayName }.toSet()
