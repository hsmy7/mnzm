package com.xianxia.sect.data.local

import android.content.ContentValues
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
 * 迁移 62→63 测试（孕养类加成丹药退役，装备重构 B2 / R11，方案 §5.7）。
 *
 * 与 [RoomMigrationV61To62Test] 同构：v62 schema 建库插种子 → 运行迁移 → 验证
 * 三步迁移面 + 其余数据零丢失 + 真实 Room schema 校验 + 幂等：
 * 1. `disciples` 删 `pillNurtureSpeedBonus` 一列（生效中孕养速度临时效果随列
 *    清零，不补偿），其余 83 列逐行逐格零丢失、索引与复合主键保留；
 * 2. `game_data` 增 `nurture_pills_retired`（DEFAULT 0）；
 * 3. `recipes` 表清 `nurtureSpeed_*`/`nurtureAdd_*` 前缀行，非孕养配方保留。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class RoomMigrationV62To63Test {

    companion object {
        /** v63 删除的列。 */
        private val DROPPED = listOf("pillNurtureSpeedBonus")

        /** v63 的 disciples 列数（= v62 的 84 − R11 删 pillNurtureSpeedBonus）。 */
        private const val V63_DISCIPLES_COLUMN_COUNT = 83

        /** 全链升到当前 DATABASE_VERSION（B3 v64：v63 83 列 −9 旧列 +5 部位列） */
        private const val FINAL_DISCIPLES_COLUMN_COUNT = 79

        private val INDICES = listOf(
            "index_disciples_name",
            "index_disciples_realm_realmLayer",
            "index_disciples_isAlive_realm",
            "index_disciples_isAlive_status",
            "index_disciples_discipleType"
        )
    }

    /** 真实 Room 校验：v62 库升级到 v63 触发 onValidateSchema。 */
    @Test
    fun `MIGRATION_62_63 passes real Room schema validation`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val dbName = "m_62_63_room_validate"
        context.deleteDatabase(dbName)
        try {
            RoomMigrationSupport.createDatabaseFromSchema(context, dbName, 62).close()
            val db = Room.databaseBuilder(context, GameDatabase::class.java, dbName)
                .addMigrations(*ALL_MIGRATIONS)
                .build()
            val migrated = db.openHelper.writableDatabase
            assertFalse(
                "真实 Room 升级后 disciples 不应再有 pillNurtureSpeedBonus 列",
                RoomMigrationSupport.columnExists(migrated, "disciples", "pillNurtureSpeedBonus")
            )
            assertTrue(
                "真实 Room 升级后 game_data 应有 nurture_pills_retired 列",
                RoomMigrationSupport.columnExists(migrated, "game_data", "nurture_pills_retired")
            )
            assertEquals(
                "真实 Room 全链升到终版（v64）后 disciples 列数应是 $FINAL_DISCIPLES_COLUMN_COUNT",
                FINAL_DISCIPLES_COLUMN_COUNT,
                RoomMigrationSupport.tableColumns(migrated, "disciples").size
            )
            db.close()
        } finally {
            context.deleteDatabase(dbName)
        }
    }

    @Suppress("LongMethod") // 列面/标志列/recipes 清行/零丢失四段断言逐段平铺，拆分遮蔽完整性（V61To62 同先例）
    @Test
    fun `MIGRATION_62_63 drops column adds flag and purges nurture recipes`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val dbName = "m_62_63_purge"
        context.deleteDatabase(dbName)
        try {
            val db = RoomMigrationSupport.createDatabaseFromSchema(context, dbName, 62)
            seedDiscipleRows(db)
            seedRecipeRows(db)
            val v62Columns = RoomMigrationSupport.tableColumns(db, "disciples")
            val before = snapshotRows(db, "disciples")

            MIGRATION_62_63.migrate(db)

            // ① 列面：删 1 列 + 其余列名一列不差 + 索引/主键保留
            for (col in DROPPED) {
                assertFalse(
                    "迁移后 disciples.$col 应被删除",
                    RoomMigrationSupport.columnExists(db, "disciples", col)
                )
            }
            assertEquals(
                "v63 弟子表应是 $V63_DISCIPLES_COLUMN_COUNT 列",
                V63_DISCIPLES_COLUMN_COUNT,
                RoomMigrationSupport.tableColumns(db, "disciples").size
            )
            assertEquals(
                "v63 列名单必须 = v62 − pillNurtureSpeedBonus（其余一列不差）",
                v62Columns.filterNot { it in DROPPED }.toSet(),
                RoomMigrationSupport.tableColumns(db, "disciples").toSet()
            )
            for (idx in INDICES) {
                assertTrue("索引 $idx 应保留", RoomMigrationSupport.indexExists(db, "disciples", idx))
            }
            assertTrue(
                "复合主键 (id, slot_id) 必须随重建保留",
                RoomMigrationSupport.primaryKeyExists(db, "disciples")
            )

            // ② game_data 新列 DEFAULT 0
            assertEquals(
                "nurture_pills_retired DEFAULT",
                "0",
                RoomMigrationSupport.columnDefault(db, "game_data", "nurture_pills_retired")
            )

            // ③ recipes 清理：孕养前缀行删除、其余保留
            db.query("SELECT id FROM recipes").use { c ->
                val ids = mutableSetOf<String>()
                while (c.moveToNext()) ids.add(c.getString(0))
                assertTrue(
                    "孕养配方行应被清空，实际残留 $ids",
                    ids.none { it.startsWith("nurtureSpeed_") || it.startsWith("nurtureAdd_") }
                )
                assertEquals(
                    "非孕养配方必须保留",
                    setOf("cultivationSpeed_1_low", "cultivationAdd_6_high"),
                    ids
                )
            }

            // ④ 保留列零丢失
            val retained = v62Columns.filterNot { it in DROPPED }
            for ((i, row) in before.withIndex()) {
                val after = queryRow(db, "d$i")
                for (col in retained) {
                    assertEquals("d$i 的保留列 $col 零丢失", row[col], after[col])
                }
            }
            db.close()
        } finally {
            context.deleteDatabase(dbName)
        }
    }

    @Test
    fun `MIGRATION_62_63 is idempotent`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val dbName = "m_62_63_idempotent"
        context.deleteDatabase(dbName)
        try {
            val db = RoomMigrationSupport.createDatabaseFromSchema(context, dbName, 62)
            seedDiscipleRows(db)
            seedRecipeRows(db)
            MIGRATION_62_63.migrate(db)
            val firstDisciples = snapshotRows(db, "disciples")

            MIGRATION_62_63.migrate(db)

            assertEquals(
                "二次迁移不得改写 disciples 数据",
                firstDisciples,
                snapshotRows(db, "disciples")
            )
            assertEquals(
                "二次迁移不得再动列数",
                V63_DISCIPLES_COLUMN_COUNT,
                RoomMigrationSupport.tableColumns(db, "disciples").size
            )
            db.close()
        } finally {
            context.deleteDatabase(dbName)
        }
    }

    // ==================== 种子 ====================

    /**
     * 两行种子（覆盖非零孕养列值），列清单与 62.json disciples schema 对齐。
     */
    @Suppress("LongMethod") // 83 列种子逐列平铺：与 62.json schema 对齐的清单，拆分遮蔽完整性（V61To62 seedAllRows 同先例）
    private fun seedDiscipleRows(db: SupportSQLiteDatabase) {
        for (i in 0..1) {
            val values = ContentValues().apply {
                put("id", "d$i")
                put("slot_id", 1)
                put("name", "弟子$i")
                put("surname", "测")
                put("realm", 9)
                put("realmLayer", 1)
                put("cultivation", 10.0)
                put("cultivationCheckpoint", 0L)
                put("cultivationCheckpointGameMonth", 0)
                put("spiritRootType", "metal")
                put("isAlive", 1)
                put("gender", "male")
                put("portraitRes", "")
                put("templateId", "")
                put("manualIds", "[]")
                put("manualMasteries", "{}")
                put("status", "IDLE")
                put("statusData", "{}")
                put("cultivationSpeedBonus", 0.0)
                put("cultivationSpeedDuration", 0)
                put("discipleType", "outer")
                put("cultivationCompletionMonth", 0)
                put("manualCompletionMonth", 0)
                put("manualCompletionPhase", 1)
                put("equipmentNurturingCompletionMonth", 0)
                put("equipmentNurturingCompletionPhase", 1)
                put("baseHp", 100 + i)
                put("baseMp", 50 + i)
                put("baseAttack", 20 + i)
                put("baseDefense", 15 + i)
                put("baseSpeed", 15 + i)
                put("hpVariance", 5)
                put("mpVariance", -5)
                put("attackVariance", 0)
                put("defenseVariance", 0)
                put("innateDamageType", "PHYSICAL")
                put("speedVariance", 0)
                put("totalCultivation", 0L)
                put("breakthroughCount", 0)
                put("breakthroughFailCount", 0)
                put("currentHp", -1)
                put("currentMp", -1)
                put("pillAttackBonus", 3 + i)
                put("pillDefenseBonus", 4 + i)
                put("pillHpBonus", 1 + i)
                put("pillMpBonus", 2 + i)
                put("pillSpeedBonus", 0)
                put("pillCritRateBonus", 0.0)
                put("pillCritEffectBonus", 0.0)
                put("pillCultivationSpeedBonus", 0.0)
                put("pillSkillExpSpeedBonus", 0.0)
                put("pillNurtureSpeedBonus", 0.25 + i)
                put("pillEffectDuration", 0)
                put("activePillCategory", "")
                put("weaponId", "")
                put("armorId", "")
                put("bootsId", "")
                put("accessoryId", "")
                put("weaponNurture", "")
                put("armorNurture", "")
                put("bootsNurture", "")
                put("accessoryNurture", "")
                put("storageBagItems", "[]")
                put("storageBagSpiritStones", 0L)
                put("spiritStones", 0)
                put("intelligence", 50)
                put("charm", 50)
                put("comprehension", 50)
                put("artifactRefining", 50)
                put("pillRefining", 50)
                put("spiritPlanting", 50)
                put("mining", 50)
                put("teaching", 50)
                put("morality", 50)
                put("salaryPaidCount", 0)
                put("salaryMissedCount", 0)
                put("alchemyLevel", 0)
                put("alchemyPromotionCount", 0)
                put("forgeLevel", 0)
                put("forgePromotionCount", 0)
                put("usage_usedFunctionalPillTypes", "[]")
                put("usage_recruitedMonth", 0)
                put("usage_hasReviveEffect", 0)
                put("usage_hasClearAllEffect", 0)
            }
            db.insert("disciples", android.database.sqlite.SQLiteDatabase.CONFLICT_FAIL, values)
        }
    }

    /** 三行配方种子：两条孕养（删除目标）+ 两条非孕养（保留目标）。 */
    private fun seedRecipeRows(db: SupportSQLiteDatabase) {
        val ids = listOf(
            "nurtureSpeed_1_low",
            "nurtureAdd_6_high",
            "cultivationSpeed_1_low",
            "cultivationAdd_6_high"
        )
        for (id in ids) {
            val values = ContentValues().apply {
                put("id", id)
                put("slot_id", 1)
                put("name", "配方$id")
                put("description", "")
                put("type", "PILL")
                put("isUnlocked", 1)
                put("unlockYear", 1)
                put("unlockMonth", 1)
                put("requiredMaterials", "{}")
                put("outputItemId", id)
                put("outputItemName", "产出$id")
                put("outputQuantity", 1)
                put("duration", 1)
            }
            db.insert("recipes", android.database.sqlite.SQLiteDatabase.CONFLICT_FAIL, values)
        }
    }

    // ==================== 断言辅助 ====================

    private fun queryRow(db: SupportSQLiteDatabase, id: String): Map<String, String?> {
        val out = mutableMapOf<String, String?>()
        db.query("SELECT * FROM disciples WHERE id = ?", arrayOf(id)).use { c ->
            assertTrue("种子行 $id 应存在", c.moveToFirst())
            for (name in c.columnNames) {
                val idx = c.getColumnIndex(name)
                out[name] = if (c.isNull(idx)) null else c.getString(idx)
            }
        }
        return out
    }

    @Suppress("NestedBlockDepth") // use + while：cursor 遍历样板（V61To62 同先例）
    private fun snapshotRows(db: SupportSQLiteDatabase, table: String): List<Map<String, String?>> {
        val rows = mutableListOf<Map<String, String?>>()
        db.query("SELECT * FROM $table").use { c ->
            while (c.moveToNext()) {
                val row = mutableMapOf<String, String?>()
                for (name in c.columnNames) {
                    val idx = c.getColumnIndex(name)
                    row[name] = if (c.isNull(idx)) null else c.getString(idx)
                }
                rows.add(row)
            }
        }
        return rows
    }
}
