package com.xianxia.sect.data.integrity.rules

import android.util.Log
import com.xianxia.sect.core.model.MailEntity
import com.xianxia.sect.data.model.SaveData
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * 邮件弟子附件下线：存量附件摘除规则。
 *
 * 「邮件直造弟子」的发放与领取分支均已不存在，更早版本落库的存档里仍可能有
 * `type == "disciple"` 的未领取附件（[MailEntity.attachments] 是 JSON 字符串，
 * 邮件附件的唯一持久化面就是 [SaveData.mails]）。这类附件留在档内会让玩家点领取
 * 落到未知类型分支、邮件永久卡住，故在校验边界把它从每封邮件里摘除。
 *
 * 命中（摘除过附件）或存在不可解析附件串时返回 [RuleOutcome.Repaired]，读档路径据此
 * 替换内存快照、保存路径的 `mails` 表整对象替换据此写回清洗后的附件串；两者都没有时
 * 返回 [RuleOutcome.Passed]——mails 与入参逐条相同，报 Repaired 只会让每次读档无谓
 * 整表替换并触发写盘（[RecruitListCleanupRule] 的恒 Repaired 不同：它确实在改表内容）。
 *
 * 改写只针对被摘除的条目：其余附件的字段与顺序保留（剔除后重新序列化）；
 * 附件串不可解析的邮件原样保留并在明细中如实计数（未知格式不做猜测性改写）。
 *
 * 规则必须零抛异常——抛异常会被框架转为 Corrupted，阻断读档。
 */
object MailDiscipleAttachmentCleanupRule : SaveValidationRule {
    override val id = "mail_disciple_attachment_cleanup"
    override val order = 22

    override fun execute(data: SaveData, context: RuleContext): RuleOutcome {
        var cleanedMailCount = 0
        var removedAttachmentCount = 0
        var unparsableMailCount = 0

        val cleanedMails = data.mails.map { mail ->
            when (val strip = mail.stripDiscipleAttachments()) {
                is AttachmentStrip.Kept -> mail
                is AttachmentStrip.Unparsable -> {
                    unparsableMailCount++
                    mail
                }
                is AttachmentStrip.Stripped -> {
                    cleanedMailCount++
                    removedAttachmentCount += strip.removedCount
                    strip.mail
                }
            }
        }

        if (cleanedMailCount == 0 && unparsableMailCount == 0) {
            // 零命中：mails 与入参逐条相同，没有需要落盘的改动。报 Repaired 会让每次
            // 读档都整表替换 mails 并触发写盘，还把「无改动」混进修复明细。
            return RuleOutcome.Passed
        }

        val details = mutableListOf<String>()
        if (cleanedMailCount > 0) {
            details.add(
                "弟子邮件附件已下线：从 $cleanedMailCount 封邮件中摘除 $removedAttachmentCount 条弟子附件"
            )
        }
        if (unparsableMailCount > 0) {
            details.add("弟子邮件附件清理跳过 $unparsableMailCount 封附件串不可解析的邮件")
        }

        return RuleOutcome.Repaired(data.copy(mails = cleanedMails), details)
    }
}

/** 单封邮件的附件清洗结果。 */
private sealed interface AttachmentStrip {
    /** 无需改写（无弟子附件，或附件串不可解析时由 [Unparsable] 单独表达） */
    data object Kept : AttachmentStrip

    /** 附件串不是 JSON 数组：原样保留 */
    data object Unparsable : AttachmentStrip

    /**
     * 已摘除弟子附件。
     * @param mail 改写后的邮件（附件串按剩余条目重新序列化，剩余为空时 hasAttachment 置 false）
     * @param removedCount 本次摘除的附件条数
     */
    data class Stripped(val mail: MailEntity, val removedCount: Int) : AttachmentStrip
}

/**
 * 摘除本邮件附件中的弟子条目。
 *
 * 解析失败不抛出：附件串来自历史版本落库内容，格式异常时保持原样比猜测改写更安全。
 */
@Suppress("TooGenericExceptionCaught") // 防御兜底: 附件串为历史落库字符串, 异常源为数据形态而非程序错误, 原样保留+明细留痕, 非静默吞噬
private fun MailEntity.stripDiscipleAttachments(): AttachmentStrip {
    val parsed = try {
        Json.parseToJsonElement(attachments)
    } catch (e: Exception) {
        Log.w(TAG, "邮件 $id 附件串不可解析，弟子附件清理跳过该邮件", e)
        return AttachmentStrip.Unparsable
    }
    val entries = parsed as? JsonArray ?: return AttachmentStrip.Unparsable
    val kept = entries.filterNot { it.isDiscipleAttachment() }
    if (kept.size == entries.size) return AttachmentStrip.Kept

    val keptJson = JsonArray(kept).toString()
    return AttachmentStrip.Stripped(
        copy(
            attachments = keptJson,
            hasAttachment = kept.isNotEmpty()
        ),
        removedCount = entries.size - kept.size
    )
}

/** 附件条目是否为已下线的弟子类型。 */
private fun JsonElement.isDiscipleAttachment(): Boolean {
    val type = (this as? JsonObject)?.get(ATTACHMENT_TYPE_KEY) as? JsonPrimitive ?: return false
    return type.contentOrNull == DISCIPLE_ATTACHMENT_TYPE
}

/** 附件 JSON 中标识类型的字段名。 */
private const val ATTACHMENT_TYPE_KEY = "type"

/** 已下线的弟子附件类型值。 */
private const val DISCIPLE_ATTACHMENT_TYPE = "disciple"

/** 本规则日志标签。 */
private const val TAG = "MailDiscipleAttachmentCleanup"
