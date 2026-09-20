package com.xianxia.sect.core.gameview

import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.ElderSlots
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.GameEventRecord
import com.xianxia.sect.core.model.SectPolicies
import com.xianxia.sect.core.nativebridge.FakeGameStateStore
import com.xianxia.sect.core.nativebridge.StateSyncService
import com.xianxia.sect.core.state.GameStateStore
import com.xianxia.sect.core.state.MutableGameState
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.serializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GameViewStoreGuardTest —— R2.3 第二波投影态契约守卫（验收门 5）。
 *
 * 四件事：
 * 1. **投影 ↔ 旧全量取数逐值等价**：已迁 UI 块从投影读到的值，与迁移前从
 *    GameStateStore 全量 gameData 快照算出的值必须逐字段相同（UI 行为零变更红线）；
 * 2. **未触及块不重投**：每旬变更集只带时间/灵石时，配置块与事件块的视图实例
 *    引用保持不变——"瘦身"的运行期证据（旧形态下每旬整份 gameData 换引用，
 *    所有派生块都要重算一遍）；
 * 3. **fail-fast 红线**：块声明的字段一旦掉出 gameData 镜像协议面（改名 /
 *    转 @Transient / C++ 不再导出），投影当场抛错，而不是静默取默认值——
 *    方案 §5「投影缺失字段 fail-fast 而非静默空」的守卫用例；
 * 4. **非镜像写入对账**：Kotlin 侧事务（回退臂 / 未下沉 UI 写）提交世代 ≠ 最近
 *    镜像世代 ⇒ 投影立即从 store 快照重投，不显示与 store 分叉的旧值。
 */
class GameViewStoreGuardTest {

    @Test
    fun `投影值与旧全量快照取数逐字段等价`() {
        val store = FakeGameStateStore().apply { gameDataValue = seededGameData() }
        val viewStore = GameViewStore().also { it.attach(store) }
        val service = StateSyncService(store, viewStore)

        service.applyDirty(dirtyJson(change("gameYear", 7), change("spiritStones", 4242L)))

        val header = viewStore.resourcesHeader.value
        val gd = store.gameDataValue
        assertEquals("头部块投影值 ≠ 迁移前从整份快照取数", GameViewStoreTestExpectations.headerOf(gd), header)
        assertEquals("资源头部块值分歧：spiritStones", 4242L, header.spiritStones)
        assertEquals("资源头部块值分歧：gameYear", 7, header.gameYear)

        service.applyDirty(dirtyJson(change("elderSlots", ElderSlots(viceSectMaster = "12"))))
        val config = viewStore.configEcho.value
        assertEquals("配置块投影值 ≠ 迁移前从整份快照取数", GameViewStoreTestExpectations.configOf(store.gameDataValue), config)
        assertSame("块内未随封变化的字段沿用 store 现值（宽松合并语义）", store.gameDataValue.sectPolicies, config.sectPolicies)
    }

    @Test
    fun `每旬只重投被触及的块（未触及块引用不变）`() {
        val store = FakeGameStateStore().apply { gameDataValue = seededGameData() }
        val viewStore = GameViewStore().also { it.attach(store) }
        val service = StateSyncService(store, viewStore)

        service.applyDirty(dirtyJson(change("gamePhase", 2), change("spiritStones", 10L)))
        val configBefore = viewStore.configEcho.value
        val eventBefore = viewStore.eventLog.value
        val genBefore = viewStore.projectionGeneration
        val headerBefore = viewStore.resourcesHeader.value

        service.applyDirty(dirtyJson(change("gamePhase", 0), change("spiritStones", 11L)))

        assertNotSame("头部块被本封触及：必须产出新投影实例", headerBefore, viewStore.resourcesHeader.value)
        assertSame("配置块未被本封触及：投影实例必须复用（旧全量形态每旬整份重建）", configBefore, viewStore.configEcho.value)
        assertSame("事件块未被本封触及：投影实例必须复用", eventBefore, viewStore.eventLog.value)
        assertEquals("两个头部块代际各 +1", genBefore + 1, viewStore.projectionGeneration)

        service.applyDirty(dirtyJson(change("sectPolicies", SectPolicies(spiritMineBoost = true))))
        assertNotSame("配置块被触达：必须重投", configBefore, viewStore.configEcho.value)
        assertEquals(
            "配置块投影值 = store 现值",
            GameViewStoreTestExpectations.configOf(store.gameDataValue),
            viewStore.configEcho.value
        )
    }

    @Test
    fun `块声明字段掉出镜像协议面即 fail-fast（禁止静默取默认值）`() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            GameViewStore.requireInMirrorSurface("守卫用例", setOf("spiritStones", "renamedAwayField"))
        }
        assertTrue(
            "fail-fast 必须点名漂移字段（可归因），实际消息：${error.message}",
            error.message!!.contains("renamedAwayField")
        )
        // 在册三块必须全部通过（协议漂移即红）
        GameViewStore.requireInMirrorSurface("资源头部", GameViewStore.RESOURCES_FIELDS)
        GameViewStore.requireInMirrorSurface("配置回声", GameViewStore.CONFIG_FIELDS)
        GameViewStore.requireInMirrorSurface("事件流", GameViewStore.EVENT_LOG_FIELDS)
    }

    @Test
    fun `非镜像事务提交后投影与 store 对账（回退臂不误显示旧投影）`() {
        val store = ObserverFiringStore().apply { gameDataValue = seededGameData() }
        val viewStore = GameViewStore().also { it.attach(store) }
        val service = StateSyncService(store, viewStore)

        service.applyDirty(dirtyJson(change("spiritStones", 900L)))
        assertEquals(900L, viewStore.resourcesHeader.value.spiritStones)

        // Kotlin 侧游戏写入（非镜像事务）：改灵石后必须立刻反映到投影
        store.kotlinWrite { gameData = gameData.copy().also { it.spiritStones = 1234L } }
        assertEquals("非镜像事务后投影未对账", 1234L, viewStore.resourcesHeader.value.spiritStones)
        assertEquals("对账命中计数", 1L, viewStore.reconciliationCount)

        // 镜像事务不得触发对账（否则每旬白做一次全块重投）
        val reconciliationsBefore = viewStore.reconciliationCount
        service.applyDirty(dirtyJson(change("spiritStones", 500L)))
        assertEquals("镜像事务不应触发对账", reconciliationsBefore, viewStore.reconciliationCount)
        assertEquals(500L, viewStore.resourcesHeader.value.spiritStones)
    }

    @Test
    fun `换档重投点与空态复位`() {
        val store = FakeGameStateStore().apply { gameDataValue = seededGameData() }
        val viewStore = GameViewStore().also { it.attach(store) }
        viewStore.reprojectAll(store.gameDataValue)
        assertEquals(
            "全量重投 = 三块全部对齐 store",
            GameViewStoreTestExpectations.headerOf(store.gameDataValue),
            viewStore.resourcesHeader.value
        )
        viewStore.reset()
        assertEquals("换档复位 = 头部块回空态（与 GameStateStore 未装载同形）", 0L, viewStore.resourcesHeader.value.spiritStones)
        assertEquals("换档复位 = 事件块清空", emptyList<GameEventRecord>(), viewStore.eventLog.value.records)
        assertEquals("proto eventFeed 块 R2.4 起正式产出", "produced-r2.4", GameViewStore.PROTO_EVENT_FEED_BLOCK)
    }

    @Test
    fun `镜像馈送恒推进投影（B18 后无灰度开关）`() {
        val store = FakeGameStateStore().apply { gameDataValue = seededGameData() }
        val viewStore = GameViewStore().also { it.attach(store) }
        val service = StateSyncService(store, viewStore)

        service.applyDirty(dirtyJson(change("spiritStones", 777L)))

        assertEquals("镜像写回 store 生效", 777L, store.gameDataValue.spiritStones)
        assertEquals("镜像封必须恒推进投影（旗标已随 B18 删除）", 777L, viewStore.resourcesHeader.value.spiritStones)
        assertEquals("投影代际必须随镜像封推进", 1L, viewStore.projectionGeneration)
    }

    // ── 夹具 ────────────────────────────────────────────────────

    private val json = kotlinx.serialization.json.Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    private inline fun <reified T> change(name: String, value: T): Pair<String, JsonElement> =
        name to json.encodeToJsonElement(json.serializersModule.serializer<T>(), value)

    private fun dirtyJson(vararg changes: Pair<String, JsonElement>): String =
        buildString {
            append("""{"version":1,"changed":{""")
            append(
                changes.joinToString(",") { (name, value) ->
                    "\"gameData.$name\":${value.toString()}"
                }
            )
            append("},\"removed\":{}}")
        }

    private fun seededGameData(): GameData = GameData().apply {
        spiritStones = 5000L
        midGradeSpiritStones = 3L
        highGradeSpiritStones = 1L
        gameYear = 2
        gameMonth = 3
        gamePhase = 0
        elderSlots = ElderSlots(viceSectMaster = "11")
        sectPolicies = SectPolicies(spiritMineBoost = true)
        gameEventRecords = listOf(GameEventRecord(timestamp = 1_700_000_000_000L, eventType = "X", summary = "y"))
        recruitList = listOf(Disciple(id = "501", name = "甲"))
    }

    /**
     * 会在每次非镜像 [update] 后广播事务世代的 store 替身——生产
     * GameStateStoreImpl 的 fireCommitted 语义面（FakeGameStateStore 为纯桩）。
     */
    private class ObserverFiringStore : FakeGameStateStore() {
        private val observers = mutableListOf<GameStateStore.TransactionObserver>()
        private var generation = 0L

        override fun registerTransactionObserver(observer: GameStateStore.TransactionObserver) {
            if (observer !in observers) observers.add(observer)
        }

        override val currentTransactionGeneration: Long get() = generation

        override fun update(block: MutableGameState.() -> Unit) {
            generation++
            super.update(block)
            observers.forEach { it.onTransactionCommitted(generation) }
        }

        fun kotlinWrite(block: MutableGameState.() -> Unit) = update(block)
    }
}

/** 期望值以"迁移前 UI 的取数方式"（整份 gameData 快照逐字段读）表达 */
internal object GameViewStoreTestExpectations {
    fun headerOf(gd: GameData) = ResourcesHeaderView(
        spiritStones = gd.spiritStones,
        midGradeSpiritStones = gd.midGradeSpiritStones,
        highGradeSpiritStones = gd.highGradeSpiritStones,
        gameYear = gd.gameYear,
        gameMonth = gd.gameMonth,
        gamePhase = gd.gamePhase
    )

    fun configOf(gd: GameData) = ConfigEchoView(
        sectPolicies = gd.sectPolicies,
        yearlySalary = gd.yearlySalary,
        yearlySalaryEnabled = gd.yearlySalaryEnabled,
        elderSlots = gd.elderSlots,
        placedBuildings = gd.placedBuildings,
        autoRecruitSpiritRootFilter = gd.autoRecruitSpiritRootFilter
    )
}
