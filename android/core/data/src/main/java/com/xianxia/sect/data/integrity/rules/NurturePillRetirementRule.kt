package com.xianxia.sect.data.integrity.rules

import android.util.Log
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.MailAttachment
import com.xianxia.sect.core.model.Pill
import com.xianxia.sect.core.model.MailEntity
import com.xianxia.sect.data.model.SaveData
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * 孕养类加成丹药（R11）退役：存量资产折算补偿 + 引用清理规则。
 *
 * `nurtureSpeed_*` / `nurtureAdd_*` 两族丹药的定义与效果链已整体删除
 * （模板/配方/引擎写入点/C++ 配方），旧存档可能仍有存量：
 * - 仓库丹药堆叠（[SaveData.pills]）；
 * - 弟子储物袋条目（[com.xianxia.sect.core.model.Disciple] equipment.storageBagItems）；
 * - 未领取的含孕养丹邮件附件（[SaveData.mails]，attachments 为 JSON 串）；
 * - 已解锁配方（`GameData.unlockedRecipes`——不清会导致配方页出现无法炼制的死条目）。
 *
 * 处置（方案 §5.7）：
 * - 持有丹药按**丹药原价 100%** 折算灵石，汇总后以单封补偿邮件发放
 *   （source="nurture_pill_retirement"，永久有效）；
 * - 价格取自退役时刻模板的**静态快照表**（模板已删，不依赖运行时模板）；
 * - 弟子身上生效中的孕养速度加成不补偿（pillNurtureSpeedBonus 列在
 *   MIGRATION_62_63 随列删除）；
 * - 单档补偿上限 **2000 万灵石**，超出按比例截断并记日志；
 * - 幂等：[com.xianxia.sect.core.model.GameData.nurturePillsRetired] 标记与全部
 *   改写/发放动作落在**同一份** [RuleOutcome.Repaired] 快照上（同事务落盘）；
 *   标记已置位时恒 [RuleOutcome.Passed]。
 *
 * 规则必须零抛异常——抛异常会被框架转为 Corrupted，阻断读档。
 */
object NurturePillRetirementRule : SaveValidationRule {
    override val id = "nurture_pill_retirement"
    override val order = 26

    private const val TAG = "NurturePillRetirementRule"

    /** 单档补偿上限（灵石）——方案 §5.7 */
    const val COMPENSATION_CAP = 20_000_000L

    /** 孕养丹 id 前缀（nurtureSpeed_{tier}_{grade} / nurtureAdd_{tier}_{grade}） */
    private val RETIRED_ID_PREFIXES = listOf("nurtureSpeed_", "nurtureAdd_")

    /** 退役时刻价格快照：tier(1..6) → 模板基础价（= Rarity.pillBasePrice，rarity==tier） */
    private val TIER_BASE_PRICES = longArrayOf(
        4_000L, 16_000L, 80_000L, 480_000L, 3_360_000L, 26_880_000L
    )

    /** 品阶价格乘数（PillGrade.priceMultiplier 快照） */
    private val GRADE_MULTIPLIERS = mapOf("low" to 0.5, "medium" to 1.0, "high" to 2.0)

    private val mailJson = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    /**
     * 退役丹药 id → 原价（灵石）。id 不在快照内返回 null（非孕养丹或非法 id）。
     */
    fun retiredPillPrice(itemId: String): Long? {
        val tier = itemId.takeIf { id -> RETIRED_ID_PREFIXES.any(id::startsWith) }
            ?.substringAfter('_')?.substringBefore('_')?.toIntOrNull()
            ?.takeIf { it in 1..TIER_BASE_PRICES.size }
            ?: return null
        val mult = GRADE_MULTIPLIERS[itemId.substringAfterLast('_')] ?: return null
        return (TIER_BASE_PRICES[tier - 1] * mult).toLong()
    }

    private fun isRetiredPillId(itemId: String): Boolean = retiredPillPrice(itemId) != null

    override fun execute(data: SaveData, context: RuleContext): RuleOutcome {
        val gd = data.gameData
        if (gd.nurturePillsRetired) return RuleOutcome.Passed

        val refund = RefundLedger()
        val keptPills = stripWarehousePills(data.pills, refund)
        val keptDisciples = stripStorageBags(data.disciples, refund)
        val keptMails = stripMailAttachments(data.mails, refund)
        val keptRecipes = stripUnlockedRecipes(gd.unlockedRecipes, refund)
        // 无存量资产：不落盘、不置标记（退役后新代码不再产出孕养丹，扫描恒空
        // ⇒ 每次 Passed 零成本；报 Repaired 只会让每次读档无谓整表写盘，
        // MailDiscipleAttachmentCleanupRule 同语义先例）
        if (refund.assetCount == 0) return RuleOutcome.Passed

        // 单档上限截断：超出按比例截断（发放 = 总额 × min(1, 上限/总额)）并记日志
        val compensated = if (refund.total > COMPENSATION_CAP) {
            Log.w(TAG, "孕养丹退役补偿 ${refund.total} 超单档上限 $COMPENSATION_CAP，按比例截断发放")
            COMPENSATION_CAP
        } else {
            refund.total
        }

        return RuleOutcome.Repaired(
            data.copy(
                gameData = gd.copy(
                    unlockedRecipes = keptRecipes,
                    nurturePillsRetired = true
                ),
                pills = keptPills,
                disciples = keptDisciples,
                mails = keptMails + buildCompensationMail(data, compensated)
            ),
            refund.details() + listOf("补偿灵石 $compensated 已随补偿邮件发放")
        )
    }

    /** 折算累计账本（四类资产共用；total=灵石合计，assetCount=资产条数） */
    private class RefundLedger {
        var total = 0L
            private set
        var assetCount = 0
            private set
        private val lines = mutableListOf<String>()

        /** 折算一笔（数量 × 退役单价） */
        fun add(quantity: Int, unitPrice: Long) {
            total += unitPrice * quantity
            assetCount += 1
        }

        fun note(line: String) = lines.add(line)

        fun details(): List<String> = lines.toList()

        /** 仓库丹药堆叠折算 + 移除 */
        fun stripWarehousePills(pills: List<Pill>): List<Pill> = pills.filter { pill ->
            val price = retiredPillPrice(pill.id) ?: return@filter true
            add(pill.quantity, price)
            false
        }

        /** 弟子储物袋条目折算 + 移除 */
        fun stripStorageBags(disciples: List<Disciple>): List<Disciple> = disciples.map { d ->
            val bag = d.equipment.storageBagItems
            if (bag.none { isRetiredPillId(it.itemId) }) return@map d
            d.copy(equipment = d.equipment.copy(storageBagItems = bag.filter { item ->
                val price = retiredPillPrice(item.itemId) ?: return@filter true
                add(item.quantity, price)
                false
            }))
        }

        /** 已解锁配方回滚（防配方页死条目） */
        fun stripRecipes(recipes: List<String>): List<String> = recipes.filter { recipeId ->
            if (!isRetiredPillId(recipeId)) {
                true
            } else {
                assetCount += 1
                false
            }
        }
    }

    /** 仓库堆叠折算（账本化明细） */
    private fun stripWarehousePills(pills: List<Pill>, refund: RefundLedger): List<Pill> =
        refund.stripWarehousePills(pills).also {
            val removed = pills.size - it.size
            if (removed > 0) refund.note("仓库孕养丹 $removed 条已折算回收")
        }

    /** 储物袋条目折算（账本化明细） */
    private fun stripStorageBags(disciples: List<Disciple>, refund: RefundLedger): List<Disciple> =
        refund.stripStorageBags(disciples).also { kept ->
            val removed = disciples.sumOf { it.equipment.storageBagItems.size } -
                kept.sumOf { it.equipment.storageBagItems.size }
            if (removed > 0) refund.note("储物袋孕养丹 $removed 条已折算回收")
        }

    /** 未领取邮件附件折算 + 摘除（附件串为 JSON 数组；不可解析原样保留） */
    private fun stripMailAttachments(mails: List<MailEntity>, refund: RefundLedger): List<MailEntity> =
        mails.map { mail ->
            when (val strip = stripNurtureAttachments(mail, refund)) {
                is AttachmentStrip.Kept -> mail
                is AttachmentStrip.Stripped -> if (strip.newJson == null) {
                    mail.copy(attachments = "[]", hasAttachment = false)
                } else {
                    mail.copy(attachments = strip.newJson)
                }
            }
        }

    /** 已解锁配方回滚（账本化明细） */
    private fun stripUnlockedRecipes(recipes: List<String>, refund: RefundLedger): List<String> =
        refund.stripRecipes(recipes).also {
            val removed = recipes.size - it.size
            if (removed > 0) refund.note("已解锁孕养丹配方 $removed 条已回滚")
        }

    /** 构造补偿邮件（永久有效；领取路径见 MailAttachmentDistributeOps "spiritStones" 分支） */
    private fun buildCompensationMail(data: SaveData, amount: Long): MailEntity {
        // SaveData 不携带槽位上下文：跟随存量邮件的 slotId（mails 表按槽位整对象替换回写）
        val slotId = data.mails.firstOrNull()?.slotId ?: 0
        return MailEntity(
            id = "nurture_pill_retirement_${data.timestamp}",
            slotId = slotId,
            // 来源键字面量直写（OverflowMailSenderTest 反向守卫按字面量核对来源点）
            source = "nurture_pill_retirement",
            mailType = "compensation",
            title = "孕养丹药退役补偿",
            content = "装备体系更新后，孕养类丹药已退役。" +
                "您存量的孕养丹药已按原价折算为灵石随信附上，感谢理解。",
            senderName = "天道意志",
            sendTime = data.timestamp,
            expireTime = Long.MAX_VALUE,
            hasAttachment = true,
            attachments = mailJson.encodeToString(
                ListSerializer(MailAttachment.serializer()),
                listOf(
                    MailAttachment(
                        type = "spiritStones",
                        name = "下品灵石",
                        quantity = amount.toInt(),
                        rarity = 1
                    )
                )
            )
        )
    }

    /** 单封邮件的孕养附件清洗结果 */
    private sealed interface AttachmentStrip {
        /** 无孕养附件（原样保留） */
        data object Kept : AttachmentStrip

        /** 摘除过孕养附件（newJson=null 表示附件已清空） */
        data class Stripped(val newJson: String?) : AttachmentStrip
    }

    /**
     * 从附件 JSON 串中摘除孕养丹附件并折算；无孕养附件返回 [AttachmentStrip.Kept]。
     *
     * 解析失败不抛出：附件串来自历史版本落库内容，格式异常时保持原样比猜测改写更安全。
     */
    @Suppress("TooGenericExceptionCaught") // 防御兜底: 附件串为历史落库字符串, 异常源为数据形态而非程序错误, 原样保留+日志留痕, 非静默吞噬
    private fun stripNurtureAttachments(mail: MailEntity, refund: RefundLedger): AttachmentStrip {
        val element: JsonElement = try {
            mailJson.parseToJsonElement(mail.attachments)
        } catch (e: Exception) {
            Log.w(TAG, "邮件 ${mail.id} 附件串不可解析，孕养附件清理跳过该邮件", e)
            return AttachmentStrip.Kept
        }
        if (element !is JsonArray) return AttachmentStrip.Kept

        var removedCount = 0
        val kept = ArrayList<JsonElement>(element.size)
        for (item in element) {
            val obj = item as? JsonObject
            val type = (obj?.get("type") as? JsonPrimitive)?.contentOrNull
            val itemId = (obj?.get("itemId") as? JsonPrimitive)?.contentOrNull
            val quantity = (obj?.get("quantity") as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: 1
            val price = itemId?.let { retiredPillPrice(it) }
            if (type == "pill" && price != null) {
                refund.add(quantity, price)
                removedCount += 1
            } else {
                kept.add(item)
            }
        }
        if (removedCount == 0) return AttachmentStrip.Kept
        refund.note("邮件孕养丹附件 $removedCount 条已折算回收")
        return AttachmentStrip.Stripped(
            newJson = if (kept.isEmpty()) null else mailJson.encodeToString(
                ListSerializer(JsonElement.serializer()),
                kept
            )
        )
    }
}
