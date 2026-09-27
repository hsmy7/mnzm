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
 * 同一个类内出现重复字段号 ⇒ 变红。历史首例即 `GameData.pendingTraitAdds` 与
 * `prisonerSpiritRootFilter` 同用 162（当时修正：pendingTraitAdds → 1002 让号）；
 * 该字段已随 G04 洗炼/特质链下线删除，号 1002（连同血炼四号 115/150/151/152）
 * 在 `GameData.kt` 登记 reserved 禁复用，Disciple 侧 22/93/104/105/110 同理——
 * 退役号不得再被 `@ProtoNumber` 重新标注（见 reserved 守卫用例）。
 */
class ProtoNumberUniquenessTest {

    private companion object {
        /** 模型源码根（单元测试工作目录 = 模块根 `android/core/data`）。
         *  SR-1 起纳入 core:data `data/model`（SaveData 所在目录）——wire 面新字段
         *  必须过本守卫（方案 IN4）；两种路径形状分别对应模块根/仓库根两种 cwd。 */
        val MODEL_ROOTS = listOf(
            "../domain/src/main/java/com/xianxia/sect/core/model",
            "core/domain/src/main/java/com/xianxia/sect/core/model",
            "../data/src/main/java/com/xianxia/sect/data/model",
            "core/data/src/main/java/com/xianxia/sect/data/model"
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
        // 修正方向锁：162 的归属一直是更老的 prisonerSpiritRootFilter（老档兼容）。
        // 历史上的让号方 pendingTraitAdds 已随 G04 删除，其半边断言随之退役，
        // 退役号禁复用由下方 reserved 守卫用例承接。防后人"改回去"。
        val gameData = MODEL_ROOTS.map { File(it, "GameData.kt") }.firstOrNull { it.isFile }
            ?: error("GameData.kt 未找到（cwd=${File(".").absolutePath}）")
        val text = gameData.readText()

        val prisonerAnnotations = text.substringBefore("var prisonerSpiritRootFilter").takeLast(200)

        assertTrue(
            "prisonerSpiritRootFilter 应仍保留 @ProtoNumber(162)（老档兼容）",
            prisonerAnnotations.contains("@ProtoNumber(162)")
        )
    }

    @Test
    fun `G04 G15 retired proto numbers stay reserved instead of reused`() {
        // G04（洗炼/特质链下线）与 G15（关系列下线，Disciple 退役号 93）删除字段的号
        // 已登记 reserved（GameData.kt / DiscipleSerializer.kt 的 reserved 注释），
        // 禁止复用——复用会让旧档字节按新语义解码（wire 漂移）。
        // 号是 per-message 语义：GameData.kt 内其他消息合法占用的 104/105/110
        // 不在本断言面（Disciple 侧保留号只对 SerializableDisciple 生效，该文件单一消息）。
        val modelRoot = MODEL_ROOTS.map(::File).firstOrNull { it.isDirectory }
            ?: error("模型源码目录未找到（cwd=${File(".").absolutePath}）")
        val gameDataText = File(modelRoot, "GameData.kt").readText()
        val discipleSerializerText = File(modelRoot, "DiscipleSerializer.kt").readText()

        val gameDataRetired = listOf(115, 150, 151, 152, 1002)
        val discipleRetired = listOf(22, 93, 104, 105, 110)
        val gameDataReused = gameDataRetired.filter { gameDataText.contains("@ProtoNumber($it)") }
        val discipleReused = discipleRetired.filter { discipleSerializerText.contains("@ProtoNumber($it)") }
        assertTrue(
            "GameData 退役号 $gameDataReused 不得再标注 @ProtoNumber" +
                "（reserved 禁复用，登记见 GameData.kt 注释）",
            gameDataReused.isEmpty()
        )
        assertTrue(
            "Disciple 退役号 $discipleReused 不得再标注 @ProtoNumber" +
                "（reserved 禁复用，登记见 DiscipleSerializer.kt 注释）",
            discipleReused.isEmpty()
        )
    }

    @Test
    fun `ElderSlots and SectPolicies retired proto numbers stay reserved instead of reused`() {
        // 执法堂/监牢下线批：ElderSlots 退役 tag 9（lawEnforcementElder）/10（lawEnforcementDisciples），
        // 赏善罚恶下线批：SectPolicies 退役 tag 32（rewardPunish）。
        // 复用退役号会让旧档字节按新语义解码（wire 漂移）——reserved 登记见
        // GameDataSectModels.kt 注释；本用例锁住「不得重新标注 @ProtoNumber」。
        // 号是 per-message 语义 ⇒ 必须按**类作用域**抽取后判定（同文件其他消息
        // 合法占用 9/10 等号，全局 contains 会假阳性）。
        val modelRoot = MODEL_ROOTS.map(::File).firstOrNull { it.isDirectory }
            ?: error("模型源码目录未找到（cwd=${File(".").absolutePath}）")
        val sectModelsText = File(modelRoot, "GameDataSectModels.kt").readText()

        val elderSlotsBody = classBody(sectModelsText, "ElderSlots")
        val sectPoliciesBody = classBody(sectModelsText, "SectPolicies")

        val elderSlotsReused = listOf(9, 10).filter { elderSlotsBody.contains("@ProtoNumber($it)") }
        val sectPoliciesReused = listOf(32).filter { sectPoliciesBody.contains("@ProtoNumber($it)") }
        assertTrue(
            "ElderSlots 退役号 $elderSlotsReused 不得再标注 @ProtoNumber（reserved 禁复用）",
            elderSlotsReused.isEmpty()
        )
        assertTrue(
            "SectPolicies 退役号 $sectPoliciesReused 不得再标注 @ProtoNumber（reserved 禁复用）",
            sectPoliciesReused.isEmpty()
        )
        assertTrue(
            "ElderSlots 应保留 reserved 9,10 登记注释",
            elderSlotsBody.contains("reserved 9,10")
        )
        assertTrue(
            "SectPolicies 应保留 reserved 29,32 登记注释",
            sectPoliciesBody.contains("reserved 29,32")
        )
    }

    /** 抽取 `data class <name>(` 到其闭合括号的类体文本（同文件按最内层消息作用域判定）。 */
    private fun classBody(text: String, className: String): String {
        val start = text.indexOf("class $className(")
        if (start < 0) error("未找到类 $className")
        val end = text.indexOf("\n)", start)
        return if (end < 0) text.substring(start) else text.substring(start, end)
    }

    @Test
    fun `SaveData mails keeps proto tag 56`() {
        // SR-1 方向锁：SaveData.mails 用 56（本类现有最大 55+1，升序口径）。
        // 防后人改号——改号 = 云档/.sav wire 破裂（旧档按旧号解码）。
        // 56 的类内唯一性由本类第一例扫描保证（SaveData.kt 已入 MODEL_ROOTS）。
        val saveData = MODEL_ROOTS.map { File(it, "SaveData.kt") }.firstOrNull { it.isFile }
            ?: error("SaveData.kt 未找到（cwd=${File(".").absolutePath}）")
        val text = saveData.readText()

        val mailsAnnotations = text.substringBefore("val mails").takeLast(200)

        assertTrue(
            "SaveData.mails 应使用 @ProtoNumber(56)（SR-1 wire 契约）",
            mailsAnnotations.contains("@ProtoNumber(56)")
        )
    }
}
