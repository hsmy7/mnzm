package com.xianxia.sect.core.nativebridge

import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.state.DiscipleTables
import com.xianxia.sect.core.state.GameStateStore
import com.xianxia.sect.core.state.ReverseChannelPolicy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * StateSyncServiceReverseTest — 反向增量回导信封构建与窗口语义。
 *
 * 覆盖：空窗口零发送 / 弟子仅发变化 id 全实体 / gameData 剔除 rngStates /
 * 集合 upsert+removed / 版本单调递增 / 多事务窗口合并 / reset 清窗 /
 * 移除弟子进 removed 通道。C++ 应用语义由 apply_reverse_dirty_test.cpp 守护。
 *
 * 平台约束：信封构建经 DiscipleTables（android.util.SparseArray）组装——普通
 * JVM 的 android.jar stub 在 returnDefaultValues=true 下静默 no-op，必须
 * Robolectric 运行（与 DiffDirtyDisciplesTest 同约束）；本类不经 native
 *（发送器注入记录桩），不触发桌面 .so 加载。
 */
@org.junit.experimental.categories.Category(com.xianxia.sect.core.RobolectricTests::class)
@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [34])
class StateSyncServiceReverseTest {

    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    private fun disciple(id: String, name: String, cultivation: Double = 10.0) = Disciple(
        id = id, name = name, realm = 9, realmLayer = 1, cultivation = cultivation,
        spiritRootType = "metal"
    )

    /** JsonElement? → 字符串值（JsonPrimitive 才解包；结构不匹配返回 null）。 */
    private fun JsonElement?.string(): String? = (this as? JsonPrimitive)?.contentOrNull

    private fun tablesWith(vararg disciples: Disciple): DiscipleTables =
        DiscipleTables().also {
            it.writeAllowed = true
            it.replaceAll(disciples.toList())
        }

    @Test
    fun `empty window returns true without native call`() {
        val store = FakeGameStateStore()
        var nativeCalled = false
        val sync = StateSyncService(store) {
            nativeCalled = true
            true
        }
        assertTrue(sync.applyDirtyToNative())
        assertFalse(nativeCalled)
    }

    @Test
    fun `disciple change encodes full entity for changed id only`() {
        val store = FakeGameStateStore()
        val tables = tablesWith(disciple("5", "甲"), disciple("6", "乙"))
        val sync = StateSyncService(store) { true }
        val snapshot = GameStateStore.ReverseDirtySnapshot(discipleIds = setOf(5))
        val envelope = sync.buildReverseEnvelope(snapshot, tables, store.gameDataValue)
        val changed = envelope["changed"]!!.jsonObject
        val disciples = changed["disciples"]!!.jsonArray
        assertEquals(1, disciples.size)
        assertEquals("5", disciples[0].jsonObject["id"].string())
        assertEquals("甲", disciples[0].jsonObject["name"].string())
        // 未变化弟子不出现在信封
        assertNull(changed["disciples"]?.jsonArray?.firstOrNull {
            it.jsonObject["id"].string() == "6"
        })
        assertNull(envelope["removed"]!!.jsonObject["disciples"])
    }

    @Test
    fun `gameData channel excludes rngStates`() {
        val store = FakeGameStateStore()
        store.gameDataValue = store.gameDataValue.copy(rngStates = mapOf(1 to 999L))
        val sync = StateSyncService(store) { true }
        val snapshot = GameStateStore.ReverseDirtySnapshot(gameDataChanged = true)
        val envelope = sync.buildReverseEnvelope(snapshot, store.discipleTables, store.gameDataValue)
        val gd = envelope["changed"]!!.jsonObject["gameData"]!!.jsonObject
        assertNull(gd["rngStates"])
        assertTrue(gd["spiritStones"] != null)
    }

    @Test
    fun `gameData segment carries only changed fields after anchor`() {
        // 反向通道差分语义：首窗未锚定 → 全量建立基线；后续窗与锚点按键
        // 差分 → 仅携带变更字段（不做 gameData 整段重发）
        val store = FakeGameStateStore()
        val sent = mutableListOf<String>()
        val sync = StateSyncService(store) { sent += it.decodeToString(); true }

        store.update { gameData = gameData.copy(spiritStones = 100) }
        assertTrue(sync.applyDirtyToNative())
        val full = json.parseToJsonElement(sent[0]).jsonObject["changed"]!!
            .jsonObject["gameData"]!!.jsonObject
        assertTrue("首窗应为全量段（实际 ${full.size} 键）", full.size > 1)
        assertTrue(full["spiritStones"] != null)

        store.update { gameData = gameData.copy(spiritStones = 200) }
        assertTrue(sync.applyDirtyToNative())
        val patch = json.parseToJsonElement(sent[1]).jsonObject["changed"]!!
            .jsonObject["gameData"]!!.jsonObject
        assertEquals("增量窗仅携带变更字段", setOf("spiritStones"), patch.keys)
        assertEquals(200L, patch["spiritStones"]!!.jsonPrimitive.longOrNull)
    }

    @Test
    fun `failed send keeps anchor so next window resends full gameData`() {
        // 发送失败 → 锚点不推进（保守过期方向）；生产由全量回导兜底，
        // 此处断言下一成功窗仍全量重发（绝不因锚点越过 C++ 实际状态而漏发）
        val store = FakeGameStateStore()
        var ok = false
        val sent = mutableListOf<String>()
        val sync = StateSyncService(store) { sent += it.decodeToString(); ok }

        store.update { gameData = gameData.copy(spiritStones = 100) }
        assertFalse(sync.applyDirtyToNative())
        store.update { gameData = gameData.copy(spiritStones = 150) }
        ok = true
        assertTrue(sync.applyDirtyToNative())
        val gd = json.parseToJsonElement(sent.last()).jsonObject["changed"]!!
            .jsonObject["gameData"]!!.jsonObject
        assertTrue("锚点未推进应全量重发", gd.size > 1)
        assertEquals(150L, gd["spiritStones"]!!.jsonPrimitive.longOrNull)
    }

    @Test
    fun `collection capture encodes upserts and removed ids`() {
        val store = FakeGameStateStore()
        val sync = StateSyncService(store) { true }
        val capture = GameStateStore.CollectionCapture(
            upserts = listOf(
                com.xianxia.sect.core.model.EquipmentStack(id = "e1", name = "剑", quantity = 1)
            ),
            removedIds = setOf("e_old")
        )
        val snapshot = GameStateStore.ReverseDirtySnapshot(
            collections = mapOf("equipmentStacks" to capture)
        )
        val envelope = sync.buildReverseEnvelope(snapshot, store.discipleTables, store.gameDataValue)
        val changed = envelope["changed"]!!.jsonObject
        val stacks = changed["equipmentStacks"]!!.jsonArray
        assertEquals(1, stacks.size)
        assertEquals("e1", stacks[0].jsonObject["id"].string())
        val removed = envelope["removed"]!!.jsonObject["equipmentStacks"]!!.jsonArray
        assertEquals(listOf("e_old"), removed.map { it.jsonPrimitive.contentOrNull })
    }

    @Test
    fun `removed disciple goes to removed channel`() {
        val store = FakeGameStateStore()
        val sync = StateSyncService(store) { true }
        // 快照声明 5 被修改，但当前表已无 5（remove 窗口语义）
        val snapshot = GameStateStore.ReverseDirtySnapshot(discipleIds = setOf(5))
        val envelope = sync.buildReverseEnvelope(
            snapshot, tablesWith(), store.gameDataValue
        )
        val removed = envelope["removed"]!!.jsonObject["disciples"]!!.jsonArray
        assertEquals(listOf("5"), removed.map { it.jsonPrimitive.contentOrNull })
        assertNull(envelope["changed"]!!.jsonObject["disciples"])
    }

    @Test
    fun `version increments after each successful apply`() {
        val store = FakeGameStateStore()
        val envelopes = mutableListOf<String>()
        val sync = StateSyncService(store) { bytes ->
            envelopes += bytes.decodeToString()
            true
        }
        store.update { gameData = gameData.copy(spiritStones = 100) }
        assertTrue(sync.applyDirtyToNative())
        assertEquals(
            1L,
            json.parseToJsonElement(envelopes[0]).jsonObject["version"]!!.jsonPrimitive.longOrNull
        )

        store.update { gameData = gameData.copy(spiritStones = 200) }
        assertTrue(sync.applyDirtyToNative())
        assertEquals(
            2L,
            json.parseToJsonElement(envelopes[1]).jsonObject["version"]!!.jsonPrimitive.longOrNull
        )
    }

    @Test
    fun `window accumulates multiple transactions and drains`() {
        val store = FakeGameStateStore()
        store.disciplesValue = listOf(disciple("5", "甲"))
        val envelopes = mutableListOf<String>()
        val sync = StateSyncService(store) { bytes ->
            envelopes += bytes.decodeToString()
            true
        }
        store.update { discipleTables.update(disciple("5", "甲改")) }
        store.update { gameData = gameData.copy(spiritStones = 555) }
        assertTrue(sync.applyDirtyToNative())
        assertEquals(1, envelopes.size)
        val changed = json.parseToJsonElement(envelopes[0]).jsonObject["changed"]!!.jsonObject
        // 两个事务的捕获合并到一次回导
        assertTrue(changed["disciples"] != null)
        assertTrue(changed["gameData"] != null)
        // 窗口已消费：第二次回导零发送
        assertTrue(sync.applyDirtyToNative())
        assertEquals(1, envelopes.size)
    }

    @Test
    fun `reset clears the window`() {
        val store = FakeGameStateStore()
        store.disciplesValue = listOf(disciple("5", "甲"))
        val envelopes = mutableListOf<String>()
        val sync = StateSyncService(store) { bytes ->
            envelopes += bytes.decodeToString()
            true
        }
        store.update { discipleTables.update(disciple("5", "甲改")) }
        store.resetReverseAccumulator()
        assertTrue(sync.applyDirtyToNative())
        assertEquals(0, envelopes.size)
    }

    @Test
    fun `lockedBeastIds segment is closed and reopen restores replacement semantics`() {
        // batch-21：该段已关闭（batch-23 把 UI 锁定/解锁操作面下沉 C++，Kotlin 侧仅剩回退臂写者）
        // ——关闭后不再占用信封面；逐域回滚可恢复"变化时携带全量 id 集"的整体替换语义
        val store = FakeGameStateStore()
        val sent = mutableListOf<String>()
        val sync = StateSyncService(store) { sent += it.decodeToString(); true }

        store.update { gameData = gameData.copy(lockedBeastIds = setOf("beast-1", "beast-2")) }
        assertTrue(sync.applyDirtyToNative())
        assertNull(
            "关闭段不得进入信封",
            json.parseToJsonElement(sent.last()).jsonObject["changed"]!!.jsonObject["lockedBeastIds"]
        )

        // 回滚该域（紧急回滚路径）→ 段恢复携带
        ReverseChannelPolicy.reopenDomain(ReverseChannelPolicy.Domain.BATTLE)
        try {
            val store2 = FakeGameStateStore()
            val sent2 = mutableListOf<String>()
            val sync2 = StateSyncService(store2) { sent2 += it.decodeToString(); true }
            store2.update { gameData = gameData.copy(lockedBeastIds = setOf("beast-1", "beast-2")) }
            assertTrue(sync2.applyDirtyToNative())
            val changed = json.parseToJsonElement(sent2.last()).jsonObject["changed"]!!.jsonObject
            assertEquals(
                listOf("beast-1", "beast-2"),
                changed["lockedBeastIds"]!!.jsonArray.map { it.jsonPrimitive.contentOrNull }
            )
        } finally {
            ReverseChannelPolicy.resetSwitches()
        }
    }
}
