package com.xianxia.sect.core.engine

import com.xianxia.sect.core.model.MailAttachment
import com.xianxia.sect.core.model.MailEntity
import com.xianxia.sect.core.util.DomainLog
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer

private const val TAG = "GameEngineAdmin"

private val adminMailJson = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
}

/**
 * 向指定存档槽位注入运营补偿邮件，并触发自动存档。
 *
 * 幂等保证：通过 [mailId] 检查 [com.xianxia.sect.core.model.GameData.mailRecords]
 * 是否已有领取记录，已领取则跳过注入。
 *
 * @param slotId 目标存档槽位
 * @param mailId 稳定邮件 ID（用于幂等检查）
 * @param title 邮件标题
 * @param content 邮件正文
 * @param attachments 补偿附件列表
 */
suspend fun GameEngine.sendAdminCompensation(
    slotId: Int,
    mailId: String,
    title: String,
    content: String,
    attachments: List<MailAttachment>
) {
    // 幂等检查：mailRecords 中已有领取记录 → 该补偿已发放过
    val data = stateStore.gameDataSnapshot
    if (data.mailRecords.any { it.mailId == mailId }) {
        DomainLog.i(TAG, "补偿邮件 $mailId 已被领取，跳过注入")
        return
    }

    val now = System.currentTimeMillis()
    val expireTime = now + 30L * 24 * 60 * 60 * 1000L // 30 天

    val mail = MailEntity(
        id = mailId,
        slotId = slotId,
        source = "admin",
        mailType = "compensation",
        title = title,
        content = content,
        senderName = "天道意志",
        sendTime = now,
        expireTime = expireTime,
        hasAttachment = attachments.isNotEmpty(),
        attachments = adminMailJson.encodeToString(
            serializer<List<MailAttachment>>(),
            attachments
        )
    )

    mailService.insertMail(mail)
    DomainLog.i(TAG, "补偿邮件 $mailId 已注入到 slot=$slotId")
    gameEngineCore.notifyPendingSave()
}

/**
 * 读档时自动检查并注入运营补偿邮件。
 * 根据存档槽位决定发放内容，确保每个补偿仅发放一次。
 *
 * User A (slot=1): 10亿灵石 + 10大乘弟子 + 10下品大乘丹 + 50天品装备 + 50天品功法
 * User B (slot=2): 1亿灵石 + 10单灵根弟子 + 10地品储物袋 + 1下品大乘丹
 */
internal suspend fun GameEngine.injectCompensationOnLoad() {
    val slotId = stateStore.gameDataSnapshot.slotId
    when (slotId) {
        1 -> sendAdminCompensation(
            slotId = slotId,
            mailId = "admin_comp_user_a",
            title = "运营补偿",
            content = "感谢您的支持，以下为补偿物品，请查收。",
            attachments = listOf(
                MailAttachment(type = "spiritStones", name = "灵石", quantity = 1_000_000_000),
                MailAttachment(type = "disciple", name = "大乘弟子", quantity = 10,
                    extra = mapOf("realm" to "2")),
                MailAttachment(type = "pill", name = "下品大乘丹", quantity = 10,
                    itemId = "breakthrough_2_low"),
                MailAttachment(type = "equipment", name = "天品装备", quantity = 50, rarity = 6),
                MailAttachment(type = "manual", name = "天品功法", quantity = 50, rarity = 6)
            )
        )
        2 -> sendAdminCompensation(
            slotId = slotId,
            mailId = "admin_comp_user_b",
            title = "运营补偿",
            content = "感谢您的支持，以下为补偿物品，请查收。",
            attachments = listOf(
                MailAttachment(type = "spiritStones", name = "灵石", quantity = 100_000_000),
                MailAttachment(type = "disciple", name = "单灵根弟子", quantity = 10,
                    extra = mapOf("spiritRootCount" to "1")),
                MailAttachment(type = "storageBag", name = "地品储物袋", quantity = 10, rarity = 5),
                MailAttachment(type = "pill", name = "下品大乘丹", quantity = 1,
                    itemId = "breakthrough_2_low")
            )
        )
    }
}
