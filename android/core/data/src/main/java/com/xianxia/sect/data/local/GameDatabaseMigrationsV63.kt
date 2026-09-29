// GameDatabaseMigrationsV63.kt — v62→v63 迁移（见 GameDatabase.kt ALL_MIGRATIONS）
package com.xianxia.sect.data.local

import android.util.Log
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Migration 文件共用的日志 TAG（各文件独立声明，避免 top-level 冲突） */
private const val TAG_V63 = "GameDatabase"

/**
 * v62→v63: 孕养类加成丹药退役（装备重构 B2 / R11，方案 §5.7）。
 *
 * 三步（全部幂等，迁移中断重试安全）：
 * 1. `pills` 删 2 列 `nurtureSpeedPercent`/`nurtureAdd`（@Embedded PillEffect
 *    平铺列，物品定义面彻底退役）+ `disciples` 删 1 列 `pillNurtureSpeedBonus`
 *    （字段与 `DiscipleSurrogate` 47 号已退役，旧档值随列丢弃 = 正在生效的
 *    孕养速度临时效果清零，方案 §5.7 不补偿）——均走
 *    `rebuildTableDroppingColumns`（禁 `ALTER TABLE DROP COLUMN`，SQLite < 3.35）；
 * 2. `game_data` 增 1 列 `nurture_pills_retired`（补偿幂等标记，默认 0）；
 * 3. `recipes` 表删除孕养丹配方行（id 前缀 `nurtureSpeed_`/`nurtureAdd_`）——
 *    数据清理幂等执行；`production_state.unlockedRecipes`/`game_data.unlockedRecipes`
 *    的 JSON 列不做 SQL 精细编辑，由 `NurturePillRetirementRule` 在读档链清理
 *    真源后随保存整列回写收敛。
 *
 * 升级前由 `GameDatabase.backupDatabaseForMigration` 落文件级备份，降级依赖该
 * 备份恢复。63.json 由 KSP 自动导出。
 */
internal val MIGRATION_62_63 = object : Migration(62, 63) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // ① pills 删 nurtureSpeedPercent/nurtureAdd 两列（@Embedded PillEffect 平铺列，
        //    物品定义面彻底退役；待删列不存在时工具内部直接返回）
        rebuildTableDroppingColumns(
            db = db,
            table = "pills",
            columnsToDrop = listOf("nurtureSpeedPercent", "nurtureAdd"),
            pkColumns = listOf("id", "slot_id"),
            indices = V63_PILLS_INDICES
        )

        // ② disciples 删 pillNurtureSpeedBonus 列（同上）
        rebuildTableDroppingColumns(
            db = db,
            table = "disciples",
            columnsToDrop = listOf("pillNurtureSpeedBonus"),
            pkColumns = listOf("id", "slot_id"),
            indices = V63_DISCIPLES_INDICES
        )

        // ② game_data 增补偿幂等标记列
        if (!columnExists(db, "game_data", "nurture_pills_retired")) {
            db.execSQL(
                "ALTER TABLE `game_data` ADD COLUMN `nurture_pills_retired` " +
                    "INTEGER NOT NULL DEFAULT 0"
            )
        }

        // ③ recipes 表清理孕养丹配方行（每配方一行，前缀 DELETE 幂等）
        db.execSQL("DELETE FROM `recipes` WHERE `id` LIKE 'nurtureSpeed\\_%' ESCAPE '\\'")
        db.execSQL("DELETE FROM `recipes` WHERE `id` LIKE 'nurtureAdd\\_%' ESCAPE '\\'")

        Log.i(
            TAG_V63,
            "Migration 62→63: nurture pill retirement (pills -2 cols, disciples -1 col, " +
                "game_data +nurture_pills_retired, recipes nurture rows purged)"
        )
    }
}

/** v62→v63 删列重建后需恢复的 pills 索引（与实体声明一致） */
internal val V63_PILLS_INDICES = listOf(
    Triple("index_pills_name", "`pills`(`name`)", false),
    Triple("index_pills_rarity", "`pills`(`rarity`)", false),
    Triple("index_pills_category", "`pills`(`category`)", false),
    Triple("index_pills_targetRealm", "`pills`(`targetRealm`)", false),
    Triple("index_pills_rarity_category", "`pills`(`rarity`, `category`)", false)
)

/** v62→v63 删列重建后需恢复的 disciples 索引（与 v62 起实体声明一致） */
internal val V63_DISCIPLES_INDICES = listOf(
    Triple("index_disciples_name", "`disciples`(`name`)", false),
    Triple("index_disciples_realm_realmLayer", "`disciples`(`realm`, `realmLayer`)", false),
    Triple("index_disciples_isAlive_realm", "`disciples`(`isAlive`, `realm`)", false),
    Triple("index_disciples_isAlive_status", "`disciples`(`isAlive`, `status`)", false),
    Triple("index_disciples_discipleType", "`disciples`(`discipleType`)", false)
)
