package com.xianxia.sect.core.engine

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 速度维度删除防复发守卫（§5 编码规范 9.5 守卫三要素；符号面静态扫描）。
 *
 * ① 锚点：扫描「游戏时间倍速」曾落位的白名单文件集（Kotlin 时钟/看门狗/
 *    JNI 桥/UI 入口 + C++ PhaseClock/SettlementEngine/看门狗头），断言
 *    速度符号面归零。
 * ② 故意排除项（intentionallyExcluded，同名不同义，扫描会误报）：
 *    - 角色属性 `speed`（身法）：`FormulaService`/`HeavenlyTrialBuildOps` 的
 *      `var speed` 聚合、`Disciple`/`disciple_stats`/`battle`/`level_generator`
 *      等战力与身法面——与本守卫守护的「时间倍速」无关，不扫描；
 *    - `GameDatabaseMigrationsV11ToV20`/`RoomMigrationTest`：历史迁移 SQL 中的
 *      `gameSpeed` 是 v20 已删列的历史事实记录，非现行机制，不扫描。
 * ③ 错误消息带操作指引（见 [GUARD_MESSAGE]）。
 *
 * 墙钟成本：纯文件读取 + 字符串包含判定，< 50ms，确定性单次迭代。
 */
class SpeedDimensionRemovedGuardTest {

    private val engineMain = "src/main/java/com/xianxia/sect/core/engine"
    private val coreMain = "src/main/java/com/xianxia/sect/core"
    // 本测试模块 = android/core/engine；feature 与 cpp 根在 android/<X>（两级上跳）
    private val featureMain = "../../feature/game/src/main/java/com/xianxia/sect"
    private val cppRoot = "../../app/src/main/cpp"

    /** 白名单文件 → 该文件禁现的速度符号面（子串匹配，注释一并禁现） */
    private val scannedFiles: List<Pair<String, List<String>>> = listOf(
        "$engineMain/system/GameTimeClock.kt" to listOf(
            "setSpeed", "speedFlow", "onSpeedChanged", "var speed",
            "MS_PER_PHASE_1X", "maxPhasesPerTick("
        ),
        "$engineMain/monitor/GameTimeProgressMonitor.kt" to listOf("speed"),
        "$coreMain/nativebridge/GameCoreBridge.kt" to listOf("LoopSetSpeed", "setSpeed"),
        "$featureMain/ui/game/SaveLoadViewModel.kt" to listOf(
            "timeSpeed", "setTimeSpeed", "timeScale", "speedFlow"
        ),
        "$featureMain/ui/game/tabs/SettingsTab.kt" to listOf(
            "timeSpeed", "SpeedToggle", "倍速", "时间流速"
        ),
        // C++ 真相源三头（相对模块目录回溯仓库 cpp 根）
        "$cppRoot/gamecore/include/gamecore/system/engine_loop.h" to listOf(
            "setSpeed", "speed_", "int speed", "kMsPerPhase1x"
        ),
        "$cppRoot/gamecore/include/gamecore/system/settlement.h" to listOf(
            "setSpeed", "speed_", "int speed", "kMsPerPhase1x", "maxPhasesPerTick("
        ),
        "$cppRoot/gamecore/include/gamecore/system/watchdog.h" to listOf(
            "setSpeed", "speed_", "int speed"
        )
    )

    @Test
    fun `speed dimension symbol surface is zero across whitelisted files`() {
        val violations = buildString {
            for ((path, tokens) in scannedFiles) {
                val file = File(path)
                assertTrue("守卫定位失败：$path 不可读（相对路径漂移？请修本守卫定位）", file.isFile)
                val content = file.readText()
                for (token in tokens) {
                    if (token in content) {
                        appendLine("  $path 含被禁符号「$token」")
                    }
                }
            }
        }
        assertTrue("${GUARD_MESSAGE}\n$violations", violations.isEmpty())
    }

    @Test
    fun `single speed constants are pinned`() {
        // 双端常量锚（与 GameTimeClockPhaseCapParityTest 互为冗余备份）：
        // 改值须同步 C++ settlement.h kMsPerPhase / kMaxPhasesPerTick
        val clock = File("$engineMain/system/GameTimeClock.kt").readText()
        assertTrue("GameTimeClock.MS_PER_PHASE 应为 2000ms（1 旬 = 2s 墙钟，删除前 1x 逐位一致）",
            "const val MS_PER_PHASE: Long = 2000L" in clock)
        assertTrue("GameTimeClock.MAX_PHASES_PER_TICK 应为 3（与速度解耦的常量）",
            "const val MAX_PHASES_PER_TICK: Int = 3" in clock)
    }

    companion object {
        private const val GUARD_MESSAGE =
            "「游戏倍速」维度已删除（见 docs/design/remove-2x-speed-implementation-plan.md）。" +
                "若确需重新引入时间倍率，须先扩 C++ PhaseClock 协议并双端对拍，" +
                "禁止在 UI/ViewModel 层加回 timescale 开关"
    }
}
