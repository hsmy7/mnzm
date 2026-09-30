// GameDatabaseMigrationsV64.kt — v63→v64 迁移（见 GameDatabase.kt ALL_MIGRATIONS）
package com.xianxia.sect.data.local

import android.util.Log
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Migration 文件共用的日志 TAG（各文件独立声明，避免 top-level 冲突） */
private const val TAG_V64 = "GameDatabase"

/**
 * v63→v64: 装备体系原子替换（装备重构 B3，方案 §5.1/§6.5 A1）。
 *
 * 七步（全部幂等，迁移中断重试安全）：
 * 1. 堆叠/实例旧表搬运到影子表（`legacy_equipment_stacks`/`legacy_equipment_instances`，
 *    `CREATE TABLE AS SELECT` 原样保行）——补偿数据源：方案 §5.4 的折算补偿在
 *    **规则链**（Kotlin）执行，而规则链跑在 Room 装配出的 SaveData 上；若迁移
 *    直接 DROP，Room 主链的旧装备行补偿不可达（方案盲区，B3 实施补齐）。影子表
 *    由 `StorageEngineLoadOps` 装配时物化进 `SaveData.equipmentStacks`（deprecated
 *    载体），补偿置位后 `EquipmentLegacyDao.dropLegacyTables()` 清除；
 * 2. `DROP TABLE equipment_stacks`——堆叠语义整体退役（R6）；
 * 3. `DROP TABLE equipment_instances` + 按新模型重建（一行一实例、词条/等级
 *    随实例单点）——旧装备全部作废（R2 既定结果，不是迁移缺陷），折算补偿由
 *    `LegacyEquipmentCompensationRule` 在读档链发放（§5.4）；
 * 4. `disciples` 增 5 个新部位列（headId/bodyId/handsId/feetId/legsId）；
 * 5. **清空六个部位列**（含复用的 weaponId）——🔴 必须清行（方案 §6.5 A1）：
 *    Room 按枚举 name 落列，旧 `slot="WEAPON"` 行若不清会被当作新武器部位
 *    读出（幽灵件）；`ACCESSORY` 行则被静默回退成 HEAD；
 * 6. `disciples` 删 9 列（旧 armorId/bootsId/accessoryId 三槽 + 四 nurture +
 *    孕养 checkpoint 两列）——走 `rebuildTableDroppingColumns`
 *    （禁 `ALTER TABLE DROP COLUMN`，SQLite < 3.35）；
 * 7. `game_data` 增 1 列 `legacy_equipment_compensated`（旧装备折算补偿幂等
 *    标记，默认 0；与发放同事务由规则置位）。
 *
 * 升级前由 `GameDatabase.backupDatabaseForMigration` 落文件级备份，降级依赖该
 * 备份恢复；**存档版本高于旧客户端时不可回退**（I1，更新公告见 B5）。
 * 64.json 由 KSP 自动导出。
 */
internal val MIGRATION_63_64 = object : Migration(63, 64) {
    @Suppress("LongMethod") // 七步迁移逐段平铺（影子搬运/退役/重建/加列/清槽/删列/标记），拆分遮蔽原子性（V62To63 同先例）
    override fun migrate(db: SupportSQLiteDatabase) {
        // ① 影子表搬运（幂等：仅在**旧形态源表**仍在时搬运——迁移中断重试时
        //    ②/③ 可能已删旧表，此时既有影子行已保真、原样保留；新模型实例表
        //    （无 nurtureLevel 列）绝不回写影子表，防用空表覆盖补偿数据源）
        if (columnExists(db, "equipment_stacks", "id")) {
            db.execSQL("DROP TABLE IF EXISTS `legacy_equipment_stacks`")
            db.execSQL(
                "CREATE TABLE `legacy_equipment_stacks` AS " +
                    "SELECT * FROM `equipment_stacks`"
            )
        }
        if (columnExists(db, "equipment_instances", "nurtureLevel")) {
            db.execSQL("DROP TABLE IF EXISTS `legacy_equipment_instances`")
            db.execSQL(
                "CREATE TABLE `legacy_equipment_instances` AS " +
                    "SELECT * FROM `equipment_instances`"
            )
        }

        // ② 堆叠表整体退役
        db.execSQL("DROP TABLE IF EXISTS `equipment_stacks`")

        // ③ 实例表按新模型重建（旧装备全部作废；列定义与实体声明逐字一致，
        //    Room schema 校验以 64.json 为准）
        db.execSQL("DROP TABLE IF EXISTS `equipment_instances`")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `equipment_instances` (" +
                "`id` TEXT NOT NULL, " +
                "`slot_id` INTEGER NOT NULL, " +
                "`name` TEXT NOT NULL, " +
                "`setId` TEXT NOT NULL, " +
                "`part` TEXT NOT NULL, " +
                "`growth` TEXT NOT NULL, " +
                "`meta` TEXT NOT NULL, " +
                "`ownerId` TEXT, " +
                "`isEquipped` INTEGER NOT NULL, " +
                "PRIMARY KEY(`id`, `slot_id`))"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_equipment_instances_ownerId` " +
            "ON `equipment_instances`(`ownerId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_equipment_instances_setId` " +
            "ON `equipment_instances`(`setId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_equipment_instances_part` " +
            "ON `equipment_instances`(`part`)")

        // ④ disciples 增 5 个新部位列（复用列 weaponId 不动）
        for (column in listOf("headId", "bodyId", "handsId", "feetId", "legsId")) {
            if (!columnExists(db, "disciples", column)) {
                db.execSQL("ALTER TABLE `disciples` ADD COLUMN `$column` TEXT NOT NULL DEFAULT ''")
            }
        }

        // ⑤ 清空六个部位列（含复用的 weaponId；A1 幽灵件兜底，幂等）
        db.execSQL(
            "UPDATE `disciples` SET `weaponId`='', `headId`='', `bodyId`='', " +
                "`handsId`='', `feetId`='', `legsId`='' WHERE 1"
        )

        // ⑥ 删 9 列（旧三槽 id + 四 nurture + 孕养 checkpoint 两列）
        rebuildTableDroppingColumns(
            db = db,
            table = "disciples",
            columnsToDrop = listOf(
                "armorId", "bootsId", "accessoryId",
                "weaponNurture", "armorNurture", "bootsNurture", "accessoryNurture",
                "equipmentNurturingCompletionMonth", "equipmentNurturingCompletionPhase"
            ),
            pkColumns = listOf("id", "slot_id"),
            indices = V63_DISCIPLES_INDICES
        )

        // ⑦ game_data 增旧装备补偿幂等标记列
        if (!columnExists(db, "game_data", "legacy_equipment_compensated")) {
            db.execSQL(
                "ALTER TABLE `game_data` ADD COLUMN `legacy_equipment_compensated` " +
                    "INTEGER NOT NULL DEFAULT 0"
            )
        }

        Log.i(
            TAG_V64,
            "Migration 63→64: equipment system atomic replacement " +
                "(equipment_stacks dropped, equipment_instances rebuilt, disciples " +
                "+5 part columns / slots cleared / -9 legacy columns, " +
                "game_data +legacy_equipment_compensated)"
        )
    }
}
