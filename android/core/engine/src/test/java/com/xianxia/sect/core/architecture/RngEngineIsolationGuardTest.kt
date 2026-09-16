package com.xianxia.sect.core.architecture

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 守卫测试：**引擎随机上下文不得经进程级 `object` 全局注入**。
 *
 * ## 存在理由（事故背景）
 *
 * `MissionSystem` 原为 `object` + `@Volatile private var rngManager` + `initialize(manager)`，
 * 由 `CultivationEventProcessor.init` 注入。对象是**进程级单例**，而双引擎同进程场景
 *（跨语言对拍夹具、多存档预览）下**后构造者覆写前者**——实测
 * `DiffAuthoritativeTickTest` 第 9 旬 `availableMissions` 1 vs 4：Kotlin 臂的月变
 * 消费了 C++ 的 MISSION 分区，C++ 自己再消费一次。
 *
 * 同类形态还有 `AISectDiscipleManager.rngManager`（阶段 1② 收敛后保留为
 * "接入真源"的解析器，其**状态归宿主侧保管**，非自持流——见白名单豁免理由）。
 *
 * ## 判据（枚举驱动 + 白名单只缩不增）
 * 扫描 `core:engine` 主源，命中"`object`/单例内出现 `GameRngManager` 可变字段"
 * 的文件必须登记在 [intentionallyExcluded] 并写明理由；新增即红。
 *
 * 处置指引：把管理器改为**形参必传**（参照 `MissionSystem.processMonthlyRefresh`），
 * 隔离性由构造期依赖保证，而非依赖"初始化顺序恰好正确"。
 */
class RngEngineIsolationGuardTest {

    /** Gradle 测试工作目录 = android/core/engine */
    private val mainSourceDir: File = File("src" + File.separator + "main" + File.separator + "java")

    /**
     * 显式豁免（**只缩不增**）——每条必须写明为何不是"全局串流"。
     *
     * key = 相对 `src/main/java` 的路径（`/` 分隔），value = 豁免理由。
     *
     * **W4-C C-③ 收敛后（handover §2.64.1）**：`EnemyGenerator` /
     * `AISectAttackManager` / `AISectTeamComposer` 三处顶层可变 `var xxxRngManager`
     * 已改形参必传，白名单条目随之删除；现值 = **恰 1 条**（`AISectDiscipleManager`
     * ——AI 随机源解析器，非自持流）。条目数由本文件的白名单计数断言机器锁死
     *（新增豁免 = 该断言红，与 detekt-baseline-count.guard 同纪律）。
     */
    private val intentionallyExcluded: Map<String, String> = mapOf(
        "com/xianxia/sect/core/engine/domain/diplomacy/AISectDiscipleManager.kt" to
            "AI 随机源**解析器**（非自持流）：rngManager 只用于按模式解析分区 9/6，" +
            "状态归宿主侧（C++ aiRng_ / 本地 PCG 分区）保管；" +
            "且 initialize 由 GameEngine 构造注入，生产单实例——同进程多引擎下" +
            "该解析器仅服务 AI 域，消费点已随阶段 1② 归一（见 DiffAiRngSeedingTest 锁守）"
    )

    /** 白名单条目登记值（**只缩不增**；新增豁免必须同步改本值并在 PR 说明——机器锁死防静默扩权） */
    private val expectedExclusionCount = 1

    /** `object`/单例内声明 `GameRngManager` 可变字段（= 可被外部覆写的全局随机上下文） */
    private val mutableRngFieldPattern =
        Regex("""(private|internal|public)?\s*@?\w*\s*(private|internal)?\s*(var)\s+\w+\s*:\s*GameRngManager\??""")

    @Test
    fun `引擎随机上下文不得经 object 全局注入（白名单只缩不增）`() {
        assertTrue("主源目录不可达（Gradle 工作目录应为 android/core/engine）：${mainSourceDir.absolutePath}",
            mainSourceDir.isDirectory)

        val offenders = mutableListOf<String>()
        mainSourceDir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .forEach { file ->
                val relative = file.relativeTo(mainSourceDir).path.replace(File.separatorChar, '/')
                if (relative in intentionallyExcluded) return@forEach
                val stripped = stripComments(file.readLines())
                if (stripped.any { mutableRngFieldPattern.containsMatchIn(it) }) {
                    offenders += relative
                }
            }

        assertTrue(
            "发现 object 级可变 GameRngManager 字段（随机上下文经全局注入 " +
                "⇒ 双引擎同进程时后构造者覆写前者，一侧的结算消费另一侧的分区）：\n" +
                offenders.joinToString("\n") { "    $it" } +
                "\n处置指引：" +
                "\n  1) 首选——改为**形参必传**（参照 MissionSystem.processMonthlyRefresh：" +
                "调用方各自透传自己持有的 GameRngManager，隔离靠构造期依赖）；" +
                "\n  2) 若确为'接入真源的解析器'（状态归宿主侧保管）→ 加入 intentionallyExcluded " +
                "并写明理由（只缩不增，参照 CLAUDE.md 13.2）",
            offenders.isEmpty()
        )
    }

    /**
     * 白名单条目计数断言（§12 债表项落地）：此前"只缩不增"仅靠注释纪律，
     * 手工加一条豁免不会被任何机器检查拦下。本断言锁死条目数——
     * 新增/恢复豁免 = 本用例红，必须同步修改 [expectedExclusionCount] 并在
     * PR 说明中给出豁免理由（与 detekt-baseline-count.guard 同纪律）。
     */
    @Test
    fun `白名单条目数必须等于登记值`() {
        assertTrue(
            "intentionallyExcluded 条目数 ${intentionallyExcluded.size} ≠ 登记值 " +
                "$expectedExclusionCount——新增豁免须同步改登记值并说明理由（只缩不增）",
            intentionallyExcluded.size == expectedExclusionCount
        )
    }

    /**
     * 剔除注释（行注释 + 块注释含 KDoc），保留行结构。
     *
     * 结构：逐行外循环 + 逐字符单层 while（**不用 continue / break**——把
     * "块注释内跳过""块注释起始""行注释丢尾"三条路径都收敛到同一处 `i` 推进，
     * 既降嵌套深度也避免多跳转语句）。
     */
    private fun stripComments(lines: List<String>): List<String> {
        val out = ArrayList<String>(lines.size)
        var inBlock = false
        for (raw in lines) {
            val sb = StringBuilder()
            var i = 0
            while (i < raw.length) {
                val c = raw[i]
                val next = raw.getOrNull(i + 1)
                when {
                    inBlock && c == '*' && next == '/' -> { inBlock = false; i += 2 }
                    inBlock -> i++
                    c == '/' && next == '/' -> i = raw.length // 行注释：余下丢尾
                    c == '/' && next == '*' -> { inBlock = true; i += 2 }
                    else -> { sb.append(c); i++ }
                }
            }
            out.add(sb.toString())
        }
        return out
    }
}
