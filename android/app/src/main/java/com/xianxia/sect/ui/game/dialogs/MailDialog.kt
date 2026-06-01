package com.xianxia.sect.ui.game.dialogs

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
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
    val mails by viewModel.mails.collectAsState()
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
            Text(
                text = formatExpireTime(mail.expireTime),
                fontSize = 9.sp,
                color = Color.Gray
            )
        }
    }
}

@Composable
private fun MailDetailPanel(
    mail: MailEntity,
    onClaim: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(PanelBg, RoundedCornerShape(4.dp))
                .padding(8.dp)
        ) {
            Text(
                text = mail.title,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = Color.Black
            )
            Text(
                text = "发件人: ${mail.senderName}",
                fontSize = 10.sp,
                color = Color.Gray
            )
        }

        HorizontalDivider(
            thickness = 1.dp,
            color = Color(0xFFBDBDBD),
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
        )

        Column(
            modifier = Modifier
                .weight(1f)
                .background(PanelBg, RoundedCornerShape(4.dp))
                .padding(8.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                "亲爱的道友，",
                fontSize = 12.sp,
                color = Color.Black
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = mail.content,
                fontSize = 12.sp,
                color = Color.Black
            )
        }

        if (mail.hasAttachment) {
            HorizontalDivider(
                thickness = 1.dp,
                color = Color(0xFFBDBDBD),
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
            )

            Column(
                modifier = Modifier
                    .weight(1f)
                    .background(PanelBg, RoundedCornerShape(4.dp))
                    .padding(8.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                if (!mail.attachmentClaimed) {
                    val attachments: List<MailAttachment> = try {
                        mailJson.decodeFromString(mail.attachments)
                    } catch (e: Exception) {
                        emptyList()
                    }

                    if (attachments.isNotEmpty()) {
                        Text(
                            "附件",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.Black
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            attachments.forEach { attachment ->
                                UnifiedItemCard(
                                    data = ItemCardData(
                                        id = attachment.itemId ?: "",
                                        name = attachment.name,
                                        rarity = attachment.rarity,
                                        quantity = attachment.quantity,
                                        type = attachment.type
                                    ),
                                    showQuantity = true
                                )
                            }
                        }
                    }
                }
            }

            HorizontalDivider(
                thickness = 1.dp,
                color = Color(0xFFBDBDBD),
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(PanelBg, RoundedCornerShape(4.dp))
                    .padding(vertical = 6.dp),
                contentAlignment = Alignment.Center
            ) {
                if (!mail.attachmentClaimed) {
                    GameButton(
                        text = "领取",
                        onClick = onClaim
                    )
                } else {
                    Text(
                        "已领取",
                        fontSize = 12.sp,
                        color = Color.Gray,
                        textAlign = TextAlign.Center
                    )
                }
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
