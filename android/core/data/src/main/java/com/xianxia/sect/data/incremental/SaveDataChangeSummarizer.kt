package com.xianxia.sect.data.incremental

import com.xianxia.sect.data.model.SaveData
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.util.concurrent.ConcurrentHashMap

/**
 * 保存变更摘要器：对比上次成功保存的 [SaveData] 与本次保存数据，产出
 * `change_log` 的真实变更摘要行（表名 / 主键 / 变化字段集）。
 *
 * 全量事务保存每次重写全部表行，表行本身不携带差异信息；诊断（最近保存变更）
 * 与 `StorageMetrics` 上报需要的是**业务数据层面的变化**，故在保存后置阶段对
 * 内存数据做一次前后对比。对比基准 = 保存前缓存中的上一份数据
 * （`CacheLayer` 既有缓存，不新增副本）。
 *
 * 覆盖面：`game_data` 主行（字段级）+ 全部集合表（按 `id` 键控的增/删/改）。
 * 变化字段集经数据类反射取值对比得出，字段表按类缓存（保存为低频操作）。
 */
internal object SaveDataChangeSummarizer {

    /** 单次保存摘要行数上限——异常大变更截断为聚合行，防 change_log 单次膨胀 */
    internal const val MAX_ENTRIES_PER_SAVE = 200

    /** 溢出聚合行的 recordId 占位（摘要语义，非真实主键） */
    internal const val OVERFLOW_RECORD_ID = "__overflow__"

    /**
     * 产出变更摘要行。
     *
     * @param previous 上次成功保存的数据；null（首次保存/缓存未命中）时写一条
     *   基线行（`game_data` INSERT + 集合计数），保证诊断面从首次保存起有连续轨迹
     */
    fun summarize(previous: SaveData?, current: SaveData): List<ChangeLogEntry> {
        if (previous == null) return listOf(baselineEntry(current))
        val entries = mutableListOf<ChangeLogEntry>()

        summarizeGameData(entries, previous, current)
        summarizeCollection(entries, "disciples", previous.disciples, current.disciples)
        // equipmentStacks(53) 已随 B3 退役（@Deprecated 协议号占位，恒为空表），不进摘要面
        summarizeCollection(entries, "equipment_instances", previous.equipmentInstances, current.equipmentInstances)
        summarizeCollection(entries, "manual_stacks", previous.manualStacks, current.manualStacks)
        summarizeCollection(entries, "manual_instances", previous.manualInstances, current.manualInstances)
        summarizeCollection(entries, "pills", previous.pills, current.pills)
        summarizeCollection(entries, "materials", previous.materials, current.materials)
        summarizeCollection(entries, "herbs", previous.herbs, current.herbs)
        summarizeCollection(entries, "seeds", previous.seeds, current.seeds)
        summarizeCollection(entries, "storage_bags", previous.storageBags, current.storageBags)
        summarizeCollection(entries, "battle_logs", previous.battleLogs, current.battleLogs)
        summarizeCollection(entries, "alliances", previous.alliances, current.alliances)
        summarizeCollection(entries, "production_slots", previous.productionSlots, current.productionSlots)
        summarizeCollection(entries, "mails", previous.mails, current.mails)

        return entries.take(MAX_ENTRIES_PER_SAVE) + overflowEntries(entries)
    }

    /** 首次保存基线行：集合计数摘要，给诊断面一个可读的起点 */
    private fun baselineEntry(current: SaveData) = ChangeLogEntry(
        tableName = "game_data",
        recordId = "game_data",
        operation = ChangeLogOperation.INSERT,
        newValue = summary(
            "baseline:disciples=${current.disciples.size},pills=${current.pills.size}," +
            "materials=${current.materials.size},battleLogs=${current.battleLogs.size}"
        )
    )

    /** game_data 主行：字段级对比，变化字段名集进 newValue */
    private fun summarizeGameData(
        entries: MutableList<ChangeLogEntry>,
        previous: SaveData,
        current: SaveData
    ) {
        val changed = DataClassDiffer.changedFieldNames(previous.gameData, current.gameData)
        if (changed.isNotEmpty()) {
            entries += ChangeLogEntry(
                tableName = "game_data",
                recordId = "game_data",
                operation = ChangeLogOperation.UPDATE,
                newValue = summary("fields=${changed.joinToString(",")}")
            )
        }
    }

    /**
     * 集合表对比：按 `id` 键控三向分类（新增 INSERT / 删除 DELETE / 变更 UPDATE），
     * 变更行 newValue = 变化字段名集。
     */
    private fun summarizeCollection(
        entries: MutableList<ChangeLogEntry>,
        tableName: String,
        previous: List<Any>,
        current: List<Any>
    ) {
        val previousById = previous.associateBy { entityId(it) }
        val currentById = current.associateBy { entityId(it) }

        for ((id, entity) in currentById) {
            summarizeEntityChange(tableName, previousById[id], id, entity)
                ?.let { entries += it }
        }
        for ((id, _) in previousById) {
            if (id !in currentById) {
                entries += ChangeLogEntry(
                    tableName = tableName,
                    recordId = id,
                    operation = ChangeLogOperation.DELETE,
                    newValue = summary("removed")
                )
            }
        }
    }

    /** 单实体三向分类：null = 无实质变化（既非新增、内容也无差异） */
    private fun summarizeEntityChange(
        tableName: String,
        before: Any?,
        id: String,
        entity: Any
    ): ChangeLogEntry? {
        if (before == null) {
            return ChangeLogEntry(
                tableName = tableName,
                recordId = id,
                operation = ChangeLogOperation.INSERT,
                newValue = summary("added")
            )
        }
        if (before == entity) return null
        val fields = DataClassDiffer.changedFieldNames(before, entity)
        if (fields.isEmpty()) return null
        return ChangeLogEntry(
            tableName = tableName,
            recordId = id,
            operation = ChangeLogOperation.UPDATE,
            newValue = summary("fields=${fields.joinToString(",")}")
        )
    }

    /** 超上限部分的聚合行：按表各一条，保量表级量感（真实主键级明细被截断） */
    private fun overflowEntries(entries: List<ChangeLogEntry>): List<ChangeLogEntry> {
        if (entries.size <= MAX_ENTRIES_PER_SAVE) return emptyList()
        return entries.drop(MAX_ENTRIES_PER_SAVE)
            .groupBy { it.tableName }
            .map { (tableName, dropped) ->
                ChangeLogEntry(
                    tableName = tableName,
                    recordId = OVERFLOW_RECORD_ID,
                    operation = ChangeLogOperation.UPDATE,
                    newValue = summary("overflow:count=${dropped.size}")
                )
            }
    }

    /** 摘要文本 → new_value 列载荷（UTF-8 字节） */
    private fun summary(text: String): ByteArray = text.toByteArray(Charsets.UTF_8)

    private fun entityId(entity: Any): String =
        DataClassDiffer.fieldValue(entity, "id") as? String ?: ""
}

/**
 * 数据类字段级对比（反射，字段表按类缓存）。
 *
 * 只读 `declaredFields`（数据类属性即构造属性），排除静态/合成字段与
 * [SaveDataChangeSummarizer] 声明的保存戳字段；字段缺失（无 backing field）
 * 视为不可比，跳过不误报。
 */
internal object DataClassDiffer {

    private val fieldsCache = ConcurrentHashMap<Class<*>, List<Field>>()

    fun changedFieldNames(previous: Any, current: Any): List<String> {
        val fields = accessibleFields(previous.javaClass)
        return fields.mapNotNull { field ->
            if (field.get(previous) != field.get(current)) field.name else null
        }
    }

    fun fieldValue(target: Any, name: String): Any? =
        accessibleFields(target.javaClass).firstOrNull { it.name == name }?.get(target)

    private fun accessibleFields(clazz: Class<*>): List<Field> = fieldsCache.getOrPut(clazz) {
        clazz.declaredFields
            .filter { !it.isSynthetic && !Modifier.isStatic(it.modifiers) && it.name !in EXCLUDED }
            .onEach { it.isAccessible = true }
    }

    /** 保存动作自身维护的戳字段：恒变无业务信息量 */
    private val EXCLUDED = setOf("timestamp", "saveVersion")
}
