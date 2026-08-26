package com.xianxia.sect.core.nativebridge

import com.xianxia.sect.core.engine.system.TimeSystem
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.state.DiscipleTables
import com.xianxia.sect.core.state.EntityStore
import com.xianxia.sect.core.state.MutableGameState
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * DiffTimeTest — 时间推进跨语言差分对拍（批次 3 验收核心；C-15 已切换真实引擎基准）。
 *
 * 守护目标：C++ SettlementEngine/TimeSystem 的时间推进（年/月/旬进位、
 * 边界检测）与 Kotlin [TimeSystem].onPhaseTick 语义**逐位一致**。
 *
 * Kotlin 基准：真实 [TimeSystem] 实例驱动 [MutableGameState]
 * （C-15 计划 v2 阶段 1——原内联复刻已删除，杜绝"复刻漂移"盲区）。
 *
 * 流程：Kotlin 真实 TimeSystem 推进 N 旬 → 期望 (y,m,p)；C++ 经 JNI
 * advancePhases(N) → export 读 (y,m,p)；断言相等。
 *
 * 前置：桌面 JNI 已构建并注入 `-Dgamecore.jni.path`；未注入时跳过。
 */
class DiffTimeTest {

    private val json = Json { encodeDefaults = true }

    /** Kotlin 基准：真实 TimeSystem 推进 N 旬（不再内联复刻——C-15）。 */
    private fun kotlinAdvanceN(start: Triple<Int, Int, Int>, n: Int): Triple<Int, Int, Int> {
        val store = FakeGameStateStore()
        val mutableState = MutableGameState(
            gameData = GameData().apply {
                gameYear = start.first; gameMonth = start.second; gamePhase = start.third
            },
            discipleTables = DiscipleTables().also { it.writeAllowed = true },
            equipmentStacks = EntityStore(emptyList()),
            equipmentInstances = EntityStore(emptyList()),
            manualStacks = EntityStore(emptyList()),
            manualInstances = EntityStore(emptyList()),
            pills = EntityStore(emptyList()),
            materials = EntityStore(emptyList()),
            herbs = EntityStore(emptyList()),
            seeds = EntityStore(emptyList()),
            storageBags = EntityStore(emptyList()),
            battleLogs = emptyList(),
            isPaused = false,
            isLoading = false,
            isSaving = false
        )
        val timeSystem = TimeSystem(store)
        repeat(n) { timeSystem.onPhaseTick(mutableState, 1) }
        val gd = mutableState.gameData
        return Triple(gd.gameYear, gd.gameMonth, gd.gamePhase)
    }

    private fun cppAdvanceN(start: Triple<Int, Int, Int>, n: Int): Triple<Int, Int, Int> {
        val sample = NativeGameState(
            gameData = GameData().apply {
                gameYear = start.first; gameMonth = start.second; gamePhase = start.third
            }
        )
        val encoded = json.encodeToString(NativeGameState.serializer(), sample)
        assertTrue("C++ 导入失败", DiffRngBridge.nativeCoreImportState(encoded.encodeToByteArray()))
        DiffRngBridge.nativeCoreAdvancePhases(n)
        val decoded = json.decodeFromString(
            NativeGameState.serializer(), DiffRngBridge.nativeCoreExportState().decodeToString()
        )
        return Triple(decoded.gameData.gameYear, decoded.gameData.gameMonth, decoded.gameData.gamePhase)
    }

    private fun assertAdvance(start: Triple<Int, Int, Int>, n: Int) {
        val expected = kotlinAdvanceN(start, n)
        val actual = cppAdvanceN(start, n)
        assertEquals("从 $start 推进 $n 旬后时间不一致", expected, actual)
    }

    @Test
    fun `phase increments match Kotlin`() {
        assumeTrue(DiffRngBridge.isAvailable())
        DiffRngBridge.nativeCoreInit()
        for (phase in 0..2) assertAdvance(Triple(1, 1, phase), 1)
    }

    @Test
    fun `month rollover matches Kotlin`() {
        assumeTrue(DiffRngBridge.isAvailable())
        DiffRngBridge.nativeCoreInit()
        assertAdvance(Triple(1, 1, 2), 1)   // 下旬 → 下月
        assertAdvance(Triple(5, 6, 2), 1)
        assertAdvance(Triple(1, 1, 0), 3)   // 一个月
    }

    @Test
    fun `year rollover matches Kotlin`() {
        assumeTrue(DiffRngBridge.isAvailable())
        DiffRngBridge.nativeCoreInit()
        assertAdvance(Triple(1, 12, 2), 1)  // 12月下旬 → 2年1月
        assertAdvance(Triple(1, 1, 0), 36)  // 一整年
    }

    @Test
    fun `long advance matches Kotlin`() {
        assumeTrue(DiffRngBridge.isAvailable())
        DiffRngBridge.nativeCoreInit()
        // 跨越多年：起点 3年5月上旬 → 100 旬后
        assertAdvance(Triple(3, 5, 0), 100)
        // 大推进：1000 旬 ≈ 9.26 年
        assertAdvance(Triple(1, 1, 0), 1000)
    }

    @Test
    fun `advance leaves non-time state unchanged`() {
        assumeTrue(DiffRngBridge.isAvailable())
        DiffRngBridge.nativeCoreInit()

        // 批次 3 验收：空档（无弟子/生产）推进 1000 旬，非时间字段不得被误改
        val sample = NativeGameState(
            gameData = GameData().apply {
                sectName = "青云宗"
                spiritStones = 99999
                midGradeSpiritStones = 123
                jadeSymbols = 7
                jadeSymbolsToday = 2
                merchantRefreshCount = 3
                soundEnabled = true
                musicEnabled = false
            }
        )
        val encoded = json.encodeToString(NativeGameState.serializer(), sample)
        assertTrue("C++ 导入失败", DiffRngBridge.nativeCoreImportState(encoded.encodeToByteArray()))
        DiffRngBridge.nativeCoreAdvancePhases(1000)

        val decoded = json.decodeFromString(
            NativeGameState.serializer(), DiffRngBridge.nativeCoreExportState().decodeToString()
        )
        val d = decoded.gameData
        assertEquals("灵石不得被时间推进修改", 99999L, d.spiritStones)
        assertEquals("中品灵石不得被修改", 123L, d.midGradeSpiritStones)
        assertEquals("玉符不得被修改", 7, d.jadeSymbols)
        assertEquals("今日玉符不得被修改", 2, d.jadeSymbolsToday)
        assertEquals("商人刷新计数不得被修改", 3, d.merchantRefreshCount)
        assertEquals("音效开关不得被修改", true, d.soundEnabled)
        assertEquals("音乐开关不得被修改", false, d.musicEnabled)
        // 时间确实推进了（1000 旬 ≈ 9.26 年）
        assertTrue("时间应推进到 10 年后（年=${d.gameYear}）", d.gameYear >= 10)
    }
}
