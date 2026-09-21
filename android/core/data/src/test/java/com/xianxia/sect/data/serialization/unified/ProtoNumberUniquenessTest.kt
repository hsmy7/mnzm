package com.xianxia.sect.data.serialization.unified

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * proto 字段号**唯一性**守卫（审计 §12-E）。
 *
 * 背景：既有 `ProtoNumberCoverageTest` 只查"每个字段有没有标 `@ProtoNumber`"，
 * **不查唯一性**（尽管它的报错文案写着"全局唯一"）⇒ `GameData` 里
 * `prisonerSpiritRootFilter` 与 `pendingTraitAdds` 同用 **162** 长期未被发现。
 * 后果：两者 wire type 都是 length-delimited，同时非空时解码互相抢占/抛错
 * ⇒ 云档与 `.sav`/`.bak` 恢复路径上"现在不爆、将来必爆"，且难归因。
 *
 * 本守卫按**类作用域**扫描模型源码（proto 号是 per-message 语义，跨类重复合法）：
 * 同一个类内出现重复字段号 ⇒ 变红。已在 `GameData.pendingTraitAdds` 上按此修正
 * （162 → 1002，让号给更老的 `prisonerSpiritRootFilter`）。
 */
class ProtoNumberUniquenessTest {

    private companion object {
        /** 模型源码根（单元测试工作目录 = 模块根 `android/core/data`） */
        val MODEL_ROOTS = listOf(
            "../domain/src/main/java/com/xianxia/sect/core/model",
            "core/domain/src/main/java/com/xianxia/sect/core/model"
        )

        /** 类/对象声明（含嵌套——嵌套声明会重置作用域，故天然按最内层类归属） */
        val CLASS_DECL = Regex(
            "^\\s*(?:@\\w+\\s+)*(?:public |internal |private |sealed |abstract |open |data |value |enum )*" +
                "(?:class|object)\\s+(\\w+)"
        )

        /** 字段号注解（只认行首注解，避免 KDoc/注释里提到 `@ProtoNumber(n)` 造成假阳性） */
        val PROTO_NUMBER = Regex("^\\s*@ProtoNumber\\((\\d+)\\)")
    }

    @Test
    fun `no duplicate proto numbers within the same class`() {
        val roots = MODEL_ROOTS.map(::File).filter { it.isDirectory }
        assertTrue(
            "模型源码目录应存在（cwd=${File(".").absolutePath}）：$MODEL_ROOTS",
            roots.isNotEmpty()
        )

        val violations = mutableListOf<String>()
        var scannedClasses = 0
        var scannedFields = 0

        for (root in roots) {
            root.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { file ->
                var currentClass: String? = null
                val seen = mutableMapOf<String, MutableMap<Int, Int>>() // class -> (tag -> 行号)
                file.readLines().forEachIndexed { idx, line ->
                    CLASS_DECL.find(line)?.let { currentClass = it.groupValues[1] }
                    val tag = PROTO_NUMBER.find(line)?.groupValues?.get(1)?.toIntOrNull() ?: return@forEachIndexed
                    val owner = currentClass ?: return@forEachIndexed
                    val perClass = seen.getOrPut(owner) { mutableMapOf() }
                    val lineNo = idx + 1
                    val prev = perClass.put(tag, lineNo)
                    scannedFields++
                    if (prev != null) {
                        violations += "${file.name}:$lineNo 与 $owner 的 :$prev 同用 proto 号 $tag"
                    }
                }
                scannedClasses += seen.size
            }
        }

        assertTrue("扫描量异常（类=$scannedClasses 字段=$scannedFields）", scannedFields >= 100)
        assertTrue(
            "同类内 proto 字段号必须唯一（重复 ⇒ wire 解码互相抢占/抛错）：\n" +
                violations.joinToString("\n"),
            violations.isEmpty()
        )
    }

    @Test
    fun `GameData keeps the historical 162 for prisonerSpiritRootFilter`() {
        // 修正方向锁：让号的是 v47 新加的 pendingTraitAdds（→1002），不是更老的
        // prisonerSpiritRootFilter（保留 162 以兼容旧档）。防后人"改回去"。
        val gameData = MODEL_ROOTS.map { File(it, "GameData.kt") }.firstOrNull { it.isFile }
            ?: error("GameData.kt 未找到（cwd=${File(".").absolutePath}）")
        val text = gameData.readText()

        val prisonerAnnotations = text.substringBefore("var prisonerSpiritRootFilter").takeLast(200)
        val pendingAnnotations = text.substringBefore("var pendingTraitAdds").takeLast(400)

        assertTrue(
            "prisonerSpiritRootFilter 应仍保留 @ProtoNumber(162)（老档兼容）",
            prisonerAnnotations.contains("@ProtoNumber(162)")
        )
        assertTrue(
            "pendingTraitAdds 不得再使用 162（已让号）",
            !pendingAnnotations.contains("@ProtoNumber(162)")
        )
        assertTrue(
            "pendingTraitAdds 应使用 @ProtoNumber(1002)",
            pendingAnnotations.contains("@ProtoNumber(1002)")
        )
    }
}
