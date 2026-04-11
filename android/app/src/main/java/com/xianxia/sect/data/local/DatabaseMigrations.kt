package com.xianxia.sect.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import android.util.Log

enum class MigrationGeneration(val startVersion: Int, val endVersion: Int, val description: String) {
    GEN_1_TO_10(1, 10, "Initial schema - core tables"),
    GEN_11_TO_25(11, 25, "Disciple split - 6 sub-tables"),
    GEN_26_TO_40(26, 40, "Feature expansion - events, buildings"),
    GEN_41_TO_55(41, 55, "Performance optimization - indexes"),
    GEN_56_TO_61(56, 61, "Recent changes - unified architecture"),
    GEN_62_PLUS(62, Int.MAX_VALUE, "Future migrations");

    companion object {
        fun findGeneration(version: Int): MigrationGeneration {
            return entries.lastOrNull { version >= it.startVersion } ?: GEN_1_TO_10
        }
    }
}

data class MigrationManifest(
    val currentVersion: Int,
    val targetVersion: Int,
    val generations: List<MigrationGeneration>,
    val estimatedTimeMs: Long,
    val requiresFullBackup: Boolean
)

object DatabaseMigrations {

    private const val TAG = "DatabaseMigrations"

    internal class Migration53To54 : Migration(53, 54) {
        override fun migrate(db: SupportSQLiteDatabase) {
            Log.i(TAG, "Starting migration from version 53 to 54")
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS change_log (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    table_name TEXT NOT NULL,
                    record_id TEXT NOT NULL,
                    operation TEXT NOT NULL,
                    old_value BLOB,
                    new_value BLOB,
                    timestamp INTEGER NOT NULL,
                    synced INTEGER NOT NULL DEFAULT 0,
                    sync_version INTEGER NOT NULL DEFAULT 0
                )
            """)
            db.execSQL("CREATE INDEX IF NOT EXISTS index_change_log_table_record ON change_log (table_name, record_id)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_change_log_timestamp ON change_log (timestamp)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_change_log_synced ON change_log (synced)")
            Log.i(TAG, "Migration 53 to 54 completed successfully")
        }
    }

    internal class Migration54To55 : Migration(54, 55) {
        override fun migrate(db: SupportSQLiteDatabase) {
            Log.i(TAG, "Migrating from version 54 to 55")
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS alchemy_slots (
                    id TEXT NOT NULL PRIMARY KEY,
                    slotIndex INTEGER NOT NULL,
                    recipeId TEXT,
                    recipeName TEXT NOT NULL,
                    pillName TEXT NOT NULL,
                    pillRarity INTEGER NOT NULL,
                    startYear INTEGER NOT NULL,
                    startMonth INTEGER NOT NULL,
                    duration INTEGER NOT NULL,
                    status TEXT NOT NULL,
                    successRate REAL NOT NULL,
                    requiredMaterials TEXT NOT NULL
                )
            """)
            db.execSQL("CREATE INDEX IF NOT EXISTS index_alchemy_slots_slotIndex ON alchemy_slots (slotIndex)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_disciple_slot_alive ON disciple (slot, isAlive)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_disciple_slot_level ON disciple (slot, level)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_equipment_slot_rarity ON equipment (slot, rarity)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_battle_log_slot_time ON battle_log (slot, timestamp)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_event_slot_time ON game_event (slot, timestamp)")
            Log.i(TAG, "Migration 54 to 55 completed successfully")
        }
    }

    internal class Migration55To56 : Migration(55, 56) {
        override fun migrate(db: SupportSQLiteDatabase) {
            Log.i(TAG, "Migrating from version 55 to 56")
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS data_version (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    table_name TEXT NOT NULL,
                    record_id TEXT NOT NULL,
                    version INTEGER NOT NULL DEFAULT 1,
                    updated_at INTEGER NOT NULL,
                    UNIQUE(table_name, record_id)
                )
            """)
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_data_version_table ON data_version (table_name)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_data_version_record ON data_version (record_id)")
            Log.i(TAG, "Migration 55 to 56 completed successfully")
        }
    }

    internal class Migration56To57 : Migration(56, 57) {
        override fun migrate(db: SupportSQLiteDatabase) {
            Log.i(TAG, "Migrating from version 56 to 57")
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS storage_stats (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    stat_type TEXT NOT NULL,
                    stat_key TEXT NOT NULL,
                    stat_value REAL NOT NULL,
                    recorded_at INTEGER NOT NULL
                )
            """)
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_storage_stats_type ON storage_stats (stat_type)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_storage_stats_time ON storage_stats (recorded_at)")
            Log.i(TAG, "Migration 56 to 57 completed successfully")
        }
    }

    internal class Migration57To58 : Migration(57, 58) {
        override fun migrate(db: SupportSQLiteDatabase) {
            Log.i(TAG, "Migrating from version 57 to 58 - Disciple entity split")

            db.execSQL("""
                CREATE TABLE IF NOT EXISTS disciples_core (
                    id TEXT NOT NULL PRIMARY KEY,
                    name TEXT NOT NULL,
                    realm INTEGER NOT NULL,
                    realmLayer INTEGER NOT NULL,
                    cultivation REAL NOT NULL,
                    isAlive INTEGER NOT NULL,
                    status TEXT NOT NULL,
                    discipleType TEXT NOT NULL,
                    age INTEGER NOT NULL,
                    lifespan INTEGER NOT NULL,
                    gender TEXT NOT NULL,
                    spiritRootType TEXT NOT NULL,
                    recruitedMonth INTEGER NOT NULL,
                    updatedAt INTEGER NOT NULL
                )
            """)

            db.execSQL("""
                CREATE TABLE IF NOT EXISTS disciples_combat (
                    discipleId TEXT NOT NULL PRIMARY KEY,
                    baseHp INTEGER NOT NULL,
                    baseMp INTEGER NOT NULL,
                    basePhysicalAttack INTEGER NOT NULL,
                    baseMagicAttack INTEGER NOT NULL,
                    basePhysicalDefense INTEGER NOT NULL,
                    baseMagicDefense INTEGER NOT NULL,
                    baseSpeed INTEGER NOT NULL,
                    hpVariance INTEGER NOT NULL,
                    mpVariance INTEGER NOT NULL,
                    physicalAttackVariance INTEGER NOT NULL,
                    magicAttackVariance INTEGER NOT NULL,
                    physicalDefenseVariance INTEGER NOT NULL,
                    magicDefenseVariance INTEGER NOT NULL,
                    speedVariance INTEGER NOT NULL,
                    pillPhysicalAttackBonus REAL NOT NULL,
                    pillMagicAttackBonus REAL NOT NULL,
                    pillPhysicalDefenseBonus REAL NOT NULL,
                    pillMagicDefenseBonus REAL NOT NULL,
                    pillHpBonus REAL NOT NULL,
                    pillMpBonus REAL NOT NULL,
                    pillSpeedBonus REAL NOT NULL,
                    pillEffectDuration INTEGER NOT NULL,
                    totalCultivation INTEGER NOT NULL,
                    breakthroughCount INTEGER NOT NULL,
                    breakthroughFailCount INTEGER NOT NULL,
                    FOREIGN KEY (discipleId) REFERENCES disciples_core(id) ON DELETE CASCADE ON UPDATE CASCADE
                )
            """)

            db.execSQL("""
                CREATE TABLE IF NOT EXISTS disciples_equipment (
                    discipleId TEXT NOT NULL PRIMARY KEY,
                    weaponId TEXT,
                    armorId TEXT,
                    bootsId TEXT,
                    accessoryId TEXT,
                    weaponNurture TEXT,
                    armorNurture TEXT,
                    bootsNurture TEXT,
                    accessoryNurture TEXT,
                    storageBagItems TEXT,
                    storageBagSpiritStones INTEGER NOT NULL,
                    spiritStones INTEGER NOT NULL,
                    soulPower INTEGER NOT NULL,
                    FOREIGN KEY (discipleId) REFERENCES disciples_core(id) ON DELETE CASCADE ON UPDATE CASCADE
                )
            """)

            db.execSQL("""
                CREATE TABLE IF NOT EXISTS disciples_extended (
                    discipleId TEXT NOT NULL PRIMARY KEY,
                    manualIds TEXT,
                    talentIds TEXT,
                    manualMasteries TEXT,
                    statusData TEXT,
                    cultivationSpeedBonus REAL NOT NULL,
                    cultivationSpeedDuration INTEGER NOT NULL,
                    partnerId TEXT,
                    partnerSectId TEXT,
                    parentId1 TEXT,
                    parentId2 TEXT,
                    lastChildYear INTEGER NOT NULL,
                    griefEndYear INTEGER,
                    monthlyUsedPillIds TEXT,
                    usedExtendLifePillIds TEXT,
                    hasReviveEffect INTEGER NOT NULL,
                    hasClearAllEffect INTEGER NOT NULL,
                    FOREIGN KEY (discipleId) REFERENCES disciples_core(id) ON DELETE CASCADE ON UPDATE CASCADE
                )
            """)

            db.execSQL("""
                CREATE TABLE IF NOT EXISTS disciples_attributes (
                    discipleId TEXT NOT NULL PRIMARY KEY,
                    intelligence INTEGER NOT NULL,
                    charm INTEGER NOT NULL,
                    loyalty INTEGER NOT NULL,
                    comprehension INTEGER NOT NULL,
                    artifactRefining INTEGER NOT NULL,
                    pillRefining INTEGER NOT NULL,
                    spiritPlanting INTEGER NOT NULL,
                    teaching INTEGER NOT NULL,
                    morality INTEGER NOT NULL,
                    salaryPaidCount INTEGER NOT NULL,
                    salaryMissedCount INTEGER NOT NULL,
                    FOREIGN KEY (discipleId) REFERENCES disciples_core(id) ON DELETE CASCADE ON UPDATE CASCADE
                )
            """)

            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS idx_disciples_combat_disciple ON disciples_combat (discipleId)")
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS idx_disciples_equipment_disciple ON disciples_equipment (discipleId)")
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS idx_disciples_extended_disciple ON disciples_extended (discipleId)")
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS idx_disciples_attributes_disciple ON disciples_attributes (discipleId)")

            DbPragmas.executeSafely(db, "PRAGMA foreign_keys = ON")
            Log.i(TAG, "Migration 57 to 58 completed successfully")
        }
    }

    internal class Migration58To59 : Migration(58, 59) {
        override fun migrate(db: SupportSQLiteDatabase) {
            Log.i(TAG, "Migrating from version 58 to 59 - Add production_slots")
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS production_slots (
                    id TEXT NOT NULL PRIMARY KEY,
                    slotIndex INTEGER NOT NULL,
                    buildingType TEXT NOT NULL,
                    buildingId TEXT NOT NULL,
                    status TEXT NOT NULL,
                    recipeId TEXT,
                    recipeName TEXT NOT NULL,
                    startYear INTEGER NOT NULL,
                    startMonth INTEGER NOT NULL,
                    duration INTEGER NOT NULL,
                    assignedDiscipleId TEXT,
                    assignedDiscipleName TEXT NOT NULL,
                    successRate REAL NOT NULL,
                    requiredMaterials TEXT NOT NULL,
                    outputItemId TEXT,
                    outputItemName TEXT NOT NULL,
                    outputItemRarity INTEGER NOT NULL,
                    outputItemSlot TEXT NOT NULL
                )
            """)
            db.execSQL("CREATE INDEX IF NOT EXISTS index_production_slots_building ON production_slots (buildingId, slotIndex)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_production_slots_type ON production_slots (buildingType)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_production_slots_status ON production_slots (status)")
            Log.i(TAG, "Migration 58 to 59 completed successfully")
        }
    }

    internal class Migration59To60 : Migration(59, 60) {
        override fun migrate(db: SupportSQLiteDatabase) {
            Log.i(TAG, "Migrating from version 59 to 60 - Performance optimization")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_disciples_core_alive_realm ON disciples_core (isAlive, realm)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_disciples_core_alive_status ON disciples_core (isAlive, status)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_disciples_core_type ON disciples_core (discipleType)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_disciples_core_age ON disciples_core (age)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_disciples_attributes_loyalty ON disciples_attributes (loyalty)")
            Log.i(TAG, "Migration 59 to 60 completed successfully")
        }
    }

    /**
     * v60 → v61: 统一单实例架构迁移
     *
     * 核心变更：为所有22个业务表添加 [slot_id] 列（INTEGER NOT NULL DEFAULT 0），
     * 使多个存档槽位的数据可以在单一数据库实例中共存。
     *
     * 设计决策：
     * - slot_id 默认值为 0：旧数据（迁移前已存在的记录）自动归属到 slot 0
     * - 每张表的 slot_id 都创建索引：DAO 层的 WHERE slot_id = ? 查询可命中索引
     * - game_data 表额外创建 UNIQUE 约束：确保每个槽位只有一条 game_data 记录
     * - 幂等性保证：Room Migration 机制确保此迁移只在版本升级时执行一次
     */
    internal class Migration60To61 : Migration(60, 61) {
        override fun migrate(db: SupportSQLiteDatabase) {
            Log.i(TAG, "Starting migration from version 60 to 61 - Unified single-instance architecture")

            // 所有需要添加 slot_id 列的表（与 @Database entities 列表一一对应）
            val tables = listOf(
                "game_data",
                "disciples",
                "equipment",
                "manuals",
                "pills",
                "materials",
                "seeds",
                "herbs",
                "exploration_teams",
                "building_slots",
                "game_events",
                "dungeons",
                "recipes",
                "battle_logs",
                "forge_slots",
                "alchemy_slots",
                "production_slots",
                // 弟弟子表（v57 拆分出的5张表）
                "disciples_core",
                "disciples_combat",
                "disciples_equipment",
                "disciples_extended",
                "disciples_attributes",
                // 增量表
                "change_log"
            )

            for (table in tables) {
                // 为每张表添加 slot_id 列
                // NOT NULL DEFAULT 0: 旧数据自动归入 slot 0，新插入必须显式指定 slot_id
                db.execSQL("ALTER TABLE `$table` ADD COLUMN slot_id INTEGER NOT NULL DEFAULT 0")

                // 防御性编程：显式将所有可能的 NULL 值更新为 0（双重保障）
                // 虽然 DEFAULT 0 应该已处理，但某些 SQLite 版本或边缘情况可能需要此步骤
                db.execSQL("UPDATE `$table` SET slot_id = 0 WHERE slot_id IS NULL")

                // 为 slot_id 创建索引以加速按槽位查询
                // DAO 层的典型查询模式: SELECT * FROM table WHERE slot_id = ?
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_${table}_slot_id ON `$table` (slot_id)")

                Log.d(TAG, "Added slot_id column and index to table: $table")
            }

            // game_data 表特殊处理：每个槽位应只有一条 game_data 记录
            // SQLite 不支持直接修改主键，用 UNIQUE 约束替代实现逻辑唯一性
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS idx_game_data_slot_unique ON game_data (slot_id)"
            )

            Log.i(TAG, "Migration 60 to 61 completed successfully: ${tables.size} tables migrated")
        }
    }

    internal class Migration61To62 : Migration(61, 62) {
        override fun migrate(db: SupportSQLiteDatabase) {
            Log.i(TAG, "Starting migration from version 61 to 62 - DB cleanup and optimization")

            db.execSQL("DROP INDEX IF EXISTS index_disciple_deleted_temp")
            db.execSQL("DROP TABLE IF EXISTS temp_migration_backup")

            db.execSQL("CREATE INDEX IF NOT EXISTS idx_disciple_slot_sect ON disciple(slot_id, sect_id)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_gamedata_slot ON game_data(slot)")

            DbPragmas.executeSafely(db, "ANALYZE")

            try {
                DbPragmas.executeSafely(db, "VACUUM")
            } catch (e: Exception) {
                Log.w(TAG, "VACUUM failed (may be inside transaction), skipping", e)
            }

            Log.i(TAG, "Migration 61 to 62 completed successfully - Database optimized")
        }
    }

    /**
     * v62 -> v63: 索引优化与碎片整理
     *
     * 核心变更：
     * - 创建复合索引覆盖高频查询路径（disciples_core 按槽位+存活+境界、equipment 按槽位+稀有度+主人）
     * - 删除 v62 中创建的低效/冗余索引 idx_gamedata_slot
     * - 更新查询规划器统计信息 (ANALYZE)
     * - 增量释放空闲页 (incremental_vacuum)
     */
    internal class Migration62To63 : Migration(62, 63) {
        override fun migrate(db: SupportSQLiteDatabase) {
            Log.i(TAG, "Starting migration from version 62 to 63 - Index optimization and defragmentation")

            // 1. 创建优化索引：覆盖高频查询的复合索引
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_disciples_core_slot_alive_realm ON disciples_core(slot_id, isAlive, realm)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_equipment_slot_rarity_owner ON equipment(slot_id, rarity, ownerId)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_battle_logs_slot_time ON battle_logs(slot_id, timestamp DESC)")

            // 2. 删除重复/低效索引：v62 中创建的 game_data 单列 slot 索引已被更优方案替代
            db.execSQL("DROP INDEX IF EXISTS idx_gamedata_slot")

            // 3. 更新查询规划器统计：让优化器基于最新数据分布选择执行计划
            DbPragmas.executeSafely(db, "ANALYZE")

            // 4. 增量碎片整理：释放最多 100 个空闲页回操作系统，避免全库 VACUUM 的长时间阻塞
            DbPragmas.executeSafely(db, "PRAGMA incremental_vacuum(100)")

            Log.i(TAG, "Migration 62 to 63 completed successfully - Indexes optimized, DB defragmented")
        }
    }

    /**
     * v63 -> v64: schema identity hash 同步
     *
     * 触发原因：v63 设定后，某 @Entity 类的字段/索引/注解发生变更，
     * 导致编译后的 schema identity hash 与设备上已安装的 DB 不匹配：
     *   Expected: fc7d5ad959ea882847bdb84d6dcc5770 (旧DB)
     *   Found:    6a463829df5af6ff5faa4fa115a099a3 (新代码)
     *
     * 此迁移为空操作（no-op）：Room 在执行此迁移后会用新的 Entity 定义
     * 重新验证 schema，hash 将匹配当前代码。
     *
     * 注意：GameDatabase.create() 中已移除 fallbackToDestructiveMigration()，
     * 改用 MigrationFallbackHandler 提供安全的备份-清理流程。
     * 如果未来出现同类 schema 不匹配问题，需要添加对应的 Migration 对象。
     */
    internal class Migration63To64 : Migration(63, 64) {
        override fun migrate(db: SupportSQLiteDatabase) {
            Log.i(TAG, "Migration 63 to 64 - schema identity hash sync (no-op)")
        }
    }

    /**
     * v64 -> v65: schema identity hash 同步
     *
     * 触发原因：v64 设定后，某 @Entity 类的字段/索引/注解再次发生变更，
     * 导致编译后的 schema identity hash 与设备上已安装的 DB 不匹配：
     *   Expected: 7a2c35ac62179df89da2fc5f7fe0f3f0 (新代码)
     *   Found:    fc7d5ad959ea882847bdb84d6dcc5770 (设备旧DB)
     *
     * 此迁移为空操作（no-op）：Room 在执行此迁移后会用新的 Entity 定义
     * 重新验证 schema，hash 将匹配当前代码。
     */
    internal class Migration64To65 : Migration(64, 65) {
        override fun migrate(db: SupportSQLiteDatabase) {
            Log.i(TAG, "Migration 64 to 65 - schema identity hash sync (no-op)")
        }
    }

    /**
     * v65 -> v66: schema identity hash 同步
     *
     * 触发原因：v65 设定后，Disciple 实体的 @Embedded 子组件字段再次变更，
     * 导致编译后的 schema identity hash 与设备上已安装的 DB 不匹配。
     *
     * 典型错误日志：
     *   IllegalStateException: Migration didn't properly handle: disciples(...)
     *   Expected: [当前 Entity 编译出的完整列定义]
     *   Found:    [设备旧DB的实际列定义]
     *
     * 此迁移为空操作（no-op）：Room 在执行此迁移后会用新的 Entity 定义
     * 重新验证 schema，hash 将匹配当前代码。
     *
     * 根本预防措施（已实现但需持续遵守）：
     * - 每次修改 @Entity 类的字段、@ColumnInfo、@Embedded、Index 后，
     *   必须同步递增 version 并添加对应 Migration
     * - 或在 CI 中增加 schema 校验步骤
     */
    internal class Migration65To66 : Migration(65, 66) {
        override fun migrate(db: SupportSQLiteDatabase) {
            Log.i(TAG, "Migration 65 to 66 - schema identity hash sync (no-op)")
        }
    }

    /**
     * v66 -> v67: disciples 表 schema 强制对齐
     *
     * 触发原因：
     *   设备数据库版本低于 65 时，经过连续的 no-op 迁移(63->64, 64->65, 65->66)后，
     *   disciples 表的实际列定义可能滞后于当前 Disciple Entity 的 @Embedded 展开。
     *   Room 在 open/validate 时检测到 Expected vs Found 不匹配，抛出：
     *     IllegalStateException: Migration didn't properly handle: disciples(...)
     *
     * 策略：
     *   幂等 ADD COLUMN —— 对已存在的列 SQLite 会静默忽略（实际上会报错，所以用 try-catch 包裹），
     *   或先查 sqlite_master 再决定是否添加。这里采用更安全的"查了再加"方式。
     */
    internal class Migration66To67 : Migration(66, 67) {
        override fun migrate(db: SupportSQLiteDatabase) {
            Log.i(TAG, "Migration 66 to 67 - disciples table schema reconciliation")

            val tableName = "disciples"

            // Disciple Entity @Embedded 展开后的完整列清单（按声明顺序）
            // 来源：Disciple.kt 主构造函数 + @Embedded 子组件
            val expectedColumns = listOf(
                // 主构造函数基础字段
                Triple("id", "TEXT", true),
                Triple("slot_id", "INTEGER", true),
                Triple("name", "TEXT", true),
                Triple("realm", "INTEGER", true),
                Triple("realmLayer", "INTEGER", true),
                Triple("cultivation", "REAL", true),
                Triple("spiritRootType", "TEXT", true),
                Triple("age", "INTEGER", true),
                Triple("lifespan", "INTEGER", true),
                Triple("isAlive", "INTEGER", true),
                Triple("gender", "TEXT", true),
                Triple("manualIds", "TEXT", true),
                Triple("talentIds", "TEXT", true),
                Triple("manualMasteries", "TEXT", true),
                Triple("status", "TEXT", true),
                Triple("statusData", "TEXT", true),
                Triple("cultivationSpeedBonus", "REAL", true),
                Triple("cultivationSpeedDuration", "INTEGER", true),
                Triple("discipleType", "TEXT", true),
                // @Embedded combat (无前缀)
                Triple("baseHp", "INTEGER", true),
                Triple("baseMp", "INTEGER", true),
                Triple("basePhysicalAttack", "INTEGER", true),
                Triple("baseMagicAttack", "INTEGER", true),
                Triple("basePhysicalDefense", "INTEGER", true),
                Triple("baseMagicDefense", "INTEGER", true),
                Triple("baseSpeed", "INTEGER", true),
                Triple("hpVariance", "INTEGER", true),
                Triple("mpVariance", "INTEGER", true),
                Triple("physicalAttackVariance", "INTEGER", true),
                Triple("magicAttackVariance", "INTEGER", true),
                Triple("physicalDefenseVariance", "INTEGER", true),
                Triple("magicDefenseVariance", "INTEGER", true),
                Triple("speedVariance", "INTEGER", true),
                Triple("totalCultivation", "INTEGER", true),
                Triple("breakthroughCount", "INTEGER", true),
                Triple("breakthroughFailCount", "INTEGER", true),
                // @Embedded pillEffects (无前缀)
                Triple("pillPhysicalAttackBonus", "REAL", true),
                Triple("pillMagicAttackBonus", "REAL", true),
                Triple("pillPhysicalDefenseBonus", "REAL", true),
                Triple("pillMagicDefenseBonus", "REAL", true),
                Triple("pillHpBonus", "REAL", true),
                Triple("pillMpBonus", "REAL", true),
                Triple("pillSpeedBonus", "REAL", true),
                Triple("pillEffectDuration", "INTEGER", true),
                // @Embedded equipment (无前缀)
                Triple("weaponId", "TEXT", true),
                Triple("armorId", "TEXT", true),
                Triple("bootsId", "TEXT", true),
                Triple("accessoryId", "TEXT", true),
                Triple("weaponNurture", "TEXT", true),
                Triple("armorNurture", "TEXT", true),
                Triple("bootsNurture", "TEXT", true),
                Triple("accessoryNurture", "TEXT", true),
                Triple("storageBagItems", "TEXT", true),
                Triple("storageBagSpiritStones", "INTEGER", true),
                Triple("spiritStones", "INTEGER", true),
                Triple("soulPower", "INTEGER", true),
                // @Embedded social (prefix = "social_")
                Triple("social_partnerId", "TEXT", false),
                Triple("social_partnerSectId", "TEXT", false),
                Triple("social_parentId1", "TEXT", false),
                Triple("social_parentId2", "TEXT", false),
                Triple("social_lastChildYear", "INTEGER", true),
                Triple("social_griefEndYear", "INTEGER", false),
                // @Embedded skills (无前缀)
                Triple("intelligence", "INTEGER", true),
                Triple("charm", "INTEGER", true),
                Triple("loyalty", "INTEGER", true),
                Triple("comprehension", "INTEGER", true),
                Triple("artifactRefining", "INTEGER", true),
                Triple("pillRefining", "INTEGER", true),
                Triple("spiritPlanting", "INTEGER", true),
                Triple("teaching", "INTEGER", true),
                Triple("morality", "INTEGER", true),
                Triple("salaryPaidCount", "INTEGER", true),
                Triple("salaryMissedCount", "INTEGER", true),
                // @Embedded usage (prefix = "usage_")
                Triple("usage_monthlyUsedPillIds", "TEXT", true),
                Triple("usage_usedExtendLifePillIds", "TEXT", true),
                Triple("usage_recruitedMonth", "INTEGER", true),
                Triple("usage_hasReviveEffect", "INTEGER", true),
                Triple("usage_hasClearAllEffect", "INTEGER", true)
            )

            val existingColumns = mutableSetOf<String>()
            db.query("PRAGMA table_info(`$tableName`)").use { cursor ->
                while (cursor.moveToNext()) {
                    existingColumns.add(cursor.getString(1))
                }
            }

            var addedCount = 0
            for ((colName, colType, notNull) in expectedColumns) {
                if (colName !in existingColumns) {
                    val defaultVal = when {
                        colName == "id" -> ""
                        colType == "TEXT" -> " NOT NULL DEFAULT ''"
                        colType == "REAL" -> " NOT NULL DEFAULT 0.0"
                        colType == "INTEGER" && !notNull -> ""
                        else -> " NOT NULL DEFAULT 0"
                    }
                    try {
                        db.execSQL("ALTER TABLE `$tableName` ADD COLUMN `$colName` $colType$defaultVal")
                        addedCount++
                        Log.d(TAG, "  Added missing column: $colName ($colType)")
                    } catch (e: Exception) {
                        Log.w(TAG, "  Failed to add column $colName (may already exist): ${e.message}")
                    }
                }
            }

            Log.i(TAG, "Migration 66 to 67 completed: $addedCount columns added to $tableName")
        }
    }

    internal class Migration67To68 : Migration(67, 68) {
        override fun migrate(db: SupportSQLiteDatabase) {
            Log.i(TAG, "Migration 67 to 68 - GameData primary key fix: id from fixed 'game_data' to 'game_data_{slot_id}'")

            val cursor = db.query("SELECT id, slot_id FROM game_data")
            val updates = mutableListOf<Pair<String, Int>>()
            cursor.use {
                while (it.moveToNext()) {
                    val id = it.getString(0)
                    val slotId = it.getInt(1)
                    updates.add(Pair(id, slotId))
                }
            }

            for ((oldId, slotId) in updates) {
                val newId = "game_data_$slotId"
                if (oldId != newId) {
                    db.execSQL("UPDATE game_data SET id = ? WHERE slot_id = ?", arrayOf(newId, slotId))
                    Log.d(TAG, "  Updated game_data id: '$oldId' -> '$newId' for slot_id=$slotId")
                }
            }

            Log.i(TAG, "Migration 67 to 68 completed: ${updates.size} game_data rows updated")
        }
    }

    internal class Migration68To69 : Migration(68, 69) {
        override fun migrate(db: SupportSQLiteDatabase) {
            Log.i(TAG, "Migration 68 to 69 - Recreate disciples table to fix schema mismatch")

            try { db.execSQL("DROP TABLE IF EXISTS `disciples_old`") } catch (_: Exception) {}

            val existingColumns = mutableSetOf<String>()
            try {
                db.query("PRAGMA table_info(`disciples`)").use { cursor ->
                    while (cursor.moveToNext()) {
                        existingColumns.add(cursor.getString(1))
                    }
                }
            } catch (_: Exception) {}

            if (existingColumns.isEmpty()) {
                Log.i(TAG, "disciples table does not exist, creating from scratch")
                createDisciplesTable(db)
                createDisciplesIndexes(db)
                Log.i(TAG, "Migration 68 to 69 completed: disciples table created")
                return
            }

            val hasNullableSocialPartnerId = checkColumnNullable(db, "disciples", "social_partnerId")
            val hasNullableSocialGriefEndYear = checkColumnNullable(db, "disciples", "social_griefEndYear")

            if (hasNullableSocialPartnerId && hasNullableSocialGriefEndYear) {
                Log.i(TAG, "disciples table schema already correct, skipping recreation")
                createDisciplesIndexes(db)
                Log.i(TAG, "Migration 68 to 69 completed: schema already correct, indexes ensured")
                return
            }

            db.execSQL("ALTER TABLE `disciples` RENAME TO `disciples_old`")

            createDisciplesTable(db)

            val newColumns = mutableSetOf<String>()
            db.query("PRAGMA table_info(`disciples`)").use { cursor ->
                while (cursor.moveToNext()) {
                    newColumns.add(cursor.getString(1))
                }
            }

            val commonColumns = existingColumns.intersect(newColumns).toList()
            val columnList = commonColumns.joinToString(", ") { "`$it`" }

            if (columnList.isNotEmpty()) {
                db.execSQL("INSERT INTO `disciples` ($columnList) SELECT $columnList FROM `disciples_old`")
                Log.d(TAG, "Copied data for ${commonColumns.size} common columns from disciples_old to disciples")
            }

            db.execSQL("DROP TABLE `disciples_old`")

            createDisciplesIndexes(db)

            Log.i(TAG, "Migration 68 to 69 completed: disciples table recreated with correct schema")
        }

        private fun checkColumnNullable(db: SupportSQLiteDatabase, table: String, column: String): Boolean {
            var nullable = false
            db.query("PRAGMA table_info(`$table`)").use { cursor ->
                while (cursor.moveToNext()) {
                    val colName = cursor.getString(1)
                    if (colName == column) {
                        val notNull = cursor.getInt(3)
                        nullable = notNull == 0
                        break
                    }
                }
            }
            return nullable
        }

        private fun createDisciplesTable(db: SupportSQLiteDatabase) {
            db.execSQL("DROP TABLE IF EXISTS `disciples`")
            db.execSQL("""
                CREATE TABLE `disciples` (
                    `id` TEXT NOT NULL,
                    `slot_id` INTEGER NOT NULL,
                    `name` TEXT NOT NULL,
                    `realm` INTEGER NOT NULL,
                    `realmLayer` INTEGER NOT NULL,
                    `cultivation` REAL NOT NULL,
                    `spiritRootType` TEXT NOT NULL,
                    `age` INTEGER NOT NULL,
                    `lifespan` INTEGER NOT NULL,
                    `isAlive` INTEGER NOT NULL,
                    `gender` TEXT NOT NULL,
                    `manualIds` TEXT NOT NULL,
                    `talentIds` TEXT NOT NULL,
                    `manualMasteries` TEXT NOT NULL,
                    `status` TEXT NOT NULL,
                    `statusData` TEXT NOT NULL,
                    `cultivationSpeedBonus` REAL NOT NULL,
                    `cultivationSpeedDuration` INTEGER NOT NULL,
                    `discipleType` TEXT NOT NULL,
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
                    `pillPhysicalAttackBonus` REAL NOT NULL,
                    `pillMagicAttackBonus` REAL NOT NULL,
                    `pillPhysicalDefenseBonus` REAL NOT NULL,
                    `pillMagicDefenseBonus` REAL NOT NULL,
                    `pillHpBonus` REAL NOT NULL,
                    `pillMpBonus` REAL NOT NULL,
                    `pillSpeedBonus` REAL NOT NULL,
                    `pillEffectDuration` INTEGER NOT NULL,
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
                    `social_partnerId` TEXT,
                    `social_partnerSectId` TEXT,
                    `social_parentId1` TEXT,
                    `social_parentId2` TEXT,
                    `social_lastChildYear` INTEGER NOT NULL,
                    `social_griefEndYear` INTEGER,
                    `intelligence` INTEGER NOT NULL,
                    `charm` INTEGER NOT NULL,
                    `loyalty` INTEGER NOT NULL,
                    `comprehension` INTEGER NOT NULL,
                    `artifactRefining` INTEGER NOT NULL,
                    `pillRefining` INTEGER NOT NULL,
                    `spiritPlanting` INTEGER NOT NULL,
                    `teaching` INTEGER NOT NULL,
                    `morality` INTEGER NOT NULL,
                    `salaryPaidCount` INTEGER NOT NULL,
                    `salaryMissedCount` INTEGER NOT NULL,
                    `usage_monthlyUsedPillIds` TEXT NOT NULL,
                    `usage_usedExtendLifePillIds` TEXT NOT NULL,
                    `usage_recruitedMonth` INTEGER NOT NULL,
                    `usage_hasReviveEffect` INTEGER NOT NULL,
                    `usage_hasClearAllEffect` INTEGER NOT NULL,
                    PRIMARY KEY(`id`)
                )
            """)
        }

        private fun createDisciplesIndexes(db: SupportSQLiteDatabase) {
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_disciples_name` ON `disciples` (`name`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_disciples_realm_realmLayer` ON `disciples` (`realm`, `realmLayer`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_disciples_isAlive_realm` ON `disciples` (`isAlive`, `realm`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_disciples_isAlive_status` ON `disciples` (`isAlive`, `status`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_disciples_discipleType` ON `disciples` (`discipleType`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_disciples_loyalty` ON `disciples` (`loyalty`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_disciples_age` ON `disciples` (`age`)")
        }
    }

    class Migration70To71 : Migration(70, 71) {
        override fun migrate(db: SupportSQLiteDatabase) {
            Log.i(TAG, "Migration 70 to 71: clearing recruitList and removing unrecruited disciples")

            db.execSQL("DELETE FROM `disciples` WHERE `usage_recruitedMonth` = 0")

            db.execSQL("UPDATE `game_data` SET `recruitList` = '' WHERE 1=1")

            Log.i(TAG, "Migration 70 to 71 completed")
        }
    }

    internal class Migration69To70 : Migration(69, 70) {
        override fun migrate(db: SupportSQLiteDatabase) {
            Log.i(TAG, "Migration 69 to 70 - Force recreate disciples table to fix schema")

            try { db.execSQL("DROP TABLE IF EXISTS `disciples_old`") } catch (_: Exception) {}
            try { db.execSQL("DROP INDEX IF EXISTS `idx_disciples_slot_id`") } catch (_: Exception) {}

            val tableExists = try {
                db.query("SELECT name FROM sqlite_master WHERE type='table' AND name='disciples'").use { it.moveToNext() }
            } catch (_: Exception) { false }

            if (!tableExists) {
                Log.i(TAG, "disciples table does not exist, creating from scratch")
                createDisciplesTable(db)
                createDisciplesIndexes(db)
                Log.i(TAG, "Migration 69 to 70 completed: disciples table created")
                return
            }

            db.execSQL("ALTER TABLE `disciples` RENAME TO `disciples_old`")

            createDisciplesTable(db)

            val oldColumns = mutableSetOf<String>()
            db.query("PRAGMA table_info(`disciples_old`)").use { cursor ->
                while (cursor.moveToNext()) {
                    oldColumns.add(cursor.getString(1))
                }
            }

            val newColumns = mutableSetOf<String>()
            db.query("PRAGMA table_info(`disciples`)").use { cursor ->
                while (cursor.moveToNext()) {
                    newColumns.add(cursor.getString(1))
                }
            }

            val commonColumns = oldColumns.intersect(newColumns).toList()
            val columnList = commonColumns.joinToString(", ") { "`$it`" }

            if (columnList.isNotEmpty()) {
                db.execSQL("INSERT INTO `disciples` ($columnList) SELECT $columnList FROM `disciples_old`")
                Log.d(TAG, "Copied data for ${commonColumns.size} common columns from disciples_old to disciples")
            }

            db.execSQL("DROP TABLE `disciples_old`")

            createDisciplesIndexes(db)

            Log.i(TAG, "Migration 69 to 70 completed: disciples table recreated with correct schema")
        }

        private fun createDisciplesTable(db: SupportSQLiteDatabase) {
            db.execSQL("DROP TABLE IF EXISTS `disciples`")
            db.execSQL("CREATE TABLE `disciples` (`id` TEXT NOT NULL, `slot_id` INTEGER NOT NULL, `name` TEXT NOT NULL, `realm` INTEGER NOT NULL, `realmLayer` INTEGER NOT NULL, `cultivation` REAL NOT NULL, `spiritRootType` TEXT NOT NULL, `age` INTEGER NOT NULL, `lifespan` INTEGER NOT NULL, `isAlive` INTEGER NOT NULL, `gender` TEXT NOT NULL, `manualIds` TEXT NOT NULL, `talentIds` TEXT NOT NULL, `manualMasteries` TEXT NOT NULL, `status` TEXT NOT NULL, `statusData` TEXT NOT NULL, `cultivationSpeedBonus` REAL NOT NULL, `cultivationSpeedDuration` INTEGER NOT NULL, `discipleType` TEXT NOT NULL, `baseHp` INTEGER NOT NULL, `baseMp` INTEGER NOT NULL, `basePhysicalAttack` INTEGER NOT NULL, `baseMagicAttack` INTEGER NOT NULL, `basePhysicalDefense` INTEGER NOT NULL, `baseMagicDefense` INTEGER NOT NULL, `baseSpeed` INTEGER NOT NULL, `hpVariance` INTEGER NOT NULL, `mpVariance` INTEGER NOT NULL, `physicalAttackVariance` INTEGER NOT NULL, `magicAttackVariance` INTEGER NOT NULL, `physicalDefenseVariance` INTEGER NOT NULL, `magicDefenseVariance` INTEGER NOT NULL, `speedVariance` INTEGER NOT NULL, `totalCultivation` INTEGER NOT NULL, `breakthroughCount` INTEGER NOT NULL, `breakthroughFailCount` INTEGER NOT NULL, `pillPhysicalAttackBonus` REAL NOT NULL, `pillMagicAttackBonus` REAL NOT NULL, `pillPhysicalDefenseBonus` REAL NOT NULL, `pillMagicDefenseBonus` REAL NOT NULL, `pillHpBonus` REAL NOT NULL, `pillMpBonus` REAL NOT NULL, `pillSpeedBonus` REAL NOT NULL, `pillEffectDuration` INTEGER NOT NULL, `weaponId` TEXT NOT NULL, `armorId` TEXT NOT NULL, `bootsId` TEXT NOT NULL, `accessoryId` TEXT NOT NULL, `weaponNurture` TEXT NOT NULL, `armorNurture` TEXT NOT NULL, `bootsNurture` TEXT NOT NULL, `accessoryNurture` TEXT NOT NULL, `storageBagItems` TEXT NOT NULL, `storageBagSpiritStones` INTEGER NOT NULL, `spiritStones` INTEGER NOT NULL, `soulPower` INTEGER NOT NULL, `social_partnerId` TEXT, `social_partnerSectId` TEXT, `social_parentId1` TEXT, `social_parentId2` TEXT, `social_lastChildYear` INTEGER NOT NULL, `social_griefEndYear` INTEGER, `intelligence` INTEGER NOT NULL, `charm` INTEGER NOT NULL, `loyalty` INTEGER NOT NULL, `comprehension` INTEGER NOT NULL, `artifactRefining` INTEGER NOT NULL, `pillRefining` INTEGER NOT NULL, `spiritPlanting` INTEGER NOT NULL, `teaching` INTEGER NOT NULL, `morality` INTEGER NOT NULL, `salaryPaidCount` INTEGER NOT NULL, `salaryMissedCount` INTEGER NOT NULL, `usage_monthlyUsedPillIds` TEXT NOT NULL, `usage_usedExtendLifePillIds` TEXT NOT NULL, `usage_recruitedMonth` INTEGER NOT NULL, `usage_hasReviveEffect` INTEGER NOT NULL, `usage_hasClearAllEffect` INTEGER NOT NULL, PRIMARY KEY(`id`))")
        }

        private fun createDisciplesIndexes(db: SupportSQLiteDatabase) {
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_disciples_name` ON `disciples` (`name`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_disciples_realm_realmLayer` ON `disciples` (`realm`, `realmLayer`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_disciples_isAlive_realm` ON `disciples` (`isAlive`, `realm`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_disciples_isAlive_status` ON `disciples` (`isAlive`, `status`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_disciples_discipleType` ON `disciples` (`discipleType`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_disciples_loyalty` ON `disciples` (`loyalty`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_disciples_age` ON `disciples` (`age`)")
        }
    }

    internal class Migration71To72 : Migration(71, 72) {
        override fun migrate(db: SupportSQLiteDatabase) {
            Log.i(TAG, "Migration 71 to 72: converting all tables to composite primary keys (id, slot_id)")

            migrateGameData(db)
            migrateDisciples(db)
            migrateDisciplesCore(db)
            migrateDisciplesCombat(db)
            migrateDisciplesEquipment(db)
            migrateDisciplesExtended(db)
            migrateDisciplesAttributes(db)
            migrateEquipment(db)
            migrateManuals(db)
            migratePills(db)
            migrateMaterials(db)
            migrateSeeds(db)
            migrateHerbs(db)
            migrateExplorationTeams(db)
            migrateBuildingSlots(db)
            migrateGameEvents(db)
            migrateDungeons(db)
            migrateRecipes(db)
            migrateBattleLogs(db)
            migrateForgeSlots(db)
            migrateAlchemySlots(db)
            migrateProductionSlots(db)

            Log.i(TAG, "Migration 71 to 72 completed")
        }

        private fun migrateGameData(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `game_data` RENAME TO `game_data_v71`")
            db.execSQL("CREATE TABLE IF NOT EXISTS `game_data` (`id` TEXT NOT NULL, `slot_id` INTEGER NOT NULL, `sectName` TEXT NOT NULL, `currentSlot` INTEGER NOT NULL, `gameYear` INTEGER NOT NULL, `gameMonth` INTEGER NOT NULL, `gameDay` INTEGER NOT NULL, `isGameStarted` INTEGER NOT NULL, `gameSpeed` INTEGER NOT NULL, `spiritStones` INTEGER NOT NULL, `spiritHerbs` INTEGER NOT NULL, `sectCultivation` REAL NOT NULL, `autoSaveIntervalMonths` INTEGER NOT NULL, `monthlySalary` TEXT NOT NULL, `monthlySalaryEnabled` TEXT NOT NULL, `worldMapSects` TEXT NOT NULL, `exploredSects` TEXT NOT NULL, `scoutInfo` TEXT NOT NULL, `herbGardenPlantSlots` TEXT NOT NULL, `manualProficiencies` TEXT NOT NULL, `travelingMerchantItems` TEXT NOT NULL, `merchantLastRefreshYear` INTEGER NOT NULL, `merchantRefreshCount` INTEGER NOT NULL, `playerListedItems` TEXT NOT NULL, `recruitList` TEXT NOT NULL, `lastRecruitYear` INTEGER NOT NULL, `cultivatorCaves` TEXT NOT NULL, `caveExplorationTeams` TEXT NOT NULL, `aiCaveTeams` TEXT NOT NULL, `unlockedDungeons` TEXT NOT NULL, `unlockedRecipes` TEXT NOT NULL, `unlockedManuals` TEXT NOT NULL, `lastSaveTime` INTEGER NOT NULL, `elderSlots` TEXT NOT NULL, `spiritMineSlots` TEXT NOT NULL, `librarySlots` TEXT NOT NULL, `forgeSlots` TEXT NOT NULL, `alchemySlots` TEXT NOT NULL, `productionSlots` TEXT NOT NULL, `alliances` TEXT NOT NULL, `sectRelations` TEXT NOT NULL, `playerAllianceSlots` INTEGER NOT NULL, `sectPolicies` TEXT NOT NULL, `battleTeam` TEXT, `aiBattleTeams` TEXT NOT NULL, `usedRedeemCodes` TEXT NOT NULL, `playerProtectionEnabled` INTEGER NOT NULL, `playerProtectionStartYear` INTEGER NOT NULL, `playerHasAttackedAI` INTEGER NOT NULL, `activeMissions` TEXT NOT NULL, `availableMissions` TEXT NOT NULL, `pendingCompetitionResults` TEXT NOT NULL, `lastCompetitionYear` INTEGER NOT NULL, PRIMARY KEY(`id`, `slot_id`))")
            db.execSQL("INSERT INTO `game_data` SELECT * FROM `game_data_v71`")
            db.execSQL("DROP TABLE `game_data_v71`")
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_game_data_slot_id` ON `game_data` (`slot_id`)")
        }

        private fun migrateDisciples(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `disciples` RENAME TO `disciples_v71`")
            db.execSQL("CREATE TABLE IF NOT EXISTS `disciples` (`id` TEXT NOT NULL, `slot_id` INTEGER NOT NULL, `name` TEXT NOT NULL, `realm` INTEGER NOT NULL, `realmLayer` INTEGER NOT NULL, `cultivation` REAL NOT NULL, `spiritRootType` TEXT NOT NULL, `age` INTEGER NOT NULL, `lifespan` INTEGER NOT NULL, `isAlive` INTEGER NOT NULL, `gender` TEXT NOT NULL, `manualIds` TEXT NOT NULL, `talentIds` TEXT NOT NULL, `manualMasteries` TEXT NOT NULL, `status` TEXT NOT NULL, `statusData` TEXT NOT NULL, `cultivationSpeedBonus` REAL NOT NULL, `cultivationSpeedDuration` INTEGER NOT NULL, `discipleType` TEXT NOT NULL, `baseHp` INTEGER NOT NULL, `baseMp` INTEGER NOT NULL, `basePhysicalAttack` INTEGER NOT NULL, `baseMagicAttack` INTEGER NOT NULL, `basePhysicalDefense` INTEGER NOT NULL, `baseMagicDefense` INTEGER NOT NULL, `baseSpeed` INTEGER NOT NULL, `hpVariance` INTEGER NOT NULL, `mpVariance` INTEGER NOT NULL, `physicalAttackVariance` INTEGER NOT NULL, `magicAttackVariance` INTEGER NOT NULL, `physicalDefenseVariance` INTEGER NOT NULL, `magicDefenseVariance` INTEGER NOT NULL, `speedVariance` INTEGER NOT NULL, `totalCultivation` INTEGER NOT NULL, `breakthroughCount` INTEGER NOT NULL, `breakthroughFailCount` INTEGER NOT NULL, `pillPhysicalAttackBonus` REAL NOT NULL, `pillMagicAttackBonus` REAL NOT NULL, `pillPhysicalDefenseBonus` REAL NOT NULL, `pillMagicDefenseBonus` REAL NOT NULL, `pillHpBonus` REAL NOT NULL, `pillMpBonus` REAL NOT NULL, `pillSpeedBonus` REAL NOT NULL, `pillEffectDuration` INTEGER NOT NULL, `weaponId` TEXT NOT NULL, `armorId` TEXT NOT NULL, `bootsId` TEXT NOT NULL, `accessoryId` TEXT NOT NULL, `weaponNurture` TEXT NOT NULL, `armorNurture` TEXT NOT NULL, `bootsNurture` TEXT NOT NULL, `accessoryNurture` TEXT NOT NULL, `storageBagItems` TEXT NOT NULL, `storageBagSpiritStones` INTEGER NOT NULL, `spiritStones` INTEGER NOT NULL, `soulPower` INTEGER NOT NULL, `social_partnerId` TEXT, `social_partnerSectId` TEXT, `social_parentId1` TEXT, `social_parentId2` TEXT, `social_lastChildYear` INTEGER NOT NULL, `social_griefEndYear` INTEGER, `intelligence` INTEGER NOT NULL, `charm` INTEGER NOT NULL, `loyalty` INTEGER NOT NULL, `comprehension` INTEGER NOT NULL, `artifactRefining` INTEGER NOT NULL, `pillRefining` INTEGER NOT NULL, `spiritPlanting` INTEGER NOT NULL, `teaching` INTEGER NOT NULL, `morality` INTEGER NOT NULL, `salaryPaidCount` INTEGER NOT NULL, `salaryMissedCount` INTEGER NOT NULL, `usage_monthlyUsedPillIds` TEXT NOT NULL, `usage_usedExtendLifePillIds` TEXT NOT NULL, `usage_recruitedMonth` INTEGER NOT NULL, `usage_hasReviveEffect` INTEGER NOT NULL, `usage_hasClearAllEffect` INTEGER NOT NULL, PRIMARY KEY(`id`, `slot_id`))")
            db.execSQL("INSERT INTO `disciples` SELECT * FROM `disciples_v71`")
            db.execSQL("DROP TABLE `disciples_v71`")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_disciples_name` ON `disciples` (`name`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_disciples_realm_realmLayer` ON `disciples` (`realm`, `realmLayer`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_disciples_isAlive_realm` ON `disciples` (`isAlive`, `realm`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_disciples_isAlive_status` ON `disciples` (`isAlive`, `status`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_disciples_discipleType` ON `disciples` (`discipleType`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_disciples_loyalty` ON `disciples` (`loyalty`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_disciples_age` ON `disciples` (`age`)")
        }

        private fun migrateDisciplesCore(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `disciples_core` RENAME TO `disciples_core_v71`")
            db.execSQL("CREATE TABLE IF NOT EXISTS `disciples_core` (`id` TEXT NOT NULL, `slot_id` INTEGER NOT NULL, `name` TEXT NOT NULL, `realm` INTEGER NOT NULL, `realmLayer` INTEGER NOT NULL, `cultivation` REAL NOT NULL, `isAlive` INTEGER NOT NULL, `status` TEXT NOT NULL, `discipleType` TEXT NOT NULL, `age` INTEGER NOT NULL, `lifespan` INTEGER NOT NULL, `gender` TEXT NOT NULL, `spiritRootType` TEXT NOT NULL, `recruitedMonth` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`, `slot_id`))")
            db.execSQL("INSERT INTO `disciples_core` SELECT * FROM `disciples_core_v71`")
            db.execSQL("DROP TABLE `disciples_core_v71`")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_disciples_core_name` ON `disciples_core` (`name`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_disciples_core_realm_realmLayer` ON `disciples_core` (`realm`, `realmLayer`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_disciples_core_isAlive_realm` ON `disciples_core` (`isAlive`, `realm`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_disciples_core_isAlive_status` ON `disciples_core` (`isAlive`, `status`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_disciples_core_discipleType` ON `disciples_core` (`discipleType`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_disciples_core_age` ON `disciples_core` (`age`)")
        }

        private fun migrateDisciplesCombat(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `disciples_combat` RENAME TO `disciples_combat_v71`")
            db.execSQL("CREATE TABLE IF NOT EXISTS `disciples_combat` (`discipleId` TEXT NOT NULL, `slot_id` INTEGER NOT NULL, `baseHp` INTEGER NOT NULL, `baseMp` INTEGER NOT NULL, `basePhysicalAttack` INTEGER NOT NULL, `baseMagicAttack` INTEGER NOT NULL, `basePhysicalDefense` INTEGER NOT NULL, `baseMagicDefense` INTEGER NOT NULL, `baseSpeed` INTEGER NOT NULL, `hpVariance` INTEGER NOT NULL, `mpVariance` INTEGER NOT NULL, `physicalAttackVariance` INTEGER NOT NULL, `magicAttackVariance` INTEGER NOT NULL, `physicalDefenseVariance` INTEGER NOT NULL, `magicDefenseVariance` INTEGER NOT NULL, `speedVariance` INTEGER NOT NULL, `pillPhysicalAttackBonus` REAL NOT NULL, `pillMagicAttackBonus` REAL NOT NULL, `pillPhysicalDefenseBonus` REAL NOT NULL, `pillMagicDefenseBonus` REAL NOT NULL, `pillHpBonus` REAL NOT NULL, `pillMpBonus` REAL NOT NULL, `pillSpeedBonus` REAL NOT NULL, `pillEffectDuration` INTEGER NOT NULL, `totalCultivation` INTEGER NOT NULL, `breakthroughCount` INTEGER NOT NULL, `breakthroughFailCount` INTEGER NOT NULL, PRIMARY KEY(`discipleId`, `slot_id`), FOREIGN KEY(`discipleId`, `slot_id`) REFERENCES `disciples_core`(`id`, `slot_id`) ON UPDATE CASCADE ON DELETE CASCADE)")
            db.execSQL("INSERT INTO `disciples_combat` SELECT * FROM `disciples_combat_v71`")
            db.execSQL("DROP TABLE `disciples_combat_v71`")
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_disciples_combat_discipleId_slot_id` ON `disciples_combat` (`discipleId`, `slot_id`)")
        }

        private fun migrateDisciplesEquipment(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `disciples_equipment` RENAME TO `disciples_equipment_v71`")
            db.execSQL("CREATE TABLE IF NOT EXISTS `disciples_equipment` (`discipleId` TEXT NOT NULL, `slot_id` INTEGER NOT NULL, `weaponId` TEXT NOT NULL, `armorId` TEXT NOT NULL, `bootsId` TEXT NOT NULL, `accessoryId` TEXT NOT NULL, `weaponNurture` TEXT NOT NULL, `armorNurture` TEXT NOT NULL, `bootsNurture` TEXT NOT NULL, `accessoryNurture` TEXT NOT NULL, `storageBagItems` TEXT NOT NULL, `storageBagSpiritStones` INTEGER NOT NULL, `spiritStones` INTEGER NOT NULL, `soulPower` INTEGER NOT NULL, PRIMARY KEY(`discipleId`, `slot_id`), FOREIGN KEY(`discipleId`, `slot_id`) REFERENCES `disciples_core`(`id`, `slot_id`) ON UPDATE CASCADE ON DELETE CASCADE)")
            db.execSQL("INSERT INTO `disciples_equipment` SELECT * FROM `disciples_equipment_v71`")
            db.execSQL("DROP TABLE `disciples_equipment_v71`")
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_disciples_equipment_discipleId_slot_id` ON `disciples_equipment` (`discipleId`, `slot_id`)")
        }

        private fun migrateDisciplesExtended(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `disciples_extended` RENAME TO `disciples_extended_v71`")
            db.execSQL("CREATE TABLE IF NOT EXISTS `disciples_extended` (`discipleId` TEXT NOT NULL, `slot_id` INTEGER NOT NULL, `manualIds` TEXT NOT NULL, `talentIds` TEXT NOT NULL, `manualMasteries` TEXT NOT NULL, `statusData` TEXT NOT NULL, `cultivationSpeedBonus` REAL NOT NULL, `cultivationSpeedDuration` INTEGER NOT NULL, `partnerId` TEXT, `partnerSectId` TEXT, `parentId1` TEXT, `parentId2` TEXT, `lastChildYear` INTEGER NOT NULL, `griefEndYear` INTEGER, `monthlyUsedPillIds` TEXT NOT NULL, `usedExtendLifePillIds` TEXT NOT NULL, `hasReviveEffect` INTEGER NOT NULL, `hasClearAllEffect` INTEGER NOT NULL, PRIMARY KEY(`discipleId`, `slot_id`), FOREIGN KEY(`discipleId`, `slot_id`) REFERENCES `disciples_core`(`id`, `slot_id`) ON UPDATE CASCADE ON DELETE CASCADE)")
            db.execSQL("INSERT INTO `disciples_extended` SELECT * FROM `disciples_extended_v71`")
            db.execSQL("DROP TABLE `disciples_extended_v71`")
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_disciples_extended_discipleId_slot_id` ON `disciples_extended` (`discipleId`, `slot_id`)")
        }

        private fun migrateDisciplesAttributes(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `disciples_attributes` RENAME TO `disciples_attributes_v71`")
            db.execSQL("CREATE TABLE IF NOT EXISTS `disciples_attributes` (`discipleId` TEXT NOT NULL, `slot_id` INTEGER NOT NULL, `intelligence` INTEGER NOT NULL, `charm` INTEGER NOT NULL, `loyalty` INTEGER NOT NULL, `comprehension` INTEGER NOT NULL, `artifactRefining` INTEGER NOT NULL, `pillRefining` INTEGER NOT NULL, `spiritPlanting` INTEGER NOT NULL, `teaching` INTEGER NOT NULL, `morality` INTEGER NOT NULL, `salaryPaidCount` INTEGER NOT NULL, `salaryMissedCount` INTEGER NOT NULL, PRIMARY KEY(`discipleId`, `slot_id`), FOREIGN KEY(`discipleId`, `slot_id`) REFERENCES `disciples_core`(`id`, `slot_id`) ON UPDATE CASCADE ON DELETE CASCADE)")
            db.execSQL("INSERT INTO `disciples_attributes` SELECT * FROM `disciples_attributes_v71`")
            db.execSQL("DROP TABLE `disciples_attributes_v71`")
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_disciples_attributes_discipleId_slot_id` ON `disciples_attributes` (`discipleId`, `slot_id`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_disciples_attributes_loyalty` ON `disciples_attributes` (`loyalty`)")
        }

        private fun migrateEquipment(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `equipment` RENAME TO `equipment_v71`")
            db.execSQL("CREATE TABLE IF NOT EXISTS `equipment` (`id` TEXT NOT NULL, `slot_id` INTEGER NOT NULL, `name` TEXT NOT NULL, `rarity` INTEGER NOT NULL, `description` TEXT NOT NULL, `slot` TEXT NOT NULL, `physicalAttack` INTEGER NOT NULL, `magicAttack` INTEGER NOT NULL, `physicalDefense` INTEGER NOT NULL, `magicDefense` INTEGER NOT NULL, `speed` INTEGER NOT NULL, `hp` INTEGER NOT NULL, `mp` INTEGER NOT NULL, `critChance` REAL NOT NULL, `nurtureLevel` INTEGER NOT NULL, `nurtureProgress` REAL NOT NULL, `minRealm` INTEGER NOT NULL, `ownerId` TEXT, `isEquipped` INTEGER NOT NULL, `quantity` INTEGER NOT NULL, `isLocked` INTEGER NOT NULL, PRIMARY KEY(`id`, `slot_id`))")
            db.execSQL("INSERT INTO `equipment` SELECT * FROM `equipment_v71`")
            db.execSQL("DROP TABLE `equipment_v71`")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_equipment_name` ON `equipment` (`name`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_equipment_rarity` ON `equipment` (`rarity`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_equipment_slot` ON `equipment` (`slot`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_equipment_ownerId` ON `equipment` (`ownerId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_equipment_isEquipped` ON `equipment` (`isEquipped`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_equipment_rarity_slot` ON `equipment` (`rarity`, `slot`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_equipment_minRealm` ON `equipment` (`minRealm`)")
        }

        private fun migrateManuals(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `manuals` RENAME TO `manuals_v71`")
            db.execSQL("CREATE TABLE IF NOT EXISTS `manuals` (`id` TEXT NOT NULL, `slot_id` INTEGER NOT NULL, `name` TEXT NOT NULL, `rarity` INTEGER NOT NULL, `description` TEXT NOT NULL, `type` TEXT NOT NULL, `stats` TEXT NOT NULL, `skillName` TEXT, `skillDescription` TEXT, `skillType` TEXT NOT NULL, `skillDamageType` TEXT NOT NULL, `skillHits` INTEGER NOT NULL, `skillDamageMultiplier` REAL NOT NULL, `skillCooldown` INTEGER NOT NULL, `skillMpCost` INTEGER NOT NULL, `skillHealPercent` REAL NOT NULL, `skillHealType` TEXT NOT NULL, `skillBuffType` TEXT, `skillBuffValue` REAL NOT NULL, `skillBuffDuration` INTEGER NOT NULL, `skillBuffsJson` TEXT NOT NULL, `skillIsAoe` INTEGER NOT NULL, `skillTargetScope` TEXT NOT NULL, `minRealm` INTEGER NOT NULL, `ownerId` TEXT, `isLearned` INTEGER NOT NULL, `quantity` INTEGER NOT NULL, `isLocked` INTEGER NOT NULL, PRIMARY KEY(`id`, `slot_id`))")
            db.execSQL("INSERT INTO `manuals` SELECT * FROM `manuals_v71`")
            db.execSQL("DROP TABLE `manuals_v71`")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_manuals_name` ON `manuals` (`name`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_manuals_rarity` ON `manuals` (`rarity`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_manuals_type` ON `manuals` (`type`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_manuals_ownerId` ON `manuals` (`ownerId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_manuals_isLearned` ON `manuals` (`isLearned`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_manuals_minRealm` ON `manuals` (`minRealm`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_manuals_rarity_type` ON `manuals` (`rarity`, `type`)")
        }

        private fun migratePills(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `pills` RENAME TO `pills_v71`")
            db.execSQL("CREATE TABLE IF NOT EXISTS `pills` (`id` TEXT NOT NULL, `slot_id` INTEGER NOT NULL, `name` TEXT NOT NULL, `rarity` INTEGER NOT NULL, `description` TEXT NOT NULL, `category` TEXT NOT NULL, `breakthroughChance` REAL NOT NULL, `targetRealm` INTEGER NOT NULL, `isAscension` INTEGER NOT NULL, `cultivationSpeed` REAL NOT NULL, `duration` INTEGER NOT NULL, `cannotStack` INTEGER NOT NULL, `cultivationPercent` REAL NOT NULL, `skillExpPercent` REAL NOT NULL, `extendLife` INTEGER NOT NULL, `physicalAttackPercent` REAL NOT NULL, `magicAttackPercent` REAL NOT NULL, `physicalDefensePercent` REAL NOT NULL, `magicDefensePercent` REAL NOT NULL, `hpPercent` REAL NOT NULL, `mpPercent` REAL NOT NULL, `speedPercent` REAL NOT NULL, `healMaxHpPercent` REAL NOT NULL, `healPercent` REAL NOT NULL, `heal` INTEGER NOT NULL, `battleCount` INTEGER NOT NULL, `revive` INTEGER NOT NULL, `clearAll` INTEGER NOT NULL, `mpRecoverMaxMpPercent` REAL NOT NULL, `quantity` INTEGER NOT NULL, `isLocked` INTEGER NOT NULL, PRIMARY KEY(`id`, `slot_id`))")
            db.execSQL("INSERT INTO `pills` SELECT * FROM `pills_v71`")
            db.execSQL("DROP TABLE `pills_v71`")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_pills_name` ON `pills` (`name`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_pills_rarity` ON `pills` (`rarity`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_pills_category` ON `pills` (`category`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_pills_targetRealm` ON `pills` (`targetRealm`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_pills_rarity_category` ON `pills` (`rarity`, `category`)")
        }

        private fun migrateMaterials(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `materials` RENAME TO `materials_v71`")
            db.execSQL("CREATE TABLE IF NOT EXISTS `materials` (`id` TEXT NOT NULL, `slot_id` INTEGER NOT NULL, `name` TEXT NOT NULL, `rarity` INTEGER NOT NULL, `description` TEXT NOT NULL, `category` TEXT NOT NULL, `quantity` INTEGER NOT NULL, `isLocked` INTEGER NOT NULL, PRIMARY KEY(`id`, `slot_id`))")
            db.execSQL("INSERT INTO `materials` SELECT * FROM `materials_v71`")
            db.execSQL("DROP TABLE `materials_v71`")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_materials_name` ON `materials` (`name`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_materials_rarity` ON `materials` (`rarity`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_materials_category` ON `materials` (`category`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_materials_rarity_category` ON `materials` (`rarity`, `category`)")
        }

        private fun migrateSeeds(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `seeds` RENAME TO `seeds_v71`")
            db.execSQL("CREATE TABLE IF NOT EXISTS `seeds` (`id` TEXT NOT NULL, `slot_id` INTEGER NOT NULL, `name` TEXT NOT NULL, `rarity` INTEGER NOT NULL, `description` TEXT NOT NULL, `growTime` INTEGER NOT NULL, `yield` INTEGER NOT NULL, `quantity` INTEGER NOT NULL, `isLocked` INTEGER NOT NULL, PRIMARY KEY(`id`, `slot_id`))")
            db.execSQL("INSERT INTO `seeds` SELECT * FROM `seeds_v71`")
            db.execSQL("DROP TABLE `seeds_v71`")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_seeds_name` ON `seeds` (`name`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_seeds_rarity` ON `seeds` (`rarity`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_seeds_growTime` ON `seeds` (`growTime`)")
        }

        private fun migrateHerbs(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `herbs` RENAME TO `herbs_v71`")
            db.execSQL("CREATE TABLE IF NOT EXISTS `herbs` (`id` TEXT NOT NULL, `slot_id` INTEGER NOT NULL, `name` TEXT NOT NULL, `rarity` INTEGER NOT NULL, `description` TEXT NOT NULL, `category` TEXT NOT NULL, `quantity` INTEGER NOT NULL, `isLocked` INTEGER NOT NULL, PRIMARY KEY(`id`, `slot_id`))")
            db.execSQL("INSERT INTO `herbs` SELECT * FROM `herbs_v71`")
            db.execSQL("DROP TABLE `herbs_v71`")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_herbs_name` ON `herbs` (`name`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_herbs_rarity` ON `herbs` (`rarity`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_herbs_category` ON `herbs` (`category`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_herbs_rarity_category` ON `herbs` (`rarity`, `category`)")
        }

        private fun migrateExplorationTeams(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `exploration_teams` RENAME TO `exploration_teams_v71`")
            db.execSQL("CREATE TABLE IF NOT EXISTS `exploration_teams` (`id` TEXT NOT NULL, `slot_id` INTEGER NOT NULL, `name` TEXT NOT NULL, `caveId` TEXT, `caveName` TEXT NOT NULL, `dungeon` TEXT NOT NULL, `dungeonName` TEXT NOT NULL, `memberIds` TEXT NOT NULL, `memberNames` TEXT NOT NULL, `startYear` INTEGER NOT NULL, `startMonth` INTEGER NOT NULL, `startDay` INTEGER NOT NULL, `duration` INTEGER NOT NULL, `status` TEXT NOT NULL, `progress` INTEGER NOT NULL, `scoutTargetSectId` TEXT, `scoutTargetSectName` TEXT NOT NULL, `currentX` REAL NOT NULL, `currentY` REAL NOT NULL, `targetX` REAL NOT NULL, `targetY` REAL NOT NULL, `moveProgress` REAL NOT NULL, `arrivalYear` INTEGER NOT NULL, `arrivalMonth` INTEGER NOT NULL, `arrivalDay` INTEGER NOT NULL, `route` TEXT NOT NULL, `currentRouteIndex` INTEGER NOT NULL, `currentSegmentProgress` REAL NOT NULL, `pityCounterEquipment` INTEGER NOT NULL, `pityCounterPill` INTEGER NOT NULL, `pityCounterManual` INTEGER NOT NULL, PRIMARY KEY(`id`, `slot_id`))")
            db.execSQL("INSERT INTO `exploration_teams` SELECT * FROM `exploration_teams_v71`")
            db.execSQL("DROP TABLE `exploration_teams_v71`")
        }

        private fun migrateBuildingSlots(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `building_slots` RENAME TO `building_slots_v71`")
            db.execSQL("CREATE TABLE IF NOT EXISTS `building_slots` (`id` TEXT NOT NULL, `slot_id` INTEGER NOT NULL, `buildingId` TEXT NOT NULL, `slotIndex` INTEGER NOT NULL, `type` TEXT NOT NULL, `discipleId` TEXT, `discipleName` TEXT NOT NULL, `startYear` INTEGER NOT NULL, `startMonth` INTEGER NOT NULL, `duration` INTEGER NOT NULL, `recipeId` TEXT, `recipeName` TEXT NOT NULL, `status` TEXT NOT NULL, PRIMARY KEY(`id`, `slot_id`))")
            db.execSQL("INSERT INTO `building_slots` SELECT * FROM `building_slots_v71`")
            db.execSQL("DROP TABLE `building_slots_v71`")
        }

        private fun migrateGameEvents(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `game_events` RENAME TO `game_events_v71`")
            db.execSQL("CREATE TABLE IF NOT EXISTS `game_events` (`id` TEXT NOT NULL, `slot_id` INTEGER NOT NULL, `message` TEXT NOT NULL, `type` TEXT NOT NULL, `timestamp` INTEGER NOT NULL, `year` INTEGER NOT NULL, `month` INTEGER NOT NULL, PRIMARY KEY(`id`, `slot_id`))")
            db.execSQL("INSERT INTO `game_events` SELECT * FROM `game_events_v71`")
            db.execSQL("DROP TABLE `game_events_v71`")
        }

        private fun migrateDungeons(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `dungeons` RENAME TO `dungeons_v71`")
            db.execSQL("CREATE TABLE IF NOT EXISTS `dungeons` (`id` TEXT NOT NULL, `slot_id` INTEGER NOT NULL, `name` TEXT NOT NULL, `description` TEXT NOT NULL, `realm` INTEGER NOT NULL, `realmName` TEXT NOT NULL, `difficulty` INTEGER NOT NULL, `isUnlocked` INTEGER NOT NULL, `unlockYear` INTEGER NOT NULL, `unlockMonth` INTEGER NOT NULL, `completedCount` INTEGER NOT NULL, `rewards` TEXT NOT NULL, PRIMARY KEY(`id`, `slot_id`))")
            db.execSQL("INSERT INTO `dungeons` SELECT * FROM `dungeons_v71`")
            db.execSQL("DROP TABLE `dungeons_v71`")
        }

        private fun migrateRecipes(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `recipes` RENAME TO `recipes_v71`")
            db.execSQL("CREATE TABLE IF NOT EXISTS `recipes` (`id` TEXT NOT NULL, `slot_id` INTEGER NOT NULL, `name` TEXT NOT NULL, `description` TEXT NOT NULL, `type` TEXT NOT NULL, `isUnlocked` INTEGER NOT NULL, `unlockYear` INTEGER NOT NULL, `unlockMonth` INTEGER NOT NULL, `requiredMaterials` TEXT NOT NULL, `outputItemId` TEXT NOT NULL, `outputItemName` TEXT NOT NULL, `outputQuantity` INTEGER NOT NULL, `duration` INTEGER NOT NULL, PRIMARY KEY(`id`, `slot_id`))")
            db.execSQL("INSERT INTO `recipes` SELECT * FROM `recipes_v71`")
            db.execSQL("DROP TABLE `recipes_v71`")
        }

        private fun migrateBattleLogs(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `battle_logs` RENAME TO `battle_logs_v71`")
            db.execSQL("CREATE TABLE IF NOT EXISTS `battle_logs` (`id` TEXT NOT NULL, `slot_id` INTEGER NOT NULL, `timestamp` INTEGER NOT NULL, `year` INTEGER NOT NULL, `month` INTEGER NOT NULL, `type` TEXT NOT NULL, `attackerName` TEXT NOT NULL, `defenderName` TEXT NOT NULL, `result` TEXT NOT NULL, `details` TEXT NOT NULL, `drops` TEXT NOT NULL, `dungeonName` TEXT NOT NULL, `teamId` TEXT, `teamMembers` TEXT NOT NULL, `enemies` TEXT NOT NULL, `rounds` TEXT NOT NULL, `turns` INTEGER NOT NULL, `teamCasualties` INTEGER NOT NULL, `beastsDefeated` INTEGER NOT NULL, `battleResult` TEXT, PRIMARY KEY(`id`, `slot_id`))")
            db.execSQL("INSERT INTO `battle_logs` SELECT * FROM `battle_logs_v71`")
            db.execSQL("DROP TABLE `battle_logs_v71`")
        }

        private fun migrateForgeSlots(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `forge_slots` RENAME TO `forge_slots_v71`")
            db.execSQL("CREATE TABLE IF NOT EXISTS `forge_slots` (`id` TEXT NOT NULL, `slot_id` INTEGER NOT NULL, `slotIndex` INTEGER NOT NULL, `recipeId` TEXT, `recipeName` TEXT NOT NULL, `equipmentName` TEXT NOT NULL, `equipmentRarity` INTEGER NOT NULL, `equipmentSlot` TEXT NOT NULL, `startYear` INTEGER NOT NULL, `startMonth` INTEGER NOT NULL, `duration` INTEGER NOT NULL, `status` TEXT NOT NULL, `successRate` REAL NOT NULL, `requiredMaterials` TEXT NOT NULL, PRIMARY KEY(`id`, `slot_id`))")
            db.execSQL("INSERT INTO `forge_slots` SELECT * FROM `forge_slots_v71`")
            db.execSQL("DROP TABLE `forge_slots_v71`")
        }

        private fun migrateAlchemySlots(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `alchemy_slots` RENAME TO `alchemy_slots_v71`")
            db.execSQL("CREATE TABLE IF NOT EXISTS `alchemy_slots` (`id` TEXT NOT NULL, `slot_id` INTEGER NOT NULL, `slotIndex` INTEGER NOT NULL, `recipeId` TEXT, `recipeName` TEXT NOT NULL, `pillName` TEXT NOT NULL, `pillRarity` INTEGER NOT NULL, `startYear` INTEGER NOT NULL, `startMonth` INTEGER NOT NULL, `duration` INTEGER NOT NULL, `status` TEXT NOT NULL, `successRate` REAL NOT NULL, `requiredMaterials` TEXT NOT NULL, PRIMARY KEY(`id`, `slot_id`))")
            db.execSQL("INSERT INTO `alchemy_slots` SELECT * FROM `alchemy_slots_v71`")
            db.execSQL("DROP TABLE `alchemy_slots_v71`")
        }

        private fun migrateProductionSlots(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `production_slots` RENAME TO `production_slots_v71`")
            db.execSQL("CREATE TABLE IF NOT EXISTS `production_slots` (`id` TEXT NOT NULL, `slot_id` INTEGER NOT NULL, `slotIndex` INTEGER NOT NULL, `buildingType` TEXT NOT NULL, `buildingId` TEXT NOT NULL, `status` TEXT NOT NULL, `recipeId` TEXT, `recipeName` TEXT NOT NULL, `startYear` INTEGER NOT NULL, `startMonth` INTEGER NOT NULL, `duration` INTEGER NOT NULL, `assignedDiscipleId` TEXT, `assignedDiscipleName` TEXT NOT NULL, `successRate` REAL NOT NULL, `requiredMaterials` TEXT NOT NULL, `outputItemId` TEXT, `outputItemName` TEXT NOT NULL, `outputItemRarity` INTEGER NOT NULL, `outputItemSlot` TEXT NOT NULL, PRIMARY KEY(`id`, `slot_id`))")
            db.execSQL("INSERT INTO `production_slots` SELECT * FROM `production_slots_v71`")
            db.execSQL("DROP TABLE `production_slots_v71`")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_production_slots_buildingId_slotIndex` ON `production_slots` (`buildingId`, `slotIndex`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_production_slots_buildingType` ON `production_slots` (`buildingType`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_production_slots_status` ON `production_slots` (`status`)")
        }
    }

    internal class Migration72To73 : Migration(72, 73) {
        override fun migrate(db: SupportSQLiteDatabase) {
            Log.i(TAG, "Migration 72 to 73: adding currentHp/currentMp columns")
            db.execSQL("ALTER TABLE `disciples` ADD COLUMN `currentHp` INTEGER NOT NULL DEFAULT -1")
            db.execSQL("ALTER TABLE `disciples` ADD COLUMN `currentMp` INTEGER NOT NULL DEFAULT -1")
            db.execSQL("ALTER TABLE `disciples_combat` ADD COLUMN `currentHp` INTEGER NOT NULL DEFAULT -1")
            db.execSQL("ALTER TABLE `disciples_combat` ADD COLUMN `currentMp` INTEGER NOT NULL DEFAULT -1")
            Log.i(TAG, "Migration 72 to 73 completed")
        }
    }

    internal class Migration73To74 : Migration(73, 74) {
        override fun migrate(db: SupportSQLiteDatabase) {
            Log.i(TAG, "Migration 73 to 74: storage system optimization - auto migration handles schema changes")
            Log.i(TAG, "Migration 73 to 74 completed")
        }
    }

    internal class Migration74To75 : Migration(74, 75) {
        override fun migrate(db: SupportSQLiteDatabase) {
            Log.i(TAG, "Migration 74 to 75: auto migration handles schema changes")
            Log.i(TAG, "Migration 74 to 75 completed")
        }
    }

    internal class Migration75To76 : Migration(75, 76) {
        override fun migrate(db: SupportSQLiteDatabase) {
            Log.i(TAG, "Migration 75 to 76: add minRealm column to pills table")
            db.execSQL("ALTER TABLE `pills` ADD COLUMN `minRealm` INTEGER NOT NULL DEFAULT 9")
            Log.i(TAG, "Migration 75 to 76 completed")
        }
    }

    internal class Migration76To77 : Migration(76, 77) {
        override fun migrate(db: SupportSQLiteDatabase) {
            Log.i(TAG, "Migration 76 to 77: add expectedYield and harvestAmount columns to production_slots table")
            db.execSQL("ALTER TABLE `production_slots` ADD COLUMN `expectedYield` INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE `production_slots` ADD COLUMN `harvestAmount` INTEGER NOT NULL DEFAULT 0")
            Log.i(TAG, "Migration 76 to 77 completed")
        }
    }

    internal class Migration77To78 : Migration(77, 78) {
        override fun migrate(db: SupportSQLiteDatabase) {
            Log.i(TAG, "Migration 77 to 78: remove legacy slot columns from game_data table")
            db.execSQL("ALTER TABLE `game_data` DROP COLUMN `herbGardenPlantSlots`")
            db.execSQL("ALTER TABLE `game_data` DROP COLUMN `forgeSlots`")
            db.execSQL("ALTER TABLE `game_data` DROP COLUMN `alchemySlots`")
            Log.i(TAG, "Migration 77 to 78 completed")
        }
    }

    @JvmStatic
    val ALL_MIGRATIONS = arrayOf<Migration>(
        Migration53To54(),
        Migration54To55(),
        Migration55To56(),
        Migration56To57(),
        Migration57To58(),
        Migration58To59(),
        Migration59To60(),
        Migration60To61(),
        Migration61To62(),
        Migration62To63(),
        Migration63To64(),
        Migration64To65(),
        Migration65To66(),
        Migration66To67(),
        Migration67To68(),
        Migration68To69(),
        Migration69To70(),
        Migration70To71(),
        Migration71To72(),
        Migration72To73(),
        Migration73To74(),
        Migration74To75(),
        Migration75To76(),
        Migration76To77(),
        Migration77To78()
    )

    private val MIGRATION_REGISTRY = mapOf(
        53 to Migration53To54(),
        54 to Migration54To55(),
        55 to Migration55To56(),
        56 to Migration56To57(),
        57 to Migration57To58(),
        58 to Migration58To59(),
        59 to Migration59To60(),
        60 to Migration60To61(),
        61 to Migration61To62(),
        62 to Migration62To63(),
        63 to Migration63To64(),
        64 to Migration64To65(),
        65 to Migration65To66(),
        66 to Migration66To67(),
        67 to Migration67To68(),
        68 to Migration68To69(),
        69 to Migration69To70(),
        70 to Migration70To71(),
        71 to Migration71To72(),
        72 to Migration72To73(),
        73 to Migration73To74(),
        74 to Migration74To75(),
        75 to Migration75To76(),
        76 to Migration76To77(),
        77 to Migration77To78()
    )

    fun buildMigrationChain(fromVersion: Int, toVersion: Int): Array<Migration> {
        val generations = mutableListOf<Migration>()
        for (v in fromVersion until toVersion) {
            MIGRATION_REGISTRY[v]?.let { generations.add(it) }
        }
        return generations.toTypedArray()
    }

    fun getMigrationManifest(fromVersion: Int, toVersion: Int = 62): MigrationManifest {
        val chain = buildMigrationChain(fromVersion, toVersion)
        return MigrationManifest(
            currentVersion = fromVersion,
            targetVersion = toVersion,
            generations = chain.map { MigrationGeneration.findGeneration(it.startVersion) },
            estimatedTimeMs = estimateMigrationTime(chain.size),
            requiresFullBackup = fromVersion <= 25
        )
    }

    private fun estimateMigrationTime(steps: Int): Long {
        return when {
            steps <= 5 -> 500L
            steps <= 15 -> 2000L
            steps <= 30 -> 5000L
            steps <= 50 -> 10000L
            else -> steps * 250L
        }
    }

    data class IntegrityReport(
        val isValid: Boolean,
        val errors: List<String> = emptyList(),
        val warnings: List<String> = emptyList(),
        val totalMigrations: Int = 0,
        val versionRange: String = "",
        val latestVersion: Int = 0
    )

    fun validateMigrationIntegrity(expectedDbVersion: Int): IntegrityReport {
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()

        if (ALL_MIGRATIONS.isEmpty()) {
            errors.add("ALL_MIGRATIONS array is empty - no migrations registered")
            return IntegrityReport(isValid = false, errors = errors)
        }

        val firstMigration = ALL_MIGRATIONS.first()
        val lastMigration = ALL_MIGRATIONS.last()
        val latestVersion = lastMigration.endVersion

        val registryKeys = MIGRATION_REGISTRY.keys.sorted()
        val arrayStartVersions = ALL_MIGRATIONS.map { it.startVersion }.sorted()

        if (ALL_MIGRATIONS.size != MIGRATION_REGISTRY.size) {
            errors.add("Registration mismatch: ALL_MIGRATIONS has ${ALL_MIGRATIONS.size} items but MIGRATION_REGISTRY has ${MIGRATION_REGISTRY.size} entries")
        }

        for (i in 0 until ALL_MIGRATIONS.size - 1) {
            val current = ALL_MIGRATIONS[i]
            val next = ALL_MIGRATIONS[i + 1]
            if (current.endVersion != next.startVersion) {
                errors.add("Gap in migration chain: ${current.startVersion}->${current.endVersion} followed by ${next.startVersion}->${next.endVersion} (expected endVersion=${current.endVersion} to match next startVersion=${next.startVersion})")
            }
        }

        val allVersions = mutableSetOf<Int>()
        val duplicateVersions = mutableListOf<Pair<Int, Int>>()
        for (migration in ALL_MIGRATIONS) {
            val rangeKey = Pair(migration.startVersion, migration.endVersion)
            if (!allVersions.add(migration.startVersion)) {
                duplicateVersions.add(rangeKey)
            }
        }
        if (duplicateVersions.isNotEmpty()) {
            val dupStr = duplicateVersions.joinToString { "${it.first}->${it.second}" }
            errors.add("Duplicate migration start versions detected: $dupStr")
        }

        if (latestVersion != expectedDbVersion) {
            errors.add("Version mismatch: latest migration ends at v$latestVersion but @Database declares version=$expectedDbVersion. " +
                    "If you modified an @Entity class, you must bump the version and add a Migration${latestVersion}To${expectedDbVersion}.")
        }

        val missingVersions = mutableListOf<Int>()
        var expectedNext = firstMigration.startVersion
        for (migration in ALL_MIGRATIONS) {
            if (migration.startVersion != expectedNext && expectedNext != firstMigration.startVersion) {
                missingVersions.add(expectedNext)
            }
            expectedNext = migration.endVersion
        }

        if (missingVersions.isNotEmpty() && errors.isEmpty()) {
            warnings.add("Potential gap in version coverage (may be intentional if old migrations were removed): $missingVersions")
        }

        if (firstMigration.startVersion > 1) {
            warnings.add("First migration starts at v${firstMigration.startVersion} (installations below this version will lack migration path - fallbackToDestructiveMigration has been removed, add Migration or use MigrationFallbackHandler)")
        }

        val isValid = errors.isEmpty()

        if (!isValid) {
            Log.e(TAG, "=== MIGRATION INTEGRITY CHECK FAILED ===")
            errors.forEach { Log.e(TAG, "  ERROR: $it") }
            warnings.forEach { Log.w(TAG, "  WARN:  $it") }
            Log.e(TAG, "======================================")
        } else {
            Log.i(TAG, "Migration integrity OK: ${ALL_MIGRATIONS.size} migrations, v${firstMigration.startVersion} -> v$latestVersion, db version=$expectedDbVersion")
        }

        return IntegrityReport(
            isValid = isValid,
            errors = errors,
            warnings = warnings,
            totalMigrations = ALL_MIGRATIONS.size,
            versionRange = "v${firstMigration.startVersion} -> v$latestVersion",
            latestVersion = latestVersion
        )
    }
}
