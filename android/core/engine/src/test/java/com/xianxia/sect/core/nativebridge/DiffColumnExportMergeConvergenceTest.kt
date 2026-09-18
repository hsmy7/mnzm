package com.xianxia.sect.core.nativebridge

import com.xianxia.sect.core.model.CombatAttributes
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.DiscipleStatus
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.Herb
import com.xianxia.sect.core.model.Material
import com.xianxia.sect.core.model.Pill
import com.xianxia.sect.core.model.Seed
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * DiffColumnExportMergeConvergenceTest — R2.4/B09 列级导出生产接线的
 * **Kotlin 消费侧收敛守卫**（真实 C++ 结算驱动）。
 *
 * 红线对照（batch-R2D.md「对拍零漂移」）：列级导出仅序列化脏列必须与全量
 * 导出在相同初态+相同写集下语义等价。C++ GTest
 * `ColumnExportEquivalenceTest` 在信封树层对照；本类在 **Kotlin store 层**
 * 对照：同一旬的真实结算分别以「列级信封 → 补丁合并应用」与「全量信封 →
 * 全行应用」馈送到同初态的两个 store，逐旬断言 gameData/实体集合/弟子表
 * 逐字段全等——C++ 写屏障漏标/错标或 Kotlin 合并语义漂移即红。
 *
 * 平台约束：普通 JUnit（桌面 .so 单 ClassLoader）；对拍桥
 * `nativeCoreExportDirtyColumn`（列级树 JSON，只消费 ColumnDirtyTracker）
 * 与 `nativeCoreExportDirty`（全量树 JSON）严格配对同一写集，
 * `nativeCoreEncodeGameView` 纯编码为 protobuf 信封后走生产
 * [StateSyncService.applyDirtyProto]。
 */
class DiffColumnExportMergeConvergenceTest {

    @Test
    fun `column-level envelope converges with full envelope across phases`() {
        assumeTrue(DiffRngBridge.isAvailable())
        DiffRngBridge.nativeCoreInit()

        val arms = TwoArms()
        repeat(PHASES) { arms.advanceOnePhase() }
        arms.assertConverged()
    }

    private class TwoArms {
        private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
        private val seed = seedState()
        private val storeColumn = FakeGameStateStore().also { seedStore(it, seed) }
        private val storeFull = FakeGameStateStore().also { seedStore(it, seed) }
        private val columnArm = StateSyncService(storeColumn)
        private val fullArm = StateSyncService(storeFull)
        private var convergedPhases = 0

        init {
            assertTrue(
                "C++ 导入失败",
                DiffRngBridge.nativeCoreImportState(
                    json.encodeToString(NativeGameState.serializer(), seed).encodeToByteArray()
                )
            )
        }

        /** 一旬：真实结算 → 月/年边界结算 → 双臂导出编码 → 分别馈送 → 对账。 */
        fun advanceOnePhase() {
            val flags = DiffRngBridge.nativeCoreSettlePhase()
            // 与生产 processMonthYearChange 同序：年变先于月变
            if (flags and 0b10 != 0) DiffRngBridge.nativeCoreSettleYear()
            if (flags and 0b01 != 0) DiffRngBridge.nativeCoreSettleMonth()

            // 先列级后全量：两封分别消费各自追踪器（C++ 守卫同款双臂序）
            val columnTree = DiffRngBridge.nativeCoreExportDirtyColumn().decodeToString()
            val fullTree = DiffRngBridge.nativeCoreExportDirty().decodeToString()
            val columnProto = DiffRngBridge.nativeCoreEncodeGameView(columnTree.encodeToByteArray())
            val fullProto = DiffRngBridge.nativeCoreEncodeGameView(fullTree.encodeToByteArray())
            if (convergedPhases == 0) {
                println("[probe] COLTREE=$columnTree")
                println("[probe] FULLTREE=$fullTree")
            }

            val viaColumn = withColumnFlag(true) { columnArm.applyDirtyProto(columnProto) }
            val viaFull = withColumnFlag(false) { fullArm.applyDirtyProto(fullProto) }

            assertNotNull("列级信封解析/应用失败", viaColumn)
            assertNotNull("全量信封解析/应用失败", viaFull)
            assertEquals(
                "列级/全量双臂 gameData 全字段一致（写屏障漏标或合并漂移即红）",
                storeFull.gameDataValue, storeColumn.gameDataValue,
            )
            assertEquals("列级/全量双臂弟子表一致", storeFull.disciplesValue, storeColumn.disciplesValue)
            assertEquals("pills 一致", storeFull.pillsValue, storeColumn.pillsValue)
            assertEquals("herbs 一致", storeFull.herbsValue, storeColumn.herbsValue)
            assertEquals("materials 一致", storeFull.materialsValue, storeColumn.materialsValue)
            assertEquals("seeds 一致", storeFull.seedsValue, storeColumn.seedsValue)
            convergedPhases++
        }

        fun assertConverged() {
            assertTrue("须完成 ≥1 旬对账（否则本守卫空转）", convergedPhases >= 1)
        }

        private inline fun <T> withColumnFlag(on: Boolean, block: () -> T): T {
            val previous = NativeEngineFlag.dirtyColumnExport
            NativeEngineFlag.dirtyColumnExport = on
            return try {
                block()
            } finally {
                NativeEngineFlag.dirtyColumnExport = previous
            }
        }

        private fun seedStore(store: FakeGameStateStore, seed: NativeGameState) {
            store.gameDataValue = seed.gameData
            store.disciplesValue = seed.disciples
            store.pillsValue = seed.pills
            store.herbsValue = seed.herbs
            store.materialsValue = seed.materials
            store.seedsValue = seed.seeds
        }
    }

    companion object {
        private const val PHASES = 12

        private fun seedState(): NativeGameState = NativeGameState(
            gameData = GameData().apply {
                spiritStones = 5000
                gameYear = 2
                gameMonth = 3
                gamePhase = 0
                sectName = "列级宗"
            },
            disciples = listOf(
                disciple("1", "玄真", 3, 55.0),
                disciple("2", "李四", 5, 10.0),
                disciple("3", "王五", 7, 30.0),
            ),
            pills = listOf(Pill(id = "p1", name = "聚气丹", rarity = 1, quantity = 4)),
            herbs = listOf(Herb(id = "h1", name = "灵草", rarity = 1, quantity = 6, category = "普通")),
            materials = listOf(Material(id = "m1", name = "兽皮", rarity = 1, quantity = 3)),
            seeds = listOf(Seed(id = "s1", name = "灵草种", rarity = 1, quantity = 2, growTime = 3, yield = 1)),
        )

        private fun disciple(
            id: String,
            name: String,
            realm: Int,
            cultivation: Double,
        ): Disciple = Disciple(
            id = id,
            name = name,
            realm = realm,
            realmLayer = 1,
            cultivation = cultivation,
            soulPower = 100,
            status = DiscipleStatus.IDLE,
        ).apply {
            combat = CombatAttributes(currentHp = 500, currentMp = 300)
        }
    }
}
