package com.xianxia.sect.core.engine.service

import com.xianxia.sect.core.model.MailAttachment
import com.xianxia.sect.core.model.MailEntity
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer

/**
 * 天枢殿重建补偿邮件。
 *
 * 旧档遗留天枢殿（占地尺寸命中历史白名单，见
 * [com.xianxia.sect.core.engine.TIANSHU_LEGACY_FOOTPRINTS] 与
 * [com.xianxia.sect.core.engine.filterLegacyTianshuHalls]）读档时直接删除，
 * 通过本邮件补偿玩家 1000 万灵石（用户指定金额）。
 * 独立文件保持 BootSequenceController 规模稳定（一次性迁移补偿，非持续邮件源）。
 */

/** 补偿邮件稳定 ID（天枢殿全局唯一 + 删除后不再触发 → 天然幂等） */
private const val TIANSHU_COMPENSATION_MAIL_ID = "tianshu_hall_rebuild_compensation_v1"

/** 补偿灵石数量：1000 万（用户指定） */
internal const val TIANSHU_COMPENSATION_SPIRIT_STONES = 10_000_000

/** 补偿邮件有效期：7 天 */
private const val TIANSHU_COMPENSATION_EXPIRE_MS = 7L * 24 * 60 * 60 * 1000

private val tianshuCompensationJson = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
}

/**
 * 构造天枢殿重建补偿邮件（灵石 1000 万附件）。
 *
 * @param slotId 目标存档槽位
 * @return 邮件实体（mailType=compensation，附件为下品灵石 10,000,000）
 */
internal fun buildTianshuCompensationMail(slotId: Int): MailEntity {
    val now = System.currentTimeMillis()
    return MailEntity(
        id = TIANSHU_COMPENSATION_MAIL_ID,
        slotId = slotId,
        source = "system",
        mailType = "compensation",
        title = "天枢殿重建补偿",
        content = "宗门天枢殿已完成全面重建，原有的天枢殿已拆除。为感谢您的支持，特补偿灵石 1000 万" +
            "（10,000,000），请查收。重建后的天枢殿可在建造界面重新建造。\n\n——天道意志",
        senderName = "天道意志",
        sendTime = now,
        expireTime = now + TIANSHU_COMPENSATION_EXPIRE_MS,
        hasAttachment = true,
        attachments = tianshuCompensationJson.encodeToString(
            serializer<List<MailAttachment>>(),
            listOf(
                MailAttachment(
                    type = "spiritStones",
                    name = "灵石",
                    quantity = TIANSHU_COMPENSATION_SPIRIT_STONES,
                    rarity = 1
                )
            )
        )
    )
}
