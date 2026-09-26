package com.xianxia.sect.core.architecture

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 守卫测试：弟子构造路径单点（G08 验收⑦ · TASKBOOK-G08 §4 片 c340-3）。
 *
 * ## 存在理由
 * G08 把弟子构造从「逐字段随机」收敛为「模板实例化」：`DiscipleService.recruitDisciple`
 * 与三条直造臂（`RedeemCodeManager.generateDisciple` / `RedeemCodeRewardOps.buildRedeemDisciple`
 * / `MailAttachmentVariantsOps.distributeDiscipleAttachment`）已全删，具名角色的
 * **唯一**生产构造口是 `DiscipleService.instantiateTemplate(templateId)`
 * → `DiscipleTables.allocateAndInsert`（开局名册与 G09 寻访解锁共用此口）。
 *
 * 但该收敛**没有任何机器约束**：新增一条 `discipleTables.insert(...)` 旁路（例如
 * "活动直接送一名随机弟子"）能安静通过编译与既有全量测试，只在运行期表现为
 * 「无 `templateId` 的弟子混进名册 ⇒ 限持 1 判定失效、图鉴与立绘口径分叉、
 * 兑换码重新变成角色获取渠道」。⇒ 本守卫把收敛固化为 CI 红线（根 AGENTS.md §5 0.1 + 9.5）。
 *
 * ## 判据（AGENTS.md §9.5 守卫三要素）
 * - **① 枚举驱动**：扫六模块 `src/main`（`.kt` + `.java`，注释剔除）收集全部
 *   `allocateAndInsert(` 与 `<…DiscipleTables>.insert(` 调用点，按「文件 + 所属函数」归类，
 *   断言集合 ⊆ [registeredSites]；并逐口核对**语义前置**——回写口须紧邻 `remove(id)`、
 *   读档口须紧邻 `clear()`、入册口所在文件须真在查 `CharacterTemplateDb`。
 *   前置消失 = 该口从「整行回写」漂移成「新造实例」。
 *   另设反向用例 [登记白名单不得含已消失的僵尸条目]，防白名单腐化。
 * - **② 显式排除项**：见 [intentionallyExcluded]（物理入口定义本体、批量写回 API、
 *   Room DAO 落盘口、测试夹具、C++ 侧构造），每项写明为什么不纳入本守卫。
 * - **③ 失败消息带操作指引**：命中新口时直接告知必须走 `instantiateTemplate`、
 *   确需新通道时要在哪里登记什么。
 *
 * ## 同批配套（验收④⑤，本文件后三条用例）
 * 已删除的直造弟子入口不得复活、兑换码/邮件链不得再直取肖像池、
 * `portraitRes` 的**生成式**赋值落点必须限于「模板工厂空键臂 + AI 宗旁路」两域
 * （口径 9 明确保留 AI 旁路；模板弟子的角色键由 `portraitResOverride` 提供，不再随机）。
 */
class DiscipleCreationPathGuardTest {

    // ==================== 扫描根与路径常量 ====================

    /** 模块主源根目录（Gradle 测试工作目录 = android/core/engine，故用相对路径） */
    private val moduleMainDirs: List<Pair<String, File>> = listOf(
        "core/domain" to File(mainSourcePath("core", "domain")),
        "core/engine" to File(mainSourcePath("core", "engine")),
        "core/data" to File(mainSourcePath("core", "data")),
        "core/ui" to File(mainSourcePath("core", "ui")),
        "feature/game" to File(mainSourcePath("feature", "game")),
        "app" to File(mainSourcePath("app"))
    )

    private val engineDiscipleDir = "core/engine/src/main/java/com/xianxia/sect/core/engine/domain/disciple"
    private val engineRootDir = "core/engine/src/main/java/com/xianxia/sect/core/engine"
    private val appStateDir = "app/src/main/java/com/xianxia/sect/core/state"

    // ==================== 判据①：入册口白名单 ====================

    /** 一个弟子入册/写回调用点 */
    private data class CallSite(
        val module: String,
        val path: String,
        val line: Int,
        val function: String,
        val api: String,
    ) {
        override fun toString(): String = "$module | $path:$line | fn=$function | api=$api"
    }

    /** 一次全量扫描的产物：文件 → 注释剔除后的代码行，以及命中的调用点 */
    private class Corpus(val files: Map<String, List<String>>, val sites: List<CallSite>)

    /** 扫描结果（每个测试方法一个新实例 ⇒ lazy 恰好扫一遍） */
    private val corpus: Corpus by lazy { buildCorpus() }

    /** 登记口类别——决定该口必须满足的语义前置 */
    private enum class SiteKind(val label: String) {
        /** 原子分配新 ID 落表：真·入册 */
        REGISTRATION("模板实例化入册（生产唯一构造口）"),

        /** 读档/回滚重建：对象来自存档，不是构造 */
        LOAD("读档/回滚整表重建"),

        /** assemble → remove → insert 整行回写：改的是已在册弟子 */
        WRITE_BACK("已在册弟子的整行回写"),
    }

    /** 一个登记口：文件 + 所属函数 + 类别 + 为什么合法 */
    private data class RegisteredSite(
        val path: String,
        val function: String,
        val kind: SiteKind,
        val reason: String,
    )

    /**
     * 实测白名单（2026-09-25 G08 c340-3 逐处 grep + 逐处读码所得）。
     * 按「文件 + 所属函数」而非行号登记：行号会随无关改动漂移，函数名不会。
     */
    private val registeredSites: List<RegisteredSite> = listOf(
        RegisteredSite(
            "$engineDiscipleDir/DiscipleService.kt", "insertTemplateDisciple", SiteKind.REGISTRATION,
            "instantiateTemplate 的事务内落库：查 CharacterTemplateDb → 限持判定 → 工厂 → 入册"
        ),
        RegisteredSite(
            "$appStateDir/GameStateStoreImpl.kt", "applyLoadedCore", SiteKind.LOAD,
            "读档：Disciple 对象来自存档快照，先 clear() 再逐行 insert"
        ),
        RegisteredSite(
            "$appStateDir/GameStateStoreImpl.kt", "rollbackLoad", SiteKind.LOAD,
            "读档失败回滚：用 baseline 快照整表重建，不产生新弟子"
        ),
        RegisteredSite(
            "$engineDiscipleDir/DiscipleFacadeImpl.kt", "updateDisciple", SiteKind.WRITE_BACK,
            "ids.contains 前置 + remove/insert 重装行，改的是已在册弟子"
        ),
        RegisteredSite(
            "$engineDiscipleDir/DiscipleLifecycleManager.kt", "updateDisciple", SiteKind.WRITE_BACK,
            "同上：remove(id) 后 insert(同 id 对象)"
        ),
        RegisteredSite(
            "$engineRootDir/GameEngineCoordination.kt", "updateDisciple", SiteKind.WRITE_BACK,
            "引擎线程通用写回臂（id !in discipleTables.ids 直接 return）"
        ),
        RegisteredSite(
            "$engineRootDir/GameEngineDiscipleItemOps.kt", "applyLearnedManualSnapshot",
            SiteKind.WRITE_BACK, "功法学习成果回写组件表（重装行）"
        ),
        RegisteredSite(
            "$engineRootDir/GameEngineJadePurchaseOps.kt", "purchaseBreakthroughBonus",
            SiteKind.WRITE_BACK, "玉符突破加成回写 statusData"
        ),
        RegisteredSite(
            "$engineRootDir/GameEngineManualOps.kt", "forgetManual", SiteKind.WRITE_BACK,
            "功法回收入储物袋的回写臂"
        ),
        RegisteredSite(
            "$engineRootDir/GameEngineManualOps.kt", "replaceManualFallback", SiteKind.WRITE_BACK,
            "非镜像回退臂的功法替换回写"
        ),
    )

    /**
     * 判据②：故意不覆盖的面。
     *
     * 必须显式声明——否则「守卫没抓到」会被后来者误读成「不存在这种面」。
     */
    private val intentionallyExcluded: Map<String, String> = linkedMapOf(
        "core/domain/…/state/DiscipleTables.kt 的 fun allocateAndInsert / fun insert" to
            "物理入口定义本体 = 本守卫的锚点，不是消费方（声明行由 DECLARATION 规则剔除）",
        "DiscipleTables.replaceAll / addId / update / upsertMirrorRow" to
            "批量写回与镜像行写入：src/main 的 10 处 replaceAll 全是 assembleAll→map→replaceAll " +
                "的结算回写，不构造新 Disciple；镜像面纪律另有 MirrorReadOnlyGuardTest 看护",
        "Room DAO 的 insert（DiscipleDao / GameDataDao 等 20 处）" to
            "落盘通道而非内存名册入册口；接收者正则要求 `*DiscipleTables.insert(`，天然不命中",
        "src/test 全量" to
            "测试夹具直造弟子是常规做法（与 RngSourceGuardTest 的『只扫主源』口径一致）",
        "app/src/main/cpp/gamecore（C++ createDisciple / ai_sect_recruit.h）" to
            "C++ 侧不写 Kotlin 名册表；AI 旁路按口径 9 保留死面并已加 KDoc，" +
                "其 portraitRes 落点由本文件的『肖像池直取白名单』一条看护"
    )

    @Test
    fun `弟子入册与回写调用点集合不超过登记白名单`() {
        val violations = corpus.sites.mapNotNull { site ->
            val registered = registeredSites.firstOrNull {
                it.path == site.path && it.function == site.function
            }
            if (registered == null) site.toString() else verifySemantics(site, registered)
        }
        assertTrue(
            "发现未登记的弟子入册/写回通道（G08 构造路径单点契约）：\n" +
                violations.joinToString("\n") +
                "\n处置指引：" +
                "\n  1) 新角色入册**必须**经 DiscipleService.instantiateTemplate(templateId)" +
                "（查 CharacterTemplateDb + 限持 1 判定 + 模板钉身份），" +
                "不得新增 allocateAndInsert / discipleTables.insert 调用点直接落库；" +
                "\n  2) 确需新增一条通道（例如 G09 之外的新获取途径）时，" +
                "必须在本测试 registeredSites 追加条目并写明类别与理由，" +
                "并同步 docs/design/gacha-batches/report-G08.md 的本批登记项；" +
                "\n  3) 若只是既有函数改名/搬家导致归类变化，" +
                "同步 path 与 function 字段即可，但须确认 remove/clear 语义前置仍成立。" +
                "\n本守卫故意不覆盖的面（新增通道若落在这些面上，请一并说明）：\n" +
                intentionallyExcluded.entries.joinToString("\n") { "  · ${it.key} —— ${it.value}" },
            violations.isEmpty()
        )
    }

    @Test
    fun `登记白名单不得含已消失的僵尸条目`() {
        val stale = registeredSites.filterNot { registered ->
            corpus.sites.any { it.path == registered.path && it.function == registered.function }
        }
        assertTrue(
            "以下登记口在 src/main 已不存在：\n" +
                stale.joinToString("\n") { "${it.path} :: ${it.function}（${it.kind.label}）" } +
                "\n处置：白名单必须只反映现实——删除该条目（或修正 path/function 使其重新对齐），" +
                "否则守卫会对真正的旁路逐渐失去判别力。",
            stale.isEmpty()
        )
    }

    /** 语义前置核对：回写口须紧邻 remove、读档口须紧邻 clear、入册口须真在查模板表 */
    private fun verifySemantics(site: CallSite, registered: RegisteredSite): String? {
        val failure = when (registered.kind) {
            SiteKind.WRITE_BACK -> requirePattern(site, REMOVE_BEFORE, WRITE_BACK_WINDOW)
            SiteKind.LOAD -> requirePattern(site, CLEAR_BEFORE, LOAD_WINDOW)
            SiteKind.REGISTRATION -> requirePattern(site, TEMPLATE_LOOKUP, null)
        }
        return failure?.let { "$site —— 语义前置缺失：$it（登记理由：${registered.reason}）" }
    }

    // ==================== 同批配套：验收④与验收⑤ ====================

    /** 已删除的直造弟子入口符号（验收④字面）：主源任何命中都视为复活 */
    private val retiredConstructorSymbols: Map<String, Regex> = linkedMapOf(
        "recruitDisciple（随机招募臂）" to Regex("""recruitDisciple"""),
        "generateDisciple(（兑换码随机弟子工厂）" to Regex("""generateDisciple\s*\("""),
        "buildRedeemDisciple（兑换码直造弟子）" to Regex("""buildRedeemDisciple"""),
        "distributeDiscipleAttachment（邮件弟子附件）" to Regex("""distributeDiscipleAttachment"""),
        "DiscipleRewardConfig（弟子奖励配置模型）" to Regex("""DiscipleRewardConfig"""),
        "RedeemRewardType.DISCIPLE / STARTER_PACK（已退役枚举值）" to
            Regex("""RewardType\.(DISCIPLE|STARTER_PACK)""")
    )

    @Test
    fun `已退役的直造弟子入口不得在任意模块主源复活`() {
        val violations = mutableListOf<String>()
        forEachCodeLine { path, line, text ->
            for ((symbol, pattern) in retiredConstructorSymbols) {
                if (pattern.containsMatchIn(text)) violations += "$symbol\n    $path:$line: $text"
            }
        }
        assertTrue(
            "G08 已物理删除这些『绕过模板的弟子构造』入口（验收④），主源再现即回归：\n" +
                violations.joinToString("\n") +
                "\n处置：角色类奖励/发放一律改发碎片（GachaFacade.grantFragments(templateId, count)），" +
                "需要落册时走 DiscipleService.instantiateTemplate，禁止重新引入随机弟子构造。",
            violations.isEmpty()
        )
    }

    @Test
    fun `兑换码与邮件链不得再直取肖像池`() {
        val violations = mutableListOf<String>()
        var scannedFiles = 0
        for ((path, lines) in corpus.files) {
            if (!grantChannelFileNames.containsMatchIn(path)) continue
            scannedFiles += 1
            lines.forEachIndexed { index, text ->
                if (PORTRAIT_DRAW.containsMatchIn(text)) violations += "$path:${index + 1}: $text"
            }
        }
        assertTrue(
            "先决条件失效：兑换码/邮件链文件未被全部扫到（应为 $GRANT_CHANNEL_FILE_COUNT 个，" +
                "实扫 $scannedFiles 个）——发放通道文件新增或改名须同步 grantChannelFileNames",
            scannedFiles == GRANT_CHANNEL_FILE_COUNT
        )
        assertTrue(
            "兑换码与邮件已改为『发碎片』通道（G08 D-9 / 验收④），" +
                "再出现 PortraitPool.getRandomPortrait 即说明有人在发放链里直造随机弟子：\n" +
                violations.joinToString("\n") +
                "\n处置：改用 GachaFacade.grantFragments(templateId, count) 入账；" +
                "确需角色实例时经 DiscipleService.instantiateTemplate。",
            violations.isEmpty()
        )
    }

    /** 兑换码与邮件链主源文件（按文件名匹配，避免硬编码整路径） */
    private val grantChannelFileNames = Regex(
        """[/\\](RedeemCodeRewardOps|RedeemCodeManager|MailAttachment\w*)\.kt$"""
    )

    @Test
    fun `portraitRes 生成式赋值与肖像池直取落点限于白名单`() {
        val generatedSites = mutableSetOf<String>()
        val poolCallSites = mutableSetOf<String>()
        forEachCodeLine { path, _, text ->
            if (GENERATED_PORTRAIT_ASSIGN.containsMatchIn(text)) generatedSites += path
            if (PORTRAIT_DRAW_CALL.containsMatchIn(text) &&
                !PORTRAIT_DRAW_DEFINITION.containsMatchIn(text)
            ) {
                poolCallSites += path
            }
        }
        assertTrue(
            "`portraitRes =` 的**生成式**赋值（同一行右端直取肖像池）落点漂移：" +
                "\n  实测 ${generatedSites.sorted()}" +
                "\n  登记 $PORTRAIT_GENERATION_WHITELIST" +
                "\n处置：弟子 portraitRes 只能由 DiscipleFactory（空模板键的回退臂）或 " +
                "AISectDiscipleManager（口径 9 明确保留的 AI 旁路）产出；" +
                "新增生成点等于绕过模板层，请改用 CharacterTemplate.portraitKey。",
            generatedSites.all { path -> PORTRAIT_GENERATION_WHITELIST.any { path.endsWith(it) } }
        )
        assertTrue(
            "PortraitPool.getRandomPortrait 的调用方集合漂移（37 张通用像的随机取像面）：" +
                "\n  实测 ${poolCallSites.sorted()}" +
                "\n  登记 $PORTRAIT_DRAW_WHITELIST" +
                "\n处置：新调用方必须属于「模板空键回退臂 / AI 宗旁路 / UI 展示兜底」三类之一，" +
                "否则就是把程序化生成重新接回角色获取渠道（37 张通用像已从获取渠道退役）。",
            poolCallSites.all { path -> PORTRAIT_DRAW_WHITELIST.any { path.endsWith(it) } }
        )
    }

    // ==================== 扫描基建 ====================

    /** 命中判定 + 所属函数归类 */
    private fun classify(module: String, path: String, line: Int, text: String, lines: List<String>): CallSite? {
        // 定义行（`fun insert(` / `fun allocateAndInsert(`）= 物理入口本体，见 intentionallyExcluded
        if (DECLARATION.containsMatchIn(text) && !DISCIPLE_TABLES_INSERT.containsMatchIn(text)) return null
        val api = when {
            ALLOCATE_AND_INSERT.containsMatchIn(text) -> "allocateAndInsert"
            DISCIPLE_TABLES_INSERT.containsMatchIn(text) -> "discipleTables.insert"
            else -> null
        } ?: return null
        return CallSite(module, path, line, enclosingFunction(lines, line), api)
    }

    /** 向上回溯最近的函数声明名（Kotlin 接收者限定式 `fun Foo.bar(` 亦可） */
    private fun enclosingFunction(lines: List<String>, line: Int): String {
        for (index in line - 1 downTo 0) {
            FUNCTION_DECLARATION.find(lines[index])?.let { return it.groupValues[2].removeSurrounding("`") }
        }
        return TOP_LEVEL
    }

    private fun buildCorpus(): Corpus {
        val files = LinkedHashMap<String, List<String>>()
        val sites = mutableListOf<CallSite>()
        for ((module, dir) in moduleMainDirs) {
            assertTrue(
                "模块主源目录不可达（Gradle 工作目录应为 android/core/engine）：${dir.absolutePath}",
                dir.isDirectory
            )
            dir.walkTopDown()
                .filter { it.isFile && (it.extension == "kt" || it.extension == "java") }
                .forEach { file ->
                    val relative = relativePath(file)
                    val lines = stripComments(file.readLines())
                    files[relative] = lines
                    lines.forEachIndexed { index, text ->
                        classify(module, relative, index + 1, text, lines)?.let { sites += it }
                    }
                }
        }
        return Corpus(files, sites)
    }

    private fun forEachCodeLine(block: (String, Int, String) -> Unit) {
        for ((path, lines) in corpus.files) {
            lines.forEachIndexed { index, text ->
                if (text.isNotBlank()) block(path, index + 1, text)
            }
        }
    }

    /**
     * 语义前置核对。
     *
     * @param window 向上回溯的行窗口；null = 全文件搜索（用于「所在文件必须真在查模板表」）
     */
    private fun requirePattern(site: CallSite, pattern: Regex, window: Int?): String? {
        val lines = corpus.files[site.path] ?: return "文件未进入扫描集"
        val scope = if (window == null) {
            lines
        } else {
            lines.subList(maxOf(0, site.line - 1 - window), site.line - 1)
        }
        val label = if (window == null) "文件内" else "上方 $window 行内"
        return if (scope.any { pattern.containsMatchIn(it) }) null else "${label}缺少 /${pattern.pattern}/"
    }

    private fun mainSourcePath(vararg segments: String): String = (
        listOf("..", "..") + segments + listOf("src", "main")
        ).joinToString(File.separator)

    /**
     * 归一化相对路径（`File.separatorChar` → `/`）。
     *
     * 🔴 必须归一：Windows 上 `relativeTo().path` 用反斜杠，与白名单里的 `/` 路径
     * 永不相等 ⇒ 白名单**静默失效**、每个合法调用点都被判成旁路
     * （教训来源：WallClockReflowGuardTest.relativePath 的实测注释）。
     */
    private fun relativePath(file: File): String =
        file.relativeTo(File(".." + File.separator + "..")).path.replace(File.separatorChar, '/')

    private companion object {
        const val DISCIPLE_FACTORY = "DiscipleFactory.kt"
        const val AI_SECT_DISCIPLE_MANAGER = "AISectDiscipleManager.kt"
        const val HEAVENLY_TRIAL_COMPONENTS = "HeavenlyTrialComponents.kt"
        const val TOP_LEVEL = "<top-level>"

        /** `portraitRes` 生成式赋值的合法落点（模板工厂空键臂 + AI 宗旁路） */
        val PORTRAIT_GENERATION_WHITELIST = listOf(DISCIPLE_FACTORY, AI_SECT_DISCIPLE_MANAGER)

        /**
         * 肖像池随机取像的登记调用方（文件名后缀）。三类各有保留理由：
         * - `DiscipleFactory`：空 templateId 回退臂（D-6 要求该臂 RNG 消费序逐字节不变）
         * - `AISectDiscipleManager`：口径 9——AI 宗弟子构造保持旁路
         * - `HeavenlyTrialComponents`：UI 展示兜底，无立绘键的参战者按场景键确定性取像
         */
        val PORTRAIT_DRAW_WHITELIST = listOf(
            DISCIPLE_FACTORY, AI_SECT_DISCIPLE_MANAGER, HEAVENLY_TRIAL_COMPONENTS
        )

        /** 回写口 remove 前置与读档口 clear 前置的回溯窗口（行） */
        const val WRITE_BACK_WINDOW = 24
        const val LOAD_WINDOW = 16

        /** 兑换码/邮件链文件数（RedeemCodeManager + RedeemCodeRewardOps + MailAttachment×2） */
        const val GRANT_CHANNEL_FILE_COUNT = 4

        val ALLOCATE_AND_INSERT = Regex("""allocateAndInsert\s*\(""")
        val DISCIPLE_TABLES_INSERT = Regex("""[A-Za-z0-9_.]*[Dd]iscipleTables\s*\.\s*insert\s*\(""")
        val DECLARATION = Regex("""(^|\s)(fun|void)\s+(allocateAndInsert|insert)\s*\(""")
        val FUNCTION_DECLARATION = Regex(
            """(^|[^\w.])fun\s+(?:[\w<>?, .]+\s+)?(?:[\w<>?, .]+[.])?([A-Za-z_]\w*|`[^`]+`)\s*[(<]"""
        )
        val REMOVE_BEFORE = Regex("""\.remove\s*\(""")
        val CLEAR_BEFORE = Regex("""\.clear\s*\(""")
        val TEMPLATE_LOOKUP = Regex("""CharacterTemplateDb""")
        val PORTRAIT_DRAW = Regex("""getRandomPortrait""")
        val PORTRAIT_DRAW_CALL = Regex("""[Pp]ortraitPool\s*\.\s*getRandomPortrait\s*\(""")
        val PORTRAIT_DRAW_DEFINITION = Regex("""fun\s+getRandomPortrait\s*\(""")
        val GENERATED_PORTRAIT_ASSIGN = Regex("""portraitRes\s*=(?!=).*getRandomPortrait""")

        /**
         * 剔除注释（行注释 + 块注释，含 KDoc），保留行号与行序。
         *
         * 必须剔除：`DiscipleService.kt` 的占位注释（"allocateAndInsert 会覆盖"）与
         * `PresentationRandom.kt` 的 KDoc 引用都会命中被禁字面量，不剔除则「改注释即改守卫」。
         * 实现与 [RngSourceGuardTest] 同源（该守卫已实测过这套词法的够用性）。
         */
        fun stripComments(lines: List<String>): List<String> {
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
                        c == '/' && next == '/' -> i = raw.length
                        c == '/' && next == '*' -> { inBlockComment = true; i += 2 }
                        else -> { sb.append(c); i++ }
                    }
                }
                out.add(sb.toString().trim())
            }
            return out
        }
    }
}
