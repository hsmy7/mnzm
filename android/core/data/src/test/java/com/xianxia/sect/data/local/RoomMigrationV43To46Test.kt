package com.xianxia.sect.data.local

import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

/**
 * 迁移 43→46 测试（v44 职业 4 列 / v45 探索队表删除 / v46 资质列）。
 *
 * 2026-08-12 拆分自 [RoomMigrationTest]（单文件 2000 行上限），
 * 与母文件共享 createDatabaseFromSchema/columnExists 模式，独立文件保持内聚。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class RoomMigrationV43To46Test {

    companion object {
        /** Schema 文件所在目录（相对于模块根目录） */
        private val SCHEMA_DIR: File = File(
            "schemas",
            "com.xianxia.sect.data.local.GameDatabase"
        )

        private val M43_44 = MIGRATION_43_44
        private val M44_45 = MIGRATION_44_45
        private val M45_46 = MIGRATION_45_46
        private val M46_47 = MIGRATION_46_47

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

        /** v45 种子行：v43 基础上补职业 4 列（v44 起 attributes 表职业列 NOT NULL 无默认值） */
        private val SEED_DISCIPLES_ATTRIBUTES_V45: String = SEED_DISCIPLES_ATTRIBUTES_V43
            .replace(
                "salaryPaidCount, salaryMissedCount\n",
                "salaryPaidCount, salaryMissedCount, alchemyLevel, alchemyPromotionCount,\n" +
                    "forgeLevel, forgePromotionCount\n"
            )
            .replace(
                "2, 3\n",
                "2, 3, 0, 0, 0, 0\n"
            )
    }

    // ═══════════════ 43 → 44（职业 4 列）═══════════════

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
                .addMigrations(M43_44, M44_45, M45_46, M46_47)
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

    // ═══════════════ 44 → 45（探索队表删除）═══════════════

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
                .addMigrations(M44_45, M45_46, M46_47)
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

    // ═══════════════ 45 → 46（资质列）═══════════════

    /**
     * 真实 Room 校验：v45 库升级到 v46（两表新增 aptitude 列），触发 onValidateSchema——
     * 列定义不一致（含 DEFAULT 50 缺失）都会在此崩溃。46.json 由 ksp 自动导出。
     */
    @Test
    fun `MIGRATION_45_TO_46 passes real Room schema validation`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val dbName = "m_45_46_room_validate"
        context.deleteDatabase(dbName)
        try {
            createDatabaseFromSchema(context, dbName, 45).close()
            val db = Room.databaseBuilder(context, GameDatabase::class.java, dbName)
                .addMigrations(M45_46, M46_47)
                .build()
            db.openHelper.writableDatabase
            db.close()
        } finally {
            context.deleteDatabase(dbName)
        }
    }

    @Test
    fun `MIGRATION_45_TO_46 adds aptitude columns default 50 keeps other data`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val dbName = "m_45_46_columns"
        context.deleteDatabase(dbName)
        try {
            val db = createDatabaseFromSchema(context, dbName, 45)
            // v45 库（disciples 职业 4 列同 v44），seed 弟子两表
            db.execSQL(SEED_DISCIPLES_V44)
            db.execSQL(SEED_DISCIPLES_ATTRIBUTES_V45)
            listOf(M45_46).forEach { it.migrate(db) }

            // 两表 aptitude 列存在
            assertTrue(columnExists(db, "disciples", "aptitude"))
            assertTrue(columnExists(db, "disciples_attributes", "aptitude"))

            // 旧行默认 50（自愈哨兵值，与 DiscipleTables.DEFAULT_APTITUDE 统一）
            val aptitude = db.query(
                "SELECT aptitude FROM disciples WHERE id = 'd1'"
            ).use { c -> c.moveToFirst(); c.getInt(0) }
            assertEquals("旧弟子 aptitude 默认 50（自愈哨兵）", 50, aptitude)
            val attrAptitude = db.query(
                "SELECT aptitude FROM disciples_attributes WHERE discipleId = 'd1'"
            ).use { c -> c.moveToFirst(); c.getInt(0) }
            assertEquals("旧属性 aptitude 默认 50", 50, attrAptitude)

            // 旧数据保留
            val disciple = db.query("SELECT name, isAlive FROM disciples WHERE id = 'd1'").use { c ->
                c.moveToFirst(); c.getString(0) to c.getInt(1)
            }
            assertEquals("旧弟子数据保留", "测试弟子" to 1, disciple)
            db.close()
        } finally {
            context.deleteDatabase(dbName)
        }
    }

    // ═══════════════ 辅助函数 ═══════════════

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
                    ) {
                        // schema 直开只触发 onValidateSchema 校验迁移链；onUpgrade 不应被调用
                        error("schema-only open should not upgrade")
                    }
                })
                .build()
        )
        return helper.writableDatabase
    }

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
}
