package com.xianxia.sect.core.engine.service

import com.xianxia.sect.core.util.DomainLog
import com.xianxia.sect.core.util.DomainResult
import com.xianxia.sect.core.model.MailAttachment
import com.xianxia.sect.core.model.MailClaimRecord
import com.xianxia.sect.core.model.MailEntity
import com.xianxia.sect.core.model.Material
import com.xianxia.sect.core.model.RewardCardItem
import com.xianxia.sect.core.model.SpiritStoneGrade
import com.xianxia.sect.core.registry.BeastMaterialDatabase
import com.xianxia.sect.core.registry.EquipmentDatabase
import com.xianxia.sect.core.registry.ItemDatabase
import com.xianxia.sect.core.registry.ManualDatabase
import com.xianxia.sect.core.state.MutableGameState
import com.xianxia.sect.core.wallet.SpiritStoneSource
import com.xianxia.sect.core.util.RngPartition
import com.xianxia.sect.core.util.asKotlinRandom
import kotlinx.coroutines.CancellationException

/**
 * 单用户定向补偿邮件（MailService 扩展，独立文件）。
 *
 * 拆分原因：MailService 类主体接近 detekt LargeClass（800 行）阈值，
 * 补偿邮件属独立运营配置，放独立文件保持 MailService 规模稳定；
 * stateStore/mailRepo 已放宽为 internal 供本扩展读取（三重防护）。
 */
// ── 邮件附件发放域（自 MailService 拆出，行为零变更） ────────────────────────

private val TAG = MailService.TAG
private val json = MailService.json
/**
 * mailRecords 幂等账本保留条数。
 * 与 C++ 导入侧归一常量同源：game_core.cpp normalizeLedgers
 * MAIL_RECORD_RETENTION = 500（改值须双端同步）。
 */
private val MAIL_RECORD_RETENTION = MailService.MAIL_RECORD_RETENTION
/**
 * 邮件状态门控：存在 → 未过期 → 未领取 → mailRecords
 * 补偿防护（含 Room 自愈）。
 *
 * @return first = 非空表示不可继续领取；second = 邮件实体（不存在时为 null）
 */
internal suspend fun MailService.findClaimableMail(mailId: String, slotId: Int): Pair<ClaimResult?, MailEntity?> {
    val mail = mailRepo.getById(slotId, mailId) ?: return ClaimResult.MailNotFound to null
    val now = wallClock.currentTimeMillis()
    if (mail.expireTime <= now) return ClaimResult.Expired to mail
    if (mail.attachmentClaimed) return ClaimResult.AlreadyClaimed to mail
    // 二次保护：若 Room DB 的 attachmentClaimed 未及时更新，
    // GameData 中的 mailRecords 作为补偿防护防止重复领取。
    // 若 mailRecords 已有记录而 Room 未同步，主动自愈 Room 状态
    // 并刷新 UI，使领取按钮自然消失。
    val snapshot = stateStore.gameData.value
    if (snapshot.mailRecords.any { it.mailId == mailId }) {
        healRoomClaimState(mail = mail, mailId = mailId, slotId = slotId)
        return ClaimResult.AlreadyClaimed to mail
    }
    return null to mail
}

/**
 * 附件解析与容量检查：解析失败按"无可领附件"成功处理
 * （原语义），容量不足返回失败结果。
 *
 * @return first = 非空表示不可继续领取；second = 通过时的附件列表
 */

@Suppress("TooGenericExceptionCaught") // 防御兜底: 异常源跨IO/SDK不可枚举, 降级继续+日志留痕, 非静默吞噬
internal suspend fun MailService.parseAndCheckAttachmentCapacity(
    mailId: String,
    attachmentsJson: String
): Pair<ClaimResult?, List<MailAttachment>> {
    val attachments: List<MailAttachment> = try {
        json.decodeFromString(attachmentsJson)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        DomainLog.e(TAG, "Failed to parse attachments for mail $mailId", e)
        return ClaimResult.Success(emptyList()) to emptyList()
    }

    // 容量检查
    if (attachments.isNotEmpty()) {
        val capacityCheck = ensureCapacity(attachments)
        if (capacityCheck != null) {
            return ClaimResult.CapacityInsufficient(capacityCheck) to emptyList()
        }
    }
    return null to attachments
}

/** Room 状态自愈：mailRecords 已有记录而 Room 未同步时主动修复 */

@Suppress("TooGenericExceptionCaught") // 防御兜底: 异常源跨IO/SDK不可枚举, 降级继续+日志留痕, 非静默吞噬
internal suspend fun MailService.healRoomClaimState(mail: MailEntity, mailId: String, slotId: Int) {
    try {
        mailRepo.update(mail.copy(
            attachmentClaimed = true, isRead = true
        ))
        refreshActiveMails(slotId)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        DomainLog.e(TAG,
            "Heal Room state failed for mail $mailId: ${e.message}", e)
    }
}

/**
 * 附件发放：物品入库 + 领取记录在同一事务内原子写入。
 *
 * @return (奖励卡片, 失败结果)——发放异常时失败结果非空，凭据未写入可重试
 */
// 防御兜底: 异常源跨IO/SDK不可枚举, 降级继续+日志留痕, 非静默吞噬

@Suppress("TooGenericExceptionCaught", "UnusedParameter")
internal suspend fun MailService.grantAttachments(
    mail: MailEntity,
    attachments: List<MailAttachment>,
    slotId: Int
): Pair<List<RewardCardItem>, ClaimResult?> {
    if (attachments.isEmpty()) return Pair(emptyList(), null)
    return try {
        // 捕获豁免（updateMirror，§2.80）：发放写面（钱包/年度账/mailRecords 已关闭
        // 回导 + 9 类集合在册保留经捕获传输）——领取成功由调用方 claimAttachment 尾部
        // 基线重建回导 C++；updateMirror 与 update 唯一差异即捕获豁免，行为零变更
        stateStore.updateMirror {
            distributeAttachmentsInline(this, attachments)
            gameData = gameData.copy(
                // 幂等账本按防重复窗口截断：追加即裁剪，
                // 保留近 MAIL_RECORD_RETENTION 条——防重复发放只需覆盖
                // 「删除已读后重领」的交互窗口，500 条远超实际需要
                mailRecords = (gameData.mailRecords + MailClaimRecord(
                    mailId = mail.id,
                    claimedAt = wallClock.currentTimeMillis(),
                    source = mail.source
                )).takeLast(MAIL_RECORD_RETENTION)
            )
        }
        Pair(buildRewardCardsFromAttachments(attachments), null)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        DomainLog.e(TAG, "Failed to distribute attachments for mail ${mail.id}", e)
        Pair(emptyList(), ClaimResult.DistributeFailed(
            "发放附件失败: ${e.message ?: "未知错误"}"
        ))
    }
}

/** 邮件状态门控：未过期 → 未领取 → mailRecords 二次保护 */
internal suspend fun MailService.checkInternalMailClaimable(mail: MailEntity, now: Long): ClaimResult? {
    if (mail.expireTime <= now) return ClaimResult.Expired
    if (mail.attachmentClaimed) return ClaimResult.AlreadyClaimed
    // 二次保护：与 claimAttachment 一致，防止 Room 与 mailRecords
    // 不一致时通过"一键已读"重复发放物品
    val snapshot = stateStore.gameData.value
    if (snapshot.mailRecords.any { it.mailId == mail.id }) {
        return ClaimResult.AlreadyClaimed
    }
    return null
}

@Suppress("TooGenericExceptionCaught") // 防御兜底: 探针/可选增强失败即降级默认值, 异常类型不可枚举
internal suspend fun MailService.claimAttachmentInternal(mail: MailEntity, slotId: Int, now: Long): ClaimResult {
    val guardFailure = checkInternalMailClaimable(mail, now)
    if (guardFailure != null) return guardFailure

    val attachments: List<MailAttachment> = try {
        json.decodeFromString(mail.attachments)
    } catch (ignored: Exception) {
        return ClaimResult.Success(emptyList())
    }

    if (attachments.isNotEmpty()) {
        val capacityCheck = ensureCapacity(attachments)
        if (capacityCheck != null) {
            return ClaimResult.CapacityInsufficient(capacityCheck)
        }
    }

    // 原子发放：物品入库 + 领取记录在同一 stateStore 事务中
    val rewardCards: List<RewardCardItem>
    if (attachments.isNotEmpty()) {
        try {
            // 捕获豁免（updateMirror，§2.80）：同 grantAttachments——领取成功由
            // markAllAsRead 尾部基线重建回导 C++
            stateStore.updateMirror {
                distributeAttachmentsInline(this, attachments)
                gameData = gameData.copy(
                    // 同上：追加即裁剪
                    mailRecords = (gameData.mailRecords + MailClaimRecord(
                        mailId = mail.id,
                        claimedAt = wallClock.currentTimeMillis(),
                        source = mail.source
                    )).takeLast(MAIL_RECORD_RETENTION)
                )
            }
            rewardCards = buildRewardCardsFromAttachments(attachments)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DomainLog.e(TAG, "Failed to distribute attachments for mail ${mail.id}", e)
            return ClaimResult.DistributeFailed(
                "发放附件失败: ${e.message ?: "未知错误"}"
            )
        }
    } else {
        rewardCards = emptyList()
    }

    mailRepo.update(mail.copy(attachmentClaimed = true, isRead = true))
    refreshActiveMails(slotId)
    return ClaimResult.Success(attachments, rewardCards)
}

/**
 * 确保有足够容量领取附件。容量不足时直接返回错误——不自动删除任何邮件
 * （邮件只保留，仅玩家手动"删除已读"清理），由玩家清理仓库后重试。
 */

internal suspend fun MailService.ensureCapacity(attachments: List<MailAttachment>): String? {
    val data = stateStore.gameData.value

    for (attachment in attachments) {
        when (attachment.type) {
            "spiritStones", "spiritHerbs", "storageBag" -> {}
            "equipment", "manual", "pill", "material", "beastMaterial", "herb", "seed" -> {
                val totalItems = stateStore.equipmentStacks.value.size +
                        stateStore.manualStacks.value.size +
                        stateStore.pills.value.size +
                        stateStore.materials.value.size +
                        stateStore.herbs.value.size +
                        stateStore.seeds.value.size
                val warehouseCount = data.placedBuildings.count { it.displayName == "仓库" }
                val maxCap = gameConfigProvider.warehouse.baseCapacity +
                        warehouseCount * gameConfigProvider.warehouse.capacityPerBuilding

                if (totalItems >= maxCap) {
                    return "仓库空间不足，请清理后再领取"
                }
            }
            "disciple" -> {
                // 弟子数量无上限，不检查容量
            }
        }
    }
    return null
}

/**
 * 内联附件发放——直接修改 MutableGameState，由调用方包裹在 stateStore.update {} 中。
 * 发放失败时异常传播到外层，由外层决定是否回滚（不记录 mailRecords）。
 *
 * 所有可堆叠物品统一委托 [InventorySystem.addXxx]（走 StackableItemStore 合并），
 * 消除手写"找第一个堆叠 + 追加"导致同种物品分裂为多个堆叠的问题。
 * 年度报告来源由 addXxx 内部按 `mail:...` 键自动累加，键格式与原手写统计一致。
 */

@Suppress("CyclomaticComplexMethod") // 附件类型分发表（12+ 类），分支多但非控制流纠结
internal fun MailService.distributeAttachmentsInline(
    state: MutableGameState,
    attachments: List<MailAttachment>
) {
    val mailRng = gameRngManager.getRng(RngPartition.MAIL).asKotlinRandom()
    // 抑制溢出转邮件：本路径 Partial/Failure 抛异常回滚整个领取事务，
    // 若已入队邮件草稿会造成"物品回滚但邮件已发"的双重发放
    inventorySystem.withOverflowMailSuppressed {
    inventorySystem.withTrackingSource("mail") {
        for (attachment in attachments) {
            when (attachment.type) {
                "spiritStones" -> {
                    // 按附件名称解析品阶（"上品灵石"→HIGH 等），与邮件附件卡片的
                    // 精灵图品阶解析保持一致，杜绝显示品阶与到账品阶不一致
                    spiritStoneWallet.add(
                        state = state,
                        amount = attachment.quantity.toLong(),
                        grade = SpiritStoneGrade.fromDisplayName(attachment.name)
                            ?: SpiritStoneGrade.LOW,
                        source = SpiritStoneSource.Mail
                    )
                }
                "spiritHerbs" -> {
                    state.gameData = state.gameData.copy(
                        spiritHerbs = state.gameData.spiritHerbs + attachment.quantity
                    )
                }
                "equipment" -> distributeEquipmentAttachment(attachment, mailRng)
                "manual" -> distributeManualAttachment(attachment, mailRng)
                "pill" -> distributePillAttachment(attachment, mailRng)
                "material" -> distributeMaterialAttachment(attachment, mailRng)
                "beastMaterial" -> distributeBeastMaterialAttachment(attachment)
                "herb" -> distributeHerbAttachment(attachment, mailRng)
                "seed" -> distributeSeedAttachment(attachment, mailRng)
                "disciple" -> distributeDiscipleAttachment(state, attachment, mailRng)
                "storageBag" -> distributeStorageBagAttachment(attachment)
                else -> throw IllegalArgumentException(
                    "未知邮件附件类型: type=${attachment.type}, name=${attachment.name}"
                )
            }
        }
    }
    }
}

/**
 * 记录 addXxx 三态结果。
 *
 * Partial/Failure 时抛出异常——外层 stateStore.update
 * 未提交（事务回滚），邮件保持未领取状态，玩家清理仓库后可重新领取，
 * 物品不会部分发放后静默丢失。与 distributeAttachmentsInline 的
 * KDoc 契约（"发放失败时异常传播到外层回滚"）一致。
 */

internal fun MailService.handleResult(result: DomainResult<*>, label: String) {
    when (result) {
        is DomainResult.Success -> { /* 正常发放 */ }
        is DomainResult.Partial -> error("$label 仓库空间不足，溢出 ${result.overflow} 个")
        is DomainResult.Failure -> error("$label 发放失败: ${result.error}")
    }
}

/** 装备附件：itemId 优先精确发放指定模板；未命中回退按品阶逐件随机生成，委托 addEquipmentStack 合并 */

internal fun MailService.distributeEquipmentAttachment(attachment: MailAttachment, mailRng: kotlin.random.Random) {
    val qty = attachment.quantity.coerceAtLeast(1)
    val template = attachment.itemId?.let { EquipmentDatabase.getById(it) }
    if (template != null) {
        val stack = EquipmentDatabase.createFromTemplate(template).copy(quantity = qty)
        handleResult(inventorySystem.addEquipmentStack(stack), "装备 ${stack.name}")
        return
    }
    repeat(qty) {
        val newEquipment = EquipmentDatabase.generateRandom(
            minRarity = attachment.rarity,
            maxRarity = attachment.rarity,
            random = mailRng
        ).copy(quantity = 1)
        handleResult(inventorySystem.addEquipmentStack(newEquipment), "装备 ${newEquipment.name}")
    }
}

/** 功法附件：itemId 优先精确发放指定模板；未命中回退按品阶逐件随机生成，委托 addManualStack 合并 */

internal fun MailService.distributeManualAttachment(attachment: MailAttachment, mailRng: kotlin.random.Random) {
    val qty = attachment.quantity.coerceAtLeast(1)
    val template = attachment.itemId?.let { ManualDatabase.getById(it) }
    if (template != null) {
        val stack = ManualDatabase.createFromTemplate(template).copy(quantity = qty)
        handleResult(inventorySystem.addManualStack(stack), "功法 ${stack.name}")
        return
    }
    repeat(qty) {
        val newManual = ManualDatabase.generateRandom(
            minRarity = attachment.rarity,
            maxRarity = attachment.rarity,
            random = mailRng
        ).copy(quantity = 1)
        handleResult(inventorySystem.addManualStack(newManual), "功法 ${newManual.name}")
    }
}

/** 丹药附件：按模板或随机生成，委托 addPill 合并（含品阶键，跨品阶不合并） */

internal fun MailService.distributePillAttachment(attachment: MailAttachment, mailRng: kotlin.random.Random) {
    val qty = attachment.quantity.coerceAtLeast(1)
    val pillItemId = attachment.itemId // local val for cross-module smart cast
    val pill = if (pillItemId != null) {
        // 指定具体丹药模板（如下品大乘丹 breakthrough_2_low）
        val template = ItemDatabase.getPillById(pillItemId)
        if (template != null) {
            ItemDatabase.createPillFromTemplate(template, qty)
        } else {
            ItemDatabase.generateRandomPill(
                minRarity = attachment.rarity,
                maxRarity = attachment.rarity,
                random = mailRng
            ).copy(quantity = qty)
        }
    } else {
        ItemDatabase.generateRandomPill(
            minRarity = attachment.rarity,
            maxRarity = attachment.rarity,
            random = mailRng
        ).copy(quantity = qty)
    }
    handleResult(inventorySystem.addPill(pill), "丹药 ${pill.name}")
}

/** 材料附件：itemId 优先精确发放（先普通材料再妖兽材料模板）；未命中回退按品阶随机，委托 addMaterial 合并 */

internal fun MailService.distributeMaterialAttachment(attachment: MailAttachment, mailRng: kotlin.random.Random) {
    val qty = attachment.quantity.coerceAtLeast(1)
    val itemId = attachment.itemId
    if (itemId != null) {
        val template = ItemDatabase.getMaterialById(itemId)
        if (template != null) {
            val material = ItemDatabase.createMaterialFromTemplate(template).copy(quantity = qty)
            handleResult(inventorySystem.addMaterial(material), "材料 ${material.name}")
            return
        }
        // 秘境妖兽材料（如"凡虎皮"）也走此附件类型
        val beastMat = BeastMaterialDatabase.getMaterialById(itemId)
        if (beastMat != null) {
            val mat = Material(
                id = java.util.UUID.randomUUID().toString(),
                name = beastMat.name,
                rarity = beastMat.rarity,
                category = beastMat.materialCategory,
                quantity = qty
            )
            handleResult(inventorySystem.addMaterial(mat), "材料 ${mat.name}")
            return
        }
    }
    val material = ItemDatabase.generateRandomMaterial(
        minRarity = attachment.rarity,
        maxRarity = attachment.rarity,
        random = mailRng
    ).copy(quantity = qty)
    handleResult(inventorySystem.addMaterial(material), "材料 ${material.name}")
}

/** 妖兽材料附件：按 itemId 查库，委托 addMaterial 合并 */

internal fun MailService.distributeBeastMaterialAttachment(attachment: MailAttachment) {
    val beastMat = BeastMaterialDatabase.getMaterialById(attachment.itemId ?: "")
    if (beastMat != null) {
        val qty = attachment.quantity.coerceAtLeast(1)
        val mat = Material(
            id = java.util.UUID.randomUUID().toString(),
            name = beastMat.name,
            rarity = beastMat.rarity,
            category = beastMat.materialCategory,
            quantity = qty
        )
        handleResult(inventorySystem.addMaterial(mat), "材料 ${mat.name}")
    }
}

/** 草药附件：itemId 优先精确发放指定模板；未命中回退按品阶随机，委托 addHerb 合并 */
