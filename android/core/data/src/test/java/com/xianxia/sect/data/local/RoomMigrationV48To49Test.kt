package com.xianxia.sect.data.local

import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.gson.JsonParser
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

/**
 * 迁移 48→49 测试（game_data 新增"石板道路"roads 列）。
 *
 * 背景：道路系统——玩家在宗门地图网格放置石板道路，程序按邻接位掩码自动拼接。
 * roads 经 CollectionConverters（Protobuf Base64）存 TEXT 列（空列表 = ''）。
 * 纯加法 ADD COLUMN ... DEFAULT ''，兼容旧行，`49.json` 由 ksp 自动导出。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class RoomMigrationV48To49Test {

    companion object {
        private val SCHEMA_DIR: File = File(
            "schemas",
            "com.xianxia.sect.data.local.GameDatabase"
        )

        private val M48_49 = MIGRATION_48_49
    }

    /** 真实 Room 校验：v48 库升级到 v49，触发 onValidateSchema——roads 列定义与实体注解一致才通过。 */
    @Test
    fun `MIGRATION_48_TO_49 passes real Room schema validation`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val dbName = "m_48_49_room_validate"
        context.deleteDatabase(dbName)
        try {
            createDatabaseFromSchema(context, dbName, 48).close()
            val db = Room.databaseBuilder(context, GameDatabase::class.java, dbName)
                .addMigrations(*ALL_MIGRATIONS)
                .build()
            db.openHelper.writableDatabase
            db.close()
        } finally {
            context.deleteDatabase(dbName)
        }
    }

    /** 迁移后 game_data.roads 列存在（纯加法新增列）。 */
    @Test
    fun `MIGRATION_48_TO_49 adds roads column`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val dbName = "m_48_49_roads"
        context.deleteDatabase(dbName)
        try {
            val db = createDatabaseFromSchema(context, dbName, 48)
            listOf(M48_49).forEach { it.migrate(db) }
            assertTrue(
                "roads 列应存在于 game_data（MIGRATION_48_49 未生效）",
                columnExists(db, "game_data", "roads")
            )
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
