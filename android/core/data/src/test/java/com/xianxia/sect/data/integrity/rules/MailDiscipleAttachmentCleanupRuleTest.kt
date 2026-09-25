package com.xianxia.sect.data.integrity.rules

import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.MailEntity
import com.xianxia.sect.data.integrity.SaveValidator
import com.xianxia.sect.data.model.SaveData
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 邮件弟子附件下线清理规则测试——覆盖 [MailDiscipleAttachmentCleanupRule]。
 *
 * 改道后客户端**不再生产也不再消费** `type == "disciple"` 的邮件附件
 * （发放分支与领取分支均已删除），但更早版本落库的存档里仍可能留有未领取的
 * 弟子附件；这类附件留在档内会让玩家点领取落到未知类型分支、邮件永久卡住。
 * 本测试钉住四条契约：
 * 1. 弟子附件被摘除，剩余附件的字段与顺序保留；
 * 2. `hasAttachment` 随剩余附件是否为空正确翻转；
 * 3. **命中才 Repaired**：摘除过附件或存在不可解析附件串时返回 Repaired 以触发落盘收敛；
 *    两者都没有时返回 Passed（mails 与入参逐条相同，无谓的 Repaired 会让每次读档整表
 *    替换并触发写盘）；
 * 4. 附件串不可解析时**零抛异常**、原样保留并在明细中如实计数
 *    （抛异常会被 [SaveValidator] 转成 Corrupted 并阻断读档）。
 */
class MailDiscipleAttachmentCleanupRuleTest {

    @Before
    fun setup() {
        SaveValidationRuleRegistry.clear()
        SaveValidationRuleRegistry.register(MailDiscipleAttachmentCleanupRule)
    }

    @After
    fun teardown() {
        SaveValidationRuleRegistry.clear()
    }

    // ── ① 摘除与 hasAttachment 翻转 ──────────────────────────────────────────

    @Test
    fun execute_mailWithOnlyDiscipleAttachment_stripsItAndClearsHasAttachment() {
        // Given 一封只带弟子附件的旧邮件（未领取）
        val mail = mail(
            id = "mail-disciple-only",
            hasAttachment = true,
            attachments = """[{"type":"disciple","name":"单灵根弟子","quantity":1,"rarity":3}]"""
        )

        // When 跑校验
        val outcome = validateRepaired(saveData(mail))
        val actual = outcome.data.mails.single()

        // Then 附件清空、hasAttachment 翻转为 false
        assertEquals("弟子附件应被摘除", "[]", actual.attachments)
        assertFalse("剩余附件为空时 hasAttachment 必须置 false", actual.hasAttachment)
        assertFalse("清洗后不得残留弟子字样", actual.attachments.contains("disciple"))
        assertDetails(outcome, "从 1 封邮件中摘除 1 条弟子附件")
    }

    @Test
    fun execute_mixedAttachments_removesOnlyDiscipleEntries() {
        // Given 弟子附件与受支持附件混排（顺序：material → disciple → equipment）
        val mail = mail(
            id = "mail-mixed",
            hasAttachment = true,
            attachments = """[{"type":"material","name":"灵石","quantity":1000,"rarity":1},""" +
                """{"type":"disciple","name":"双灵根弟子","quantity":2,"rarity":4},""" +
                """{"type":"equipment","name":"木剑","quantity":1,"rarity":2}]"""
        )

        // When 跑校验
        val actual = validateRepaired(saveData(mail)).data.mails.single()

        // Then 只摘弟子项，其余条目的字段与顺序原样保留
        assertTrue("仍有剩余附件，hasAttachment 必须保持 true", actual.hasAttachment)
        val kept = attachmentEntries(actual.attachments)
        assertEquals("应只剩 2 条附件", 2, kept.size)
        assertEquals("剩余附件的类型与顺序必须不变", listOf("material", "equipment"), kept.map { it.typeOf() })
        assertEquals("剩余条目字段不得被改写", "灵石", kept.first()["name"]?.jsonPrimitive?.contentOrNull)
        assertEquals("数量字段不得被改写", 1000, kept.first()["quantity"]?.jsonPrimitive?.content?.toIntOrNull())
        assertFalse("清洗后不得残留弟子字样", actual.attachments.contains("disciple"))
    }

    @Test
    fun execute_severalMails_stripsEachAndCountsTotals() {
        // Given 两封带弟子附件 + 一封干净的邮件
        val dirtyOne = mail("mail-a", true, """[{"type":"disciple","name":"甲","quantity":1,"rarity":1}]""")
        val dirtyTwo = mail(
            "mail-b",
            true,
            """[{"type":"disciple","name":"乙","quantity":1,"rarity":1},""" +
                """{"type":"pill","name":"聚气丹","quantity":3,"rarity":1}]"""
        )
        val clean = mail("mail-c", true, """[{"type":"seed","name":"灵草种","quantity":2,"rarity":1}]""")

        // When 跑校验
        val outcome = validateRepaired(saveData(dirtyOne, dirtyTwo, clean))
        val cleaned = outcome.data.mails

        // Then 逐封处理互不串扰，计数按「封 + 条」两个口径分别正确
        assertEquals("mail-a 附件应清空", "[]", cleaned[0].attachments)
        assertFalse("mail-a hasAttachment 应翻转", cleaned[0].hasAttachment)
        val keptInB = attachmentEntries(cleaned[1].attachments).map { it.typeOf() }
        assertEquals("mail-b 应只剩 pill 一条", listOf("pill"), keptInB)
        assertTrue("mail-b 仍有附件", cleaned[1].hasAttachment)
        assertSame("未命中的邮件必须原样复用同一实例", clean, cleaned[2])
        assertDetails(outcome, "从 2 封邮件中摘除 2 条弟子附件")
    }

    // ── ② 零命中不触发落盘 ────────────────────────────────────────────────────

    @Test
    fun execute_noDiscipleAttachment_passesWithContentUnchanged() {
        // Given 完全没有弟子附件的两封邮件
        val mails = listOf(
            mail("mail-clean-1", true, """[{"type":"herb","name":"灵芝","quantity":1,"rarity":2}]"""),
            mail("mail-clean-2", false, "[]")
        )

        // When 跑校验
        val input = saveData(*mails.toTypedArray())
        validatePassed(input)

        // Then Passed（无改动即不报 Repaired）；Passed 不携带数据，故入参即终态
        assertEquals(
            "无弟子附件的邮件不得被改写。落点：MailDiscipleAttachmentCleanupRule 的 Kept 分支",
            mails,
            input.mails
        )
    }

    @Test
    fun execute_secondPassOnCleanedSave_convergesToPassed() {
        // Given 已清洗过一遍的存档
        val first = validateRepaired(
            saveData(mail("mail-idem", true, """[{"type":"disciple","name":"丙","quantity":1,"rarity":1}]"""))
        )

        // When 再清洗一遍
        val second = validatePassed(first.data)

        // Then 内容收敛不再改写，第二遍不再触发落盘
        assertEquals("二次清洗内容必须收敛", first.data.mails, second.mails)
    }

    // ── ③ 不可解析附件串的兜底 ────────────────────────────────────────────────

    @Test
    fun execute_unparsableAttachmentString_keepsMailUntouchedAndCountsIt() {
        // Given 三封形态各异的问题邮件：非 JSON / 合法 JSON 但不是数组 / 正常弟子附件
        val broken = mail("mail-broken", true, "not-a-json-array")
        val objectShape = mail("mail-object", true, """{"type":"disciple","name":"丁","quantity":1,"rarity":1}""")
        val normal = mail("mail-normal", true, """[{"type":"disciple","name":"戊","quantity":1,"rarity":1}]""")

        // When 跑校验（规则绝不允许抛异常——抛出即被框架判 Corrupted、阻断读档）
        val outcome = validateRepaired(saveData(broken, objectShape, normal))
        val cleaned = outcome.data.mails

        // Then 问题邮件原样保留（未知格式不做猜测性改写），正常邮件照常被清理
        assertSame("非 JSON 邮件不得被复制改写", broken, cleaned[0])
        assertSame("非数组附件串必须原样保留", objectShape, cleaned[1])
        assertTrue("被跳过的邮件里弟子项仍在（未猜测改写）", cleaned[1].attachments.contains("disciple"))
        assertEquals("正常邮件照常被摘除", "[]", cleaned[2].attachments)
        assertDetails(outcome, "跳过 2 封附件串不可解析的邮件")
    }

    @Test
    fun execute_noMails_passes() {
        // Given 完全没有邮件的新档
        val outcome = validatePassed(saveData())

        // Then Passed 且邮件表仍为空（规则不得凭空造条目）
        assertTrue("mails 必须保持为空", outcome.mails.isEmpty())
    }

    // ── ④ 注册表与顺序 ────────────────────────────────────────────────────────

    @Test
    fun registry_defaultRules_containsCleanupRuleBetweenOrder21And23() {
        SaveValidationRuleRegistry.clear()
        SaveValidationRuleRegistry.registerDefaults()

        val rule = SaveValidationRuleRegistry.findById(RULE_ID)
        assertNotNull("registerDefaults 必须包含 id=$RULE_ID（否则存量弟子附件永不清理）", rule)
        assertSame("注册的必须是规则本体", MailDiscipleAttachmentCleanupRule, rule)
        assertEquals(
            "order 变更须同步改本断言与 SaveValidationRuleDefaults 的相邻规则注释。" +
                "落点：core/data/.../integrity/rules/SaveValidationRuleDefaults.kt",
            EXPECTED_ORDER,
            rule!!.order
        )

        // 先后关系：结构/引用校验（BattleLogRef=21）之后、玉符钳制（23）之前
        val ids = SaveValidationRuleRegistry.all.map { it.id }
        val battleLogIndex = ids.indexOf(BattleLogRefRule.id)
        val cleanupIndex = ids.indexOf(RULE_ID)
        val jadeIndex = ids.indexOf(JadeSymbolNonNegativeRule.id)
        assertTrue(
            "邮件清理必须排在 BattleLogRefRule（order=21 结构校验）之后：只改写已判合格的快照",
            battleLogIndex in 0 until cleanupIndex
        )
        assertTrue(
            "邮件清理必须排在 JadeSymbolNonNegativeRule（order=23）之前",
            cleanupIndex in 0 until jadeIndex
        )
        assertEquals("相邻规则 order 漂移：BattleLogRefRule 应为 21", 21, BattleLogRefRule.order)
        assertEquals("相邻规则 order 漂移：JadeSymbolNonNegativeRule 应为 23", 23, JadeSymbolNonNegativeRule.order)
    }

    // ── 夹具与断言辅助 ────────────────────────────────────────────────────────

    private fun mail(id: String, hasAttachment: Boolean, attachments: String): MailEntity = MailEntity(
        id = id,
        slotId = 1,
        title = "测试邮件",
        hasAttachment = hasAttachment,
        attachments = attachments
    )

    private fun saveData(vararg mails: MailEntity): SaveData = SaveData(
        gameData = GameData(sectName = "测试宗", gameYear = 1, gameMonth = 1),
        disciples = emptyList(),
        pills = emptyList(),
        materials = emptyList(),
        herbs = emptyList(),
        seeds = emptyList(),
        mails = mails.toList()
    )

    /**
     * 直呼本规则并断言结果为 Repaired（命中时必须报，否则改动不落盘），返回结果以便
     * 同时断言明细。断 Passed 说明清理被跳过，断 Corrupted 说明规则抛了异常——两者都会
     * 让存量弟子附件留在档内，均须显式判红。
     *
     * 走规则本体而非 [SaveValidator]：链上 `RecruitListCleanupRule` 自身恒 Repaired，
     * 整链结果无法区分「本规则是否报了修复」。注册关系由 registry 用例单独看护。
     */
    private fun validateRepaired(data: SaveData): RuleOutcome.Repaired {
        val result = MailDiscipleAttachmentCleanupRule.execute(data, RuleContext(data))
        assertTrue(
            "命中清理的规则必须返回 Repaired，实际为 ${result::class.simpleName}：" +
                "Corrupted 会阻断读档、Passed 说明清理被跳过。落点：MailDiscipleAttachmentCleanupRule.execute",
            result is RuleOutcome.Repaired
        )
        return result as RuleOutcome.Repaired
    }

    /**
     * 直呼本规则并断言结果为 Passed（零命中时不得报 Repaired，否则每次读档都整表替换
     * mails 并触发写盘），返回入参以便断言内容逐字段未动——Passed 不携带数据，入参即终态。
     */
    private fun validatePassed(data: SaveData): SaveData {
        val result = MailDiscipleAttachmentCleanupRule.execute(data, RuleContext(data))
        assertTrue(
            "无弟子附件且附件串均可解析时必须 Passed，实际为 ${result::class.simpleName}：" +
                "Repaired 会无谓触发 mails 整表写盘。落点：MailDiscipleAttachmentCleanupRule.execute",
            result is RuleOutcome.Passed
        )
        return data
    }

    /** 明细只校验关键计数片段（文案主体由规则单源产出）。 */
    private fun assertDetails(outcome: RuleOutcome.Repaired, vararg expectedFragments: String) {
        val details = outcome.details.joinToString("\n")
        expectedFragments.forEach { fragment ->
            assertTrue(
                "修复明细应含「$fragment」，实际=$details。" +
                    "落点：MailDiscipleAttachmentCleanupRule 的 details 拼装",
                details.contains(fragment)
            )
        }
    }

    private fun attachmentEntries(attachments: String): List<JsonObject> =
        (Json.parseToJsonElement(attachments) as JsonArray).map { it as JsonObject }

    private fun JsonObject.typeOf(): String = this["type"]?.jsonPrimitive?.contentOrNull.orEmpty()

    private companion object {
        /** 规则 id 字面量（与云存档/日志的对外契约，改名即判红） */
        const val RULE_ID = "mail_disciple_attachment_cleanup"

        /** 注册顺序期望值（见 SaveValidationRuleDefaults 的先后理由注释） */
        const val EXPECTED_ORDER = 22
    }
}
