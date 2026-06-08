package com.xianxia.sect.data.local

import androidx.room.TypeConverter
import com.xianxia.sect.core.model.*

/**
 * JsonConverters - 简单类型和枚举的 Room TypeConverter
 *
 * 包含基本集合类型（List<String>, Map<K,V>, Set<Int>）和枚举类型的转换器。
 * 枚举使用 name.toString() / valueOf() 存储。
 */
object JsonConverters {

    // ==================== 枚举类型转换器 ====================

    @TypeConverter
    @JvmStatic
    fun fromEquipmentSlot(value: EquipmentSlot): String = value.name

    @TypeConverter
    @JvmStatic
    fun toEquipmentSlot(value: String): EquipmentSlot =
        EquipmentSlot.entries.find { it.name == value } ?: EquipmentSlot.WEAPON

    @TypeConverter
    @JvmStatic
    fun fromManualType(value: ManualType): String = value.name

    @TypeConverter
    @JvmStatic
    fun toManualType(value: String): ManualType = when (value) {
        "MOVEMENT" -> ManualType.SUPPORT
        "PRODUCTION" -> ManualType.SUPPORT
        else -> ManualType.entries.find { it.name == value } ?: ManualType.MIND
    }

    @TypeConverter
    @JvmStatic
    fun fromPillCategory(value: PillCategory): String = value.name

    @TypeConverter
    @JvmStatic
    fun toPillCategory(value: String): PillCategory = when (value) {
        "BATTLE" -> PillCategory.BATTLE
        "BATTLE_PHYSICAL", "BATTLE_MAGIC", "BATTLE_STATUS" -> PillCategory.BATTLE
        "BREAKTHROUGH" -> PillCategory.CULTIVATION
        "HEALING" -> PillCategory.FUNCTIONAL
        else -> PillCategory.entries.find { it.name == value } ?: PillCategory.CULTIVATION
    }

    @TypeConverter
    @JvmStatic
    fun fromMaterialCategory(value: MaterialCategory): String = value.name

    @TypeConverter
    @JvmStatic
    fun toMaterialCategory(value: String): MaterialCategory =
        MaterialCategory.entries.find { it.name == value } ?: MaterialCategory.BEAST_HIDE

    @TypeConverter
    @JvmStatic
    fun fromDiscipleStatus(value: DiscipleStatus): String = value.name

    @TypeConverter
    @JvmStatic
    fun toDiscipleStatus(value: String): DiscipleStatus =
        DiscipleStatus.entries.find { it.name == value } ?: DiscipleStatus.IDLE

    @TypeConverter
    @JvmStatic
    fun fromTeamStatus(value: TeamStatus): String = value.name

    @TypeConverter
    @JvmStatic
    fun toTeamStatus(value: String): TeamStatus =
        TeamStatus.entries.find { it.name == value } ?: TeamStatus.IDLE

    @TypeConverter
    @JvmStatic
    fun fromSlotStatus(value: SlotStatus): String = value.name

    @TypeConverter
    @JvmStatic
    fun toSlotStatus(value: String): SlotStatus =
        SlotStatus.entries.find { it.name == value } ?: SlotStatus.IDLE

    @TypeConverter
    @JvmStatic
    fun fromRecipeType(value: RecipeType): String = value.name

    @TypeConverter
    @JvmStatic
    fun toRecipeType(value: String): RecipeType =
        RecipeType.entries.find { it.name == value } ?: RecipeType.PILL

    @TypeConverter
    @JvmStatic
    fun fromBattleType(value: BattleType): String = value.name

    @TypeConverter
    @JvmStatic
    fun toBattleType(value: String): BattleType =
        BattleType.entries.find { it.name == value } ?: BattleType.PVE

    @TypeConverter
    @JvmStatic
    fun fromBattleResult(value: BattleResult): String = value.name

    @TypeConverter
    @JvmStatic
    fun toBattleResult(value: String): BattleResult =
        BattleResult.entries.find { it.name == value } ?: BattleResult.DRAW
}
