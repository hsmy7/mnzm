package com.xianxia.sect.core.nativebridge

import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.Herb
import com.xianxia.sect.core.model.Pill
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * DiffDirtyTest — exportDirty 变更集跨语言对拍（计划 v2 阶段 1 验收核心）。
 *
 * 守护目标：
 *   1. C++ DirtyTracker 产出的变更集协议（changed/removed/version）能被
 *      Kotlin [StateSyncService.applyDirty] 正确增量应用（单事务原子）
 *   2. 未变化字段/实体零触碰；空变更集零写入
 *   3. 实体按 id upsert/remove 语义正确（含未知路径宽松忽略）
 *
 * 平台约束（重要）：
 *   - 本类为**普通 JUnit**：桌面 .so 只能在应用 ClassLoader 加载一次，
 *     Robolectric 沙箱类加载器会触发 "already loaded in another classloader"
 *     （与 DiffTimeTest/DiffRngTest 同规约）
 *   - 弟子集合（DiscipleTables/SparseArray）在普通 JVM 下不可用
 *     （android.jar stub 静默 no-op）——其增量应用行为由
 *     [DiffDirtyDisciplesTest]（Robolectric、不经 native）单独守护
 */
class DiffDirtyTest {

    private val json = Json { encodeDefaults = true }

    // ── JNI 端到端 ───────────────────────────────────────────────

    @Test
    fun `applyDirty applies native changes in single transaction`() {
        assumeTrue(DiffRngBridge.isAvailable())
        DiffRngBridge.nativeCoreInit()

        val store = FakeGameStateStore()
        store.gameDataValue = GameData().apply {
            spiritStones = 111
            sectName = "测试宗"
            gameYear = 1; gameMonth = 1; gamePhase = 0
        }
        val service = StateSyncService(store)
        val encoded = json.encodeToString(
            NativeGameState.serializer(), service.buildNativeState()
        )
        assertTrue("C++ 导入失败", DiffRngBridge.nativeCoreImportState(encoded.encodeToByteArray()))

        // 推进 2 旬 → 变更集应含时间字段
        assertEquals(2, DiffRngBridge.nativeCoreAdvancePhases(2))
        val dirtyJson = DiffRngBridge.nativeCoreExportDirty().decodeToString()
        val result = service.applyDirty(dirtyJson)

        assertNotNull(result)
        assertTrue(
            "时间推进后变更集字段数应 ≥1（实际 ${result!!.changedFieldCount}）",
            result.changedFieldCount >= 1,
        )
        assertEquals("必须单事务原子应用", 1, store.updateCallCount)
        // 时间字段已镜像推进（1年1月上旬 → 上旬+2 = 中旬）
        assertEquals(2, store.gameDataValue.gamePhase)
        // 未变化资源保持原值（C++ 推进不碰钱包）
        assertEquals(111L, store.gameDataValue.spiritStones)
    }

    @Test
    fun `second pull after apply is empty and writes nothing`() {
        assumeTrue(DiffRngBridge.isAvailable())
        DiffRngBridge.nativeCoreInit()

        val store = FakeGameStateStore()
        val service = StateSyncService(store)
        val encoded = json.encodeToString(
            NativeGameState.serializer(), service.buildNativeState()
        )
        assertTrue(DiffRngBridge.nativeCoreImportState(encoded.encodeToByteArray()))
        DiffRngBridge.nativeCoreAdvancePhases(1)

        val first = service.applyDirty(DiffRngBridge.nativeCoreExportDirty().decodeToString())
        assertNotNull(first)
        val transactionsAfterFirst = store.updateCallCount

        // 基线已随导出推进 → 第二次拉取为空且零写入
        val second = service.applyDirty(DiffRngBridge.nativeCoreExportDirty().decodeToString())
        assertNotNull(second)
        assertEquals(0, second!!.changedFieldCount)
        assertEquals(0, second.upsertCount)
        assertEquals(0, second.removedCount)
        assertEquals("空变更集不得触发事务", transactionsAfterFirst, store.updateCallCount)
    }

    @Test
    fun `full export resets dirty baseline`() {
        assumeTrue(DiffRngBridge.isAvailable())
        DiffRngBridge.nativeCoreInit()

        val store = FakeGameStateStore()
        val service = StateSyncService(store)
        val encoded = json.encodeToString(
            NativeGameState.serializer(), service.buildNativeState()
        )
        assertTrue(DiffRngBridge.nativeCoreImportState(encoded.encodeToByteArray()))
        DiffRngBridge.nativeCoreAdvancePhases(3)

        // 全量快照导出（桌面通道；生产 syncFromNative 的 native 侧同源）后，
        // 变更集清零——接收方已拿到全部状态，无需重复增量
        assertTrue(DiffRngBridge.nativeCoreExportState().decodeToString() != "{}")
        val afterFull = service.applyDirty(DiffRngBridge.nativeCoreExportDirty().decodeToString())
        assertNotNull(afterFull)
        assertEquals(0, afterFull!!.changedFieldCount + afterFull.upsertCount + afterFull.removedCount)
    }

    @Test
    fun `applyDirtyFromNative returns null when native unavailable`() {
        // 未加载桌面 JNI → 降级 null 不崩溃（已加载环境下本用例无意义，直接通过）
        if (DiffRngBridge.isAvailable()) return
        val store = FakeGameStateStore()
        val service = StateSyncService(store)
        assertEquals(null, service.applyDirtyFromNative())
    }

    // ── applier 协议细节（直测，不经 native） ─────────────────────

    @Test
    fun `entity upserts and removals applied by id`() {
        val store = FakeGameStateStore()
        store.pillsValue = listOf(Pill(id = "p-old", name = "旧丹", rarity = 1, quantity = 1))
        store.herbsValue = listOf(Herb(id = "h-1", name = "灵草", rarity = 1, quantity = 2, category = "普通"))
        val service = StateSyncService(store)

        val result = service.applyDirty(
            """
            {"version":7,
             "changed":{
               "pills":[{"id":"p-new","name":"新丹","rarity":2,"quantity":3}],
               "futureCollection":[{"id":"x"}]},
             "removed":{"pills":["p-old"],"herbs":["h-1"]}}
            """.trimIndent()
        )

        assertNotNull(result)
        assertEquals(7L, result!!.version)
        assertEquals(1, result.upsertCount)   // pill（未知集合不计）
        assertEquals(2, result.removedCount)  // p-old + h-1

        // pills：旧删新增
        assertEquals(1, store.pillsValue.size)
        assertEquals("p-new", store.pillsValue[0].id)
        assertEquals(3, store.pillsValue[0].quantity)
        // herbs：整删
        assertTrue(store.herbsValue.isEmpty())
    }

    @Test
    fun `malformed dirty json returns null without touching state`() {
        val store = FakeGameStateStore()
        store.gameDataValue = GameData().apply { spiritStones = 42 }
        val service = StateSyncService(store)

        assertEquals(null, service.applyDirty("not-a-json{{{"))
        assertEquals(null, service.applyDirty("""{"version":1,"changed":12}"""))
        assertEquals(42L, store.gameDataValue.spiritStones)
        assertEquals(0, store.updateCallCount)
    }

    @Test
    fun `unknown gameData field is leniently ignored`() {
        val store = FakeGameStateStore()
        store.gameDataValue = GameData().apply { spiritStones = 5; sectName = "原名" }
        val service = StateSyncService(store)

        val result = service.applyDirty(
            """{"version":1,"changed":{"gameData.spiritStones":99,"gameData.futureField":123}}"""
        )

        assertNotNull(result)
        assertEquals(99L, store.gameDataValue.spiritStones)
        assertEquals("原名", store.gameDataValue.sectName)
    }
}
