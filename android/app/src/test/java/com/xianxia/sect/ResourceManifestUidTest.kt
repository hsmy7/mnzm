package com.xianxia.sect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * UID 稳定性守卫（2026-08-13 资源管线 codegen，对标 Godot UID 稳定引用）。
 *
 * 调用 resource-manifest.mjs 的 --dir 模式（单目录扫描）验证：
 * 1. 重复生成 → UID 与基线完全一致（内容未变不重新分配）
 * 2. 新增资源 → 既有资源 UID 不变，新资源按字典序追加新 UID
 *
 * 保证：新增精灵图不会打乱既有资源的稳定引用（依赖 UID 的下游系统安全）。
 */
class ResourceManifestUidTest {

    @get:Rule
    val tmpFolder = TemporaryFolder()

    private val script = File("../scripts/resource-manifest.mjs")

    @Test
    fun `重复生成 - UID 与基线完全一致`() {
        val dir = tmpFolder.newFolder("sprites")
        writeSprite(dir, "b.webp", byteArrayOf(1, 2, 3))
        writeSprite(dir, "a.webp", byteArrayOf(4, 5, 6))

        val first = runManifest(dir)
        val second = runManifest(dir)

        assertEquals("重复生成的 UID 必须一致", first.uidByName(), second.uidByName())
        assertEquals("重复生成的条目数必须一致", first.entries.size, second.entries.size)
    }

    @Test
    fun `新增资源 - 既有 UID 不变且新资源按字典序追加`() {
        val dir = tmpFolder.newFolder("sprites")
        writeSprite(dir, "b.webp", byteArrayOf(1, 2, 3))
        writeSprite(dir, "a.webp", byteArrayOf(4, 5, 6))

        val baseline = runManifest(dir)

        // 新增资源（含字典序位于 a 与 b 之间的 a1——插入中间不得漂移既有 UID）
        writeSprite(dir, "c.webp", byteArrayOf(7, 8, 9))
        writeSprite(dir, "a1.webp", byteArrayOf(10, 11, 12))

        val after = runManifest(dir)

        for ((name, uid) in baseline.uidByName()) {
            assertEquals("既有资源 $name 的 UID 在新增资源后必须保持不变", uid, after.uidByName()[name])
        }
        // 新增资源按字典序追加新 UID（max+1）：a1=2、c=3（既有 a=0、b=1 不动）
        assertEquals("a", 0, after.uidByName()["a"])
        assertEquals("b", 1, after.uidByName()["b"])
        assertEquals("a1", 2, after.uidByName()["a1"])
        assertEquals("c", 3, after.uidByName()["c"])
    }

    @Test
    fun `内容变更 - 同名文件 MD5 变化时 UID 保持但标记变更`() {
        val dir = tmpFolder.newFolder("sprites")
        writeSprite(dir, "a.webp", byteArrayOf(1, 2, 3))

        val baseline = runManifest(dir)

        // 同名文件内容变化（模拟资源更新）——UID 必须稳定（引用不漂移），MD5 更新
        writeSprite(dir, "a.webp", byteArrayOf(9, 9, 9))
        val after = runManifest(dir)

        assertEquals("内容变更后 UID 必须保持稳定", baseline.uidByName()["a"], after.uidByName()["a"])
        assertTrue(
            "内容变更后 MD5 必须更新（资源导入管线依赖 MD5 判定重导入）",
            baseline.md5ByName()["a"] != after.md5ByName()["a"]
        )
    }

    private fun writeSprite(dir: File, name: String, content: ByteArray) {
        File(dir, name).writeBytes(content)
    }

    private fun runManifest(dir: File): ManifestData {
        // 固定 out 与 uid-map 路径：两次运行共享持久 UID 映射（与真实管线一致——
        // sprite-uid-map.json 提交版本库，clean 重建后 UID 依然稳定）
        val outFile = File(tmpFolder.root, "manifest.json")
        val uidMapFile = File(tmpFolder.root, "uid-map.json")
        assertTrue(
            "resource-manifest.mjs 不存在: ${script.absolutePath}",
            script.exists()
        )
        val proc = ProcessBuilder(
            "node", script.absolutePath,
            "--dir", dir.absolutePath,
            "--out", outFile.absolutePath,
            "--uid-map", uidMapFile.absolutePath
        ).redirectErrorStream(true).start()
        val completed = proc.waitFor(60, TimeUnit.SECONDS)
        assertTrue("resource-manifest.mjs 执行超时", completed)
        val output = proc.inputStream.bufferedReader().readText()
        assertEquals("resource-manifest.mjs 退出码非 0：\n$output", 0, proc.exitValue())
        return ManifestData.fromFile(outFile)
    }

    private data class ManifestData(val entries: List<Entry>) {
        fun uidByName(): Map<String, Int> = entries.associate { it.name to it.uid }
        fun md5ByName(): Map<String, String> = entries.associate { it.name to it.md5 }

        data class Entry(val name: String, val uid: Int, val md5: String)

        companion object {
            /**
             * 解析 manifest JSON（unit test 无 org.json，按固定结构正则提取）。
             * 条目格式: { "name": "...", "module": "...", "relPath": "...", "size": N, "md5": "...", "uid": N }
             */
            fun fromFile(file: File): ManifestData {
                val text = file.readText()
                val nameRegex = Regex(""""name": "([^"]+)"""")
                val uidRegex = Regex(""""uid": (\d+)""")
                val md5Regex = Regex(""""md5": "([0-9a-f]{32})"""")
                val names = nameRegex.findAll(text).map { it.groupValues[1] }.toList()
                val uids = uidRegex.findAll(text).map { it.groupValues[1].toInt() }.toList()
                val md5s = md5Regex.findAll(text).map { it.groupValues[1] }.toList()
                assertTrue("manifest 字段数量不一致（name=${names.size} uid=${uids.size} md5=${md5s.size}）",
                    names.size == uids.size && uids.size == md5s.size)
                val entries = names.indices.map { Entry(names[it], uids[it], md5s[it]) }
                return ManifestData(entries)
            }
        }
    }
}
