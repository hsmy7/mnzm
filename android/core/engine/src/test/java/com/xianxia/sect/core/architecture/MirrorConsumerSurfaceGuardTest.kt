package com.xianxia.sect.core.architecture

import com.xianxia.sect.core.nativebridge.NativeEngineFlag
import org.junit.Assert.assertEquals
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
 *    出现在 `GameViewMirrorCodec.kt`——信封解码唯一出口；
 * 3. UI 模块（`core/ui` 与 `feature` 各模块）主源对镜像符号零命中——UI 只经
 *    GameStateStore 只读流消费，不直连镜像通道（"UI 行为零变更"的结构面）；
 * 4. 灰度共存两臂与旗标默认值在源码面保留（`applyDirtyProto` / `applyDirty` 双分支
 *    + `mirrorProtobufTransport` 默认 true）——回滚臂物理删除须等灰度期满、走独立批次。
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
            listOf(CODEC_FILE),
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
    fun `灰度共存两臂与旗标默认值保留`() {
        val syncService = readMainFile(MIRROR_ENTRY_FILE)
        val flagSource = readMainFile(FLAG_FILE)
        assertTrue(
            "二进制臂缺失（applyDirtyProto 分支）——R2.2 换轨被回退",
            syncService.contains("applyDirtyProto(raw)")
        )
        assertTrue(
            "JSON 回滚臂缺失（applyDirty(raw.decodeToString())）——灰度共存期未满不得删臂",
            syncService.contains("applyDirty(raw.decodeToString())")
        )
        assertTrue(
            "旗标默认值漂移（生产默认应为换轨生效 true）",
            flagSource.contains("var mirrorProtobufTransport: Boolean = true")
        )
        assertTrue("运行期旗标默认值", NativeEngineFlag.mirrorProtobufTransport)
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
        private const val FLAG_FILE =
            "core/engine:java/com/xianxia/sect/core/nativebridge/NativeEngineFlag.kt"

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
