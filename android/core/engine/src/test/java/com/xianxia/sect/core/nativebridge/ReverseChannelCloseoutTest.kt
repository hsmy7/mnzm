package com.xianxia.sect.core.nativebridge

import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.Pill
import com.xianxia.sect.core.state.ReverseChannelPolicy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ReverseChannelCloseoutTest — 反向通道**逐域关闭语义**（batch-21）。
 *
 * 覆盖三件事：
 * 1. 关闭的单元不进信封（gameData 字段 / 集合段 / 顶层段），信封不再为其产生字节；
 * 2. 关闭后若仍有 Kotlin 写者 ⇒ **关闭域写入检测**命中（可从诊断面观测，
 *    把"漏域"从静默丢数据变成可归因缺陷）；
 * 3. 逐域回滚（[ReverseChannelPolicy.reopenDomain]）恢复传输——关闭开关可单域撤销。
 *
 * 平台约束：信封构建经 `DiscipleTables`（android.util.SparseArray）组装 ⇒ Robolectric；
 * 本类不经 native（发送器注入记录桩）。
 */
@org.junit.experimental.categories.Category(com.xianxia.sect.core.RobolectricTests::class)
@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [34])
class ReverseChannelCloseoutTest {

    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    @After
    fun tearDown() {
        ReverseChannelPolicy.resetSwitches()
    }

    private fun closedField(name: String) = ReverseChannelPolicy.ClosedUnit(
        domain = ReverseChannelPolicy.Domain.INVENTORY,
        kind = ReverseChannelPolicy.Kind.GAME_DATA_FIELD,
        name = name,
    )

    @Test
    fun `closed gameData field is stripped from envelope and its write is detected`() {
        ReverseChannelPolicy.overrideClosedUnitsForTest(listOf(closedField("spiritStones")))
        val store = FakeGameStateStore()
        val sent = mutableListOf<String>()
        val sync = StateSyncService(store) { sent += it.decodeToString(); true }
        // 建立"C++ 已知值"基线（全量镜像语义）
        sync.applySnapshot(NativeGameState(gameData = store.gameDataValue))

        store.update { gameData = gameData.copy(spiritStones = 999) }
        assertTrue(sync.applyDirtyToNative())

        val changed = json.parseToJsonElement(sent.last()).jsonObject["changed"]!!.jsonObject
        assertFalse(
            "关闭字段不得随信封回导（Kotlin 写入即数据丢失风险）",
            changed["gameData"]?.jsonObject?.containsKey("spiritStones") == true
        )
        assertTrue(
            "关闭字段被 Kotlin 写入必须被检测（诊断面计数 > 0）",
            ReverseChannelPolicy.closedWriteCountSnapshot() > 0
        )
        assertTrue(
            "检测记录应含字段名",
            ReverseChannelPolicy.closedWriteRecordsSnapshot().any { it.contains("spiritStones") }
        )
    }

    @Test
    fun `transported gameData field still flows`() {
        val store = FakeGameStateStore()
        val sent = mutableListOf<String>()
        val sync = StateSyncService(store) { sent += it.decodeToString(); true }
        sync.applySnapshot(NativeGameState(gameData = store.gameDataValue))

        store.update { gameData = gameData.copy(jadeSymbols = 42) }
        assertTrue(sync.applyDirtyToNative())

        val changed = json.parseToJsonElement(sent.last()).jsonObject["changed"]!!.jsonObject
        assertTrue(
            "在册保留字段（玉符运行时为 Kotlin 掌有）必须继续回导",
            changed["gameData"]?.jsonObject?.containsKey("jadeSymbols") == true
        )
        assertTrue("在册保留字段的写入不得计入关闭域检测", ReverseChannelPolicy.closedWriteCountSnapshot() == 0L)
    }

    @Test
    fun `closed top level section is not carried`() {
        val store = FakeGameStateStore()
        val sent = mutableListOf<String>()
        val sync = StateSyncService(store) { sent += it.decodeToString(); true }

        store.update { gameData = gameData.copy(lockedBeastIds = setOf("beast-1")) }
        assertTrue(sync.applyDirtyToNative())
        assertFalse(
            "已关闭的 lockedBeastIds 段（batch-23 已下沉 UI 操作面）不得再占用信封面",
            sent.last().contains("\"lockedBeastIds\"")
        )
    }

    @Test
    fun `closed collection capture is dropped and detected`() {
        ReverseChannelPolicy.overrideClosedUnitsForTest(
            listOf(
                ReverseChannelPolicy.ClosedUnit(
                    domain = ReverseChannelPolicy.Domain.INVENTORY,
                    kind = ReverseChannelPolicy.Kind.COLLECTION,
                    name = "pills",
                )
            )
        )
        val store = FakeGameStateStore()
        store.update { pills.add(Pill(id = "p1", name = "丹", quantity = 1)) }

        val snapshot = store.consumeReverseDirty()
        assertTrue("仅关闭集合被改写时窗口应为空（不产生捕获载荷）", snapshot == null)
        assertTrue(
            "关闭集合被 Kotlin 写入必须被检测",
            ReverseChannelPolicy.closedWriteRecordsSnapshot().any { it.contains("pills") }
        )
    }

    @Test
    fun `reopening the domain restores transport`() {
        ReverseChannelPolicy.overrideClosedUnitsForTest(listOf(closedField("spiritStones")))
        ReverseChannelPolicy.reopenDomain(ReverseChannelPolicy.Domain.INVENTORY)
        val store = FakeGameStateStore()
        val sent = mutableListOf<String>()
        val sync = StateSyncService(store) { sent += it.decodeToString(); true }

        store.update { gameData = gameData.copy(spiritStones = 123) }
        assertTrue(sync.applyDirtyToNative())

        val changed = json.parseToJsonElement(sent.last()).jsonObject["changed"]!!.jsonObject
        assertTrue(
            "回滚该域后字段必须恢复传输",
            changed["gameData"]?.jsonObject?.containsKey("spiritStones") == true
        )
    }

    @Test
    fun `envelope carries nothing once every changed unit is closed`() {
        ReverseChannelPolicy.overrideClosedUnitsForTest(
            listOf(
                closedField("spiritStones"),
                ReverseChannelPolicy.ClosedUnit(
                    domain = ReverseChannelPolicy.Domain.INVENTORY,
                    kind = ReverseChannelPolicy.Kind.COLLECTION,
                    name = "pills",
                ),
                ReverseChannelPolicy.ClosedUnit(
                    domain = ReverseChannelPolicy.Domain.BATTLE,
                    kind = ReverseChannelPolicy.Kind.TOP_LEVEL_SECTION,
                    name = ReverseChannelPolicy.SECTION_LOCKED_BEAST_IDS,
                ),
            )
        )
        val store = FakeGameStateStore()
        val sent = mutableListOf<String>()
        val sync = StateSyncService(store) { sent += it.decodeToString(); true }

        // 预热窗口：建立反向锚点与各段变化检测缓存（生产由引擎启动全量导入完成）
        store.update { gameData = gameData.copy(gameYear = 1) }
        assertTrue(sync.applyDirtyToNative())
        sent.clear()

        store.update {
            gameData = gameData.copy(spiritStones = 5, lockedBeastIds = setOf("b1"))
            pills.add(Pill(id = "p1", name = "丹", quantity = 1))
        }
        assertTrue(sync.applyDirtyToNative())

        val changed = json.parseToJsonElement(sent.last()).jsonObject["changed"]!!.jsonObject
        val removed = json.parseToJsonElement(sent.last()).jsonObject["removed"]!!.jsonObject
        assertTrue("关闭单元全部摘除后信封应为空：changed=$changed", changed.isEmpty())
        assertTrue("关闭单元全部摘除后信封应为空：removed=$removed", removed.isEmpty())
    }

    @Test
    fun `closed field detection tolerates cpp integer double formatting`() {
        // C++ 导出对整数值 double 输出整数形式（12000）；kotlinx 输出 12000.0——
        // 检测必须容忍该格式差异，不得产生误报
        ReverseChannelPolicy.overrideClosedUnitsForTest(listOf(closedField("sectCultivation")))
        val store = FakeGameStateStore()
        val sent = mutableListOf<String>()
        val sync = StateSyncService(store) { sent += it.decodeToString(); true }
        sync.applySnapshot(
            NativeGameState(gameData = GameData().copy(sectCultivation = 12000.0))
        )

        // 前向增量镜像携带 C++ 侧当前值（整数形式）→ 基线随之刷新
        sync.applyDirty("""{"version":1,"changed":{"gameData.sectCultivation":12000},"removed":{}}""")
        // 在册保留字段的写入制造非空窗口（信封构建 → 触发关闭字段检测）
        store.update { gameData = gameData.copy(jadeSymbols = 7) }
        assertTrue(sync.applyDirtyToNative())
        assertTrue(
            "同一数值的两种输出形式不得被判为越权写入",
            ReverseChannelPolicy.closedWriteCountSnapshot() == 0L
        )
    }
}
