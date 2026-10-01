package com.xianxia.sect.core.model.production

import androidx.annotation.Keep
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.model.BuildingTypeAsStringSerializer
import com.xianxia.sect.core.model.NullableStringAsEmptySerializer
import com.xianxia.sect.core.model.ProductionSlotStatusAsStringSerializer
import com.xianxia.sect.core.util.TimeProgressUtil
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.protobuf.ProtoNumber

@Keep
@Serializable
@Entity(
    tableName = "production_slots",
    primaryKeys = ["id"],
    indices = [
        Index(value = ["buildingId", "slotIndex"]),
        Index(value = ["buildingType"]),
        Index(value = ["status"])
    ]
)
@TypeConverters(ProductionSlotConverters::class)
data class ProductionSlot(
    @ProtoNumber(1)
    @ColumnInfo(name = "id")
    val id: String = java.util.UUID.randomUUID().toString(),

    @Transient
    @ProtoNumber(2)
    val slotIndex: Int = 0,
    @ProtoNumber(3)
    @kotlinx.serialization.Serializable(with = BuildingTypeAsStringSerializer::class)
    val buildingType: BuildingType = BuildingType.ALCHEMY,
    @ProtoNumber(4)
    val buildingId: String = "",
    @ProtoNumber(5)
    @kotlinx.serialization.Serializable(with = ProductionSlotStatusAsStringSerializer::class)
    val status: ProductionSlotStatus = ProductionSlotStatus.IDLE,
    @ProtoNumber(6)
    @kotlinx.serialization.Serializable(with = NullableStringAsEmptySerializer::class)
    val recipeId: String? = null,
    @ProtoNumber(7)
    val recipeName: String = "",
    @ProtoNumber(8)
    val startYear: Int = 0,
    @ProtoNumber(9)
    val startMonth: Int = 0,
    @ProtoNumber(10)
    val duration: Int = 0,
    /** 配方基础持续时间（不含加成），用于月结时动态重算 */
    @ProtoNumber(22)
    @ColumnInfo(defaultValue = "0")
    val baseDuration: Int = 0,
    @ProtoNumber(11)
    @kotlinx.serialization.Serializable(with = NullableStringAsEmptySerializer::class)
    val assignedDiscipleId: String? = null,
    @ProtoNumber(12)
    val assignedDiscipleName: String = "",
    @ProtoNumber(13)
    val successRate: Double = 0.0,
    @Transient
    val requiredMaterials: Map<String, Int> = emptyMap(),
    @ProtoNumber(14)
    @kotlinx.serialization.Serializable(with = NullableStringAsEmptySerializer::class)
    val outputItemId: String? = null,
    @ProtoNumber(15)
    val outputItemName: String = "",
    @ProtoNumber(16)
    val outputItemRarity: Int = 1,
    @ProtoNumber(24)
    val outputItemSlot: String = "",
    @ProtoNumber(17)
    @ColumnInfo(defaultValue = "0")
    val expectedYield: Int = 0,
    @ProtoNumber(18)
    @ColumnInfo(defaultValue = "0")
    val autoRestartEnabled: Boolean = false,
    @ProtoNumber(19)
    @ColumnInfo(defaultValue = "0")
    val completionMonth: Int = 0,
    @ProtoNumber(20)
    @ColumnInfo(defaultValue = "1")
    val completionPhase: Int = 1,
    /**
     * 所属建筑实例 ID（炼丹炉/锻造坊）。
     *
     * 用于建筑移除时按实例精确匹配槽位，替代旧的 `maxOfOrNull { it.slotIndex }`
     * 按最大 slotIndex 移除的模式（多建筑同类型时可能移除错误槽位）。
     *
     * 旧存档加载时为空字符串。ProductionSlot 由 Repository 管理，
     * 旧数据回填需在 Repository 初始化时按 buildingId 分组顺序推断。
     */
    @ProtoNumber(21)
    @ColumnInfo(defaultValue = "")
    val buildingInstanceId: String = "",

    /**
     * 开工绝对游戏毫秒（结算改造 2026-09-27 B5 连续时长模型；方案 §2.3/§4.1）。
     * 写入点四处同步：SlotStateMachine.startProduction / BuildingServiceSlotOps /
     * Processor 启动段（均取月初 phase=0）+ 读档归一化回填（startYear/startMonth
     * 月初换算）。>0 时完成判定走毫秒判据（[isFinishedMs]），零值回退年月整数。
     */
    @ProtoNumber(23)
    @ColumnInfo(defaultValue = "0")
    val startedAtGameMs: Long = 0,

    /**
     * 预期完工绝对游戏毫秒（毫秒精度替代 completionMonth/completionPhase
     * 月+旬双编码；与 [startedAtGameMs] 同点维护，= startedAt + 时长 × 月长）。
     */
    @ProtoNumber(25)
    @ColumnInfo(defaultValue = "0")
    val completeAtGameMs: Long = 0
) {
    val isIdle: Boolean get() = status == ProductionSlotStatus.IDLE
    val isWorking: Boolean get() = status == ProductionSlotStatus.WORKING
    val isCompleted: Boolean get() = status == ProductionSlotStatus.COMPLETED
    val slotType: SlotType get() = buildingType.toSlotType()

    /** 旧年月整数判据（B5 起槽位判据以 *Ms 孪生为准；本组退役入债表 D2，勿新增消费点） */
    @Deprecated(
        message = "用 remainingTimeMs(nowGameMs)——毫秒孪生判据（结算改造 B5）",
        replaceWith = ReplaceWith("remainingTimeMs(gameData.elapsedGameMs)")
    )
    fun remainingTime(currentYear: Int, currentMonth: Int): Int {
        if (status != ProductionSlotStatus.WORKING) return 0
        return TimeProgressUtil.calculateRemainingMonths(startYear, startMonth, duration, currentYear, currentMonth)
    }

    @Deprecated(
        message = "用 getProgressFractionMs(nowGameMs)——毫秒孪生判据（结算改造 B5）",
        replaceWith = ReplaceWith("getProgressFractionMs(gameData.elapsedGameMs)")
    )
    fun getProgressPercent(currentYear: Int, currentMonth: Int): Int {
        if (status != ProductionSlotStatus.WORKING || duration <= 0) return 0
        return TimeProgressUtil.calculateProgressPercent(startYear, startMonth, duration, currentYear, currentMonth)
    }

    @Deprecated(
        message = "用 isFinishedMs(nowGameMs)——毫秒孪生判据（结算改造 B5）",
        replaceWith = ReplaceWith("isFinishedMs(gameData.elapsedGameMs)")
    )
    fun isFinished(currentYear: Int, currentMonth: Int): Boolean {
        if (status != ProductionSlotStatus.WORKING) return status == ProductionSlotStatus.COMPLETED
        return TimeProgressUtil.isTimeElapsed(startYear, startMonth, duration, currentYear, currentMonth)
    }

    /**
     * 剩余时长（游戏毫秒；结算改造 2026-09-27 B5 毫秒孪生判据，方案 §3.4.4）。
     * completeAt 孪生有值（槽位启动/checkpoint/卸任归一/读档回填四处同步维护）
     * ⇒ 毫秒判据；零值（测试直构/异常档）⇒ 回退旧年月整数 × 月长折算。
     * [nowGameMs] 取权威轴 GameData.elapsedGameMs 镜像。
     */
    fun remainingTimeMs(nowGameMs: Long): Long {
        if (status != ProductionSlotStatus.WORKING) return 0L
        if (completeAtGameMs > 0L) {
            return TimeProgressUtil.calculateRemainingGameMs(completeAtGameMs, nowGameMs)
        }
        val remainingMonths = TimeProgressUtil.calculateRemainingMonths(
            startYear, startMonth, duration,
            GameConfig.Time.projectCalendar(nowGameMs).year,
            GameConfig.Time.projectCalendar(nowGameMs).month
        )
        return remainingMonths * GameConfig.Time.GAME_MS_PER_MONTH
    }

    /** 进度比例 [0,1]（毫秒孪生口径；语义同 [getProgressPercent] 的连续版） */
    fun getProgressFractionMs(nowGameMs: Long): Float {
        if (status != ProductionSlotStatus.WORKING || duration <= 0) return 0f
        if (completeAtGameMs > 0L) {
            return TimeProgressUtil.calculateProgressFractionByGameMs(
                startedAtGameMs, completeAtGameMs, nowGameMs)
        }
        val calendar = GameConfig.Time.projectCalendar(nowGameMs)
        return TimeProgressUtil.calculateProgressFraction(
            startYear, startMonth, duration, calendar.year, calendar.month)
    }

    /**
     * 完成判定（毫秒孪生口径；与 C++ isSlotCompleteDynamic 毫秒臂同式——
     * 回退臂同刻等价：startedAt 取月初、判定窗口对齐 2000ms 网格）。
     */
    fun isFinishedMs(nowGameMs: Long): Boolean {
        if (status != ProductionSlotStatus.WORKING) return status == ProductionSlotStatus.COMPLETED
        if (completeAtGameMs > 0L) {
            return TimeProgressUtil.isElapsedByGameMs(completeAtGameMs, nowGameMs)
        }
        val calendar = GameConfig.Time.projectCalendar(nowGameMs)
        return TimeProgressUtil.isTimeElapsed(
            startYear, startMonth, duration, calendar.year, calendar.month)
    }

    companion object {
        fun createIdle(
            id: String = java.util.UUID.randomUUID().toString(),
            slotIndex: Int,
            buildingType: BuildingType,
            buildingId: String = "",
            autoRestartEnabled: Boolean = false,
            assignedDiscipleId: String? = null,
            assignedDiscipleName: String = "",
            recipeId: String? = null
        ): ProductionSlot = ProductionSlot(
            id = id,
            slotIndex = slotIndex,
            buildingType = buildingType,
            buildingId = buildingId,
            status = ProductionSlotStatus.IDLE,
            autoRestartEnabled = autoRestartEnabled,
            assignedDiscipleId = assignedDiscipleId,
            assignedDiscipleName = assignedDiscipleName,
            recipeId = recipeId
        )

        fun resolveBuildingType(buildingId: String): BuildingType = when (buildingId.lowercase()) {
            "forge", "forging" -> BuildingType.FORGE
            "alchemy", "alchemyroom" -> BuildingType.ALCHEMY
            "mine", "mining" -> BuildingType.MINING
            "herb", "herbgarden", "herb_garden" -> BuildingType.HERB_GARDEN
            else -> BuildingType.ALCHEMY
        }

        fun fromBuildingSlot(buildingSlot: com.xianxia.sect.core.model.BuildingSlot): ProductionSlot {
            val bType = resolveBuildingType(buildingSlot.buildingId)
            return ProductionSlot(
                id = buildingSlot.id,
                slotIndex = buildingSlot.slotIndex,
                buildingType = bType,
                buildingId = buildingSlot.buildingId,
                status = when (buildingSlot.status) {
                    com.xianxia.sect.core.model.SlotStatus.IDLE -> ProductionSlotStatus.IDLE
                    com.xianxia.sect.core.model.SlotStatus.WORKING -> ProductionSlotStatus.WORKING
                    com.xianxia.sect.core.model.SlotStatus.COMPLETED -> ProductionSlotStatus.COMPLETED
                },
                recipeId = buildingSlot.recipeId,
                recipeName = buildingSlot.recipeName,
                startYear = buildingSlot.startYear,
                startMonth = buildingSlot.startMonth,
                duration = buildingSlot.duration,
                assignedDiscipleId = buildingSlot.discipleId,
                assignedDiscipleName = buildingSlot.discipleName
            )
        }

        fun fromAlchemySlot(alchemySlot: com.xianxia.sect.core.model.AlchemySlot): ProductionSlot = ProductionSlot(
            id = alchemySlot.id,
            slotIndex = alchemySlot.slotIndex,
            buildingType = BuildingType.ALCHEMY,
            buildingId = "alchemy",
            status = when (alchemySlot.status) {
                com.xianxia.sect.core.model.AlchemySlotStatus.IDLE -> ProductionSlotStatus.IDLE
                com.xianxia.sect.core.model.AlchemySlotStatus.WORKING -> ProductionSlotStatus.WORKING
                com.xianxia.sect.core.model.AlchemySlotStatus.FINISHED -> ProductionSlotStatus.COMPLETED
            },
            recipeId = alchemySlot.recipeId,
            recipeName = alchemySlot.recipeName,
            startYear = alchemySlot.startYear,
            startMonth = alchemySlot.startMonth,
            duration = alchemySlot.duration,
            assignedDiscipleId = null,
            assignedDiscipleName = "",
            successRate = alchemySlot.successRate,
            requiredMaterials = alchemySlot.requiredMaterials,
            outputItemId = alchemySlot.recipeId,
            outputItemName = alchemySlot.pillName,
            outputItemRarity = alchemySlot.pillRarity,
            autoRestartEnabled = alchemySlot.autoRestartEnabled
        )

        fun fromForgeSlot(forgeSlot: com.xianxia.sect.core.model.ForgeSlot): ProductionSlot = ProductionSlot(
            id = forgeSlot.id,
            slotIndex = forgeSlot.slotIndex,
            buildingType = BuildingType.FORGE,
            buildingId = "forge",
            status = when (forgeSlot.status) {
                com.xianxia.sect.core.model.ForgeSlotStatus.IDLE -> ProductionSlotStatus.IDLE
                com.xianxia.sect.core.model.ForgeSlotStatus.WORKING -> ProductionSlotStatus.WORKING
                com.xianxia.sect.core.model.ForgeSlotStatus.FINISHED -> ProductionSlotStatus.COMPLETED
            },
            recipeId = forgeSlot.recipeId,
            recipeName = forgeSlot.recipeName,
            startYear = forgeSlot.startYear,
            startMonth = forgeSlot.startMonth,
            duration = forgeSlot.duration,
            assignedDiscipleId = null,
            assignedDiscipleName = "",
            successRate = forgeSlot.successRate,
            requiredMaterials = forgeSlot.requiredMaterials,
            outputItemId = forgeSlot.recipeId,
            outputItemName = forgeSlot.equipmentName,
            outputItemRarity = forgeSlot.equipmentRarity,
            outputItemSlot = forgeSlot.equipmentSlot.name,
            autoRestartEnabled = forgeSlot.autoRestartEnabled
        )

        fun fromPlantSlot(plantSlot: com.xianxia.sect.core.model.PlantSlotData): ProductionSlot = ProductionSlot(
            id = java.util.UUID.randomUUID().toString(),
            slotIndex = plantSlot.index,
            buildingType = BuildingType.HERB_GARDEN,
            buildingId = "herbGarden",
            status = when (plantSlot.status) {
                "idle" -> ProductionSlotStatus.IDLE
                "growing" -> ProductionSlotStatus.WORKING
                "mature" -> ProductionSlotStatus.COMPLETED
                else -> ProductionSlotStatus.IDLE
            },
            recipeId = plantSlot.seedId.ifEmpty { null },
            recipeName = plantSlot.seedName,
            startYear = plantSlot.startYear,
            startMonth = plantSlot.startMonth,
            duration = plantSlot.growTime,
            outputItemId = plantSlot.seedId.ifEmpty { null },
            outputItemName = plantSlot.seedName,
            expectedYield = plantSlot.expectedYield
        )
    }
}

@Keep
@Serializable
enum class BuildingType {
    ALCHEMY,
    FORGE,
    MINING,
    SPIRIT_FIELD,
    HERB_GARDEN,
    ADMINISTRATION,
    LIBRARY,
    WEN_DAO_PEAK,
    QINGYUN_PEAK,
    MISSION_HALL,
    SINGLE_RESIDENCE,
    MULTI_RESIDENCE,
    WAREHOUSE,
    PATROL;

    val displayName: String get() = when (this) {
        ALCHEMY -> "炼丹"
        FORGE -> "锻造"
        MINING -> "灵矿开采"
        SPIRIT_FIELD -> "灵田"
        HERB_GARDEN -> "灵植阁"
        ADMINISTRATION -> "天枢殿"
        LIBRARY -> "藏经阁"
        WEN_DAO_PEAK -> "问道塔"
        QINGYUN_PEAK -> "青云塔"
        MISSION_HALL -> "任务阁"
        SINGLE_RESIDENCE -> "初级单人住所"
        MULTI_RESIDENCE -> "初级多人住所"
        WAREHOUSE -> "仓库"
        PATROL -> "巡视"
    }

    fun toSlotType(): SlotType = when (this) {
        ALCHEMY -> SlotType.ALCHEMY
        FORGE -> SlotType.FORGING
        MINING -> SlotType.MINING
        SPIRIT_FIELD -> SlotType.IDLE
        HERB_GARDEN -> SlotType.HERB_GARDEN
        else -> SlotType.IDLE
    }
}

@Keep
@Serializable
enum class ProductionSlotStatus {
    IDLE,
    WORKING,
    COMPLETED;

    val displayName: String get() = when (this) {
        IDLE -> "空闲"
        WORKING -> "进行中"
        COMPLETED -> "已完成"
    }
}

@Keep
@Serializable
enum class SlotType {
    IDLE,
    MINING,
    ALCHEMY,
    FORGING,
    HERB_GARDEN;

    val displayName: String get() = when (this) {
        IDLE -> "空闲"
        MINING -> "灵矿开采"
        ALCHEMY -> "炼丹"
        FORGING -> "锻造"
        HERB_GARDEN -> "灵植阁"
    }

    fun toBuildingType(): BuildingType? = when (this) {
        IDLE -> null
        MINING -> BuildingType.MINING
        ALCHEMY -> BuildingType.ALCHEMY
        FORGING -> BuildingType.FORGE
        HERB_GARDEN -> BuildingType.HERB_GARDEN
    }
}

class ProductionSlotConverters {
    @TypeConverter
    fun fromBuildingType(value: BuildingType): String = value.name

    @TypeConverter
    fun toBuildingType(value: String): BuildingType = 
        BuildingType.entries.find { it.name == value } ?: BuildingType.ALCHEMY

    @TypeConverter
    fun fromStatus(value: ProductionSlotStatus): String = value.name

    @TypeConverter
    fun toStatus(value: String): ProductionSlotStatus = 
        ProductionSlotStatus.entries.find { it.name == value } ?: ProductionSlotStatus.IDLE

    @TypeConverter
    fun fromSlotType(value: SlotType): String = value.name

    @TypeConverter
    fun toSlotType(value: String): SlotType = 
        SlotType.entries.find { it.name == value } ?: SlotType.IDLE

    @TypeConverter
    fun fromMaterialMap(value: Map<String, Int>): String = 
        value.entries.joinToString(",") { "${it.key}:${it.value}" }

    @TypeConverter
    fun toMaterialMap(value: String): Map<String, Int> {
        if (value.isEmpty()) return emptyMap()
        return value.split(",").associate {
            val parts = it.split(":")
            parts[0] to (parts.getOrNull(1)?.toIntOrNull() ?: 0)
        }
    }
}
