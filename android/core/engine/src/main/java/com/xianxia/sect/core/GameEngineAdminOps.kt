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
 * 向指定存档注入一次性运营补偿邮件（用户ID校验 + 仅限一个存档）。
 *
 * 由 [SaveLoadViewModel] 在游戏加载完成后调用，通过用户 ID 判断目标用户，
 * 通过 SharedPreferences 标志控制全局仅一个存档可领取。
 *
 * @param slotId 目标存档槽位
 * @param userId 当前登录用户 ID（NULL 时不注入）
 */
suspend fun GameEngine.sendDirectCompensation(slotId: Int, userId: String?) {
    val injected = mailService.injectDirectCompensation(slotId, userId)
    if (injected) {
        DomainLog.i(TAG, "直接运营补偿已注入 slot=$slotId，触发自动存档")
        gameEngineCore.notifyPendingSave()
    } else {
        DomainLog.i(TAG, "直接运营补偿跳过注入（userId=$userId, slotId=$slotId）")
    }
}
