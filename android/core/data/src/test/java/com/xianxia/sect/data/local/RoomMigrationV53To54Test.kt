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
 * 迁移 53→54 测试（角色卡池 G01 协议列，纯加法 ADD COLUMN）。
 *
 * - `game_data.gacha_fragment_counts` / `gacha_star_map` / `gacha_pity_counters` / `gacha_history`
 * - `disciples.templateId`
 *
 * 旧档默认空 map/list 与 `templateId=''`；`54.json` 由 ksp 自动导出。
 * 规则见 `rules/database-migration.md`。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class RoomMigrationV53To54Test {

    companion object {
        private val SCHEMA_DIR: File = File(
            "schemas",
            "com.xianxia.sect.data.local.GameDatabase"
        )
        private val M53_54 = MIGRATION_53_54
    }

    /** 真实 Room 校验：v53 库升级到 v54，触发 onValidateSchema。 */
    @Test
    fun `MIGRATION_53_TO_54 passes real Room schema validation`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val dbName = "m_53_54_room_validate"
        context.deleteDatabase(dbName)
        try {
            createDatabaseFromSchema(context, dbName, 53).close()
            val db = Room.databaseBuilder(context, GameDatabase::class.java, dbName)
                .addMigrations(*ALL_MIGRATIONS)
                .build()
            db.openHelper.writableDatabase
            db.close()
        } finally {
            context.deleteDatabase(dbName)
        }
    }

    /** 迁移后 gacha 列 + templateId 存在，旧行默认值正确，既有数据零丢失。 */
    @Test
    fun `MIGRATION_53_TO_54 adds gacha columns with defaults and preserves data`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val dbName = "m_53_54_gacha"
        context.deleteDatabase(dbName)
        try {
            val db = createDatabaseFromSchema(context, dbName, 53)
            insertSeedGameData(db, id = "sect-1", overrides = mapOf(
                "sectName" to "'青云宗'",
                "spiritStones" to "99999",
            ))
            insertSeedDisciple(db, id = "1", name = "旧弟子")

            M53_54.migrate(db)

            listOf(
                "gacha_fragment_counts",
                "gacha_star_map",
                "gacha_pity_counters",
                "gacha_history",
            ).forEach { col ->
                assertTrue("game_data.$col 列应存在", columnExists(db, "game_data", col))
            }
            assertTrue("disciples.templateId 列应存在", columnExists(db, "disciples", "templateId"))

            db.query(
                "SELECT gacha_fragment_counts, gacha_star_map, gacha_pity_counters, " +
                    "gacha_history, sectName, spiritStones FROM game_data WHERE id = 'sect-1'"
            ).use { c ->
                assertTrue("迁移后既有行数据应保留", c.moveToFirst())
                assertEquals("碎片 map 旧行默认 {}", "{}", c.getString(0))
                assertEquals("星级 map 旧行默认 {}", "{}", c.getString(1))
                assertEquals("保底 map 旧行默认 {}", "{}", c.getString(2))
                assertEquals("历史 list 旧行默认 []", "[]", c.getString(3))
                assertEquals("既有列 sectName 零丢失", "青云宗", c.getString(4))
                assertEquals("既有列 spiritStones 零丢失", 99999L, c.getLong(5))
            }
            db.query("SELECT templateId, name FROM disciples WHERE id = '1'").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("存量弟子 templateId 默认空串", "", c.getString(0))
                assertEquals("既有弟子名零丢失", "旧弟子", c.getString(1))
            }
            db.close()
        } finally {
            context.deleteDatabase(dbName)
        }
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
                        error("schema-only open should not upgrade")
                    }
                })
                .build()
        )
        return helper.writableDatabase
    }

    private fun insertSeedGameData(
        db: SupportSQLiteDatabase,
        id: String,
        overrides: Map<String, String>
    ) {
        data class Col(val name: String, val type: String, val dflt: String?)
        val cols = mutableListOf<Col>()
        db.query("PRAGMA table_info(game_data)").use { cursor ->
            while (cursor.moveToNext()) {
                cols += Col(
                    name = cursor.getString(cursor.getColumnIndexOrThrow("name")),
                    type = cursor.getString(cursor.getColumnIndexOrThrow("type")),
                    dflt = cursor.getString(cursor.getColumnIndexOrThrow("dflt_value"))
                )
            }
        }
        val values = cols.joinToString(", ") { col ->
            when {
                col.name == "id" -> "'$id'"
                col.name == "slot_id" -> "1"
                overrides[col.name] != null -> overrides[col.name]!!
                col.type.equals("INTEGER", true) -> col.dflt ?: "0"
                else -> col.dflt ?: "''"
            }
        }
        db.execSQL("INSERT INTO game_data (${cols.joinToString(", ") { it.name }}) VALUES ($values)")
    }

    private fun insertSeedDisciple(db: SupportSQLiteDatabase, id: String, name: String) {
        data class Col(val name: String, val type: String, val dflt: String?)
        val cols = mutableListOf<Col>()
        db.query("PRAGMA table_info(disciples)").use { cursor ->
            while (cursor.moveToNext()) {
                cols += Col(
                    name = cursor.getString(cursor.getColumnIndexOrThrow("name")),
                    type = cursor.getString(cursor.getColumnIndexOrThrow("type")),
                    dflt = cursor.getString(cursor.getColumnIndexOrThrow("dflt_value"))
                )
            }
        }
        val values = cols.joinToString(", ") { col ->
            when {
                col.name == "id" -> "'$id'"
                col.name == "slot_id" -> "1"
                col.name == "name" -> "'$name'"
                col.type.equals("INTEGER", true) -> col.dflt ?: "0"
                else -> col.dflt ?: "''"
            }
        }
        db.execSQL("INSERT INTO disciples (${cols.joinToString(", ") { it.name }}) VALUES ($values)")
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
