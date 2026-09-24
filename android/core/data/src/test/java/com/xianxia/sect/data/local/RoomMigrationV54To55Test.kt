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
 * 迁移 54→55 测试（G02 删列：disciples 5 列 / game_data 3 列 / pills 1 列）。
 *
 * 与 [RoomMigrationTest]、[RoomMigrationV43To46Test] 共享 createDatabaseFromSchema
 * 模式：旧版本插种子 → 运行迁移 → 验证删除列消失且其余列数据完整。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class RoomMigrationV54To55Test {

    companion object {
        /** Schema 文件所在目录（相对于模块根目录） */
        private val SCHEMA_DIR: File = File(
            "schemas",
            "com.xianxia.sect.data.local.GameDatabase"
        )

        /** disciples 表 v55 删除列（与 V55_DISCIPLES_DROPPED_COLUMNS 同源断言） */
        private val DISCIPLES_DROPPED = V55_DISCIPLES_DROPPED_COLUMNS

        /** game_data 表 v55 删除列（与 V55_GAME_DATA_DROPPED_COLUMNS 同源断言） */
        private val GAME_DATA_DROPPED = V55_GAME_DATA_DROPPED_COLUMNS

        /** pills 表 v55 删除列（与 V55_PILLS_DROPPED_COLUMNS 同源断言） */
        private val PILLS_DROPPED = V55_PILLS_DROPPED_COLUMNS
    }

    /**
     * 真实 Room 校验：v54 库升级到 v55（删 9 列），触发 onValidateSchema——
     * 迁移未删净实体已删除的列、或重建索引与实体 Index 声明不一致都会在此崩溃。
     */
    @Test
    fun `MIGRATION_54_TO_55 passes real Room schema validation`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val dbName = "m_54_55_room_validate"
        context.deleteDatabase(dbName)
        try {
            createDatabaseFromSchema(context, dbName, 54).close()
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
    fun `MIGRATION_54_TO_55 drops G02 columns and preserves other data`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val dbName = "m_54_55_drop_columns"
        context.deleteDatabase(dbName)
        try {
            val db = createDatabaseFromSchema(context, dbName, 54)
            seedDiscipleRow(db)
            seedGameDataRow(db)
            seedPillRow(db)
            assertPreMigrationShape(db)

            listOf(MIGRATION_54_55).forEach { it.migrate(db) }

            assertPostMigrationShape(db)
            assertSurvivingDataIntact(db)
            db.close()
        } finally {
            context.deleteDatabase(dbName)
        }
    }

    /** 迁移前：三表删除列在场（证伪"种子没插上"的假绿）+ 随列退役索引在场 */
    private fun assertPreMigrationShape(db: SupportSQLiteDatabase) {
        for (col in DISCIPLES_DROPPED) {
            assertTrue("v54 应含 disciples.$col", columnExists(db, "disciples", col))
        }
        for (col in GAME_DATA_DROPPED) {
            assertTrue("v54 应含 game_data.$col", columnExists(db, "game_data", col))
        }
        for (col in PILLS_DROPPED) {
            assertTrue("v54 应含 pills.$col", columnExists(db, "pills", col))
        }
        assertTrue("v54 应含 index_disciples_loyalty",
            indexExists(db, "disciples", "index_disciples_loyalty"))
        assertTrue("v54 应含 index_disciples_age",
            indexExists(db, "disciples", "index_disciples_age"))
    }

    /** 迁移后：5+3+1 删除列全部消失；随列退役索引不重建、存续索引照常重建 */
    private fun assertPostMigrationShape(db: SupportSQLiteDatabase) {
        for (col in DISCIPLES_DROPPED) {
            assertFalse("迁移后 disciples.$col 应被删除", columnExists(db, "disciples", col))
        }
        for (col in GAME_DATA_DROPPED) {
            assertFalse("迁移后 game_data.$col 应被删除", columnExists(db, "game_data", col))
        }
        for (col in PILLS_DROPPED) {
            assertFalse("迁移后 pills.$col 应被删除", columnExists(db, "pills", col))
        }
        assertFalse("index_disciples_loyalty 应随列退役",
            indexExists(db, "disciples", "index_disciples_loyalty"))
        assertFalse("index_disciples_age 应随列退役",
            indexExists(db, "disciples", "index_disciples_age"))
        assertTrue("index_disciples_name 应重建",
            indexExists(db, "disciples", "index_disciples_name"))
        assertTrue("index_pills_name 应重建",
            indexExists(db, "pills", "index_pills_name"))
    }

    /** 迁移后：三表存续列数据逐标记完整（弟子/宗门/丹药各一行） */
    private fun assertSurvivingDataIntact(db: SupportSQLiteDatabase) {
        val disciple = db.query(
            "SELECT name, isAlive, realm, cultivation, comprehension FROM disciples WHERE id = 'd1'"
        ).use { c ->
            c.moveToFirst()
            listOf(c.getString(0), c.getInt(1), c.getInt(2), c.getDouble(3), c.getInt(4))
        }
        assertEquals("旧弟子数据保留", "测试弟子", disciple[0])
        assertEquals("存活标记保留", 1, disciple[1])
        assertEquals("境界保留", 7, disciple[2])
        assertEquals("修为保留", 1234.5, disciple[3])
        assertEquals("悟性保留", 66, disciple[4])

        val gameData = db.query(
            "SELECT sectName, gameYear, gameMonth FROM game_data WHERE id = 'gd1'"
        ).use { c ->
            c.moveToFirst()
            listOf(c.getString(0), c.getInt(1), c.getInt(2))
        }
        assertEquals("宗门名保留", "迁移宗", gameData[0])
        assertEquals("游戏年保留", 42, gameData[1])
        assertEquals("游戏月保留", 6, gameData[2])

        val pill = db.query(
            "SELECT name, rarity, extendLife FROM pills WHERE id = 'p1'"
        ).use { c ->
            c.moveToFirst()
            listOf(c.getString(0), c.getInt(1), c.getInt(2))
        }
        assertEquals("丹药名保留", "测试丹药", pill[0])
        assertEquals("丹药品阶保留", 3, pill[1])
        assertEquals("丹药延寿字段保留", 0, pill[2])
    }

    /**
     * 按 v54 PRAGMA 现状动态生成整行 INSERT：全部列逐一赋值（NOT NULL 无默认列
     * 按类型给 0/0.0/''，标记列给可核对的非默认值）——列数随 schema 演进自动对齐。
     */
    private fun seedDiscipleRow(db: SupportSQLiteDatabase) {
        val (cols, types) = readColumns(db, "disciples")
        val values = cols.mapIndexed { i, name ->
            when (name) {
                "id" -> "'d1'"
                "slot_id" -> "1"
                "name" -> "'测试弟子'"
                "isAlive" -> "1"
                "realm" -> "7"
                "cultivation" -> "1234.5"
                "comprehension" -> "66"
                "age" -> "88"
                "lifespan" -> "300"
                "soulPower" -> "9"
                "loyalty" -> "77"
                "usage_usedExtendLifePillIds" -> "'pill_life_1'"
                else -> zeroLiteral(types[i])
            }
        }
        db.execSQL(
            "INSERT INTO disciples (${cols.joinToString(",")}) VALUES (${values.joinToString(",")})"
        )
    }

    private fun seedGameDataRow(db: SupportSQLiteDatabase) {
        val (cols, types) = readColumns(db, "game_data")
        val values = cols.mapIndexed { i, name ->
            when (name) {
                "id" -> "'gd1'"
                "slot_id" -> "1"
                "sectName" -> "'迁移宗'"
                "gameYear" -> "42"
                "gameMonth" -> "6"
                "annual_theft_count" -> "5"
                "theft_judgements_this_month" -> "3"
                "warehouseGarrisons" -> "'[]'"
                else -> zeroLiteral(types[i])
            }
        }
        db.execSQL(
            "INSERT INTO game_data (${cols.joinToString(",")}) VALUES (${values.joinToString(",")})"
        )
    }

    private fun seedPillRow(db: SupportSQLiteDatabase) {
        val (cols, types) = readColumns(db, "pills")
        val values = cols.mapIndexed { i, name ->
            when (name) {
                "id" -> "'p1'"
                "slot_id" -> "1"
                "name" -> "'测试丹药'"
                "rarity" -> "3"
                "loyaltyAdd" -> "77"
                else -> zeroLiteral(types[i])
            }
        }
        db.execSQL(
            "INSERT INTO pills (${cols.joinToString(",")}) VALUES (${values.joinToString(",")})"
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
        val cursor = db.query(
            "PRAGMA index_list($table)", emptyArray()
        )
        return cursor.use {
            while (it.moveToNext()) {
                val name = it.getString(it.getColumnIndexOrThrow("name"))
                if (name == indexName) return@use true
            }
            false
        }
    }
}
