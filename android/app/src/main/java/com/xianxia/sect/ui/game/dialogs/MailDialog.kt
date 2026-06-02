package com.xianxia.sect.ui.game.dialogs

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xianxia.sect.R
import com.xianxia.sect.core.engine.service.ClaimResult
import com.xianxia.sect.core.model.MailAttachment
import com.xianxia.sect.core.model.MailEntity
import com.xianxia.sect.ui.components.CloseButton
import com.xianxia.sect.ui.components.GameButton
import com.xianxia.sect.ui.components.ItemCardData
import com.xianxia.sect.ui.components.StandardPromptDialog
import com.xianxia.sect.ui.components.UnifiedItemCard
import com.xianxia.sect.ui.game.GameViewModel
import com.xianxia.sect.ui.theme.GameColors
import kotlinx.serialization.json.Json

private val PanelBg = Color(0xFFF6EBD5)
private val mailJson = Json { ignoreUnknownKeys = true; coerceInputValues = true }

@Composable
fun MailDialog(
    viewModel: GameViewModel,
    onDismiss: () -> Unit
) {
    val mails by viewModel.mails.collectAsStateWithLifecycle()
    var selectedMailId by remember { mutableStateOf<String?>(null) }
    var capacityWarning by remember { mutableStateOf<String?>(null) }

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
                    Column(
                        modifier = Modifier
                            .weight(0.4f)
                            .fillMaxHeight()
                            .padding(end = 4.dp)
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
                                items(mails, key = { it.id }) { mail ->
                                    MailCard(
                                        mail = mail,
                                        isSelected = mail.id == selectedMailId,
                                        onClick = {
                                            selectedMailId = mail.id
                                            if (!mail.isRead) {
                                                viewModel.markMailAsRead(mail.id)
                                            }
                                        }
                                    )
                                }
                            }
                        }

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

                    Box(
                        modifier = Modifier
                            .width(1.dp)
                            .fillMaxHeight()
                            .background(Color(0xFFBDBDBD))
                    )

                    Column(
                        modifier = Modifier
                            .weight(0.6f)
                            .fillMaxHeight()
                            .padding(start = 8.dp)
                    ) {
                        if (selectedMail != null) {
                            MailDetailPanel(
                                mail = selectedMail,
                                onClaim = {
                                    viewModel.claimMailAttachment(selectedMail.id) { result ->
                                        if (result is ClaimResult.CapacityInsufficient) {
                                            capacityWarning = result.message
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
            }
        }
    }

    capacityWarning?.let { message ->
        StandardPromptDialog(
            onDismissRequest = { capacityWarning = null },
            title = "无法领取",
            text = message,
            confirmLabel = "确定",
            onConfirm = { capacityWarning = null },
            dismissLabel = null,
            onDismiss = null
        )
    }
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
            .clickable(onClick = onClick)
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MailDetailPanel(
    mail: MailEntity,
    onClaim: () -> Unit
) {
    // 解析附件（领取后仍显示，已领时精灵图替换为"已领"文本）
    val attachments: List<MailAttachment> = remember(mail.attachments) {
        if (mail.hasAttachment) {
            try { mailJson.decodeFromString<List<MailAttachment>>(mail.attachments) }
            catch (_: Exception) { emptyList() }
        } else emptyList()
    }

    // 标题 + 内容 + 按钮共享一个底色面板
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(PanelBg, RoundedCornerShape(4.dp)),
    ) {
        // 标题区
        Column(modifier = Modifier.padding(8.dp)) {
            Text(mail.title, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.Black)
            Text("发件人: ${mail.senderName}", fontSize = 10.sp, color = Color.Gray)
        }

        HorizontalDivider(thickness = 1.dp, color = Color(0xFFBDBDBD))

        // 内容 + 附件合并区
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
                            ClaimedAttachmentCard(attachment)
                        }
                    }
                } else {
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        attachments.forEach { attachment ->
                            UnifiedItemCard(
                                data = ItemCardData(
                                    id = attachment.itemId ?: "",
                                    name = attachment.name,
                                    rarity = attachment.rarity,
                                    quantity = attachment.quantity,
                                    type = attachment.type,
                                    isSpiritStone = attachment.type == "spiritStones",
                                    isPill = attachment.type == "pill",
                                    isMaterial = attachment.type in listOf("material", "herb", "seed"),
                                    isBag = attachment.type == "storageBag"
                                ),
                                showQuantity = true
                            )
                        }
                    }
                }
            }
        }

        // 按钮区 — 领取后不再显示
        if (mail.hasAttachment && !mail.attachmentClaimed) {
            HorizontalDivider(thickness = 1.dp, color = Color(0xFFBDBDBD))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp),
                contentAlignment = Alignment.Center
            ) {
                GameButton(text = "领取", onClick = onClaim)
            }
        }
    }
}

/**
 * 已领取附件的物品卡片：布局与 UnifiedItemCard 完全一致，
 * 仅精灵图替换为绿色"已领"文本，名称和数量不变。
 */
@Composable
private fun ClaimedAttachmentCard(attachment: MailAttachment) {
    Box(
        modifier = Modifier.size(60.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(6.dp))
                .border(2.dp, GameColors.Border, RoundedCornerShape(6.dp))
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
                    color = Color(0xFF4CAF50)
                )
                // 数量角标（与 UnifiedItemCard 一致：右下角白字）
                Text(
                    text = "${attachment.quantity}",
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

    val days = diff / (24 * 60 * 60 * 1000)
    val hours = diff / (60 * 60 * 1000)
    val minutes = diff / (60 * 1000)

    return when {
        days > 1 -> "${days}天后过期"
        hours > 0 -> "${hours}小时后过期"
        else -> "${minutes.coerceAtLeast(1)}分钟后过期"
    }
}
