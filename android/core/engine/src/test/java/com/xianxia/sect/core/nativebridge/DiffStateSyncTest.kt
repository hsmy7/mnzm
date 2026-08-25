package com.xianxia.sect.core.nativebridge

import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.EquipmentSlot
import com.xianxia.sect.core.model.EquipmentStack
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.Herb
import com.xianxia.sect.core.model.Material
import com.xianxia.sect.core.model.Pill
import com.xianxia.sect.core.model.Seed
import com.xianxia.sect.core.state.BootPhase
import com.xianxia.sect.core.state.GameStateStore
import com.xianxia.sect.core.state.MutableGameState
import com.xianxia.sect.core.state.RunState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DiffStateSyncTest — StateSyncService 镜像同步测试（批次 9 核心基础设施）。
 *
 * 守护目标：
 *   1. buildNativeState：从 GameStateStore 构建快照（全字段收集）
 *   2. applySnapshot：快照镜像写入（单事务原子 + 宽松合并——未覆盖字段保留）
 *   3. buildNativeState → applySnapshot 往返保真
 *
 * 用 Fake GameStateStore（手写 Fake 而非 Mock——CLAUDE.md 9.4 优先 Fake）隔离
 * 真实 store，聚焦 StateSyncService 自身逻辑。
 */
@org.junit.experimental.categories.Category(com.xianxia.sect.core.RobolectricTests::class)
@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
class DiffStateSyncTest {

    /** Fake GameStateStore：仅实现镜像同步所需的最小面（update/实体列表/原子快照）。
     *  未用接口成员为合法 no-op 桩（测试只验证 StateSyncService 自身逻辑）。 */
    @Suppress("EmptyFunctionBlock")
    private class FakeStore : GameStateStore {
        var gameDataValue = GameData()
        var disciplesValue: List<Disciple> = emptyList()
        var equipmentStacksValue: List<EquipmentStack> = emptyList()
        var equipmentInstancesValue: List<com.xianxia.sect.core.model.EquipmentInstance> = emptyList()
        var manualStacksValue: List<com.xianxia.sect.core.model.ManualStack> = emptyList()
        var manualInstancesValue: List<com.xianxia.sect.core.model.ManualInstance> = emptyList()
        var pillsValue: List<Pill> = emptyList()
        var materialsValue: List<Material> = emptyList()
        var herbsValue: List<Herb> = emptyList()
        var seedsValue: List<Seed> = emptyList()
        var storageBagsValue: List<com.xianxia.sect.core.model.StorageBag> = emptyList()

        // 记录 update 调用（验证单事务）
        var updateCallCount = 0

        override fun update(block: MutableGameState.() -> Unit) {
            updateCallCount++
            val mgs = MutableGameState(
                gameData = gameDataValue,
                discipleTables = com.xianxia.sect.core.state.DiscipleTables().also {
                    it.writeAllowed = true
                    it.replaceAll(disciplesValue)
                },
                equipmentStacks = com.xianxia.sect.core.state.EntityStore(equipmentStacksValue),
                equipmentInstances = com.xianxia.sect.core.state.EntityStore(equipmentInstancesValue),
                manualStacks = com.xianxia.sect.core.state.EntityStore(manualStacksValue),
                manualInstances = com.xianxia.sect.core.state.EntityStore(manualInstancesValue),
                pills = com.xianxia.sect.core.state.EntityStore(pillsValue),
                materials = com.xianxia.sect.core.state.EntityStore(materialsValue),
                herbs = com.xianxia.sect.core.state.EntityStore(herbsValue),
                seeds = com.xianxia.sect.core.state.EntityStore(seedsValue),
                storageBags = com.xianxia.sect.core.state.EntityStore(storageBagsValue),
                battleLogs = emptyList(),
                isPaused = false,
                isLoading = false,
                isSaving = false
            )
            mgs.block()
            gameDataValue = mgs.gameData
            disciplesValue = mgs.discipleTables.assembleAll()
            equipmentStacksValue = mgs.equipmentStacks.all()
            equipmentInstancesValue = mgs.equipmentInstances.all()
            manualStacksValue = mgs.manualStacks.all()
            manualInstancesValue = mgs.manualInstances.all()
            pillsValue = mgs.pills.all()
            materialsValue = mgs.materials.all()
            herbsValue = mgs.herbs.all()
            seedsValue = mgs.seeds.all()
            storageBagsValue = mgs.storageBags.all()
        }

        override fun <R> updateAndReturn(block: MutableGameState.() -> R): R {
            val mgs = MutableGameState(
                gameData = gameDataValue,
                discipleTables = com.xianxia.sect.core.state.DiscipleTables().also {
                    it.writeAllowed = true
                    it.replaceAll(disciplesValue)
                },
                equipmentStacks = com.xianxia.sect.core.state.EntityStore(equipmentStacksValue),
                equipmentInstances = com.xianxia.sect.core.state.EntityStore(equipmentInstancesValue),
                manualStacks = com.xianxia.sect.core.state.EntityStore(manualStacksValue),
                manualInstances = com.xianxia.sect.core.state.EntityStore(manualInstancesValue),
                pills = com.xianxia.sect.core.state.EntityStore(pillsValue),
                materials = com.xianxia.sect.core.state.EntityStore(materialsValue),
                herbs = com.xianxia.sect.core.state.EntityStore(herbsValue),
                seeds = com.xianxia.sect.core.state.EntityStore(seedsValue),
                storageBags = com.xianxia.sect.core.state.EntityStore(storageBagsValue),
                battleLogs = emptyList(),
                isPaused = false,
                isLoading = false,
                isSaving = false
            )
            val result = mgs.block()
            gameDataValue = mgs.gameData
            disciplesValue = mgs.discipleTables.assembleAll()
            equipmentStacksValue = mgs.equipmentStacks.all()
            equipmentInstancesValue = mgs.equipmentInstances.all()
            manualStacksValue = mgs.manualStacks.all()
            manualInstancesValue = mgs.manualInstances.all()
            pillsValue = mgs.pills.all()
            materialsValue = mgs.materials.all()
            herbsValue = mgs.herbs.all()
            seedsValue = mgs.seeds.all()
            storageBagsValue = mgs.storageBags.all()
            return result
        }

        override fun takeAtomicSnapshot(): GameStateStore.GameSnapshot = GameStateStore.GameSnapshot(
            gameData = gameDataValue,
            disciples = disciplesValue,
            equipmentStacks = equipmentStacksValue,
            equipmentInstances = equipmentInstancesValue,
            manualStacks = manualStacksValue,
            manualInstances = manualInstancesValue,
            pills = pillsValue,
            materials = materialsValue,
            herbs = herbsValue,
            seeds = seedsValue,
            storageBags = storageBagsValue
        )

        // 未使用接口——最小实现
        override val gameData: StateFlow<GameData> get() = MutableStateFlow(gameDataValue)
        override val disciples: StateFlow<List<Disciple>> get() = MutableStateFlow(disciplesValue)
        override val discipleTables: com.xianxia.sect.core.state.DiscipleTables
            get() = com.xianxia.sect.core.state.DiscipleTables().also {
                it.writeAllowed = true
                it.replaceAll(disciplesValue)
            }
        override val equipmentStacks: StateFlow<List<EquipmentStack>> get() = MutableStateFlow(equipmentStacksValue)
        override val equipmentInstances: StateFlow<List<com.xianxia.sect.core.model.EquipmentInstance>>
            get() = MutableStateFlow(equipmentInstancesValue)
        override val manualStacks: StateFlow<List<com.xianxia.sect.core.model.ManualStack>>
            get() = MutableStateFlow(manualStacksValue)
        override val manualInstances: StateFlow<List<com.xianxia.sect.core.model.ManualInstance>>
            get() = MutableStateFlow(manualInstancesValue)
        override val pills: StateFlow<List<Pill>> get() = MutableStateFlow(pillsValue)
        override val materials: StateFlow<List<Material>> get() = MutableStateFlow(materialsValue)
        override val herbs: StateFlow<List<Herb>> get() = MutableStateFlow(herbsValue)
        override val seeds: StateFlow<List<Seed>> get() = MutableStateFlow(seedsValue)
        override val storageBags: StateFlow<List<com.xianxia.sect.core.model.StorageBag>>
            get() = MutableStateFlow(storageBagsValue)
        override val battleLogs: StateFlow<List<com.xianxia.sect.core.model.BattleLog>>
            get() = MutableStateFlow(emptyList())
        override val isPaused: StateFlow<Boolean> get() = MutableStateFlow(false)
        override val isLoading: StateFlow<Boolean> get() = MutableStateFlow(false)
        override val isSaving: StateFlow<Boolean> get() = MutableStateFlow(false)
        override val pendingBattleResult: StateFlow<com.xianxia.sect.core.state.BattleResultUIData?>
            get() = MutableStateFlow(null)
        override val pendingNotification: StateFlow<com.xianxia.sect.core.state.GameNotification?>
            get() = MutableStateFlow(null)
        override val rewardCardQueue: StateFlow<List<com.xianxia.sect.core.model.RewardCardItem>>
            get() = MutableStateFlow(emptyList())
        override val pendingBattleRewardCards: StateFlow<List<com.xianxia.sect.core.model.RewardCardItem>>
            get() = MutableStateFlow(emptyList())
        override val pendingBeastAttacks: StateFlow<List<com.xianxia.sect.core.state.PendingBeastAttack>>
            get() = MutableStateFlow(emptyList())
        override val pendingMarriageProposals: StateFlow<List<com.xianxia.sect.core.state.PendingMarriageProposal>>
            get() = MutableStateFlow(emptyList())
        override val highFreqState: StateFlow<GameStateStore.HighFreqState>
            get() = MutableStateFlow(GameStateStore.HighFreqState())
        override val entityState: StateFlow<GameStateStore.EntityState>
            get() = MutableStateFlow(GameStateStore.EntityState())
        override val configState: StateFlow<GameStateStore.ConfigState>
            get() = MutableStateFlow(GameStateStore.ConfigState())
        override val sectCombatPower: StateFlow<Long> get() = MutableStateFlow(0L)
        override val aiSectCombatPowers: StateFlow<Map<String, Long>> get() = MutableStateFlow(emptyMap())
        override val discipleAggregates: StateFlow<List<com.xianxia.sect.core.model.DiscipleAggregate>>
            get() = MutableStateFlow(emptyList())
        override val discipleAggregatesSnapshot: List<com.xianxia.sect.core.model.DiscipleAggregate> get() = emptyList()
        override val warehouseFullEvent: kotlinx.coroutines.flow.MutableSharedFlow<String>
            get() = kotlinx.coroutines.flow.MutableSharedFlow()
        override fun getCurrentSeeds(): List<Seed> = seedsValue
        override fun getCurrentHerbs(): List<Herb> = herbsValue
        override fun getCurrentMaterials(): List<Material> = materialsValue
        override val notifications: StateFlow<List<com.xianxia.sect.core.state.GameNotification>>
            get() = MutableStateFlow(emptyList())
        // ── GameStateSnapshotProvider 快照属性 ──
        override val gameDataSnapshot: GameData get() = gameDataValue
        override val disciplesSnapshot: List<Disciple> get() = disciplesValue
        override val equipmentStacksSnapshot: List<EquipmentStack> get() = equipmentStacksValue
        override val equipmentInstancesSnapshot: List<com.xianxia.sect.core.model.EquipmentInstance>
            get() = equipmentInstancesValue
        override val manualStacksSnapshot: List<com.xianxia.sect.core.model.ManualStack> get() = manualStacksValue
        override val manualInstancesSnapshot: List<com.xianxia.sect.core.model.ManualInstance>
            get() = manualInstancesValue
        override val pillsSnapshot: List<Pill> get() = pillsValue
        override val materialsSnapshot: List<Material> get() = materialsValue
        override val herbsSnapshot: List<Herb> get() = herbsValue
        override val seedsSnapshot: List<Seed> get() = seedsValue
        override val storageBagsSnapshot: List<com.xianxia.sect.core.model.StorageBag> get() = storageBagsValue
        override val battleLogsSnapshot: List<com.xianxia.sect.core.model.BattleLog> get() = emptyList()
        override fun enqueueNotification(notification: com.xianxia.sect.core.state.GameNotification) {}
        override fun consumeNotification(): com.xianxia.sect.core.state.GameNotification? = null
        override fun clearPendingNotification() {}
        override fun setPendingBattleResult(result: com.xianxia.sect.core.state.BattleResultUIData) {}
        override fun clearPendingBattleResult() {}
        override fun setPendingBeastAttacks(attacks: List<com.xianxia.sect.core.state.PendingBeastAttack>) {}
        override fun clearPendingBeastAttacks() {}
        override fun removePendingBeastAttack(beastLevelId: String) {}
        override fun clearPendingMarriageProposals() {}
        override fun setPendingBattleRewardCards(cards: List<com.xianxia.sect.core.model.RewardCardItem>) {}
        override fun clearPendingBattleRewardCards() {}
        override fun enqueueRewardCards(items: List<com.xianxia.sect.core.model.RewardCardItem>) {}
        override fun clearRewardCardQueue(count: Int) {}

        // === 交互状态与生命周期 ===
        override var activeTab: String = "main"
        override var activeDialog: String? = null
        override var activeSubDialogs: Set<String> = emptySet()
        override val lifecycleState: StateFlow<GameStateStore.LifecycleState>
            get() = MutableStateFlow(GameStateStore.LifecycleState())
        override val bootPhase: StateFlow<BootPhase> get() = MutableStateFlow(BootPhase.UNINITIALIZED)
        override val runState: StateFlow<RunState> get() = MutableStateFlow(RunState.IDLE)
        override fun advanceBootPhase() {}
        override fun resetBootPhase() {}
        override fun setPlaying() {}
        override fun setReloading() {}
        override fun modifyState(block: MutableGameState.() -> Unit) = update(block)
        override fun setLoading() {}
        override fun setPausedDirect(paused: Boolean) {}
        override fun setLoadingDirect(loading: Boolean) {}
        override fun setSavingDirect(saving: Boolean) {}
        override suspend fun loadFromSnapshot(
            gameData: GameData,
            disciples: List<Disciple>,
            equipmentStacks: List<EquipmentStack>,
            equipmentInstances: List<com.xianxia.sect.core.model.EquipmentInstance>,
            manualStacks: List<com.xianxia.sect.core.model.ManualStack>,
            manualInstances: List<com.xianxia.sect.core.model.ManualInstance>,
            pills: List<Pill>,
            materials: List<Material>,
            herbs: List<Herb>,
            seeds: List<Seed>,
            storageBags: List<com.xianxia.sect.core.model.StorageBag>,
            battleLogs: List<com.xianxia.sect.core.model.BattleLog>,
            isPaused: Boolean,
            isLoading: Boolean,
            isSaving: Boolean
        ) {
            gameDataValue = gameData
            disciplesValue = disciples
            equipmentStacksValue = equipmentStacks
            equipmentInstancesValue = equipmentInstances
            manualStacksValue = manualStacks
            manualInstancesValue = manualInstances
            pillsValue = pills
            materialsValue = materials
            herbsValue = herbs
            seedsValue = seeds
            storageBagsValue = storageBags
        }

        override suspend fun reset() {}
        override fun registerTransactionObserver(observer: GameStateStore.TransactionObserver) {}
    }

    // ── 测试用例 ─────────────────────────────────────────────────

    @Test
    fun `buildNativeState collects all fields`() {
        val store = FakeStore()
        store.gameDataValue = GameData().apply { spiritStones = 999; gameYear = 5 }
        store.disciplesValue = listOf(
            Disciple().apply { id = "1"; name = "张三"; realm = 7 }
        )
        store.equipmentStacksValue = listOf(
            EquipmentStack(id = "eq-1", name = "木剑", rarity = 1, slot = EquipmentSlot.WEAPON, quantity = 5)
        )
        store.pillsValue = listOf(
            Pill(id = "pill-1", name = "聚气丹", rarity = 2, quantity = 10)
        )
        store.materialsValue = listOf(
            Material(id = "mat-1", name = "兽皮", rarity = 1, quantity = 3)
        )
        store.herbsValue = listOf(Herb(id = "herb-1", name = "灵草", rarity = 1, quantity = 8, category = "普通"))
        store.seedsValue = listOf(Seed(id = "seed-1", name = "灵草种", rarity = 1, quantity = 2, growTime = 3, yield = 1))

        val service = StateSyncService(store)
        val native = service.buildNativeState()

        assertEquals(999L, native.gameData.spiritStones)
        assertEquals(5, native.gameData.gameYear)
        assertEquals(1, native.disciples.size)
        assertEquals("张三", native.disciples[0].name)
        assertEquals(5, native.equipmentStacks[0].quantity)
        assertEquals(10, native.pills[0].quantity)
        assertEquals(3, native.materials[0].quantity)
        assertEquals(8, native.herbs[0].quantity)
        assertEquals(2, native.seeds[0].quantity)
    }

    @Test
    fun `applySnapshot writes all fields in single transaction`() {
        val store = FakeStore()
        val service = StateSyncService(store)

        val snapshot = NativeGameState(
            gameData = GameData().apply { spiritStones = 500; gameMonth = 7 },
            disciples = listOf(Disciple().apply { id = "9"; name = "李四" }),
            equipmentStacks = listOf(
                EquipmentStack(id = "eq-9", name = "铁剑", rarity = 2, slot = EquipmentSlot.WEAPON, quantity = 3)
            ),
            pills = listOf(Pill(id = "pill-9", name = "回气丹", rarity = 1, quantity = 20)),
            materials = listOf(Material(id = "mat-9", name = "兽骨", rarity = 2, quantity = 7)),
            herbs = listOf(Herb(id = "herb-9", name = "寒霜草", rarity = 2, quantity = 4, category = "冰")),
            seeds = listOf(Seed(id = "seed-9", name = "寒霜草种", rarity = 2, quantity = 1, growTime = 6, yield = 2))
        )

        service.applySnapshot(snapshot)

        // 单事务
        assertEquals(1, store.updateCallCount)
        // 全字段写入
        assertEquals(500L, store.gameDataValue.spiritStones)
        assertEquals(7, store.gameDataValue.gameMonth)
        assertEquals(1, store.disciplesValue.size)
        assertEquals("李四", store.disciplesValue[0].name)
        assertEquals(3, store.equipmentStacksValue[0].quantity)
        assertEquals(20, store.pillsValue[0].quantity)
        assertEquals(7, store.materialsValue[0].quantity)
        assertEquals(4, store.herbsValue[0].quantity)
        assertEquals(1, store.seedsValue[0].quantity)
    }

    @Test
    fun `round trip build then apply preserves state`() {
        val store = FakeStore()
        store.gameDataValue = GameData().apply { spiritStones = 12345; gameYear = 12; gameMonth = 3 }
        store.disciplesValue = listOf(Disciple().apply { id = "1"; name = "王五"; realm = 5; realmLayer = 3 })
        store.equipmentStacksValue = listOf(
            EquipmentStack(id = "eq-1", name = "玄铁剑", rarity = 4, slot = EquipmentSlot.WEAPON, quantity = 1)
        )
        store.materialsValue = listOf(
            Material(id = "mat-1", name = "龙鳞", rarity = 5, quantity = 2)
        )

        val service = StateSyncService(store)
        val native = service.buildNativeState()
        // 清空后镜像恢复
        store.gameDataValue = GameData()
        store.disciplesValue = emptyList()
        store.equipmentStacksValue = emptyList()
        store.materialsValue = emptyList()

        service.applySnapshot(native)

        assertEquals(12345L, store.gameDataValue.spiritStones)
        assertEquals(12, store.gameDataValue.gameYear)
        assertEquals(1, store.disciplesValue.size)
        assertEquals("王五", store.disciplesValue[0].name)
        assertEquals(1, store.equipmentStacksValue[0].quantity)
        assertEquals(2, store.materialsValue[0].quantity)
    }

    @Test
    fun `empty snapshot disciples keeps existing list`() {
        // 宽松合并语义：C++ 未覆盖字段保留 Kotlin 侧值
        val store = FakeStore()
        store.disciplesValue = listOf(Disciple().apply { id = "100"; name = "幸存者" })
        val service = StateSyncService(store)

        val snapshot = NativeGameState(gameData = GameData().apply { spiritStones = 100 })
        service.applySnapshot(snapshot)

        assertEquals(100L, store.gameDataValue.spiritStones)
        // disciples 快照为空 → 保留既有列表（宽松合并）
        assertEquals(1, store.disciplesValue.size)
        assertEquals("幸存者", store.disciplesValue[0].name)
    }

    @Test
    fun `importToNative returns false when native unavailable`() {
        // 未加载 native 库 → importToNative 静默降级 false（不崩溃）
        val store = FakeStore()
        val service = StateSyncService(store)
        // 仅验证不抛异常（真实 native 路径由 Diff 测试覆盖）
        val result = service.importToNative()
        assertTrue("未初始化时应返回 false", !result)
    }

    @Test
    fun `mergeGameData keeps unmigrated fields`() {
        // C++ 只导出已迁移字段——白名单外字段保留 Kotlin 值（宽松合并）
        val store = FakeStore()
        store.gameDataValue = GameData().apply {
            spiritStones = 100
            gameYear = 3
            sectName = "青云宗"          // C++ 未迁移字段
            jadeSymbols = 42              // C++ 未迁移字段
        }
        val service = StateSyncService(store)

        val snapshotGameData = GameData().apply {
            spiritStones = 500            // C++ 已迁移：覆盖
            gameYear = 4                  // C++ 已迁移：覆盖
        }
        val merged = service.mergeGameData(store.gameDataValue, snapshotGameData,
            exportedKeys = setOf("spiritStones", "gameYear"))

        assertEquals(500L, merged.spiritStones)
        assertEquals(4, merged.gameYear)
        // 白名单外字段保留 Kotlin 值
        assertEquals("青云宗", merged.sectName)
        assertEquals(42, merged.jadeSymbols)
    }

    @Test
    fun `applySnapshot with exported keys merges not replaces`() {
        val store = FakeStore()
        store.gameDataValue = GameData().apply {
            spiritStones = 100
            sectName = "青云宗"
            jadeSymbols = 42
        }
        val service = StateSyncService(store)
        val snapshot = NativeGameState(
            gameData = GameData().apply { spiritStones = 500 }
        )
        service.applySnapshot(snapshot, exportedGameDataKeys = setOf("spiritStones"))

        assertEquals(500L, store.gameDataValue.spiritStones)
        assertEquals("青云宗", store.gameDataValue.sectName)
        assertEquals(42, store.gameDataValue.jadeSymbols)
    }
}
