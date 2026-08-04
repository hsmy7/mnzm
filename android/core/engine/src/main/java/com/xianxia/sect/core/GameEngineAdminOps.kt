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
    require(mailId.isNotBlank()) { "sendAdminCompensation: mailId must not be blank" }

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
}

/**
 * 向指定存档注入白名单专属福利邮件（永久有效，每档一次）。
 *
 * 白名单判定与幂等保护均在
 * [com.xianxia.sect.core.engine.service.MailService.injectWhitelistBonus]
 * 内部完成（AdFreeWhitelist + mailRecords + Room DB 三重防护）。
 *
 * @param slotId 目标存档槽位
 */
suspend fun GameEngine.sendWhitelistBonus(slotId: Int) {
    val injected = mailService.injectWhitelistBonus(slotId)
    if (injected) {
        DomainLog.i(TAG, "白名单福利已注入 slot=$slotId")
    } else {
        DomainLog.i(TAG, "白名单福利跳过注入（slotId=$slotId）")
    }
}

/**
 * 向指定存档注入单用户专属运营福利邮件（2026-09-04 截止，每档一次）。
 *
 * 用户判定与幂等保护均在
 * [com.xianxia.sect.core.engine.service.MailService.injectExclusiveBonus]
 * 内部完成（目标 unionId + mailRecords + Room DB 三重防护）。
 *
 * @param slotId 目标存档槽位
 */
suspend fun GameEngine.sendExclusiveBonus(slotId: Int) {
    val injected = mailService.injectExclusiveBonus(slotId)
    if (injected) {
        DomainLog.i(TAG, "专属福利已注入 slot=$slotId")
    } else {
        DomainLog.i(TAG, "专属福利跳过注入（slotId=$slotId）")
    }
}
