@file:Suppress("TooManyFunctions") // 拆分聚合:提取的私有辅助函数集中在原文件,文件级复杂度为拆分代价
package com.xianxia.sect.ui.game.dialogs

import com.xianxia.sect.core.util.GameUtils
import com.xianxia.sect.core.util.WATCHABLE_ITEM_TYPES
import com.xianxia.sect.core.util.sortedByWatchedThenRarity
import com.xianxia.sect.core.util.watchKey
import com.xianxia.sect.core.util.normalizeItemType
import com.xianxia.sect.ui.game.components.watchKeyOf
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xianxia.sect.feature.game.R
import com.xianxia.sect.core.engine.service.ClaimResult
import com.xianxia.sect.core.model.MailAttachment
import com.xianxia.sect.core.model.MailEntity
import com.xianxia.sect.core.model.MerchantItem
import com.xianxia.sect.core.model.SpiritStoneGrade
import com.xianxia.sect.ui.components.CloseButton
import com.xianxia.sect.ui.components.GameButton
import com.xianxia.sect.ui.components.ItemCardData

import com.xianxia.sect.ui.components.StandardPromptDialog
import com.xianxia.sect.ui.components.UnifiedItemCard
import com.xianxia.sect.ui.game.GameViewModel
import com.xianxia.sect.ui.game.components.ItemDetailDialog
import com.xianxia.sect.ui.theme.GameColors
import kotlinx.serialization.json.Json
import com.xianxia.sect.ui.components.clickableWithSound

private val PanelBg = GameColors.ButtonBackground
private val mailJson = Json { ignoreUnknownKeys = true; coerceInputValues = true }

@Composable
fun MailDialog(
    viewModel: GameViewModel,
    onDismiss: () -> Unit
) {
    val mails by viewModel.mails.collectAsStateWithLifecycle()
    var selectedMailId by remember { mutableStateOf<String?>(null) }
    var capacityWarning by remember { mutableStateOf<String?>(null) }
    val mailRewardCards by viewModel.mailRewardCards.collectAsStateWithLifecycle()

    val selectedMail = mails.find { it.id == selectedMailId }

    BackHandler(onBack = onDismiss)
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = GameColors.PageBackground
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Image(
                painter = painterResource(id = R.drawable.bg_horizontal),
                contentDescription = null,
                modifier = Modifier.matchParentSize(),
                contentScale = ContentScale.Crop
            )
            Column(modifier = Modifier.fillMaxSize().padding(horizontal = 32.dp)) {
                MailDialogBody(
                    mails = mails,
                    selectedMailId = selectedMailId,
                    selectedMail = selectedMail,
                    viewModel = viewModel,
                    onMailClick = { mail ->
                        selectedMailId = mail.id
                        if (!mail.isRead) {
                            viewModel.markMailAsRead(mail.id)
                        }
                    },
                    onCapacityWarning = { capacityWarning = it },
                    onDismiss = onDismiss
                )
            }
        }
    }

    capacityWarning?.let { message ->
        MailCapacityWarningDialog(
            message = message,
            onDismiss = { capacityWarning = null }
        )
    }

    LaunchedEffect(mailRewardCards) {
        if (mailRewardCards.isNotEmpty()) {
            viewModel.enqueueMailRewardCards()
        }
    }
}

/** 邮件标题栏 + 主内容行（MailDialog 拆分） */
@Composable
private fun ColumnScope.MailDialogBody(
    mails: List<MailEntity>,
    selectedMailId: String?,
    selectedMail: MailEntity?,
    viewModel: GameViewModel,
    onMailClick: (MailEntity) -> Unit,
    onCapacityWarning: (String) -> Unit,
    onDismiss: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "邮件",
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            color = Color.Black
        )
        Spacer(modifier = Modifier.weight(1f))
        CloseButton(onClick = onDismiss)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .weight(1f)
    ) {
        MailListPane(
            mails = mails,
            selectedMailId = selectedMailId,
            viewModel = viewModel,
            onMailClick = onMailClick
        )

        Box(
            modifier = Modifier
                .width(1.dp)
                .fillMaxHeight()
                .background(GameColors.ButtonDisabled)
        )

        MailDetailPane(
            selectedMail = selectedMail,
            viewModel = viewModel,
            onCapacityWarning = onCapacityWarning
        )
    }
}

/** 左侧邮件列表面板（MailDialog 拆分） */
@Composable
private fun RowScope.MailListPane(
    mails: List<MailEntity>,
    selectedMailId: String?,
    viewModel: GameViewModel,
    onMailClick: (MailEntity) -> Unit
) {
    Column(
        modifier = Modifier
            .weight(0.4f)
            .fillMaxHeight()
            .padding(end = 4.dp)
    ) {
        MailListContent(
            mails = mails,
            selectedMailId = selectedMailId,
            onMailClick = onMailClick
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(PanelBg, RoundedCornerShape(4.dp))
                .padding(vertical = 6.dp),
            horizontalArrangement = Arrangement.Center
        ) {
            GameButton(
                text = "删除已读",
                onClick = { viewModel.deleteAllReadAndClaimedMails() }
            )
            Spacer(modifier = Modifier.width(8.dp))
            GameButton(
                text = "一键已读",
                onClick = { viewModel.markAllMailsAsRead() }
            )
        }
    }
}

/** 邮件列表或空态提示（MailDialog 拆分） */
@Composable
private fun ColumnScope.MailListContent(
    mails: List<MailEntity>,
    selectedMailId: String?,
    onMailClick: (MailEntity) -> Unit
) {
    if (mails.isEmpty()) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            Text(
                "暂无邮件",
                fontSize = 12.sp,
                color = Color.Gray
            )
        }
    } else {
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            items(mails, key = { mail -> mail.id.ifEmpty { "mail_${mail.hashCode()}" } }, contentType = { "mail" }) { mail ->
                MailCard(
                    mail = mail,
                    isSelected = mail.id == selectedMailId,
                    onClick = { onMailClick(mail) }
                )
            }
        }
    }
}

/** 右侧邮件详情面板（MailDialog 拆分）：详情或空态 + 领取回调（含容量警告上报） */
@Composable
private fun RowScope.MailDetailPane(
    selectedMail: MailEntity?,
    viewModel: GameViewModel,
    onCapacityWarning: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .weight(0.6f)
            .fillMaxHeight()
            .padding(start = 8.dp)
    ) {
        if (selectedMail != null) {
            MailDetailPanel(
                mail = selectedMail,
                viewModel = viewModel,
                onClaim = {
                    viewModel.claimMailAttachment(selectedMail.id) { result ->
                        when (result) {
                            is ClaimResult.Success -> {
                                // 奖励卡片通过 mailRewardCards / LaunchedEffect 流程处理
                            }
                            is ClaimResult.AlreadyClaimed -> {
                                // claimAttachment 已自愈 Room 状态并刷新列表，
                                // 按钮会因 attachmentClaimed 变 true 而自然消失
                            }
                            is ClaimResult.CapacityInsufficient -> {
                                onCapacityWarning(result.message)
                            }
                            is ClaimResult.DistributeFailed -> {
                                onCapacityWarning(result.message)
                            }
                            is ClaimResult.Expired -> {
                                onCapacityWarning("该邮件已过期")
                            }
                            is ClaimResult.MailNotFound -> {
                                onCapacityWarning("邮件不存在")
                            }
                        }
                    }
                }
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "请选择一封邮件",
                    fontSize = 12.sp,
                    color = Color.Gray
                )
            }
        }
    }
}

/** 容量不足/领取失败提示弹窗（MailDialog 拆分） */
@Composable
private fun MailCapacityWarningDialog(message: String, onDismiss: () -> Unit) {
    StandardPromptDialog(
        onDismissRequest = onDismiss,
        title = "无法领取",
        text = message,
        confirmLabel = "确定",
        onConfirm = onDismiss,
        dismissLabel = null,
        onDismiss = null
    )
}

@Composable
private fun MailCard(
    mail: MailEntity,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val cardAlpha = if (mail.isRead) 0.5f else 1f

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .clip(RoundedCornerShape(4.dp))
            .clickableWithSound(onClick = onClick)
            .alpha(cardAlpha),
        contentAlignment = Alignment.Center
    ) {
        Image(
            painter = painterResource(id = R.drawable.bg_dialog_mail),
            contentDescription = null,
            modifier = Modifier.matchParentSize(),
            contentScale = ContentScale.FillBounds
        )
        if (isSelected) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(Color(0x33FFD700))
            )
        }
        Column(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
        ) {
            Text(
                text = mail.title,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = Color.Black,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = formatExpireTime(mail.expireTime),
                    fontSize = 9.sp,
                    color = Color.Gray
                )
                Text(
                    text = if (mail.isRead) "已读" else "未读",
                    fontSize = 9.sp,
                    color = if (mail.isRead) Color.Gray else Color(0xFFE53935)
                )
            }
        }
    }
}

@Composable
private fun MailDetailPanel(
    mail: MailEntity,
    viewModel: GameViewModel? = null,
    onClaim: () -> Unit
) {
    // 解析附件（领取后仍显示，已领时精灵图替换为"已领"文本）
    val watchedKeys = viewModel?.watchedItemIds?.collectAsStateWithLifecycle()?.value ?: emptySet()
    val attachments: List<MailAttachment> = remember(mail.attachments, watchedKeys) {
        val parsed = if (mail.hasAttachment) {
            try { mailJson.decodeFromString<List<MailAttachment>>(mail.attachments) }
            catch (_: Exception) { emptyList() }
        } else emptyList()
        parsed.sortedByWatchedThenRarity(
            watchedKeys,
            keyOf = { attachment ->
                val type = normalizeItemType(attachment.type)
                if (type in WATCHABLE_ITEM_TYPES) watchKey(type, attachment.name) else null
            },
            rarityOf = { it.rarity },
            nameOf = { it.name }
        )
    }

    var showDetail by remember { mutableStateOf(false) }
    var detailAttachment by remember { mutableStateOf<MailAttachment?>(null) }
    // 标题 + 内容 + 按钮共享一个底色面板
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(PanelBg, RoundedCornerShape(4.dp)),
    ) {
        // 标题区
        MailDetailHeader(mail = mail)

        HorizontalDivider(thickness = 1.dp, color = GameColors.ButtonDisabled)

        // 内容 + 附件合并区
        MailAttachmentArea(
            mail = mail,
            attachments = attachments,
            watchedKeys = watchedKeys,
            onAttachmentLongPress = { attachment ->
                detailAttachment = attachment
                showDetail = true
            }
        )

        // 按钮区 — 领取后不再显示
        if (mail.hasAttachment && !mail.attachmentClaimed) {
            MailClaimButton(onClaim = onClaim)
        }
    }

    if (showDetail && detailAttachment != null) {
        MailAttachmentDetailDialog(
            attachment = checkNotNull(detailAttachment),
            viewModel = viewModel,
            onDismiss = {
                showDetail = false
                detailAttachment = null
            }
        )
    }
}

/** 邮件详情标题区（MailDetailPanel 拆分）：标题 + 发件人 */
@Composable
private fun MailDetailHeader(mail: MailEntity) {
    Column(modifier = Modifier.padding(8.dp)) {
        Text(mail.title, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.Black)
        Text("发件人: ${mail.senderName}", fontSize = 10.sp, color = Color.Gray)
    }
}

/** 邮件内容 + 附件区（MailDetailPanel 拆分）：滚动区，附件按领取态分流渲染 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColumnScope.MailAttachmentArea(
    mail: MailEntity,
    attachments: List<MailAttachment>,
    watchedKeys: Set<String>,
    onAttachmentLongPress: (MailAttachment) -> Unit
) {
    Column(
        modifier = Modifier
            .weight(1f)
            .padding(8.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text(mail.content, fontSize = 12.sp, color = Color.Black)

        if (attachments.isNotEmpty()) {
            Spacer(modifier = Modifier.height(12.dp))
            Text("附件", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.Black)
            Spacer(modifier = Modifier.height(4.dp))
            if (mail.attachmentClaimed) {
                // 已领取：物品卡片结构不变，仅精灵图替换为"已领"
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    attachments.forEach { attachment ->
                        ClaimedAttachmentCard(attachment = attachment, watchedKeys = watchedKeys)
                    }
                }
            } else {
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    attachments.forEach { attachment ->
                        MailAttachmentItemCard(
                            attachment = attachment,
                            watchedKeys = watchedKeys,
                            onLongPress = { onAttachmentLongPress(attachment) }
                        )
                    }
                }
            }
        }
    }
}

/** 未领取附件物品卡片（MailDetailPanel 拆分）：构造 ItemCardData 并复用 UnifiedItemCard */
@Composable
private fun MailAttachmentItemCard(
    attachment: MailAttachment,
    watchedKeys: Set<String>,
    onLongPress: () -> Unit
) {
    UnifiedItemCard(
        data = mailAttachmentToItemCardData(attachment),
        showQuantity = true,
        isFollowed = watchKeyOf(attachment)?.let { it in watchedKeys } ?: false,
        onLongPress = onLongPress
    )
}

/**
 * 邮件附件 → 物品卡片数据转换（纯函数，便于单元测试）。
 *
 * 类型标志必须与 [ItemCardData] 的精灵解析分支一一对应（参照
 * RewardCardItem.toItemCardData / WarehouseGridCard）：
 * - 功法（manual）→ isManual=true，解析 manual_$rarity 精灵图（修复：此前遗漏
 *   该标志导致功法落 equipment 分支查不到 → 显示"敬请期待"）
 * - 弟子（disciple）→ isDisciple=true，解析通用弟子头像 disciple_portrait
 * - 灵石（spiritStones）→ 按名称解析品阶（"上品灵石"→HIGH 等，名称不含品阶
 *   词时默认 LOW），与发放侧 MailService 的品阶解析保持一致，杜绝显示与
 *   到账品阶不一致的错图
 * - 灵草资源（spiritHerbs）→ isHerb=true（语义归入草药类；无专属精灵图时
 *   由 ItemCard 的 herb 分支回退丹药图，不再显示"敬请期待"）
 */
internal fun mailAttachmentToItemCardData(attachment: MailAttachment): ItemCardData = ItemCardData(
    id = attachment.itemId ?: "",
    name = attachment.name,
    rarity = attachment.rarity,
    quantity = attachment.quantity,
    type = attachment.type,
    spiritStoneGrade = if (attachment.type == "spiritStones") {
        SpiritStoneGrade.fromDisplayName(attachment.name) ?: SpiritStoneGrade.LOW
    } else null,
    isPill = attachment.type == "pill",
    isHerb = attachment.type in listOf("herb", "spiritHerbs"),
    isSeed = attachment.type == "seed",
    isMaterial = attachment.type in listOf("material", "beastMaterial"),
    isBag = attachment.type == "storageBag",
    isManual = attachment.type == "manual",
    isDisciple = attachment.type == "disciple"
)

/** 领取按钮区（MailDetailPanel 拆分） */
@Composable
private fun MailClaimButton(onClaim: () -> Unit) {
    HorizontalDivider(thickness = 1.dp, color = GameColors.ButtonDisabled)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        GameButton(text = "领取", onClick = onClaim)
    }
}

/** 附件详情弹窗（MailDetailPanel 拆分） */
@Composable
private fun MailAttachmentDetailDialog(
    attachment: MailAttachment,
    viewModel: GameViewModel?,
    onDismiss: () -> Unit
) {
    ItemDetailDialog(
        item = MerchantItem(
            id = attachment.itemId ?: "",
            name = attachment.name,
            type = normalizeItemType(attachment.type),
            rarity = attachment.rarity,
            quantity = attachment.quantity,
            price = 0L
        ),
        onDismiss = onDismiss,
        viewModel = viewModel
    )
}

/**
 * 已领取附件的物品卡片：布局与 UnifiedItemCard 完全一致，
 * 仅精灵图替换为绿色"已领"文本，名称和数量不变。
 */
@Composable
private fun ClaimedAttachmentCard(
    attachment: MailAttachment,
    watchedKeys: Set<String> = emptySet()
) {
    Box(modifier = Modifier.size(60.dp), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(6.dp))
                .border(
                    2.dp,
                    if (watchKeyOf(attachment)?.let { it in watchedKeys } == true) GameColors.Gold
                    else GameColors.Border,
                    RoundedCornerShape(6.dp)
                )
        ) {
            // 精灵图区域（与 UnifiedItemCard 比例一致）
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(Color(0xFFF5F5F5)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "已领",
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    color = GameColors.Success
                )
                // 数量角标（与 UnifiedItemCard 一致：右下角白字）
                Text(
                    text = GameUtils.formatNumber(attachment.quantity),
                    fontSize = 8.sp,
                    color = Color.White,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 3.dp, bottom = 2.dp)
                )
            }
            // 名称区域（与 UnifiedItemCard 一致：14dp 白色背景黑字）
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(14.dp)
                    .background(Color.White),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = attachment.name,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.Black,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 2.dp)
                )
            }
        }
    }
}

private fun formatExpireTime(expireTime: Long): String {
    val now = System.currentTimeMillis()
    val diff = expireTime - now
    if (diff <= 0) return "已过期"
    // 永久邮件（Long.MAX_VALUE）显示"永久有效"，而非超大天数
    if (diff >= 100L * 365 * 24 * 60 * 60 * 1000) return "永久有效"

    val days = diff / (24 * 60 * 60 * 1000)
    val hours = diff / (60 * 60 * 1000)
    val minutes = diff / (60 * 1000)

    return when {
        days > 1 -> "${days}天后过期"
        hours > 0 -> "${hours}小时后过期"
        else -> "${minutes.coerceAtLeast(1)}分钟后过期"
    }
}
