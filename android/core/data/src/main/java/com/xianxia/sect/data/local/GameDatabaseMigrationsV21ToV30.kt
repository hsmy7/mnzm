// GameDatabaseMigrationsV21ToV30.kt — 由 GameDatabase.kt 拆分生成（见 GameDatabase.kt addMigrations 列表）
package com.xianxia.sect.data.local

import android.util.Log
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Migration 文件共用的日志 TAG（各文件独立声明，避免 top-level 冲突） */
private const val TAG = "GameDatabase"

        /** v21->v22: game_data 新增 merchantRefreshChances / merchantLastRefreshChanceGrantYear 列 */
        internal val MIGRATION_21_22 = object : Migration(21, 22) {
            override fun migrate(db: SupportSQLiteDatabase) {
                if (!columnExists(db, "game_data", "merchantRefreshChances")) {
                    db.execSQL("ALTER TABLE game_data ADD COLUMN merchantRefreshChances INTEGER NOT NULL DEFAULT 1")
                }
                if (!columnExists(db, "game_data", "merchantLastRefreshChanceGrantYear")) {
                    db.execSQL("ALTER TABLE game_data ADD COLUMN merchantLastRefreshChanceGrantYear INTEGER NOT NULL DEFAULT 0")
                }
                Log.i(TAG, "Migration 21->22: added merchantRefreshChances, merchantLastRefreshChanceGrantYear to game_data")
            }
        }

        /** v22->v23: game_data 移除废弃字段 discipleDesertionPopup */
        internal val MIGRATION_22_23 = object : Migration(22, 23) {
            override fun migrate(db: SupportSQLiteDatabase) {
                if (columnExists(db, "game_data", "discipleDesertionPopup")) {
                    // 获取当前列列表（排除 discipleDesertionPopup）
                    val columns = mutableListOf<String>()
                    val cursor = db.query("PRAGMA table_info(game_data)")
                    cursor.use {
                        while (it.moveToNext()) {
                            val name = it.getString(it.getColumnIndexOrThrow("name"))
                            if (name != "discipleDesertionPopup") {
                                columns.add("\"$name\"")
                            }
                        }
                    }
                    // ⚠️ 之前使用 CREATE TABLE ... AS SELECT ...（CTAS）丢失了所有列约束
                    // (NOT NULL, DEFAULT, PRIMARY KEY, 索引)。改用显式 CREATE TABLE + IFNULL 兜底。
                    rebuildGameData(db, "_old", columns)
                    Log.i(TAG, "Migration 22->23: dropped discipleDesertionPopup from game_data (fixed CTAS constraint loss)")
                }
            }
        }

        internal val MIGRATION_23_24 = object : Migration(23, 24) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // storage_bags 从 PRIMARY KEY (id) 重建为 PRIMARY KEY (id, slot_id)
                // 使用 create-copy-drop-rename 模式兼容 SQLite < 3.35.0
                // Step 1: 去重（极低概率的跨槽位相同 id 情况，防守性保留一条）
                db.execSQL("""
                    DELETE FROM storage_bags WHERE rowid NOT IN (
                        SELECT MIN(rowid) FROM storage_bags GROUP BY id
                    )
                """)
                // Step 2: 建新表 — ⚠️ 必须与 StorageBag 实体完全一致（无 DEFAULT 子句）
                // Room 自动检验会对比 DEFAULT 值，实体无 @ColumnInfo(defaultValue=...) 则不能有 DEFAULT
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `storage_bags_new` (
                        `id` TEXT NOT NULL,
                        `slot_id` INTEGER NOT NULL,
                        `name` TEXT NOT NULL,
                        `rarity` INTEGER NOT NULL,
                        `description` TEXT NOT NULL,
                        `quantity` INTEGER NOT NULL,
                        `isLocked` INTEGER NOT NULL,
                        PRIMARY KEY(`id`, `slot_id`)
                    )
                """)
                // Step 3: 迁移数据
                db.execSQL("INSERT INTO `storage_bags_new` SELECT * FROM `storage_bags`")
                // Step 3a: 修复旧数据 slot_id=0 → 1（旧 bug 导致部分 storageBags slot_id 为默认值 0）
                // 设为 slot 1 作为安全默认值，用户可在游戏内自行整理
                db.execSQL("UPDATE `storage_bags_new` SET `slot_id` = 1 WHERE `slot_id` = 0")
                // Step 4: 替换
                db.execSQL("DROP TABLE IF EXISTS `storage_bags`")
                db.execSQL("ALTER TABLE `storage_bags_new` RENAME TO `storage_bags`")
                // Step 5: 索引
                db.execSQL("CREATE INDEX IF NOT EXISTS index_storage_bags_slot_id ON storage_bags(`slot_id`)")
                Log.i(TAG, "Migration 23->24: rebuilt storage_bags with composite PK (id, slot_id) — no DEFAULT clauses")
            }
        }

        /**
         * v24->v25: 修复两个 migration bug：
         * 1. MIGRATION_22_23 CTAS 丢失 game_data 约束（NOT NULL、DEFAULT、PRIMARY KEY、索引）
         * 2. MIGRATION_23_24 给 storage_bags 加 DEFAULT 值但实体无 @ColumnInfo(defaultValue)
         *
         * 修复方式：重建 game_data 和 storage_bags 表，使用正确的 Room 生成 schema
         */
        internal val MIGRATION_24_25 = object : Migration(24, 25) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // === 修复 game_data ===
                val columns = mutableListOf<String>()
                val cursor = db.query("PRAGMA table_info(game_data)")
                cursor.use {
                    while (it.moveToNext()) {
                        columns.add("\"${it.getString(it.getColumnIndexOrThrow("name"))}\"")
                    }
                }
                // 重建 game_data 表，用 IFNULL 兜底旧数据中的 NULL
                rebuildGameData(db, "_old", columns)

                // === 修复 storage_bags ===
                // 旧版 MIGRATION_23_24 使用了 DEFAULT 子句，实体未定义 DEFAULT。
                // 重建 storage_bags，使用 Room 生成的正确 schema（无 DEFAULT）
                rebuildStorageBags(db)

                Log.i(TAG, "Migration 24->25: rebuilt game_data + storage_bags (restored correct schema)")
            }
        }

        /**
         * v25→v26: 新增引导系统字段到 game_data
         * - guideClaimedRewardIds: Set<Int> → TEXT: 已领取奖励的引导任务ID集合
         * - guideCounters: Map<String, Long> → TEXT: 引导系统计数器（如"点击次数"等）
         */
        internal val MIGRATION_25_26 = object : Migration(25, 26) {
            override fun migrate(db: SupportSQLiteDatabase) {
                if (!columnExists(db, "game_data", "guideClaimedRewardIds")) {
                    db.execSQL(
                        "ALTER TABLE game_data ADD COLUMN guideClaimedRewardIds TEXT " +
                        "NOT NULL DEFAULT '[]'"
                    )
                }
                if (!columnExists(db, "game_data", "guideCounters")) {
                    db.execSQL(
                        "ALTER TABLE game_data ADD COLUMN guideCounters TEXT " +
                        "NOT NULL DEFAULT '{}'"
                    )
                }
                Log.i(TAG, "Migration 25→26: added guideClaimedRewardIds, guideCounters to game_data")
            }
        }

        /**
         * v26→v27: 新增年报系统字段到 game_data
         * - annual_income_by_source: 年内灵石收入按来源
         * - annual_expenditure_by_reason: 年内灵石支出按原因
         * - annual_total_income: 年内灵石总收入
         * - annual_total_expenditure: 年内灵石总支出
         * - annual_alchemy_count: 年内炼丹完成次数
         * - annual_forge_count: 年内锻造完成次数
         * - annual_herb_count: 年内灵植收获次数
         * - annual_new_disciples: 年内新增弟子数
         * - annual_deceased_disciples: 年内死亡弟子数
         * - annual_deserted_disciples: 年内脱离弟子数
         * - yearly_reports: 年度报告存档列表
         */
        internal val MIGRATION_26_27 = object : Migration(26, 27) {
            override fun migrate(db: SupportSQLiteDatabase) {
                val cols = listOf(
                    "annual_income_by_source" to "TEXT NOT NULL DEFAULT '{}'",
                    "annual_expenditure_by_reason" to "TEXT NOT NULL DEFAULT '{}'",
                    "annual_total_income" to "INTEGER NOT NULL DEFAULT 0",
                    "annual_total_expenditure" to "INTEGER NOT NULL DEFAULT 0",
                    "annual_alchemy_count" to "INTEGER NOT NULL DEFAULT 0",
                    "annual_forge_count" to "INTEGER NOT NULL DEFAULT 0",
                    "annual_herb_count" to "INTEGER NOT NULL DEFAULT 0",
                    "annual_new_disciples" to "INTEGER NOT NULL DEFAULT 0",
                    "annual_deceased_disciples" to "INTEGER NOT NULL DEFAULT 0",
                    "annual_deserted_disciples" to "INTEGER NOT NULL DEFAULT 0",
                    "annual_equipment_by_source" to "TEXT NOT NULL DEFAULT '{}'",
                    "annual_pill_by_source" to "TEXT NOT NULL DEFAULT '{}'",
                    "annual_herb_by_source" to "TEXT NOT NULL DEFAULT '{}'",
                    "yearly_reports" to "TEXT NOT NULL DEFAULT '[]'"
                )
                cols.forEach { (name, type) ->
                    if (!columnExists(db, "game_data", name)) {
                        db.execSQL("ALTER TABLE game_data ADD COLUMN $name $type")
                    }
                }
                Log.i(TAG, "Migration 26→27: added annual report fields to game_data")
            }
        }

        /**
         * v27→v28: 补漏 annual_equipment_by_source / annual_pill_by_source / annual_herb_by_source
         * 开发过程中追加了此三列但未升版本，导致已迁移至 v27 的存档 schema 不匹配。
         */
        internal val MIGRATION_27_28 = object : Migration(27, 28) {
            override fun migrate(db: SupportSQLiteDatabase) {
                val cols = listOf(
                    "annual_equipment_by_source" to "TEXT NOT NULL DEFAULT '{}'",
                    "annual_pill_by_source" to "TEXT NOT NULL DEFAULT '{}'",
                    "annual_herb_by_source" to "TEXT NOT NULL DEFAULT '{}'"
                )
                cols.forEach { (name, type) ->
                    if (!columnExists(db, "game_data", name)) {
                        db.execSQL("ALTER TABLE game_data ADD COLUMN $name $type")
                    }
                }
                Log.i(TAG, "Migration 27→28: added missing annual source tracking columns")
            }
        }

        /**
         * v28→v29: 新增 open_recruitment_last_paid_month 列 — 广纳门徒3年冷却追踪
         */
        internal val MIGRATION_28_29 = object : Migration(28, 29) {
            override fun migrate(db: SupportSQLiteDatabase) {
                if (!columnExists(db, "game_data", "open_recruitment_last_paid_month")) {
                    db.execSQL(
                        "ALTER TABLE game_data ADD COLUMN open_recruitment_last_paid_month " +
                        "INTEGER NOT NULL DEFAULT 0"
                    )
                }
                Log.i(TAG, "Migration 28→29: added open_recruitment_last_paid_month")
            }
        }

        /**
         * v29→v30: 新增 annual_theft_count 列 — 宗门偷盗年上限计数器
         */
        internal val MIGRATION_29_30 = object : Migration(29, 30) {
            override fun migrate(db: SupportSQLiteDatabase) {
                if (!columnExists(db, "game_data", "annual_theft_count")) {
                    db.execSQL(
                        "ALTER TABLE game_data ADD COLUMN annual_theft_count " +
                        "INTEGER NOT NULL DEFAULT 0"
                    )
                }
                Log.i(TAG, "Migration 29→30: added annual_theft_count")
            }
        }

        /** v30→v31: 删除 disciples 表中的 usage_lastTheftMonth 列 */
        internal val MIGRATION_30_31 = object : Migration(30, 31) {
            override fun migrate(db: SupportSQLiteDatabase) {
                if (columnExists(db, "disciples", "usage_lastTheftMonth")) {
                    // SQLite < 3.35.0 不支持 DROP COLUMN，使用 create-copy-drop-rename
                    // 新表不含 usage_lastTheftMonth 列，与当前 Disciple 实体一致
                    db.execSQL("""
                        CREATE TABLE IF NOT EXISTS `disciples_v31` (
                            `id` TEXT NOT NULL, `slot_id` INTEGER NOT NULL, `name` TEXT NOT NULL,
                            `surname` TEXT NOT NULL, `realm` INTEGER NOT NULL, `realmLayer` INTEGER NOT NULL,
                            `cultivation` REAL NOT NULL, `cultivationCheckpoint` REAL NOT NULL,
                            `cultivationCheckpointGameMonth` INTEGER NOT NULL, `spiritRootType` TEXT NOT NULL,
                            `age` INTEGER NOT NULL, `lifespan` INTEGER NOT NULL, `isAlive` INTEGER NOT NULL,
                            `gender` TEXT NOT NULL, `portraitRes` TEXT NOT NULL, `manualIds` TEXT NOT NULL,
                            `talentIds` TEXT NOT NULL, `manualMasteries` TEXT NOT NULL, `status` TEXT NOT NULL,
                            `statusData` TEXT NOT NULL, `cultivationSpeedBonus` REAL NOT NULL,
                            `cultivationSpeedDuration` INTEGER NOT NULL, `discipleType` TEXT NOT NULL,
                            `autoLearnFromWarehouse` INTEGER NOT NULL, `soulPower` INTEGER NOT NULL,
                            `cultivationCompletionMonth` INTEGER NOT NULL DEFAULT 0,
                            `cultivationCompletionPhase` INTEGER NOT NULL DEFAULT 1,
                            `manualCompletionMonth` INTEGER NOT NULL DEFAULT 0,
                            `manualCompletionPhase` INTEGER NOT NULL DEFAULT 1,
                            `equipmentNurturingCompletionMonth` INTEGER NOT NULL DEFAULT 0,
                            `equipmentNurturingCompletionPhase` INTEGER NOT NULL DEFAULT 1,
                            `baseHp` INTEGER NOT NULL, `baseMp` INTEGER NOT NULL,
                            `basePhysicalAttack` INTEGER NOT NULL, `baseMagicAttack` INTEGER NOT NULL,
                            `basePhysicalDefense` INTEGER NOT NULL, `baseMagicDefense` INTEGER NOT NULL,
                            `baseSpeed` INTEGER NOT NULL, `hpVariance` INTEGER NOT NULL,
                            `mpVariance` INTEGER NOT NULL, `physicalAttackVariance` INTEGER NOT NULL,
                            `magicAttackVariance` INTEGER NOT NULL, `physicalDefenseVariance` INTEGER NOT NULL,
                            `magicDefenseVariance` INTEGER NOT NULL, `speedVariance` INTEGER NOT NULL,
                            `totalCultivation` INTEGER NOT NULL, `breakthroughCount` INTEGER NOT NULL,
                            `breakthroughFailCount` INTEGER NOT NULL, `currentHp` INTEGER NOT NULL,
                            `currentMp` INTEGER NOT NULL, `pillPhysicalAttackBonus` INTEGER NOT NULL,
                            `pillMagicAttackBonus` INTEGER NOT NULL, `pillPhysicalDefenseBonus` INTEGER NOT NULL,
                            `pillMagicDefenseBonus` INTEGER NOT NULL, `pillHpBonus` INTEGER NOT NULL,
                            `pillMpBonus` INTEGER NOT NULL, `pillSpeedBonus` INTEGER NOT NULL,
                            `pillCritRateBonus` REAL NOT NULL, `pillCritEffectBonus` REAL NOT NULL,
                            `pillCultivationSpeedBonus` REAL NOT NULL, `pillSkillExpSpeedBonus` REAL NOT NULL,
                            `pillNurtureSpeedBonus` REAL NOT NULL, `pillEffectDuration` INTEGER NOT NULL,
                            `activePillCategory` TEXT NOT NULL, `weaponId` TEXT NOT NULL,
                            `armorId` TEXT NOT NULL, `bootsId` TEXT NOT NULL, `accessoryId` TEXT NOT NULL,
                            `weaponNurture` TEXT NOT NULL, `armorNurture` TEXT NOT NULL,
                            `bootsNurture` TEXT NOT NULL, `accessoryNurture` TEXT NOT NULL,
                            `autoEquipFromWarehouse` INTEGER NOT NULL, `storageBagItems` TEXT NOT NULL,
                            `storageBagSpiritStones` INTEGER NOT NULL, `spiritStones` INTEGER NOT NULL,
                            `social_partnerId` TEXT, `social_partnerSectId` TEXT,
                            `social_parentId1` TEXT, `social_parentId2` TEXT,
                            `social_lastChildYear` INTEGER NOT NULL, `social_childBirthMonth` INTEGER,
                            `social_griefEndYear` INTEGER, `social_masterId` TEXT,
                            `intelligence` INTEGER NOT NULL, `charm` INTEGER NOT NULL,
                            `loyalty` INTEGER NOT NULL, `comprehension` INTEGER NOT NULL,
                            `artifactRefining` INTEGER NOT NULL, `pillRefining` INTEGER NOT NULL,
                            `spiritPlanting` INTEGER NOT NULL, `mining` INTEGER NOT NULL,
                            `teaching` INTEGER NOT NULL, `morality` INTEGER NOT NULL,
                            `salaryPaidCount` INTEGER NOT NULL, `salaryMissedCount` INTEGER NOT NULL,
                            `usage_usedFunctionalPillTypes` TEXT NOT NULL,
                            `usage_usedExtendLifePillIds` TEXT NOT NULL,
                            `usage_recruitedMonth` INTEGER NOT NULL, `usage_hasReviveEffect` INTEGER NOT NULL,
                            `usage_hasClearAllEffect` INTEGER NOT NULL,
                            PRIMARY KEY(`id`, `slot_id`)
                        )
                    """)
                    // 复制所有列（排除 usage_lastTheftMonth）
                    val insertCols = listOf(
                        "id", "slot_id", "name", "surname", "realm", "realmLayer",
                        "cultivation", "cultivationCheckpoint", "cultivationCheckpointGameMonth",
                        "spiritRootType", "age", "lifespan", "isAlive", "gender",
                        "portraitRes", "manualIds", "talentIds", "manualMasteries",
                        "status", "statusData", "cultivationSpeedBonus", "cultivationSpeedDuration",
                        "discipleType", "autoLearnFromWarehouse", "soulPower",
                        "cultivationCompletionMonth", "cultivationCompletionPhase",
                        "manualCompletionMonth", "manualCompletionPhase",
                        "equipmentNurturingCompletionMonth", "equipmentNurturingCompletionPhase",
                        "baseHp", "baseMp", "basePhysicalAttack", "baseMagicAttack",
                        "basePhysicalDefense", "baseMagicDefense", "baseSpeed",
                        "hpVariance", "mpVariance", "physicalAttackVariance", "magicAttackVariance",
                        "physicalDefenseVariance", "magicDefenseVariance", "speedVariance",
                        "totalCultivation", "breakthroughCount", "breakthroughFailCount",
                        "currentHp", "currentMp",
                        "pillPhysicalAttackBonus", "pillMagicAttackBonus",
                        "pillPhysicalDefenseBonus", "pillMagicDefenseBonus",
                        "pillHpBonus", "pillMpBonus", "pillSpeedBonus",
                        "pillCritRateBonus", "pillCritEffectBonus",
                        "pillCultivationSpeedBonus", "pillSkillExpSpeedBonus", "pillNurtureSpeedBonus",
                        "pillEffectDuration", "activePillCategory",
                        "weaponId", "armorId", "bootsId", "accessoryId",
                        "weaponNurture", "armorNurture", "bootsNurture", "accessoryNurture",
                        "autoEquipFromWarehouse", "storageBagItems", "storageBagSpiritStones", "spiritStones",
                        "social_partnerId", "social_partnerSectId",
                        "social_parentId1", "social_parentId2", "social_lastChildYear",
                        "social_childBirthMonth", "social_griefEndYear", "social_masterId",
                        "intelligence", "charm", "loyalty", "comprehension",
                        "artifactRefining", "pillRefining", "spiritPlanting", "mining",
                        "teaching", "morality",
                        "salaryPaidCount", "salaryMissedCount",
                        "usage_usedFunctionalPillTypes", "usage_usedExtendLifePillIds",
                        "usage_recruitedMonth", "usage_hasReviveEffect", "usage_hasClearAllEffect"
                    )
                    val cols = insertCols.joinToString(", ")
                    db.execSQL("INSERT INTO `disciples_v31` ($cols) SELECT $cols FROM `disciples`")
                    db.execSQL("DROP TABLE IF EXISTS `disciples`")
                    db.execSQL("ALTER TABLE `disciples_v31` RENAME TO `disciples`")
                    // Room 2.7+ 在迁移后校验 schema，create-copy-drop-rename 会丢失索引，必须重建
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_disciples_name` ON `disciples` (`name`)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_disciples_realm_realmLayer` ON `disciples` (`realm`, `realmLayer`)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_disciples_isAlive_realm` ON `disciples` (`isAlive`, `realm`)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_disciples_isAlive_status` ON `disciples` (`isAlive`, `status`)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_disciples_discipleType` ON `disciples` (`discipleType`)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_disciples_loyalty` ON `disciples` (`loyalty`)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_disciples_age` ON `disciples` (`age`)")
                    Log.i(TAG, "Migration 30→31: dropped usage_lastTheftMonth from disciples")
                } else {
                    Log.i(TAG, "Migration 30→31: usage_lastTheftMonth already absent, skipped")
                }
            }
        }
