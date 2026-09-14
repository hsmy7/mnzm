package com.xianxia.sect.core.engine.service

import com.xianxia.sect.core.engine.RedeemCodeManager
import com.xianxia.sect.core.model.DiscipleRewardConfig
import com.xianxia.sect.core.model.Herb
import com.xianxia.sect.core.model.MailAttachment
import com.xianxia.sect.core.model.RewardCardItem
import com.xianxia.sect.core.model.Seed
import com.xianxia.sect.core.model.StorageBag
import com.xianxia.sect.core.model.recruitedMonth
import com.xianxia.sect.core.registry.HerbDatabase
import com.xianxia.sect.core.state.MutableGameState
import com.xianxia.sect.core.engine.system.addStorageBag

// ── 附件变体发放域（自 MailService 拆出，行为零变更） ────────────────────────

private val TAG = MailService.TAG
private val json = MailService.json
private val MAIL_RECORD_RETENTION = MailService.MAIL_RECORD_RETENTION
internal fun MailService.distributeHerbAttachment(attachment: MailAttachment, mailRng: kotlin.random.Random) {
    val qty = attachment.quantity.coerceAtLeast(1)
    val template = attachment.itemId?.let { HerbDatabase.getHerbById(it) }
    if (template != null) {
        val herb = Herb(
            id = java.util.UUID.randomUUID().toString(),
            name = template.name,
            rarity = template.rarity,
            description = template.description,
            category = template.category,
            quantity = qty
        )
        handleResult(inventorySystem.addHerb(herb), "草药 ${herb.name}")
        return
    }
    val herbTemplate = HerbDatabase.generateRandomHerb(
        minRarity = attachment.rarity,
        maxRarity = attachment.rarity,
        random = mailRng
    )
    val herb = Herb(
        id = java.util.UUID.randomUUID().toString(),
        name = herbTemplate.name,
        rarity = herbTemplate.rarity,
        description = herbTemplate.description,
        category = herbTemplate.category,
        quantity = qty
    )
    handleResult(inventorySystem.addHerb(herb), "草药 ${herb.name}")
}

/** 种子附件：itemId 优先精确发放指定模板；未命中回退按品阶随机，委托 addSeed 合并 */

internal fun MailService.distributeSeedAttachment(attachment: MailAttachment, mailRng: kotlin.random.Random) {
    val qty = attachment.quantity.coerceAtLeast(1)
    val template = attachment.itemId?.let { HerbDatabase.getSeedById(it) }
    if (template != null) {
        val seed = Seed(
            id = java.util.UUID.randomUUID().toString(),
            name = template.name,
            rarity = template.rarity,
            description = template.description,
            growTime = template.growTime,
            yield = template.yield,
            quantity = qty
        )
        handleResult(inventorySystem.addSeed(seed), "种子 ${seed.name}")
        return
    }
    val seedTemplate = HerbDatabase.generateRandomSeed(
        minRarity = attachment.rarity,
        maxRarity = attachment.rarity,
        random = mailRng
    )
    val seed = Seed(
        id = java.util.UUID.randomUUID().toString(),
        name = seedTemplate.name,
        rarity = seedTemplate.rarity,
        description = seedTemplate.description,
        growTime = seedTemplate.growTime,
        yield = seedTemplate.yield,
        quantity = qty
    )
    handleResult(inventorySystem.addSeed(seed), "种子 ${seed.name}")
}

/** 弟子附件：直接生成弟子，不走仓库 */

internal fun MailService.distributeDiscipleAttachment(
    state: MutableGameState,
    attachment: MailAttachment,
    mailRng: kotlin.random.Random
) {
    val currentMonthValue = state.gameData.gameYear * 12 + state.gameData.gameMonth
    val usedNames = state.discipleTables.assembleAll().map { it.name }.toMutableSet()
    // 支持通过 extra 传递境界参数（realm / realmLayer）和灵根数（spiritRootCount）
    val realm = attachment.extra["realm"]?.toIntOrNull() ?: 9
    val realmLayer = attachment.extra["realmLayer"]?.toIntOrNull() ?: 1
    val spiritRootCount = attachment.extra["spiritRootCount"]?.toIntOrNull()
    val config = if (realm != 9 || realmLayer != 1 || spiritRootCount != null) {
        DiscipleRewardConfig(
            realm = realm,
            realmLayer = realmLayer,
            spiritRootCount = spiritRootCount
        )
    } else null
    repeat(attachment.quantity.coerceAtLeast(1)) {
        val disciple = RedeemCodeManager.generateDisciple(config, usedNames, random = mailRng)
        disciple.id = ((state.discipleTables.ids.maxOrNull() ?: 0) + 1).toString()
        disciple.usage.recruitedMonth = currentMonthValue
        state.discipleTables.insert(disciple)
        usedNames.add(disciple.name)
    }
}

/** 储物袋附件：委托 addStorageBag 合并（同稀有度合并为一个堆叠） */

internal fun MailService.distributeStorageBagAttachment(attachment: MailAttachment) {
    val qty = attachment.quantity.coerceAtLeast(1)
    val rarity = attachment.rarity.coerceIn(1, 6)
    val bagName = StorageBag.TIER_NAMES.getOrElse(rarity - 1) { "凡品储物袋" }
    handleResult(
        inventorySystem.addStorageBag(
            StorageBag(
                id = java.util.UUID.randomUUID().toString(),
                name = bagName,
                rarity = rarity,
                quantity = qty
            )
        ),
        "储物袋"
    )
}

internal fun MailService.buildRewardCardsFromAttachments(
    attachments: List<MailAttachment>
): List<RewardCardItem> {
    return attachments.mapNotNull { attachment ->
        when {
            attachment.type == "spiritStones" || attachment.type == "spiritHerbs" ->
                RewardCardItem(
                    itemName = attachment.name.ifEmpty { "灵石" },
                    itemType = "spiritStones",
                    rarity = attachment.rarity.coerceIn(1, 6),
                    quantity = attachment.quantity
                )
            attachment.type == "disciple" -> null // 弟子不显示为物品卡片
            attachment.quantity > 0 ->
                RewardCardItem(
                    itemName = attachment.name,
                    itemType = attachment.type,
                    rarity = attachment.rarity.coerceIn(1, 6),
                    quantity = attachment.quantity
                )
            else -> null
        }
    }
}

suspend fun MailService.deleteMail(mailId: String, slotId: Int) {
    // 使用原子条件删除替代读-改-写模式，消除 TOCTOU 竞态
    mailRepo.deleteIfClaimed(slotId, mailId)
}

suspend fun MailService.deleteAllReadAndClaimed(slotId: Int) {
    // 邮件唯一删除入口：玩家手动点击"删除已读"（已读且已领取，无资产丢失）
    mailRepo.deleteAllReadAndClaimed(slotId)
}

/**
 * 插入外部邮件（如运营补偿）并刷新活跃邮件缓存，确保 [activeMails] 立即反映最新数据。
 */
