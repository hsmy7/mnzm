package com.xianxia.sect.data.local

import android.database.Cursor
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * 迁移 57→58 测试（G04 删列：disciples 4 列 / game_data 5 列）。
 *
 * 与 [RoomMigrationV56To57Test] 同构：旧版本插种子 → 运行迁移 →
 * 验证删除列消失、索引按实体声明重建、其余列数据完整。
 *
 * 本类的种子面与断言面：
 * 1. **种子非零值覆盖全部 9 个被删列**——证明"列消失"来自迁移而非"种子没插上"；
 * 2. **悟性列 `comprehension` 逐行播种非零值**，迁移后逐值核对——悟性保留口径
 *    （HANDOVER §4#15）是本次删除面的边界，悟性丢失属于存档损坏级缺陷；
 * 3. **保留列逐值全等**：两表迁移前/后按 `PRAGMA table_info` 显式列清单整行快照
 *    （不使用 `SELECT *`——Robolectric legacy cursor 在 RENAME+CREATE+DROP 后会拿到
 *    过期列元数据），扣除被删列后逐格比对，任何存续列被改写/置空都会红；
 * 4. **行数与行序不变**：快照按自然序（rowid 序）读取，比对行序而非集合。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class RoomMigrationV57To58Test {

    companion object {
        /** disciples 表 v58 删除列（与 V58_DISCIPLES_DROPPED_COLUMNS 同源断言） */
        private val DISCIPLES_DROPPED = V58_DISCIPLES_DROPPED_COLUMNS

        /** game_data 表 v58 删除列（与 V58_GAME_DATA_DROPPED_COLUMNS 同源断言） */
        private val GAME_DATA_DROPPED = V58_GAME_DATA_DROPPED_COLUMNS

        /** disciples 存续索引（本批不删索引，重建表后必须全部回来）。 */
        private val DISCIPLES_INDICES = listOf(
            "index_disciples_name",
            "index_disciples_realm_realmLayer",
            "index_disciples_isAlive_realm",
            "index_disciples_isAlive_status",
            "index_disciples_discipleType"
        )

        /** game_data 存续索引。 */
        private val GAME_DATA_INDICES = listOf(
            "index_game_data_slot_id",
            "index_game_data_lastSaveTime",
            "index_game_data_gameYear_gameMonth",
            "index_game_data_sectName",
            "index_game_data_spiritStones"
        )

        /** 种子弟子行序（迁移前后自然序必须与之一致）。 */
        private val DISCIPLE_IDS = listOf("d1", "d2", "d3")

        /** 两张被迁表。 */
        private val MIGRATED_TABLES = listOf("disciples", "game_data")
    }

    /**
     * 真实 Room 校验：v57 库升级到 v58（删 9 列），触发 onValidateSchema——
     * 迁移未删净实体已删除的列、或重建索引与实体 Index 声明不一致都会在此崩溃。
     */
    @Test
    fun `MIGRATION_57_TO_58 passes real Room schema validation`() {
        val context = ApplicationProvider
            .getApplicationContext<android.content.Context>()
        val dbName = "m_57_58_room_validate"
        context.deleteDatabase(dbName)
        try {
            RoomMigrationSupport.createDatabaseFromSchema(context, dbName, 57).close()
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
    fun `MIGRATION_57_TO_58 drops G04 columns and preserves other data`() {
        val context = ApplicationProvider
            .getApplicationContext<android.content.Context>()
        val dbName = "m_57_58_drop_columns"
        context.deleteDatabase(dbName)
        try {
            val db = RoomMigrationSupport.createDatabaseFromSchema(context, dbName, 57)
            seedRows(db)
            assertPreMigrationShape(db)
            val before = MIGRATED_TABLES.associateWith { snapshotRows(db, it) }

            MIGRATION_57_58.migrate(db)

            assertPostMigrationShape(db)
            assertPreservedDataIntact(before, db)
            db.close()
        } finally {
            context.deleteDatabase(dbName)
        }
    }

    /** 重复执行安全：待删列已不存在时迁移直接返回（迁移中断重试依赖该幂等性）。 */
    @Test
    fun `MIGRATION_57_TO_58 is idempotent after columns are gone`() {
        val context = ApplicationProvider
            .getApplicationContext<android.content.Context>()
        val dbName = "m_57_58_idempotent"
        context.deleteDatabase(dbName)
        try {
            val db = RoomMigrationSupport.createDatabaseFromSchema(context, dbName, 57)
            seedRows(db)
            val first = snapshotRows(db, "disciples")
            MIGRATION_57_58.migrate(db)
            val afterFirst = snapshotRows(db, "disciples")

            MIGRATION_57_58.migrate(db)

            assertEquals("二次迁移不得改写 disciples 数据", afterFirst, snapshotRows(db, "disciples"))
            assertEquals("行数不变", first.size, snapshotRows(db, "disciples").size)
            db.close()
        } finally {
            context.deleteDatabase(dbName)
        }
    }

    // ==================== 形态断言 ====================

    /** 迁移前：两表 9 个删除列全部在场（证伪"种子没插上"的假绿）。 */
    private fun assertPreMigrationShape(db: SupportSQLiteDatabase) {
        assertDroppedColumnsPresent(db, "disciples", DISCIPLES_DROPPED)
        assertDroppedColumnsPresent(db, "game_data", GAME_DATA_DROPPED)
        assertTrue("v57 应含 disciples.comprehension（悟性列）",
            RoomMigrationSupport.columnExists(db, "disciples", "comprehension"))
        assertTrue("v57 应含 disciples.social_masterId（师徒列）",
            RoomMigrationSupport.columnExists(db, "disciples", "social_masterId"))
    }

    /** 迁移后：9 删除列全部消失；两表存续索引照常重建。 */
    private fun assertPostMigrationShape(db: SupportSQLiteDatabase) {
        assertDroppedColumnsGone(db, "disciples", DISCIPLES_DROPPED)
        assertDroppedColumnsGone(db, "game_data", GAME_DATA_DROPPED)
        for (idx in DISCIPLES_INDICES) {
            assertTrue("索引 $idx 应重建", RoomMigrationSupport.indexExists(db, "disciples", idx))
        }
        for (idx in GAME_DATA_INDICES) {
            assertTrue("索引 $idx 应重建", RoomMigrationSupport.indexExists(db, "game_data", idx))
        }
    }

    private fun assertDroppedColumnsPresent(
        db: SupportSQLiteDatabase,
        table: String,
        dropped: List<String>
    ) {
        for (col in dropped) {
            assertTrue("v57 应含 $table.$col", RoomMigrationSupport.columnExists(db, table, col))
        }
    }

    private fun assertDroppedColumnsGone(
        db: SupportSQLiteDatabase,
        table: String,
        dropped: List<String>
    ) {
        for (col in dropped) {
            assertFalse("迁移后 $table.$col 应被删除",
                RoomMigrationSupport.columnExists(db, table, col))
        }
    }

    // ==================== 数据完整性断言 ====================

    /**
     * 迁移后存续数据完整性：行数、行序、以及**扣除被删列后的全部列逐格相等**，
     * 外加悟性列 `comprehension` 的显式逐行核对（保留口径边界，必须原样存活）。
     */
    private fun assertPreservedDataIntact(
        before: Map<String, List<Map<String, String?>>>,
        db: SupportSQLiteDatabase
    ) {
        for ((table, beforeRows) in before) {
            val afterRows = snapshotRows(db, table)
            assertEquals("$table 迁移后行数不变", beforeRows.size, afterRows.size)
            val dropped = droppedColumnsOf(table)
            assertEquals(
                "$table 存续列必须逐值不变（扣除 ${dropped.size} 个被删列）",
                beforeRows.map { row -> row.filterKeys { it !in dropped } },
                afterRows
            )
        }
        assertComprehensionPreserved(db)
    }

    /** 悟性列逐行核对：播种值必须原样存活且行序不变。 */
    private fun assertComprehensionPreserved(db: SupportSQLiteDatabase) {
        val comprehensions = db.query(
            "SELECT comprehension FROM disciples"
        ).use { c ->
            val values = mutableListOf<String?>()
            while (c.moveToNext()) values += if (c.isNull(0)) null else c.getString(0)
            values
        }
        assertEquals(
            "comprehension 必须逐行原样存活（悟性保留口径，行序也不变）",
            listOf("60", "61", "62"),
            comprehensions
        )
        val ids = db.query("SELECT id FROM disciples").use { c ->
            val values = mutableListOf<String>()
            while (c.moveToNext()) values += c.getString(0)
            values
        }
        assertEquals("弟子行序不变", DISCIPLE_IDS, ids)
    }

    private fun droppedColumnsOf(table: String): Set<String> = when (table) {
        "disciples" -> DISCIPLES_DROPPED.toSet()
        else -> GAME_DATA_DROPPED.toSet()
    }

    /**
     * 按 `PRAGMA table_info` 显式列清单整行读取（**不用 `SELECT *`**，重建表后
     * Robolectric legacy cursor 会复用过期列元数据），顺序为自然序（rowid 序）。
     */
    private fun snapshotRows(db: SupportSQLiteDatabase, table: String): List<Map<String, String?>> {
        val cols = RoomMigrationSupport.tableColumns(db, table)
        val sql = "SELECT ${cols.joinToString(", ") { "`$it`" }} FROM `$table`"
        val rows = mutableListOf<Map<String, String?>>()
        db.query(sql).use { cursor -> while (cursor.moveToNext()) rows += cursor.rowSnapshot(cols) }
        return rows
    }

    /** 按 cols 顺序把游标当前行读成 列名→文本值（NULL 保留为 null）。 */
    private fun Cursor.rowSnapshot(cols: List<String>): Map<String, String?> =
        cols.withIndex().associate { (index, name) ->
            name to if (isNull(index)) null else getString(index)
        }

    // ==================== 种子数据 ====================

    private fun seedRows(db: SupportSQLiteDatabase) {
        seedDiscipleRows(db)
        seedGameDataRows(db)
    }

    /**
     * 三行弟子：被删 4 列全部给非零标记值；`comprehension` 覆盖可核对值
     * （悟性保留口径边界），存续列（name/realm/cultivation）给可核对值。
     */
    private fun seedDiscipleRows(db: SupportSQLiteDatabase) {
        val (cols, types) = readColumns(db, "disciples")
        DISCIPLE_IDS.forEachIndexed { index, id ->
            val seeds = discipleSeedLiterals(id, index)
            val values = cols.mapIndexed { i, name -> seeds[name] ?: zeroLiteral(types[i]) }
            db.execSQL(
                "INSERT INTO disciples (${cols.joinToString(",")}) VALUES (${values.joinToString(",")})"
            )
        }
    }

    /** 列名→种子字面量：存续列给可核对值，被删 4 列给非零标记值，未列出的列走零值兜底。 */
    private fun discipleSeedLiterals(id: String, index: Int): Map<String, String> = mapOf(
        "id" to "'$id'",
        "slot_id" to "1",
        "name" to "'弟子_$id'",
        "isAlive" to "1",
        "realm" to "${7 + index}",
        "cultivation" to "${1000 + index}.5",
        "comprehension" to "${60 + index}",
        "talentIds" to "'talent_$id'",
        "physiqueIds" to "'physique_$id'",
        "affixIds" to "'affix_$id'",
        "aptitude" to "${70 + index}"
    )

    /** 两行 game_data（两个 slot）：血炼四列与 pending_trait_adds 给非零值，验证多行同时不受损。 */
    private fun seedGameDataRows(db: SupportSQLiteDatabase) {
        val (cols, types) = readColumns(db, "game_data")
        listOf(1 to "gd1", 2 to "gd2").forEach { (slot, id) ->
            val values = cols.mapIndexed { i, name ->
                when (name) {
                    "id" -> "'$id'"
                    "slot_id" -> "$slot"
                    "sectName" -> "'迁移宗$slot'"
                    "gameYear" -> "${40 + slot}"
                    "gameMonth" -> "$slot"
                    "spiritStones" -> "${5000L * slot}"
                    "bloodRefinements" -> "'{\"d$slot\":[\"mat_$slot\"]}'"
                    "activeBloodRefinements" -> "'{\"pool_$slot\":{\"discipleId\":\"d$slot\"}}'"
                    "bloodRefinementBonusTotals" -> "'{\"d$slot\":{}}'"
                    "bloodRefinementPctTotals" -> "'{\"d$slot\":{}}'"
                    "pending_trait_adds" -> "'trait_pending_$slot'"
                    else -> zeroLiteral(types[i])
                }
            }
            db.execSQL(
                "INSERT INTO game_data (${cols.joinToString(",")}) VALUES (${values.joinToString(",")})"
            )
        }
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
}
