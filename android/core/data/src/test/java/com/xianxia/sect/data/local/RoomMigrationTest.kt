package com.xianxia.sect.data.local

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

/**
 * Room 数据库迁移测试。
 *
 * 使用 schema JSON 创建初始数据库，直接执行每个 Migration 的 migrate 函数，
 * 验证新列添加正确、列删除正确，且不崩溃。
 *
 * 2026-08-04 补充：直接执行 migrate() 不会触发 Room 的迁移后校验（onValidateSchema），
 * 陈旧列/缺索引类缺陷（如 v39 no-op 迁移崩溃）漏网——新增 `passes real Room schema validation`
 * 系列测试，通过 Room.databaseBuilder 真实打开库强制校验。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class RoomMigrationTest {

    companion object {
        /** Schema 文件所在目录（相对于模块根目录） */
        private val SCHEMA_DIR: File = File(
            "schemas",
            "com.xianxia.sect.data.local.GameDatabase"
        )

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

        /** v44 新增的弟子职业 4 列（disciples 与 disciples_attributes 两表共用） */
        private val PROFESSION_COLUMNS = listOf(
            "alchemyLevel", "alchemyPromotionCount", "forgeLevel", "forgePromotionCount"
        )

        // v43 种子行（disciples 表 101 列，列清单与 affinity 来自 43.json；isAlive=1 便于断言存活）
        private val SEED_DISCIPLES_V43 = """
            INSERT INTO disciples (
                    id,                     slot_id,                     name,                     surname,
                    realm,                     realmLayer,                     cultivation,                     cultivationCheckpoint,
                    cultivationCheckpointGameMonth,                     spiritRootType,                     age,                     lifespan,
                    isAlive,                     gender,                     portraitRes,                     manualIds,
                    talentIds,                     physiqueIds,                     affixIds,                     manualMasteries,
                    status,                     statusData,                     cultivationSpeedBonus,                     cultivationSpeedDuration,
                    discipleType,                     soulPower,                     cultivationCompletionMonth,                     cultivationCompletionPhase,
                    manualCompletionMonth,                     manualCompletionPhase,                     equipmentNurturingCompletionMonth,                     equipmentNurturingCompletionPhase,
                    baseHp,                     baseMp,                     basePhysicalAttack,                     baseMagicAttack,
                    basePhysicalDefense,                     baseMagicDefense,                     baseSpeed,                     hpVariance,
                    mpVariance,                     physicalAttackVariance,                     magicAttackVariance,                     physicalDefenseVariance,
                    magicDefenseVariance,                     speedVariance,                     totalCultivation,                     breakthroughCount,
                    breakthroughFailCount,                     currentHp,                     currentMp,                     pillPhysicalAttackBonus,
                    pillMagicAttackBonus,                     pillPhysicalDefenseBonus,                     pillMagicDefenseBonus,                     pillHpBonus,
                    pillMpBonus,                     pillSpeedBonus,                     pillCritRateBonus,                     pillCritEffectBonus,
                    pillCultivationSpeedBonus,                     pillSkillExpSpeedBonus,                     pillNurtureSpeedBonus,                     pillEffectDuration,
                    activePillCategory,                     weaponId,                     armorId,                     bootsId,
                    accessoryId,                     weaponNurture,                     armorNurture,                     bootsNurture,
                    accessoryNurture,                     storageBagItems,                     storageBagSpiritStones,                     spiritStones,
                    social_partnerId,                     social_partnerSectId,                     social_parentId1,                     social_parentId2,
                    social_lastChildYear,                     social_childBirthMonth,                     social_griefEndYear,                     social_masterId,
                    intelligence,                     charm,                     loyalty,                     comprehension,
                    artifactRefining,                     pillRefining,                     spiritPlanting,                     mining,
                    teaching,                     morality,                     salaryPaidCount,                     salaryMissedCount,
                    usage_usedFunctionalPillTypes,                     usage_usedExtendLifePillIds,                     usage_recruitedMonth,                     usage_hasReviveEffect,
                    usage_hasClearAllEffect
                ) VALUES (
                    'd1',                     1,                     '测试弟子',                     '',
                    0,                     0,                     0.0,                     0.0,
                    0,                     '',                     0,                     0,
                    1,                     '',                     '',                     '',
                    '',                     '',                     '',                     '',
                    '',                     '',                     0.0,                     0,
                    '',                     0,                     0,                     0,
                    0,                     0,                     0,                     0,
                    0,                     0,                     0,                     0,
                    0,                     0,                     0,                     0,
                    0,                     0,                     0,                     0,
                    0,                     0,                     0,                     0,
                    0,                     0,                     0,                     0,
                    0,                     0,                     0,                     0,
                    0,                     0,                     0.0,                     0.0,
                    0.0,                     0.0,                     0.0,                     0,
                    '',                     '',                     '',                     '',
                    '',                     '',                     '',                     '',
                    '',                     '',                     0,                     0,
                    '',                     '',                     '',                     '',
                    0,                     0,                     0,                     '',
                    0,                     0,                     0,                     0,
                    0,                     0,                     0,                     0,
                    0,                     0,                     0,                     0,
                    '',                     '',                     0,                     0,
                    0
                )
        """.trimIndent()

        /** v44 种子行：v43 基础上补职业 4 列（alchemyLevel 等 NOT NULL 无默认值） */
        private val SEED_DISCIPLES_V44: String = SEED_DISCIPLES_V43
            .replace(
                "usage_hasClearAllEffect\n",
                "alchemyLevel, alchemyPromotionCount, forgeLevel, forgePromotionCount, usage_hasClearAllEffect\n"
            )
            .replace(
                "\n        0\n    )",
                "\n        0,                     0,                     0,                     0,\n        0\n    )"
            )

        private val SEED_DISCIPLES_ATTRIBUTES_V43 = """
            INSERT INTO disciples_attributes (
                    discipleId, slot_id, intelligence, charm,
                    loyalty, comprehension, artifactRefining, pillRefining,
                    spiritPlanting, mining, teaching, morality,
                    salaryPaidCount, salaryMissedCount
                ) VALUES (
                    'd1', 1, 10, 20,
                    30, 40, 50, 60,
                    70, 80, 90, 100,
                    2, 3
                )
        """.trimIndent()

        // v38 种子行（含被删除的 auto 开关列；列清单来自 38.json，仅用于迁移重建测试）
        private val SEED_DISCIPLES_V38 = """
            INSERT INTO disciples (
                    id, slot_id, name, surname,
                    realm, realmLayer, cultivation, cultivationCheckpoint,
                    cultivationCheckpointGameMonth, spiritRootType, age, lifespan,
                    isAlive, gender, portraitRes, manualIds,
                    talentIds, physiqueIds, affixIds, manualMasteries,
                    status, statusData, cultivationSpeedBonus, cultivationSpeedDuration,
                    discipleType, autoLearnFromWarehouse, soulPower, cultivationCompletionMonth,
                    cultivationCompletionPhase, manualCompletionMonth, manualCompletionPhase, equipmentNurturingCompletionMonth,
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
        // 2026-08-04 修复：原 no-op 保留旧列版本在 Room 2.7 迁移后校验（列全等比较）崩溃。
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
     * 2026-08-04 回归防线：原 no-op 保留旧列迁移在此抛
     * "Migration didn't properly handle: disciples"（Room 2.7.0 TableInfo 列全等比较），
     * 而旧的迁移测试直接执行 migrate() SQL、从不通过 Room 打开库，无法发现该崩溃。
     */
    @Test
    fun `MIGRATION_38_TO_39 passes real Room schema validation`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val dbName = "m_38_39_room_validate"
        context.deleteDatabase(dbName)
        try {
            createDatabaseFromSchema(context, dbName, 38).close()
            val db = Room.databaseBuilder(context, GameDatabase::class.java, dbName)
                // 实体当前版本 42：仅注册 M38_39 时 Room 要求 38→42 迁移路径
                .addMigrations(M38_39, M39_40, M40_41, M41_42, M42_43, M43_44, M44_45)
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
                    M44_45
                )
                .build()
            roomDb.openHelper.writableDatabase
            roomDb.close()
        } finally {
            context.deleteDatabase(dbName)
        }
    }

    /**
     * 真实 Room 校验：v39 库升级到 v40（A3 战斗队伍持久化三列），
     * 触发 onValidateSchema——任何列定义与实体注解不一致都会在此崩溃
     * （对齐 v38→v39 的 no-op 迁移崩溃回归防线模式）。
     */
    @Test
    fun `MIGRATION_39_TO_40 passes real Room schema validation`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val dbName = "m_39_40_room_validate"
        context.deleteDatabase(dbName)
        try {
            createDatabaseFromSchema(context, dbName, 39).close()
            val db = Room.databaseBuilder(context, GameDatabase::class.java, dbName)
                // 实体当前版本 42：完整迁移路径 39→42
                .addMigrations(M39_40, M40_41, M41_42, M42_43, M43_44, M44_45)
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
                .addMigrations(M40_41, M41_42, M42_43, M43_44, M44_45)
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
                .addMigrations(M41_42, M42_43, M43_44, M44_45)
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
                .addMigrations(M42_43, M43_44, M44_45)
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

    /**
     * 真实 Room 校验：v43 库升级到 v44（弟子炼丹师/锻造师职业 8 列），
     * 触发 onValidateSchema——任何列定义与实体注解不一致都会在此崩溃。
     */
    @Test
    fun `MIGRATION_43_TO_44 passes real Room schema validation`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val dbName = "m_43_44_room_validate"
        context.deleteDatabase(dbName)
        try {
            createDatabaseFromSchema(context, dbName, 43).close()
            val db = Room.databaseBuilder(context, GameDatabase::class.java, dbName)
                .addMigrations(M43_44, M44_45)
                .build()
            db.openHelper.writableDatabase
            db.close()
        } finally {
            context.deleteDatabase(dbName)
        }
    }

    @Test
    fun `MIGRATION_43_TO_44 adds 8 profession columns keeps old data`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val dbName = "m_43_44_columns"
        context.deleteDatabase(dbName)
        try {
            val db = createDatabaseFromSchema(context, dbName, 43)
            db.execSQL(SEED_DISCIPLES_V43)
            db.execSQL(SEED_DISCIPLES_ATTRIBUTES_V43)
            // 手动执行迁移 SQL（真实 Room 校验由上一测试覆盖）
            listOf(M43_44).forEach { it.migrate(db) }

            // 两表各 4 列存在且 DEFAULT 0（旧行自动回填无职业）
            for (table in listOf("disciples", "disciples_attributes")) {
                assertProfessionColumns(db, table)
            }

            // 旧数据保留
            val disciple = db.query("SELECT name, isAlive FROM disciples WHERE id = 'd1'").use { c ->
                c.moveToFirst(); c.getString(0) to c.getInt(1)
            }
            assertEquals("旧弟子数据保留", "测试弟子" to 1, disciple)
            val pillRefining = db.query(
                "SELECT pillRefining FROM disciples_attributes WHERE discipleId = 'd1'"
            ).use { c ->
                c.moveToFirst(); c.getInt(0)
            }
            assertEquals("旧属性数据保留", 60, pillRefining)
            db.close()
        } finally {
            context.deleteDatabase(dbName)
        }
    }

    /**
     * 真实 Room 校验：v44 库升级到 v45（世界地图探索队 exploration_teams 表删除），
     * 触发 onValidateSchema——表仍存在或列定义不一致都会在此崩溃。
     */
    @Test
    fun `MIGRATION_44_TO_45 passes real Room schema validation`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val dbName = "m_44_45_room_validate"
        context.deleteDatabase(dbName)
        try {
            createDatabaseFromSchema(context, dbName, 44).close()
            val db = Room.databaseBuilder(context, GameDatabase::class.java, dbName)
                .addMigrations(M44_45)
                .build()
            db.openHelper.writableDatabase
            db.close()
        } finally {
            context.deleteDatabase(dbName)
        }
    }

    @Test
    fun `MIGRATION_44_TO_45 drops exploration_teams table keeps other data`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val dbName = "m_44_45_drop_table"
        context.deleteDatabase(dbName)
        try {
            val db = createDatabaseFromSchema(context, dbName, 44)
            // v44 库 disciples 表含职业 4 列（NOT NULL 无默认），用补齐后的种子行
            db.execSQL(SEED_DISCIPLES_V44)
            // v44 库含 exploration_teams 表（世界地图探索队，已下线）——seed 一行验证删除
            db.execSQL(
                """
                INSERT INTO exploration_teams (
                    id, slot_id, name, caveName, dungeon, dungeonName, memberIds, memberNames,
                    startYear, startMonth, startDay, duration, status, progress, scoutTargetSectName,
                    currentX, currentY, targetX, targetY, moveProgress, arrivalYear, arrivalMonth,
                    arrivalDay, route, currentRouteIndex, currentSegmentProgress,
                    pityCounterEquipment, pityCounterPill, pityCounterManual
                ) VALUES (
                    't1', 1, '探索队', '', '', '', '["d1"]', '["弟子1"]',
                    2026, 1, 1, 30, 'EXPLORING', 50, '',
                    0.0, 0.0, 0.0, 0.0, 0.5, 2026, 2,
                    1, '[]', 0, 0.0,
                    0, 0, 0
                )
                """
            )
            // 手动执行迁移 SQL（真实 Room 校验由上一测试覆盖）
            listOf(M44_45).forEach { it.migrate(db) }

            // 表已删除
            val tableExists = db.query(
                "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='exploration_teams'"
            ).use { c -> c.moveToFirst(); c.getInt(0) }
            assertEquals("exploration_teams 表应被删除", 0, tableExists)

            // 其它数据保留
            val disciple = db.query("SELECT name, isAlive FROM disciples WHERE id = 'd1'").use { c ->
                c.moveToFirst(); c.getString(0) to c.getInt(1)
            }
            assertEquals("旧弟子数据保留", "测试弟子" to 1, disciple)
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
    fun `MIGRATION_2_TO_3 adds sectLevelClaimRecords to game_data`() {
        testSingleMigration("m_2_3", 2, 3, listOf(M2_3), "game_data", "sectLevelClaimRecords")
    }

    @Test
    fun `MIGRATION_3_TO_4 adds save_version to game_data`() {
        testSingleMigration("m_3_4", 2, 4, listOf(M2_3, M3_4), "game_data", "save_version")
    }

    @Test
    fun `MIGRATION_4_TO_5 adds autoBuyList to game_data`() {
        testSingleMigration("m_4_5", 2, 5, listOf(M2_3, M3_4, M4_5), "game_data", "autoBuyList")
    }

    @Test
    fun `MIGRATION_5_TO_6 adds bloodRefinementBonusTotals to game_data`() {
        testSingleMigration(
            "m_5_6_gd", 2, 6, listOf(M2_3, M3_4, M4_5, M5_6), "game_data", "bloodRefinementBonusTotals"
        )
    }

    @Test
    fun `MIGRATION_5_TO_6 adds usage_lastTheftMonth to disciples`() {
        testSingleMigration(
            "m_5_6_d", 2, 6, listOf(M2_3, M3_4, M4_5, M5_6), "disciples", "usage_lastTheftMonth"
        )
    }

    @Test
    fun `MIGRATION_5_TO_6 adds buildingInstanceId to production_slots`() {
        testSingleMigration(
            "m_5_6_ps", 2, 6, listOf(M2_3, M3_4, M4_5, M5_6), "production_slots", "buildingInstanceId"
        )
    }

    @Test
    fun `MIGRATION_6_TO_7 adds aiSectPersonalities to game_data`() {
        testSingleMigration(
            "m_6_7", 2, 7, listOf(M2_3, M3_4, M4_5, M5_6, M6_7), "game_data", "aiSectPersonalities"
        )
    }

    @Test
    fun `MIGRATION_7_TO_8 adds midGradeSpiritStones to game_data`() {
        testSingleMigration(
            "m_7_8", 2, 8, listOf(M2_3, M3_4, M4_5, M5_6, M6_7, M7_8),
            "game_data", "midGradeSpiritStones"
        )
    }

    @Test
    fun `MIGRATION_8_TO_9 adds autoSellMidGradeForPurchase to game_data`() {
        testSingleMigration(
            "m_8_9", 2, 9, listOf(M2_3, M3_4, M4_5, M5_6, M6_7, M7_8, M8_9),
            "game_data", "autoSellMidGradeForPurchase"
        )
    }

    @Test
    fun `MIGRATION_9_TO_10 adds social_masterId to disciples`() {
        testSingleMigration(
            "m_9_10", 2, 10, listOf(M2_3, M3_4, M4_5, M5_6, M6_7, M7_8, M8_9, M9_10),
            "disciples", "social_masterId"
        )
    }

    @Test
    fun `MIGRATION_9_TO_10 adds masterId to disciples_extended`() {
        testSingleMigration(
            "m_9_10_ex", 2, 10, listOf(M2_3, M3_4, M4_5, M5_6, M6_7, M7_8, M8_9, M9_10),
            "disciples_extended", "masterId"
        )
    }

    @Test
    fun `MIGRATION_10_TO_11 adds vassalContracts to game_data`() {
        testSingleMigration(
            "m_10_11", 2, 11,
            listOf(M2_3, M3_4, M4_5, M5_6, M6_7, M7_8, M8_9, M9_10, M10_11),
            "game_data", "vassalContracts"
        )
    }

    @Test
    fun `MIGRATION_11_TO_12 adds map_seed to game_data`() {
        testSingleMigration(
            "m_11_12", 2, 12,
            listOf(M2_3, M3_4, M4_5, M5_6, M6_7, M7_8, M8_9, M9_10, M10_11, M11_12),
            "game_data", "map_seed"
        )
    }

    @Test
    fun `MIGRATION_12_TO_13 removes isGameStarted from game_data`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val dbName = "m_12_13_remove"
        context.deleteDatabase(dbName)
        try {
            // 从 v12 schema 创建（跳过早期版本缺少 merchantAcquisitionItems 等列的问题）
            val db = createDatabaseFromSchema(context, dbName, 12)

            // 验证 isGameStarted 列在 v12 中存在
            assertTrue("isGameStarted should exist before v13 migration",
                columnExists(db, "game_data", "isGameStarted"))

            // 应用 v12→v13 迁移
            applyMigrationsSequentially(db, listOf(M12_13))

            // 验证 isGameStarted 列已被删除
            assertFalse("isGameStarted should be removed after v13 migration",
                columnExists(db, "game_data", "isGameStarted"))

            db.close()
        } finally {
            context.deleteDatabase(dbName)
        }
    }

    @Test
    fun `MIGRATION_13_TO_14 adds cultivationCheckpoint to disciples`() {
        testSingleMigration(
            "m_13_14_cp", 13, 14, listOf(M13_14), "disciples", "cultivationCheckpoint"
        )
    }

    @Test
    fun `MIGRATION_13_TO_14 adds cultivationCheckpointGameMonth to disciples`() {
        testSingleMigration(
            "m_13_14_cpm", 13, 14, listOf(M13_14), "disciples", "cultivationCheckpointGameMonth"
        )
    }

    @Test
    fun `MIGRATION_14_TO_15 adds discipleDesertionPopup to game_data`() {
        testSingleMigration(
            "m_14_15", 14, 15, listOf(M14_15), "game_data", "discipleDesertionPopup"
        )
    }

    @Test
    fun `MIGRATION_15_TO_16 adds showAllAvailableDisciples to game_data`() {
        testSingleMigration(
            "m_15_16", 15, 16, listOf(M15_16), "game_data", "showAllAvailableDisciples"
        )
    }

    @Test
    fun `MIGRATION_22_TO_23 removes discipleDesertionPopup from game_data`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val dbName = "m_22_23_remove"
        context.deleteDatabase(dbName)
        try {
            val db = createDatabaseFromSchema(context, dbName, 22)

            assertTrue("discipleDesertionPopup should exist before v23 migration",
                columnExists(db, "game_data", "discipleDesertionPopup"))
            // 验证 v22 schema 有 PRIMARY KEY
            assertTrue("PK should exist before v23 migration",
                primaryKeyExists(db, "game_data"))

            applyMigrationsSequentially(db, listOf(M22_23))

            assertFalse("discipleDesertionPopup should be removed after v23 migration",
                columnExists(db, "game_data", "discipleDesertionPopup"))
            // 验证修复后的 M22_23 仍保留约束
            assertTrue("PRIMARY KEY should be preserved after v23 migration",
                primaryKeyExists(db, "game_data"))
            assertTrue("NOT NULL should be preserved on sectName",
                columnIsNotNull(db, "game_data", "sectName"))
            assertEquals("DEFAULT should be preserved on save_version",
                "0", columnDefault(db, "game_data", "save_version"))
            // 验证索引重建
            assertTrue("index_game_data_slot_id should exist after v23 migration",
                indexExists(db, "game_data", "index_game_data_slot_id"))

            db.close()
        } finally {
            context.deleteDatabase(dbName)
        }
    }

    @Test
    fun `MIGRATION_23_TO_24 rebuilds storage_bags with composite primary key`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val dbName = "m_23_24_pk"
        context.deleteDatabase(dbName)
        try {
            // 从 v23 schema 创建
            val db = createDatabaseFromSchema(context, dbName, 23)

            // 验证迁移前 storage_bags 表存在
            assertTrue("storage_bags should exist before v24 migration",
                tableExists(db, "storage_bags"))

            // 写入一条测试数据（旧 PK 模式下 slot_id 为 0）
            db.execSQL("INSERT INTO storage_bags (id, slot_id, name, rarity, description, quantity, isLocked) VALUES ('test_id_1', 0, '测试储物袋', 1, '', 1, 0)")

            // 应用 v23→v24 迁移
            applyMigrationsSequentially(db, listOf(M23_24))

            // 验证迁移后表存在
            assertTrue("storage_bags should exist after v24 migration",
                tableExists(db, "storage_bags"))

            // 验证 slot_id 从 0 被修复为 1
            val cursor = db.query("SELECT slot_id FROM storage_bags WHERE id = 'test_id_1'", emptyArray())
            cursor.use {
                assertTrue("Row should exist", it.moveToFirst())
                val slotId = it.getInt(it.getColumnIndexOrThrow("slot_id"))
                assertEquals("slot_id should be migrated from 0 to 1", 1, slotId)
            }

            // 验证复合主键：相同 id 不同 slot_id 可以同时存在
            db.execSQL("INSERT INTO storage_bags (id, slot_id, name, rarity, description, quantity, isLocked) VALUES ('test_id_1', 2, '跨槽位测试', 1, '', 1, 0)")
            val cursor2 = db.query("SELECT count(*) FROM storage_bags WHERE id = 'test_id_1'", emptyArray())
            cursor2.use {
                assertTrue("Row should exist", it.moveToFirst())
                val count = it.getInt(0)
                assertEquals("Two rows with same id but different slot_id should coexist", 2, count)
            }

            // 验证索引存在
            assertTrue("index_storage_bags_slot_id should exist after v24 migration",
                indexExists(db, "storage_bags", "index_storage_bags_slot_id"))

            db.close()
        } finally {
            context.deleteDatabase(dbName)
        }
    }

    @Test
    fun `MIGRATION_24_TO_25 fixes broken constraints from CTAS on game_data`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val dbName = "m_24_25_fix"
        context.deleteDatabase(dbName)
        try {
            // Step 1: 从 v22 schema 创建数据库（包含 discipleDesertionPopup 列）
            val db = createDatabaseFromSchema(context, dbName, 22)

            // Step 2: 模拟旧版 MIGRATION_22_23 的 CTAS 行为（故意破坏约束）
            val columnsBefore = mutableListOf<String>()
            var c1 = db.query("PRAGMA table_info(game_data)")
            c1.use {
                while (it.moveToNext()) {
                    val name = it.getString(it.getColumnIndexOrThrow("name"))
                    if (name != "discipleDesertionPopup") {
                        columnsBefore.add("\"$name\"")
                    }
                }
            }
            val colList = columnsBefore.joinToString(", ")
            db.execSQL("ALTER TABLE game_data RENAME TO game_data_old")
            db.execSQL("CREATE TABLE game_data AS SELECT $colList FROM game_data_old")
            db.execSQL("DROP TABLE game_data_old")
            db.execSQL("PRAGMA user_version = 23")

            // Step 3: 确认 CTAS 后约束被破坏
            // 验证 PRIMARY KEY 不存在
            assertFalse("PK should be lost after CTAS",
                primaryKeyExists(db, "game_data"))
            // 验证一些列的 NOT NULL 被丢失
            assertTrue("game_data should still have sectName column",
                columnExists(db, "game_data", "sectName"))

            // Step 4: 应用 MIGRATION_23_24 和 MIGRATION_24_25
            applyMigrationsSequentially(db, listOf(M23_24, M24_25))

            // Step 5: 验证修复后约束已恢复
            // 验证 PRIMARY KEY 已重建
            assertTrue("PRIMARY KEY should be restored after v25 migration",
                primaryKeyExists(db, "game_data"))
            // 验证 NOT NULL 约束已恢复
            assertTrue("sectName should have NOT NULL after v25 migration",
                columnIsNotNull(db, "game_data", "sectName"))
            assertTrue("spiritStones should have NOT NULL after v25 migration",
                columnIsNotNull(db, "game_data", "spiritStones"))
            // 验证 DEFAULT 值已恢复
            assertEquals("save_version should have DEFAULT 0",
                "0", columnDefault(db, "game_data", "save_version"))
            assertEquals("bloodRefinements should have DEFAULT '{}'",
                "'{}'", columnDefault(db, "game_data", "bloodRefinements"))
            assertEquals("map_seed should have DEFAULT 0",
                "0", columnDefault(db, "game_data", "map_seed"))
            // 验证 discipleDesertionPopup 列仍被排除
            assertFalse("discipleDesertionPopup should not exist after v25 migration",
                columnExists(db, "game_data", "discipleDesertionPopup"))
            // 验证所有 5 个索引已重建
            assertTrue("index_game_data_slot_id should exist",
                indexExists(db, "game_data", "index_game_data_slot_id"))
            assertTrue("index_game_data_lastSaveTime should exist",
                indexExists(db, "game_data", "index_game_data_lastSaveTime"))
            assertTrue("index_game_data_gameYear_gameMonth should exist",
                indexExists(db, "game_data", "index_game_data_gameYear_gameMonth"))
            assertTrue("index_game_data_sectName should exist",
                indexExists(db, "game_data", "index_game_data_sectName"))
            assertTrue("index_game_data_spiritStones should exist",
                indexExists(db, "game_data", "index_game_data_spiritStones"))

            db.close()
        } finally {
            context.deleteDatabase(dbName)
        }
    }

    // ==================== 全量迁移测试 ====================

    @Test
    fun `full migration from v2 to v39 applies all steps without crash and preserves seed data`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val dbName = "full_migrate_v39"
        context.deleteDatabase(dbName)
        try {
            // 2026-08-01 修复：全链迁移从 v2 起点覆盖全部 34 条迁移。
            // 旧测试跳过 v2→v12 段，导致 MIGRATION_12_13 引用缺失列
            // （merchantAcquisitionItems）的升级崩溃缺陷未被发现。
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
            val sectName = db.query("SELECT sectName, gameYear, gameMonth, spiritStones FROM game_data WHERE slot_id = 1").use { cursor ->
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

    // ==================== 新增迁移测试（M16→M32，补齐覆盖缺口） ====================

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

            // 覆盖关键字段为测试值
            val testSectName = "TestSect_Chain_21_32"
            val idIdx = columns.indexOf("id"); if (idIdx >= 0) values[idIdx] = "'gd_chain'"
            val sidIdx = columns.indexOf("slot_id"); if (sidIdx >= 0) values[sidIdx] = "1"
            val snIdx = columns.indexOf("sectName"); if (snIdx >= 0) values[snIdx] = "'$testSectName'"
            val csIdx = columns.indexOf("currentSlot"); if (csIdx >= 0) values[csIdx] = "1"
            val gyIdx = columns.indexOf("gameYear"); if (gyIdx >= 0) values[gyIdx] = "42"
            val gmIdx = columns.indexOf("gameMonth"); if (gmIdx >= 0) values[gmIdx] = "6"
            val gpIdx = columns.indexOf("gamePhase"); if (gpIdx >= 0) values[gpIdx] = "0"

            val insertSql = "INSERT INTO game_data (${columns.joinToString(",")}) VALUES (${values.joinToString(",")})"
            db.execSQL(insertSql)

            // 验证数据已写入
            var cursor = db.query("SELECT sectName FROM game_data WHERE id = 'gd_chain'", emptyArray())
            cursor.use {
                assertTrue("test data should exist", it.moveToFirst())
                assertEquals(testSectName, it.getString(0))
            }

            // 按顺序运行 M21_22 → M22_23 → ... → M32_33（12 步迁移）
            // 2026-08-04 修复：补上缺失的 M30_31（原列表漏掉，链测试未覆盖该步）
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

    // ==================== 辅助方法 ====================

    /**
     * 测试单个迁移：从 v2 开始，应用一系列迁移，验证目标列存在/不存在。
     */
    private fun testSingleMigration(
        dbName: String,
        fromSchemaVersion: Int,
        toVersion: Int,
        migrations: List<Migration>,
        tableName: String,
        expectedColumn: String
    ) {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        context.deleteDatabase(dbName)
        try {
            val db = createDatabaseFromSchema(context, dbName, fromSchemaVersion)
            applyMigrationsSequentially(db, migrations)
            assertTrue(
                "Column '$expectedColumn' should exist in '$tableName' after migration",
                columnExists(db, tableName, expectedColumn)
            )
            db.close()
        } finally {
            context.deleteDatabase(dbName)
        }
    }

    /**
     * 从 schema JSON 文件创建数据库（创建所有表 + 索引）。
     */
    private fun createDatabaseFromSchema(
        context: android.content.Context,
        dbName: String,
        version: Int
    ): SupportSQLiteDatabase {
        val factory = FrameworkSQLiteOpenHelperFactory()
        val helper = factory.create(
            androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbName)
                .callback(object : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(version) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        val schemaFile = File(SCHEMA_DIR, "${version}.json")
                        val json = JsonParser.parseString(schemaFile.readText()).asJsonObject
                        val database = json.getAsJsonObject("database")
                        val entities = database.getAsJsonArray("entities")

                        for (i in 0 until entities.size()) {
                            val entity = entities[i].asJsonObject
                            val createSql = entity.get("createSql").asString
                            val tableName = entity.get("tableName").asString
                            db.execSQL(createSql.replace("\${TABLE_NAME}", tableName))

                            // 创建索引（部分实体可能没有索引）
                            val indices = entity.getAsJsonArray("indices")
                            if (indices != null) {
                                for (j in 0 until indices.size()) {
                                    val indexSql = indices[j].asJsonObject.get("createSql").asString
                                    db.execSQL(indexSql.replace("\${TABLE_NAME}", tableName))
                                }
                            }
                        }
                        db.execSQL("PRAGMA user_version = $version")
                    }

                    override fun onUpgrade(
                        db: SupportSQLiteDatabase,
                        oldVersion: Int,
                        newVersion: Int
                    ) {}
                })
                .build()
        )
        return helper.writableDatabase
    }

    /**
     * 按顺序应用迁移，每次更新 PRAGMA user_version。
     */
    private fun applyMigrationsSequentially(
        db: SupportSQLiteDatabase,
        migrations: List<Migration>
    ) {
        for (migration in migrations) {
            db.beginTransaction()
            try {
                migration.migrate(db)
                db.execSQL("PRAGMA user_version = ${migration.endVersion}")
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
    }

    /** 插入最小 game_data 行（NOT NULL 列都填写默认等价的值，匹配 v2 schema 列清单） */
    private fun insertMinimalGameDataRow(
        db: SupportSQLiteDatabase,
        id: String,
        slotId: Int
    ) {
        db.execSQL(
            """INSERT INTO game_data (
                id, slot_id, sectName, currentSlot, gameYear, gameMonth, gamePhase,
                isGameStarted, gameSpeed, spiritStones, spiritHerbs, sectCultivation,
                autoSaveIntervalMonths, monthlySalary, monthlySalaryEnabled,
                worldMapSects, sectDetails, aiSectDisciples, exploredSects, scoutInfo,
                manualProficiencies, travelingMerchantItems, merchantLastRefreshYear,
                merchantRefreshCount, playerListedItems, recruitList, lastRecruitYear,
                worldLevels, cultivatorCaves, caveExplorationTeams, aiCaveTeams,
                unlockedRecipes, unlockedManuals, lastSaveTime, elderSlots,
                spiritMineSlots, spiritMineExpansions, librarySlots, productionSlots,
                placedBuildings, spiritFieldPlants, activeSectId, residenceSlots,
                warehouseGarrisons, patrolSlots, patrolConfig, patrolConfigs,
                alliances, sectRelations, playerAllianceSlots, sectPolicies,
                battleTeam, aiBattleTeams, usedRedeemCodes, claimedMailIds,
                playerProtectionEnabled, playerProtectionStartYear, playerHasAttackedAI,
                activeMissions, availableMissions, autoRecruitSpiritRootFilter,
                daoCompanionBannedRootCounts, daoCompanionConsentRequired,
                patrolBattleResultPopup, breakthroughAutoPillFocused,
                breakthroughAutoPillRootCounts, autoEquipFromWarehouseFocused,
                autoEquipFromWarehouseRootCounts, autoLearnFromWarehouseFocused,
                autoLearnFromWarehouseRootCounts, isGameOver,
                bloodRefinements, activeBloodRefinements
            ) VALUES (
                '$id', $slotId, 'TestSect', 1, 1, 1, 0,
                1, 1, 1000, 0, 0.0,
                3, '{}', '{}',
                '[]', '{}', '[]', '{}', '{}',
                '{}', '[]', 0,
                0, '[]', '[]', 0,
                '[]', '[]', '[]', '[]',
                '[]', '[]', 0, '{}',
                '[]', 0, '[]', '[]',
                '[]', '[]', '', '[]',
                '[]', '[]', '{}', '{}',
                '[]', '[]', 3, '{}',
                NULL, '[]', '[]', '[]',
                1, 1, 0,
                '[]', '[]', '[]',
                '[]', 0,
                0, 0,
                '[]', 0,
                '[]', 0,
                '[]', 0,
                '{}', '{}'
            )""".trimIndent()
        )
    }

    /** 插入 v12 完整 game_data 行（包含所有列，排除 isGameStarted） */
    /** 查询指定表是否存在 */
    private fun tableExists(
        db: SupportSQLiteDatabase,
        table: String
    ): Boolean {
        val cursor = db.query("PRAGMA table_info($table)", emptyArray())
        return cursor.use { it.moveToFirst() }
    }

    /** 查询 PRAGMA table_info 返回列名有序列表 */
    private fun tableColumns(db: SupportSQLiteDatabase, table: String): List<String> {
        val cursor = db.query("PRAGMA table_info($table)", emptyArray())
        return cursor.use {
            val cols = mutableListOf<String>()
            while (it.moveToNext()) {
                cols.add(it.getString(it.getColumnIndexOrThrow("name")))
            }
            cols
        }
    }

    /**
     * 断言职业 4 列在表中存在且 DEFAULT 0（v44 迁移专用，减少测试嵌套深度）。
     */
    private fun assertProfessionColumns(db: SupportSQLiteDatabase, table: String) {
        val defaults = db.query("PRAGMA table_info($table)").use { cursor ->
            val map = mutableMapOf<String, String?>()
            while (cursor.moveToNext()) {
                map[cursor.getString(1)] = cursor.getString(4)
            }
            map
        }
        for (col in PROFESSION_COLUMNS) {
            assertTrue("$table 缺职业列 $col", columnExists(db, table, col))
            assertEquals("$table.$col 默认 0", "0", defaults[col])
        }
    }

    /** 查询 PRAGMA table_info 检查列是否存在 */
    private fun columnExists(
        db: SupportSQLiteDatabase,
        table: String,
        column: String
    ): Boolean {
        val cursor = db.query("PRAGMA table_info($table)", emptyArray())
        return cursor.use {
            while (it.moveToNext()) {
                val name = it.getString(it.getColumnIndexOrThrow("name"))
                if (name == column) return@use true
            }
            false
        }
    }

    /** 查询单列字符串结果（首行首列；无行返回空串） */
    private fun queryString(db: SupportSQLiteDatabase, sql: String): String {
        return db.query(sql, emptyArray()).use { cursor ->
            var result = ""
            if (cursor.moveToFirst()) result = cursor.getString(0)
            result
        }
    }

    /** 查询指定表的索引是否存在 */
    private fun indexExists(
        db: SupportSQLiteDatabase,
        table: String,
        indexName: String
    ): Boolean {
        val cursor = db.query("PRAGMA index_list($table)", emptyArray())
        return cursor.use {
            while (it.moveToNext()) {
                val name = it.getString(it.getColumnIndexOrThrow("name"))
                if (name == indexName) return@use true
            }
            false
        }
    }

    // ==================== 全量迁移后的列验证 ====================

    /** 验证 v2→v16 迁移后 game_data 的所有列存在 */
    private fun verifyGameDataColumnsExist(db: SupportSQLiteDatabase) {
        val expected = listOf(
            "sectLevelClaimRecords", "save_version", "autoBuyList",
            "bloodRefinementBonusTotals",
            "aiSectPersonalities", "suzerainSectId", "lastYearSpiritStoneIncome",
            "activeAttackWarnings", "shownWarningStageIds", "sectAttackCooldowns",
            "midGradeSpiritStones", "highGradeSpiritStones",
            "autoSellMidGradeForPurchase", "autoSellHighGradeForPurchase",
            "vassalContracts", "sectBattleRecords",
            "map_seed", "spiritMineLastSettledMonth"
        )
        for (col in expected) {
            assertTrue("game_data should have column '$col'",
                columnExists(db, "game_data", col))
        }
    }

    /** 验证全量链式迁移（v21→v33）后 game_data 的所有列存在 */
    private fun verifyGameDataColumnsExistFullChain(db: SupportSQLiteDatabase) {
        val expected = listOf(
            "sectLevelClaimRecords", "save_version", "autoBuyList",
            "bloodRefinementBonusTotals", "bloodRefinementPctTotals",
            "aiSectPersonalities", "suzerainSectId", "lastYearSpiritStoneIncome",
            "activeAttackWarnings", "shownWarningStageIds", "sectAttackCooldowns",
            "midGradeSpiritStones", "highGradeSpiritStones",
            "autoSellMidGradeForPurchase", "autoSellHighGradeForPurchase",
            "vassalContracts", "sectBattleRecords",
            "map_seed", "spiritMineLastSettledMonth",
            "gameEventRecords",
            "merchantRefreshChances", "merchantLastRefreshChanceGrantYear",
            "guideClaimedRewardIds", "guideCounters",
            "annual_income_by_source", "annual_expenditure_by_reason",
            "annual_total_income", "annual_total_expenditure",
            "annual_alchemy_count", "annual_forge_count", "annual_herb_count",
            "annual_new_disciples", "annual_deceased_disciples", "annual_deserted_disciples",
            "annual_equipment_by_source", "annual_pill_by_source", "annual_herb_by_source",
            "yearly_reports",
            "open_recruitment_last_paid_month",
            "annual_theft_count",
            "theft_judgements_this_month",
            "soundEnabled",
            "musicEnabled"
        )
        for (col in expected) {
            assertTrue("game_data should have column '$col' after full chain",
                columnExists(db, "game_data", col))
        }
    }

    private fun verifyDisciplesColumnsExist(db: SupportSQLiteDatabase) {
        // usage_lastTheftMonth 已在 v31 删除（偷盗系统年上限重构）——v36 不应存在
        assertFalse("usage_lastTheftMonth should be removed after v31 migration",
            columnExists(db, "disciples", "usage_lastTheftMonth"))
        assertTrue("disciples should have social_masterId",
            columnExists(db, "disciples", "social_masterId"))
        // v14: 修炼 Checkpoint 列（修炼 VoidForge Checkpoint 快照法）
        assertTrue("disciples should have cultivationCheckpoint",
            columnExists(db, "disciples", "cultivationCheckpoint"))
        assertTrue("disciples should have cultivationCheckpointGameMonth",
            columnExists(db, "disciples", "cultivationCheckpointGameMonth"))
    }

    private fun verifyProductionSlotsColumnsExist(db: SupportSQLiteDatabase) {
        assertTrue("production_slots should have buildingInstanceId",
            columnExists(db, "production_slots", "buildingInstanceId"))
    }

    private fun verifyDisciplesExtendedColumnsExist(db: SupportSQLiteDatabase) {
        assertTrue("disciples_extended should have masterId",
            columnExists(db, "disciples_extended", "masterId"))
    }

    private fun verifyDiscipleCompactColumnsExist(db: SupportSQLiteDatabase) {
        assertTrue("disciple_compact should be accessible after full migration",
            columnExists(db, "disciple_compact", "cultivation"))
    }

    /** 查询指定表是否存在 PRIMARY KEY */
    private fun primaryKeyExists(
        db: SupportSQLiteDatabase,
        table: String
    ): Boolean {
        val cursor = db.query("PRAGMA table_info($table)", emptyArray())
        return cursor.use {
            while (it.moveToNext()) {
                val pk = it.getInt(it.getColumnIndexOrThrow("pk"))
                if (pk > 0) return@use true
            }
            false
        }
    }

    /** 查询指定列是否有 NOT NULL 约束 */
    private fun columnIsNotNull(
        db: SupportSQLiteDatabase,
        table: String,
        column: String
    ): Boolean {
        val cursor = db.query("PRAGMA table_info($table)", emptyArray())
        return cursor.use {
            while (it.moveToNext()) {
                val name = it.getString(it.getColumnIndexOrThrow("name"))
                if (name == column) {
                    val notNull = it.getInt(it.getColumnIndexOrThrow("notnull"))
                    return@use notNull == 1
                }
            }
            false
        }
    }

    // ==================== 备份恢复测试 ====================

    @Test
    fun `backup and restore recovers data from pre_migrate_backup`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()

        // 使用真实数据库名（restoreFromBackupIfNeeded 内部硬编码为此名）
        val REAL_DB_NAME = "xianxia_sect.db"
        context.deleteDatabase(REAL_DB_NAME)

        try {
            val dbPath = context.getDatabasePath(REAL_DB_NAME)
            dbPath.parentFile?.mkdirs()

            // 创建有数据的数据库
            SQLiteDatabase.openOrCreateDatabase(dbPath, null).use { db ->
                db.execSQL("CREATE TABLE test_data (id INTEGER PRIMARY KEY, value TEXT NOT NULL)")
                db.execSQL("INSERT INTO test_data VALUES (1, 'original_data')")
                db.execSQL("PRAGMA user_version = 5")
            }

            // 验证数据已写入
            SQLiteDatabase.openDatabase(dbPath.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                val cursor = db.rawQuery("SELECT value FROM test_data WHERE id = 1", null)
                assertTrue("data should exist", cursor.moveToFirst())
                assertEquals("original_data", cursor.getString(0))
                cursor.close()
            }

            // 创建备份（模拟 backupDatabaseForMigration 的产物）
            val backupPath = File(dbPath.absolutePath + ".pre_migrate_backup")
            dbPath.inputStream().use { input ->
                backupPath.outputStream().use { output -> input.copyTo(output) }
            }
            assertTrue("backup file should exist", backupPath.exists())

            // 篡改原数据库：清空数据
            SQLiteDatabase.openOrCreateDatabase(dbPath, null).use { db ->
                db.execSQL("DELETE FROM test_data")
                db.execSQL("PRAGMA user_version = 32")
            }
            SQLiteDatabase.openDatabase(dbPath.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                val cursor = db.rawQuery("SELECT COUNT(*) FROM test_data", null)
                if (cursor.moveToFirst()) assertEquals(0, cursor.getInt(0))
                cursor.close()
            }

            // 调用 restoreFromBackupIfNeeded
            val restored = GameDatabase.restoreFromBackupIfNeeded(context)
            assertTrue("restoreFromBackupIfNeeded should return true", restored)

            // 验证数据已恢复
            SQLiteDatabase.openDatabase(dbPath.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                val cursor = db.rawQuery("SELECT value FROM test_data WHERE id = 1", null)
                assertTrue("data should exist after restore", cursor.moveToFirst())
                assertEquals("original_data should be restored", "original_data", cursor.getString(0))
                cursor.close()
            }
        } finally {
            context.deleteDatabase(REAL_DB_NAME)
            val backupPath = File(context.getDatabasePath(REAL_DB_NAME).absolutePath + ".pre_migrate_backup")
            if (backupPath.exists()) backupPath.delete()
        }
    }

    /**
     * 2026-08-04 修复守卫：迁移恢复判定谓词（纯逻辑，无 SQLite 依赖）。
     *
     * 原实现 currentRowCount > 0 即跳过恢复——迁移崩溃（MigrationNotFoundException）
     * 后 DB 行数仍 > 0，pre_migrate_backup 永不使用，每次启动重复迁移崩溃。
     * 新谓词：当前 user_version < DATABASE_VERSION 且备份与当前同版本（备份创建后
     * 迁移从未完成）→ 触发恢复。
     *
     * 注：restoreFromBackupIfNeeded 全流程在 Robolectric 下不稳定（时序相关），
     * 判定逻辑提取为纯函数后在此直接覆盖全部分支。
     */
    @Test
    fun `shouldRestoreFromBackup - migration pending restores`() {
        // 迁移崩溃场景：行数 > 0、版本低于目标、备份与当前同版 → 恢复
        assertTrue(
            GameDatabaseConfig.shouldRestoreFromBackup(currentRowCount = 1, currentVersion = 5, backupVersion = 5)
        )
    }

    @Test
    fun `shouldRestoreFromBackup - empty database restores`() {
        // 无数据（destructive fallback 后）→ 恢复（保留原语义）
        assertTrue(
            GameDatabaseConfig.shouldRestoreFromBackup(currentRowCount = 0, currentVersion = 32, backupVersion = 5)
        )
    }

    @Test
    fun `shouldRestoreFromBackup - unreadable database restores`() {
        // 当前库打不开/表缺失（-1）→ 恢复（安全兜底）
        assertTrue(
            GameDatabaseConfig.shouldRestoreFromBackup(currentRowCount = -1, currentVersion = -1, backupVersion = 5)
        )
    }

    @Test
    fun `shouldRestoreFromBackup - migration completed skips`() {
        // 迁移已完成（版本已达最新）→ 跳过
        assertFalse(
            GameDatabaseConfig.shouldRestoreFromBackup(
                currentRowCount = 1,
                currentVersion = GameDatabaseConfig.DATABASE_VERSION,
                backupVersion = 5
            )
        )
    }

    @Test
    fun `shouldRestoreFromBackup - backup version mismatch skips`() {
        // 有数据、待迁移但备份版本与当前不一致（备份过期/被覆盖）→ 跳过
        assertFalse(
            GameDatabaseConfig.shouldRestoreFromBackup(currentRowCount = 1, currentVersion = 5, backupVersion = 8)
        )
    }

    @Test
    fun `shouldRestoreFromBackup - future database version restores`() {
        // 2026-08-04 对抗性审查修复（A1）：降级场景——高版本 App 数据回退到
        // 低版本 App（currentVersion > DATABASE_VERSION），Room 无法降级打开必然
        // 崩溃；备份版本迁移链可达（2..DATABASE_VERSION 且比当前旧）→ 恢复
        assertTrue(
            GameDatabaseConfig.shouldRestoreFromBackup(
                currentRowCount = 1,
                currentVersion = GameDatabaseConfig.DATABASE_VERSION + 1,
                backupVersion = GameDatabaseConfig.DATABASE_VERSION - 1
            )
        )
    }

    @Test
    fun `shouldRestoreFromBackup - future version with unusable backup skips`() {
        // 降级场景但备份不可用（版本读取失败 -1）→ 跳过（无法确认备份有效性）
        assertFalse(
            GameDatabaseConfig.shouldRestoreFromBackup(
                currentRowCount = 1,
                currentVersion = GameDatabaseConfig.DATABASE_VERSION + 1,
                backupVersion = -1
            )
        )
    }

    /** 在 v2 种子库插入最小 game_data 行（v2 createSql 列集），供全链迁移测试使用 */
    private fun insertMinimalGameDataV2(db: SupportSQLiteDatabase) {
        db.execSQL(
            "INSERT INTO game_data (" +
                "id, slot_id, sectName, currentSlot, gameYear, gameMonth, gamePhase, " +
                "isGameStarted, gameSpeed, spiritStones, spiritHerbs, sectCultivation, " +
                "autoSaveIntervalMonths, monthlySalary, monthlySalaryEnabled, " +
                "worldMapSects, sectDetails, aiSectDisciples, exploredSects, scoutInfo, " +
                "manualProficiencies, travelingMerchantItems, merchantLastRefreshYear, " +
                "merchantRefreshCount, playerListedItems, recruitList, lastRecruitYear, " +
                "worldLevels, cultivatorCaves, caveExplorationTeams, aiCaveTeams, " +
                "unlockedRecipes, unlockedManuals, lastSaveTime, elderSlots, spiritMineSlots, " +
                "spiritMineExpansions, librarySlots, productionSlots, placedBuildings, " +
                "spiritFieldPlants, activeSectId, residenceSlots, warehouseGarrisons, " +
                "patrolSlots, patrolConfig, patrolConfigs, alliances, sectRelations, " +
                "playerAllianceSlots, sectPolicies, battleTeam, aiBattleTeams, " +
                "usedRedeemCodes, claimedMailIds, playerProtectionEnabled, " +
                "playerProtectionStartYear, playerHasAttackedAI, activeMissions, " +
                "availableMissions, autoRecruitSpiritRootFilter, daoCompanionBannedRootCounts, " +
                "daoCompanionConsentRequired, patrolBattleResultPopup, " +
                "breakthroughAutoPillFocused, breakthroughAutoPillRootCounts, " +
                "autoEquipFromWarehouseFocused, autoEquipFromWarehouseRootCounts, " +
                "autoLearnFromWarehouseFocused, autoLearnFromWarehouseRootCounts, isGameOver" +
                ") VALUES (" +
                "'game_data_1', 1, '测试宗门', 0, 1, 1, 0, 1, 1, 100, 0, 0.0, 3, '0', '0', " +
                "'[]', '{}', '{}', '{}', '{}', '{}', '[]', 0, 0, '[]', '[]', 0, '[]', " +
                "'[]', '[]', '[]', '[]', '[]', 0, '{}', '[]', 0, '[]', '[]', '[]', '[]', " +
                "'sect_1', '[]', '[]', '[]', '{}', '{}', '[]', '[]', 0, '{}', NULL, '{}', " +
                "'[]', '[]', 0, 0, 0, '[]', '[]', '[]', '{}', 0, 0, 0, '{}', 0, '{}', 0, '{}', 0" +
                ")"
        )
    }

    /** 全链迁移后的各表列完整性验证（2026-08-04 从全链测试提取） */
    private fun verifyFullChainColumns(db: SupportSQLiteDatabase) {
        verifyGameDataColumnsExist(db)
        verifyDisciplesColumnsExist(db)
        verifyProductionSlotsColumnsExist(db)
        verifyDisciplesExtendedColumnsExist(db)
        verifyDiscipleCompactColumnsExist(db)
        // 验证 isGameStarted 已被 v13 迁移删除
        assertFalse("isGameStarted should be removed after v13 migration",
            columnExists(db, "game_data", "isGameStarted"))
        // 验证 v14 新增列存在
        assertTrue("cultivationCheckpoint should exist after v14 migration",
            columnExists(db, "disciples", "cultivationCheckpoint"))
        assertTrue("cultivationCheckpointGameMonth should exist after v14 migration",
            columnExists(db, "disciples", "cultivationCheckpointGameMonth"))
        // discipleDesertionPopup 在 v15 加入、v25 移除——全链终点（v39）应不存在
        assertFalse("discipleDesertionPopup should be removed by v25 migration",
            columnExists(db, "game_data", "discipleDesertionPopup"))
        // 验证 v16 新增列存在
        assertTrue("showAllAvailableDisciples should exist after v16 migration",
            columnExists(db, "game_data", "showAllAvailableDisciples"))
        // 验证 v36 新增列存在（2026-07-31 体质/词条列）
        assertTrue("physiqueIds should exist after v36 migration",
            columnExists(db, "disciples", "physiqueIds"))
        assertTrue("affixIds should exist after v36 migration",
            columnExists(db, "disciples", "affixIds"))
    }

    /** 查询指定列的 DEFAULT 值 */
    private fun columnDefault(
        db: SupportSQLiteDatabase,
        table: String,
        column: String
    ): String? {
        val cursor = db.query("PRAGMA table_info($table)", emptyArray())
        return cursor.use {
            while (it.moveToNext()) {
                val name = it.getString(it.getColumnIndexOrThrow("name"))
                if (name == column) {
                    return@use it.getString(it.getColumnIndexOrThrow("dflt_value"))
                }
            }
            null
        }
    }
}
