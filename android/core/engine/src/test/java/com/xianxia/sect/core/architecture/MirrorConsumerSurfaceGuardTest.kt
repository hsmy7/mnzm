package com.xianxia.sect.core.architecture

import com.xianxia.sect.core.nativebridge.NativeEngineFlag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 守卫测试：UI 消费面二进制切换的**单一入口**门禁（重构方案 R2.3 第一波，
 * 审计清单见 docs/mirror-consumer-audit-2026-09-18.md）。
 *
 * ## 存在理由
 * R2.3 第一波的交付是"UI 消费面只换传输（镜像仍全量、二进制）"。B06 之后
 * 生产稳态馈送链（`nativeExportDirty` → GameView protobuf → 解码 → GameStateStore）
 * 已全量换轨，残余 JSON 点是**设计上保留**的两条臂（F2 灰度回滚臂、F3 低频全量
 * 兜底臂）。这类"设计上保留"最怕的不是保留本身，而是**未经审计的第三入口长回来**
 * ——某处在 StateSyncService 之外直接拉 JNI 字节、或另起一个 GameView 解码器，
 * 灰度旗标就会只覆盖一半消费面，回滚臂形同虚设（第二波瘦身时会误判消费面规模）。
 * 故把审计结论固化为静态门禁：命中集合 == 审计白名单，多一个即红。
 *
 * ## 判据（四条）
 * 1. 镜像导出 JNI 调用点（`GameCoreBridge.nativeExportDirty/State(`）只允许出现在
 *    `StateSyncService.kt`——馈送链唯一入口；
 * 2. `GameView` protobuf 契约（`com.xianxia.sect.proto.gameview`）import 只允许
 *    出现在 `GameViewMirrorCodec.kt`（信封解码唯一出口）与
 *    `gameview/GameViewDiscipleRows.kt`（R2.3 第二波弟子行 typed 投影——只消费
 *    `DiscipleRow` 行契约类型，不解析信封、不产变更集树）——信封级类型的第二处
 *    解析者一旦出现即红；
 * 3. UI 模块（`core/ui` 与 `feature` 各模块）主源对镜像符号零命中——UI 只经
 *    GameStateStore 只读流消费，不直连镜像通道（"UI 行为零变更"的结构面）；
 * 4. 传输臂退役后的单臂源码面（B18，2026-09-20 用户决策开工）：生产通道恒
 *    `applyDirtyProto`，JSON 回滚分支与 `mirrorProtobufTransport` 旗标不得回流。
 */
class MirrorConsumerSurfaceGuardTest {

    /** 六模块主源根目录（Gradle 测试工作目录 = android/core/engine，故用相对路径） */
    private val moduleMainDirs: List<Pair<String, File>> = listOf(
        "core/domain" to File(mainSourcePath("core", "domain")),
        "core/engine" to File(mainSourcePath("core", "engine")),
        "core/data" to File(mainSourcePath("core", "data")),
        "core/ui" to File(mainSourcePath("core", "ui")),
        "feature/game" to File(mainSourcePath("feature", "game")),
        "app" to File(mainSourcePath("app"))
    )

    @Test
    fun `镜像导出 JNI 调用点唯一（馈送链单一入口）`() {
        val hits = kotlinHits(Regex("""GameCoreBridge\.nativeExport(Dirty|State)\s*\("""))
        assertEquals(
            "镜像导出只允许 StateSyncService 拉取（新增拉取点 = 未经审计的第二消费入口，" +
                "灰度旗标将只覆盖一半馈送链）：\n" + hits.joinToString("\n"),
            listOf(MIRROR_ENTRY_FILE),
            hits.paths,
        )
    }

    @Test
    fun `GameView 信封解码只经 GameViewMirrorCodec`() {
        val hits = kotlinHits(Regex("""import com\.xianxia\.sect\.proto\.gameview\."""))
        assertEquals(
            "GameView protobuf 契约只允许镜像解码器消费（第二处解码 = 双解码器漂移）：\n" +
                hits.joinToString("\n"),
            listOf(CODEC_FILE, ROW_PROJECTION_FILE).sorted(),
            hits.paths,
        )
    }

    @Test
    fun `UI 模块主源零镜像符号直连（只经 GameStateStore 只读流消费）`() {
        val uiModules = setOf("core/ui", "feature/game")
        val violations = moduleMainDirs
            .filter { it.first in uiModules }
            .flatMap { (moduleName, dir) -> scan(dir, moduleName, MIRROR_SYMBOLS) }
        assertTrue(
            "UI 层不得直连镜像通道（读 GameStateStore 只读流；R2.3 红线" +
                "「UI 行为零变更」的结构面守卫）：\n" + violations.joinToString("\n"),
            violations.isEmpty()
        )
    }

    @Test
    fun `传输臂已退役（B18 后单臂源码面）`() {
        val syncService = readMainFile(MIRROR_ENTRY_FILE)
        val flagSource = readMainFile(FLAG_FILE)
        assertTrue(
            "二进制臂缺失（applyDirtyProto 恒走）——R2.2 换轨被回退",
            syncService.contains("applyDirtyProto(raw)")
        )
        assertFalse(
            "JSON 回滚臂不得回流生产分发（applyDirty(raw.decodeToString())）",
            syncService.contains("applyDirty(raw.decodeToString())")
        )
        assertFalse(
            "传输旗标不得回流（mirrorProtobufTransport 已随 B18 删除）",
            flagSource.contains("mirrorProtobufTransport")
        )
        assertTrue(
            "第二波投影旗标默认值漂移（R2.3 二波生产默认 = 投影态生效）",
            flagSource.contains("var gameViewProjection: Boolean = true")
        )
        assertTrue("运行期旗标默认值（第二波）", NativeEngineFlag.gameViewProjection)
    }

    /**
     * R2.3 第二波「ViewModel 逐块迁移」来源锁定。
     *
     * 每迁一个 UI 消费块，就把该块登记进 [MIGRATED_BLOCKS]：块的 ViewModel 取数面
     * 必须引用投影入口（[projectedFrom]），且迁移前的整份快照取数面
     * （[legacySource]）必须已从源码消失。漏登记 / 半途回退 / 只改一半 ⇒ 本守卫红，
     * 保证"逐块可回滚"的编排前提在源码面可核。
     */
    @Test
    fun `已迁 UI 消费块以投影为来源（逐块迁移台账）`() {
        val viewModel = readMainFile(GAME_VIEW_MODEL_FILE)
        for (block in MIGRATED_BLOCKS) {
            assertTrue(
                "已迁块「${block.name}」的投影入口消失（${block.projectedFrom}）——" +
                    "第二波迁移被回退或改到一半",
                viewModel.contains(block.projectedFrom)
            )
            assertTrue(
                "已迁块「${block.name}」仍从整份 gameData 快照取数（${block.legacySource}）" +
                    "——迁移未完成却保留双源，两臂可能分叉",
                !viewModel.contains(block.legacySource)
            )
        }
    }

    /** 已迁 UI 消费块台账（块名 / 投影入口 / 迁移前取数面） */
    private data class MigratedBlock(val name: String, val projectedFrom: String, val legacySource: String)

    /**
     * R2.3 第二波「每旬级全量重建路径退场」门禁。
     *
     * 稳态热路径（[StateSyncService.applyDirtyFromNative] 起至文件尾：两臂解析 +
     * applyEnvelope + mergeGameDataChanges + 实体集合/弟子行应用）必须**零全表替换**，
     * 且第二波形状（字段级 gameData 应用 + 弟子行 typed 投影）必须在位。
     * 全表 `replaceAll` 只允许留在低频全量兜底臂 F3（[StateSyncService.applySnapshot]，
     * 实测 12 旬 0 次触发，见 docs/mirror-consumer-audit-2026-09-18.md 观测表）——
     * 热路径若重新长出全表替换，即"每旬级全量重建"复活，本守卫红。
     */
    @Test
    fun `每旬增量臂零全表替换且第二波形状在位`() {
        val sync = readMainFile(MIRROR_ENTRY_FILE)
        val hotPathStart = sync.indexOf("fun applyDirtyFromNative(")
        assertTrue("增量臂入口消失（applyDirtyFromNative）", hotPathStart > 0)
        val hotPath = sync.substring(hotPathStart)
        assertTrue(
            "每旬热路径重新出现全表替换（= 每旬级全量重建复活）：\n" +
                hotPath.lines().filter { it.contains(".replaceAll(") }.joinToString("\n"),
            !hotPath.contains(".replaceAll(")
        )
        assertTrue(
            "gameData 字段级应用缺失（第二波瘦身被回退成整份 JSON 往返）",
            hotPath.contains("GameDataFieldPatch.apply(")
        )
        assertTrue(
            "弟子行 typed 投影臂缺失（每行 JSON 造树复活）",
            hotPath.contains("discipleProjections")
        )
        val snapshotArm = sync.substring(0, sync.indexOf("fun applyDirtyFromNative("))
        val replaceAllCount = Regex("""\.replaceAll\(""").findAll(snapshotArm).count()
        assertEquals(
            "全量兜底臂（F3）的全表替换面漂移（10 个实体集合各一处；增删集合需同步本门禁" +
                "与方案 §7.2 B08 行）",
            10, replaceAllCount
        )
    }

    // ── 扫描工具 ────────────────────────────────────────────────

    private fun kotlinHits(pattern: Regex): List<String> =
        moduleMainDirs.flatMap { (moduleName, dir) -> scan(dir, moduleName, pattern) }.sorted()

    /** 命中记为 `模块:相对路径:行号`，比对时仅取路径部分（行号随编辑漂移）。 */
    private fun scan(dir: File, moduleName: String, pattern: Regex): List<String> {
        assertTrue("模块主源目录不存在: $moduleName -> $dir", dir.isDirectory)
        return dir.walkTopDown()
            .filter { it.isFile && it.extension in setOf("kt", "java") }
            .flatMap { file ->
                val path = "$moduleName:${file.relativeTo(dir).invariantSeparatorsPath}"
                file.readLines().mapIndexedNotNull { idx, line ->
                    if (pattern.containsMatchIn(line)) "$path:${idx + 1}" else null
                }
            }
            .toList()
    }

    private fun readMainFile(key: String): String {
        val (moduleName, relative) = key.split(':', limit = 2)
        val dir = moduleMainDirs.first { it.first == moduleName }.second
        return File(dir, relative).readText()
    }

    private fun mainSourcePath(vararg segments: String): String = (
        listOf("..", "..") + segments + listOf("src", "main")
        ).joinToString(File.separator)

    companion object {
        private const val MIRROR_ENTRY_FILE =
            "core/engine:java/com/xianxia/sect/core/nativebridge/StateSyncService.kt"
        private const val CODEC_FILE =
            "core/engine:java/com/xianxia/sect/core/nativebridge/GameViewMirrorCodec.kt"
        /**
         * 弟子行 typed 投影（R2.3 第二波）：只消费 `DiscipleRow` **行契约类型**，
         * 不解析 GameView 信封、不产变更集树——解码出口仍唯一 = [CODEC_FILE]。
         */
        private const val ROW_PROJECTION_FILE =
            "core/engine:java/com/xianxia/sect/core/gameview/GameViewDiscipleRows.kt"
        private const val FLAG_FILE =
            "core/engine:java/com/xianxia/sect/core/nativebridge/NativeEngineFlag.kt"
        private const val GAME_VIEW_MODEL_FILE =
            "feature/game:java/com/xianxia/sect/ui/game/GameViewModel.kt"

        /**
         * R2.3 第二波逐块迁移台账（块①资源头部 / 块②配置回声 / 块③事件流）。
         * 每迁一块加一行；未迁块不得出现在此表。
         */
        private val MIGRATED_BLOCKS = listOf(
            MigratedBlock(
                name = "块①资源头部（仓库页灵石三阶）",
                projectedFrom = "gameEngine.resourcesHeader",
                legacySource = "val spiritStoneTotals: StateFlow<SpiritStoneTotals> = gameData"
            ),
            MigratedBlock(
                name = "块②配置回声（政策/年俸/长老槽/放置建筑/招募灵根）",
                projectedFrom = "gameEngine.configEcho",
                legacySource = "val configState: StateFlow<GameStateStore.ConfigState> get() = gameEngine.configState"
            ),
            MigratedBlock(
                name = "块③事件流（消息栏 gameEventRecords）",
                projectedFrom = "gameEngine.eventLog",
                legacySource = "val gameEventRecords: StateFlow<List<GameEventRecord>> = gameEngine.gameData"
            )
        )

        /** 镜像通道符号族（UI 侧出现即 = 绕过 GameStateStore 直连馈送链） */
        private val MIRROR_SYMBOLS = Regex(
            """StateSyncService|GameViewMirrorCodec|proto\.gameview|""" +
                """nativeExportDirty|nativeExportState|applyDirty|applySnapshot|mirrorProtobufTransport"""
        )

        /** [scan] 结果形如 `path:line`——等值比较用去行号的去重路径集合。 */
        private val List<String>.paths: List<String>
            get() = map { it.substringBeforeLast(':') }.distinct()
    }
}
