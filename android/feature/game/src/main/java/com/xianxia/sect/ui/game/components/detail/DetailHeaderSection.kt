package com.xianxia.sect.ui.game.components.detail

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xianxia.sect.feature.game.R
import com.xianxia.sect.core.model.DiscipleAggregate
import com.xianxia.sect.core.util.PortraitPool
import com.xianxia.sect.core.util.isFollowed
import com.xianxia.sect.ui.game.GameViewModel
import com.xianxia.sect.ui.game.LocalDismissDropdown

@Composable
fun DetailRightPanel(
    disciple: DiscipleAggregate,
    allDisciples: List<DiscipleAggregate>,
    localDiscipleType: String,
    showDiscipleTypeDropdown: Boolean,
    onDiscipleTypeDropdownChange: (Boolean) -> Unit,
    onLocalDiscipleTypeChange: (String) -> Unit,
    onShowRelations: () -> Unit,
    onShowStorageBag: () -> Unit,
    onShowExpelConfirm: () -> Unit,
    onShowApprentice: () -> Unit,
    onNavigateToDisciple: ((DiscipleAggregate) -> Unit)?,
    viewModel: GameViewModel?
) {
    val context = LocalContext.current
    val dismissDropdown = LocalDismissDropdown.current

    Column(
        modifier = Modifier.fillMaxHeight().fillMaxWidth(0.4f).padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        val portraitResId = PortraitPool.getResourceId(context, disciple.portraitRes)
        Image(
            painter = if (portraitResId != 0) painterResource(id = portraitResId)
            else painterResource(id = R.drawable.disciple_portrait),
            contentDescription = null,
            modifier = Modifier.weight(2f).fillMaxWidth().padding(horizontal = 4.dp),
            contentScale = ContentScale.Fit
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(disciple.name, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.Black)
        Text(disciple.realmName, fontSize = 14.sp, color = Color.Black)
        Text(disciple.spiritRootName, fontSize = 12.sp, color = Color(0xFF00695C))
        Spacer(modifier = Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val btnColor = if (localDiscipleType == "inner") Color(0xFF9C27B0) else Color(0xFF7B1FA2)
            val btnShape = if (showDiscipleTypeDropdown)
                RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp)
            else
                RoundedCornerShape(4.dp)
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    modifier = Modifier
                        .clip(btnShape)
                        .background(btnColor)
                        .clickable { onDiscipleTypeDropdownChange(!showDiscipleTypeDropdown) }
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        if (localDiscipleType == "inner") "内门弟子" else "外门弟子",
                        fontSize = 10.sp,
                        color = Color.White
                    )
                }
                if (showDiscipleTypeDropdown) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(bottomStart = 4.dp, bottomEnd = 4.dp))
                            .background(Color.White)
                            .border(1.dp, btnColor)
                            .clickable {
                                onDiscipleTypeDropdownChange(false)
                                val newType = if (localDiscipleType == "outer") "inner" else "outer"
                                onLocalDiscipleTypeChange(newType)
                                viewModel?.changeDiscipleType(disciple.id, newType)
                            }
                            .padding(horizontal = 6.dp, vertical = 1.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            if (localDiscipleType == "outer") "内门弟子" else "外门弟子",
                            fontSize = 10.sp,
                            color = Color.Black
                        )
                    }
                }
            }
            Box(
                modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(Color(0xFF4CAF50))
                    .clickable { dismissDropdown(); onShowRelations() }.padding(horizontal = 6.dp, vertical = 2.dp)
            ) { Text("关系", fontSize = 10.sp, color = Color.White) }
            Box(
                modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(Color(0xFF2196F3))
                    .clickable { dismissDropdown(); onShowStorageBag() }.padding(horizontal = 6.dp, vertical = 2.dp)
            ) { Text("储物袋", fontSize = 10.sp, color = Color.White) }
            Box(
                modifier = Modifier.clip(RoundedCornerShape(4.dp))
                    .background(if (disciple.isFollowed) Color(0xFFFFD700) else Color.Black)
                    .clickable { dismissDropdown(); viewModel?.toggleFollowDisciple(disciple.id) }
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) { Text(if (disciple.isFollowed) "已关注" else "关注", fontSize = 10.sp, color = Color.White) }
            Box(
                modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(Color(0xFFE74C3C))
                    .clickable { dismissDropdown(); onShowExpelConfirm() }.padding(horizontal = 6.dp, vertical = 2.dp)
            ) { Text("驱逐", fontSize = 10.sp, color = Color.White) }
            // 拜师按钮：已有师父时灰色禁用显示"已拜师"；师徒关系永久，仅一方死亡解绑
            val hasMaster = disciple.masterId != null
            Box(
                modifier = Modifier.clip(RoundedCornerShape(4.dp))
                    .background(if (hasMaster) Color(0xFF9E9E9E) else Color(0xFF8D6E63))
                    .clickable(enabled = !hasMaster) { dismissDropdown(); onShowApprentice() }
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) { Text(if (hasMaster) "已拜师" else "拜师", fontSize = 10.sp, color = Color.White) }
        }
        Spacer(modifier = Modifier.height(8.dp))
        // prev/next navigation at bottom
        val currentIndex = allDisciples.indexOfFirst { it.id == disciple.id }
        val hasPrev = currentIndex > 0
        val hasNext = currentIndex >= 0 && currentIndex < allDisciples.size - 1

        if (hasPrev || hasNext) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                if (hasPrev && onNavigateToDisciple != null) {
                    Box(
                        modifier = Modifier.size(28.dp).clip(CircleShape).background(Color(0x99000000))
                            .clickable { dismissDropdown(); onNavigateToDisciple(allDisciples[currentIndex - 1]) },
                        contentAlignment = Alignment.Center
                    ) { Text("‹", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White) }
                }
                Spacer(modifier = Modifier.width(8.dp))
                if (hasNext && onNavigateToDisciple != null) {
                    Box(
                        modifier = Modifier.size(28.dp).clip(CircleShape).background(Color(0x99000000))
                            .clickable { dismissDropdown(); onNavigateToDisciple(allDisciples[currentIndex + 1]) },
                        contentAlignment = Alignment.Center
                    ) { Text("›", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White) }
                }
            }
        }
        Spacer(modifier = Modifier.weight(0.5f))
    }
}
