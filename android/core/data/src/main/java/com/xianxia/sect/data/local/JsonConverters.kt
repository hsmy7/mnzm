package com.xianxia.sect.data.local

import android.util.Log
import androidx.room.TypeConverter
import com.xianxia.sect.core.model.BattleResult
import com.xianxia.sect.core.model.BattleType
import com.xianxia.sect.core.model.DiscipleStatus
import com.xianxia.sect.core.model.EquipAffixSet
import com.xianxia.sect.core.model.EquipGrowth
import com.xianxia.sect.core.model.EquipInstanceMeta
import com.xianxia.sect.core.model.EquipStat
import com.xianxia.sect.core.model.EquipStatValue
import com.xianxia.sect.core.model.EquipmentSlot
import com.xianxia.sect.core.model.ManualType
import com.xianxia.sect.core.model.MaterialCategory
import com.xianxia.sect.core.model.PillCategory
import com.xianxia.sect.core.model.RecipeType
import com.xianxia.sect.core.model.SlotStatus
import com.xianxia.sect.core.model.TeamStatus
import kotlinx.serialization.json.Json



/**
 * EnumStringConverters - 枚举类型的 Room TypeConverter（存储为枚举字符串名）
 *
 * 所有枚举通过 name.toString() / entries.find{} 双向转换，不使用 JSON 序列化。
 * 原名 JsonConverters，因名称易误导（实际无 JSON 序列化），v4.0.40+ 重命名为
 * EnumStringConverters 以及映其真实用途。
 */
@Suppress("TooManyFunctions") // Room @TypeConverter 注册面：每模型类型一对转换函数（Room 强制函数形态），1:1 契约映射
object JsonConverters {

    /** 装备聚合值对象（growth/meta/affix/statValue）共用的宽松 JSON 配置 */
    private val equipJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** 装备聚合值对象解码失败时的中性兜底值 */
    private val DEFAULT_STAT_VALUE = EquipStatValue(EquipStat.ATTACK, 0.0)

    /** 装备转换器日志 TAG */
    private const val TAG_EQUIP_CONVERTER = "JsonConverters"

    // ==================== 枚举类型转换器 ====================

    @TypeConverter
    @JvmStatic
    fun fromEquipmentSlot(value: EquipmentSlot): String = value.name

    @TypeConverter
    @JvmStatic
    fun toEquipmentSlot(value: String): EquipmentSlot {
        // 退役枚举名（ARMOR/BOOTS/ACCESSORY 等）不再存在于六部位枚举：回退 HEAD
        // 并记日志（D6 处置；旧行由 MIGRATION_63_64 清空，此分支只兜脏数据）
        return EquipmentSlot.entries.find { it.name == value } ?: run {
            Log.w(TAG_EQUIP_CONVERTER, "未知装备部位 \"$value\"，回退 HEAD")
            EquipmentSlot.HEAD
        }
    }

    // ==================== 装备聚合值对象（B3 新模型，方案 §3.5） ====================

    @TypeConverter
    @JvmStatic
    fun fromEquipGrowth(value: EquipGrowth): String = equipJson.encodeToString(EquipGrowth.serializer(), value)

    @TypeConverter
    @JvmStatic
    fun toEquipGrowth(value: String): EquipGrowth =
        runCatching { equipJson.decodeFromString(EquipGrowth.serializer(), value) }
            .getOrElse { EquipGrowth(affix = EquipAffixSet(mainStat = DEFAULT_STAT_VALUE)) }

    @TypeConverter
    @JvmStatic
    fun fromEquipInstanceMeta(value: EquipInstanceMeta): String =
        equipJson.encodeToString(EquipInstanceMeta.serializer(), value)

    @TypeConverter
    @JvmStatic
    fun toEquipInstanceMeta(value: String): EquipInstanceMeta =
        runCatching { equipJson.decodeFromString(EquipInstanceMeta.serializer(), value) }
            .getOrDefault(EquipInstanceMeta())

    @TypeConverter
    @JvmStatic
    fun fromEquipAffixSet(value: EquipAffixSet): String = equipJson.encodeToString(EquipAffixSet.serializer(), value)

    @TypeConverter
    @JvmStatic
    fun toEquipAffixSet(value: String): EquipAffixSet =
        runCatching { equipJson.decodeFromString(EquipAffixSet.serializer(), value) }
            .getOrElse { EquipAffixSet(mainStat = DEFAULT_STAT_VALUE) }

    @TypeConverter
    @JvmStatic
    fun fromEquipStatValue(value: EquipStatValue): String = equipJson.encodeToString(EquipStatValue.serializer(), value)

    @TypeConverter
    @JvmStatic
    fun toEquipStatValue(value: String): EquipStatValue =
        runCatching { equipJson.decodeFromString(EquipStatValue.serializer(), value) }
            .getOrDefault(DEFAULT_STAT_VALUE)

    @TypeConverter
    @JvmStatic
    fun fromManualType(value: ManualType): String = value.name

    @TypeConverter
    @JvmStatic
    fun toManualType(value: String): ManualType =
        ManualType.entries.find { it.name == value } ?: ManualType.MIND

    @TypeConverter
    @JvmStatic
    fun fromPillCategory(value: PillCategory): String = value.name

    @TypeConverter
    @JvmStatic
    fun toPillCategory(value: String): PillCategory =
        PillCategory.entries.find { it.name == value } ?: PillCategory.CULTIVATION

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
