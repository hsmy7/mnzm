package com.xianxia.sect.data.local

import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

/**
 * 迁移 55→56 测试（G05 删列：game_data 5 列 / sect_policy_state 1 列）。
 *
 * 与 [RoomMigrationV54To55Test] 同构：旧版本插种子 → 运行迁移 →
 * 验证删除列消失、索引按实体声明重建、其余列数据完整。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class RoomMigrationV55To56Test {

    companion object {
        /** Schema 文件所在目录（相对于模块根目录） */
        private val SCHEMA_DIR: File = File(
            "schemas",
            "com.xianxia.sect.data.local.GameDatabase"
        )

        /** game_data 表 v56 删除列（与 V56_GAME_DATA_DROPPED_COLUMNS 同源断言） */
        private val GAME_DATA_DROPPED = V56_GAME_DATA_DROPPED_COLUMNS

        /** sect_policy_state 表 v56 删除列（与 V56_SECT_POLICY_DROPPED_COLUMNS 同源断言） */
        private val SECT_POLICY_DROPPED = V56_SECT_POLICY_DROPPED_COLUMNS
    }

    /**
     * 真实 Room 校验：v55 库升级到 v56（删 6 列），触发 onValidateSchema——
     * 迁移未删净实体已删除的列、或重建索引与实体 Index 声明不一致都会在此崩溃。
     */
    @Test
    fun `MIGRATION_55_TO_56 passes real Room schema validation`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val dbName = "m_55_56_room_validate"
        context.deleteDatabase(dbName)
        try {
            createDatabaseFromSchema(context, dbName, 55).close()
            val db = Room.databaseBuilder(context, GameDatabase::class.java, dbName)
                .addMigrations(*ALL_MIGRATIONS)
                .build()
            db.openHelper.writableDatabase
            db.close()
        } finally {
            context.deleteDatabase(dbName)
        }
    }

    @Test
    fun `MIGRATION_55_TO_56 drops G05 columns and preserves other data`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val dbName = "m_55_56_drop_columns"
        context.deleteDatabase(dbName)
        try {
            val db = createDatabaseFromSchema(context, dbName, 55)
            seedGameDataRow(db)
            seedSectPolicyRow(db)
            assertPreMigrationShape(db)

            listOf(MIGRATION_55_56).forEach { it.migrate(db) }

            assertPostMigrationShape(db)
            assertSurvivingDataIntact(db)
            db.close()
        } finally {
            context.deleteDatabase(dbName)
        }
    }

    /** 迁移前：两表删除列在场（证伪"种子没插上"的假绿）+ 退役索引在场 */
    private fun assertPreMigrationShape(db: SupportSQLiteDatabase) {
        for (col in GAME_DATA_DROPPED) {
            assertTrue("v55 应含 game_data.$col", columnExists(db, "game_data", col))
        }
        for (col in SECT_POLICY_DROPPED) {
            assertTrue("v55 应含 sect_policy_state.$col", columnExists(db, "sect_policy_state", col))
        }
        assertTrue(
            "v55 应含 index_sect_policy_state_slot_id",
            indexExists(db, "sect_policy_state", "index_sect_policy_state_slot_id")
        )
    }

    /** 迁移后：5+1 删除列全部消失；两表存续索引照常重建 */
    private fun assertPostMigrationShape(db: SupportSQLiteDatabase) {
        for (col in GAME_DATA_DROPPED) {
            assertFalse("迁移后 game_data.$col 应被删除", columnExists(db, "game_data", col))
        }
        for (col in SECT_POLICY_DROPPED) {
            assertFalse(
                "迁移后 sect_policy_state.$col 应被删除",
                columnExists(db, "sect_policy_state", col)
            )
        }
        assertTrue("index_game_data_slot_id 应重建", indexExists(db, "game_data", "index_game_data_slot_id"))
        assertTrue(
            "index_sect_policy_state_slot_id 应重建",
            indexExists(db, "sect_policy_state", "index_sect_policy_state_slot_id")
        )
    }

    /** 迁移后：两表存续列数据逐标记完整 */
    private fun assertSurvivingDataIntact(db: SupportSQLiteDatabase) {
        val gameData = db.query(
            "SELECT sectName, gameYear, gameMonth, spiritStones FROM game_data WHERE id = 'gd1'"
        ).use { c ->
            c.moveToFirst()
            listOf(c.getString(0), c.getInt(1), c.getInt(2), c.getLong(3))
        }
        assertEquals("宗门名保留", "迁移宗", gameData[0])
        assertEquals("游戏年保留", 42, gameData[1])
        assertEquals("游戏月保留", 6, gameData[2])
        assertEquals("灵石保留", 5000L, gameData[3])

        val policy = db.query(
            "SELECT sectPolicies FROM sect_policy_state WHERE slot_id = 1"
        ).use { c ->
            c.moveToFirst()
            c.getString(0)
        }
        assertEquals("政策载荷保留", "{\"probe\":1}", policy)
    }

    /**
     * 按 v55 PRAGMA 现状动态生成整行 INSERT：全部列逐一赋值（NOT NULL 无默认列
     * 按类型给 0/0.0/''，标记列给可核对的非默认值）——列数随 schema 演进自动对齐。
     */
    private fun seedGameDataRow(db: SupportSQLiteDatabase) {
        val (cols, types) = readColumns(db, "game_data")
        val values = cols.mapIndexed { i, name ->
            when (name) {
                "id" -> "'gd1'"
                "slot_id" -> "1"
                "sectName" -> "'迁移宗'"
                "gameYear" -> "42"
                "gameMonth" -> "6"
                "spiritStones" -> "5000"
                "lastRecruitYear" -> "3"
                "last_ai_sect_recruit_year" -> "5"
                "open_recruitment_last_paid_month" -> "77"
                "autoRecruitSpiritRootFilter" -> "'[1,2]'"
                "autoRejectSpiritRootFilter" -> "'[3]'"
                else -> zeroLiteral(types[i])
            }
        }
        db.execSQL(
            "INSERT INTO game_data (${cols.joinToString(",")}) VALUES (${values.joinToString(",")})"
        )
    }

    private fun seedSectPolicyRow(db: SupportSQLiteDatabase) {
        val (cols, types) = readColumns(db, "sect_policy_state")
        val values = cols.mapIndexed { i, name ->
            when (name) {
                "slot_id" -> "1"
                "sectPolicies" -> "'{\"probe\":1}'"
                "autoRecruitSpiritRootFilter" -> "'[4,5]'"
                else -> zeroLiteral(types[i])
            }
        }
        db.execSQL(
            "INSERT INTO sect_policy_state (${cols.joinToString(",")}) " +
                "VALUES (${values.joinToString(",")})"
        )
    }

    private fun readColumns(db: SupportSQLiteDatabase, table: String): Pair<List<String>, List<String>> {
        val cols = mutableListOf<String>()
        val types = mutableListOf<String>()
        db.query("PRAGMA table_info($table)", emptyArray()).use { c ->
            while (c.moveToNext()) {
                cols += c.getString(c.getColumnIndexOrThrow("name"))
                types += c.getString(c.getColumnIndexOrThrow("type")).uppercase()
            }
        }
        return cols to types
    }

    private fun zeroLiteral(type: String): String = when {
        type.contains("INT") -> "0"
        type.contains("REAL") || type.contains("NUMERIC") -> "0.0"
        else -> "''"
    }

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

    private fun columnExists(db: SupportSQLiteDatabase, table: String, column: String): Boolean {
        val cursor = db.query("PRAGMA table_info($table)", emptyArray())
        return cursor.use {
            while (it.moveToNext()) {
                val name = it.getString(it.getColumnIndexOrThrow("name"))
                if (name == column) return@use true
            }
            false
        }
    }

    private fun indexExists(db: SupportSQLiteDatabase, table: String, indexName: String): Boolean {
        val cursor = db.query("PRAGMA index_list($table)", emptyArray())
        return cursor.use {
            while (it.moveToNext()) {
                val name = it.getString(it.getColumnIndexOrThrow("name"))
                if (name == indexName) return@use true
            }
            false
        }
    }
}
