package com.xianxia.sect.data.local

import android.database.Cursor
import android.util.Log
import androidx.room.RoomDatabase
import com.xianxia.sect.core.model.EquipmentStack

/**
 * 旧装备影子表读取器（B3 补偿数据源，见 [MIGRATION_63_64] KDoc 第①步）。
 *
 * 影子表（`legacy_equipment_stacks` / `legacy_equipment_instances`）由迁移以
 * `CREATE TABLE AS SELECT` 原样保行——它们不是 @Database 实体（Room 编译期
 * 校验不允许 DAO 查询未注册表），故走 rawQuery 手工映射。
 *
 * 生命周期：读档装配时读取（仅在补偿未置位时）→ `LegacyEquipmentCompensationRule`
 * 折算补偿并置位 → [dropLegacyTables] 清除。补偿已置位的存档读影子表恒空。
 */
class EquipmentLegacyTableReader(private val db: RoomDatabase) {

    /** 读旧堆叠行（仓库装备；`quantity` 保留原值） */
    fun readLegacyStacks(): List<EquipmentStack> =
        queryRows("legacy_equipment_stacks") { c ->
            EquipmentStack(
                id = c.string("id"),
                slotId = c.int("slot_id"),
                name = c.string("name"),
                rarity = c.int("rarity"),
                description = c.string("description"),
                slot = parseLegacySlot(c.string("slot")),
                minRealm = c.int("minRealm"),
                quantity = c.int("quantity"),
                isLocked = c.int("isLocked") != 0
            )
        }

    /** 读旧实例行（已装备/储物袋在册件；一行一件 → 堆叠形，携带孕养等级供折算系数） */
    fun readLegacyInstancesAsStacks(): List<EquipmentStack> =
        queryRows("legacy_equipment_instances") { c ->
            EquipmentStack(
                id = c.string("id"),
                slotId = c.int("slot_id"),
                name = c.string("name"),
                rarity = c.int("rarity"),
                description = c.string("description"),
                slot = parseLegacySlot(c.string("slot")),
                minRealm = c.int("minRealm"),
                quantity = 1,
                isLocked = false,
                nurtureLevel = c.int("nurtureLevel")
            )
        }

    /** 补偿完成后清除影子表（幂等；表不存在时静默通过） */
    fun dropLegacyTables() {
        val database = db.openHelper.writableDatabase
        database.execSQL("DROP TABLE IF EXISTS `legacy_equipment_stacks`")
        database.execSQL("DROP TABLE IF EXISTS `legacy_equipment_instances`")
        Log.i(TAG, "Legacy equipment shadow tables dropped")
    }

    @Suppress("TooGenericExceptionCaught") // 防御兜底：附件串/影子表为迁移历史落库形态（库不可用/表不存在/搬运中断），异常源是数据形态而非程序错误，按空处理保读档主链
    private inline fun queryRows(
        table: String,
        mapRow: (Cursor) -> EquipmentStack
    ): List<EquipmentStack> {
        val database = try {
            db.openHelper.writableDatabase
        } catch (e: Exception) {
            Log.w(TAG, "影子表 $table 读取失败（库不可用），按空处理", e)
            return emptyList()
        }
        return try {
            database.query("SELECT * FROM `$table`").use { cursor ->
                val rows = ArrayList<EquipmentStack>(cursor.count)
                while (cursor.moveToNext()) rows.add(mapRow(cursor))
                rows
            }
        } catch (e: Exception) {
            // 影子表不存在（新装/已清理/搬运中断重试已消费）按空处理——补偿零发放
            Log.w(TAG, "影子表 $table 不存在或不可读，按空处理", e)
            emptyList()
        }
    }

    private fun Cursor.string(column: String): String =
        getColumnIndexOrThrow(column).let { getString(it) ?: "" }

    private fun Cursor.int(column: String): Int =
        getColumnIndexOrThrow(column).let { getInt(it) }

    /** 旧四槽枚举名 → 新六部位（补偿载体只作展示/日志，映射错误无经济影响） */
    private fun parseLegacySlot(name: String): com.xianxia.sect.core.model.EquipmentSlot =
        com.xianxia.sect.core.model.EquipmentSlot.entries.find { it.name == name }
            ?: com.xianxia.sect.core.model.EquipmentSlot.HEAD

    companion object {
        private const val TAG = "EquipmentLegacyReader"
    }
}
