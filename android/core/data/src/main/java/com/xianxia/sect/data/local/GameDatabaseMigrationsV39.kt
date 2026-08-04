// GameDatabaseMigrationsV39.kt — 由 GameDatabase.kt 拆分生成（见 GameDatabase.kt addMigrations 列表）
package com.xianxia.sect.data.local

import android.util.Log
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Migration 文件共用的日志 TAG（各文件独立声明，避免 top-level 冲突） */
private const val TAG = "GameDatabase"

/**
 * v38→v39: 删除弟子级 autoLearnFromWarehouse/autoEquipFromWarehouse 死开关列
 *
 * 背景：弟子级开关（Disciple.autoLearnFromWarehouse、EquipmentSet.autoEquipFromWarehouse）
 * 是死代码——UI 无入口、引擎从不读取（自动学习/装备只认 gameData 级全局策略），
 * 本次重构整体删除字段。
 *
 * 实现：SQLite < 3.35 不支持 DROP COLUMN（项目规范 7.2 禁止），采用
 * create-copy-drop-rename 重建三张表（参照 MIGRATION_30_31 先例）：
 * - disciples：删除 autoLearnFromWarehouse、autoEquipFromWarehouse（101 列，重建 7 个索引）
 * - disciples_extended：删除 autoLearnFromWarehouse（23 列，无索引）
 * - disciples_equipment：删除 autoEquipFromWarehouse（14 列，无索引）
 *
 * 2026-08-04 修复：原实现为 no-op 保留旧列——Room 2.7.0 迁移后强制 onValidateSchema
 * （TableInfo.equalsCommon 对列做全等比较，不允许多余列），老存档升级 v39 时在
 * "Migration didn't properly handle: disciples" 处崩溃（迁移事务回滚、每次启动复现）。
 * 重建后表结构与 v39 实体完全一致，校验通过。列定义取自 39.json createSql，
 * 由 RoomMigrationTest 的真实 Room 校验测试保证逐列一致。
 */
internal val MIGRATION_38_39 = object : Migration(38, 39) {
    override fun migrate(db: SupportSQLiteDatabase) {
        rebuildTable(
            db, "disciples", DISCIPLES_V39_CREATE_SQL, DISCIPLES_V39_COLS, DISCIPLES_V39_INDEX_SQL
        )
        rebuildTable(
            db, "disciples_extended", DISCIPLES_EXTENDED_V39_CREATE_SQL,
            DISCIPLES_EXTENDED_V39_COLS, emptyList()
        )
        rebuildTable(
            db, "disciples_equipment", DISCIPLES_EQUIPMENT_V39_CREATE_SQL,
            DISCIPLES_EQUIPMENT_V39_COLS, emptyList()
        )
        Log.i(
            TAG,
            "Migration 38→39: dropped autoLearnFromWarehouse/autoEquipFromWarehouse " +
                "from disciples/disciples_extended/disciples_equipment"
        )
    }
}

/**
 * create-copy-drop-rename 重建表并迁移数据（SQLite < 3.35 不支持 DROP COLUMN）。
 *
 * 注意：RENAME 后索引全部丢失，调用方必须通过 [indexSqls] 重建（Room 2.7
 * 迁移后校验 schema，索引缺失同样判定"Migration didn't properly handle"）。
 */
private fun rebuildTable(
    db: SupportSQLiteDatabase,
    table: String,
    createSql: String,
    cols: List<String>,
    indexSqls: List<String>
) {
    val tempTable = "${table}_v39"
    db.execSQL(createSql)
    val colsSql = cols.joinToString(", ")
    db.execSQL("INSERT INTO `$tempTable` ($colsSql) SELECT $colsSql FROM `$table`")
    db.execSQL("DROP TABLE IF EXISTS `$table`")
    db.execSQL("ALTER TABLE `$tempTable` RENAME TO `$table`")
    indexSqls.forEach { db.execSQL(it) }
}

/** v39 disciples 建表 SQL（与 39.json createSql 逐列一致，含 5 个 DEFAULT 列） */
private val DISCIPLES_V39_CREATE_SQL = """
        CREATE TABLE IF NOT EXISTS `disciples_v39` (
            `id` TEXT NOT NULL,
            `slot_id` INTEGER NOT NULL,
            `name` TEXT NOT NULL,
            `surname` TEXT NOT NULL,
            `realm` INTEGER NOT NULL,
            `realmLayer` INTEGER NOT NULL,
            `cultivation` REAL NOT NULL,
            `cultivationCheckpoint` REAL NOT NULL,
            `cultivationCheckpointGameMonth` INTEGER NOT NULL,
            `spiritRootType` TEXT NOT NULL,
            `age` INTEGER NOT NULL,
            `lifespan` INTEGER NOT NULL,
            `isAlive` INTEGER NOT NULL,
            `gender` TEXT NOT NULL,
            `portraitRes` TEXT NOT NULL,
            `manualIds` TEXT NOT NULL,
            `talentIds` TEXT NOT NULL,
            `physiqueIds` TEXT NOT NULL,
            `affixIds` TEXT NOT NULL,
            `manualMasteries` TEXT NOT NULL,
            `status` TEXT NOT NULL,
            `statusData` TEXT NOT NULL,
            `cultivationSpeedBonus` REAL NOT NULL,
            `cultivationSpeedDuration` INTEGER NOT NULL,
            `discipleType` TEXT NOT NULL,
            `soulPower` INTEGER NOT NULL,
            `cultivationCompletionMonth` INTEGER NOT NULL DEFAULT 0,
            `cultivationCompletionPhase` INTEGER NOT NULL DEFAULT 1,
            `manualCompletionMonth` INTEGER NOT NULL DEFAULT 0,
            `manualCompletionPhase` INTEGER NOT NULL DEFAULT 1,
            `equipmentNurturingCompletionMonth` INTEGER NOT NULL DEFAULT 0,
            `equipmentNurturingCompletionPhase` INTEGER NOT NULL DEFAULT 1,
            `baseHp` INTEGER NOT NULL,
            `baseMp` INTEGER NOT NULL,
            `basePhysicalAttack` INTEGER NOT NULL,
            `baseMagicAttack` INTEGER NOT NULL,
            `basePhysicalDefense` INTEGER NOT NULL,
            `baseMagicDefense` INTEGER NOT NULL,
            `baseSpeed` INTEGER NOT NULL,
            `hpVariance` INTEGER NOT NULL,
            `mpVariance` INTEGER NOT NULL,
            `physicalAttackVariance` INTEGER NOT NULL,
            `magicAttackVariance` INTEGER NOT NULL,
            `physicalDefenseVariance` INTEGER NOT NULL,
            `magicDefenseVariance` INTEGER NOT NULL,
            `speedVariance` INTEGER NOT NULL,
            `totalCultivation` INTEGER NOT NULL,
            `breakthroughCount` INTEGER NOT NULL,
            `breakthroughFailCount` INTEGER NOT NULL,
            `currentHp` INTEGER NOT NULL,
            `currentMp` INTEGER NOT NULL,
            `pillPhysicalAttackBonus` INTEGER NOT NULL,
            `pillMagicAttackBonus` INTEGER NOT NULL,
            `pillPhysicalDefenseBonus` INTEGER NOT NULL,
            `pillMagicDefenseBonus` INTEGER NOT NULL,
            `pillHpBonus` INTEGER NOT NULL,
            `pillMpBonus` INTEGER NOT NULL,
            `pillSpeedBonus` INTEGER NOT NULL,
            `pillCritRateBonus` REAL NOT NULL,
            `pillCritEffectBonus` REAL NOT NULL,
            `pillCultivationSpeedBonus` REAL NOT NULL,
            `pillSkillExpSpeedBonus` REAL NOT NULL,
            `pillNurtureSpeedBonus` REAL NOT NULL,
            `pillEffectDuration` INTEGER NOT NULL,
            `activePillCategory` TEXT NOT NULL,
            `weaponId` TEXT NOT NULL,
            `armorId` TEXT NOT NULL,
            `bootsId` TEXT NOT NULL,
            `accessoryId` TEXT NOT NULL,
            `weaponNurture` TEXT NOT NULL,
            `armorNurture` TEXT NOT NULL,
            `bootsNurture` TEXT NOT NULL,
            `accessoryNurture` TEXT NOT NULL,
            `storageBagItems` TEXT NOT NULL,
            `storageBagSpiritStones` INTEGER NOT NULL,
            `spiritStones` INTEGER NOT NULL,
            `social_partnerId` TEXT,
            `social_partnerSectId` TEXT,
            `social_parentId1` TEXT,
            `social_parentId2` TEXT,
            `social_lastChildYear` INTEGER NOT NULL,
            `social_childBirthMonth` INTEGER,
            `social_griefEndYear` INTEGER,
            `social_masterId` TEXT,
            `intelligence` INTEGER NOT NULL,
            `charm` INTEGER NOT NULL,
            `loyalty` INTEGER NOT NULL,
            `comprehension` INTEGER NOT NULL,
            `artifactRefining` INTEGER NOT NULL,
            `pillRefining` INTEGER NOT NULL,
            `spiritPlanting` INTEGER NOT NULL,
            `mining` INTEGER NOT NULL,
            `teaching` INTEGER NOT NULL,
            `morality` INTEGER NOT NULL,
            `salaryPaidCount` INTEGER NOT NULL,
            `salaryMissedCount` INTEGER NOT NULL,
            `usage_usedFunctionalPillTypes` TEXT NOT NULL,
            `usage_usedExtendLifePillIds` TEXT NOT NULL,
            `usage_recruitedMonth` INTEGER NOT NULL,
            `usage_hasReviveEffect` INTEGER NOT NULL,
            `usage_hasClearAllEffect` INTEGER NOT NULL,
            PRIMARY KEY(`id`, `slot_id`)
        )
    """.trimIndent()

/** v39 disciples 列清单（INSERT INTO ... SELECT 用，已排除删除的 auto 两列） */
private val DISCIPLES_V39_COLS = listOf(
    "id", "slot_id", "name", "surname",
    "realm", "realmLayer", "cultivation", "cultivationCheckpoint",
    "cultivationCheckpointGameMonth", "spiritRootType", "age", "lifespan",
    "isAlive", "gender", "portraitRes", "manualIds",
    "talentIds", "physiqueIds", "affixIds", "manualMasteries",
    "status", "statusData", "cultivationSpeedBonus", "cultivationSpeedDuration",
    "discipleType", "soulPower", "cultivationCompletionMonth", "cultivationCompletionPhase",
    "manualCompletionMonth", "manualCompletionPhase",
    "equipmentNurturingCompletionMonth", "equipmentNurturingCompletionPhase",
    "baseHp", "baseMp", "basePhysicalAttack", "baseMagicAttack",
    "basePhysicalDefense", "baseMagicDefense", "baseSpeed", "hpVariance",
    "mpVariance", "physicalAttackVariance", "magicAttackVariance", "physicalDefenseVariance",
    "magicDefenseVariance", "speedVariance", "totalCultivation", "breakthroughCount",
    "breakthroughFailCount", "currentHp", "currentMp", "pillPhysicalAttackBonus",
    "pillMagicAttackBonus", "pillPhysicalDefenseBonus", "pillMagicDefenseBonus", "pillHpBonus",
    "pillMpBonus", "pillSpeedBonus", "pillCritRateBonus", "pillCritEffectBonus",
    "pillCultivationSpeedBonus", "pillSkillExpSpeedBonus", "pillNurtureSpeedBonus", "pillEffectDuration",
    "activePillCategory", "weaponId", "armorId", "bootsId",
    "accessoryId", "weaponNurture", "armorNurture", "bootsNurture",
    "accessoryNurture", "storageBagItems", "storageBagSpiritStones", "spiritStones",
    "social_partnerId", "social_partnerSectId", "social_parentId1", "social_parentId2",
    "social_lastChildYear", "social_childBirthMonth", "social_griefEndYear", "social_masterId",
    "intelligence", "charm", "loyalty", "comprehension",
    "artifactRefining", "pillRefining", "spiritPlanting", "mining",
    "teaching", "morality", "salaryPaidCount", "salaryMissedCount",
    "usage_usedFunctionalPillTypes", "usage_usedExtendLifePillIds", "usage_recruitedMonth", "usage_hasReviveEffect",
    "usage_hasClearAllEffect"
)

/** v39 disciples 索引（RENAME 丢失索引后必须重建，与 39.json indices 一致） */
private val DISCIPLES_V39_INDEX_SQL = listOf(
    "CREATE INDEX IF NOT EXISTS `index_disciples_name` ON `disciples` (`name`)",
    "CREATE INDEX IF NOT EXISTS `index_disciples_realm_realmLayer` ON `disciples` (`realm`, `realmLayer`)",
    "CREATE INDEX IF NOT EXISTS `index_disciples_isAlive_realm` ON `disciples` (`isAlive`, `realm`)",
    "CREATE INDEX IF NOT EXISTS `index_disciples_isAlive_status` ON `disciples` (`isAlive`, `status`)",
    "CREATE INDEX IF NOT EXISTS `index_disciples_discipleType` ON `disciples` (`discipleType`)",
    "CREATE INDEX IF NOT EXISTS `index_disciples_loyalty` ON `disciples` (`loyalty`)",
    "CREATE INDEX IF NOT EXISTS `index_disciples_age` ON `disciples` (`age`)"
)

/** v39 disciples_extended 建表 SQL（与 39.json createSql 一致） */
private val DISCIPLES_EXTENDED_V39_CREATE_SQL = """
        CREATE TABLE IF NOT EXISTS `disciples_extended_v39` (
            `discipleId` TEXT NOT NULL,
            `slot_id` INTEGER NOT NULL,
            `manualIds` TEXT NOT NULL,
            `talentIds` TEXT NOT NULL,
            `physiqueIds` TEXT NOT NULL,
            `affixIds` TEXT NOT NULL,
            `manualMasteries` TEXT NOT NULL,
            `statusData` TEXT NOT NULL,
            `cultivationSpeedBonus` REAL NOT NULL,
            `cultivationSpeedDuration` INTEGER NOT NULL,
            `pillCultivationSpeedBonus` REAL NOT NULL,
            `pillEffectDuration` INTEGER NOT NULL,
            `partnerId` TEXT,
            `partnerSectId` TEXT,
            `parentId1` TEXT,
            `parentId2` TEXT,
            `lastChildYear` INTEGER NOT NULL,
            `griefEndYear` INTEGER,
            `masterId` TEXT,
            `usedFunctionalPillTypes` TEXT NOT NULL,
            `usedExtendLifePillIds` TEXT NOT NULL,
            `hasReviveEffect` INTEGER NOT NULL,
            `hasClearAllEffect` INTEGER NOT NULL,
            PRIMARY KEY(`discipleId`, `slot_id`)
        )
    """.trimIndent()

/** v39 disciples_extended 列清单（已排除删除的 autoLearnFromWarehouse） */
private val DISCIPLES_EXTENDED_V39_COLS = listOf(
    "discipleId", "slot_id", "manualIds", "talentIds",
    "physiqueIds", "affixIds", "manualMasteries", "statusData",
    "cultivationSpeedBonus", "cultivationSpeedDuration", "pillCultivationSpeedBonus", "pillEffectDuration",
    "partnerId", "partnerSectId", "parentId1", "parentId2",
    "lastChildYear", "griefEndYear", "masterId", "usedFunctionalPillTypes",
    "usedExtendLifePillIds", "hasReviveEffect", "hasClearAllEffect"
)

/** v39 disciples_equipment 建表 SQL（与 39.json createSql 一致） */
private val DISCIPLES_EQUIPMENT_V39_CREATE_SQL = """
        CREATE TABLE IF NOT EXISTS `disciples_equipment_v39` (
            `discipleId` TEXT NOT NULL,
            `slot_id` INTEGER NOT NULL,
            `weaponId` TEXT NOT NULL,
            `armorId` TEXT NOT NULL,
            `bootsId` TEXT NOT NULL,
            `accessoryId` TEXT NOT NULL,
            `weaponNurture` TEXT NOT NULL,
            `armorNurture` TEXT NOT NULL,
            `bootsNurture` TEXT NOT NULL,
            `accessoryNurture` TEXT NOT NULL,
            `storageBagItems` TEXT NOT NULL,
            `storageBagSpiritStones` INTEGER NOT NULL,
            `spiritStones` INTEGER NOT NULL,
            `soulPower` INTEGER NOT NULL,
            PRIMARY KEY(`discipleId`, `slot_id`)
        )
    """.trimIndent()

/** v39 disciples_equipment 列清单（已排除删除的 autoEquipFromWarehouse） */
private val DISCIPLES_EQUIPMENT_V39_COLS = listOf(
    "discipleId", "slot_id", "weaponId", "armorId",
    "bootsId", "accessoryId", "weaponNurture", "armorNurture",
    "bootsNurture", "accessoryNurture", "storageBagItems", "storageBagSpiritStones",
    "spiritStones", "soulPower"
)
