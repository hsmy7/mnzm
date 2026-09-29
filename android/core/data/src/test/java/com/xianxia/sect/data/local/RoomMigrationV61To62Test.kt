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
 * 迁移 61→62 测试（弟子属性单列化，装备重构 B1，方案 §15/§5.1）。
 *
 * 与 [RoomMigrationV60To61Test] 同构：v61 schema 建库插种子 → 运行迁移 → 验证
 * 列面（删 12 增 7）+ 回填取和（Q7，k=1）+ 固有属性灵根派生 + 其余数据零丢失 +
 * 真实 Room schema 校验 + 幂等。
 *
 * 本类的种子面与断言面：
 * 1. **回填取和逐行核对**——物攻/法攻（基值与方差）、物防/法防、丹药四加成按
 *    非零异值种子，迁移后断言 `新列 = 旧两列之和`（方差为 Int 除法均值）；
 * 2. **固有属性按首灵根派生**——五系灵根各一行 + 空灵根兜底行，断言
 *    金/土→PHYSICAL、水/木/火→MAGIC、空→PHYSICAL（与 `InnateDamageType`
 *    派生口径逐条一致）；
 * 3. **保留列逐值全等**（扣除删增列后整行快照比对，不使用 `SELECT *`）；
 * 4. **5 个存续索引全部回来** + 复合主键保留；
 * 5. **真实 Room 校验**：升级到 v62 触发 onValidateSchema（列/DEFAULT/索引
 *    与实体不一致即崩溃）；
 * 6. **幂等**：已单列化的表重复执行迁移不改写数据。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class RoomMigrationV61To62Test {

    companion object {
        /** v62 删除的 12 列（物法攻防基值/方差/丹药加成）。 */
        private val DROPPED = listOf(
            "basePhysicalAttack", "baseMagicAttack", "basePhysicalDefense", "baseMagicDefense",
            "physicalAttackVariance", "magicAttackVariance", "physicalDefenseVariance",
            "magicDefenseVariance",
            "pillPhysicalAttackBonus", "pillMagicAttackBonus", "pillPhysicalDefenseBonus",
            "pillMagicDefenseBonus"
        )

        /** v62 新增的 7 列（带 DEFAULT）。 */
        private val ADDED = listOf(
            "baseAttack", "baseDefense", "attackVariance", "defenseVariance",
            "innateDamageType", "pillAttackBonus", "pillDefenseBonus"
        )

        /** v62 的 disciples 列数（= 89 − 12 + 7）。 */
        private const val V62_DISCIPLES_COLUMN_COUNT = 84

        /** 全链升到当前 DATABASE_VERSION（v63，R11 删 pillNurtureSpeedBonus）后的终版列数。 */
        private const val FINAL_DISCIPLES_COLUMN_COUNT = 83

        /** 灵根 → 期望固有属性（首灵根派生口径，与迁移 SQL CASE 逐条一致）。 */
        private val ROOT_EXPECTATION = listOf(
            "metal" to "PHYSICAL",
            "earth" to "PHYSICAL",
            "water" to "MAGIC",
            "wood" to "MAGIC",
            "fire" to "MAGIC",
            "" to "PHYSICAL"
        )

        private val INDICES = listOf(
            "index_disciples_name",
            "index_disciples_realm_realmLayer",
            "index_disciples_isAlive_realm",
            "index_disciples_isAlive_status",
            "index_disciples_discipleType"
        )
    }

    /** 真实 Room 校验：v61 库升级到 v62 触发 onValidateSchema。 */
    @Test
    fun `MIGRATION_61_62 passes real Room schema validation`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val dbName = "m_61_62_room_validate"
        context.deleteDatabase(dbName)
        try {
            RoomMigrationSupport.createDatabaseFromSchema(context, dbName, 61).close()
            val db = Room.databaseBuilder(context, GameDatabase::class.java, dbName)
                .addMigrations(*ALL_MIGRATIONS)
                .build()
            val migrated = db.openHelper.writableDatabase
            assertTrue(
                "真实 Room 升级后 disciples 应有单列 baseAttack",
                RoomMigrationSupport.columnExists(migrated, "disciples", "baseAttack")
            )
            assertEquals(
                "真实 Room 升级到终版（v63）后 disciples 列数应是 $FINAL_DISCIPLES_COLUMN_COUNT",
                FINAL_DISCIPLES_COLUMN_COUNT,
                RoomMigrationSupport.tableColumns(migrated, "disciples").size
            )
            db.close()
        } finally {
            context.deleteDatabase(dbName)
        }
    }

    @Test
    fun `MIGRATION_61_62 backfills sums and derives innate damage type`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val dbName = "m_61_62_backfill"
        context.deleteDatabase(dbName)
        try {
            val db = RoomMigrationSupport.createDatabaseFromSchema(context, dbName, 61)
            seedDiscipleRows(db)
            val v61Columns = RoomMigrationSupport.tableColumns(db, "disciples")
            val before = snapshotRows(db, "disciples")

            MIGRATION_61_62.migrate(db)

            assertShape(db, v61Columns)
            assertBackfillSums(before, db)
            assertInnateDamageTypeDerived(db)
            assertRetainedDataIntact(before, db, v61Columns)
            db.close()
        } finally {
            context.deleteDatabase(dbName)
        }
    }

    /**
     * S20 · 迁移前后总战力比 ∈ [0.98, 1.02]（装备重构 B1，方案 §15.7 Q7）。
     *
     * 旧公式（双列）= (物攻+法攻)×5 + HP×4 + (物防+法防)×3 + 速度×2；
     * 新公式（单列）= attack×5 + HP×4 + defense×3 + 速度×2。
     * 迁移回填为取和（k=1）⇒ 线性恒等，比值恒为 1.0（方差均值归一同样保持
     * 单列攻击=物法两半各自 round 后相加的期望值，误差 ≤ round 噪声，容差内）。
     */
    @Test
    fun `S20 combat power ratio stays within tolerance across migration`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val dbName = "m_61_62_combat_power"
        context.deleteDatabase(dbName)
        try {
            val db = RoomMigrationSupport.createDatabaseFromSchema(context, dbName, 61)
            seedDiscipleRows(db)
            val before = snapshotRows(db, "disciples")
            MIGRATION_61_62.migrate(db)

            fun oldPower(row: Map<String, String?>): Long {
                val pa = row["basePhysicalAttack"]!!.toLong()
                val ma = row["baseMagicAttack"]!!.toLong()
                val pd = row["basePhysicalDefense"]!!.toLong()
                val md = row["baseMagicDefense"]!!.toLong()
                val hp = row["baseHp"]!!.toLong()
                val spd = row["baseSpeed"]!!.toLong()
                return (pa + ma) * 5 + hp * 4 + (pd + md) * 3 + spd * 2
            }
            fun newPower(row: Map<String, String?>): Long {
                val atk = row["baseAttack"]!!.toLong()
                val dfn = row["baseDefense"]!!.toLong()
                val hp = row["baseHp"]!!.toLong()
                val spd = row["baseSpeed"]!!.toLong()
                return atk * 5 + hp * 4 + dfn * 3 + spd * 2
            }
            for ((i, row) in before.withIndex()) {
                val after = queryRow(db, "d$i")
                val oldP = oldPower(row)
                val newP = newPower(after)
                val ratio = if (oldP == 0L) 1.0 else newP.toDouble() / oldP.toDouble()
                assertTrue(
                    "d$i 战力比超出容差：old=$oldP new=$newP ratio=$ratio（要求 ∈ [0.98, 1.02]）",
                    ratio in 0.98..1.02
                )
            }
            db.close()
        } finally {
            context.deleteDatabase(dbName)
        }
    }

    @Test
    fun `MIGRATION_61_62 is idempotent after single-column rebuild`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val dbName = "m_61_62_idempotent"
        context.deleteDatabase(dbName)
        try {
            val db = RoomMigrationSupport.createDatabaseFromSchema(context, dbName, 61)
            seedDiscipleRows(db)
            MIGRATION_61_62.migrate(db)
            val first = snapshotRows(db, "disciples")

            MIGRATION_61_62.migrate(db)

            assertEquals("二次迁移不得改写 disciples 数据", first, snapshotRows(db, "disciples"))
            assertEquals(
                "二次迁移不得再动列数",
                V62_DISCIPLES_COLUMN_COUNT,
                RoomMigrationSupport.tableColumns(db, "disciples").size
            )
            db.close()
        } finally {
            context.deleteDatabase(dbName)
        }
    }

    // ==================== 种子 ====================

    /**
     * 六行种子：五行按 [ROOT_EXPECTATION] 的灵根（metal/earth/water/wood/fire）+
     * 一行空灵根兜底。攻防基值/方差/丹药加成给非零异值（回填取和可逐行核对）。
     */
    private fun seedDiscipleRows(db: SupportSQLiteDatabase) {
        seedAllRows(db, attacks = listOf(11, 12, 13, 14, 15, 16))
    }

    @Suppress("LongMethod") // 89 列种子逐列平铺：与 61.json schema 对齐的清单，拆分遮蔽完整性
    private fun seedAllRows(db: SupportSQLiteDatabase, attacks: List<Int>) {
        val defenses = listOf(21, 22, 23, 24, 25, 26)
        val atkVariances = listOf(30, -30, 10, -10, 5, 0)
        val defVariances = listOf(-20, 20, 8, -8, 3, 0)
        val pillAtkA = listOf(7, 9, 11, 13, 15, 17)
        val pillAtA = listOf(3, 5, 7, 9, 11, 13)
        ROOT_EXPECTATION.forEachIndexed { i, (root, _) ->
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
                put("spiritRootType", root)
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
                put("basePhysicalAttack", attacks[i])
                put("baseMagicAttack", attacks[i] * 2)
                put("basePhysicalDefense", defenses[i])
                put("baseMagicDefense", defenses[i] * 2)
                put("baseSpeed", 15 + i)
                put("hpVariance", 5)
                put("mpVariance", -5)
                put("physicalAttackVariance", atkVariances[i])
                put("magicAttackVariance", -atkVariances[i])
                put("physicalDefenseVariance", defVariances[i])
                put("magicDefenseVariance", -defVariances[i])
                put("speedVariance", 0)
                put("totalCultivation", 0L)
                put("breakthroughCount", 0)
                put("breakthroughFailCount", 0)
                put("currentHp", -1)
                put("currentMp", -1)
                put("pillPhysicalAttackBonus", pillAtkA[i])
                put("pillMagicAttackBonus", pillAtA[i])
                put("pillPhysicalDefenseBonus", pillAtA[i])
                put("pillMagicDefenseBonus", pillAtkA[i])
                put("pillHpBonus", 1 + i)
                put("pillMpBonus", 2 + i)
                put("pillSpeedBonus", 0)
                put("pillCritRateBonus", 0.0)
                put("pillCritEffectBonus", 0.0)
                put("pillCultivationSpeedBonus", 0.0)
                put("pillSkillExpSpeedBonus", 0.0)
                put("pillNurtureSpeedBonus", 0.0)
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

    // ==================== 断言 ====================

    private fun assertShape(db: SupportSQLiteDatabase, v61Columns: List<String>) {
        for (col in DROPPED) {
            assertFalse("迁移后 disciples.$col 应被删除", RoomMigrationSupport.columnExists(db, "disciples", col))
        }
        val v62Columns = RoomMigrationSupport.tableColumns(db, "disciples")
        assertEquals("v62 弟子表应是 $V62_DISCIPLES_COLUMN_COUNT 列", V62_DISCIPLES_COLUMN_COUNT, v62Columns.size)
        assertEquals(
            "v62 列名单必须 = v61 − 删 12 + 增 7（其余列一列不差，含顺序）",
            v61Columns.filterNot { it in DROPPED }.toSet() + ADDED.toSet(),
            v62Columns.toSet()
        )
        for (idx in INDICES) {
            assertTrue("索引 $idx 应重建", RoomMigrationSupport.indexExists(db, "disciples", idx))
        }
        assertTrue(
            "复合主键 (id, slot_id) 必须随重建保留",
            RoomMigrationSupport.primaryKeyExists(db, "disciples")
        )
        // 新列 DEFAULT 逐列核对（与 62.json 对齐）
        assertEquals("baseAttack DEFAULT", "0", RoomMigrationSupport.columnDefault(db, "disciples", "baseAttack"))
        assertEquals(
            "innateDamageType DEFAULT", "''",
            RoomMigrationSupport.columnDefault(db, "disciples", "innateDamageType")
        )
    }

    /** 回填取和逐行核对（Q7：k=1；方差 Int 除法均值——种子两方差互为相反数 ⇒ 均值精确）。 */
    private fun assertBackfillSums(before: List<Map<String, String?>>, db: SupportSQLiteDatabase) {
        for ((i, row) in before.withIndex()) {
            val pa = row["basePhysicalAttack"]!!.toInt()
            val ma = row["baseMagicAttack"]!!.toInt()
            val pd = row["basePhysicalDefense"]!!.toInt()
            val md = row["baseMagicDefense"]!!.toInt()
            val pav = row["physicalAttackVariance"]!!.toInt()
            val mav = row["magicAttackVariance"]!!.toInt()
            val pdv = row["physicalDefenseVariance"]!!.toInt()
            val mdv = row["magicDefenseVariance"]!!.toInt()
            val ppa = row["pillPhysicalAttackBonus"]!!.toInt()
            val pma = row["pillMagicAttackBonus"]!!.toInt()
            val ppd = row["pillPhysicalDefenseBonus"]!!.toInt()
            val pmd = row["pillMagicDefenseBonus"]!!.toInt()
            val after = queryRow(db, "d$i")
            assertEquals("d$i baseAttack", pa + ma, after["baseAttack"]!!.toInt())
            assertEquals("d$i baseDefense", pd + md, after["baseDefense"]!!.toInt())
            assertEquals("d$i attackVariance", (pav + mav) / 2, after["attackVariance"]!!.toInt())
            assertEquals("d$i defenseVariance", (pdv + mdv) / 2, after["defenseVariance"]!!.toInt())
            assertEquals("d$i pillAttackBonus", ppa + pma, after["pillAttackBonus"]!!.toInt())
            assertEquals("d$i pillDefenseBonus", ppd + pmd, after["pillDefenseBonus"]!!.toInt())
        }
    }

    /** 固有属性按首灵根派生（六行逐一核对）。 */
    private fun assertInnateDamageTypeDerived(db: SupportSQLiteDatabase) {
        ROOT_EXPECTATION.forEachIndexed { i, (root, expected) ->
            assertEquals(
                "d$i（灵根「$root」）的 innateDamageType",
                expected,
                queryRow(db, "d$i")["innateDamageType"]
            )
        }
    }

    /** 保留列零丢失：扣除删增列后逐行逐格全等。 */
    private fun assertRetainedDataIntact(
        before: List<Map<String, String?>>,
        db: SupportSQLiteDatabase,
        v61Columns: List<String>
    ) {
        val retained = v61Columns.filterNot { it in DROPPED }
        for ((i, row) in before.withIndex()) {
            val after = queryRow(db, "d$i")
            for (col in retained) {
                assertEquals("d$i 的保留列 $col 零丢失", row[col], after[col])
            }
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

    @Suppress("NestedBlockDepth") // use + while + 双层 for：cursor 遍历样板（V60To61 同先例）
    private fun snapshotRows(db: SupportSQLiteDatabase, table: String): List<Map<String, String?>> {
        val rows = mutableListOf<Map<String, String?>>()
        db.query("SELECT * FROM $table").use { c ->
            while (c.moveToNext()) {
                rows.add(rowOf(c))
            }
        }
        return rows
    }

    private fun rowOf(c: android.database.Cursor): Map<String, String?> {
        val row = mutableMapOf<String, String?>()
        for (name in c.columnNames) {
            val idx = c.getColumnIndex(name)
            row[name] = if (c.isNull(idx)) null else c.getString(idx)
        }
        return row
    }
}
