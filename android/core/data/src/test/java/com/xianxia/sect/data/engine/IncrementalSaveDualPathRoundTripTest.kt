package com.xianxia.sect.data.engine

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.xianxia.sect.core.model.BattleLog
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.ExploredSectInfo
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.GameHeavyData
import com.xianxia.sect.core.model.MailEntity
import com.xianxia.sect.core.model.Material
import com.xianxia.sect.core.model.Pill
import com.xianxia.sect.core.model.Seed
import com.xianxia.sect.core.model.production.ProductionSlot
import com.xianxia.sect.core.state.SaveDirtySet
import com.xianxia.sect.core.state.SaveDirtyTables
import com.xianxia.sect.data.local.GameDatabase
import com.xianxia.sect.data.model.SaveData
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 增量 ↔ 全量双路径对拍铁门（SS5 验收④）。
 *
 * 同一内存状态分别走增量路径与全量路径落盘 → 读回**逐字段全等**，
 * 覆盖「新增一批 / 删除一批 / 修改一批」三种脏集形状；同时断言：
 * - 首保（无基线）判全量、次保（有基线+有效脏集）判增量（验收⑥风险项路径证据）；
 * - 未变 heavy key 跳过（不删不写）；
 * - 越界脏集回退全量并计数（验收⑤）；
 * - stacksSerialized = false 的生产者回归被 fail-fast 拒绝（验收⑥降级断言）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class IncrementalSaveDualPathRoundTripTest {

    private lateinit var db: GameDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, GameDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    // ── 读回面：全部持久化字段的结构化快照（逐字段全等的比较载体） ──

    private data class DbSnapshot(
        val gameData: GameData?,
        val disciples: List<Disciple>,
        val pills: List<Pill>,
        val materials: List<Material>,
        val seeds: List<Seed>,
        val battleLogs: List<BattleLog>,
        /** heavy 行比较面 = dataKey + dataValue（updatedAt 是写侧时间戳元数据，不参与读档） */
        val heavyRows: List<Pair<String, List<Byte>>>,
        val mails: List<MailEntity>,
        val productionSlots: List<ProductionSlot>,
        val recipes: List<String>,
        val diplomacySectDetails: Map<String, com.xianxia.sect.core.model.SectDetail>,
        val worldMapSects: Int,
        val patrolConfig: com.xianxia.sect.core.model.PatrolConfig?
    )

    private suspend fun readAll(database: GameDatabase): DbSnapshot {
        val gd = database.gameDataDao().getGameDataSync()
        return DbSnapshot(
            gameData = gd,
            disciples = database.discipleDao().getAllSync().sortedBy { it.id },
            pills = database.pillDao().getAllSync().sortedBy { it.id },
            materials = database.materialDao().getAllSync().sortedBy { it.id },
            seeds = database.seedDao().getAllSync().sortedBy { it.id },
            battleLogs = database.battleLogDao().getAllSync().sortedBy { it.id },
            heavyRows = database.gameHeavyDataDao().getAll()
                .map { it.dataKey to it.dataValue.toList() }
                .sortedBy { it.first },
            mails = database.mailDao().getAllSync().sortedBy { it.id },
            productionSlots = database.productionSlotDao().getAllSync().sortedBy { it.id },
            recipes = database.recipeDao().getAll().first().map { it.id }.sorted(),
            diplomacySectDetails = database.diplomacyStateDao().get()?.sectDetails ?: emptyMap(),
            worldMapSects = database.worldMapStateDao().get()?.worldMapSects?.size ?: -1,
            patrolConfig = database.patrolStateDao().get()?.patrolConfig
        )
    }

    /** 基线夹具：三弟子 + 丹/材/种 + 战报 + 邮件 + 生产槽 + 全部 heavy 字段非空。 */
    private fun baselineSaveData(): SaveData {
        val gameData = GameData(
            id = "game_data",
            sectName = "青云宗",
            exploredSects = mapOf(
                "sect-a" to ExploredSectInfo(sectName = "血煞门", battleCount = 3)
            ),
            unlockedRecipes = listOf("recipe-1", "recipe-2")
        )
        return SaveData(
            gameData = gameData,
            disciples = listOf(
                disciple("1", "林寒", 1200.5),
                disciple("2", "苏挽月", 3400.0),
                disciple("3", "陆沉", 800.0)
            ),
            pills = listOf(Pill(id = "pill-1", name = "凝气丹"), Pill(id = "pill-2", name = "筑基丹")),
            materials = listOf(Material(id = "mat-1", name = "铁精"), Material(id = "mat-2", name = "灵砂")),
            herbs = listOf(),
            seeds = listOf(Seed(id = "seed-1", name = "灵谷种")),
            battleLogs = listOf(
                battleLog("log-1", 1), battleLog("log-2", 2), battleLog("log-3", 3)
            ),
            storageBags = listOf(),
            productionSlots = listOf(ProductionSlot(id = "slot-1")),
            mails = listOf(MailEntity(id = "mail-1", title = "欢迎来信")),
            stacksSerialized = true
        )
    }

    private fun disciple(id: String, name: String, cultivation: Double) = Disciple(
        id = id,
        name = name,
        surname = name.take(1),
        realm = 3,
        cultivation = cultivation
    )

    private fun battleLog(id: String, ts: Long) = BattleLog(
        id = id,
        timestamp = ts,
        year = 2,
        month = ts.toInt(),
        attackerName = "青云宗",
        defenderName = "血煞门",
        details = "战报$id"
    )

    /**
     * 对拍骨架：基线全量落库（首保）→ 变异状态按增量路径落库（次保）→
     * 同一变异状态在独立库走全量路径 → 两库读回逐字段全等。
     */
    private suspend fun assertDualPathEquivalence(
        mutated: SaveData,
        dirty: SaveDirtySet,
        baseline: SaveData = baselineSaveData()
    ): DbSnapshot {
        // 增量臂：首保全量基线建立 → 增量写
        db.writeAllDataToDatabase(baseline)
        val incrResult = db.writeIncrementalDataToDatabase(mutated, dirty)
        assertTrue("增量路径必须成功", incrResult.isSuccess)

        // 全量对照臂：独立库全量写
        val context = ApplicationProvider.getApplicationContext<Context>()
        val referenceDb = Room.inMemoryDatabaseBuilder(context, GameDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val fullResult = referenceDb.writeAllDataToDatabase(mutated)
            assertTrue("全量对照路径必须成功", fullResult.isSuccess)

            val incrementalSnapshot = readAll(db)
            val fullSnapshot = readAll(referenceDb)
            assertEquals(
                "增量路径读回必须与全量路径逐字段全等",
                fullSnapshot,
                incrementalSnapshot
            )
            return incrementalSnapshot
        } finally {
            referenceDb.close()
        }
    }

    // ── 形状一：新增一批 ──

    @Test
    fun `新增一批丹药与弟子_双路径读回全等`() = runTest {
        val baseline = baselineSaveData()
        val mutated = baseline.copy(
            disciples = baseline.disciples + disciple("4", "白芷", 2500.0),
            pills = baseline.pills + Pill(id = "pill-3", name = "回春丹")
        )
        val dirty = SaveDirtySet(
            upsertIds = mapOf(
                SaveDirtyTables.DISCIPLES to setOf("4"),
                SaveDirtyTables.PILLS to setOf("pill-3")
            )
        )
        val snapshot = assertDualPathEquivalence(mutated, dirty)
        assertEquals(4, snapshot.disciples.size)
        assertEquals(3, snapshot.pills.size)
    }

    // ── 形状二：删除一批 ──

    @Test
    fun `删除一批丹药材料与战报_双路径读回全等`() = runTest {
        val baseline = baselineSaveData()
        val mutated = baseline.copy(
            pills = baseline.pills.filterNot { it.id == "pill-1" },
            materials = baseline.materials.filterNot { it.id == "mat-2" },
            battleLogs = baseline.battleLogs.filterNot { it.id == "log-2" }
        )
        // 删除形状：无 upsert id——删除集由「已落盘 ↔ 快照」id 对账承载
        val dirty = SaveDirtySet.EMPTY
        val snapshot = assertDualPathEquivalence(mutated, dirty)
        assertEquals(listOf("pill-2"), snapshot.pills.map { it.id })
        assertEquals(listOf("mat-1"), snapshot.materials.map { it.id })
        assertEquals(setOf("log-1", "log-3"), snapshot.battleLogs.map { it.id }.toSet())
    }

    // ── 形状三：修改一批 ──

    @Test
    fun `修改一批弟子内容与探索 sect_双路径读回全等`() = runTest {
        val baseline = baselineSaveData()
        val mutatedGame = baseline.gameData.copy(
            exploredSects = baseline.gameData.exploredSects +
                ("sect-b" to ExploredSectInfo(sectName = "落霞宗", battleCount = 1)),
            unlockedRecipes = baseline.gameData.unlockedRecipes.orEmpty() + "recipe-3"
        )
        val mutated = baseline.copy(
            gameData = mutatedGame,
            disciples = baseline.disciples.map {
                if (it.id == "2") it.copy(cultivation = 9600.0) else it
            }
        )
        val dirty = SaveDirtySet(
            upsertIds = mapOf(SaveDirtyTables.DISCIPLES to setOf("2")),
            rewriteTables = setOf(SaveDirtyTables.RECIPES),
            heavyKeys = setOf(GameHeavyData.KEY_EXPLORED_SECTS)
        )
        val snapshot = assertDualPathEquivalence(mutated, dirty)
        assertEquals(
            2,
            snapshot.heavyRows.count { it.first.startsWith(GameHeavyData.KEY_EXPLORED_SECTS) }
        )
        assertEquals(setOf("recipe-1", "recipe-2", "recipe-3"), snapshot.recipes.toSet())
    }

    // ── 未变 heavy key 跳过（验收②） ──

    @Test
    fun `未变heavykey不删不写_变动key整key重编码`() = runTest {
        val baseline = baselineSaveData()
        db.writeAllDataToDatabase(baseline)
        val sectDetailRowsBefore = db.gameHeavyDataDao().getByPrefix(GameHeavyData.KEY_SECT_DETAILS)

        val mutated = baseline.copy(
            gameData = baseline.gameData.copy(
                exploredSects = baseline.gameData.exploredSects +
                    ("sect-c" to ExploredSectInfo(sectName = "北冥宗", battleCount = 2))
            )
        )
        val result = db.writeIncrementalDataToDatabase(
            mutated,
            SaveDirtySet(heavyKeys = setOf(GameHeavyData.KEY_EXPLORED_SECTS))
        )
        assertTrue(result.isSuccess)

        // 未变 key：行内容与行数完全不变（不删不写）
        val sectDetailRowsAfter = db.gameHeavyDataDao().getByPrefix(GameHeavyData.KEY_SECT_DETAILS)
        assertEquals(sectDetailRowsBefore, sectDetailRowsAfter)
        // 变动 key：整 key 重编码后包含新内容
        val exploredRows = db.gameHeavyDataDao().getByPrefix(GameHeavyData.KEY_EXPLORED_SECTS)
        assertTrue(exploredRows.isNotEmpty())
        assertTrue(
            exploredRows.any { it.dataValue.decodeToString().contains("北冥宗") }
        )
    }

    // ── 首保/次保路径判定证据（验收⑥风险项） ──

    @Test
    fun `首保无基线判全量_次保有基线判增量`() {
        val tracker = DirtySetTracker()
        val metrics = StorageMetrics()

        // 首保：进程启动无基线（tracke 初始 requiresFullWrite = true）
        val firstDecision = resolveSaveDecisionWithCount(
            dirtySet = SaveDirtySet.EMPTY,
            hasBaseline = !tracker.isFullWriteRequired(),
            snapshotIds = emptyMap(),
            metrics = metrics
        )
        assertFalse("首保（脏集空、无基线）必须走全量基线建立", firstDecision.incremental)
        assertEquals(FullSaveReason.NO_BASELINE, firstDecision.fullSaveReason)

        // 首保成功 ⇒ 基线建立
        tracker.settleSaveResult(SaveDirtySet.EMPTY, success = true, writtenViaFull = true)

        // 次保：有基线 + 有效脏集 ⇒ 增量
        val secondDecision = resolveSaveDecisionWithCount(
            dirtySet = SaveDirtySet(upsertIds = mapOf(SaveDirtyTables.PILLS to setOf("p1"))),
            hasBaseline = !tracker.isFullWriteRequired(),
            snapshotIds = mapOf(SaveDirtyTables.PILLS to setOf("p1")),
            metrics = metrics
        )
        assertTrue("次保（有基线+有效脏集）必须走增量路径", secondDecision.incremental)

        val snapshot = metrics.snapshot()
        assertEquals(1L, snapshot.fullSaveCount)
        assertEquals(1L, snapshot.incrementalSaveCount)
        assertEquals(0L, snapshot.dirtyFallbackCount)
    }

    // ── 越界回退计数（验收⑤） ──

    @Test
    fun `越界脏集回退全量并计数`() {
        val metrics = StorageMetrics()
        val decision = resolveSaveDecisionWithCount(
            dirtySet = SaveDirtySet(upsertIds = mapOf(SaveDirtyTables.MATERIALS to setOf("stale-id"))),
            hasBaseline = true,
            snapshotIds = mapOf(SaveDirtyTables.MATERIALS to setOf("mat-1", "mat-2")),
            metrics = metrics
        )
        assertFalse("越界脏集必须回退全量", decision.incremental)
        assertEquals(FullSaveReason.DIRTY_OUT_OF_SNAPSHOT, decision.fullSaveReason)

        val snapshot = metrics.snapshot()
        assertEquals(1L, snapshot.fullSaveCount)
        assertEquals(1L, snapshot.dirtyFallbackCount)
        assertEquals(0L, snapshot.incrementalSaveCount)
        assertEquals(FullSaveReason.DIRTY_OUT_OF_SNAPSHOT.name, snapshot.lastFullSaveReason)
    }

    // ── stacksSerialized 断言（验收⑥降级） ──

    @Test
    fun `stacksSerialized为假的生产者回归被fail_fast拒绝`() = runTest {
        val baseline = baselineSaveData().copy(stacksSerialized = false)
        val error = runCatching { db.writeAllDataToDatabase(baseline) }.exceptionOrNull()
        assertTrue(
            "stacksSerialized=false 必须被断言拒绝（真回归；生产链路由 save() 包装为失败）",
            error is IllegalStateException
        )
    }
}
