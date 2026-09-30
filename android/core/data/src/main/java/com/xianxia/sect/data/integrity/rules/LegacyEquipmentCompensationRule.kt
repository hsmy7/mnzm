package com.xianxia.sect.data.integrity.rules

import android.util.Log
import com.xianxia.sect.core.model.EquipmentStack
import com.xianxia.sect.core.model.LegacyEquipmentPrices
import com.xianxia.sect.core.model.MailAttachment
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
 * 旧装备折算补偿（装备重构 B3 / R2，方案 §5.4）。
 *
 * 旧装备（四槽体系 72 模板）已全部作废；存量资产按 **basePrice 100%** 折算
 * 灵石（实例按 `basePrice × (1 + 0.5 × 已孕养等级/品阶满级)`），汇总后以单封
 * 补偿邮件发放。数据源四路：
 * - `SaveData.equipmentStacks`（@Deprecated 载体）：仓库堆叠 + 影子表搬运行
 *   （`legacy_equipment_stacks` 直映 + `legacy_equipment_instances` 一行一件携带
 *   孕养等级——见 [MIGRATION_63_64] KDoc 第①步）；
 * - 弟子储物袋内旧装备条目（itemType `equipment`/`equipment_stack`/
 *   `equipment_instance` 引用式条目）；
 * - 未领取邮件附件 `type="equipment"`；
 * - 秘境背包 `secretRealmState.backpack.equipment`。
 *
 * - 价格取退役时刻模板的**静态快照表**（[EquipmentStack.basePrice] →
 *   `LegacyEquipmentPrices`；模板已删，不依赖运行时模板）；
 * - 单档补偿上限 **1 亿灵石**，超出按比例截断并记日志；
 * - 幂等：[com.xianxia.sect.core.model.GameData.legacyEquipmentCompensated]
 *   标记与全部改写/发放动作落在**同一份** [RuleOutcome.Repaired] 快照上
 *   （同事务落盘）；标记已置位时恒 [RuleOutcome.Passed]。
 *
 * 规则必须零抛异常——抛异常会被框架转为 Corrupted，阻断读档。
 */
object LegacyEquipmentCompensationRule : SaveValidationRule {
    override val id = "equipment_legacy_compensation"
    override val order = 27

    private const val TAG = "LegacyEquipmentCompRule"

    /** 单档补偿上限（灵石）——方案 §5.4 */
    const val COMPENSATION_CAP = 100_000_000L

    /**
     * 补偿邮件来源键（OverflowMailSender.SOURCE_DISPLAY_NAMES 同步登记）。
     * 注意：[buildCompensationMail] 的 `source` 形参须沿用**同名键字面量**——
     * OverflowMailSenderTest 反向守卫按 `source = "..."` 字面量扫描来源点，
     * 经常量间接（`source = SOURCE_KEY`）会让守卫误判该键无生产使用。
     */
    const val SOURCE_KEY = "equipment_legacy_compensation"

    /** 旧孕养等级上限表（rarity 1..6，退役时刻 EquipmentNurtureSystem 口径快照） */
    private val LEGACY_MAX_NURTURE_LEVELS = intArrayOf(5, 9, 13, 17, 21, 25)

    /** 储物袋内旧装备条目类型（引用式条目；equipment_instance 已物化的独立存储不计） */
    private val LEGACY_BAG_ITEM_TYPES = setOf("equipment", "equipment_stack")

    private val mailJson = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    /** 实例折算系数：`1 + 0.5 × 已孕养等级/品阶满级`（方案 §5.4 表） */
    fun instanceMultiplier(nurtureLevel: Int, rarity: Int): Double {
        if (nurtureLevel <= 0) return 1.0
        val maxLevel = LEGACY_MAX_NURTURE_LEVELS.getOrElse((rarity - 1).coerceIn(0, 5)) { 25 }
        return 1.0 + 0.5 * nurtureLevel.toDouble() / maxLevel
    }

    /** 单件折算额（灵石；堆叠传 quantity，实例恒 1；孕养系数向下取整收敛） */
    fun stackValue(stack: EquipmentStack): Long =
        (stack.basePrice.toLong() * stack.quantity * instanceMultiplier(stack.nurtureLevel, stack.rarity)).toLong()

    @Suppress("LongMethod") // 仓库堆叠/储物袋/秘境背包/邮件附件四路折算平铺，拆分会遮蔽资产清点完整性
    override fun execute(data: SaveData, context: RuleContext): RuleOutcome {
        val gd = data.gameData
        if (gd.legacyEquipmentCompensated) return RuleOutcome.Passed

        var total = 0L
        var assetCount = 0

        // ① 仓库堆叠 + 影子搬运行（deprecated 载体，读后即清）
        val legacyStacks = data.equipmentStacks
        for (stack in legacyStacks) {
            total += stackValue(stack)
            assetCount += 1
        }

        // ② 弟子储物袋内旧装备条目（引用式；已物化的 equipmentInstance 独立存储条目不动
        //    ——其装备实例属新实例轨，B3 后新模型；旧档未物化条目按堆叠口径折算摘除）
        var bagRemoved = 0
        val keptDisciples = data.disciples.map { d ->
            val bag = d.equipment.storageBagItems
            if (bag.none { it.itemType in LEGACY_BAG_ITEM_TYPES }) return@map d
            val keptBag = bag.filter { item ->
                if (item.itemType in LEGACY_BAG_ITEM_TYPES) {
                    total += LegacyEquipmentPrices.priceOf(item.name, item.rarity).toLong() * item.quantity
                    assetCount += 1
                    bagRemoved += 1
                    false
                } else true
            }
            d.copy(equipment = d.equipment.copy(storageBagItems = keptBag))
        }

        // ③ 秘境背包装备（挂在 ExplorationSession）
        val realmBackpack = gd.secretRealmSession.backpack
        var realmRemoved = 0
        for (stack in realmBackpack.equipment) {
            total += stackValue(stack)
            assetCount += 1
            realmRemoved += 1
        }
        val keptSession = if (realmBackpack.equipment.isEmpty()) {
            gd.secretRealmSession
        } else {
            gd.secretRealmSession.copy(
                backpack = realmBackpack.copy(equipment = emptyList())
            )
        }

        // ④ 未领取邮件附件（type="equipment"）
        var mailRemoved = 0
        val keptMails = stripMailAttachments(data.mails) { quantity, price ->
            total += price * quantity
            assetCount += 1
            mailRemoved += 1
        }

        // 无存量资产：不落盘、不置标记（新档/已消费档恒 Passed 零成本，B2 先例）
        if (assetCount == 0) return RuleOutcome.Passed

        // 单档上限截断：超出按比例截断（发放 = 总额 × min(1, 上限/总额)）并记日志
        val compensated = if (total > COMPENSATION_CAP) {
            Log.w(TAG, "旧装备补偿 $total 超单档上限 $COMPENSATION_CAP，按比例截断发放")
            COMPENSATION_CAP
        } else {
            total
        }

        return RuleOutcome.Repaired(
            data.copy(
                gameData = gd.copy(
                    secretRealmSession = keptSession,
                    legacyEquipmentCompensated = true
                ),
                disciples = keptDisciples,
                equipmentStacks = emptyList(),
                mails = keptMails + buildCompensationMail(data, compensated)
            ),
            listOf(
                "旧装备 $assetCount 件已折算（堆叠 ${legacyStacks.size}/储物袋 $bagRemoved/" +
                    "秘境 $realmRemoved/邮件 $mailRemoved）",
                "补偿灵石 $compensated 已随补偿邮件发放"
            )
        )
    }

    /** 未领取邮件附件折算 + 摘除（附件串为 JSON 数组；不可解析原样保留） */
    private fun stripMailAttachments(
        mails: List<MailEntity>,
        onStrip: (quantity: Int, unitPrice: Long) -> Unit
    ): List<MailEntity> =
        mails.map { mail ->
            when (val strip = stripEquipmentAttachments(mail, onStrip)) {
                is AttachmentStrip.Kept -> mail
                is AttachmentStrip.Stripped -> if (strip.newJson == null) {
                    mail.copy(attachments = "[]", hasAttachment = false)
                } else {
                    mail.copy(attachments = strip.newJson)
                }
            }
        }

    private sealed interface AttachmentStrip {
        data object Kept : AttachmentStrip
        data class Stripped(val newJson: String?) : AttachmentStrip
    }

    /** 从附件 JSON 串中摘除装备附件并折算；无装备附件返回 [AttachmentStrip.Kept] */
    @Suppress("TooGenericExceptionCaught") // 防御兜底: 附件串为历史落库字符串, 异常源为数据形态而非程序错误, 原样保留+日志留痕, 非静默吞噬
    private fun stripEquipmentAttachments(
        mail: MailEntity,
        onStrip: (quantity: Int, unitPrice: Long) -> Unit
    ): AttachmentStrip {
        val element: JsonElement = try {
            mailJson.parseToJsonElement(mail.attachments)
        } catch (e: Exception) {
            Log.w(TAG, "邮件 ${mail.id} 附件串不可解析，装备附件清理跳过该邮件", e)
            return AttachmentStrip.Kept
        }
        if (element !is JsonArray) return AttachmentStrip.Kept

        var removedCount = 0
        val kept = ArrayList<JsonElement>(element.size)
        for (item in element) {
            val obj = item as? JsonObject
            val type = (obj?.get("type") as? JsonPrimitive)?.contentOrNull
            val name = (obj?.get("name") as? JsonPrimitive)?.contentOrNull ?: ""
            val rarity = (obj?.get("rarity") as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: 1
            val quantity = (obj?.get("quantity") as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: 1
            if (type == "equipment") {
                onStrip(quantity, LegacyEquipmentPrices.priceOf(name, rarity).toLong())
                removedCount += 1
            } else {
                kept.add(item)
            }
        }
        if (removedCount == 0) return AttachmentStrip.Kept
        return AttachmentStrip.Stripped(
            newJson = if (kept.isEmpty()) null else mailJson.encodeToString(
                ListSerializer(JsonElement.serializer()),
                kept
            )
        )
    }

    /** 构造补偿邮件（永久有效；领取路径见 MailAttachmentDistributeOps "spiritStones" 分支） */
    private fun buildCompensationMail(data: SaveData, amount: Long): MailEntity {
        val slotId = data.mails.firstOrNull()?.slotId ?: 0
        return MailEntity(
            id = "equipment_legacy_compensation_${data.timestamp}",
            slotId = slotId,
            source = "equipment_legacy_compensation", // 同名键字面量（守卫可见，值 = SOURCE_KEY）
            mailType = "compensation",
            title = "装备体系更新补偿",
            content = "装备体系已全面更新为六部位套装。" +
                "您存量的旧装备已按原价折算为灵石随信附上，感谢理解。",
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
}
