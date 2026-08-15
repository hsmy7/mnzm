package com.xianxia.sect.core.engine.service

import com.xianxia.sect.core.AdFreeWhitelist
import com.xianxia.sect.core.model.MailAttachment
import com.xianxia.sect.core.model.MailEntity
import com.xianxia.sect.core.model.StorageBag
import com.xianxia.sect.core.util.DomainLog
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer

/**
 * 单用户定向补偿邮件（MailService 扩展，独立文件）。
 *
 * 拆分原因：MailService 类主体接近 detekt LargeClass（800 行）阈值，
 * 补偿邮件属独立运营配置，放独立文件保持 MailService 规模稳定；
 * stateStore/mailRepo 已放宽为 internal 供本扩展读取（三重防护）。
 */
private const val TAG = "MailCompensationOps"

// ── 单用户定向补偿邮件常量（地品储物袋 ×10）──
private const val COMPENSATION_MAIL_ID = "compensation_storage_bag_v1"
/** 补偿目标用户 TapTap unionId */
private const val COMPENSATION_UNION_ID = "I13lvAJjLgqSvh/LHjiJCg=="
/** 补偿地品储物袋数量 */
private const val COMPENSATION_BAG_COUNT = 10
/** 地品储物袋品阶（TIER_NAMES 索引：1凡 2灵 3宝 4玄 5地 6天） */
private const val COMPENSATION_BAG_RARITY = 5
/** 补偿邮件有效期：3 天 */
private const val COMPENSATION_EXPIRE_MS = 3L * 24 * 60 * 60 * 1000

private val compensationMailJson = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
}

/**
 * 注入单用户定向补偿邮件（地品储物袋 ×10，3 天有效，每个存档仅可领取一次）。
 *
 * 与 [MailService.injectExclusiveBonus] 的防护结构一致：
 * 保护1：当前用户必须是指定目标 unionId（定向判定，非全量白名单）；
 * 保护2：mailRecords 已领取则跳过（每个存档仅可领取一次）；
 * 保护3：邮件已存在 Room 中则跳过（防重复注入）。
 *
 * @param slotId 目标存档槽位
 * @return true=成功注入, false=跳过
 */
@Suppress("ReturnCount")
suspend fun MailService.injectStorageBagCompensation(slotId: Int): Boolean {
    // 保护1：目标用户判定
    if (!AdFreeWhitelist.isCurrentUser(COMPENSATION_UNION_ID)) {
        DomainLog.i(TAG, "非补偿目标用户，跳过补偿邮件注入")
        return false
    }

    val snapshot = stateStore.gameData.value

    // 保护2：mailRecords 已领取检查 — 每个存档仅可领取一次
    if (snapshot.mailRecords.any { it.mailId == COMPENSATION_MAIL_ID }) {
        DomainLog.i(TAG, "补偿邮件已领取，跳过注入")
        return false
    }

    // 保护3：重复注入检查 — 邮件已存在 DB 中则跳过
    val existing = try {
        mailRepo.getById(slotId, COMPENSATION_MAIL_ID)
    } catch (e: CancellationException) {
        throw e
    } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
        // 防御性兜底：Room 异常类型不可预期，检查失败不阻断注入（同 injectExclusiveBonus）
        DomainLog.e(TAG, "检查补偿邮件是否存在时失败", e)
        null
    }
    if (existing != null) {
        DomainLog.i(TAG, "补偿邮件已存在，跳过重复注入")
        return false
    }

    return try {
        insertMail(buildStorageBagCompensationMail(slotId))
        DomainLog.i(TAG, "补偿邮件已注入到 slot=$slotId")
        true
    } catch (e: CancellationException) {
        throw e
    } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
        // 注入失败不应阻塞游戏启动（boot 已成功），下次启动自动重试
        DomainLog.e(TAG, "补偿邮件插入失败 slot=$slotId", e)
        false
    }
}

/** 构造补偿邮件实体（地品储物袋 ×10 附件 + 3 天有效期） */
private fun buildStorageBagCompensationMail(slotId: Int): MailEntity {
    val now = System.currentTimeMillis()
    // 储物袋名称与发放逻辑单一来源：TIER_NAMES[rarity-1]，避免名称写错
    val bagName = StorageBag.TIER_NAMES.getOrElse(COMPENSATION_BAG_RARITY - 1) { "凡品储物袋" }
    return MailEntity(
        id = COMPENSATION_MAIL_ID,
        slotId = slotId,
        source = "admin",
        mailType = "compensation",
        title = "补偿礼包",
        content = "感谢您的理解与支持，特此补偿地品储物袋 ×10，请查收。" +
            "有效期 3 天，每个存档仅可领取一次。\n\n——天道意志",
        senderName = "天道意志",
        sendTime = now,
        expireTime = now + COMPENSATION_EXPIRE_MS,
        hasAttachment = true,
        attachments = compensationMailJson.encodeToString(
            serializer<List<MailAttachment>>(),
            listOf(
                MailAttachment(
                    type = "storageBag",
                    name = bagName,
                    quantity = COMPENSATION_BAG_COUNT,
                    rarity = COMPENSATION_BAG_RARITY
                )
            )
        )
    )
}
