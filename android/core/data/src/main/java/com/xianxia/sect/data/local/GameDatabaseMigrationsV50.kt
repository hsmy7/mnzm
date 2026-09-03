// GameDatabaseMigrationsV50.kt — 由 GameDatabase.kt 拆分生成（见 GameDatabase.kt addMigrations 列表）
package com.xianxia.sect.data.local

import android.util.Log
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Migration 文件共用的日志 TAG（各文件独立声明，避免 top-level 冲突） */
private const val TAG = "GameDatabase"

/**
 * v49→v50: 自动存档残留清理——删除 game_data / sect_policy_state 的 autoSaveIntervalMonths 列
 *
 * 背景（决策2：纯手动存档为既定产品设计；本条为残留彻底清理）。自动存档机制
 * 曾被正式移除（changelog_entries.json:813），但 autoSaveIntervalMonths 列仍残留
 * 于两张表：game_data 与 sect_policy_state。本次删除该列，杜绝后续被误判为功能缺失。
 *
 * SQLite < 3.35.0（API24 内置 3.9）不支持 DROP COLUMN（规则 7.2），故采用
 * create-copy-drop-rename 重建表。
 *
 * ⚠️ 关键：不能复用 [rebuildGameData] / GAME_DATA_CREATE_SQL——那是 v29 历史基线，
 * 用它重建会丢弃 v29 之后新增的 21 列（roads、battle_teams、jade_*、secret_realm_*、
 * soundEnabled、musicEnabled、pending_trait_adds 等）。本迁移改为：
 *  1. 读旧表 PRAGMA table_info 的全部列（name/type/notnull/dflt_value/pk）
 *  2. 剔除 autoSaveIntervalMonths，用逐列重建 CREATE TABLE（保留约束与默认值）
 *  3. 逐列名 INSERT SELECT 复制数据 → 删旧表 → 重命名 → 重建索引
 *
 * 旧档兼容：GameData / SectPolicyState 实体上的 autoSaveIntervalMonths 已改 @Ignore +
 * @Transient——新档不再写入；旧档经 lenient 解码（ignoreUnknownKeys=true）跳过该字段仍可读。
 * 数据无损：被删列无消费方（纯手动存档），不迁移业务数据。
 */
internal val MIGRATION_49_50 = object : Migration(49, 50) {
    override fun migrate(db: SupportSQLiteDatabase) {
        rebuildDroppingColumn(
            db = db,
            table = "game_data",
            columnToDrop = "autoSaveIntervalMonths",
            pkColumns = listOf("id", "slot_id"),
            indices = listOf(
                Triple("index_game_data_slot_id", "game_data(`slot_id`)", true),
                Triple("index_game_data_lastSaveTime", "game_data(`lastSaveTime`)", false),
                Triple("index_game_data_gameYear_gameMonth", "game_data(`gameYear`, `gameMonth`)", false),
                Triple("index_game_data_sectName", "game_data(`sectName`)", false),
                Triple("index_game_data_spiritStones", "game_data(`spiritStones`)", false)
            )
        )
        Log.i(TAG, "Migration 49→50: dropped autoSaveIntervalMonths from game_data")

        rebuildDroppingColumn(
            db = db,
            table = "sect_policy_state",
            columnToDrop = "autoSaveIntervalMonths",
            pkColumns = listOf("slot_id"),
            indices = listOf(
                Triple("index_sect_policy_state_slot_id", "sect_policy_state(`slot_id`)", true)
            )
        )
        Log.i(TAG, "Migration 49→50: dropped autoSaveIntervalMonths from sect_policy_state")
    }

    /**
     * 用 create-copy-drop-rename 重建指定表并删除一列。
     *
     * 从 PRAGMA table_info 读旧表全部列（保留类型/NOT NULL/DEFAULT/主键位），
     * 剔除 [columnToDrop] 后逐列重建新表；仅当目标列存在时才执行（幂等）。
     */
    private fun rebuildDroppingColumn(
        db: SupportSQLiteDatabase,
        table: String,
        columnToDrop: String,
        pkColumns: List<String>,
        indices: List<Triple<String, String, Boolean>>
    ) {
        if (!columnExists(db, table, columnToDrop)) return

        // 1. 读旧表列定义（排除目标列）
        val colDefs = mutableListOf<String>()
        val colNames = mutableListOf<String>()
        val cursor = db.query("PRAGMA table_info($table)")
        cursor.use {
            while (it.moveToNext()) {
                val name = it.getString(it.getColumnIndexOrThrow("name"))
                if (name == columnToDrop) continue
                val type = it.getString(it.getColumnIndexOrThrow("type"))
                val notNull = it.getInt(it.getColumnIndexOrThrow("notnull")) == 1
                val default = it.getString(it.getColumnIndexOrThrow("dflt_value"))
                val quoted = "`$name` $type"
                val def = buildString {
                    append(quoted)
                    if (notNull) append(" NOT NULL")
                    if (default != null) append(" DEFAULT $default")
                }
                colDefs.add(def)
                colNames.add("`$name`")
            }
        }

        val pkClause = if (pkColumns.isNotEmpty()) {
            ", PRIMARY KEY(${pkColumns.joinToString(", ") { "`$it`" }})"
        } else {
            ""
        }

        db.execSQL(
            "ALTER TABLE `$table` RENAME TO `${table}_old`"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `$table` (${colDefs.joinToString(", ").removeSuffix(",")}$pkClause)"
        )
        db.execSQL(
            "INSERT INTO `$table` SELECT ${colNames.joinToString(", ")} FROM `${table}_old`"
        )
        db.execSQL("DROP TABLE IF EXISTS `${table}_old`")
        for ((idxName, idxExpr, isUnique) in indices) {
            val unique = if (isUnique) "UNIQUE " else ""
            db.execSQL("CREATE $unique INDEX IF NOT EXISTS `$idxName` ON $idxExpr")
        }
    }
}
