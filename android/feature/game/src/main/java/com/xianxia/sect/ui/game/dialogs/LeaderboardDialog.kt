package com.xianxia.sect.ui.game.dialogs

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xianxia.sect.taptap.LeaderboardEntry
import com.xianxia.sect.taptap.LocalLeaderboardEntry
import com.xianxia.sect.taptap.findActivity
import com.xianxia.sect.ui.components.DialogMode
import com.xianxia.sect.ui.components.GameButton
import com.xianxia.sect.ui.components.UnifiedGameDialog
import com.xianxia.sect.ui.components.clickableWithSound
import com.xianxia.sect.ui.game.leaderboard.LeaderboardViewModel

/** 榜单行布局：名次 | 名称（玩家标记）| 战力 */
private const val RANK_COLUMN_WEIGHT = 1f
private const val NAME_COLUMN_WEIGHT = 3f
private const val POWER_COLUMN_WEIGHT = 2f
private val ROW_H_PADDING = 12.dp
private val ROW_V_PADDING = 8.dp

/** Tab 行背景（选中态浅底） */
private val TabActiveBg = Color(0x0A000000)

/**
 * 排行榜对话框：双标签（天下宗门本地榜 / 玩家排行云端榜）。
 *
 * 本地榜即时渲染；云端榜四态（加载/未登录引导/错误重试/成功+我的排名）。
 */
@Composable
fun LeaderboardDialog(
    viewModel: LeaderboardViewModel,
    onDismiss: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val localEntries by viewModel.localEntries.collectAsStateWithLifecycle()

    UnifiedGameDialog(
        onDismissRequest = onDismiss,
        title = "排行榜",
        mode = DialogMode.Half,
        scrollableContent = false
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            LeaderboardTabRow(
                selectedTab = uiState.selectedTab,
                onSelectLocal = viewModel::selectLocalTab,
                onSelectCloud = viewModel::selectCloudTab
            )
            when (uiState.selectedTab) {
                LeaderboardViewModel.LeaderboardTab.LOCAL -> LocalLeaderboardList(localEntries)
                LeaderboardViewModel.LeaderboardTab.CLOUD -> CloudLeaderboardContent(
                    state = uiState.cloudState,
                    viewModel = viewModel
                )
            }
        }
    }
}

/** 双标签行：天下宗门 / 玩家排行 */
@Composable
private fun LeaderboardTabRow(
    selectedTab: LeaderboardViewModel.LeaderboardTab,
    onSelectLocal: () -> Unit,
    onSelectCloud: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        LeaderboardTabItem(
            text = "天下宗门",
            selected = selectedTab == LeaderboardViewModel.LeaderboardTab.LOCAL,
            modifier = Modifier.weight(1f),
            onClick = onSelectLocal
        )
        LeaderboardTabItem(
            text = "玩家排行",
            selected = selectedTab == LeaderboardViewModel.LeaderboardTab.CLOUD,
            modifier = Modifier.weight(1f),
            onClick = onSelectCloud
        )
    }
}

@Composable
private fun LeaderboardTabItem(
    text: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Text(
        text = text,
        fontSize = 14.sp,
        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        color = Color.Black,
        textAlign = TextAlign.Center,
        modifier = modifier
            .background(
                color = if (selected) TabActiveBg else Color.Transparent,
                shape = RoundedCornerShape(8.dp)
            )
            .padding(vertical = 6.dp)
            .clickableWithSound(onClick = onClick)
    )
}

/** 本地榜列表（天下宗门，战力降序） */
@Composable
private fun LocalLeaderboardList(entries: List<LocalLeaderboardEntry>) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
    ) {
        LeaderboardHeaderRow()
        entries.forEachIndexed { index, entry ->
            LeaderboardRow(
                rank = index + 1,
                name = entry.name,
                power = entry.power,
                nameSuffix = if (entry.isPlayer) "（我）" else null,
                highlight = entry.isPlayer
            )
        }
    }
}

/** 云端榜内容（四态分派） */
@Composable
private fun CloudLeaderboardContent(
    state: LeaderboardViewModel.CloudLeaderboardState,
    viewModel: LeaderboardViewModel
) {
    when (state) {
        is LeaderboardViewModel.CloudLeaderboardState.Idle,
        is LeaderboardViewModel.CloudLeaderboardState.Loading -> CenteredHint("加载中…")

        is LeaderboardViewModel.CloudLeaderboardState.NeedLogin -> NeedLoginContent(viewModel)
        is LeaderboardViewModel.CloudLeaderboardState.Empty -> CenteredHint("暂无玩家上榜")
        is LeaderboardViewModel.CloudLeaderboardState.Error -> ErrorContent(state.message, viewModel::retryCloud)

        is LeaderboardViewModel.CloudLeaderboardState.Success -> CloudLeaderboardList(state)
    }
}

/** 未登录引导：登录后即可参与玩家战力排行 */
@Composable
private fun NeedLoginContent(viewModel: LeaderboardViewModel) {
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "登录 TapTap 后即可参与玩家战力排行",
            fontSize = 13.sp,
            color = Color.Black,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(16.dp))
        GameButton(
            text = "去登录",
            onClick = {
                val activity = context.findActivity()
                if (activity == null) {
                    Toast.makeText(context, "暂无法拉起登录，请稍后再试", Toast.LENGTH_SHORT).show()
                } else {
                    viewModel.login(activity)
                }
            }
        )
    }
}

/** 错误 + 重试 */
@Composable
private fun ErrorContent(message: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = message,
            fontSize = 13.sp,
            color = Color.Black,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(16.dp))
        GameButton("重试", onClick = onRetry)
    }
}

/** 居中提示文案 */
@Composable
private fun CenteredHint(text: String) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = text,
            fontSize = 13.sp,
            color = Color.Black,
            textAlign = TextAlign.Center
        )
    }
}

/** 云端榜列表：我的排名卡片 + 榜单 */
@Composable
private fun CloudLeaderboardList(
    state: LeaderboardViewModel.CloudLeaderboardState.Success
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
    ) {
        state.myRanking?.let { my ->
            MyRankingCard(my)
            Spacer(modifier = Modifier.height(8.dp))
        }
        LeaderboardHeaderRow()
        state.entries.forEach { entry ->
            LeaderboardRow(
                rank = entry.rank,
                name = entry.name,
                power = entry.power,
                nameSuffix = if (entry.isMe) "（我）" else null,
                highlight = entry.isMe
            )
        }
    }
}

/** 我的排名卡片（名次/昵称/战力） */
@Composable
private fun MyRankingCard(my: LeaderboardEntry) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0x0A000000), RoundedCornerShape(8.dp))
            .padding(horizontal = ROW_H_PADDING, vertical = ROW_V_PADDING),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "我的排名",
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            color = Color.Black,
            modifier = Modifier.weight(RANK_COLUMN_WEIGHT)
        )
        Text(
            text = "第 ${my.rank} 名 · ${my.name}",
            fontSize = 13.sp,
            color = Color.Black,
            modifier = Modifier.weight(NAME_COLUMN_WEIGHT + POWER_COLUMN_WEIGHT)
        )
        Text(
            text = "${my.power}",
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            color = Color.Black,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(POWER_COLUMN_WEIGHT)
        )
    }
}

/** 表头行：名次 | 宗门/玩家 | 战力 */
@Composable
private fun LeaderboardHeaderRow() {
    LeaderboardRow(
        rank = 0,
        name = "名次",
        power = 0L,
        nameSuffix = "玩家",
        highlight = false,
        header = true
    )
}

/** 单行：名次 | 名称 | 战力 */
@Composable
private fun LeaderboardRow(
    rank: Int,
    name: String,
    power: Long,
    nameSuffix: String?,
    highlight: Boolean,
    header: Boolean = false
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = ROW_H_PADDING, vertical = ROW_V_PADDING),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = if (header) "名次" else "#$rank",
            fontSize = 13.sp,
            fontWeight = if (header || highlight) FontWeight.Bold else FontWeight.Normal,
            color = Color.Black,
            modifier = Modifier.weight(RANK_COLUMN_WEIGHT)
        )
        Text(
            text = if (header) "玩家" else name + (nameSuffix ?: ""),
            fontSize = 13.sp,
            fontWeight = if (header || highlight) FontWeight.Bold else FontWeight.Normal,
            color = Color.Black,
            modifier = Modifier.weight(NAME_COLUMN_WEIGHT)
        )
        Text(
            text = if (header) "战力" else "$power",
            fontSize = 13.sp,
            fontWeight = if (header || highlight) FontWeight.Bold else FontWeight.Normal,
            color = Color.Black,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(POWER_COLUMN_WEIGHT)
        )
    }
}
