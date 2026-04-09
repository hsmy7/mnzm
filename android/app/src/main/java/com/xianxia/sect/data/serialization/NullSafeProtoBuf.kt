package com.xianxia.sect.data.serialization

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoBuf

/**
 * ## NullSafeProtoBuf - 统一的 ProtoBuf 序列化空安全工具类
 *
 * ### 设计目标
 * 1. **统一三套序列化系统的配置和策略**
 *    - UnifiedSerializationEngine (存档)
 *    - ProtobufCacheSerializer (缓存)
 *    - ProtobufConverters (Room数据库)
 *
 * 2. **提供类型安全的 null -> 默认值 映射**
 *    - ProtoBuf 不支持 null 值（proto3 规范）
 *    - 所有 nullable 类型必须转换为非空默认值
 *    - 提供双向转换方法确保数据完整性
 *
 * 3. **消除重复代码**
 *    - 集中管理默认值映射规则
 *    - 减少手动的 `?: default` 和 `ifEmpty { null }` 代码
 *    - 确保所有序列化路径的一致性
 *
 * ### 使用示例
 * ```kotlin
 * // 序列化方向：nullable -> non-null (用于写入 ProtoBuf)
 * val serializableId = NullSafeProtoBuf.stringToProto(disciple.id)
 * val serializableGriefEndYear = NullSafeProtoBuf.intToProto(disciple.griefEndYear, sentinel = -1)
 *
 * // 反序列化方向：non-null -> nullable (用于从 ProtoBuf 读取)
 * val discipleId = NullSafeProtoBuf.stringFromProto(serializableId)
 * val griefEndYear = NullSafeProtoBuf.intFromProto(serializableGriefEndYear, sentinel = -1)
 * ```
 */
@OptIn(ExperimentalSerializationApi::class)
object NullSafeProtoBuf {

    /**
     * 统一的 ProtoBuf 实例配置
     *
     * 关键配置说明：
     * - encodeDefaults = true: 确保所有字段都被序列化，即使值为默认值
     *   这对于版本兼容性至关重要，因为新增字段需要有明确的默认值
     * - ignoreUnknownKeys = false: 严格模式，未知字段会抛出异常
     *   这有助于早期发现 schema 不匹配问题
     */
    val protoBuf: ProtoBuf = ProtoBuf {
        encodeDefaults = true
    }

    // ==================== String 类型转换 ====================

    /**
     * 将可空字符串转换为 ProtoBuf 安全的非空字符串
     *
     * 规则：null -> defaultValue (默认为空字符串 "")
     *
     * @param value 可能为 null 的字符串
     * @param defaultValue 当 value 为 null 时使用的默认值（默认为空字符串）
     * @return 非空字符串
     */
    fun stringToProto(value: String?, defaultValue: String = ""): String = value ?: defaultValue

    /**
     * 将 ProtoBuf 非空字符串还原为可空字符串
     *
     * 规则："" -> null, 其他值保持不变
     *
     * @param value 来自 ProtoBuf 的非空字符串
     * @return 原始的可空字符串值
     */
    fun stringFromProto(value: String): String? = value.ifEmpty { null }

    // ==================== Int 类型转换（使用哨兵值）====================

    /**
     * 将可空 Int 转换为 ProtoBuf 安全的非空 Int
     *
     * 使用哨兵值模式：null -> sentinelValue
     *
     * @param value 可能为 null 的 Int
     * @param sentinelValue 表示 null 的哨兵值（默认 -1）
     * @return 非 null 的 Int 值
     */
    fun intToProto(value: Int?, sentinel: Int = DEFAULT_INT_SENTINEL): Int = value ?: sentinel

    /**
     * 将 ProtoBuf 非 null Int 还原为可空 Int
     *
     * 规则：sentinelValue -> null, 其他值保持不变
     *
     * @param value 来自 ProtoBuf 的非 null Int
     * @param sentinelValue 表示 null 的哨兵值（必须与序列化时一致）
     * @return 原始的可空 Int 值
     */
    fun intFromProto(value: Int, sentinel: Int = DEFAULT_INT_SENTINEL): Int? =
        value.takeIf { it != sentinel }

    // ==================== Long 类型转换 ====================

    /**
     * 将可空 Long 转换为 ProtoBuf 安全的非空 Long
     *
     * @param value 可能为 null 的 Long
     * @param sentinelValue 表示 null 的哨兵值（默认 -1L）
     * @return 非 null 的 Long 值
     */
    fun longToProto(value: Long?, sentinel: Long = DEFAULT_LONG_SENTINEL): Long = value ?: sentinel

    /**
     * 将 ProtoBuf 非 null Long 还原为可空 Long
     *
     * @param value 来自 ProtoBuf 的非 null Long
     * @param sentinelValue 表示 null 的哨兵值
     * @return 原始的可空 Long 值
     */
    fun longFromProto(value: Long, sentinel: Long = DEFAULT_LONG_SENTINEL): Long? =
        value.takeIf { it != sentinel }

    // ==================== Double 类型转换 ====================

    /**
     * 将可空 Double 转换为 ProtoBuf 安全的非空 Double
     *
     * @param value 可能为 null 的 Double
     * @param sentinelValue 表示 null 的哨兵值（默认 -1.0）
     * @return 非 null 的 Double 值
     */
    fun doubleToProto(value: Double?, sentinel: Double = DEFAULT_DOUBLE_SENTINEL): Double = value ?: sentinel

    /**
     * 将 ProtoBuf 非 null Double 还原为可空 Double
     *
     * @param value 来自 ProtoBuf 的非 null Double
     * @param sentinelValue 表示 null 的哨兵值
     * @return 原始的可空 Double 值
     */
    fun doubleFromProto(value: Double, sentinel: Double = DEFAULT_DOUBLE_SENTINEL): Double? =
        value.takeIf { it != sentinel }

    // ==================== TriStateBoolean 三态布尔（解决 Boolean? 的 null 安全问题）====================

    /**
     * ## TriStateBoolean - 三态布尔值
     *
     * ### 问题背景
     * ProtoBuf (proto3) 不支持 null 值，Boolean 只有 true/false 两个状态。
     * 当业务语义中 false 和 null 有不同含义时（如 isAlive: false=死亡, null=未初始化），
     * 简单的 Boolean 转换会丢失 null 信息。
     *
     * ### 解决方案
     * 使用 Int 编码三个状态：
     * - 0 = UNSET (null / 未设置)
     * - 1 = TRUE  (显式 true)
     * - 2 = FALSE (显式 false)
     *
     * ### 建议使用 triState 替代的关键字段列表
     * 以下字段在业务中 false 和 null 具有不同语义，应优先迁移至 TriStateBoolean：
     *
     * | 字段 | 所在模块 | false 含义 | null 含义 |
     * |------|----------|------------|-----------|
     * | isAlive | Disciple / BattleTeamSlot | 死亡 | 未初始化 |
     * | playerHasAttackedAI | CoreModuleProto | 已攻击且结果为否 | 从未攻击 |
     * | isPlayerSect | SectInfo | 确认为 AI 门派 | 未知 |
     * | discovered | WorldMap / Cave | 已探索但无内容 | 未探索 |
     * | isKnown | SectScout / Intelligence | 已侦察确认为否 | 未侦察 |
     * | isOccupied | SectSlot / BattleTeam | 已占领但释放 | 未涉及占领 |
     * | isOwned | Equipment / Manual | 确认不拥有 | 未检查 |
     * | isEquipped | Equipment | 明确未装备 | 装备状态未知 |
     *
     * ### 使用示例
     * ```kotlin
     * // 序列化：Boolean? -> Int (存入 ProtoBuf)
     * val protoValue = NullSafeProtoBuf.triStateToProto(disciple.isAlive)
     *
     * // 反序列化：Int -> Boolean? (从 ProtoBuf 读取)
     * val isAlive = NullSafeProtoBuf.triStateFromProto(protoValue, defaultValue = false)
     *
     * // 或使用 TriStateBoolean 数据类直接操作
     * val state = TriStateBoolean.fromNullable(disciple.isAlive)
     * val protoInt = state.value  // 0/1/2
     * ```
     */
    @Serializable
    data class TriStateBoolean(val value: Int) {
        companion object {
            /** 未设置 / null 状态 */
            val UNSET = TriStateBoolean(0)

            /** 显式 true 状态 */
            val TRUE = TriStateBoolean(1)

            /** 显式 false 状态 */
            val FALSE = TriStateBoolean(2)

            /**
             * 将可空 Boolean 转换为 TriStateBoolean
             *
             * @param b 可能为 null 的布尔值
             * @return 对应的三态编码
             */
            fun fromNullable(b: Boolean?): TriStateBoolean = when (b) {
                true -> TRUE
                false -> FALSE
                null -> UNSET
            }

            /**
             * 根据 Int 值创建 TriStateBoolean（兼容 proto 反序列化场景）
             *
             * @param value proto 中的 int 值（0/1/2，其他值视为 UNSET）
             * @return 对应的 TriStateBoolean 实例
             */
            fun fromInt(value: Int): TriStateBoolean = when (value) {
                1 -> TRUE
                2 -> FALSE
                else -> UNSET
            }
        }

        /**
         * 转回可空 Boolean
         *
         * @return true/false/null
         */
        fun toNullable(): Boolean? = when (value) {
            1 -> true
            2 -> false
            else -> null
        }

        /**
         * 转回非空 Boolean，使用默认值处理 UNSET 情况
         *
         * @param default 当状态为 UNSET 时返回的默认值
         * @return 非 null 的 Boolean
         */
        fun toBooleanOrDefault(default: Boolean): Boolean = when (value) {
            1 -> true
            2 -> false
            else -> default
        }

        /** 是否为 UNSET 状态 */
        val isUnset: Boolean get() = value == 0

        /** 是否为显式 TRUE */
        val isExplicitTrue: Boolean get() = value == 1

        /** 是否为显式 FALSE */
        val isExplicitFalse: Boolean get() = value == 2
    }

    /**
     * 将可空 Boolean 转换为 TriState 编码的 Int（用于写入 ProtoBuf）
     *
     * 映射规则：true -> 1, false -> 2, null -> 0
     *
     * @param value 可能为 null 的 Boolean
     * @return 三态编码值 (0/1/2)
     */
    fun triStateToProto(value: Boolean?): Int = TriStateBoolean.fromNullable(value).value

    /**
     * 将 TriState 编码的 Int 还原为可空 Boolean（用于从 ProtoBuf 读取）
     *
     * 映射规则：1 -> true, 2 -> false, 0/其他 -> defaultValue
     *
     * @param value 来自 ProtoBuf 的三态编码 Int 值
     * @param defaultValue 当值为 0（UNSET）时使用的默认值
     * @return 还原后的可空 Boolean 值
     */
    fun triStateFromProto(value: Int, defaultValue: Boolean = false): Boolean? =
        TriStateBoolean.fromInt(value).let { if (it.isUnset) defaultValue else it.toNullable() }

    // ==================== List 类型转换 ====================

    /**
     * 将可空 List 转换为 ProtoBuf 安全的非空 List
     *
     * 规则：null -> emptyList()
     *
     * @param value 可能为 null 的 List
     * @return 非 null 的 List（空列表）
     */
    fun <T : Any> listToProto(value: List<T>?): List<T> = value ?: emptyList()

    /**
     * 将 ProtoBuf 非 null List 还原为可空 List
     *
     * 规则：emptyList() 保持为 emptyList()（不转回 null）
     * 原因：空列表和 null 在大多数场景下语义相同
     *
     * @param value 来自 ProtoBuf 的非 null List
     * @return 始终返回非空 List
     */
    fun <T : Any> listFromProto(value: List<T>): List<T> = value

    // ==================== Map 类型转换 ====================

    /**
     * 将可空 Map 转换为 ProtoBuf 安全的非空 Map
     *
     * 规则：null -> emptyMap()
     */
    fun <K : Any, V : Any> mapToProto(value: Map<K, V>?): Map<K, V> = value ?: emptyMap()

    /**
     * 将 ProtoBuf 非 null Map 还原为可空 Map
     *
     * 规则：emptyMap() 保持为 emptyMap()
     */
    fun <K : Any, V : Any> mapFromProto(value: Map<K, V>): Map<K, V> = value

    // ==================== 对象类型转换（通用）====================

    /**
     * 将可空对象转换为 ProtoBuf 安全的非空对象
     *
     * 使用提供默认值的模式：null -> defaultValue
     *
     * @param value 可能为 null 的对象
     * @param defaultValue 当 value 为 null 时使用的默认实例
     * @return 非 null 的对象
     */
    fun <T : Any> objectToProto(value: T?, defaultValue: () -> T): T = value ?: defaultValue()

    /**
     * 将 ProtoBuf 非空对象还原为可空对象
     *
     * 使用谓词判断是否为"空"对象：
     * - 如果 isEmptyPredicate 返回 true，则视为 null
     * - 否则保留原值
     *
     * @param value 来自 ProtoBuf 的非空对象
     * @param isEmptyPredicate 判断对象是否为"空"（即原始值为 null）
     * @return 原始的可空对象值
     */
    fun <T : Any> objectFromProto(value: T, isEmptyPredicate: (T) -> Boolean): T? =
        value.takeIf { !isEmptyPredicate(it) }

    // ==================== 特定业务类型的便捷方法 ====================

    /**
     * 转换 griefEndYear 字段（Int?，哨兵值 -1）
     *
     * 业务含义：-1 表示无悲伤期结束年份（即未设置）
     */
    fun griefEndYearToProto(value: Int?): Int = intToProto(value, GRIEF_END_YEAR_SENTINEL)

    fun griefEndYearFromProto(value: Int): Int? = intFromProto(value, GRIEF_END_YEAR_SENTINEL)

    /**
     * 转换装备 ID 字段（String?，空字符串表示 null）
     *
     * 适用字段：weaponId, armorId, bootsId, accessoryId
     */
    fun equipmentIdToProto(value: String?): String = stringToProto(value)

    fun equipmentIdFromProto(value: String): String? = stringFromProto(value)

    /**
     * 转换关系 ID 字段（String?，空字符串表示 null）
     *
     * 适用字段：partnerId, partnerSectId, parentId1, parentId2
     */
    fun relationIdToProto(value: String?): String = stringToProto(value)

    fun relationIdFromProto(value: String): String? = stringFromProto(value)

    /**
     * 转换装备培养数据（EquipmentNurtureData?）
     *
     * 判断条件：equipmentId 为空表示未设置
     */
    fun nurtureDataToProto(
        value: com.xianxia.sect.core.model.EquipmentNurtureData?
    ): com.xianxia.sect.data.serialization.unified.SerializableEquipmentNurtureData {
        return if (value == null) {
            com.xianxia.sect.data.serialization.unified.SerializableEquipmentNurtureData(
                equipmentId = "",
                rarity = 0
            )
        } else {
            com.xianxia.sect.data.serialization.unified.SerializableEquipmentNurtureData(
                equipmentId = value.equipmentId ?: "",
                rarity = value.rarity ?: 0,
                nurtureLevel = value.nurtureLevel ?: 0,
                nurtureProgress = value.nurtureProgress ?: 0.0
            )
        }
    }

    fun nurtureDataFromProto(
        value: com.xianxia.sect.data.serialization.unified.SerializableEquipmentNurtureData
    ): com.xianxia.sect.core.model.EquipmentNurtureData? {
        return if (value.equipmentId.isEmpty()) {
            null
        } else {
            com.xianxia.sect.core.model.EquipmentNurtureData(
                equipmentId = value.equipmentId,
                rarity = value.rarity,
                nurtureLevel = value.nurtureLevel,
                nurtureProgress = value.nurtureProgress
            )
        }
    }

    /**
     * 转换战斗队伍（BattleTeam?）
     *
     * 判断条件：id 为空表示未设置
     */
    fun battleTeamToProto(
        value: com.xianxia.sect.core.model.BattleTeam?
    ): com.xianxia.sect.data.serialization.unified.SerializableBattleTeam {
        return if (value == null) {
            createDefaultBattleTeam()
        } else {
            convertBattleTeamToProto(value)
        }
    }

    fun battleTeamFromProto(
        value: com.xianxia.sect.data.serialization.unified.SerializableBattleTeam
    ): com.xianxia.sect.core.model.BattleTeam? {
        return if (value.id.isEmpty()) {
            null
        } else {
            convertBattleTeamFromProto(value)
        }
    }

    // ==================== 私有辅助方法 ====================

    /**
     * 安全的 BattleSlotType 枚举解析
     *
     * 使用 enumValues 线性查找替代 valueOf()，避免 IllegalArgumentException 被静默吞掉。
     * 当名称不匹配任何枚举值时，记录警告日志并返回默认值 DISCIPLE。
     *
     * @param name 枚举名称字符串
     * @return 对应的 BattleSlotType 枚举值，不匹配时返回 DISCIPLE
     */
    private fun safeValueOfBattleSlotType(name: String): com.xianxia.sect.core.model.BattleSlotType {
        return com.xianxia.sect.core.model.BattleSlotType.values()
            .firstOrNull { it.name == name }
            ?: com.xianxia.sect.core.model.BattleSlotType.DISCIPLE.also {
                android.util.Log.w(
                    "NullSafeProtoBuf",
                    "Unknown BattleSlotType '$name', defaulting to DISCIPLE"
                )
            }
    }

    private fun createDefaultBattleTeam(): com.xianxia.sect.data.serialization.unified.SerializableBattleTeam {
        return com.xianxia.sect.data.serialization.unified.SerializableBattleTeam(
            id = "",
            name = "未命名队伍",
            slots = emptyList(),
            isAtSect = true,
            currentX = 0f,
            currentY = 0f,
            targetX = 0f,
            targetY = 0f,
            status = "IDLE",
            targetSectId = "",
            originSectId = "",
            route = emptyList(),
            currentRouteIndex = 0,
            moveProgress = 0f,
            isOccupying = false,
            isReturning = false
        )
    }

    private fun convertBattleTeamToProto(
        team: com.xianxia.sect.core.model.BattleTeam
    ): com.xianxia.sect.data.serialization.unified.SerializableBattleTeam {
        return com.xianxia.sect.data.serialization.unified.SerializableBattleTeam(
            id = team.id ?: "",
            name = team.name ?: "",
            slots = team.slots?.map { slot ->
                com.xianxia.sect.data.serialization.unified.SerializableBattleTeamSlot(
                    index = slot.index,
                    discipleId = slot.discipleId ?: "",
                    discipleName = slot.discipleName,
                    discipleRealm = slot.discipleRealm,
                    slotType = slot.slotType.name,
                    isAlive = slot.isAlive
                )
            } ?: emptyList(),
            isAtSect = team.isAtSect ?: true,
            currentX = team.currentX ?: 0f,
            currentY = team.currentY ?: 0f,
            targetX = team.targetX ?: 0f,
            targetY = team.targetY ?: 0f,
            status = team.status ?: "",
            targetSectId = team.targetSectId ?: "",
            originSectId = team.originSectId ?: "",
            route = team.route ?: emptyList(),
            currentRouteIndex = team.currentRouteIndex ?: 0,
            moveProgress = team.moveProgress ?: 0f,
            isOccupying = team.isOccupying ?: false,
            occupiedSectId = team.occupiedSectId ?: "",
            isReturning = team.isReturning ?: false
        )
    }

    private fun convertBattleTeamFromProto(
        data: com.xianxia.sect.data.serialization.unified.SerializableBattleTeam
    ): com.xianxia.sect.core.model.BattleTeam {
        return com.xianxia.sect.core.model.BattleTeam(
            id = data.id,
            name = data.name,
            slots = data.slots.map { slot ->
                com.xianxia.sect.core.model.BattleTeamSlot(
                    index = slot.index,
                    discipleId = slot.discipleId,
                    discipleName = slot.discipleName,
                    discipleRealm = slot.discipleRealm,
                    slotType = safeValueOfBattleSlotType(slot.slotType),
                    isAlive = slot.isAlive
                )
            },
            isAtSect = data.isAtSect,
            currentX = data.currentX,
            currentY = data.currentY,
            targetX = data.targetX,
            targetY = data.targetY,
            status = data.status,
            targetSectId = data.targetSectId,
            originSectId = data.originSectId,
            route = data.route,
            currentRouteIndex = data.currentRouteIndex,
            moveProgress = data.moveProgress,
            isOccupying = data.isOccupying,
            occupiedSectId = data.occupiedSectId,
            isReturning = data.isReturning
        )
    }

    // ==================== 常量定义 ====================

    /** Int 类型的默认哨兵值（表示 null） */
    const val DEFAULT_INT_SENTINEL: Int = -1

    /** Long 类型的默认哨兵值（表示 null） */
    const val DEFAULT_LONG_SENTINEL: Long = -1L

    /** Double 类型的默认哨兵值（表示 null） */
    const val DEFAULT_DOUBLE_SENTINEL: Double = -1.0

    /** griefEndYear 字段的专用哨兵值 */
    const val GRIEF_END_YEAR_SENTINEL: Int = -1
}
