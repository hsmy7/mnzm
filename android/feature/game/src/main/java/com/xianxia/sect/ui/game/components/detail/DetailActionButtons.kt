package com.xianxia.sect.ui.game.components.detail

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xianxia.sect.core.model.DiscipleAggregate
import com.xianxia.sect.core.model.Talent
import com.xianxia.sect.ui.components.DialogDefaults
import com.xianxia.sect.ui.components.DialogMode
import com.xianxia.sect.ui.components.UnifiedGameDialog
import com.xianxia.sect.ui.components.getTalentRarityColor
import com.xianxia.sect.ui.game.LocalDismissDropdown
import com.xianxia.sect.ui.theme.GameColors

@Composable
fun TalentsSection(
    talents: List<Talent>,
    statusData: Map<String, String> = emptyMap(),
    onTalentClick: (Talent) -> Unit = {}
) {
    val dismissDropdown = LocalDismissDropdown.current

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "天赋",
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = Color.Black
        )

        if (talents.isEmpty()) {
            Text(
                text = "无天赋",
                fontSize = 12.sp,
                color = Color.Black
            )
        } else {
            talents.chunked(5).forEach { rowTalents ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    rowTalents.forEach { talent ->
                        val rarityColor = getTalentRarityColor(talent.rarity)

                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(4.dp))
                                .border(1.dp, rarityColor, RoundedCornerShape(4.dp))
                                .clickable { dismissDropdown(); onTalentClick(talent) }
                                .padding(vertical = 3.dp, horizontal = 4.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = talent.name,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = rarityColor,
                                maxLines = 1
                            )
                        }
                    }
                    repeat(5 - rowTalents.size) {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
fun RelationsDialog(
    disciple: DiscipleAggregate,
    allDisciples: List<DiscipleAggregate>,
    onDismiss: () -> Unit
) {
    val discipleMap = remember(allDisciples) { allDisciples.associateBy { it.id } }
    val partner = remember(disciple.partnerId, allDisciples) {
        disciple.partnerId?.let { id -> discipleMap[id] }
    }

    val parent1 = remember(disciple.parentId1, allDisciples) {
        disciple.parentId1?.let { id -> discipleMap[id] }
    }

    val parent2 = remember(disciple.parentId2, allDisciples) {
        disciple.parentId2?.let { id -> discipleMap[id] }
    }

    val children = remember(disciple.id, allDisciples) {
        allDisciples.filter { it.parentId1 == disciple.id || it.parentId2 == disciple.id }
    }

    val siblings = remember(disciple.parentId1, disciple.parentId2, allDisciples) {
        if (disciple.parentId1 == null && disciple.parentId2 == null) {
            emptyList()
        } else {
            allDisciples.filter {
                it.id != disciple.id &&
                (it.parentId1 == disciple.parentId1 || it.parentId2 == disciple.parentId2 ||
                 it.parentId1 == disciple.parentId2 || it.parentId2 == disciple.parentId1)
            }
        }
    }

    UnifiedGameDialog(
        onDismissRequest = onDismiss,
        title = "关系",
        mode = DialogMode.Half,
        scrollableContent = false
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Spacer(modifier = Modifier.height(12.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = DialogDefaults.CommonMaxHeight)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (parent1 != null || parent2 != null) {
                    RelationCategory("父母") {
                        parent1?.let { RelationItem("父亲", it) }
                        parent2?.let { RelationItem("母亲", it) }
                    }
                }

                if (partner != null) {
                    RelationCategory("道侣") {
                        RelationItem("道侣", partner)
                    }
                }

                if (children.isNotEmpty()) {
                    RelationCategory("子嗣") {
                        children.forEach { child ->
                            val relation = if (child.gender == "male") "子" else "女"
                            RelationItem(relation, child)
                        }
                    }
                }

                if (siblings.isNotEmpty()) {
                    RelationCategory("兄弟姐妹") {
                        siblings.forEach { sibling ->
                            val relation = if (sibling.gender == "male") "兄弟" else "姐妹"
                            RelationItem(relation, sibling)
                        }
                    }
                }

                if (parent1 == null && parent2 == null && partner == null && children.isEmpty() && siblings.isEmpty()) {
                    Text(
                        text = "无关系",
                        fontSize = 12.sp,
                        color = Color.Black
                    )
                }
            }
        }
    }
}

@Composable
fun RelationCategory(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = title,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = Color.Black
        )
        content()
    }
}

@Composable
fun RelationItem(relation: String, disciple: DiscipleAggregate) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            .border(1.dp, GameColors.Border, RoundedCornerShape(4.dp))
            .padding(8.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = relation,
            fontSize = 12.sp,
            color = Color.Black
        )
        Text(
            text = disciple.name,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = Color.Black
        )
    }
}
