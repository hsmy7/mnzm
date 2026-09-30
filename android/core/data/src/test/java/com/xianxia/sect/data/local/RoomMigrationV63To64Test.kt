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
 * 迁移 63→64 测试（装备体系原子替换，装备重构 B3，方案 §5.1/§6.5 A1）。
 *
 * 与 [RoomMigrationV62To63Test] 同构：v63 schema 建库插种子 → 运行迁移 → 验证
 * 七步迁移面 + 真实 Room schema 校验 + 幂等：
 * 1. 影子表搬运：`legacy_equipment_stacks`/`legacy_equipment_instances` 保行
 *    （补偿数据源，LegacyEquipmentCompensationRule 读后清）；
 * 2. `equipment_stacks` 表删除（R6 堆叠退役）；
 * 3. `equipment_instances` 按新模型重建为空表（旧装备作废，R2 + §5.4 补偿）；
 * 4. `disciples` 增 5 部位列 + 清空六槽位（含复用 weaponId，A1 幽灵件兜底）；
 * 5. `disciples` 删 9 旧列（三槽 + 四 nurture + 孕养 checkpoint 两列）；
 * 6. `game_data` 增 `legacy_equipment_compensated`（DEFAULT 0）；
 * 7. 其余数据零丢失、索引与复合主键保留。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class RoomMigrationV63To64Test {

    companion object {
        /** v64 删除的 9 列（方案 §5.1 删列清单）。 */
        private val DROPPED = listOf(
            "armorId", "bootsId", "accessoryId",
            "weaponNurture", "armorNurture", "bootsNurture", "accessoryNurture",
            "equipmentNurturingCompletionMonth", "equipmentNurturingCompletionPhase"
        )

        /** v64 新增的 5 个部位列。 */
        private val ADDED = listOf("headId", "bodyId", "handsId", "feetId", "legsId")

        /** v64 的 disciples 列数（= v63 的 83 − 9 删 + 5 增）。 */
        private const val V64_DISCIPLES_COLUMN_COUNT = 79

        private val INDICES = listOf(
            "index_disciples_name",
            "index_disciples_realm_realmLayer",
            "index_disciples_isAlive_realm",
            "index_disciples_isAlive_status",
            "index_disciples_discipleType"
        )
    }

    /** 真实 Room 校验：v63 库升级到 v64 触发 onValidateSchema。 */
    @Test
    fun `MIGRATION_63_64 passes real Room schema validation`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val dbName = "m_63_64_room_validate"
        context.deleteDatabase(dbName)
        try {
            RoomMigrationSupport.createDatabaseFromSchema(context, dbName, 63).close()
            val db = Room.databaseBuilder(context, GameDatabase::class.java, dbName)
                .addMigrations(*ALL_MIGRATIONS)
                .build()
            val migrated = db.openHelper.writableDatabase
            assertFalse(
                "真实 Room 升级后 equipment_stacks 表应删除",
                RoomMigrationSupport.tableExists(migrated, "equipment_stacks")
            )
            assertTrue(
                "真实 Room 升级后 disciples 应有 headId 列",
                RoomMigrationSupport.columnExists(migrated, "disciples", "headId")
            )
            assertFalse(
                "真实 Room 升级后 disciples 不应再有 armorId 列",
                RoomMigrationSupport.columnExists(migrated, "disciples", "armorId")
            )
            assertEquals(
                "真实 Room 升级后 disciples 列数应是 $V64_DISCIPLES_COLUMN_COUNT",
                V64_DISCIPLES_COLUMN_COUNT,
                RoomMigrationSupport.tableColumns(migrated, "disciples").size
            )
            db.close()
        } finally {
            context.deleteDatabase(dbName)
        }
    }

    // 影子表/装备表/列面/清槽/标志列/零丢失六段断言逐段平铺，拆分遮蔽完整性（V62To63 同先例）；
    // try/seed+assert 嵌套为断言上下文所需
    @Suppress("LongMethod", "NestedBlockDepth")
    @Test
    fun `MIGRATION_63_64 replaces equipment system atomically`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val dbName = "m_63_64_replace"
        context.deleteDatabase(dbName)
        try {
            val db = RoomMigrationSupport.createDatabaseFromSchema(context, dbName, 63)
            seedDiscipleRows(db)
            seedEquipmentRows(db)
            val v63Columns = RoomMigrationSupport.tableColumns(db, "disciples")
            val before = snapshotRows(db, "disciples")

            MIGRATION_63_64.migrate(db)

            // ① 影子表搬运（补偿数据源保行）
            assertEquals(
                "影子表 legacy_equipment_stacks 应保留全部 2 行",
                2, countRows(db, "legacy_equipment_stacks")
            )
            assertEquals(
                "影子表 legacy_equipment_instances 应保留全部 1 行",
                1, countRows(db, "legacy_equipment_instances")
            )

            // ② equipment_stacks 表删除
            assertFalse(
                "equipment_stacks 表应删除（R6）",
                RoomMigrationSupport.tableExists(db, "equipment_stacks")
            )

            // ③ equipment_instances 新结构空表
            val instCols = RoomMigrationSupport.tableColumns(db, "equipment_instances")
            assertEquals(
                "新 equipment_instances 应为空表（旧装备作废）",
                0, countRows(db, "equipment_instances")
            )
            assertEquals(
                "新 equipment_instances 列面应为 9 列新模型",
                setOf("id", "slot_id", "name", "setId", "part", "growth", "meta", "ownerId", "isEquipped"),
                instCols.toSet()
            )

            // ④⑤ disciples 列面：9 删 5 增 + 其余一列不差
            for (col in DROPPED) {
                assertFalse("迁移后 disciples.$col 应被删除", RoomMigrationSupport.columnExists(db, "disciples", col))
            }
            for (col in ADDED) {
                assertTrue("迁移后 disciples.$col 应存在", RoomMigrationSupport.columnExists(db, "disciples", col))
            }
            assertEquals(
                "v64 弟子表应是 $V64_DISCIPLES_COLUMN_COUNT 列",
                V64_DISCIPLES_COLUMN_COUNT,
                RoomMigrationSupport.tableColumns(db, "disciples").size
            )
            assertEquals(
                "v64 列名单必须 = v63 − 9 删 + 5 增",
                (v63Columns - DROPPED.toSet() + ADDED.toSet()).toSet(),
                RoomMigrationSupport.tableColumns(db, "disciples").toSet()
            )
            for (idx in INDICES) {
                assertTrue("索引 $idx 应保留", RoomMigrationSupport.indexExists(db, "disciples", idx))
            }
            assertTrue(
                "复合主键 (id, slot_id) 必须随重建保留",
                RoomMigrationSupport.primaryKeyExists(db, "disciples")
            )

            // ④ 六槽位清空（A1 幽灵件兜底：含复用 weaponId）
            for ((i, _) in before.withIndex()) {
                val row = queryRow(db, "d$i")
                for (col in listOf("weaponId") + ADDED) {
                    assertEquals("d$i 的槽位列 $col 应清空", "", row[col])
                }
            }

            // ⑥ game_data 幂等标记列 DEFAULT 0
            assertEquals(
                "legacy_equipment_compensated DEFAULT",
                "0",
                RoomMigrationSupport.columnDefault(db, "game_data", "legacy_equipment_compensated")
            )

            // ⑦ 保留列零丢失
            val retained = v63Columns - DROPPED.toSet()
            for ((i, _) in before.withIndex()) {
                val row = queryRow(db, "d$i")
                for (col in retained) {
                    if (col in (listOf("weaponId") + ADDED)) continue // 槽位列有意清空
                    assertEquals("d$i 的保留列 $col 零丢失", before[i][col], row[col])
                }
            }
            assertEquals("迁移前后行数零丢失", before.size, countRows(db, "disciples"))
            db.close()
        } finally {
            context.deleteDatabase(dbName)
        }
    }

    @Test
    fun `MIGRATION_63_64 is idempotent`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val dbName = "m_63_64_idempotent"
        context.deleteDatabase(dbName)
        try {
            val db = RoomMigrationSupport.createDatabaseFromSchema(context, dbName, 63)
            seedDiscipleRows(db)
            seedEquipmentRows(db)
            MIGRATION_63_64.migrate(db)
            val firstDisciples = snapshotRows(db, "disciples")

            MIGRATION_63_64.migrate(db)

            assertEquals(
                "二次迁移不得改写 disciples 数据",
                firstDisciples,
                snapshotRows(db, "disciples")
            )
            assertEquals(
                "二次迁移不得再动列数",
                V64_DISCIPLES_COLUMN_COUNT,
                RoomMigrationSupport.tableColumns(db, "disciples").size
            )
            db.close()
        } finally {
            context.deleteDatabase(dbName)
        }
    }

    // ==================== 种子 ====================

    /**
     * 两行种子（覆盖装备旧列非零值），列清单与 63.json disciples schema 对齐
     *（= V62To63Test 种子 − pillNurtureSpeedBonus）。
     */
    @Suppress("LongMethod") // 82 列种子逐列平铺：与 63.json schema 对齐的清单，拆分遮蔽完整性
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
                put("equipmentNurturingCompletionMonth", i)
                put("equipmentNurturingCompletionPhase", 1 + i)
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
                put("pillEffectDuration", 0)
                put("activePillCategory", "")
                put("weaponId", "legacy-weapon-$i")
                put("armorId", "legacy-armor-$i")
                put("bootsId", "legacy-boots-$i")
                put("accessoryId", "legacy-acc-$i")
                put("weaponNurture", "{}")
                put("armorNurture", "{}")
                put("bootsNurture", "{}")
                put("accessoryNurture", "{}")
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

    /** 装备种子：堆叠 2 行 + 实例 1 行（含孕养）。 */
    private fun seedEquipmentRows(db: SupportSQLiteDatabase) {
        for (i in 0..1) {
            val values = ContentValues().apply {
                put("id", "stack$i")
                put("slot_id", 1)
                put("name", "精铁剑")
                put("rarity", 1)
                put("description", "")
                put("slot", "WEAPON")
                put("physicalAttack", 15)
                put("magicAttack", 0)
                put("physicalDefense", 0)
                put("magicDefense", 0)
                put("speed", 0)
                put("hp", 0)
                put("mp", 0)
                put("critChance", 0.03)
                put("minRealm", 9)
                put("quantity", 3)
                put("isLocked", 0)
            }
            db.insert("equipment_stacks", android.database.sqlite.SQLiteDatabase.CONFLICT_FAIL, values)
        }
        val inst = ContentValues().apply {
            put("id", "inst0")
            put("slot_id", 1)
            put("name", "皮甲")
            put("rarity", 2)
            put("description", "")
            put("slot", "ARMOR")
            put("physicalAttack", 0)
            put("magicAttack", 0)
            put("physicalDefense", 11)
            put("magicDefense", 0)
            put("speed", 0)
            put("hp", 126)
            put("mp", 0)
            put("critChance", 0.0)
            put("nurtureLevel", 3)
            put("nurtureProgress", 12.5)
            put("minRealm", 7)
            put("ownerId", "d0")
            put("isEquipped", 1)
        }
        db.insert("equipment_instances", android.database.sqlite.SQLiteDatabase.CONFLICT_FAIL, inst)
    }

    // ==================== 断言辅助 ====================

    private fun countRows(db: SupportSQLiteDatabase, table: String): Int {
        db.query("SELECT COUNT(*) FROM `$table`").use { c ->
            c.moveToFirst()
            return c.getInt(0)
        }
    }

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

    @Suppress("NestedBlockDepth") // use + while：cursor 遍历样板（V62To63 同先例）
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
