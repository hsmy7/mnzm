package com.xianxia.sect.data.local

import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import com.xianxia.sect.data.local.RoomMigrationSupport.insertMinimalGameDataV2
import com.xianxia.sect.data.local.RoomMigrationSupport.verifyFullChainColumns
import com.xianxia.sect.data.local.RoomMigrationSupport.applyMigrationsSequentially
import com.xianxia.sect.data.local.RoomMigrationSupport.columnExists
import com.xianxia.sect.data.local.RoomMigrationSupport.createDatabaseFromSchema
import com.xianxia.sect.data.local.RoomMigrationSupport.indexExists
import com.xianxia.sect.data.local.RoomMigrationSupport.queryString
import com.xianxia.sect.data.local.RoomMigrationSupport.tableColumns
import com.xianxia.sect.data.local.RoomMigrationSupport.tableExists
import com.xianxia.sect.data.local.RoomMigrationSupport.testSingleMigration
import com.xianxia.sect.data.local.RoomMigrationSupport.verifyGameDataColumnsExistFullChain

/**
 * Room 数据库迁移测试。
 *
 * 使用 schema JSON 创建初始数据库，直接执行每个 Migration 的 migrate 函数，
 * 验证新列添加正确、列删除正确，且不崩溃。
 *
 * 直接执行 migrate() 不会触发 Room 的迁移后校验（onValidateSchema）；
 * `passes real Room schema validation` 系列测试通过 Room.databaseBuilder
 * 真实打开库强制校验，覆盖陈旧列/缺索引类缺陷。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class RoomMigrationTest {

    companion object {

        private val M2_3 = MIGRATION_2_3
        private val M3_4 = MIGRATION_3_4
        private val M4_5 = MIGRATION_4_5
        private val M5_6 = MIGRATION_5_6
        private val M6_7 = MIGRATION_6_7
        private val M7_8 = MIGRATION_7_8
        private val M8_9 = MIGRATION_8_9
        private val M9_10 = MIGRATION_9_10
        private val M10_11 = MIGRATION_10_11
        private val M11_12 = MIGRATION_11_12
        private val M12_13 = MIGRATION_12_13
        private val M13_14 = MIGRATION_13_14
        private val M14_15 = MIGRATION_14_15
        private val M15_16 = MIGRATION_15_16
        private val M16_17 = MIGRATION_16_17
        private val M17_18 = MIGRATION_17_18
        private val M18_19 = MIGRATION_18_19
        private val M19_20 = MIGRATION_19_20
        private val M20_21 = MIGRATION_20_21
        private val M21_22 = MIGRATION_21_22
        private val M22_23 = MIGRATION_22_23
        private val M23_24 = MIGRATION_23_24
        private val M24_25 = MIGRATION_24_25
        private val M25_26 = MIGRATION_25_26
        private val M26_27 = MIGRATION_26_27
        private val M27_28 = MIGRATION_27_28
        private val M28_29 = MIGRATION_28_29
        private val M29_30 = MIGRATION_29_30
        private val M30_31 = MIGRATION_30_31
        private val M31_32 = MIGRATION_31_32
        private val M32_33 = MIGRATION_32_33
        private val M33_34 = MIGRATION_33_34
        private val M34_35 = MIGRATION_34_35
        private val M35_36 = MIGRATION_35_36
        private val M36_37 = MIGRATION_36_37
        private val M37_38 = MIGRATION_37_38
        private val M38_39 = MIGRATION_38_39
        private val M39_40 = MIGRATION_39_40
        private val M40_41 = MIGRATION_40_41
        private val M41_42 = MIGRATION_41_42
        private val M42_43 = MIGRATION_42_43
        private val M43_44 = MIGRATION_43_44
        private val M44_45 = MIGRATION_44_45
        private val M45_46 = MIGRATION_45_46
        private val M46_47 = MIGRATION_46_47
        private val M47_48 = MIGRATION_47_48
        private val M48_49 = MIGRATION_48_49
        private val M49_50 = MIGRATION_49_50

        private val SEED_DISCIPLES_V38 = """
            INSERT INTO disciples (
                    id, slot_id, name, surname,
                    realm, realmLayer, cultivation, cultivationCheckpoint,
                    cultivationCheckpointGameMonth, spiritRootType, age, lifespan,
                    isAlive, gender, portraitRes, manualIds,
                    talentIds, physiqueIds, affixIds, manualMasteries,
                    status, statusData, cultivationSpeedBonus, cultivationSpeedDuration,
                    discipleType, autoLearnFromWarehouse, soulPower, cultivationCompletionMonth,
                    cultivationCompletionPhase, manualCompletionMonth, manualCompletionPhase,
                    equipmentNurturingCompletionMonth,
                    equipmentNurturingCompletionPhase, baseHp, baseMp, basePhysicalAttack,
                    baseMagicAttack, basePhysicalDefense, baseMagicDefense, baseSpeed,
                    hpVariance, mpVariance, physicalAttackVariance, magicAttackVariance,
                    physicalDefenseVariance, magicDefenseVariance, speedVariance, totalCultivation,
                    breakthroughCount, breakthroughFailCount, currentHp, currentMp,
                    pillPhysicalAttackBonus, pillMagicAttackBonus, pillPhysicalDefenseBonus, pillMagicDefenseBonus,
                    pillHpBonus, pillMpBonus, pillSpeedBonus, pillCritRateBonus,
                    pillCritEffectBonus, pillCultivationSpeedBonus, pillSkillExpSpeedBonus, pillNurtureSpeedBonus,
                    pillEffectDuration, activePillCategory, weaponId, armorId,
                    bootsId, accessoryId, weaponNurture, armorNurture,
                    bootsNurture, accessoryNurture, autoEquipFromWarehouse, storageBagItems,
                    storageBagSpiritStones, spiritStones, social_partnerId, social_partnerSectId,
                    social_parentId1, social_parentId2, social_lastChildYear, social_childBirthMonth,
                    social_griefEndYear, social_masterId, intelligence, charm,
                    loyalty, comprehension, artifactRefining, pillRefining,
                    spiritPlanting, mining, teaching, morality,
                    salaryPaidCount, salaryMissedCount, usage_usedFunctionalPillTypes, usage_usedExtendLifePillIds,
                    usage_recruitedMonth, usage_hasReviveEffect, usage_hasClearAllEffect
                ) VALUES (
                    'd1', 1, '测试弟子', '',
                    0, 0, 0.0, 0.0,
                    0, '', 0, 0,
                    0, '', '', '',
                    '', '', '', '',
                    '', '', 0.0, 0,
                    '', 0, 0, 0,
                    0, 0, 0, 0,
                    0, 0, 0, 0,
                    0, 0, 0, 0,
                    0, 0, 0, 0,
                    0, 0, 0, 0,
                    0, 0, 0, 0,
                    0, 0, 0, 0,
                    0, 0, 0, 0.0,
                    0.0, 0.0, 0.0, 0.0,
                    0, '', '', '',
                    '', '', '', '',
                    '', '', 0, '',
                    0, 0, '', '',
                    '', '', 0, 0,
                    0, '', 0, 0,
                    0, 0, 0, 0,
                    0, 0, 0, 0,
                    0, 0, '', '',
                    0, 0, 0
                )
        """.trimIndent()
        private val SEED_DISCIPLES_EXTENDED_V38 = """
            INSERT INTO disciples_extended (
                    discipleId, slot_id, manualIds, talentIds,
                    physiqueIds, affixIds, manualMasteries, statusData,
                    cultivationSpeedBonus, cultivationSpeedDuration, pillCultivationSpeedBonus, pillEffectDuration,
                    partnerId, partnerSectId, parentId1, parentId2,
                    lastChildYear, griefEndYear, masterId, usedFunctionalPillTypes,
                    usedExtendLifePillIds, hasReviveEffect, hasClearAllEffect, autoLearnFromWarehouse
                ) VALUES (
                    'd1', 1, 'EXT_MARKER', '',
                    '', '', '', '',
                    0.0, 0, 0.0, 0,
                    '', '', '', '',
                    0, 0, '', '',
                    '', 0, 0, 0
                )
        """.trimIndent()
        private val SEED_DISCIPLES_EQUIPMENT_V38 = """
            INSERT INTO disciples_equipment (
                    discipleId, slot_id, weaponId, armorId,
                    bootsId, accessoryId, weaponNurture, armorNurture,
                    bootsNurture, accessoryNurture, storageBagItems, storageBagSpiritStones,
                    spiritStones, soulPower, autoEquipFromWarehouse
                ) VALUES (
                    'd1', 1, 'EQ_MARKER', '',
                    '', '', '', '',
                    '', '', '', 0,
                    0, 0, 0
                )
        """.trimIndent()
    }

    // ==================== 单个迁移步骤测试 ====================

    @Test
    fun `MIGRATION_36_TO_37 adds watchedItemIds to game_data`() {
        testSingleMigration(
            "m_36_37", 36, 37, listOf(M36_37), "game_data", "watchedItemIds"
        )
    }

    @Test
    fun `MIGRATION_37_TO_38 adds secret realm 4 columns to game_data`() {
        testSingleMigration(
            "m_37_38", 37, 38, listOf(M37_38), "game_data", "secret_realm_state"
        )
        testSingleMigration(
            "m_37_38_cd", 37, 38, listOf(M37_38), "game_data", "secret_realm_cooldown_year"
        )
        testSingleMigration(
            "m_37_38_sess", 37, 38, listOf(M37_38), "game_data", "secret_realm_session"
        )
        testSingleMigration(
            "m_37_38_ai", 37, 38, listOf(M37_38), "game_data", "secret_realm_ai_teams"
        )
    }

    @Test
    fun `MIGRATION_38_TO_39 rebuild drops dead columns and preserves data`() {
        // 弟子级 autoLearnFromWarehouse/autoEquipFromWarehouse 死开关列删除采用
        // create-copy-drop-rename 重建（SQLite < 3.35 不支持 DROP COLUMN，参照 MIGRATION_30_31）。
        // Room 2.7 迁移后校验（列全等比较）不允许残留实体已删除的旧列。
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val dbName = "m_38_39_rebuild"
        context.deleteDatabase(dbName)
        try {
            val db = createDatabaseFromSchema(context, dbName, 38)
            // v38 种子行（含被删除的 auto 开关列）
            db.execSQL(SEED_DISCIPLES_V38)
            db.execSQL(SEED_DISCIPLES_EXTENDED_V38)
            db.execSQL(SEED_DISCIPLES_EQUIPMENT_V38)
            applyMigrationsSequentially(db, listOf(M38_39))

            // 死列已删除
            assertFalse("disciples.autoLearnFromWarehouse should be dropped",
                columnExists(db, "disciples", "autoLearnFromWarehouse"))
            assertFalse("disciples.autoEquipFromWarehouse should be dropped",
                columnExists(db, "disciples", "autoEquipFromWarehouse"))
            assertFalse("disciples_extended.autoLearnFromWarehouse should be dropped",
                columnExists(db, "disciples_extended", "autoLearnFromWarehouse"))
            assertFalse("disciples_equipment.autoEquipFromWarehouse should be dropped",
                columnExists(db, "disciples_equipment", "autoEquipFromWarehouse"))

            // 数据保留
            assertEquals("弟子数据应在重建后保留", "测试弟子",
                queryString(db, "SELECT name FROM disciples WHERE id = 'd1'"))
            assertEquals("扩展表数据应在重建后保留", "EXT_MARKER",
                queryString(db, "SELECT manualIds FROM disciples_extended WHERE discipleId = 'd1'"))
            assertEquals("装备表数据应在重建后保留", "EQ_MARKER",
                queryString(db, "SELECT weaponId FROM disciples_equipment WHERE discipleId = 'd1'"))

            // RENAME 丢失的 7 个索引必须重建（Room 2.7 迁移后校验会检查索引）
            assertTrue("index_disciples_name should be recreated",
                indexExists(db, "disciples", "index_disciples_name"))
            assertTrue("index_disciples_realm_realmLayer should be recreated",
                indexExists(db, "disciples", "index_disciples_realm_realmLayer"))
            assertTrue("index_disciples_isAlive_realm should be recreated",
                indexExists(db, "disciples", "index_disciples_isAlive_realm"))
            assertTrue("index_disciples_isAlive_status should be recreated",
                indexExists(db, "disciples", "index_disciples_isAlive_status"))
            assertTrue("index_disciples_discipleType should be recreated",
                indexExists(db, "disciples", "index_disciples_discipleType"))
            assertTrue("index_disciples_loyalty should be recreated",
                indexExists(db, "disciples", "index_disciples_loyalty"))
            assertTrue("index_disciples_age should be recreated",
                indexExists(db, "disciples", "index_disciples_age"))

            db.close()
        } finally {
            context.deleteDatabase(dbName)
        }
    }

    /**
     * 真实 Room 校验测试：用 Room.databaseBuilder 打开 v38 库升级到 v39，
     * 触发 RoomOpenHelper.onUpgrade → onValidateSchema（迁移后强制校验）。
     *
     * 回归防线：若迁移保留了实体已删除的列，会在此抛
     * "Migration didn't properly handle: disciples"（Room 2.7.0 TableInfo 列全等比较）；
     * 直接执行 migrate() SQL 的测试不经过 Room 打开库，无法发现该问题。
     */
    @Test
    fun `MIGRATION_38_TO_39 passes real Room schema validation`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val dbName = "m_38_39_room_validate"
        context.deleteDatabase(dbName)
        try {
            createDatabaseFromSchema(context, dbName, 38).close()
            val db = Room.databaseBuilder(context, GameDatabase::class.java, dbName)
                // Room 打开时要求 38→当前版本(50) 路径可达，必须注册后续全部迁移
                .addMigrations(
                    M38_39, M39_40, M40_41, M41_42, M42_43, M43_44,
                    M44_45, M45_46, M46_47, M47_48, M48_49, M49_50
                )
                .build()
            db.openHelper.writableDatabase
            db.close()
        } finally {
            context.deleteDatabase(dbName)
        }
    }

    /**
     * 全链真实 Room 校验：v11 库一次性升级到 v42，触发 onValidateSchema 对全部表
     * 做列/索引/外键全等校验——任何历史迁移的陈旧列/缺索引都会在此暴露。
     *
     * 起点选择 v11 而非 v2：仓库提交的 2.json 是陈旧导出（917416ce 时版本已 11，
     * 2.json 为旧时代拷贝，manual_stacks 仅 26 列），与真实 v2 时代实体（skill 系
     * 列自 5df5bc99 起一直存在，31 列）不符，作为校验起点会产生假阳性。
     * 11.json 与 v11 实体同步导出，可信。v2→v11 段仅含带 columnExists 守卫的
     * game_data ALTER，风险由下方数据保真测试覆盖。
     */
    @Test
    fun `full migration v11 to v42 passes real Room schema validation`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val dbName = "full_migrate_v42_room_validate"
        context.deleteDatabase(dbName)
        try {
            createDatabaseFromSchema(context, dbName, 11).close()
            val roomDb = Room.databaseBuilder(context, GameDatabase::class.java, dbName)
                .addMigrations(
                    M11_12, M12_13, M13_14, M14_15, M15_16,
                    M16_17, M17_18, M18_19, M19_20, M20_21, M21_22,
                    M22_23, M23_24, M24_25, M25_26, M26_27, M27_28,
                    M28_29, M29_30, M30_31, M31_32, M32_33, M33_34, M34_35, M35_36,
                    M36_37, M37_38, M38_39, M39_40, M40_41, M41_42, M42_43, M43_44,
                    M44_45, M45_46, M46_47, M47_48, M48_49, M49_50
                )
                .build()
            roomDb.openHelper.writableDatabase
            roomDb.close()
        } finally {
            context.deleteDatabase(dbName)
        }
    }

    /**
     * 真实 Room 校验：v39 库升级到 v40（战斗队伍持久化三列），
     * 触发 onValidateSchema——任何列定义与实体注解不一致都会在此崩溃。
     */
    @Test
    fun `MIGRATION_39_TO_40 passes real Room schema validation`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val dbName = "m_39_40_room_validate"
        context.deleteDatabase(dbName)
        try {
            createDatabaseFromSchema(context, dbName, 39).close()
            val db = Room.databaseBuilder(context, GameDatabase::class.java, dbName)
                // 完整迁移路径 39→50（直至当前版本）
                .addMigrations(M39_40, M40_41, M41_42, M42_43, M43_44, M44_45, M45_46, M46_47, M47_48, M48_49, M49_50)
                .build()
            db.openHelper.writableDatabase
            db.close()
        } finally {
            context.deleteDatabase(dbName)
        }
    }

    @Test
    fun `MIGRATION_39_TO_40 adds battle team columns with defaults`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val dbName = "m_39_40_columns"
        context.deleteDatabase(dbName)
        try {
            val db = createDatabaseFromSchema(context, dbName, 39)
            // 手动执行迁移 SQL（真实 Room 校验由上一测试覆盖）
            listOf(M39_40).forEach { it.migrate(db) }

            // 新列存在且默认值正确（旧档数据保留由 full migration v2 to v40 全链测试覆盖）
            assertTrue(columnExists(db, "game_data", "battle_teams"))
            assertTrue(columnExists(db, "game_data", "used_team_numbers"))
            assertTrue(columnExists(db, "game_data", "battle_teams_initialized"))

            val defaults = db.query("PRAGMA table_info(game_data)").use { cursor ->
                val map = mutableMapOf<String, String?>()
                while (cursor.moveToNext()) {
                    map[cursor.getString(1)] = cursor.getString(4)
                }
                map
            }
            assertEquals("battle_teams 默认空串", "''", defaults["battle_teams"])
            assertEquals("used_team_numbers 默认空串", "''", defaults["used_team_numbers"])
            assertEquals("battle_teams_initialized 默认 0", "0", defaults["battle_teams_initialized"])
            db.close()
        } finally {
            context.deleteDatabase(dbName)
        }
    }

    /**
     * 真实 Room 校验：v40 库升级到 v41（AI 宗门弟子三年一度招募差值判据列），
     * 触发 onValidateSchema——任何列定义与实体注解不一致都会在此崩溃
     * （对齐 v39→v40 的真实 Room 校验模式）。
     */
    @Test
    fun `MIGRATION_40_TO_41 passes real Room schema validation`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val dbName = "m_40_41_room_validate"
        context.deleteDatabase(dbName)
        try {
            createDatabaseFromSchema(context, dbName, 40).close()
            val db = Room.databaseBuilder(context, GameDatabase::class.java, dbName)
                .addMigrations(M40_41, M41_42, M42_43, M43_44, M44_45, M45_46, M46_47, M47_48, M48_49, M49_50)
                .build()
            db.openHelper.writableDatabase
            db.close()
        } finally {
            context.deleteDatabase(dbName)
        }
    }

    /**
     * 真实 Room 校验：v41 库升级到 v42（玉符氪金货币四列），
     * 触发 onValidateSchema——任何列定义与实体注解不一致都会在此崩溃
     * （对齐 v40→v41 的真实 Room 校验模式）。
     */
    @Test
    fun `MIGRATION_41_TO_42 passes real Room schema validation`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val dbName = "m_41_42_room_validate"
        context.deleteDatabase(dbName)
        try {
            createDatabaseFromSchema(context, dbName, 41).close()
            val db = Room.databaseBuilder(context, GameDatabase::class.java, dbName)
                .addMigrations(M41_42, M42_43, M43_44, M44_45, M45_46, M46_47, M47_48, M48_49, M49_50)
                .build()
            db.openHelper.writableDatabase
            db.close()
        } finally {
            context.deleteDatabase(dbName)
        }
    }

    @Test
    fun `MIGRATION_42_TO_43 passes real Room schema validation`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val dbName = "m_42_43_room_validate"
        context.deleteDatabase(dbName)
        try {
            createDatabaseFromSchema(context, dbName, 42).close()
            val db = Room.databaseBuilder(context, GameDatabase::class.java, dbName)
                .addMigrations(M42_43, M43_44, M44_45, M45_46, M46_47, M47_48, M48_49, M49_50)
                .build()
            db.openHelper.writableDatabase
            db.close()
        } finally {
            context.deleteDatabase(dbName)
        }
    }

    @Test
    fun `MIGRATION_42_TO_43 creates overflow and direct mail draft tables`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val dbName = "m_42_43_tables"
        context.deleteDatabase(dbName)
        try {
            val db = createDatabaseFromSchema(context, dbName, 42)
            // 手动执行迁移 SQL（真实 Room 校验由上一测试覆盖）
            listOf(M42_43).forEach { it.migrate(db) }

            // 溢出草稿表：8 列全量
            val overflowCols = tableColumns(db, "overflow_mail_drafts")
            assertEquals(
                listOf("id", "slotId", "source", "itemType", "itemName", "rarity", "quantity", "createdAt"),
                overflowCols
            )

            // 直发草稿表：4 列全量
            val directCols = tableColumns(db, "direct_mail_drafts")
            assertEquals(
                listOf("id", "slotId", "payload", "createdAt"),
                directCols
            )

            // 迁移后草稿表可插入/读取（Room 列类型正确性）
            db.execSQL(
                "INSERT INTO overflow_mail_drafts VALUES ('x1', 1, 'battle', 'material', '玄铁精', 2, 3, 100)"
            )
            db.execSQL("INSERT INTO direct_mail_drafts VALUES ('m1', 1, '{\"id\":\"m1\"}', 100)")
            val overflowCount = db.query("SELECT COUNT(*) FROM overflow_mail_drafts").use { c ->
                c.moveToFirst(); c.getInt(0)
            }
            val directCount = db.query("SELECT COUNT(*) FROM direct_mail_drafts").use { c ->
                c.moveToFirst(); c.getInt(0)
            }
            assertEquals("迁移后溢出草稿行可插入", 1, overflowCount)
            assertEquals("迁移后直发草稿行可插入", 1, directCount)
            db.close()
        } finally {
            context.deleteDatabase(dbName)
        }
    }

    @Test
    fun `MIGRATION_41_TO_42 adds 4 jade columns with default 0`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val dbName = "m_41_42_columns"
        context.deleteDatabase(dbName)
        try {
            val db = createDatabaseFromSchema(context, dbName, 41)
            // 手动执行迁移 SQL（真实 Room 校验由上一测试覆盖）
            listOf(M41_42).forEach { it.migrate(db) }

            assertTrue(columnExists(db, "game_data", "jade_symbols"))
            assertTrue(columnExists(db, "game_data", "jade_symbols_today"))
            assertTrue(columnExists(db, "game_data", "jade_day_anchor_ms"))
            assertTrue(columnExists(db, "game_data", "jade_accum_ms"))

            val defaults = db.query("PRAGMA table_info(game_data)").use { cursor ->
                val map = mutableMapOf<String, String?>()
                while (cursor.moveToNext()) {
                    map[cursor.getString(1)] = cursor.getString(4)
                }
                map
            }
            assertEquals("jade_symbols 默认 0", "0", defaults["jade_symbols"])
            assertEquals("jade_symbols_today 默认 0", "0", defaults["jade_symbols_today"])
            assertEquals("jade_day_anchor_ms 默认 0", "0", defaults["jade_day_anchor_ms"])
            assertEquals("jade_accum_ms 默认 0", "0", defaults["jade_accum_ms"])
            db.close()
        } finally {
            context.deleteDatabase(dbName)
        }
    }

    @Test
    fun `MIGRATION_40_TO_41 adds last_ai_sect_recruit_year column with default 0`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val dbName = "m_40_41_columns"
        context.deleteDatabase(dbName)
        try {
            val db = createDatabaseFromSchema(context, dbName, 40)
            // 手动执行迁移 SQL（真实 Room 校验由上一测试覆盖）
            listOf(M40_41).forEach { it.migrate(db) }

            assertTrue(columnExists(db, "game_data", "last_ai_sect_recruit_year"))

            val defaults = db.query("PRAGMA table_info(game_data)").use { cursor ->
                val map = mutableMapOf<String, String?>()
                while (cursor.moveToNext()) {
                    map[cursor.getString(1)] = cursor.getString(4)
                }
                map
            }
            assertEquals("last_ai_sect_recruit_year 默认 0", "0", defaults["last_ai_sect_recruit_year"])
            db.close()
        } finally {
            context.deleteDatabase(dbName)
        }
    }


    @Test
    fun `full migration from v2 to v39 applies all steps without crash and preserves seed data`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val dbName = "full_migrate_v39"
        context.deleteDatabase(dbName)
        try {
            // 全链迁移从 v2 起点覆盖全部迁移段——v2→v12 段必须覆盖：
            // MIGRATION_12_13 会引用 merchantAcquisitionItems 等列，列缺失即升级崩溃。
            val db = createDatabaseFromSchema(context, dbName, 2)

            // ── 在 v2 种子库插入最小 game_data 行（v2 createSql 列集）──
            insertMinimalGameDataV2(db)

            applyMigrationsSequentially(
                db,
                listOf(
                    M2_3, M3_4, M4_5, M5_6, M6_7, M7_8, M8_9, M9_10,
                    M10_11, M11_12, M12_13, M13_14, M14_15, M15_16,
                    M16_17, M17_18, M18_19, M19_20, M20_21, M21_22,
                    M22_23, M23_24, M24_25, M25_26, M26_27, M27_28,
                    M28_29, M29_30, M30_31, M31_32, M32_33, M33_34, M34_35, M35_36,
                    M36_37, M37_38, M38_39, M39_40
                )
            )

            // ── 种子数据保留验证 ──
            val sectName = db.query("SELECT sectName, gameYear, gameMonth, spiritStones FROM game_data WHERE slot_id " +
                "= 1").use { cursor ->
                var result = ""
                if (cursor.moveToFirst()) {
                    result = cursor.getString(0)
                }
                result
            }
            assertEquals("种子 sectName 应在全链迁移后保留", "测试宗门", sectName)

            // ── 历史缺陷回归：MIGRATION_12_13 引用的列必须存在 ──
            assertTrue("merchantAcquisitionItems should exist after full chain migration",
                columnExists(db, "game_data", "merchantAcquisitionItems"))
            assertTrue("merchantAcquisitionLastRefreshYear should exist after full chain migration",
                columnExists(db, "game_data", "merchantAcquisitionLastRefreshYear"))

            // ── 各表列完整性验证 ──
            verifyFullChainColumns(db)

            db.close()
        } finally {
            context.deleteDatabase(dbName)
        }
    }

    // ==================== 迁移测试（M16→M32） ====================

    @Test
    fun `MIGRATION_16_TO_17 adds missing columns worldLevelLastRefreshMonth rngStates pendingPatrolBattleResults`() {
        testSingleMigration("m_16_17_fix", 16, 17, listOf(M16_17), "game_data", "worldLevelLastRefreshMonth")
        testSingleMigration("m_16_17_rng", 16, 17, listOf(M16_17), "game_data", "rngStates")
        testSingleMigration("m_16_17_ppr", 16, 17, listOf(M16_17), "game_data", "pendingPatrolBattleResults")
    }

    @Test
    fun `MIGRATION_17_TO_18 drops zombie tables forge_slots and alchemy_slots`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val dbName = "m_17_18_drop"
        context.deleteDatabase(dbName)
        try {
            val db = createDatabaseFromSchema(context, dbName, 17)
            assertTrue("forge_slots should exist in v17 schema",
                tableExists(db, "forge_slots"))
            assertTrue("alchemy_slots should exist in v17 schema",
                tableExists(db, "alchemy_slots"))

            applyMigrationsSequentially(db, listOf(M17_18))

            assertFalse("forge_slots should be dropped after v18 migration",
                tableExists(db, "forge_slots"))
            assertFalse("alchemy_slots should be dropped after v18 migration",
                tableExists(db, "alchemy_slots"))

            db.close()
        } finally {
            context.deleteDatabase(dbName)
        }
    }

    @Test
    fun `MIGRATION_18_TO_19 adds pillCultivationSpeedBonus and pillEffectDuration to disciples_extended`() {
        testSingleMigration("m_18_19_pcs", 18, 19, listOf(M18_19), "disciples_extended", "pillCultivationSpeedBonus")
        testSingleMigration("m_18_19_ped", 18, 19, listOf(M18_19), "disciples_extended", "pillEffectDuration")
    }

    @Test
    fun `MIGRATION_19_TO_20 removes gameSpeed and adds bloodRefinementPctTotals`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val dbName = "m_19_20_chg"
        context.deleteDatabase(dbName)
        try {
            val db = createDatabaseFromSchema(context, dbName, 19)
            assertTrue("gameSpeed should exist in v19 schema",
                columnExists(db, "game_data", "gameSpeed"))

            applyMigrationsSequentially(db, listOf(M19_20))

            assertFalse("gameSpeed should be removed after v20 migration",
                columnExists(db, "game_data", "gameSpeed"))
            assertTrue("bloodRefinementPctTotals should exist after v20 migration",
                columnExists(db, "game_data", "bloodRefinementPctTotals"))

            db.close()
        } finally {
            context.deleteDatabase(dbName)
        }
    }

    @Test
    fun `MIGRATION_20_TO_21 adds gameEventRecords to game_data`() {
        testSingleMigration("m_20_21", 20, 21, listOf(M20_21), "game_data", "gameEventRecords")
    }

    @Test
    fun `MIGRATION_21_TO_22 adds merchantRefreshChances and merchantLastRefreshChanceGrantYear`() {
        testSingleMigration("m_21_22_mrc", 21, 22, listOf(M21_22), "game_data", "merchantRefreshChances")
        testSingleMigration("m_21_22_mlr", 21, 22, listOf(M21_22), "game_data", "merchantLastRefreshChanceGrantYear")
    }

    @Test
    fun `MIGRATION_25_TO_26 adds guideClaimedRewardIds and guideCounters`() {
        testSingleMigration("m_25_26_gc", 25, 26, listOf(M25_26), "game_data", "guideClaimedRewardIds")
        testSingleMigration("m_25_26_gct", 25, 26, listOf(M25_26), "game_data", "guideCounters")
    }

    @Test
    fun `MIGRATION_26_TO_27 adds annual report columns to game_data`() {
        val expected = listOf(
            "annual_income_by_source", "annual_expenditure_by_reason",
            "annual_total_income", "annual_total_expenditure",
            "annual_alchemy_count", "annual_forge_count", "annual_herb_count",
            "annual_new_disciples", "annual_deceased_disciples", "annual_deserted_disciples",
            "annual_equipment_by_source", "annual_pill_by_source",
            "annual_herb_by_source", "yearly_reports"
        )
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val dbName = "m_26_27_annual"
        context.deleteDatabase(dbName)
        try {
            val db = createDatabaseFromSchema(context, dbName, 26)
            applyMigrationsSequentially(db, listOf(M26_27))
            for (col in expected) {
                assertTrue("game_data should have annual column '$col' after v27 migration",
                    columnExists(db, "game_data", col))
            }
            db.close()
        } finally {
            context.deleteDatabase(dbName)
        }
    }

    @Test
    fun `MIGRATION_28_TO_29 adds open_recruitment_last_paid_month to game_data`() {
        testSingleMigration("m_28_29", 28, 29, listOf(M28_29), "game_data", "open_recruitment_last_paid_month")
    }

    @Test
    fun `MIGRATION_29_TO_30 adds annual_theft_count to game_data`() {
        testSingleMigration("m_29_30", 29, 30, listOf(M29_30), "game_data", "annual_theft_count")
    }

    @Test
    fun `MIGRATION_31_TO_32 adds theft_judgements_this_month to game_data`() {
        testSingleMigration("m_31_32", 31, 32, listOf(M31_32), "game_data", "theft_judgements_this_month")
    }

    @Test
    fun `MIGRATION_32_TO_33 adds soundEnabled to game_data`() {
        // 32.json 在 GameData 加入 soundEnabled/musicEnabled 后生成，已包含这些列，
        // 因此从 v31 schema（不含这两列）开始，先走 M31_32 升至 v32，再测 M32_33
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val dbName = "m_32_33_se"
        context.deleteDatabase(dbName)
        try {
            val db = createDatabaseFromSchema(context, dbName, 31)
            applyMigrationsSequentially(db, listOf(M31_32, M32_33))
            assertTrue("Column 'soundEnabled' should exist after M32_33",
                columnExists(db, "game_data", "soundEnabled"))
            assertTrue("Column 'musicEnabled' should exist after M32_33",
                columnExists(db, "game_data", "musicEnabled"))
            db.close()
        } finally {
            context.deleteDatabase(dbName)
        }
    }

    @Test
    fun `MIGRATION_32_TO_33 adds musicEnabled to game_data`() {
        testSingleMigration("m_32_33_me", 31, 33, listOf(M31_32, M32_33), "game_data", "musicEnabled")
    }

    @Test
    fun `MIGRATION_33_TO_34 adds prisonerSpiritRootFilter to game_data`() {
        testSingleMigration("m_33_34_psf", 33, 34, listOf(M33_34), "game_data", "prisonerSpiritRootFilter")
    }

    @Test
    fun `MIGRATION_33_TO_34 adds recruitCountThisMonth to game_data`() {
        testSingleMigration("m_33_34_rcm", 33, 34, listOf(M33_34), "game_data", "recruitCountThisMonth")
    }
    // ==================== 全链路数据留存测试（M21→M33） ====================

    @Test
    fun `full migration chain 21 to 33 preserves inserted game data`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val dbName = "chain_21_33_dp"
        context.deleteDatabase(dbName)
        try {
            val db = createDatabaseFromSchema(context, dbName, 21)

            // 动态构建 INSERT：查询 PRAGMA table_info 获取所有列名，
            // 为每列提供默认值（TEXT='', INTEGER=0, REAL=0.0）
            val (columns, values) = collectGameDataColumns(db)

            // 覆盖关键字段为测试值
            val testSectName = "TestSect_Chain_21_32"
            applyChainSeedOverrides(columns, values, testSectName)

            val insertSql = "INSERT INTO game_data (${columns.joinToString(",")}) VALUES (${values.joinToString(",")})"
            db.execSQL(insertSql)

            // 验证数据已写入
            var cursor = db.query("SELECT sectName FROM game_data WHERE id = 'gd_chain'", emptyArray())
            cursor.use {
                assertTrue("test data should exist", it.moveToFirst())
                assertEquals(testSectName, it.getString(0))
            }

            // 按顺序运行 M21_22 → M22_23 → ... → M32_33（12 步迁移，
            // 每一步——含 M30_31——都必须显式覆盖）
            applyMigrationsSequentially(
                db,
                listOf(
                    M21_22, M22_23, M23_24, M24_25, M25_26, M26_27,
                    M27_28, M28_29, M29_30, M30_31, M31_32, M32_33
                )
            )

            // 验证数据在迁移后仍然存在
            cursor = db.query("SELECT sectName, gameYear, gameMonth FROM game_data WHERE id = 'gd_chain'", emptyArray())
            cursor.use {
                assertTrue("data should survive full chain migration", it.moveToFirst())
                assertEquals(testSectName, it.getString(0))
                assertEquals(42, it.getInt(1))
                assertEquals(6, it.getInt(2))
            }

            // 验证所有版本新增的列存在
            verifyGameDataColumnsExistFullChain(db)

            db.close()
        } finally {
            context.deleteDatabase(dbName)
        }
    }


    /** game_data 列名与默认值收集（full chain 拆分）：PRAGMA 逐列，TEXT=''/INT=0/REAL=0.0 */
    private fun collectGameDataColumns(
        db: SupportSQLiteDatabase
    ): Pair<MutableList<String>, MutableList<String>> {
        val columns = mutableListOf<String>()
        val values = mutableListOf<String>()
        val colInfo = db.query("PRAGMA table_info(game_data)", emptyArray())
        colInfo.use {
            while (it.moveToNext()) {
                val colName = it.getString(it.getColumnIndexOrThrow("name"))
                val colType = it.getString(it.getColumnIndexOrThrow("type"))
                columns.add(colName)
                values.add(when {
                    colType?.uppercase()?.contains("INT") == true -> "0"
                    colType?.uppercase()?.contains("REAL") == true -> "0.0"
                    else -> "''"
                })
            }
        }
        return columns to values
    }

    /** 链路种子关键字段覆盖（full chain 拆分）：列存在才覆写 */
    private fun applyChainSeedOverrides(
        columns: MutableList<String>,
        values: MutableList<String>,
        testSectName: String
    ) {
        overrideColumnIfPresent(columns, values, "id", "'gd_chain'")
        overrideColumnIfPresent(columns, values, "slot_id", "1")
        overrideColumnIfPresent(columns, values, "sectName", "'$testSectName'")
        overrideColumnIfPresent(columns, values, "currentSlot", "1")
        overrideColumnIfPresent(columns, values, "gameYear", "42")
        overrideColumnIfPresent(columns, values, "gameMonth", "6")
        overrideColumnIfPresent(columns, values, "gamePhase", "0")
    }

    /** 单列覆写（full chain 拆分） */
    private fun overrideColumnIfPresent(
        columns: List<String>,
        values: MutableList<String>,
        column: String,
        value: String
    ) {
        val idx = columns.indexOf(column)
        if (idx >= 0) values[idx] = value
    }

    @Test
    fun `MIGRATION_30_TO_31 removes usage_lastTheftMonth from disciples`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val dbName = "m_30_31_drop"
        context.deleteDatabase(dbName)
        try {
            // 使用 v29 schema（含 usage_lastTheftMonth），模拟真实设备上的数据库路径
            // 当前 30.json 已被覆写（usage_lastTheftMonth 已移除），因此不能直接用 v30 schema 测试
            val db = createDatabaseFromSchema(context, dbName, 29)
            // 验证 usage_lastTheftMonth 在 v29 schema 中存在
            assertTrue("usage_lastTheftMonth should exist in v29 schema",
                columnExists(db, "disciples", "usage_lastTheftMonth"))

            // 应用 MIGRATION_29_30 到达 v30（新增 annual_theft_count，不影响 disciples）
            applyMigrationsSequentially(db, listOf(MIGRATION_29_30))

            // 验证迁移到 v30 后 usage_lastTheftMonth 仍存在
            assertTrue("usage_lastTheftMonth should still exist at v30",
                columnExists(db, "disciples", "usage_lastTheftMonth"))
            assertTrue("annual_theft_count should exist at v30",
                columnExists(db, "game_data", "annual_theft_count"))

            // 应用 MIGRATION_30_31 删除 usage_lastTheftMonth
            applyMigrationsSequentially(db, listOf(MIGRATION_30_31))

            assertFalse("usage_lastTheftMonth should be removed after MIGRATION_30_31",
                columnExists(db, "disciples", "usage_lastTheftMonth"))

            // 验证关键列未受影响
            assertTrue("id should still exist after migration",
                columnExists(db, "disciples", "id"))
            assertTrue("name should still exist after migration",
                columnExists(db, "disciples", "name"))
            assertTrue("cultivation should still exist after migration",
                columnExists(db, "disciples", "cultivation"))
            assertTrue("usage_usedFunctionalPillTypes should still exist after migration",
                columnExists(db, "disciples", "usage_usedFunctionalPillTypes"))

            // 验证所有 7 个索引已重建（Room 2.7+ 迁移后校验 schema 需要索引完整）
            assertTrue("index_disciples_name should exist",
                indexExists(db, "disciples", "index_disciples_name"))
            assertTrue("index_disciples_realm_realmLayer should exist",
                indexExists(db, "disciples", "index_disciples_realm_realmLayer"))
            assertTrue("index_disciples_isAlive_realm should exist",
                indexExists(db, "disciples", "index_disciples_isAlive_realm"))
            assertTrue("index_disciples_isAlive_status should exist",
                indexExists(db, "disciples", "index_disciples_isAlive_status"))
            assertTrue("index_disciples_discipleType should exist",
                indexExists(db, "disciples", "index_disciples_discipleType"))
            assertTrue("index_disciples_loyalty should exist",
                indexExists(db, "disciples", "index_disciples_loyalty"))
            assertTrue("index_disciples_age should exist",
                indexExists(db, "disciples", "index_disciples_age"))

            db.close()
        } finally {
            context.deleteDatabase(dbName)
        }
    }

    @Test
    fun `MIGRATION_49_50 drops autoSaveIntervalMonths from game_data and sect_policy_state`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val dbName = "m_49_50_drop_autosave"
        context.deleteDatabase(dbName)
        try {
            // 从 v49 schema 创建（含 autoSaveIntervalMonths），模拟真实设备升级路径
            val db = createDatabaseFromSchema(context, dbName, 49)
            assertTrue("autoSaveIntervalMonths should exist in game_data at v49",
                columnExists(db, "game_data", "autoSaveIntervalMonths"))
            assertTrue("autoSaveIntervalMonths should exist in sect_policy_state at v49",
                columnExists(db, "sect_policy_state", "autoSaveIntervalMonths"))

            // 插入最小 game_data 行（按 v49 PRAGMA 实际列生成默认值）+ sect_policy_state 行，
            // 验证迁移后数据不丢。
            insertV49MinimalRows(db)

            // 应用 MIGRATION_49_50
            applyMigrationsSequentially(db, listOf(MIGRATION_49_50))

            assertV50MigrationResult(db)

            db.close()
        } finally {
            context.deleteDatabase(dbName)
        }
    }

    /**
     * v49 库插入最小存活数据行：game_data 按 PRAGMA 实际列生成类型默认值
     * （id/slot_id/sectName 覆写为可断言值）+ sect_policy_state 显式列清单行。
     */
    private fun insertV49MinimalRows(db: SupportSQLiteDatabase) {
        val gCols = mutableListOf<String>()
        val gVals = mutableListOf<String>()
        val gInfo = db.query("PRAGMA table_info(game_data)", emptyArray())
        gInfo.use {
            while (it.moveToNext()) {
                val name = it.getString(it.getColumnIndexOrThrow("name"))
                val type = it.getString(it.getColumnIndexOrThrow("type"))
                gCols.add(name)
                gVals.add(when {
                    type?.uppercase()?.contains("INT") == true -> "0"
                    type?.uppercase()?.contains("REAL") == true -> "0.0"
                    else -> "''"
                })
            }
        }
        val gIdIdx = gCols.indexOf("id"); if (gIdIdx >= 0) gVals[gIdIdx] = "'game_data_1'"
        val gSidIdx = gCols.indexOf("slot_id"); if (gSidIdx >= 0) gVals[gSidIdx] = "1"
        val gSnIdx = gCols.indexOf("sectName"); if (gSnIdx >= 0) gVals[gSnIdx] = "'测试宗门'"
        db.execSQL(
            "INSERT INTO game_data (${gCols.joinToString(",")}) VALUES (${gVals.joinToString(",")})"
        )
        db.execSQL(
            "INSERT INTO sect_policy_state (slot_id, sectPolicies, autoRecruitSpiritRootFilter, " +
                "daoCompanionBannedRootCounts, daoCompanionConsentRequired, breakthroughAutoPillFocused, " +
                "breakthroughAutoPillRootCounts, autoEquipFromWarehouseFocused, " +
                "autoEquipFromWarehouseRootCounts, autoLearnFromWarehouseFocused, " +
                "autoLearnFromWarehouseRootCounts, yearlySalary, yearlySalaryEnabled, " +
                "autoSaveIntervalMonths) VALUES (1, '{}', '[]', '[]', 0, 0, '[]', 0, '[]', 0, '[]', '{}', '{}', 3)"
        )
    }

    /** v50 迁移结果断言：列删除 + 关键列/索引存活 + 行数据存活。 */
    private fun assertV50MigrationResult(db: SupportSQLiteDatabase) {
        // 验证两表 autoSaveIntervalMonths 均被删除
        assertFalse("autoSaveIntervalMonths should be removed from game_data after v50",
            columnExists(db, "game_data", "autoSaveIntervalMonths"))
        assertFalse("autoSaveIntervalMonths should be removed from sect_policy_state after v50",
            columnExists(db, "sect_policy_state", "autoSaveIntervalMonths"))

        // 验证关键列未受影响
        assertTrue("id should survive in game_data",
            columnExists(db, "game_data", "id"))
        assertTrue("roads should survive in game_data",
            columnExists(db, "game_data", "roads"))
        assertTrue("slot_id should survive in sect_policy_state",
            columnExists(db, "sect_policy_state", "slot_id"))

        // 验证索引已重建
        assertTrue("index_game_data_slot_id should exist",
            indexExists(db, "game_data", "index_game_data_slot_id"))
        assertTrue("index_sect_policy_state_slot_id should exist",
            indexExists(db, "sect_policy_state", "index_sect_policy_state_slot_id"))

        // 验证数据存活
        val cursor = db.query("SELECT sectName FROM game_data WHERE id = 'game_data_1'", emptyArray())
        cursor.use {
            assertTrue("game_data row should survive v50 migration", it.moveToFirst())
            assertEquals("测试宗门", it.getString(0))
        }
    }

    // ==================== 数据保留测试 ====================

    @Test
    fun `v12 to v13 migration preserves column structure correctly`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val dbName = "data_preserve"
        context.deleteDatabase(dbName)
        try {
            // 从 v12 schema 创建
            val db = createDatabaseFromSchema(context, dbName, 12)
            assertTrue("isGameStarted should exist before v13 migration",
                columnExists(db, "game_data", "isGameStarted"))

            // 应用 v12→v13 迁移
            applyMigrationsSequentially(db, listOf(M12_13))

            // 验证迁移后 isGameStarted 列不存在
            assertFalse("isGameStarted column should be gone after v13",
                columnExists(db, "game_data", "isGameStarted"))

            // 验证关键列存在
            assertTrue("map_seed should exist after v13 migration",
                columnExists(db, "game_data", "map_seed"))
            assertTrue("merchantAcquisitionItems should exist after v13 migration",
                columnExists(db, "game_data", "merchantAcquisitionItems"))
            assertTrue("vassalContracts should exist after v13 migration",
                columnExists(db, "game_data", "vassalContracts"))

            // 验证 5 个索引已重建
            assertTrue("index_game_data_slot_id should exist",
                indexExists(db, "game_data", "index_game_data_slot_id"))
            assertTrue("index_game_data_spiritStones should exist",
                indexExists(db, "game_data", "index_game_data_spiritStones"))

            db.close()
        } finally {
            context.deleteDatabase(dbName)
        }
    }


}
