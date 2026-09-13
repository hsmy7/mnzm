package com.xianxia.sect.core.architecture

import com.xianxia.sect.core.util.RngPartition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 守卫测试：随机源治理（ADR rng-determinism-remediation R1/R3/R4/R5）。
 *
 * ## 存在理由
 * 改造前项目有**四类未受治理的随机流**（`Random.Default` / `GameRandom` /
 * 对象自持 RNG / 默认值陷阱），而旧 CI 红线是 import 级 grep
 *（`grep "import kotlin.random.Random"`）——`templates.random()` 是 stdlib 扩展、
 * `GameRandom` 是自建 object，**两者都不带该 import，永远匹配不到**；且该 grep
 * 断言后来事实上从 CI 消失。⇒ 本守卫按"四类入口逐处统计"的口径重写，
 * 事故背景与对标见 [docs/adr/rng-determinism-remediation.md]。
 *
 * ## 判据（R1 / R5）
 * 任何**影响游戏状态**的随机数必须取自
 * [com.xianxia.sect.core.util.GameRngManager.getRng]；严格禁止：
 * - ② `kotlin.random.Random.Default`（`\.random\(\)` / `Random.Default` / `Math.random`）
 * - ③ `GameRandom`（自建 object——种子 = 挂钟时间、`setSeed()` 生产零调用）
 * - ④ 对象/单例自持 RNG（`fromSeed(System.*)` / `ThreadLocalRandom` / `private val x: Random =`）
 * - ⑤ 默认值陷阱（形参 `random: kotlin.random.Random = ...`）——**ADR §5 认定的漏洞真正入口**
 *
 * ## 计数只缩不增
 * 每条字面量既有实例登记在 [docs/rng-source-inventory.md]（阶段 0 产物 = 工作清单）。
 * 本守卫断言**逐模块逐类计数不得超过登记值**：新增即红并列出全部命中行。
 * 阶段 1/2/3 分批清偿后登记值逐批下调；**清零后锁死为 0**（改登记值 = 改本文件，
 * 需在 PR 说明理由——与 `detekt-baseline-count.guard` 同纪律，CLAUDE.md 13.2）。
 *
 * ## 扫描口径
 * **只扫主源**（`src/main`）：测试里裸用 `Random` 是常规做法（ADR §11 盲区 9）。
 * 覆盖六模块：core:domain / core:engine / core:data / core:ui / feature:game / app。
 */
class RngSourceGuardTest {

    /** 模块主源根目录（Gradle 测试工作目录 = android/core/engine，故用相对路径） */
    private val moduleMainDirs: List<Pair<String, File>> = listOf(
        "core/domain" to File(mainSourcePath("core", "domain")),
        "core/engine" to File(mainSourcePath("core", "engine")),
        "core/data" to File(mainSourcePath("core", "data")),
        "core/ui" to File(mainSourcePath("core", "ui")),
        "feature/game" to File(mainSourcePath("feature", "game")),
        "app" to File(mainSourcePath("app"))
    )

    /**
     * 逐模块逐类登记上限——事实来源 = `docs/rng-source-inventory.md` §2
     *（阶段 0 实跑建立，**注释已剔除**：KDoc/注释里对被禁字面量的引用不计入债务；
     * 阶段 2 表现类迁移后同步下调）。
     * 清偿后**必须同步下调**；新条目禁止加入。
     *
     * ### 阶段 2 收口后的下调（2026-09-14）
     * | 模块 | 类别 | 阶段 0 | 现值 | 处置 |
     * |---|---|---|---|---|
     * | core/domain | ② BARE_DRAW | 7 | **5** | `SectResponseTexts.getAccept/RejectResponse` |
     * |  |  |  |  | 内部 `responses.random()` 改为**形参必传** `random.nextInt(size)` |
     * | feature/game | ② BARE_DRAW | 2 | **1** | `LoadingTips.randomTip()` 改走 `PresentationRandom.pick` |
     * | feature/game | ④ SELF_HELD_RNG | 1 | **0** | `CloudLayerAnimator` 的默认值摘除 |
     * |  |  |  |  | （`NativeSurfaceView` 传派生固定种子） |
     */
    private val registeredLimits: Map<String, Map<RandomSourceCategory, Int>> = mapOf(
        "core/domain" to mapOf(
            RandomSourceCategory.BARE_DRAW to 5,
            RandomSourceCategory.GAME_RANDOM to 0,
            RandomSourceCategory.SELF_HELD_RNG to 0,
            RandomSourceCategory.DEFAULT_PARAM_TRAP to 19
        ),
        "core/engine" to mapOf(
            RandomSourceCategory.BARE_DRAW to 14,
            RandomSourceCategory.GAME_RANDOM to 0,
            RandomSourceCategory.SELF_HELD_RNG to 2,
            RandomSourceCategory.DEFAULT_PARAM_TRAP to 7
        ),
        "core/data" to mapOf(
            RandomSourceCategory.BARE_DRAW to 1,
            RandomSourceCategory.GAME_RANDOM to 0,
            RandomSourceCategory.SELF_HELD_RNG to 0,
            RandomSourceCategory.DEFAULT_PARAM_TRAP to 0
        ),
        "core/ui" to mapOf(
            RandomSourceCategory.BARE_DRAW to 0,
            RandomSourceCategory.GAME_RANDOM to 0,
            RandomSourceCategory.SELF_HELD_RNG to 0,
            RandomSourceCategory.DEFAULT_PARAM_TRAP to 0
        ),
        "feature/game" to mapOf(
            RandomSourceCategory.BARE_DRAW to 1,
            RandomSourceCategory.GAME_RANDOM to 0,
            RandomSourceCategory.SELF_HELD_RNG to 0,
            RandomSourceCategory.DEFAULT_PARAM_TRAP to 1
        ),
        "app" to mapOf(
            RandomSourceCategory.BARE_DRAW to 0,
            RandomSourceCategory.GAME_RANDOM to 0,
            RandomSourceCategory.SELF_HELD_RNG to 0,
            RandomSourceCategory.DEFAULT_PARAM_TRAP to 0
        )
    )

    /**
     * `RngPartition` 分区登记表（R4 清单式守卫的锚点）。
     *
     * 分区 id 是**存档 `rngStates` 的持久化键，不得改动**（见 [RngPartition] KDoc）；
     * 新增分区 = 必须同时在 `docs/rng-source-inventory.md` 登记消费点。
     */
    private val registeredPartitionIds: Set<Int> = setOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9)

    /** 参与 `rngStates` 序列化的分区 id（= id 全集 − 通道型分区） */
    private val snapshotPartitionIds: Set<Int> = setOf(0, 1, 2, 3, 4, 5, 6, 7, 8)

    /** 随机源类别（与 `docs/rng-source-inventory.md` §1 的五类入口一一对应） */
    private enum class RandomSourceCategory(val label: String, val pattern: Regex) {
        /** ② `kotlin.random.Random.Default` 等价面 */
        BARE_DRAW(
            "② .random()/Random.Default/Math.random",
            Regex("""\.random\(\)|Random\.Default|Math\.random""")
        ),

        /** ③ 自建 `object GameRandom` */
        GAME_RANDOM("③ GameRandom", Regex("""GameRandom""")),

        /** ④ 对象/单例自持 RNG */
        SELF_HELD_RNG(
            "④ 自持 RNG（挂钟种子/ThreadLocal/Random 字段）",
            // 标识符字符类必须含 0-9 与全部大小写：初版写 `[A-Za-z_]+` 会**漏报**
            // 含数字或连续大写的字段名（如 `private val random: Random`、
            // `private var rngManager` 之后无匹配），属守卫自身漏洞（实测修复）
            Regex("""fromSeed\(System\.|ThreadLocalRandom|private (val|var) [A-Za-z0-9_]+: *Random *=""")
        ),

        /** ⑤ 默认值陷阱（形参默认回落 `Random.Default`） */
        DEFAULT_PARAM_TRAP(
            "⑤ 默认值陷阱（random 形参默认值）",
            Regex("""random: *kotlin\.random\.Random *=|random: *Random *=|rng: *kotlin\.random\.Random *=""")
        )
    }

    @Test
    fun `主源随机源逐类计数不超过登记上限`() {
        val modules = resolveModules()
        val violations = mutableListOf<String>()

        for ((moduleName, lines) in modules) {
            val limits = registeredLimits.getValue(moduleName)
            for (category in RandomSourceCategory.entries) {
                val hits = hitsOf(lines, category)
                val limit = limits.getValue(category)
                if (hits.size > limit) {
                    violations += "[$moduleName] ${category.label} 命中 ${hits.size} 处 > 登记 $limit 处：\n" +
                        hits.joinToString("\n") { "    $it" }
                }
            }
        }

        assertTrue(
            "发现未登记的新随机源（R1/R5 违规：影响状态的随机必须取自 " +
                "GameRngManager.getRng(RngPartition.*)）：\n" + violations.joinToString("\n") +
                "\n处置指引：" +
                "\n  1) 若是决策类随机 → 改走 GameRngManager.getRng(对应分区)" +
                "\n  2) 若确属表现类随机（不影响任何数值/不写入 GameData 或实体表）→ 走 PresentationRandom" +
                "\n  3) 若为新增的合法字面量 → 在 docs/rng-source-inventory.md 登记理由与偿还触发条件" +
                "，并同步下调本测试的对应上限（只缩不增）",
            violations.isEmpty()
        )
    }

    @Test
    fun `R5 不得自建随机源（自持 RNG 逐模块归零或持平）`() {
        val modules = resolveModules()
        val violations = modules.flatMap { (moduleName, lines) ->
            val limit = registeredLimits.getValue(moduleName).getValue(RandomSourceCategory.SELF_HELD_RNG)
            hitsOf(lines, RandomSourceCategory.SELF_HELD_RNG).map { "$moduleName | $it（登记上限 $limit）" }
        }
        assertTrue(
            "禁止自建随机源（ADR R5）：不得新增 object/单例自持 RNG——" +
                "GameRandom 即反面教材（种子 = 挂钟时间 + setSeed() 生产零调用 + @ThreadLocal 每线程独立流，" +
                "KDoc 自称支持确定性存档但该承诺从未实现）。\n" + violations.joinToString("\n"),
            violations.size <= registeredLimits.values.sumOf {
                it.getValue(RandomSourceCategory.SELF_HELD_RNG)
            }
        )
    }

    @Test
    fun `R2 分区快照集合与 inSnapshot 标记一致`() {
        // R2（每个分区的状态必须可导出/恢复）+ 通道型分区的例外必须显式声明：
        // snapshotPartitionIds 是"参与 rngStates 的分区"的登记集，
        // 与枚举的 inSnapshot 标记必须双向一致——任一侧漏改即红
        val actual = RngPartition.entries.filter { it.inSnapshot }.map { it.id }.toSet()
        assertEquals(
            "参与 rngStates 的分区集合与登记不一致——" +
                "新增分区须同步 docs/rng-source-inventory.md + 本测试 snapshotPartitionIds，" +
                "通道型分区（只提供访问句柄、状态归宿主侧保管）须显式 inSnapshot=false",
            snapshotPartitionIds, actual
        )
    }

    @Test
    fun `R4 RngPartition 枚举新增值必须登记`() {
        val unregistered = RngPartition.entries.filter { it.id !in registeredPartitionIds }
        assertTrue(
            "新增 RngPartition 未登记：${unregistered.map { "${it.name}(${it.id})" }}——" +
                "分区 id 是存档 rngStates 的持久化键（不得改动已有 id），" +
                "新增分区必须：① 在 docs/rng-source-inventory.md 登记消费点；" +
                "② 在本测试 registeredPartitionIds 追加 id；" +
                "③ C++ gamecore/rng/rng_manager.h 同步枚举 + initSystemSeed 播种",
            unregistered.isEmpty()
        )
    }

    @Test
    fun `R4 分区 id 与名字一一对应无漂移`() {
        // C++ rng_manager.h 的枚举值以 id 为持久化键；名字侧漂移会让「按名字登记」
        // 与「按 id 落盘」两套口径分叉——此处锁死 id↔name 双射（增删都触发）
        val byId = RngPartition.entries.groupBy { it.id }.filterValues { it.size > 1 }
        assertTrue(
            "RngPartition 存在重复 id：${byId.map { (id, list) -> "$id=${list.map { it.name }}" }}——" +
                "id 是存档 rngStates 的键，重复会让读取落到错误分区",
            byId.isEmpty()
        )
        val names = RngPartition.entries.map { it.name }
        assertTrue(
            "RngPartition 存在重复名：${names.groupingBy { it }.eachCount().filterValues { it > 1 }}",
            names.size == names.toSet().size
        )
        val expectedNames = listOf(
            "BATTLE", "BREAKTHROUGH", "EXPLORATION", "SYSTEM", "ENEMY_GEN",
            "MAIL", "AI_SECT", "SECRET_REALM", "MISSION", "AI_SECT_MIRROR"
        )
        assertTrue(
            "RngPartition 名字/顺序偏移：实测 ${names.take(10)}——" +
                "名字是存档语义的一部分（rngStates 日志/诊断面），增删须同步 inventory 文档与本断言",
            names.size >= expectedNames.size && names.take(expectedNames.size) == expectedNames
        )
    }

    @Test
    fun `R4 AI 分区通道 id 9 保留语义有守护`() {
        // 分区 9（AI_SECT_MIRROR）是 Kotlin 侧**取用 C++ GameCore::aiRng_ 的通道句柄**
        // （该流原不在 rngStates 协议面内）；inSnapshot=false ⇒ 不参与
        // exportStates/restoreStates，其状态真源 = C++ 侧随 9 号键自行落盘。
        // 若被误设为 inSnapshot=true，Kotlin 会把本地 AI_SECT 流态写到同一键上，
        // 与 C++ 写回的 aiRng_ 流态互相覆盖（跨语言对拍恒红——阶段 1② 实测）。
        val mirror = RngPartition.entries.firstOrNull { it.name == "AI_SECT_MIRROR" }
        assertTrue(
            "分区 9（AI_SECT_MIRROR）缺失或语义漂移——C++ aiRng_ 的访问通道断链，" +
                "AUTHORITATIVE 下 AI 宗门演化将不可复现（读档后与存档前分叉）",
            mirror != null && mirror.id == 9 && !mirror.inSnapshot
        )
    }

    // ==================== 扫描基建 ====================

    /** 模块主源路径（Gradle 工作目录 = android/core/engine） */
    private fun mainSourcePath(vararg segments: String): String = (
        listOf("..", "..") + segments + listOf("src", "main")
        ).joinToString(File.separator)

    /** 扫描根（Gradle 工作目录 = android/core/engine；用于产出可读的相对路径） */
    private val repoRoot: File = File(".." + File.separator + "..")

    /** 解析各模块主源为「模块名 → 命中行清单」（**注释已剔除**） */
    private fun resolveModules(): Map<String, List<Match>> = moduleMainDirs.associate { (name, dir) ->
        if (!dir.isDirectory) {
            throw AssertionError(
                "模块主源目录不可达（Gradle 工作目录应为 android/core/engine）：${dir.absolutePath}"
            )
        }
        name to dir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file ->
                val relative = file.relativeTo(repoRoot).path
                stripComments(file.readLines()).mapIndexedNotNull { index, code ->
                    code.takeIf { it.isNotBlank() }?.let { Match(relative, index + 1, it) }
                }
            }
            .toList()
    }

    /**
     * 剔除注释（行注释 + 块注释，**含 KDoc**），保留行号。
     *
     * 剔除是必要的：KDoc/注释里大量**引用**被禁字面量（例如
     * "原默认实参回落 `Random.Default` 已消除"），若不剔除，登记值会被注释噪音
     * 撑大、真实债务被淹没，且"改注释即改守卫"会让守卫形同虚设。
     * 字符串字面量内的 `//`（如 URL）按原样保留——本守卫的目标字面量不出现在
     * 字符串里，故不做完整词法分析（够用且无过度工程）。
     */
    private fun stripComments(lines: List<String>): List<String> {
        val out = ArrayList<String>(lines.size)
        var inBlockComment = false
        for (raw in lines) {
            val sb = StringBuilder()
            var i = 0
            while (i < raw.length) {
                val c = raw[i]
                val next = raw.getOrNull(i + 1)
                when {
                    inBlockComment && c == '*' && next == '/' -> { inBlockComment = false; i += 2 }
                    inBlockComment -> i++
                    c == '/' && next == '/' -> i = raw.length // 行注释：余下丢尾
                    c == '/' && next == '*' -> { inBlockComment = true; i += 2 }
                    else -> { sb.append(c); i++ }
                }
            }
            out.add(sb.toString().trim())
        }
        return out
    }

    private fun hitsOf(lines: List<Match>, category: RandomSourceCategory): List<String> =
        lines.filter { category.pattern.containsMatchIn(it.text) }
            .map { "${it.path}:${it.line}: ${it.text}" }

    /** 单行命中记录 */
    private data class Match(val path: String, val line: Int, val text: String)
}
