package com.xianxia.sect.core.engine.service

import com.xianxia.sect.core.model.MailAttachment
import com.xianxia.sect.core.model.MailEntity
import com.xianxia.sect.core.util.ItemNames
import kotlinx.serialization.encodeToString
import kotlinx.serialization.serializer

// ── 专属福利邮件域（自 MailService 拆出，行为零变更） ──

// companion 常量/JSON 实例文件级别名（扩展作用域不可直接引用 companion 成员；行为零变更）
private const val EXCLUSIVE_BONUS_MAIL_ID = MailService.EXCLUSIVE_BONUS_MAIL_ID
private const val EXCLUSIVE_BONUS_UNION_ID = MailService.EXCLUSIVE_BONUS_UNION_ID
private const val EXCLUSIVE_BONUS_SPIRIT_STONES = MailService.EXCLUSIVE_BONUS_SPIRIT_STONES
private const val EXCLUSIVE_BONUS_DISCIPLE_COUNT = MailService.EXCLUSIVE_BONUS_DISCIPLE_COUNT
private const val EXCLUSIVE_BONUS_EXPIRE_MS = MailService.EXCLUSIVE_BONUS_EXPIRE_MS
private val json = MailService.json

/** 构造专属福利邮件附件：1000 万灵石 + 10 名单灵根弟子 */
internal fun MailService.buildExclusiveBonusAttachments(): List<MailAttachment> {
    return listOf(
        MailAttachment(
            type = "spiritStones",
            name = ItemNames.SPIRIT_STONE,
            quantity = EXCLUSIVE_BONUS_SPIRIT_STONES
        ),
        MailAttachment(
            type = "disciple",
            name = "单灵根弟子",
            quantity = EXCLUSIVE_BONUS_DISCIPLE_COUNT,
            extra = mapOf("spiritRootCount" to "1")
        )
    )
}

/** 构造专属福利邮件实体（附件 JSON 序列化 + 截止时间） */
internal fun MailService.buildExclusiveBonusMail(slotId: Int): MailEntity {
    return MailEntity(
        id = EXCLUSIVE_BONUS_MAIL_ID,
        slotId = slotId,
        source = "admin",
        mailType = "reward",
        title = "专属修士礼包",
        content = "恭喜获得专属福利：灵石 ×10,000,000、单灵根弟子 ×10。" +
            "有效期至 2026年9月4日，每个存档仅可领取一次。\n\n——天道意志",
        senderName = "天道意志",
        sendTime = timeSource(),
        expireTime = EXCLUSIVE_BONUS_EXPIRE_MS,
        hasAttachment = true,
        attachments = json.encodeToString(
            serializer<List<MailAttachment>>(),
            buildExclusiveBonusAttachments()
        )
    )
}
