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
 * 迁移 58→59 测试（G15 删列：`disciples.social_masterId`，师徒玩法整线下线）。
 *
 * 与 [RoomMigrationV57To58Test] 同构：旧版本插种子 → 运行迁移 → 验证删除列消失、
 * 索引按实体声明重建、其余列数据完整。本批删除面只落在 `disciples` 一张表
 * （[MIGRATION_58_59] 不重写 `game_data`，该表 128 列不变），故本类无 game_data 段、
 * 不搬模板的 `GAME_DATA_*` 常量，也不写 game_data 删除面断言。
 *
 * 本类的种子面与断言面：
 * 1. **种子非零值覆盖被删列**——v58 的 `social_masterId` 三行全部给非空标记值，
 *    迁移后断言该列 `columnExists == false`，证明"列消失"来自迁移而非"种子没插上"；
 * 2. **保留列逐值全等**：迁移前/后按 `PRAGMA table_info` 显式列清单整行快照
 *    （不使用 `SELECT *`——Robolectric legacy cursor 在 RENAME+CREATE+DROP 后会拿到
 *    过期列元数据），扣除被删列后逐格比对，其余 90 列的名字、顺序与每一格值都必须等价存活；
 * 3. **5 个存续索引全部回来**（name / realm+realmLayer / isAlive+realm / isAlive+status /
 *    discipleType）——本批不删索引，被删列也不在任何索引内；
 * 4. **行数与行序不变**：快照按自然序（rowid 序）读取，比对行序而非集合；
 * 5. **相邻保留列逐行非零值核对**——`comprehension`（悟性，G04 之后仍在册）与被删列在 v58 中
 *    紧邻的 `spiritStones` / `intelligence` / `charm` 一并显式播种并逐行核对：删列重建重写
 *    整张 `disciples`，同表相邻列被误伤即存档损坏级缺陷。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class RoomMigrationV58To59Test {

    companion object {
        /** disciples 表 v59 删除列（与迁移本体 V59_DISCIPLES_DROPPED_COLUMNS 同源，不另抄字面量） */
        private val DISCIPLES_DROPPED = V59_DISCIPLES_DROPPED_COLUMNS

        /** disciples 存续索引（本批不删索引，重建表后必须全部回来）。 */
        private val DISCIPLES_INDICES = listOf(
            "index_disciples_name",
            "index_disciples_realm_realmLayer",
            "index_disciples_isAlive_realm",
            "index_disciples_isAlive_status",
            "index_disciples_discipleType"
        )

        /** 种子弟子行序（迁移前后自然序必须与之一致）。 */
        private val DISCIPLE_IDS = listOf("d1", "d2", "d3")

        /** 种子行的槽位：覆盖复合主键 `(id, slot_id)` 的两列，行序仍与 [DISCIPLE_IDS] 对齐。 */
        private val DISCIPLE_SLOTS = listOf(1, 1, 2)

        /** 被删列的种子值（三行全非空——空串/NULL 会让"列消失"断言退化为空表假象）。 */
        private val DISCIPLE_SEED_MASTER_IDS = listOf("master_d1", "master_d2", "master_d3")

        /** v58 的 disciples 列数（= v59 的 90 个存续列 + 本批被删的 1 列），与 schema JSON 一致。 */
        private const val V58_DISCIPLES_COLUMN_COUNT = 91

        /** v59 的 disciples 列数（删列重建后必须精确等于此值）。 */
        private const val V59_DISCIPLES_COLUMN_COUNT = 90

        /** 相邻保留列：被删列的直接邻居 + 悟性列，逐行给可核对的非零值（判据 5）。 */
        private val ADJACENT_RETAINED_COLUMNS = listOf(
            "spiritStones", "intelligence", "charm", "comprehension"
        )
    }

    /**
     * 真实 Room 校验：v58 库升级到 v59（`disciples` 删 1 列）触发 onValidateSchema——
     * 迁移未删净实体已删除的列、或重建索引与 `Disciple` 的 @Index 声明不一致都会在此崩溃。
     */
    @Test
    fun `MIGRATION_58_59 passes real Room schema validation`() {
        val context = ApplicationProvider
            .getApplicationContext<android.content.Context>()
        val dbName = "m_58_59_room_validate"
        context.deleteDatabase(dbName)
        try {
            RoomMigrationSupport.createDatabaseFromSchema(context, dbName, 58).close()
            val db = Room.databaseBuilder(context, GameDatabase::class.java, dbName)
                .addMigrations(*ALL_MIGRATIONS)
                .build()
            val migrated = db.openHelper.writableDatabase
            assertFalse(
                "真实 Room 升级后 disciples 不应再有 social_masterId",
                RoomMigrationSupport.columnExists(migrated, "disciples", "social_masterId")
            )
            assertEquals(
                "真实 Room 升级后 disciples 列数应是 $V59_DISCIPLES_COLUMN_COUNT",
                V59_DISCIPLES_COLUMN_COUNT,
                RoomMigrationSupport.tableColumns(migrated, "disciples").size
            )
            db.close()
        } finally {
            context.deleteDatabase(dbName)
        }
    }

    @Test
    fun `MIGRATION_58_59 drops social_masterId and preserves other data`() {
        val context = ApplicationProvider
            .getApplicationContext<android.content.Context>()
        val dbName = "m_58_59_drop_columns"
        context.deleteDatabase(dbName)
        try {
            val db = RoomMigrationSupport.createDatabaseFromSchema(context, dbName, 58)
            seedDiscipleRows(db)
            val v58Columns = RoomMigrationSupport.tableColumns(db, "disciples")
            assertPreMigrationShape(db, v58Columns)
            assertDroppedColumnSeeded(db)
            val before = snapshotRows(db, "disciples")

            MIGRATION_58_59.migrate(db)

            assertPostMigrationShape(db, v58Columns)
            assertPreservedDataIntact(before, db)
            assertRetainedDataIntact(db)
            db.close()
        } finally {
            context.deleteDatabase(dbName)
        }
    }

    /** 重复执行安全：待删列已不存在时迁移直接返回（迁移中断重试依赖该幂等性）。 */
    @Test
    fun `MIGRATION_58_59 is idempotent after column is gone`() {
        val context = ApplicationProvider
            .getApplicationContext<android.content.Context>()
        val dbName = "m_58_59_idempotent"
        context.deleteDatabase(dbName)
        try {
            val db = RoomMigrationSupport.createDatabaseFromSchema(context, dbName, 58)
            seedDiscipleRows(db)
            MIGRATION_58_59.migrate(db)
            val first = snapshotRows(db, "disciples")

            MIGRATION_58_59.migrate(db)

            assertEquals("二次迁移不得改写 disciples 数据", first, snapshotRows(db, "disciples"))
            assertEquals(
                "二次迁移不得再动列数",
                V59_DISCIPLES_COLUMN_COUNT,
                RoomMigrationSupport.tableColumns(db, "disciples").size
            )
            db.close()
        } finally {
            context.deleteDatabase(dbName)
        }
    }

    // ==================== 形态断言 ====================

    /** 迁移前：v58 真实含被删列且列数为 91（证伪"种子没插上"的假绿）。 */
    private fun assertPreMigrationShape(db: SupportSQLiteDatabase, v58Columns: List<String>) {
        assertEquals(
            "两版列数常量必须与被删列集自洽（删除面变化时同步这两个常量）",
            V59_DISCIPLES_COLUMN_COUNT + DISCIPLES_DROPPED.size,
            V58_DISCIPLES_COLUMN_COUNT
        )
        assertEquals(
            "v58 弟子表应是 $V58_DISCIPLES_COLUMN_COUNT 列（$V59_DISCIPLES_COLUMN_COUNT 存续 + 1 被删）",
            V58_DISCIPLES_COLUMN_COUNT,
            v58Columns.size
        )
        for (col in DISCIPLES_DROPPED) {
            assertTrue("v58 应含 disciples.$col", RoomMigrationSupport.columnExists(db, "disciples", col))
        }
        assertTrue(
            "v58 应含 disciples.comprehension（悟性列）",
            RoomMigrationSupport.columnExists(db, "disciples", "comprehension")
        )
    }

    /** 迁移后：被删列消失；90 列名单与 v58 扣除被删列后逐项同序相同；索引与复合主键照常重建。 */
    private fun assertPostMigrationShape(db: SupportSQLiteDatabase, v58Columns: List<String>) {
        for (col in DISCIPLES_DROPPED) {
            assertFalse("迁移后 disciples.$col 应被删除",
                RoomMigrationSupport.columnExists(db, "disciples", col))
        }
        val v59Columns = RoomMigrationSupport.tableColumns(db, "disciples")
        assertEquals(
            "v59 弟子表应是 $V59_DISCIPLES_COLUMN_COUNT 列",
            V59_DISCIPLES_COLUMN_COUNT,
            v59Columns.size
        )
        assertEquals(
            "v59 弟子表列名单必须与 v58 扣除被删列后完全相同（含顺序，其余列一列不差）",
            v58Columns.filterNot { it in DISCIPLES_DROPPED },
            v59Columns
        )
        for (idx in DISCIPLES_INDICES) {
            assertTrue("索引 $idx 应重建", RoomMigrationSupport.indexExists(db, "disciples", idx))
        }
        assertTrue(
            "复合主键 (id, slot_id) 必须随删列重建保留",
            RoomMigrationSupport.primaryKeyExists(db, "disciples")
        )
        for (col in DISCIPLES_DROPPED) {
            assertFalse(
                "v59 不应再有 $col（本用例的删除面判据；[verifyDisciplesColumnsExist] 是 v40 链尾" +
                    "的历史形态断言，该端点此列仍在，不可复用于 v59）",
                RoomMigrationSupport.columnExists(db, "disciples", col)
            )
        }
    }

    /** 被删列的删除面判据：迁移前三行非空种子读得出，迁移后列本身不存在。 */
    private fun assertDroppedColumnSeeded(db: SupportSQLiteDatabase) {
        assertEquals(
            "v58 关系列必须带上三行非零种子（列值随列一并删除，才是迁移删列的直接证据）",
            DISCIPLE_SEED_MASTER_IDS,
            readSingleColumn(db, "SELECT social_masterId FROM disciples")
        )
    }

    // ==================== 数据完整性断言 ====================

    /**
     * 迁移后存续数据完整性：行数不变，且**扣除被删列后的整行快照逐格相等**——
     * 快照是有序 Map 列表，比对同时锁住"列名集合一致""列序一致""每格值一致""行序一致"。
     */
    private fun assertPreservedDataIntact(
        before: List<Map<String, String?>>,
        db: SupportSQLiteDatabase
    ) {
        val after = snapshotRows(db, "disciples")
        assertEquals("disciples 迁移后行数不变", before.size, after.size)
        val dropped = DISCIPLES_DROPPED.toSet()
        assertEquals(
            "disciples 存续列必须逐值不变（扣除 ${dropped.size} 个被删列）",
            before.map { row -> row.filterKeys { it !in dropped } },
            after
        )
    }

    /**
     * 保留口径边界：悟性列与紧邻被删列的三列逐行非零核对，且弟子行序不变（判据 4 + 判据 5）。
     */
    private fun assertRetainedDataIntact(db: SupportSQLiteDatabase) {
        val expectedByColumn = mapOf(
            "spiritStones" to listOf("5000", "5001", "5002"),
            "intelligence" to listOf("70", "71", "72"),
            "charm" to listOf("75", "76", "77"),
            "comprehension" to listOf("60", "61", "62")
        )
        assertEquals(
            "相邻保留列清单与种子断言面不一致（新增/改名时要一并补期望值）",
            expectedByColumn.keys.toList(),
            ADJACENT_RETAINED_COLUMNS
        )
        for ((column, expected) in expectedByColumn) {
            assertEquals(
                "$column 必须逐行原样存活（与 v57→v58 守 comprehension 同法，行序也不变）",
                expected,
                readSingleColumn(db, "SELECT $column FROM disciples")
            )
        }
        assertEquals(
            "弟子行序不变（自然序即 rowid 序，重建表只搬运不改写顺序）",
            DISCIPLE_IDS,
            readSingleColumn(db, "SELECT id FROM disciples").map { it.toString() }
        )
        assertEquals(
            "弟子行数不变",
            "${DISCIPLE_IDS.size}",
            RoomMigrationSupport.queryString(db, "SELECT COUNT(*) FROM disciples")
        )
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

    /** 按 SQL 读取单列文本值列表（NULL 保留为 null），列表顺序即行序。 */
    private fun readSingleColumn(db: SupportSQLiteDatabase, sql: String): List<String?> {
        val values = mutableListOf<String?>()
        db.query(sql).use { c ->
            while (c.moveToNext()) values += if (c.isNull(0)) null else c.getString(0)
        }
        return values
    }

    // ==================== 种子数据 ====================

    /**
     * 三行弟子：被删列给非空标记值；悟性列与被删列的相邻三列覆盖可核对值（保留口径边界），
     * 其余存续列（name/realm/realmLayer/cultivation/status/discipleType 等索引所在列）给可核对值，
     * 未列出的列按列类型落零值兜底。
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

    /** 列名→种子字面量：被删列给非空标记值，相邻保留列与存续列给可核对的非零值。 */
    private fun discipleSeedLiterals(id: String, index: Int): Map<String, String> = mapOf(
        "id" to "'$id'",
        "slot_id" to "${DISCIPLE_SLOTS[index]}",
        "name" to "'弟子_$id'",
        "isAlive" to "1",
        "realm" to "${7 + index}",
        "realmLayer" to "${index + 1}",
        "status" to "'CULTIVATING'",
        "discipleType" to "'OUTSIDER'",
        "cultivation" to "${1000 + index}.5",
        "social_masterId" to "'${DISCIPLE_SEED_MASTER_IDS[index]}'",
        "spiritStones" to "${5000 + index}",
        "intelligence" to "${70 + index}",
        "charm" to "${75 + index}",
        "comprehension" to "${60 + index}",
        "teaching" to "${5 + index}"
    )

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
