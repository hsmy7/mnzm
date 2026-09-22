package com.xianxia.sect.data.local

import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
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
 * 迁移 52→53 测试（SR-7 schema 第二刀：删除 6 张**零读者镜像表**）。
 *
 * 删除面 = [V53_DROPPED_TABLES]（`disciples_core` / `disciples_combat` /
 * `disciples_equipment` / `disciples_extended` / `disciples_attributes` /
 * `disciple_compact`）。判归与取证见 `docs/parallel-batches-w5/batch-SR7.md` §1 条 1-3。
 *
 * ## 本类要证的三件事
 * 1. **删得准**：迁移后的表集合**精确等于**迁移前减去那 6 张（多删一张红、少删一张也红）；
 * 2. **删得不丢玩家数据**：真相表 `disciples` 与 `game_data` 的行数、列清单、
 *    每个列值（含 NULL 语义）迁移前后**逐字段全等**；至于"6 张镜像表可安全删"这一前提，
 *    取证在代码面而非本类：唯一生产者 `writeDisciples` 逐行走 `X.fromDisciple(d)`
 *    （零外部输入），六表 SELECT 方法全仓零调用者，C++ 侧六个表名 0 命中
 *    （逐条 grep 结果见 `batch-SR7.md` §1 条 1）；
 * 3. **Room 认账**：v52 库经真实 `Room.databaseBuilder` 升到 v53，触发 `onValidateSchema`
 *    的表/列/索引/主键全等比较。
 *
 * 另含幂等重放与静态 schema 防回流（`53.json` 表集 = `52.json` 表集 − 6）。
 *
 * ⚠️ 全表读取**不得用 `SELECT *`**：Robolectric legacy cursor 会缓存列元数据
 * （先例见 `RoomMigrationV51To52Test` 类 KDoc）⇒ 统一按 `PRAGMA table_info` 显式列清单。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class RoomMigrationV52To53Test {

    private companion object {
        val SCHEMA_DIR: File = File("schemas", "com.xianxia.sect.data.local.GameDatabase")

        /**
         * 🔴 **测试侧独立字面清单——期望不得改为引用生产常量 `V53_DROPPED_TABLES`。**
         *
         * 判别力自证（本批实测，破坏 = 把迁移清单改成少删 `disciple_compact`）：
         * `MIGRATION_52_TO_53 passes real Room schema validation` 与
         * `is idempotent when mirror tables already absent` **两例仍绿**
         * ⇒ Room 的 schema 校验对"库里多出未声明的表"只告警不判错，兜不住少删；
         * 判红的是删表集比对与静态 schema 守卫（两者都带独立字面量 6）。
         * 故本类的期望面一律走这份独立清单，另由 [migrationListMatchesIndependentExpectation]
         * 钉住"生产清单 == 独立期望"，任一侧被单独改动即红。
         */
        val EXPECTED_DROPPED_TABLES = listOf(
            "disciples_core",
            "disciples_combat",
            "disciples_equipment",
            "disciples_extended",
            "disciples_attributes",
            "disciple_compact"
        )

        /** 迁移后必须仍然完好的真相表（本批只删镜像表，这两张是玩家数据的落点） */
        val TRUTH_TABLES = listOf("disciples", "game_data")

        const val DISCIPLE_MARKER = "SR7_DISCIPLE_MARKER"
        const val SECT_MARKER = "SR7_SECT_MARKER"
    }

    // ==================== 0. 清单漂移守卫（独立期望 ⇔ 生产实现） ====================

    @Test
    fun `migrationListMatchesIndependentExpectation`() {
        assertEquals(
            "生产迁移清单与本类的独立期望必须逐元素相等（任一侧被单独改动即红）",
            EXPECTED_DROPPED_TABLES,
            V53_DROPPED_TABLES
        )
    }

    // ==================== 1. 真实 Room 校验 ====================

    @Test
    fun `MIGRATION_52_TO_53 passes real Room schema validation`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val dbName = "m_52_53_room_validate"
        context.deleteDatabase(dbName)
        try {
            RoomMigrationSupport.createDatabaseFromSchema(context, dbName, 52).close()
            val db = Room.databaseBuilder(context, GameDatabase::class.java, dbName)
                // 迁移链取自单点登记表——`@Database(version)` 递增无需改本文件
                .addMigrations(*ALL_MIGRATIONS)
                .build()
            db.openHelper.writableDatabase
            db.close()
        } finally {
            context.deleteDatabase(dbName)
        }
    }

    // ==================== 2. 删得准 + 真相表逐字段等价 ====================

    @Test
    fun `MIGRATION_52_TO_53 drops exactly the six mirror tables and nothing else`() {
        withSeededV52Db("m_52_53_drop_six") { db ->
            val tablesBefore = listOfTables(db)
            assertSeedHasAllMirrorTables(tablesBefore, db)
            val truthBefore = TRUTH_TABLES.associateWith { readAllRows(db, it) }

            RoomMigrationSupport.applyMigrationsSequentially(db, listOf(MIGRATION_52_53))

            val tablesAfter = listOfTables(db)
            assertEquals(
                "迁移后的表集合必须精确等于 v52 表集减去独立期望清单（多删/少删都会到此）",
                tablesBefore - EXPECTED_DROPPED_TABLES.toSet(),
                tablesAfter
            )
            EXPECTED_DROPPED_TABLES.forEach { table ->
                assertFalse("$table 应在 v53 被删除", table in tablesAfter)
            }
            assertEquals(
                "被删表集必须恰为声明的 6 张",
                6,
                (tablesBefore - tablesAfter).size
            )

            // 真相表逐字段等价（行数 + 列清单 + 每个值，NULL 亦须相等）
            TRUTH_TABLES.forEach { table ->
                assertFieldEquivalence(table, truthBefore.getValue(table), readAllRows(db, table))
            }
        }
    }

    // ==================== 3. 幂等 ====================

    @Test
    fun `MIGRATION_52_TO_53 is idempotent when mirror tables already absent`() {
        withSeededV52Db("m_52_53_idempotent") { db ->
            RoomMigrationSupport.applyMigrationsSequentially(db, listOf(MIGRATION_52_53))
            val once = listOfTables(db)
            // 二次执行：DROP TABLE IF EXISTS 对不存在的表不得抛错
            RoomMigrationSupport.applyMigrationsSequentially(db, listOf(MIGRATION_52_53))
            assertEquals("幂等重放不得改变表集合", once, listOfTables(db))
        }
    }

    // ==================== 4. 静态防回流（schema JSON） ====================

    /**
     * 静态守卫：锁"6 张镜像表不得回流到实体清单"。
     *
     * **对照面非空转**：`52.json` 必须仍含 6 表（历史快照不动），`53.json` 必须不含
     * ——若某天有人把实体加回 `@Database(entities=…)`，`53.json` 重生成即含该表，本断言变红。
     */
    @Test
    fun `schema 53 excludes the six mirror tables while 52 keeps them as historical snapshot`() {
        val v52 = tableNamesInSchema(52)
        val v53 = tableNamesInSchema(53)

        EXPECTED_DROPPED_TABLES.forEach { table ->
            assertTrue("52.json 应仍含 $table（历史快照对照面）", table in v52)
            assertFalse("53.json 不得含 $table（零读者镜像表不得回流）", table in v53)
        }
        assertEquals("53.json 表数应恰比 52.json 少 6", v52.size - 6, v53.size)
        assertEquals(
            "53.json 表集必须等于 52.json 减 6 张镜像表",
            v52 - EXPECTED_DROPPED_TABLES.toSet(),
            v53
        )
        assertTrue("真相表 disciples 必须在 53.json", "disciples" in v53)
        assertTrue("真相表 game_data 必须在 53.json", "game_data" in v53)
    }

    // ==================== 夹具 ====================

    /**
     * 建 v52 库并种入：1 行 `game_data` + 1 行 `disciples` + 6 张镜像表各 1 行
     *（镜像行按真相行的同名列取值 ⇒ "纯派生"断言有对照面）。
     */
    private fun withSeededV52Db(dbName: String, block: (SupportSQLiteDatabase) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        context.deleteDatabase(dbName)
        try {
            val db = RoomMigrationSupport.createDatabaseFromSchema(context, dbName, 52)
            insertSeedRow(db, "game_data", mapOf("id" to "'sect-sr7'", "slot_id" to "1", "sectName" to "'$SECT_MARKER'"))
            insertSeedRow(
                db, "disciples",
                mapOf(
                    "id" to "'d1'", "slot_id" to "1", "name" to "'$DISCIPLE_MARKER'",
                    "realm" to "3", "cultivation" to "777", "isAlive" to "1"
                )
            )
            val discipleRow = readAllRows(db, "disciples").single()
            EXPECTED_DROPPED_TABLES.forEach { table ->
                val overrides = mutableMapOf("slot_id" to "1")
                when (table) {
                    "disciples_core" -> {
                        overrides["id"] = "'d1'"
                        discipleRow["name"]?.let { overrides["name"] = "'$it'" }
                        discipleRow["realm"]?.let { overrides["realm"] = it }
                    }
                    "disciple_compact" -> {
                        overrides["id"] = "'d1'"
                        discipleRow["cultivation"]?.let { overrides["cultivation"] = it }
                    }
                    else -> overrides["discipleId"] = "'d1'"
                }
                insertSeedRow(db, table, overrides)
            }
            block(db)
            db.close()
        } finally {
            context.deleteDatabase(dbName)
        }
    }

    /** 种子自检：6 张镜像表必须真的建出来**且各带一行**（否则"删得准"无对照面） */
    private fun assertSeedHasAllMirrorTables(tables: List<String>, db: SupportSQLiteDatabase) {
        EXPECTED_DROPPED_TABLES.forEach { table ->
            assertTrue("v52 种子库应含 $table（对照面不得空转）", table in tables)
            assertEquals("$table 种子行必须存在", 1, readAllRows(db, table).size)
        }
    }

    /** 当前库的全部表名（排除 sqlite 内部表），排序后返回以便集合比对 */
    private fun listOfTables(db: SupportSQLiteDatabase): List<String> {
        val names = mutableListOf<String>()
        db.query(
            "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' ORDER BY name"
        ).use { cursor ->
            while (cursor.moveToNext()) names += cursor.getString(0)
        }
        return names
    }

    /** schema JSON 的表名清单 */
    private fun tableNamesInSchema(version: Int): Set<String> {
        val schemaFile = File(SCHEMA_DIR, "$version.json")
        assertTrue("schema 文件应存在: ${schemaFile.absolutePath}", schemaFile.exists())
        val entities = JsonParser.parseString(schemaFile.readText()).asJsonObject
            .getAsJsonObject("database")
            .getAsJsonArray("entities")
        return (0 until entities.size()).map { entities[it].asJsonObject.get("tableName").asString }.toSet()
    }

    /**
     * 按 PRAGMA 列清单生成 INSERT：NOT NULL 无默认列按亲和性填 0/0.0/`'v_<列名>'`，
     * 可空列留 NULL，[values] 覆盖关键列（值须是 SQL 字面量原文）。
     */
    private fun insertSeedRow(db: SupportSQLiteDatabase, table: String, values: Map<String, String>) {
        data class Col(val name: String, val type: String, val notNull: Boolean, val dflt: String?)

        val cols = mutableListOf<Col>()
        db.query("PRAGMA table_info($table)").use { cursor ->
            while (cursor.moveToNext()) {
                cols += Col(
                    name = cursor.getString(cursor.getColumnIndexOrThrow("name")),
                    type = cursor.getString(cursor.getColumnIndexOrThrow("type")),
                    notNull = cursor.getInt(cursor.getColumnIndexOrThrow("notnull")) == 1,
                    dflt = cursor.getString(cursor.getColumnIndexOrThrow("dflt_value"))
                )
            }
        }
        assertTrue("$table 应至少有 1 列（PRAGMA 读取失败即空集）", cols.isNotEmpty())
        val rendered = cols.joinToString(", ") { col ->
            when {
                values.containsKey(col.name) -> values.getValue(col.name)
                col.type.uppercase().contains("INT") -> col.dflt ?: "0"
                col.type.uppercase().contains("REAL") -> col.dflt ?: "0.0"
                col.notNull -> col.dflt ?: "'v_${col.name}'"
                else -> "NULL"
            }
        }
        db.execSQL("INSERT INTO $table (${cols.joinToString(", ") { it.name }}) VALUES ($rendered)")
    }

    /** 读取整表（列名 → 值，NULL 保持 null），按 rowid 排序；不得用 `SELECT *`（见类 KDoc） */
    private fun readAllRows(db: SupportSQLiteDatabase, table: String): List<Map<String, String?>> {
        val cols = RoomMigrationSupport.tableColumns(db, table)
        val sql = "SELECT ${cols.joinToString(", ") { "`$it`" }} FROM $table ORDER BY rowid"
        val rows = mutableListOf<Map<String, String?>>()
        db.query(sql).use { cursor ->
            while (cursor.moveToNext()) {
                val row = LinkedHashMap<String, String?>()
                for ((i, name) in cols.withIndex()) row[name] = cursor.getString(i)
                rows.add(row)
            }
        }
        return rows
    }

    /** 逐字段等价：行数、列清单、每个列值全等 */
    private fun assertFieldEquivalence(
        table: String,
        before: List<Map<String, String?>>,
        after: List<Map<String, String?>>
    ) {
        assertEquals("$table 行数不得因迁移改变", before.size, after.size)
        assertTrue("$table 应有对照行（空表会让等价断言空转）", before.isNotEmpty())
        assertEquals("$table 列清单不得因迁移改变", before.first().keys, after.first().keys)
        for (r in before.indices) {
            before.first().keys.forEach { column ->
                assertEquals(
                    "$table 行$r 列 `$column` 迁移前后必须逐字段全等",
                    before[r][column],
                    after[r][column]
                )
            }
        }
    }
}
