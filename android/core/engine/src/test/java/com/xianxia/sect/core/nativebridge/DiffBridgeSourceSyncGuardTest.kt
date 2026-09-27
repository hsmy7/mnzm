package com.xianxia.sect.core.nativebridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/**
 * 桌面对拍桥「源码—产物同源」守卫。
 *
 * 校验注入的 `libgamecorejni.so`（`-Dgamecore.jni.path`）与其旁挂指纹文件 `<so>.fingerprint`
 * 逐文件一致；指纹由 `scripts/build-desktop-jni.ps1` / `scripts/build-desktop-jni-linux.sh`
 * 调 `android/scripts/desktop-jni-fingerprint.mjs` 生成（枚举口径两侧逐字一致）。
 *
 * 判别力场景：被编入桥的 C++ 源文件改了内容却没重编、或桥产物跨树复制（指纹缺失/不符）
 * ⇒ 本守卫判红并给出重建指令。否则 Diff*Test 会把「桥与源码不同源」误报成行为分歧，
 * 或更糟——桥落后时对拍绿灯被当成等价性证据。
 */
class DiffBridgeSourceSyncGuardTest {

    /** gamecore 源码根：从测试工作目录向上定位（`android/` 与仓库根两种起点都兼容）。 */
    private fun gamecoreDir(): File? {
        var dir: File? = File(System.getProperty("user.dir") ?: ".")
        while (dir != null) {
            val androidStart = File(dir, "app/src/main/cpp/gamecore")
            if (androidStart.isDirectory) return androidStart
            val repoStart = File(dir, "android/app/src/main/cpp/gamecore")
            if (repoStart.isDirectory) return repoStart
            dir = dir.parentFile
        }
        return null
    }

    /** 口径内源文件（相对 gamecore 的 POSIX 路径，序数升序）——与指纹脚本同规则。 */
    private fun fingerprintFiles(gamecore: File): List<String> = FINGERPRINT_ROOTS
        .map { File(gamecore, it) }
        .filter { it.isDirectory }
        .flatMap { root ->
            root.walkTopDown()
                .filter { it.isFile && FINGERPRINT_EXTENSIONS.any { ext -> it.name.endsWith(ext) } }
                .map { it.relativeTo(gamecore).path.replace(File.separatorChar, '/') }
                .toList()
        }
        .sorted()

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(file.readBytes())
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun injectedBridge(): File? = System.getProperty("gamecore.jni.path")
        ?.takeIf { it.isNotBlank() }
        ?.let(::File)

    private fun sidecarOf(bridge: File): File = File("${bridge.absolutePath}.fingerprint")

    /** 指纹文件 `file <sha256> <relPath>` 行 → relPath 到 sha256 的映射 */
    private fun recordedEntries(lines: List<String>): Map<String, String> = lines
        .filter { it.startsWith(FILE_PREFIX) }
        .map { it.removePrefix(FILE_PREFIX) }
        .associate { line -> line.substringAfter(' ') to line.substringBefore(' ') }

    @Test
    fun `sidecar exists and is well formed`() {
        val bridge = injectedBridge()
        assumeTrue("未注入 -Dgamecore.jni.path：对拍桥同源校验跳过（与 Diff*Test 同口径）", bridge != null)
        val so = checkNotNull(bridge)

        assertTrue("对拍桥产物不存在: ${so.absolutePath}——$REBUILD_HINT", so.isFile)
        val sidecar = sidecarOf(so)
        assertTrue("对拍桥缺少同源指纹文件: ${sidecar.absolutePath}——$REBUILD_HINT", sidecar.isFile)

        val lines = sidecar.readLines()
        assertEquals("指纹文件头不符（版本口径）: $SIDECAR_HEADER——$REBUILD_HINT", SIDECAR_HEADER, lines.firstOrNull())
        assertTrue("指纹文件不含源文件条目——$REBUILD_HINT", recordedEntries(lines).isNotEmpty())
    }

    @Test
    fun `recorded sources match working tree byte for byte`() {
        val bridge = injectedBridge()
        assumeTrue("未注入 -Dgamecore.jni.path：对拍桥同源校验跳过（与 Diff*Test 同口径）", bridge != null)
        val so = checkNotNull(bridge)
        assumeTrue("对拍桥缺少同源指纹文件，交由 sidecar 用例判红: ${sidecarOf(so).absolutePath}", sidecarOf(so).isFile)

        val gamecore = gamecoreDir()
        assumeTrue(
            "gamecore 源码根定位失败（工作目录: ${System.getProperty("user.dir")}）",
            gamecore != null
        )
        val sourceRoot = checkNotNull(gamecore)

        val recorded = recordedEntries(sidecarOf(so).readLines())
        val onDisk = fingerprintFiles(sourceRoot)
        val mismatched = onDisk.filter { path -> recorded[path]?.let { it != sha256(File(sourceRoot, path)) } == true }
        val notBuiltIn = onDisk.filterNot { recorded.containsKey(it) }
        val vanished = recorded.keys.filterNot { onDisk.contains(it) }

        val problems = buildList {
            if (mismatched.isNotEmpty()) add("内容不一致 ${mismatched.size} 个: ${mismatched.take(5).joinToString()}")
            if (notBuiltIn.isNotEmpty()) add("源码未编入指纹 ${notBuiltIn.size} 个: ${notBuiltIn.take(5).joinToString()}")
            if (vanished.isNotEmpty()) add("指纹含已消失文件 ${vanished.size} 个: ${vanished.take(5).joinToString()}")
        }
        assertTrue("对拍桥与 C++ 源码不同源——${problems.joinToString("; ")}——$REBUILD_HINT", problems.isEmpty())
    }

    private companion object {
        const val SIDECAR_HEADER = "gamecore-jni-source-sync v1"
        const val FILE_PREFIX = "file "
        const val REBUILD_HINT = "重编对拍桥：pwsh -File scripts/build-desktop-jni.ps1（Windows）" +
            " / bash scripts/build-desktop-jni-linux.sh（CI），再重跑测试"

        /** 与 desktop-jni-fingerprint.mjs 的 FINGERPRINT_ROOTS / FINGERPRINT_EXTENSIONS 逐字一致 */
        val FINGERPRINT_ROOTS = listOf("include", "jni-include", "third_party", "src", "jni")
        val FINGERPRINT_EXTENSIONS = listOf(".h", ".hpp", ".hh", ".hxx", ".inc", ".c", ".cc", ".cpp", ".cxx")
    }
}
