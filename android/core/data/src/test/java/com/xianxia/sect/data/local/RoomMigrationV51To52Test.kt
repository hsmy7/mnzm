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
 * 迁移 51→52 测试（B19 Room 死列清理批：`game_data` 删除两列死列）。
 *
 * 删除面：`battleTeam`（**单数**，TEXT NULL）+ `aiBattleTeams`（TEXT NOT NULL）——
 * 全仓零生产者/零消费者，且均带 `@kotlinx.serialization.Transient`（不进 `.sav`/proto）。
 * 判归与勘察见 `docs/parallel-batches-w5/batch-B19-room-dead-columns.md` §0。
 *
 * 本类覆盖四层：
 * 1. **真实 Room 校验**：v51 库经 `Room.databaseBuilder` 升到 v52，触发
 *    `onValidateSchema`（列/索引/主键全等比较）——若迁移残留实体已删列即在此红；
 * 2. **删列 + 存档回归逐字段等价**：v51 种子行（含**非空**单数 `battleTeam`）迁移后，
 *    除两死列外**所有列值逐字段全等**；
 * 3. **全链回归**：v39 旧档（单数 `battleTeam` 活跃期最后一版）经 40→52 全链迁移，
 *    被删列集**精确等于** `{autoSaveIntervalMonths, battleTeam, aiBattleTeams}`，
 *    其余交集列逐字段全等；
 * 4. **结构守卫（防回流）**：`52.json` 的 `game_data` 不含两死列、`51.json` 含
 *    （对照面非空转）+ 列数恰少 2。
 *
 * ⚠️ 全表读取**不得用 `SELECT *`**：Robolectric legacy cursor 对同一 SQL 串缓存列元数据，
 * 重建表（RENAME + CREATE + DROP）后 `SELECT *` 会拿到**过期列清单**（实测两种假象：
 * `IndexOutOfBoundsException: Index 139 out of bounds for length 139` 与"被删列集为空"）
 * ⇒ 统一走 [readAllRows]（按 `PRAGMA table_info` 显式列清单构造 SELECT）。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class RoomMigrationV51To52Test {

    private companion object {
        val SCHEMA_DIR: File = File("schemas", "com.xianxia.sect.data.local.GameDatabase")
        val M51_52 = MIGRATION_51_52

        /** 被删两列的旧档哨兵值（非空 ⇒ 证明"删列"而非"值悄悄丢"）。 */
        const val SINGULAR_MARKER = "B19_SINGULAR_TEAM_MARKER"
        const val AI_MARKER = "B19_AI_TEAMS_MARKER"

        /** 活跃复数列哨兵值（迁移后必须逐字保留）。 */
        const val PLURAL_MARKER = "B19_PLURAL_TEAMS_MARKER"
        const val USED_NUMBERS_MARKER = "B19_USED_NUMBERS_MARKER"

        const val DROPPED_BY_V52 = "battleTeam"
        const val DROPPED_AI_BY_V52 = "aiBattleTeams"

        /** game_data 的 5 个索引（重建表后必须全部回来，Room 校验会比对索引）。 */
        val GAME_DATA_INDICES = listOf(
            "index_game_data_slot_id",
            "index_game_data_lastSaveTime",
            "index_game_data_gameYear_gameMonth",
            "index_game_data_sectName",
            "index_game_data_spiritStones"
        )
    }

    // ==================== 1. 真实 Room 校验 ====================

    @Test
    fun `MIGRATION_51_TO_52 passes real Room schema validation`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val dbName = "m_51_52_room_validate"
        context.deleteDatabase(dbName)
        try {
            RoomMigrationSupport.createDatabaseFromSchema(context, dbName, 51).close()
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

    // ==================== 2. 删列 + 存档回归逐字段等价 ====================

    @Test
    fun `MIGRATION_51_TO_52 drops dead columns and preserves every remaining column`() {
        withSeededV51Db("m_51_52_drop_dead_columns") { db ->
            val before = readAllRows(db)
            assertSeedHasLiveDeadColumns(before)

            RoomMigrationSupport.applyMigrationsSequentially(db, listOf(M51_52))

            assertDeadColumnsDropped(db)
            assertActiveTeamColumnsPreserved(db)

            // 存档回归逐字段等价（含 NULL 语义）+ 列数恰少 2
            val after = readAllRows(db)
            assertFieldEquivalence(before, after, dropped = setOf(DROPPED_BY_V52, DROPPED_AI_BY_V52))
            assertEquals(
                "game_data 列数应恰少 2 列",
                before.first().keys.size - 2,
                after.first().keys.size
            )
            assertGameDataIndicesRebuilt(db)
        }
    }

    // ==================== 3. 全链回归（v39 旧档 → v52） ====================

    @Test
    fun `V39 legacy save migrates to v52 with exact dropped set and zero field drift`() {
        withSeededV39Db("m_39_52_full_chain") { db ->
            val before = readAllRows(db)
            // 全链 40→52（迁移链取自单点登记表）
            RoomMigrationSupport.applyMigrationsSequentially(
                db, ALL_MIGRATIONS.filter { it.startVersion >= 39 }
            )
            val after = readAllRows(db)

            val dropped = assertExactDroppedSetAcrossChain(before, after)
            assertPluralColumnsNotBackfilledFromSingular(db, after)
            assertFieldEquivalence(before, after, dropped = dropped)
        }
    }

    // ==================== 4. 幂等 ====================

    @Test
    fun `MIGRATION_51_TO_52 is idempotent when dead columns already absent`() {
        withSeededV51Db("m_51_52_idempotent") { db ->
            RoomMigrationSupport.applyMigrationsSequentially(db, listOf(M51_52))
            val once = readAllRows(db)
            // 二次执行：待删列已不存在 ⇒ 直接返回，不得抛错、不得清表
            RoomMigrationSupport.applyMigrationsSequentially(db, listOf(M51_52))
            val twice = readAllRows(db)

            assertEquals("幂等重放不得改变行数", once.size, twice.size)
            assertEquals("幂等重放不得改变任何列值", once, twice)
        }
    }

    // ==================== 5. 结构守卫（防回流） ====================

    /**
     * 静态守卫：锁"死列不得回流"。
     *
     * **对照面非空转**：`51.json` 必须仍含两列（历史快照不动），`52.json` 必须不含
     * ——若某天有人把字段加回实体，`52.json` 重生成即含该列，本断言变红。
     */
    @Test
    fun `schema 52 excludes dead columns while 51 keeps them as historical snapshot`() {
        val v51 = gameDataColumns(51)
        val v52 = gameDataColumns(52)

        assertTrue("51.json 应仍含 battleTeam（历史快照对照面）", DROPPED_BY_V52 in v51)
        assertTrue("51.json 应仍含 aiBattleTeams（历史快照对照面）", DROPPED_AI_BY_V52 in v51)
        assertFalse("52.json 不得含 battleTeam（死列不得回流）", DROPPED_BY_V52 in v52)
        assertFalse("52.json 不得含 aiBattleTeams（死列不得回流）", DROPPED_AI_BY_V52 in v52)

        assertEquals("game_data 列数应恰少 2 列", v51.size - 2, v52.size)
        assertTrue("活跃复数列 battle_teams 必须在 52.json", "battle_teams" in v52)
        assertTrue("v51 新增列 terrain_tiles 必须在 52.json", "terrain_tiles" in v52)
    }

    // ==================== 库生命周期夹具 ====================

    /** 建 v51 库 + 种入两行旧档样本 → 回调（退出时关闭并删除库） */
    private fun withSeededV51Db(dbName: String, block: (SupportSQLiteDatabase) -> Unit) =
        withDb(dbName, 51) { db ->
            // 两行覆盖"单数 battleTeam 非空"与"NULL"两种历史形态；每列填按列名派生的
            // 可区分值 ⇒ 逐字段等价断言具备分辨力（非全 0/'' 空转）
            insertSeedRow(
                db, id = "sect-b19-a", slotId = 1,
                overrides = mapOf(
                    "sectName" to "'死列清理批'",
                    "gameYear" to "77",
                    "spiritStones" to "123456",
                    DROPPED_BY_V52 to "'$SINGULAR_MARKER'",
                    DROPPED_AI_BY_V52 to "'$AI_MARKER'",
                    "battle_teams" to "'$PLURAL_MARKER'",
                    "used_team_numbers" to "'$USED_NUMBERS_MARKER'",
                    "battle_teams_initialized" to "1"
                )
            )
            insertSeedRow(
                db, id = "sect-b19-b", slotId = 2,
                overrides = mapOf(
                    "sectName" to "'死列清理批二'",
                    DROPPED_BY_V52 to "NULL",
                    DROPPED_AI_BY_V52 to "'$AI_MARKER'"
                )
            )
            block(db)
        }

    /** 建 v39 库（单数 `battleTeam` 仍是"活跃列"的最后一版）+ 种入非空哨兵值 → 回调 */
    private fun withSeededV39Db(dbName: String, block: (SupportSQLiteDatabase) -> Unit) =
        withDb(dbName, 39) { db ->
            insertSeedRow(
                db, id = "sect-b19-legacy", slotId = 3,
                overrides = mapOf(
                    "sectName" to "'旧档宗门'",
                    "gameYear" to "9",
                    "spiritStones" to "88888",
                    DROPPED_BY_V52 to "'$SINGULAR_MARKER'",
                    DROPPED_AI_BY_V52 to "'$AI_MARKER'"
                )
            )
            block(db)
        }

    /** 建库 → 回调 → 关闭并删除（库名清理统一在此，测试体不重复 try/finally） */
    private fun withDb(dbName: String, version: Int, block: (SupportSQLiteDatabase) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        context.deleteDatabase(dbName)
        try {
            val db = RoomMigrationSupport.createDatabaseFromSchema(context, dbName, version)
            block(db)
            db.close()
        } finally {
            context.deleteDatabase(dbName)
        }
    }

    // ==================== 断言助手 ====================

    /** 种子自检：旧档样本必须真带非空死列值（否则等价断言无对照面） */
    private fun assertSeedHasLiveDeadColumns(before: List<Map<String, String?>>) {
        assertEquals("种子行数", 2, before.size)
        assertTrue(
            "旧档样本必须真的带非空单数 battleTeam（否则等价断言无对照面）",
            before.any { it[DROPPED_BY_V52] == SINGULAR_MARKER }
        )
        assertTrue(
            "旧档样本必须带非空 aiBattleTeams",
            before.all { it[DROPPED_AI_BY_V52] == AI_MARKER }
        )
    }

    /** 两死列已从 game_data 删除 */
    private fun assertDeadColumnsDropped(db: SupportSQLiteDatabase) {
        assertFalse(
            "game_data.battleTeam 应在 v52 被删除",
            RoomMigrationSupport.columnExists(db, "game_data", DROPPED_BY_V52)
        )
        assertFalse(
            "game_data.aiBattleTeams 应在 v52 被删除",
            RoomMigrationSupport.columnExists(db, "game_data", DROPPED_AI_BY_V52)
        )
    }

    /** 活跃复数列（v40 引入的战斗队伍持久化面三列）逐字保留 */
    private fun assertActiveTeamColumnsPreserved(db: SupportSQLiteDatabase) {
        val expectations = mapOf(
            "battle_teams" to PLURAL_MARKER,
            "used_team_numbers" to USED_NUMBERS_MARKER,
            "battle_teams_initialized" to "1"
        )
        for ((column, expected) in expectations) {
            assertEquals(
                "$column 活跃列零丢失", expected,
                RoomMigrationSupport.queryString(
                    db, "SELECT `$column` FROM game_data WHERE id = 'sect-b19-a'"
                )
            )
        }
    }

    /** 5 个索引全部重建（Room 迁移后校验会比对索引） */
    private fun assertGameDataIndicesRebuilt(db: SupportSQLiteDatabase) {
        for (idx in GAME_DATA_INDICES) {
            assertTrue(
                "索引 $idx 应在重建后回来",
                RoomMigrationSupport.indexExists(db, "game_data", idx)
            )
        }
    }

    /**
     * 全链被删列集精确等于三列（v50 删 autoSaveIntervalMonths + v52 删两死列），
     * 返回该集合供逐字段等价断言复用。
     */
    private fun assertExactDroppedSetAcrossChain(
        before: List<Map<String, String?>>,
        after: List<Map<String, String?>>
    ): Set<String> {
        assertEquals("全链迁移后行数不变", before.size, after.size)
        val dropped = before.first().keys - after.first().keys
        assertEquals(
            "全链被删列集必须精确等于 {autoSaveIntervalMonths, battleTeam, aiBattleTeams}",
            setOf("autoSaveIntervalMonths", DROPPED_BY_V52, DROPPED_AI_BY_V52),
            dropped
        )
        return dropped
    }

    /** v40/v51 新增列按 DEFAULT 落值；**旧档不得把单数 battleTeam 搬进复数 battle_teams** */
    private fun assertPluralColumnsNotBackfilledFromSingular(
        db: SupportSQLiteDatabase,
        after: List<Map<String, String?>>
    ) {
        assertTrue(
            "battle_teams 旧档应取 v40 DEFAULT ''（不搬运单数 battleTeam）",
            after.first().containsKey("battle_teams")
        )
        assertEquals(
            "旧档 battle_teams 不得被单数 battleTeam 值污染（判归：业务上可弃，不搬运）",
            "",
            RoomMigrationSupport.queryString(
                db, "SELECT battle_teams FROM game_data WHERE id = 'sect-b19-legacy'"
            )
        )
        assertTrue("v51 新增 map_gen_version 应在", after.first().containsKey("map_gen_version"))
    }

    // ==================== 读取/断言基建 ====================

    /** 读取 schema JSON 中 game_data 实体的 columnName 列表 */
    private fun gameDataColumns(version: Int): List<String> {
        val schemaFile = File(SCHEMA_DIR, "$version.json")
        assertTrue("schema 文件应存在: ${schemaFile.absolutePath}", schemaFile.exists())
        val database = JsonParser.parseString(schemaFile.readText()).asJsonObject
            .getAsJsonObject("database")
        val entities = database.getAsJsonArray("entities")
        for (i in 0 until entities.size()) {
            val entity = entities[i].asJsonObject
            if (entity.get("tableName").asString != "game_data") continue
            val fields = entity.getAsJsonArray("fields")
            return (0 until fields.size()).map { fields[it].asJsonObject.get("columnName").asString }
        }
        error("schema $version.json 中未找到 game_data 实体")
    }

    /**
     * 按 PRAGMA 列清单生成 INSERT：NOT NULL 无默认列按亲和性填 0/0.0/''，
     * TEXT 列填 `v_<列名>`（可区分），[overrides] 覆盖关键列；可空列默认留 NULL。
     */
    private fun insertSeedRow(
        db: SupportSQLiteDatabase,
        id: String,
        slotId: Int,
        overrides: Map<String, String>
    ) {
        data class Col(val name: String, val type: String, val notNull: Boolean, val dflt: String?)

        val cols = mutableListOf<Col>()
        db.query("PRAGMA table_info(game_data)").use { cursor ->
            while (cursor.moveToNext()) {
                cols += Col(
                    name = cursor.getString(cursor.getColumnIndexOrThrow("name")),
                    type = cursor.getString(cursor.getColumnIndexOrThrow("type")),
                    notNull = cursor.getInt(cursor.getColumnIndexOrThrow("notnull")) == 1,
                    dflt = cursor.getString(cursor.getColumnIndexOrThrow("dflt_value"))
                )
            }
        }
        val values = cols.joinToString(", ") { col ->
            when {
                col.name == "id" -> "'$id'"
                col.name == "slot_id" -> "$slotId"
                overrides.containsKey(col.name) -> overrides.getValue(col.name)
                col.type.uppercase().contains("INT") -> col.dflt ?: "0"
                col.type.uppercase().contains("REAL") -> col.dflt ?: "0.0"
                col.notNull -> col.dflt ?: "'v_${col.name}'"
                // 可空 TEXT：默认留 NULL，逐字段等价断言因此覆盖 NULL 语义
                else -> "NULL"
            }
        }
        db.execSQL("INSERT INTO game_data (${cols.joinToString(", ") { it.name }}) VALUES ($values)")
    }

    /**
     * 读取 game_data 全行（列名 → 值，NULL 保持 null），按 slot_id 排序。
     *
     * ⚠️ **不得用 `SELECT *`**：Robolectric legacy cursor 对同一 SQL 串缓存列元数据，
     * 重建表后 `SELECT *` 会拿到**过期列清单**（见类 KDoc）。故显式按 PRAGMA 列清单构造 SELECT。
     */
    private fun readAllRows(db: SupportSQLiteDatabase): List<Map<String, String?>> {
        val cols = RoomMigrationSupport.tableColumns(db, "game_data")
        val sql = "SELECT ${cols.joinToString(", ") { "`$it`" }} FROM game_data ORDER BY slot_id"
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

    /**
     * 逐字段等价断言：两快照的行数、被删列集、存活列**相对键序**与**每个存活列的值**
     * 必须全等（NULL 亦须相等）。
     *
     * 存活列口径 = 两侧交集（全链迁移会追加新列，新列不在对照面内）。
     */
    private fun assertFieldEquivalence(
        before: List<Map<String, String?>>,
        after: List<Map<String, String?>>,
        dropped: Set<String>
    ) {
        assertEquals("行数必须相等", before.size, after.size)
        val beforeKeys = before.first().keys
        val afterKeys = after.first().keys
        assertEquals("被删列集必须与调用方声明一致", dropped, beforeKeys - afterKeys)

        val common = afterKeys.filter { it in beforeKeys }
        assertEquals(
            "存活列相对键序必须一致（重建表保持原列序，新增列追加于尾）",
            beforeKeys.filter { it in afterKeys },
            common
        )
        for (r in before.indices) {
            val b = before[r]
            val a = after[r]
            for (col in common) {
                assertEquals("行$r 列 `$col` 迁移前后必须逐字段全等", b[col], a[col])
            }
        }
    }
}
